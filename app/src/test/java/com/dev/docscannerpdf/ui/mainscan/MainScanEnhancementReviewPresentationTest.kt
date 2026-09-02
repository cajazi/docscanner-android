package com.dev.docscannerpdf.ui.mainscan

import com.dev.docscannerpdf.domain.mainscan.MainScanRenderFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScanEnhancementReviewPresentationTest {

    @Test
    fun filterRenderingSuppressesFailureAndDescribesProcessing() {
        for (enhancementApplied in listOf(false, true)) {
            val subject = mainScanReviewPresentation(
                filterRendering = true,
                highQualityResultAvailable = false,
                highQualityFailure = null,
                enhancementApplied = enhancementApplied,
                comparing = false
            )

            assertNull(subject.statusMessage)
            assertTrue(subject.imageDescription.contains("being applied"))
            assertFalse(subject.imageDescription.contains("cannot be saved"))
            assertFalse(subject.imageStateDescription.contains("not saveable"))
            assertEquals("Applying a new filter", subject.imageStateDescription)
        }
    }

    @Test
    fun comparingRetainsPriorityWhileFilterRendering() {
        val subject = mainScanReviewPresentation(
            filterRendering = true,
            highQualityResultAvailable = false,
            highQualityFailure = null,
            enhancementApplied = true,
            comparing = true
        )

        assertEquals(
            "The unenhanced cropped page, shown for comparison.",
            subject.imageDescription
        )
        assertEquals("Applying a new filter", subject.imageStateDescription)
    }

    @Test
    fun completedRenderFailureKeepsExistingFailureSemantics() {
        val subject = mainScanReviewPresentation(
            filterRendering = false,
            highQualityResultAvailable = false,
            highQualityFailure = MainScanRenderFailure.WRITE,
            enhancementApplied = true,
            comparing = false
        )

        assertEquals(
            "Preview only — the high-quality page couldn't be produced, " +
                "so this can't be saved. Go back and try the crop again.",
            subject.statusMessage
        )
        assertEquals(
            "Preview of the filtered page. Preview only — this cannot be saved.",
            subject.imageDescription
        )
        assertEquals("Preview only, not saveable", subject.imageStateDescription)
    }

    @Test
    fun healthyReviewKeepsExistingSuccessSemantics() {
        val subject = mainScanReviewPresentation(
            filterRendering = false,
            highQualityResultAvailable = true,
            highQualityFailure = null,
            enhancementApplied = true,
            comparing = false
        )

        assertNull(subject.statusMessage)
        assertEquals(
            "Preview of the cropped and filtered page. The full-quality page is ready.",
            subject.imageDescription
        )
        assertEquals("Full-quality page ready", subject.imageStateDescription)
    }
}
