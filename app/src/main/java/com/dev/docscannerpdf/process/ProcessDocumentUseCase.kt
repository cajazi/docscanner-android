package com.dev.docscannerpdf.process

import android.content.Context
import android.net.Uri
import com.dev.docscannerpdf.network.CreateBackendDocumentRequest
import com.dev.docscannerpdf.network.LinkUploadedImageToPageRequest
import com.dev.docscannerpdf.network.NetworkResult
import com.dev.docscannerpdf.network.ProcessPageRequest
import com.dev.docscannerpdf.repository.DocScannerRemoteRepository
import com.dev.docscannerpdf.upload.ImageUploadPreparer
import com.dev.docscannerpdf.upload.UploadProgress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
            is NetworkResult.Success -> documentResult.data.document
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
            is NetworkResult.Success -> uploadResult.data.upload
            is NetworkResult.Error -> return uploadResult.asWorkflowError("Upload failed", onState)
            is NetworkResult.Exception -> return uploadResult.asWorkflowError("Upload failed", onState)
        }

        onState(ProcessDocumentUiState.CreatingPage)
        val pageResult = repository.linkUploadedImageToPage(
            documentId = document.id,
            request = LinkUploadedImageToPageRequest(
                uploadUrl = upload.url,
                pageNumber = 1,
                mimeType = upload.mimeType
            )
        )
        val page = when (pageResult) {
            is NetworkResult.Success -> pageResult.data.page
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
            processingStartedAt = process.processingStartedAt ?: processingStartedAt
        )
        onState(ProcessDocumentUiState.Success(result))
        return NetworkResult.Success(result)
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
}
