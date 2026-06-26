package com.dev.docscannerpdf.process

import com.dev.docscannerpdf.network.ProcessJobStatus

sealed interface ScannerBackendProcessingState {
    data object Idle : ScannerBackendProcessingState
    data class Uploading(val progressFraction: Float? = null) : ScannerBackendProcessingState
    data object Processing : ScannerBackendProcessingState
    data class Polling(
        val attempt: Int,
        val maxAttempts: Int,
        val latestStatus: ProcessJobStatus
    ) : ScannerBackendProcessingState
    data class CompletedWithImage(
        val url: String,
        val documentId: String,
        val pageId: String,
        val processJobId: String
    ) : ScannerBackendProcessingState
    data class CompletedWithoutImage(
        val reason: String,
        val documentId: String? = null,
        val pageId: String? = null,
        val processJobId: String? = null
    ) : ScannerBackendProcessingState
    data class Error(val message: String) : ScannerBackendProcessingState
}

val ScannerBackendProcessingState.isActive: Boolean
    get() = this is ScannerBackendProcessingState.Uploading ||
        this is ScannerBackendProcessingState.Processing ||
        this is ScannerBackendProcessingState.Polling

fun ProcessDocumentUiState.toScannerBackendProcessingState(): ScannerBackendProcessingState {
    return when (this) {
        ProcessDocumentUiState.Idle -> ScannerBackendProcessingState.Idle
        is ProcessDocumentUiState.Uploading -> ScannerBackendProcessingState.Uploading(progressFraction)
        ProcessDocumentUiState.CreatingPage,
        ProcessDocumentUiState.Processing -> ScannerBackendProcessingState.Processing
        is ProcessDocumentUiState.Polling -> ScannerBackendProcessingState.Polling(
            attempt = attempt,
            maxAttempts = maxAttempts,
            latestStatus = latestStatus
        )
        is ProcessDocumentUiState.ProcessingCompletedNoImageUrl ->
            ScannerBackendProcessingState.CompletedWithoutImage(reason)
        is ProcessDocumentUiState.Success -> result.toScannerBackendProcessingState()
        is ProcessDocumentUiState.Error -> ScannerBackendProcessingState.Error(message)
        is ProcessDocumentUiState.Timeout -> ScannerBackendProcessingState.Error(message)
    }
}

fun ProcessDocumentResult.toScannerBackendProcessingState(): ScannerBackendProcessingState {
    return when (val result = imageResult) {
        is ProcessedImageResult.SuccessWithImage -> ScannerBackendProcessingState.CompletedWithImage(
            url = result.url,
            documentId = documentId,
            pageId = pageId,
            processJobId = processJobId
        )
        is ProcessedImageResult.SuccessWithoutImage -> ScannerBackendProcessingState.CompletedWithoutImage(
            reason = result.reason,
            documentId = documentId,
            pageId = pageId,
            processJobId = processJobId
        )
        is ProcessedImageResult.Error -> ScannerBackendProcessingState.Error(result.message)
    }
}
