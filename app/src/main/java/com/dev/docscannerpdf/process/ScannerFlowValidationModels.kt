package com.dev.docscannerpdf.process

/**
 * Result of fetching backend OCR for a processed page.
 *
 * This intentionally distinguishes "the backend returned no text" ([Empty]) from
 * "the OCR endpoint failed" ([Failed]) so the validation flow can prove the real
 * backend state instead of collapsing both into a generic error.
 */
sealed interface ScannerOcrResult {
    data class Available(val text: String) : ScannerOcrResult
    data object Empty : ScannerOcrResult
    data class Failed(val reason: String) : ScannerOcrResult
}

/** Status of the OCR leg of the end-to-end validation flow. */
enum class ScannerOcrStatus {
    PENDING,
    FETCHING,
    AVAILABLE,
    EMPTY,
    FAILED
}

/**
 * Discrete milestones of the production scanner-to-backend-to-OCR path. The ordering
 * mirrors the requirement's pipeline: image selected -> uploaded -> page created ->
 * processing started -> polling completed -> processed image resolved -> OCR fetched ->
 * completed (or error at any point).
 */
enum class ScannerFlowStage {
    IDLE,
    IMAGE_SELECTED,
    UPLOADING,
    PAGE_CREATED,
    PROCESSING_STARTED,
    POLLING,
    POLLING_COMPLETED,
    PROCESSED_IMAGE_RESOLVED,
    OCR_FETCHING,
    OCR_FETCHED,
    COMPLETED,
    ERROR
}

/**
 * Immutable snapshot of the full validation pipeline. The use case accumulates this
 * state as the flow progresses; the UI renders the fields directly. Nullable backend
 * identifiers stay null until the backend actually returns them — nothing is fabricated.
 */
data class ScannerFlowValidationState(
    val stage: ScannerFlowStage = ScannerFlowStage.IDLE,
    val statusMessage: String = "Idle",
    val documentId: String? = null,
    val pageId: String? = null,
    val processJobId: String? = null,
    val processedImageUrl: String? = null,
    val ocrStatus: ScannerOcrStatus = ScannerOcrStatus.PENDING,
    val ocrTextPreview: String? = null,
    val failureReason: String? = null,
    val pollAttempt: Int? = null,
    val pollMaxAttempts: Int? = null,
    val uploadProgress: Float? = null
) {
    val isActive: Boolean
        get() = when (stage) {
            ScannerFlowStage.IMAGE_SELECTED,
            ScannerFlowStage.UPLOADING,
            ScannerFlowStage.PAGE_CREATED,
            ScannerFlowStage.PROCESSING_STARTED,
            ScannerFlowStage.POLLING,
            ScannerFlowStage.POLLING_COMPLETED,
            ScannerFlowStage.PROCESSED_IMAGE_RESOLVED,
            ScannerFlowStage.OCR_FETCHING -> true
            else -> false
        }

    val isError: Boolean
        get() = stage == ScannerFlowStage.ERROR
}

/**
 * Deterministically folds a [ProcessDocumentUiState] progress event emitted by
 * [ProcessDocumentUseCase] into the accumulated validation state. Pure and total:
 * every event type maps to exactly one stage, and the same input always yields the
 * same output, which is what the validation flow relies on for predictable UI.
 */
fun ScannerFlowValidationState.reduce(event: ProcessDocumentUiState): ScannerFlowValidationState {
    return when (event) {
        ProcessDocumentUiState.Idle -> copy(
            stage = ScannerFlowStage.IDLE,
            statusMessage = "Idle"
        )
        is ProcessDocumentUiState.Uploading -> copy(
            stage = ScannerFlowStage.UPLOADING,
            statusMessage = "Uploading image to backend",
            uploadProgress = event.progressFraction,
            failureReason = null
        )
        ProcessDocumentUiState.CreatingPage -> copy(
            stage = ScannerFlowStage.PAGE_CREATED,
            statusMessage = "Upload complete — creating page",
            uploadProgress = null
        )
        ProcessDocumentUiState.Processing -> copy(
            stage = ScannerFlowStage.PROCESSING_STARTED,
            statusMessage = "Processing started"
        )
        is ProcessDocumentUiState.Polling -> copy(
            stage = ScannerFlowStage.POLLING,
            statusMessage = "Polling ${event.attempt}/${event.maxAttempts}: ${event.latestStatus.status}",
            pollAttempt = event.attempt,
            pollMaxAttempts = event.maxAttempts
        )
        is ProcessDocumentUiState.ProcessingCompletedNoImageUrl -> copy(
            stage = ScannerFlowStage.POLLING_COMPLETED,
            statusMessage = event.reason
        )
        is ProcessDocumentUiState.Success -> copy(
            stage = ScannerFlowStage.POLLING_COMPLETED,
            statusMessage = "Polling completed",
            documentId = event.result.documentId,
            pageId = event.result.pageId,
            processJobId = event.result.processJobId
        )
        is ProcessDocumentUiState.Error -> copy(
            stage = ScannerFlowStage.ERROR,
            statusMessage = event.message,
            failureReason = event.message
        )
        is ProcessDocumentUiState.Timeout -> copy(
            stage = ScannerFlowStage.ERROR,
            statusMessage = event.message,
            failureReason = event.message,
            processJobId = processJobId ?: event.processJobId
        )
    }
}

/**
 * Folds the final [ProcessDocumentResult] (processed image resolution) into the state.
 * A failed backend image becomes [ScannerFlowStage.ERROR]; otherwise the state advances
 * to [ScannerFlowStage.PROCESSED_IMAGE_RESOLVED], exposing the real processed image URL
 * only when the backend actually returned one.
 */
fun ScannerFlowValidationState.applyProcessedImage(
    result: ProcessDocumentResult
): ScannerFlowValidationState {
    val base = copy(
        documentId = result.documentId,
        pageId = result.pageId,
        processJobId = result.processJobId
    )
    return when (val imageResult = result.imageResult) {
        is ProcessedImageResult.SuccessWithImage -> base.copy(
            stage = ScannerFlowStage.PROCESSED_IMAGE_RESOLVED,
            statusMessage = "Processed image resolved",
            processedImageUrl = imageResult.url
        )
        is ProcessedImageResult.SuccessWithoutImage -> base.copy(
            stage = ScannerFlowStage.PROCESSED_IMAGE_RESOLVED,
            statusMessage = imageResult.reason,
            processedImageUrl = null
        )
        is ProcessedImageResult.Error -> base.copy(
            stage = ScannerFlowStage.ERROR,
            statusMessage = imageResult.message,
            failureReason = imageResult.message
        )
    }
}

/** Folds a resolved [ScannerOcrResult] into the terminal validation state. */
fun ScannerFlowValidationState.applyOcr(result: ScannerOcrResult): ScannerFlowValidationState {
    return when (result) {
        is ScannerOcrResult.Available -> copy(
            stage = ScannerFlowStage.COMPLETED,
            statusMessage = "Validation complete — OCR text available",
            ocrStatus = ScannerOcrStatus.AVAILABLE,
            ocrTextPreview = result.text,
            failureReason = null
        )
        ScannerOcrResult.Empty -> copy(
            stage = ScannerFlowStage.COMPLETED,
            statusMessage = "Validation complete — backend OCR is empty",
            ocrStatus = ScannerOcrStatus.EMPTY,
            ocrTextPreview = null,
            failureReason = null
        )
        is ScannerOcrResult.Failed -> copy(
            stage = ScannerFlowStage.ERROR,
            statusMessage = "OCR fetch failed",
            ocrStatus = ScannerOcrStatus.FAILED,
            failureReason = result.reason
        )
    }
}
