package com.dev.docscannerpdf.repository

import android.content.Context
import android.net.Uri
import com.dev.docscannerpdf.network.BackendDocumentDto
import com.dev.docscannerpdf.network.BackendDocumentPageDto
import com.dev.docscannerpdf.network.CreateBackendDocumentRequest
import com.dev.docscannerpdf.network.CreatePdfExportJobRequest
import com.dev.docscannerpdf.network.DocScannerApiService
import com.dev.docscannerpdf.network.EngineCapabilitiesResponse
import com.dev.docscannerpdf.network.HealthResponse
import com.dev.docscannerpdf.network.LinkUploadedImageToPageRequest
import com.dev.docscannerpdf.network.NetworkClient
import com.dev.docscannerpdf.network.NetworkResult
import com.dev.docscannerpdf.network.PageOcrResultDto
import com.dev.docscannerpdf.network.PdfExportJobResponse
import com.dev.docscannerpdf.network.ProcessJobStatus
import com.dev.docscannerpdf.network.ProcessPageRequest
import com.dev.docscannerpdf.network.ProcessPageResponse
import com.dev.docscannerpdf.network.UploadedImageDto
import com.dev.docscannerpdf.network.safeApiCall
import com.dev.docscannerpdf.process.ProcessDocumentResult
import com.dev.docscannerpdf.process.ProcessedImageResult
import com.dev.docscannerpdf.process.ScannerOcrResult
import com.dev.docscannerpdf.upload.ImageUploadPreparer
import com.dev.docscannerpdf.upload.UploadProgressListener
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import okhttp3.MultipartBody

