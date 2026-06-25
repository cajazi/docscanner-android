package com.dev.docscannerpdf.process

import com.dev.docscannerpdf.network.ProcessJobStatus

data class ProcessDocumentResult(
    val documentId: String,
    val pageId: String,
    val processJobId: String,
    val processingStartedAt: String,
    val latestJobStatus: ProcessJobStatus? = null,
    val imageResult: ProcessedImageResult = ProcessedImageResult.SuccessWithoutImage(
        "Processing completed, but no enhanced image URL is exposed by backend yet."
    )
)

sealed interface ProcessedImageResult {
    data class SuccessWithImage(val url: String) : ProcessedImageResult
    data class SuccessWithoutImage(val reason: String) : ProcessedImageResult
    data class Error(val message: String) : ProcessedImageResult
}

sealed interface ProcessDocumentUiState {
    data object Idle : ProcessDocumentUiState
    data class Uploading(val progressFraction: Float? = null) : ProcessDocumentUiState
    data object CreatingPage : ProcessDocumentUiState
    data object Processing : ProcessDocumentUiState
    data class Polling(
        val attempt: Int,
        val maxAttempts: Int,
        val latestStatus: ProcessJobStatus
    ) : ProcessDocumentUiState
    data class ProcessingCompletedNoImageUrl(val reason: String) : ProcessDocumentUiState
    data class Success(val result: ProcessDocumentResult) : ProcessDocumentUiState
    data class Error(val message: String) : ProcessDocumentUiState
    data class Timeout(
        val processJobId: String,
        val latestStatus: ProcessJobStatus?,
        val message: String
    ) : ProcessDocumentUiState
}
