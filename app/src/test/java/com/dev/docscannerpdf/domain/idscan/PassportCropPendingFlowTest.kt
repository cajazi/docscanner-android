package com.dev.docscannerpdf.domain.idscan

import com.dev.docscannerpdf.domain.filter.DocumentFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the crop-Apply immediate-preview contract: the review returns instantly with an
 * in-memory preview while the authoritative full-resolution crop settles, and until it does
 * the DOWNSCALED preview can never become the saved pixels — Confirm/save is hard-blocked.
 */
class PassportCropPendingFlowTest {

    private val base = "file://passport/base.jpg"

    private fun state() = PassportReviewState(baseUri = base)

    @Test
    fun beginCropBlocksConfirmUntilTheAuthoritativeCropSettles() {
        val pending = PassportReviewFlow.beginCrop(state())

        assertTrue(pending.cropRenderPending)
        assertTrue("the thin progress line must show while the crop settles", pending.workInFlight)
        assertFalse("the low-resolution preview crop must never be persisted", pending.canConfirm)
        assertNull("beginSave must refuse while the crop is settling", PassportReviewFlow.beginSave(pending))
    }

    @Test
    fun settledCropInstallsTheFullResolutionBaseAndUnblocksConfirm() {
        val pending = PassportReviewFlow.beginCrop(state())

        val settled = PassportReviewFlow.withCroppedBase(pending, "file://passport/cropped.jpg")

        assertFalse(settled.cropRenderPending)
        assertEquals("file://passport/cropped.jpg", settled.baseUri)
        assertEquals("the settled FULL-resolution crop is what the review displays and saves",
            "file://passport/cropped.jpg", settled.displayedUri)
        assertTrue(settled.canConfirm)
    }

    @Test
    fun settledCropReappliesTheActiveFilterFromTheNewBase() {
        var s = PassportReviewFlow.applyFilter(state(), DocumentFilter.ENHANCE)
        s = PassportReviewFlow.withRenderedFilter(s, DocumentFilter.ENHANCE, base, "file://p/e.jpg")
        s = PassportReviewFlow.beginCrop(s)

        val settled = PassportReviewFlow.withCroppedBase(s, "file://passport/cropped.jpg")

        assertEquals(DocumentFilter.ENHANCE, settled.selectedFilter)
        assertTrue("the active filter re-renders from the NEW cropped base", settled.renderPending)
        assertNull("the OLD base's filter render must never be reused", settled.renderedFilter)
    }

    @Test
    fun failedCropRecoversToAnEditableConfirmableReview() {
        val pending = PassportReviewFlow.beginCrop(state())

        val failed = PassportReviewFlow.cropFailed(pending)

        assertFalse(failed.cropRenderPending)
        assertEquals("the un-cropped base remains the truth after a failed crop", base, failed.displayedUri)
        assertTrue(failed.canConfirm)
    }

    @Test
    fun saveAlwaysPersistsTheAuthoritativeDisplayedUriNeverAPreview() {
        // The displayed URI is by construction an authoritative settled file (base, filter
        // render, rotation bake, or watermark render) — the in-memory preview bitmaps have no
        // URI at all, so the save path structurally cannot reach them.
        val s = state()
        val saving = PassportReviewFlow.beginSave(s)

        assertEquals(base, saving?.displayedUri)
    }
}