class DocScannerRemoteRepository(
    private val apiService: DocScannerApiService = NetworkClient.createApiService()
) {
    suspend fun getHealth(): NetworkResult<HealthResponse> {
        return safeApiCall { apiService.getHealth() }
    }

    suspend fun getEngineCapabilities(): NetworkResult<EngineCapabilitiesResponse> {
        return safeApiCall { apiService.getEngineCapabilities() }
    }

    suspend fun createDocument(
        request: CreateBackendDocumentRequest
    ): NetworkResult<BackendDocumentDto> {
        return safeApiCall { apiService.createDocument(request) }
    }

    suspend fun uploadImage(
        image: MultipartBody.Part
    ): NetworkResult<UploadedImageDto> {
        return safeApiCall { apiService.uploadImage(image) }
    }

    suspend fun linkUploadedImageToPage(
        documentId: String,
        request: LinkUploadedImageToPageRequest
    ): NetworkResult<BackendDocumentPageDto> {
        return safeApiCall {
            apiService.linkUploadedImageToPage(
                documentId = documentId,
                request = request
            )
        }
    }

    suspend fun processCapturedImage(
        context: Context,
        imageUri: Uri,
        title: String = "Android Upload",
        progressListener: UploadProgressListener? = null
    ): NetworkResult<ProcessDocumentResult> {
        val documentResult = createDocument(CreateBackendDocumentRequest(title = title))
        val document = when (documentResult) {
            is NetworkResult.Success -> documentResult.data
            is NetworkResult.Error -> return documentResult
            is NetworkResult.Exception -> return documentResult
        }

        val uploadPart = ImageUploadPreparer.createMultipartPart(
            context = context,
            uri = imageUri,
            progressListener = progressListener
        )
        val uploadResult = uploadImage(uploadPart)
        val upload = when (uploadResult) {
            is NetworkResult.Success -> uploadResult.data
            is NetworkResult.Error -> return uploadResult
            is NetworkResult.Exception -> return uploadResult
        }

        val pageResult = linkUploadedImageToPage(
            documentId = document.id,
            request = LinkUploadedImageToPageRequest(
                storagePath = upload.storagePath
            )
        )
        val page = when (pageResult) {
            is NetworkResult.Success -> pageResult.data
            is NetworkResult.Error -> return pageResult
            is NetworkResult.Exception -> return pageResult
        }

        val processingStartedAt = utcTimestamp()
        val processResult = processPage(
            documentId = document.id,
            pageId = page.id,
            request = ProcessPageRequest()
        )
        val process = when (processResult) {
            is NetworkResult.Success -> processResult.data
            is NetworkResult.Error -> return processResult
            is NetworkResult.Exception -> return processResult
        }

        return NetworkResult.Success(
            ProcessDocumentResult(
                documentId = document.id,
                pageId = page.id,
                processJobId = process.processJobId ?: process.pipelineId ?: process.outputUrl ?: process.status ?: "accepted",
                processingStartedAt = process.processingStartedAt ?: processingStartedAt,
                latestJobStatus = process.toProcessJobStatus(
                    fallbackJobId = process.processJobId ?: process.pipelineId ?: "accepted",
                    fallbackUpdatedAt = processingStartedAt
                )
            )
        )
    }

    suspend fun processPage(
        documentId: String,
        pageId: String,
        request: ProcessPageRequest
    ): NetworkResult<ProcessPageResponse> {
        return safeApiCall {
            apiService.processPage(
                documentId = documentId,
                pageId = pageId,
                request = request
            )
        }
    }

    suspend fun pollProcessJob(jobId: String): NetworkResult<ProcessJobStatus> {
        return safeApiCall { apiService.getProcessJob(jobId) }
    }

    suspend fun getPageOcr(
        documentId: String,
        pageId: String
    ): NetworkResult<PageOcrResultDto> {
        return safeApiCall { apiService.getPageOcr(documentId = documentId, pageId = pageId) }
    }

    /**
     * Maps a backend OCR fetch into a [ScannerOcrResult] without fabricating text:
     * a transport/HTTP failure becomes [ScannerOcrResult.Failed], a successful response
     * with usable text becomes [ScannerOcrResult.Available], and a successful but blank
     * response becomes [ScannerOcrResult.Empty].
     */
    fun resolveOcrResult(result: NetworkResult<PageOcrResultDto>): ScannerOcrResult {
        return when (result) {
            is NetworkResult.Success -> {
                val text = result.data.resolvedText()
                if (text.isNullOrBlank()) {
                    ScannerOcrResult.Empty
                } else {
                    ScannerOcrResult.Available(text)
                }
            }
            is NetworkResult.Error -> ScannerOcrResult.Failed(
                result.errorBody?.takeIf { it.isNotBlank() }
                    ?: "OCR request failed: HTTP ${result.code} ${result.message}"
            )
            is NetworkResult.Exception -> ScannerOcrResult.Failed(
                result.throwable.message ?: "OCR request failed."
            )
        }
    }

    fun resolveProcessedImageResult(status: ProcessJobStatus): ProcessedImageResult {
        if (status.isFailed) {
            return ProcessedImageResult.Error(status.errorMessage ?: "Backend processing failed.")
        }

        val imageUrl = when (status.finalImageRole?.uppercase(Locale.US)) {
            "ENHANCED" -> status.enhancedImageUrl
            "CROPPED" -> status.croppedImageUrl
            "ORIGINAL" -> status.originalImageUrl
            else -> status.enhancedImageUrl ?: status.croppedImageUrl
        }

        return if (status.isCompleted && !imageUrl.isNullOrBlank()) {
            ProcessedImageResult.SuccessWithImage(imageUrl)
        } else {
            ProcessedImageResult.SuccessWithoutImage(
                "Processing completed, but no enhanced image URL is exposed by backend yet."
            )
        }
    }

    suspend fun createPdfExportJob(
        documentId: String,
        request: CreatePdfExportJobRequest
    ): NetworkResult<PdfExportJobResponse> {
        return safeApiCall {
            apiService.createPdfExportJob(
                documentId = documentId,
                request = request
            )
        }
    }

    suspend fun getPdfExportJob(jobId: String): NetworkResult<PdfExportJobResponse> {
        return safeApiCall { apiService.getPdfExportJob(jobId) }
    }

    private fun utcTimestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date())
    }
}
