package com.dev.docscannerpdf.repository

import com.dev.docscannerpdf.network.CreatePdfExportJobRequest
import com.dev.docscannerpdf.network.DocScannerApiService
import com.dev.docscannerpdf.network.EngineCapabilitiesResponse
import com.dev.docscannerpdf.network.HealthResponse
import com.dev.docscannerpdf.network.NetworkClient
import com.dev.docscannerpdf.network.NetworkResult
import com.dev.docscannerpdf.network.PdfExportJobResponse
import com.dev.docscannerpdf.network.ProcessPageRequest
import com.dev.docscannerpdf.network.ProcessPageResponse
import com.dev.docscannerpdf.network.safeApiCall

class DocScannerRemoteRepository(
    private val apiService: DocScannerApiService = NetworkClient.createApiService()
) {
    suspend fun getHealth(): NetworkResult<HealthResponse> {
        return safeApiCall { apiService.getHealth() }
    }

    suspend fun getEngineCapabilities(): NetworkResult<EngineCapabilitiesResponse> {
        return safeApiCall { apiService.getEngineCapabilities() }
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
}
