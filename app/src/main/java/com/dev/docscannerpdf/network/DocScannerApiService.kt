package com.dev.docscannerpdf.network

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

interface DocScannerApiService {
    @GET("health")
    suspend fun getHealth(): Response<HealthResponse>

    @GET("engine/capabilities")
    suspend fun getEngineCapabilities(): Response<EngineCapabilitiesResponse>

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
data class ProcessPageRequest(
    val operations: List<String> = emptyList(),
    val options: Map<String, String> = emptyMap()
)

@Serializable
data class ProcessPageResponse(
    val documentId: String? = null,
    val pageId: String? = null,
    val status: String,
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
