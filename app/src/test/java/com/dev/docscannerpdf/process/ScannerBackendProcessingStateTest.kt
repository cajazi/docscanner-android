package com.dev.docscannerpdf.process

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerBackendProcessingStateTest {
    @Test
    fun successWithProcessedImageMapsToCompletedWithImage() {
        val state = ProcessDocumentUiState.Success(
            ProcessDocumentResult(
                documentId = "doc_1",
                pageId = "page_1",
                processJobId = "job_1",
                processingStartedAt = "2026-06-25T12:00:00.000Z",
                imageResult = ProcessedImageResult.SuccessWithImage("https://api.test/enhanced.jpg")
            )
        ).toScannerBackendProcessingState()

        assertTrue(state is ScannerBackendProcessingState.CompletedWithImage)
        state as ScannerBackendProcessingState.CompletedWithImage
        assertEquals("https://api.test/enhanced.jpg", state.url)
        assertEquals("doc_1", state.documentId)
        assertEquals("page_1", state.pageId)
        assertEquals("job_1", state.processJobId)
    }

    @Test
    fun successWithoutProcessedImageMapsToCompletedWithoutImage() {
        val state = ProcessDocumentUiState.Success(
            ProcessDocumentResult(
                documentId = "doc_1",
                pageId = "page_1",
                processJobId = "job_1",
                processingStartedAt = "2026-06-25T12:00:00.000Z",
                imageResult = ProcessedImageResult.SuccessWithoutImage(
                    "Processing completed, but no enhanced image URL is exposed by backend yet."
                )
            )
        ).toScannerBackendProcessingState()

        assertTrue(state is ScannerBackendProcessingState.CompletedWithoutImage)
        state as ScannerBackendProcessingState.CompletedWithoutImage
        assertEquals(
            "Processing completed, but no enhanced image URL is exposed by backend yet.",
            state.reason
        )
        assertEquals("doc_1", state.documentId)
        assertEquals("page_1", state.pageId)
        assertEquals("job_1", state.processJobId)
    }

    @Test
    fun backendFailureMapsToError() {
        val state = ProcessDocumentUiState.Error("Upload failed: HTTP 500")
            .toScannerBackendProcessingState()

        assertTrue(state is ScannerBackendProcessingState.Error)
        assertEquals("Upload failed: HTTP 500", (state as ScannerBackendProcessingState.Error).message)
    }
}
