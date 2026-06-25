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
    ): Response<CreateBackendDocumentResponse>

    @Multipart
    @POST("engine/uploads/images")
    suspend fun uploadImage(
        @Part image: MultipartBody.Part
    ): Response<UploadImageResponse>

    @POST("engine/documents/{documentId}/pages")
    suspend fun linkUploadedImageToPage(
        @Path("documentId") documentId: String,
        @Body request: LinkUploadedImageToPageRequest
    ): Response<LinkUploadedImageToPageResponse>

    @POST("engine/documents/{documentId}/pages/{pageId}/process")
    suspend fun processPage(
        @Path("documentId") documentId: String,
        @Path("pageId") pageId: String,
        @Body request: ProcessPageRequest
    ): Response<ProcessPageResponse>

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
data class CreateBackendDocumentResponse(
    val document: BackendDocumentDto
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
data class UploadImageResponse(
    val upload: UploadedImageDto
)

@Serializable
data class UploadedImageDto(
    val id: String? = null,
    val url: String,
    val fileName: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Long? = null
)

@Serializable
data class LinkUploadedImageToPageRequest(
    val uploadUrl: String,
    val pageNumber: Int? = null,
    val mimeType: String? = null
)

@Serializable
data class LinkUploadedImageToPageResponse(
    val page: BackendDocumentPageDto
)

@Serializable
data class BackendDocumentPageDto(
    val id: String,
    val documentId: String,
    val pageNumber: Int,
    val originalImageUrl: String? = null,
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
    val outputUrl: String? = null
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
