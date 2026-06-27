package com.dev.docscannerpdf.ui.result

/** Coarse load state of the unified result screen. */
enum class ResultLoadingState {
    IDLE,
    LOADING,
    READY,
    ERROR
}

/** OCR availability for the unified result screen. */
enum class ResultOcrStatus {
    PENDING,
    LOADING,
    AVAILABLE,
    EMPTY,
    FAILED
}

/**
 * Single, unified state model for the document result layer. It consolidates the
 * previously fragmented result data (scanner preview, OCR workspace, backend status,
 * validation panel) into one immutable snapshot the [DocumentResultScreen] renders.
 *
 * Backend-provided URLs stay null until the backend actually returns them — this model
 * never fabricates image URLs or OCR text. [localPreviewUri] is the on-device fallback
 * so the screen can always show *something* real even before/without backend results.
 */
data class DocumentResultState(
    val documentId: String? = null,
    val pageId: String? = null,
    val processJobId: String? = null,
    val originalImageUrl: String? = null,
    val croppedImageUrl: String? = null,
    val enhancedImageUrl: String? = null,
    val processedImageUrl: String? = null,
    val localPreviewUri: String? = null,
    val ocrText: String? = null,
    val ocrStatus: ResultOcrStatus = ResultOcrStatus.PENDING,
    val processingStatus: String? = null,
    val loadingState: ResultLoadingState = ResultLoadingState.IDLE,
    val errorMessage: String? = null
) {
    /**
     * Image the screen should display, in CamScanner-style preference order:
     * processed -> enhanced -> cropped -> original -> local preview. Blank values are
     * skipped so an empty backend field never wins over a usable fallback.
     */
    val preferredImageModel: String?
        get() = listOf(
            processedImageUrl,
            enhancedImageUrl,
            croppedImageUrl,
            originalImageUrl,
            localPreviewUri
        ).firstOrNull { !it.isNullOrBlank() }

    val hasOcrText: Boolean
        get() = !ocrText.isNullOrBlank()

    val isError: Boolean
        get() = loadingState == ResultLoadingState.ERROR || ocrStatus == ResultOcrStatus.FAILED
}

/**
 * Deterministic availability of the result action row. Text actions require usable OCR
 * text; PDF export is an explicit placeholder for this slice and is never enabled yet.
 */
data class DocumentResultActions(
    val canCopyText: Boolean,
    val canShareText: Boolean,
    val canExportTxt: Boolean,
    val canExportDoc: Boolean,
    val isPdfEnabled: Boolean
)

fun DocumentResultState.availableActions(): DocumentResultActions {
    val hasText = hasOcrText
    return DocumentResultActions(
        canCopyText = hasText,
        canShareText = hasText,
        canExportTxt = hasText,
        canExportDoc = hasText,
        // PDF export is intentionally not implemented in this slice.
        isPdfEnabled = false
    )
}
