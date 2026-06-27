package com.dev.docscannerpdf.ui.result

import com.dev.docscannerpdf.process.ScannerFlowStage
import com.dev.docscannerpdf.process.ScannerFlowValidationState
import com.dev.docscannerpdf.process.ScannerOcrStatus

/**
 * Builds a unified [DocumentResultState] from the existing end-to-end validation state,
 * which is the production source of truth for backend processing + OCR. Only fields the
 * validation flow actually resolved are populated; the per-role image URLs
 * (original/cropped/enhanced) stay null because the current backend state model does not
 * expose them separately — nothing is invented to fill them.
 */
fun ScannerFlowValidationState.toDocumentResultState(
    localPreviewUri: String?
): DocumentResultState {
    return DocumentResultState(
        documentId = documentId,
        pageId = pageId,
        processJobId = processJobId,
        processedImageUrl = processedImageUrl,
        localPreviewUri = localPreviewUri,
        ocrText = ocrTextPreview,
        ocrStatus = ocrStatus.toResultOcrStatus(),
        processingStatus = statusMessage,
        loadingState = toResultLoadingState(),
        errorMessage = failureReason
    )
}

private fun ScannerOcrStatus.toResultOcrStatus(): ResultOcrStatus = when (this) {
    ScannerOcrStatus.PENDING -> ResultOcrStatus.PENDING
    ScannerOcrStatus.FETCHING -> ResultOcrStatus.LOADING
    ScannerOcrStatus.AVAILABLE -> ResultOcrStatus.AVAILABLE
    ScannerOcrStatus.EMPTY -> ResultOcrStatus.EMPTY
    ScannerOcrStatus.FAILED -> ResultOcrStatus.FAILED
}

private fun ScannerFlowValidationState.toResultLoadingState(): ResultLoadingState = when {
    stage == ScannerFlowStage.ERROR -> ResultLoadingState.ERROR
    stage == ScannerFlowStage.COMPLETED -> ResultLoadingState.READY
    isActive -> ResultLoadingState.LOADING
    else -> ResultLoadingState.IDLE
}
