package com.dev.docscannerpdf.process

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
import com.dev.docscannerpdf.network.PdfExportJobResponse
import com.dev.docscannerpdf.network.ProcessJobStatus
import com.dev.docscannerpdf.network.ProcessPageRequest
import com.dev.docscannerpdf.network.ProcessPageResponse
import com.dev.docscannerpdf.network.UploadedImageDto
import com.dev.docscannerpdf.repository.DocScannerRemoteRepository
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class ProcessWorkflowNetworkTest {
    @Test
    fun uploadResultParsing_readsNestedUploadResult() {
        val payload = """
            {
              "storagePath": "file:///tmp/scan.jpg",
              "originalFilename": "scan.jpg",
              "mimeType": "image/jpeg",
              "sizeBytes": 1024,
              "ignored": true
            }
        """.trimIndent()

        val response = NetworkClient.json.decodeFromString<UploadedImageDto>(payload)

        assertEquals("file:///tmp/scan.jpg", response.storagePath)
        assertEquals("scan.jpg", response.originalFilename)
        assertEquals("image/jpeg", response.mimeType)
        assertEquals(1024L, response.sizeBytes)
    }

    @Test
    fun processResponseParsing_acceptsPipelineIdWithoutStatus() {
        val payload = """
            {
              "pipelineId": "pipeline_1",
              "documentId": "doc_1",
              "pageId": "page_1",
              "processingStartedAt": "2026-06-25T12:00:00.000Z"
            }
        """.trimIndent()

        val response = NetworkClient.json.decodeFromString<ProcessPageResponse>(payload)

        assertEquals("pipeline_1", response.pipelineId)
        assertEquals("doc_1", response.documentId)
        assertEquals("page_1", response.pageId)
        assertEquals("2026-06-25T12:00:00.000Z", response.processingStartedAt)
    }

    @Test
    fun repositoryUploadImage_mapsHttpFailureToNetworkResultError() = runBlocking {
        val repository = DocScannerRemoteRepository(
            apiService = FakeProcessApiService(
                uploadResponse = Response.error(413, "too large".toResponseBody())
            )
        )
        val part = MultipartBody.Part.createFormData(
            "image",
            "scan.jpg",
            "image".toRequestBody()
        )

        val result = repository.uploadImage(part)

        assertTrue(result is NetworkResult.Error)
        assertEquals(413, (result as NetworkResult.Error).code)
        assertEquals("too large", result.errorBody)
    }

    @Test
    fun repositoryLinkUploadedImageToPage_mapsHttpFailureToNetworkResultError() = runBlocking {
        val repository = DocScannerRemoteRepository(
            apiService = FakeProcessApiService(
                pageResponse = Response.error(400, "missing upload".toResponseBody())
            )
        )

        val result = repository.linkUploadedImageToPage(
            documentId = "doc_1",
            request = LinkUploadedImageToPageRequest(storagePath = "")
        )

        assertTrue(result is NetworkResult.Error)
        assertEquals(400, (result as NetworkResult.Error).code)
        assertEquals("missing upload", result.errorBody)
    }

    @Test
    fun repositoryProcessPage_mapsExceptionToNetworkResultException() = runBlocking {
        val throwable = IllegalStateException("processor offline")
        val repository = DocScannerRemoteRepository(
            apiService = FakeProcessApiService(processThrowable = throwable)
        )

        val result = repository.processPage(
            documentId = "doc_1",
            pageId = "page_1",
            request = ProcessPageRequest()
        )

        assertTrue(result is NetworkResult.Exception)
        assertEquals(throwable, (result as NetworkResult.Exception).throwable)
    }

    @Test
    fun resolveProcessedImageResult_completedJobWithImageUrlMapsToSuccessWithImage() {
        val repository = DocScannerRemoteRepository(apiService = FakeProcessApiService())

        val result = repository.resolveProcessedImageResult(
            ProcessJobStatus(
                id = "pipeline_1",
                status = "COMPLETED",
                finalImageRole = "ENHANCED",
                enhancedImageUrl = "file:///tmp/enhanced.jpg"
            )
        )

        assertTrue(result is ProcessedImageResult.SuccessWithImage)
        assertEquals("file:///tmp/enhanced.jpg", (result as ProcessedImageResult.SuccessWithImage).url)
    }

    @Test
    fun resolveProcessedImageResult_completedJobWithoutImageUrlMapsToSuccessWithoutImage() {
        val repository = DocScannerRemoteRepository(apiService = FakeProcessApiService())

        val result = repository.resolveProcessedImageResult(
            ProcessJobStatus(
                id = "pipeline_1",
                status = "COMPLETED",
                finalImageRole = "ENHANCED"
            )
        )

        assertTrue(result is ProcessedImageResult.SuccessWithoutImage)
        assertEquals(
            "Processing completed, but no enhanced image URL is exposed by backend yet.",
            (result as ProcessedImageResult.SuccessWithoutImage).reason
        )
    }

    @Test
    fun resolveProcessedImageResult_failedJobRemainsError() {
        val repository = DocScannerRemoteRepository(apiService = FakeProcessApiService())

        val result = repository.resolveProcessedImageResult(
            ProcessJobStatus(
                id = "pipeline_1",
                status = "FAILED",
                errorMessage = "Enhancement failed"
            )
        )

        assertTrue(result is ProcessedImageResult.Error)
        assertEquals("Enhancement failed", (result as ProcessedImageResult.Error).message)
    }
}

