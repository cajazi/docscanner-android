package com.dev.docscannerpdf.process

import android.content.Context
import android.net.Uri
import com.dev.docscannerpdf.network.CreateBackendDocumentRequest
import com.dev.docscannerpdf.network.LinkUploadedImageToPageRequest
import com.dev.docscannerpdf.network.NetworkResult
import com.dev.docscannerpdf.network.ProcessJobStatus
import com.dev.docscannerpdf.network.ProcessPageRequest
import com.dev.docscannerpdf.repository.DocScannerRemoteRepository
import com.dev.docscannerpdf.upload.ImageUploadPreparer
import com.dev.docscannerpdf.upload.UploadProgress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.delay

class ProcessDocumentUseCase(
    private val repository: DocScannerRemoteRepository = DocScannerRemoteRepository()
) {
    suspend fun processCapturedImage(
        context: Context,
        imageUri: Uri,
        title: String = "Android Upload",
        onState: (ProcessDocumentUiState) -> Unit = {}
    ): NetworkResult<ProcessDocumentResult> {
        onState(ProcessDocumentUiState.Uploading())
        val documentResult = repository.createDocument(CreateBackendDocumentRequest(title = title))
        val document = when (documentResult) {
            is NetworkResult.Success -> documentResult.data
            is NetworkResult.Error -> return documentResult.asWorkflowError("Document creation failed", onState)
            is NetworkResult.Exception -> return documentResult.asWorkflowError("Document creation failed", onState)
        }

        val uploadPart = ImageUploadPreparer.createMultipartPart(
            context = context,
            uri = imageUri,
            progressListener = { progress: UploadProgress ->
                onState(ProcessDocumentUiState.Uploading(progress.fraction))
            }
        )
        val uploadResult = repository.uploadImage(uploadPart)
        val upload = when (uploadResult) {
            is NetworkResult.Success -> uploadResult.data
            is NetworkResult.Error -> return uploadResult.asWorkflowError("Upload failed", onState)
            is NetworkResult.Exception -> return uploadResult.asWorkflowError("Upload failed", onState)
        }

        onState(ProcessDocumentUiState.CreatingPage)
        val pageResult = repository.linkUploadedImageToPage(
            documentId = document.id,
            request = LinkUploadedImageToPageRequest(
                storagePath = upload.storagePath
            )
        )
        val page = when (pageResult) {
            is NetworkResult.Success -> pageResult.data
            is NetworkResult.Error -> return pageResult.asWorkflowError("Page creation failed", onState)
            is NetworkResult.Exception -> return pageResult.asWorkflowError("Page creation failed", onState)
        }

        onState(ProcessDocumentUiState.Processing)
        val processingStartedAt = utcTimestamp()
        val processResult = repository.processPage(
            documentId = document.id,
            pageId = page.id,
            request = ProcessPageRequest()
        )
        val process = when (processResult) {
            is NetworkResult.Success -> processResult.data
            is NetworkResult.Error -> return processResult.asWorkflowError("Processing failed", onState)
            is NetworkResult.Exception -> return processResult.asWorkflowError("Processing failed", onState)
        }

        val result = ProcessDocumentResult(
            documentId = document.id,
            pageId = page.id,
            processJobId = process.processJobId ?: process.pipelineId ?: process.outputUrl ?: process.status ?: "accepted",
            processingStartedAt = process.processingStartedAt ?: processingStartedAt,
            latestJobStatus = process.toProcessJobStatus(
                fallbackJobId = process.processJobId ?: process.pipelineId ?: "accepted",
                fallbackUpdatedAt = processingStartedAt
            )
        )
        onState(ProcessDocumentUiState.Success(result))
        return NetworkResult.Success(result)
    }

    suspend fun processCapturedImageAndPoll(
        context: Context,
        imageUri: Uri,
        title: String = "Android Upload",
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        maxAttempts: Int = DEFAULT_MAX_POLL_ATTEMPTS,
        onState: (ProcessDocumentUiState) -> Unit = {}
    ): NetworkResult<ProcessDocumentResult> {
        var startedResult: ProcessDocumentResult? = null
        val processResult = processCapturedImage(
            context = context,
            imageUri = imageUri,
            title = title,
            onState = { state ->
                if (state is ProcessDocumentUiState.Success) {
                    startedResult = state.result
                } else {
                    onState(state)
                }
            }
        )

        val started = when (processResult) {
            is NetworkResult.Success -> startedResult ?: processResult.data
            is NetworkResult.Error -> return processResult
            is NetworkResult.Exception -> return processResult
        }

        return when (
            val pollResult = pollProcessJobUntilTerminal(
                processJobId = started.processJobId,
                initialStatus = started.latestJobStatus,
                pollIntervalMs = pollIntervalMs,
                maxAttempts = maxAttempts,
                onState = onState
            )
        ) {
            is PollingResult.Completed -> {
                val imageResult = repository.resolveProcessedImageResult(pollResult.status)
                val result = started.copy(
                    latestJobStatus = pollResult.status,
                    imageResult = imageResult
                )
                if (imageResult is ProcessedImageResult.SuccessWithoutImage) {
                    onState(ProcessDocumentUiState.ProcessingCompletedNoImageUrl(imageResult.reason))
                }
                onState(ProcessDocumentUiState.Success(result))
                NetworkResult.Success(result)
            }
            is PollingResult.Failed -> {
                val imageResult = repository.resolveProcessedImageResult(pollResult.status)
                val message = (imageResult as? ProcessedImageResult.Error)?.message
                    ?: pollResult.status.errorMessage
                    ?: "Process job failed."
                onState(ProcessDocumentUiState.Error(message))
                NetworkResult.Success(
                    started.copy(
                        latestJobStatus = pollResult.status,
                        imageResult = imageResult
                    )
                )
            }
            is PollingResult.TimedOut -> {
                onState(
                    ProcessDocumentUiState.Timeout(
                        processJobId = started.processJobId,
                        latestStatus = pollResult.latestStatus,
                        message = "Process job polling timed out."
                    )
                )
                NetworkResult.Success(started.copy(latestJobStatus = pollResult.latestStatus))
            }
            is PollingResult.Error -> pollResult.result
        }
    }

    suspend fun pollProcessJobUntilTerminal(
        processJobId: String,
        initialStatus: ProcessJobStatus? = null,
        pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS,
        maxAttempts: Int = DEFAULT_MAX_POLL_ATTEMPTS,
        onState: (ProcessDocumentUiState) -> Unit = {},
        delayMillis: suspend (Long) -> Unit = { delay(it) }
    ): PollingResult {
        var latestStatus = initialStatus
        if (latestStatus != null) {
            onState(ProcessDocumentUiState.Polling(0, maxAttempts, latestStatus))
            if (latestStatus.isCompleted) return PollingResult.Completed(latestStatus)
            if (latestStatus.isFailed) return PollingResult.Failed(latestStatus)
        }

        repeat(maxAttempts) { index ->
            if (index > 0 || latestStatus != null) {
                delayMillis(pollIntervalMs)
            }

            val result = repository.pollProcessJob(processJobId)
            latestStatus = when (result) {
                is NetworkResult.Success -> result.data
                is NetworkResult.Error -> return PollingResult.Error(result)
                is NetworkResult.Exception -> return PollingResult.Error(result)
            }

            val status = latestStatus ?: return@repeat
            onState(ProcessDocumentUiState.Polling(index + 1, maxAttempts, status))
            if (status.isCompleted) return PollingResult.Completed(status)
            if (status.isFailed) return PollingResult.Failed(status)
        }

        return PollingResult.TimedOut(latestStatus)
    }

    sealed interface PollingResult {
        data class Completed(val status: ProcessJobStatus) : PollingResult
        data class Failed(val status: ProcessJobStatus) : PollingResult
        data class TimedOut(val latestStatus: ProcessJobStatus?) : PollingResult
        data class Error(val result: NetworkResult<Nothing>) : PollingResult
    }

    private fun <T> NetworkResult<T>.asWorkflowError(
        prefix: String,
        onState: (ProcessDocumentUiState) -> Unit
    ): NetworkResult<T> {
        onState(ProcessDocumentUiState.Error("$prefix: ${message()}"))
        return this
    }

    private fun NetworkResult<*>.message(): String {
        return when (this) {
            is NetworkResult.Success -> "Success"
            is NetworkResult.Error -> errorBody?.takeIf { it.isNotBlank() } ?: "HTTP $code $message"
            is NetworkResult.Exception -> throwable.message ?: throwable::class.java.simpleName
        }
    }

    private fun utcTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }

    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 1_500L
        const val DEFAULT_MAX_POLL_ATTEMPTS = 40
    }
}
