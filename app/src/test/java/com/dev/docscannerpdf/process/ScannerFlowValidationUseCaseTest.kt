package com.dev.docscannerpdf.process

import com.dev.docscannerpdf.network.BackendDocumentDto
import com.dev.docscannerpdf.network.BackendDocumentPageDto
import com.dev.docscannerpdf.network.CreateBackendDocumentRequest
import com.dev.docscannerpdf.network.CreatePdfExportJobRequest
import com.dev.docscannerpdf.network.DocScannerApiService
import com.dev.docscannerpdf.network.EngineCapabilitiesResponse
import com.dev.docscannerpdf.network.HealthResponse
import com.dev.docscannerpdf.network.LinkUploadedImageToPageRequest
import com.dev.docscannerpdf.network.NetworkResult
import com.dev.docscannerpdf.network.PageOcrResultDto
import com.dev.docscannerpdf.network.PdfExportJobResponse
import com.dev.docscannerpdf.network.ProcessJobStatus
import com.dev.docscannerpdf.network.ProcessPageRequest
import com.dev.docscannerpdf.network.ProcessPageResponse
import com.dev.docscannerpdf.network.UploadedImageDto
import com.dev.docscannerpdf.repository.DocScannerRemoteRepository
import kotlinx.coroutines.runBlocking
import okhttp3.MultipartBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class ScannerFlowValidationUseCaseTest {

    private fun useCaseWith(ocrResponse: Response<PageOcrResultDto>): ScannerFlowValidationUseCase {
        val repository = DocScannerRemoteRepository(
            apiService = FakeValidationApiService(ocrResponse = ocrResponse)
        )
        return ScannerFlowValidationUseCase(repository = repository)
    }

    private fun completedResult(imageResult: ProcessedImageResult): ProcessDocumentResult {
        return ProcessDocumentResult(
            documentId = "doc_1",
            pageId = "page_1",
            processJobId = "job_1",
            processingStartedAt = "2026-06-25T12:00:00.000Z",
            latestJobStatus = ProcessJobStatus(id = "job_1", status = "COMPLETED"),
            imageResult = imageResult
        )
    }

    @Test
    fun successWithProcessedImageAndOcrText() = runBlocking {
        val useCase = useCaseWith(
            Response.success(
                PageOcrResultDto(text = "Invoice total: 42.00", status = "COMPLETED")
            )
        )

        val state = useCase.resolveAfterProcessing(
            base = ScannerFlowValidationState(stage = ScannerFlowStage.POLLING_COMPLETED),
            processResult = NetworkResult.Success(
                completedResult(ProcessedImageResult.SuccessWithImage("https://api.test/enhanced.jpg"))
            )
        )

        assertEquals(ScannerFlowStage.COMPLETED, state.stage)
        assertEquals(ScannerOcrStatus.AVAILABLE, state.ocrStatus)
        assertEquals("Invoice total: 42.00", state.ocrTextPreview)
        assertEquals("https://api.test/enhanced.jpg", state.processedImageUrl)
        assertEquals("doc_1", state.documentId)
        assertEquals("page_1", state.pageId)
        assertEquals("job_1", state.processJobId)
        assertNull(state.failureReason)
    }

    @Test
    fun successWithImageButEmptyOcr() = runBlocking {
        val useCase = useCaseWith(
            Response.success(PageOcrResultDto(text = "   ", status = "COMPLETED"))
        )

        val state = useCase.resolveAfterProcessing(
            base = ScannerFlowValidationState(stage = ScannerFlowStage.POLLING_COMPLETED),
            processResult = NetworkResult.Success(
                completedResult(ProcessedImageResult.SuccessWithImage("https://api.test/enhanced.jpg"))
            )
        )

        assertEquals(ScannerFlowStage.COMPLETED, state.stage)
        assertEquals(ScannerOcrStatus.EMPTY, state.ocrStatus)
        assertNull(state.ocrTextPreview)
        assertEquals("https://api.test/enhanced.jpg", state.processedImageUrl)
        assertNull(state.failureReason)
    }

    @Test
    fun processSuccessButOcrFetchFailureBecomesError() = runBlocking {
        val useCase = useCaseWith(Response.error(500, "ocr unavailable".toResponseBody()))

        val state = useCase.resolveAfterProcessing(
            base = ScannerFlowValidationState(stage = ScannerFlowStage.POLLING_COMPLETED),
            processResult = NetworkResult.Success(
                completedResult(ProcessedImageResult.SuccessWithImage("https://api.test/enhanced.jpg"))
            )
        )

        assertEquals(ScannerFlowStage.ERROR, state.stage)
        assertEquals(ScannerOcrStatus.FAILED, state.ocrStatus)
        assertEquals("ocr unavailable", state.failureReason)
        // The processed image that was genuinely resolved stays visible alongside the error.
        assertEquals("https://api.test/enhanced.jpg", state.processedImageUrl)
    }

    @Test
    fun uploadOrProcessFailureRemainsError() = runBlocking {
        val useCase = useCaseWith(
            Response.success(PageOcrResultDto(text = "should never be fetched"))
        )

        val state = useCase.resolveAfterProcessing(
            base = ScannerFlowValidationState(stage = ScannerFlowStage.UPLOADING),
            processResult = NetworkResult.Error(code = 413, message = "Payload Too Large", errorBody = "too large")
        )

        assertEquals(ScannerFlowStage.ERROR, state.stage)
        assertEquals("too large", state.failureReason)
        // OCR was never attempted, so its status stays at the pre-fetch default.
        assertEquals(ScannerOcrStatus.PENDING, state.ocrStatus)
        assertNull(state.ocrTextPreview)
    }

    @Test
    fun backendProcessingFailureRemainsError() = runBlocking {
        val useCase = useCaseWith(
            Response.success(PageOcrResultDto(text = "should never be fetched"))
        )

        val state = useCase.resolveAfterProcessing(
            base = ScannerFlowValidationState(stage = ScannerFlowStage.POLLING_COMPLETED),
            processResult = NetworkResult.Success(
                completedResult(ProcessedImageResult.Error("Enhancement failed"))
            )
        )

        assertEquals(ScannerFlowStage.ERROR, state.stage)
        assertEquals("Enhancement failed", state.failureReason)
        assertEquals(ScannerOcrStatus.PENDING, state.ocrStatus)
    }

    @Test
    fun terminalPollErrorIsNotOverriddenByOcr() = runBlocking {
        val useCase = useCaseWith(
            Response.success(PageOcrResultDto(text = "should never be fetched"))
        )

        val timedOut = ScannerFlowValidationState(
            stage = ScannerFlowStage.ERROR,
            statusMessage = "Process job polling timed out.",
            failureReason = "Process job polling timed out."
        )

        val state = useCase.resolveAfterProcessing(
            base = timedOut,
            processResult = NetworkResult.Success(
                completedResult(ProcessedImageResult.SuccessWithoutImage("no image"))
            )
        )

        assertEquals(timedOut, state)
    }

    @Test
    fun stateMappingIsDeterministic() {
        val base = ScannerFlowValidationState()

        val cases = mapOf(
            ProcessDocumentUiState.Idle to ScannerFlowStage.IDLE,
            ProcessDocumentUiState.Uploading(0.5f) to ScannerFlowStage.UPLOADING,
            ProcessDocumentUiState.CreatingPage to ScannerFlowStage.PAGE_CREATED,
            ProcessDocumentUiState.Processing to ScannerFlowStage.PROCESSING_STARTED,
            ProcessDocumentUiState.Polling(
                attempt = 2,
                maxAttempts = 40,
                latestStatus = ProcessJobStatus(id = "job_1", status = "PROCESSING")
            ) to ScannerFlowStage.POLLING,
            ProcessDocumentUiState.ProcessingCompletedNoImageUrl("no url") to ScannerFlowStage.POLLING_COMPLETED,
            ProcessDocumentUiState.Success(
                completedResult(ProcessedImageResult.SuccessWithImage("https://api.test/enhanced.jpg"))
            ) to ScannerFlowStage.POLLING_COMPLETED,
            ProcessDocumentUiState.Error("boom") to ScannerFlowStage.ERROR,
            ProcessDocumentUiState.Timeout("job_1", null, "timed out") to ScannerFlowStage.ERROR
        )

        for ((event, expectedStage) in cases) {
            val first = base.reduce(event)
            val second = base.reduce(event)
            assertEquals("stage for $event", expectedStage, first.stage)
            assertEquals("deterministic mapping for $event", first, second)
        }
    }
}