private class FakeProcessApiService(
    private val uploadResponse: Response<UploadedImageDto> = Response.success(
        UploadedImageDto(storagePath = "file:///tmp/scan.jpg", mimeType = "image/jpeg")
    ),
    private val pageResponse: Response<BackendDocumentPageDto> = Response.success(
        BackendDocumentPageDto(
            id = "page_1",
            documentId = "doc_1",
            pageNumber = 1,
            originalImageUrl = "file:///tmp/scan.jpg"
        )
    ),
    private val processThrowable: Throwable? = null
) : DocScannerApiService {
    override suspend fun getHealth(): Response<HealthResponse> {
        return Response.success(HealthResponse(status = "ok"))
    }

    override suspend fun getEngineCapabilities(): Response<EngineCapabilitiesResponse> {
        return Response.success(EngineCapabilitiesResponse())
    }

    override suspend fun createDocument(
        request: CreateBackendDocumentRequest
    ): Response<BackendDocumentDto> {
        return Response.success(
            BackendDocumentDto(
                id = "doc_1",
                title = request.title,
                sourceType = request.sourceType
            )
        )
    }

    override suspend fun uploadImage(
        image: MultipartBody.Part
    ): Response<UploadedImageDto> {
        return uploadResponse
    }

    override suspend fun linkUploadedImageToPage(
        documentId: String,
        request: LinkUploadedImageToPageRequest
    ): Response<BackendDocumentPageDto> {
        return pageResponse
    }

    override suspend fun processPage(
        documentId: String,
        pageId: String,
        request: ProcessPageRequest
    ): Response<ProcessPageResponse> {
        processThrowable?.let { throw it }
        return Response.success(
            ProcessPageResponse(
                documentId = documentId,
                pageId = pageId,
                pipelineId = "pipeline_1",
                processingStartedAt = "2026-06-25T12:00:00.000Z"
            )
        )
    }

    override suspend fun getProcessJob(jobId: String): Response<ProcessJobStatus> {
        return Response.success(
            ProcessJobStatus(
                id = jobId,
                status = "COMPLETED",
                completedStages = listOf("ENHANCEMENT"),
                finalImageRole = "ENHANCED",
                searchableReady = true,
                updatedAt = "2026-06-25T12:00:01.000Z"
            )
        )
    }

    override suspend fun createPdfExportJob(
        documentId: String,
        request: CreatePdfExportJobRequest
    ): Response<PdfExportJobResponse> {
        return Response.success(PdfExportJobResponse(jobId = "pdf_1", status = "PENDING"))
    }

    override suspend fun getPdfExportJob(jobId: String): Response<PdfExportJobResponse> {
        return Response.success(PdfExportJobResponse(jobId = jobId, status = "PENDING"))
    }
}
