package com.dev.docscannerpdf.process

data class ProcessDocumentResult(
    val documentId: String,
    val pageId: String,
    val processJobId: String,
    val processingStartedAt: String
)

sealed interface ProcessDocumentUiState {
    data object Idle : ProcessDocumentUiState
    data class Uploading(val progressFraction: Float? = null) : ProcessDocumentUiState
    data object CreatingPage : ProcessDocumentUiState
    data object Processing : ProcessDocumentUiState
    data class Success(val result: ProcessDocumentResult) : ProcessDocumentUiState
    data class Error(val message: String) : ProcessDocumentUiState
}
