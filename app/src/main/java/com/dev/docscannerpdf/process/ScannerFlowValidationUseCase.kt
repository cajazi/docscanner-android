package com.dev.docscannerpdf.process

import android.content.Context
import android.net.Uri
import com.dev.docscannerpdf.network.NetworkResult
import com.dev.docscannerpdf.repository.DocScannerRemoteRepository

/**
 * Drives the full end-to-end scanner validation slice:
 *
 *   scan/import image -> upload -> create page -> process -> poll -> resolve processed
 *   image -> fetch OCR result -> completed (or error).
 *
 * This reuses the existing [ProcessDocumentUseCase] for the upload/process/poll legs and
 * the existing [DocScannerRemoteRepository] for the OCR fetch. It never fabricates backend
 * OCR text or image URLs — every field shown to the UI comes from a real backend response.
 */
class ScannerFlowValidationUseCase(
    private val processDocumentUseCase: ProcessDocumentUseCase = ProcessDocumentUseCase(),
    private val repository: DocScannerRemoteRepository = DocScannerRemoteRepository()
) {
    /**
     * Runs the production pipeline against [imageUri], emitting an updated
     * [ScannerFlowValidationState] for every milestone. Returns the terminal state.
     */
    suspend fun validate(
        context: Context,
        imageUri: Uri,
        title: String = "Android Upload",
        onState: (ScannerFlowValidationState) -> Unit = {}
    ): ScannerFlowValidationState {
        var state = ScannerFlowValidationState(
            stage = ScannerFlowStage.IMAGE_SELECTED,
            statusMessage = "Image selected for validation"
        )
        onState(state)

        val processResult = processDocumentUseCase.processCapturedImageAndPoll(
            context = context,
            imageUri = imageUri,
            title = title,
            onState = { event ->
                state = state.reduce(event)
                onState(state)
            }
        )

        return resolveAfterProcessing(
            base = state,
            processResult = processResult,
            onState = onState
        )
    }

    /**
     * Continues the validation flow once the upload/process/poll legs have produced
     * [processResult]. Split out from [validate] so the OCR-resolution logic is unit
     * testable without an Android [Context] or a real image upload.
     *
     * Honors any terminal error already reached during polling (e.g. a poll timeout
     * surfaced through [base]) and only fetches OCR when processing genuinely completed.
     */
    suspend fun resolveAfterProcessing(
        base: ScannerFlowValidationState,
        processResult: NetworkResult<ProcessDocumentResult>,
        onState: (ScannerFlowValidationState) -> Unit = {}
    ): ScannerFlowValidationState {
        if (base.isError) {
            return base
        }

        val result = when (processResult) {
            is NetworkResult.Success -> processResult.data
            is NetworkResult.Error -> {
                val message = processResult.errorBody?.takeIf { it.isNotBlank() }
                    ?: "Backend processing failed: HTTP ${processResult.code} ${processResult.message}"
                return base.toError(message).also(onState)
            }
            is NetworkResult.Exception -> {
                val message = processResult.throwable.message ?: "Backend processing failed."
                return base.toError(message).also(onState)
            }
        }

        var state = base.applyProcessedImage(result)
        onState(state)
        if (state.isError) {
            return state
        }

        state = state.copy(
            stage = ScannerFlowStage.OCR_FETCHING,
            statusMessage = "Fetching OCR result",
            ocrStatus = ScannerOcrStatus.FETCHING
        )
        onState(state)

        val ocrResult = repository.resolveOcrResult(
            repository.getPageOcr(
                documentId = result.documentId,
                pageId = result.pageId
            )
        )
        state = state.copy(stage = ScannerFlowStage.OCR_FETCHED).applyOcr(ocrResult)
        onState(state)
        return state
    }

    private fun ScannerFlowValidationState.toError(message: String): ScannerFlowValidationState {
        return copy(
            stage = ScannerFlowStage.ERROR,
            statusMessage = message,
            failureReason = message
        )
    }
}