private class FakeValidationApiService(
    private val ocrResponse: Response<PageOcrResultDto>
) : DocScannerApiService {
    override suspend fun getHealth(): Response<HealthResponse> =
        Response.success(HealthResponse(status = "ok"))

    override suspend fun getEngineCapabilities(): Response<EngineCapabilitiesResponse> =
        Response.success(EngineCapabilitiesResponse())

    override suspend fun createDocument(
        request: CreateBackendDocumentRequest
    ): Response<BackendDocumentDto> =
        Response.success(BackendDocumentDto(id = "doc_1", title = request.title))

    override suspend fun uploadImage(
        image: MultipartBody.Part
    ): Response<UploadedImageDto> =
        Response.success(UploadedImageDto(storagePath = "file:///tmp/scan.jpg"))

    override suspend fun linkUploadedImageToPage(
        documentId: String,
        request: LinkUploadedImageToPageRequest
    ): Response<BackendDocumentPageDto> =
        Response.success(
            BackendDocumentPageDto(id = "page_1", documentId = documentId, pageNumber = 1)
        )

    override suspend fun processPage(
        documentId: String,
        pageId: String,
        request: ProcessPageRequest
    ): Response<ProcessPageResponse> =
        Response.success(ProcessPageResponse(documentId = documentId, pageId = pageId, pipelineId = "job_1"))

    override suspend fun getProcessJob(jobId: String): Response<ProcessJobStatus> =
        Response.success(ProcessJobStatus(id = jobId, status = "COMPLETED"))

    override suspend fun getPageOcr(
        documentId: String,
        pageId: String
    ): Response<PageOcrResultDto> = ocrResponse

    override suspend fun createPdfExportJob(
        documentId: String,
        request: CreatePdfExportJobRequest
    ): Response<PdfExportJobResponse> =
        Response.success(PdfExportJobResponse(jobId = "pdf_1", status = "PENDING"))

    override suspend fun getPdfExportJob(jobId: String): Response<PdfExportJobResponse> =
        Response.success(PdfExportJobResponse(jobId = jobId, status = "PENDING"))
}
