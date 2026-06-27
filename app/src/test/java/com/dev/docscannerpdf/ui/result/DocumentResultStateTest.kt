package com.dev.docscannerpdf.ui.result

import com.dev.docscannerpdf.process.ScannerFlowStage
import com.dev.docscannerpdf.process.ScannerFlowValidationState
import com.dev.docscannerpdf.process.ScannerOcrStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentResultStateTest {

    @Test
    fun imageFallbackPrefersProcessedOverEverythingElse() {
        val state = DocumentResultState(
            processedImageUrl = "https://api.test/processed.jpg",
            enhancedImageUrl = "https://api.test/enhanced.jpg",
            croppedImageUrl = "https://api.test/cropped.jpg",
            originalImageUrl = "https://api.test/original.jpg",
            localPreviewUri = "file:///tmp/local.jpg"
        )

        assertEquals("https://api.test/processed.jpg", state.preferredImageModel)
    }

    @Test
    fun imageFallbackWalksDownThePreferenceOrder() {
        val enhanced = DocumentResultState(
            enhancedImageUrl = "https://api.test/enhanced.jpg",
            croppedImageUrl = "https://api.test/cropped.jpg",
            originalImageUrl = "https://api.test/original.jpg",
            localPreviewUri = "file:///tmp/local.jpg"
        )
        assertEquals("https://api.test/enhanced.jpg", enhanced.preferredImageModel)

        val cropped = enhanced.copy(enhancedImageUrl = null)
        assertEquals("https://api.test/cropped.jpg", cropped.preferredImageModel)

        val original = cropped.copy(croppedImageUrl = null)
        assertEquals("https://api.test/original.jpg", original.preferredImageModel)
    }

    @Test
    fun imageFallbackUsesLocalPreviewWhenNoBackendUrlExists() {
        val state = DocumentResultState(
            // Blank backend URLs must be skipped, not chosen over the local fallback.
            processedImageUrl = "",
            enhancedImageUrl = "   ",
            localPreviewUri = "file:///tmp/local.jpg"
        )

        assertEquals("file:///tmp/local.jpg", state.preferredImageModel)
    }

    @Test
    fun imageFallbackIsNullWhenNothingIsAvailable() {
        assertNull(DocumentResultState().preferredImageModel)
    }

    @Test
    fun ocrReadyStateMapsToEditableText() {
        val validation = ScannerFlowValidationState(
            stage = ScannerFlowStage.COMPLETED,
            statusMessage = "Validation complete — OCR text available",
            documentId = "doc_1",
            pageId = "page_1",
            processJobId = "job_1",
            processedImageUrl = "https://api.test/processed.jpg",
            ocrStatus = ScannerOcrStatus.AVAILABLE,
            ocrTextPreview = "Invoice total: 42.00"
        )

        val result = validation.toDocumentResultState(localPreviewUri = "file:///tmp/local.jpg")

        assertEquals(ResultOcrStatus.AVAILABLE, result.ocrStatus)
        assertEquals(ResultLoadingState.READY, result.loadingState)
        assertEquals("Invoice total: 42.00", result.ocrText)
        assertTrue(result.hasOcrText)
        assertEquals("doc_1", result.documentId)
        assertEquals("page_1", result.pageId)
        assertEquals("job_1", result.processJobId)
        assertEquals("https://api.test/processed.jpg", result.preferredImageModel)
    }

    @Test
    fun ocrEmptyStateIsShown() {
        val validation = ScannerFlowValidationState(
            stage = ScannerFlowStage.COMPLETED,
            statusMessage = "Validation complete — backend OCR is empty",
            ocrStatus = ScannerOcrStatus.EMPTY,
            ocrTextPreview = null
        )

        val result = validation.toDocumentResultState(localPreviewUri = null)

        assertEquals(ResultOcrStatus.EMPTY, result.ocrStatus)
        assertEquals(ResultLoadingState.READY, result.loadingState)
        assertFalse(result.hasOcrText)
        assertNull(result.ocrText)
    }

    @Test
    fun errorStateIsPreserved() {
        val validation = ScannerFlowValidationState(
            stage = ScannerFlowStage.ERROR,
            statusMessage = "OCR fetch failed",
            ocrStatus = ScannerOcrStatus.FAILED,
            failureReason = "HTTP 500 ocr unavailable"
        )

        val result = validation.toDocumentResultState(localPreviewUri = "file:///tmp/local.jpg")

        assertEquals(ResultLoadingState.ERROR, result.loadingState)
        assertEquals(ResultOcrStatus.FAILED, result.ocrStatus)
        assertEquals("HTTP 500 ocr unavailable", result.errorMessage)
        assertTrue(result.isError)
    }

    @Test
    fun loadingStateMapsWhileFlowIsActive() {
        val validation = ScannerFlowValidationState(
            stage = ScannerFlowStage.OCR_FETCHING,
            statusMessage = "Fetching OCR result",
            ocrStatus = ScannerOcrStatus.FETCHING
        )

        val result = validation.toDocumentResultState(localPreviewUri = null)

        assertEquals(ResultLoadingState.LOADING, result.loadingState)
        assertEquals(ResultOcrStatus.LOADING, result.ocrStatus)
    }

    @Test
    fun actionAvailabilityIsDeterministic() {
        val withText = DocumentResultState(
            ocrText = "Some recognized text",
            ocrStatus = ResultOcrStatus.AVAILABLE
        )
        val withTextActions = withText.availableActions()
        assertTrue(withTextActions.canCopyText)
        assertTrue(withTextActions.canShareText)
        assertTrue(withTextActions.canExportTxt)
        assertTrue(withTextActions.canExportDoc)
        // PDF export remains a placeholder regardless of text availability.
        assertFalse(withTextActions.isPdfEnabled)
        // Same input -> same output.
        assertEquals(withTextActions, withText.availableActions())

        val withoutText = DocumentResultState(ocrText = "   ", ocrStatus = ResultOcrStatus.EMPTY)
        val withoutTextActions = withoutText.availableActions()
        assertFalse(withoutTextActions.canCopyText)
        assertFalse(withoutTextActions.canShareText)
        assertFalse(withoutTextActions.canExportTxt)
        assertFalse(withoutTextActions.canExportDoc)
        assertFalse(withoutTextActions.isPdfEnabled)
        assertEquals(withoutTextActions, withoutText.availableActions())
    }
}
