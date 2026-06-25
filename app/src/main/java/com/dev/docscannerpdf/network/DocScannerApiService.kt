package com.dev.docscannerpdf.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

interface DocScannerApiService {
    @GET("health")
    suspend fun getHealth(): Response<HealthResponse>

    @GET("engine/capabilities")
    suspend fun getEngineCapabilities(): Response<EngineCapabilitiesResponse>

    @POST("engine/documents")
    suspend fun createDocument(
        @Body request: CreateBackendDocumentRequest
    ): Response<BackendDocumentDto>

    @Multipart
    @POST("engine/uploads/images")
    suspend fun uploadImage(
        @Part image: MultipartBody.Part
    ): Response<UploadedImageDto>

    @POST("engine/documents/{documentId}/pages")
    suspend fun linkUploadedImageToPage(
        @Path("documentId") documentId: String,
        @Body request: LinkUploadedImageToPageRequest
    ): Response<BackendDocumentPageDto>

    @POST("engine/documents/{documentId}/pages/{pageId}/process")
    suspend fun processPage(
        @Path("documentId") documentId: String,
        @Path("pageId") pageId: String,
        @Body request: ProcessPageRequest
    ): Response<ProcessPageResponse>

    @GET("engine/process-jobs/{jobId}")
    suspend fun getProcessJob(
        @Path("jobId") jobId: String
    ): Response<ProcessJobStatus>

    @POST("engine/documents/{documentId}/pdf-export-jobs")
    suspend fun createPdfExportJob(
        @Path("documentId") documentId: String,
        @Body request: CreatePdfExportJobRequest
    ): Response<PdfExportJobResponse>

    @GET("engine/pdf-export-jobs/{jobId}")
    suspend fun getPdfExportJob(
        @Path("jobId") jobId: String
    ): Response<PdfExportJobResponse>
}

@Serializable
data class HealthResponse(
    val status: String,
    val service: String? = null,
    val version: String? = null
)

@Serializable
data class EngineCapabilitiesResponse(
    val capabilities: List<String> = emptyList(),
    val formats: List<String> = emptyList(),
    val maxPages: Int? = null
)

@Serializable
data class CreateBackendDocumentRequest(
    val title: String,
    val sourceType: String = "ANDROID_UPLOAD",
    val mimeType: String? = null,
    val originalUrl: String? = null
)

@Serializable
data class BackendDocumentDto(
    val id: String,
    val title: String,
    val status: String? = null,
    val sourceType: String? = null,
    val mimeType: String? = null,
    val originalUrl: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class UploadedImageDto(
    val id: String? = null,
    val storagePath: String,
    val url: String? = null,
    val fileName: String? = null,
    val originalFilename: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null
)

@Serializable
data class LinkUploadedImageToPageRequest(
    val storagePath: String,
    val type: String = "ORIGINAL"
)

@Serializable
data class BackendDocumentPageDto(
    val id: String,
    val documentId: String,
    val pageNumber: Int,
    val originalImageUrl: String? = null,
    val croppedImageUrl: String? = null,
    val enhancedImageUrl: String? = null,
    val processingStatus: String? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

@Serializable
data class ProcessPageRequest(
    val operations: List<String> = emptyList(),
    val options: Map<String, String> = emptyMap()
)

@Serializable
data class ProcessPageResponse(
    val documentId: String? = null,
    val pageId: String? = null,
    val processJobId: String? = null,
    val pipelineId: String? = null,
    val processingStartedAt: String? = null,
    val status: String? = null,
    val completedStages: List<String> = emptyList(),
    val failedStages: List<ProcessStageFailure> = emptyList(),
    val fallbackStages: List<String> = emptyList(),
    val finalImageRole: String? = null,
    val searchableReady: Boolean? = null,
    val errorMessage: String? = null,
    val updatedAt: String? = null,
    val outputUrl: String? = null
) {
    fun toProcessJobStatus(
        fallbackJobId: String,
        fallbackUpdatedAt: String
    ): ProcessJobStatus {
        return ProcessJobStatus(
            id = processJobId ?: pipelineId ?: fallbackJobId,
            status = status ?: if (failedStages.isEmpty()) "COMPLETED" else "FAILED",
            completedStages = completedStages,
            failedStages = failedStages,
            fallbackStages = fallbackStages,
            finalImageRole = finalImageRole,
            searchableReady = searchableReady ?: false,
            errorMessage = errorMessage,
            updatedAt = updatedAt ?: processingStartedAt ?: fallbackUpdatedAt
        )
    }
}

@Serializable
data class ProcessJobStatus(
    val id: String,
    val status: String,
    val completedStages: List<String> = emptyList(),
    val failedStages: List<ProcessStageFailure> = emptyList(),
    val fallbackStages: List<String> = emptyList(),
    val finalImageRole: String? = null,
    val searchableReady: Boolean = false,
    val errorMessage: String? = null,
    val updatedAt: String? = null,
    val enhancedImageUrl: String? = null,
    val croppedImageUrl: String? = null,
    val originalImageUrl: String? = null
) {
    val isCompleted: Boolean
        get() = status.equals("COMPLETED", ignoreCase = true)

    val isFailed: Boolean
        get() = status.equals("FAILED", ignoreCase = true)
}

@Serializable
data class ProcessStageFailure(
    val stage: String,
    val errorMessage: String? = null
)

@Serializable
data class CreatePdfExportJobRequest(
    val pageIds: List<String> = emptyList(),
    val options: PdfExportOptions = PdfExportOptions()
)

@Serializable
data class PdfExportOptions(
    val fileName: String? = null,
    val includeOcrText: Boolean = false
)

@Serializable
data class PdfExportJobResponse(
    val jobId: String,
    val documentId: String? = null,
    val status: String,
    @SerialName("pdfUrl")
    val pdfUrl: String? = null,
    val error: String? = null
)
