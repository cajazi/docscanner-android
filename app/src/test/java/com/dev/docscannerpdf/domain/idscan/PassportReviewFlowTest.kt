package com.dev.docscannerpdf.domain.idscan

import com.dev.docscannerpdf.domain.filter.DocumentFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PassportReviewFlowTest {

    private val base = "file://passport/base.jpg"

    private fun state() = PassportReviewState(baseUri = base)

    // --- orientation / rotation ---

    @Test
    fun initialStateShowsCanonicalBaseUprightWithZeroRotation() {
        val s = state()

        assertEquals(base, s.displayedUri)
        assertEquals("baker output is already upright — review never corrects it", 0, s.requestedRotationDegrees)
        assertEquals(0, s.settledRotationDegrees)
        assertEquals(DocumentFilter.ORIGINAL, s.selectedFilter)
        assertNull(s.renderedFilter)
        assertFalse(s.renderPending)
    }

    @Test
    fun manualRotationCyclesThroughQuarterTurnsBackToZero() {
        var s = state()
        val seen = mutableListOf<Int>()
        repeat(4) {
            s = PassportReviewFlow.rotate(s)
            seen.add(s.requestedRotationDegrees)
        }

        assertEquals(listOf(90, 180, 270, 0), seen)
    }

    // --- filters ---

    @Test
    fun filterRendersFromBaseAndKeepsLastImageWhilePending() {
        val enhanced = PassportReviewFlow.applyFilter(state(), DocumentFilter.ENHANCE)

        assertTrue(enhanced.renderPending)
        assertEquals("no flash back to base while rendering", base, enhanced.displayedUri)
        assertNull("stale render must not be reusable at save", enhanced.settledFilterUri)
    }

    @Test
    fun publishedRenderBecomesDisplayedAndSettled() {
        val pending = PassportReviewFlow.applyFilter(state(), DocumentFilter.ENHANCE)

        val published = PassportReviewFlow.withRenderedFilter(
            state = pending,
            filter = DocumentFilter.ENHANCE,
            fromBaseUri = base,
            renderedUri = "file://passport/enhance.jpg"
        )

        assertEquals("file://passport/enhance.jpg", published.displayedUri)
        assertEquals(DocumentFilter.ENHANCE, published.renderedFilter)
        assertFalse(published.renderPending)
        assertEquals("file://passport/enhance.jpg", published.settledFilterUri)
    }

    @Test
    fun originalRestoresTheCanonicalBase() {
        var s = PassportReviewFlow.applyFilter(state(), DocumentFilter.ENHANCE)
        s = PassportReviewFlow.withRenderedFilter(s, DocumentFilter.ENHANCE, base, "file://p/e.jpg")

        val original = PassportReviewFlow.applyFilter(s, DocumentFilter.ORIGINAL)

        assertEquals(base, original.displayedUri)
        assertEquals(DocumentFilter.ORIGINAL, original.selectedFilter)
        assertNull(original.renderedFilter)
        assertFalse(original.renderPending)
    }

    @Test
    fun staleRenderResultsAreIgnored() {
        // The user switched ENHANCE -> BW while the ENHANCE render was still running.
        val bw = PassportReviewFlow.applyFilter(state(), DocumentFilter.BW)

        val stale = PassportReviewFlow.withRenderedFilter(bw, DocumentFilter.ENHANCE, base, "file://p/e.jpg")
        assertEquals(bw, stale)

        // And a render started from a pre-crop base is equally rejected.
        val cropped = PassportReviewFlow.withCroppedBase(bw, "file://passport/cropped.jpg")
        val staleBase = PassportReviewFlow.withRenderedFilter(cropped, DocumentFilter.BW, base, "file://p/b.jpg")
        assertEquals(cropped, staleBase)
    }

    @Test
    fun reapplyingSettledOrPendingFilterIsNoOp() {
        var s = PassportReviewFlow.applyFilter(state(), DocumentFilter.ENHANCE)
        assertSame("already pending", s, PassportReviewFlow.applyFilter(s, DocumentFilter.ENHANCE))

        s = PassportReviewFlow.withRenderedFilter(s, DocumentFilter.ENHANCE, base, "file://p/e.jpg")
        assertSame("already settled", s, PassportReviewFlow.applyFilter(s, DocumentFilter.ENHANCE))

        val original = PassportReviewFlow.applyFilter(s, DocumentFilter.ORIGINAL)
        assertSame(original, PassportReviewFlow.applyFilter(original, DocumentFilter.ORIGINAL))
    }

    @Test
    fun failedRenderRestoresTheLastTruthfulResult() {
        var s = PassportReviewFlow.applyFilter(state(), DocumentFilter.ENHANCE)
        s = PassportReviewFlow.withRenderedFilter(s, DocumentFilter.ENHANCE, base, "file://p/e.jpg")
        s = PassportReviewFlow.applyFilter(s, DocumentFilter.SEPIA)

        val failed = PassportReviewFlow.withRenderFailed(s, DocumentFilter.SEPIA, base)

        assertEquals("reverts to the filter whose pixels are on screen", DocumentFilter.ENHANCE, failed.selectedFilter)
        assertEquals("file://p/e.jpg", failed.displayedUri)
        assertFalse(failed.renderPending)
    }

    @Test
    fun failedFirstRenderFallsBackToOriginalOnBase() {
        val pending = PassportReviewFlow.applyFilter(state(), DocumentFilter.ENHANCE)

        val failed = PassportReviewFlow.withRenderFailed(pending, DocumentFilter.ENHANCE, base)

        assertEquals(DocumentFilter.ORIGINAL, failed.selectedFilter)
        assertEquals(base, failed.displayedUri)
        assertFalse(failed.renderPending)
    }

    @Test
    fun staleFailureCannotAlterANewerSelection() {
        val bw = PassportReviewFlow.applyFilter(state(), DocumentFilter.BW)

        assertEquals(bw, PassportReviewFlow.withRenderFailed(bw, DocumentFilter.ENHANCE, base))
    }

    // --- crop ---

    @Test
    fun cropReplacesBaseAndInvalidatesStaleRender() {
        var s = PassportReviewFlow.applyFilter(state(), DocumentFilter.ENHANCE)
        s = PassportReviewFlow.withRenderedFilter(s, DocumentFilter.ENHANCE, base, "file://p/e.jpg")
        s = PassportReviewFlow.rotate(s)

        val cropped = PassportReviewFlow.withCroppedBase(s, "file://passport/cropped.jpg")

        assertEquals("file://passport/cropped.jpg", cropped.baseUri)
        assertEquals("file://passport/cropped.jpg", cropped.displayedUri)
        assertNull("old render discarded", cropped.renderedFilter)
        assertTrue("selected filter re-renders from the new base", cropped.renderPending)
        assertEquals("filter selection preserved", DocumentFilter.ENHANCE, cropped.selectedFilter)
        assertEquals("rotation preserved", 90, cropped.requestedRotationDegrees)
    }

    @Test
    fun cropWithOriginalFilterNeedsNoRender() {
        val cropped = PassportReviewFlow.withCroppedBase(state(), "file://passport/cropped.jpg")

        assertFalse(cropped.renderPending)
        assertEquals("file://passport/cropped.jpg", cropped.displayedUri)
    }

    // --- watermark ---

    @Test
    fun watermarkIsAppliedOnlyWhenSetAndClearsOnBlank() {
        val marked = PassportReviewFlow.withWatermark(state(), "  Copy  ")
        assertEquals("Copy", marked.watermarkText)

        assertNull("cancel/clear preserves the unwatermarked state", PassportReviewFlow.withWatermark(marked, null).watermarkText)
        assertNull(PassportReviewFlow.withWatermark(marked, "   ").watermarkText)
    }

    // --- confirm / save ---

    @Test
    fun confirmIsSingleFlight() {
        val first = PassportReviewFlow.beginSave(state())
        assertTrue(first != null && first.saveInProgress)

        assertNull("a second confirm while saving is rejected", PassportReviewFlow.beginSave(first!!))
    }

    @Test
    fun failedSaveReleasesTheGuardForRetry() {
        val saving = PassportReviewFlow.beginSave(state())!!

        val failed = PassportReviewFlow.saveFailed(saving)

        assertFalse(failed.saveInProgress)
        assertTrue("retry is possible", PassportReviewFlow.beginSave(failed) != null)
    }

    // --- watermark: authoritative rendered URI (preview == save) ---

    @Test
    fun applyingWatermarkMarksRenderPendingAndKeepsFilteredImageMeanwhile() {
        val marked = PassportReviewFlow.withWatermark(state(), "Copy")

        assertEquals("Copy", marked.watermarkText)
        assertTrue(marked.watermarkRenderPending)
        assertTrue(marked.watermarkNeedsRender)
        // Until the render lands, the preview shows the (un-watermarked) filtered image.
        assertEquals(base, marked.displayedUri)
    }

    @Test
    fun publishedWatermarkBecomesTheDisplayedAndSaveImage() {
        var s = PassportReviewFlow.withWatermark(state(), "Copy")

        s = PassportReviewFlow.withWatermarkRendered(
            state = s,
            forText = "Copy",
            fromFilteredUri = base,
            atRotation = 0,
            renderedUri = "file://p/wm.jpg"
        )

        assertEquals("file://p/wm.jpg", s.watermarkRenderedUri)
        assertFalse(s.watermarkRenderPending)
        // The ONE authoritative image the review shows and the save persists.
        assertEquals("file://p/wm.jpg", s.displayedUri)
    }

    @Test
    fun visibleReviewUriEqualsFinalSaveInput() {
        // What displayedUri returns is exactly what the save reuses when a watermark is active.
        var s = PassportReviewFlow.withWatermark(state(), "Copy")
        s = PassportReviewFlow.withWatermarkRendered(s, "Copy", base, 0, "file://p/wm.jpg")

        assertEquals(s.displayedUri, s.watermarkRenderedUri)
    }

    @Test
    fun filterChangeInvalidatesTheWatermarkRender() {
        var s = PassportReviewFlow.withWatermark(state(), "Copy")
        s = PassportReviewFlow.withWatermarkRendered(s, "Copy", base, 0, "file://p/wm.jpg")

        val filtered = PassportReviewFlow.applyFilter(s, DocumentFilter.ENHANCE)

        assertNull("watermark dropped when the filtered image changes", filtered.watermarkRenderedUri)
        assertTrue(filtered.watermarkRenderPending)
    }

    @Test
    fun rotationChangeInvalidatesTheWatermarkRender() {
        var s = PassportReviewFlow.withWatermark(state(), "Copy")
        s = PassportReviewFlow.withWatermarkRendered(s, "Copy", base, 0, "file://p/wm.jpg")

        val rotated = PassportReviewFlow.rotate(s)

        assertNull(rotated.watermarkRenderedUri)
        assertTrue(rotated.watermarkRenderPending)
        assertEquals(90, rotated.requestedRotationDegrees)
    }

    @Test
    fun cropChangeInvalidatesTheWatermarkRender() {
        var s = PassportReviewFlow.withWatermark(state(), "Copy")
        s = PassportReviewFlow.withWatermarkRendered(s, "Copy", base, 0, "file://p/wm.jpg")

        val cropped = PassportReviewFlow.withCroppedBase(s, "file://p/cropped.jpg")

        assertNull(cropped.watermarkRenderedUri)
        assertTrue(cropped.watermarkRenderPending)
    }

    @Test
    fun staleWatermarkResultIsRejected() {
        // The user changed the text after the render started.
        val s = PassportReviewFlow.withWatermark(state(), "New")

        val stale = PassportReviewFlow.withWatermarkRendered(s, "Old", base, 0, "file://p/old.jpg")
        assertEquals(s, stale)

        // And a render from a superseded rotation is rejected.
        val rotated = PassportReviewFlow.rotate(s)
        val staleRotation = PassportReviewFlow.withWatermarkRendered(rotated, "New", base, 0, "file://p/r0.jpg")
        assertNull(staleRotation.watermarkRenderedUri)
    }

    @Test
    fun failedWatermarkRenderKeepsTheLastTruthfulImage() {
        val s = PassportReviewFlow.withWatermark(state(), "Copy")

        val failed = PassportReviewFlow.withWatermarkFailed(s, "Copy")

        assertFalse(failed.watermarkRenderPending)
        assertEquals("preview stays on the filtered page", base, failed.displayedUri)
        assertEquals("text kept for retry", "Copy", failed.watermarkText)
    }

    @Test
    fun removingWatermarkRestoresTheSettledUnwatermarkedImage() {
        var s = PassportReviewFlow.withWatermark(state(), "Copy")
        s = PassportReviewFlow.withWatermarkRendered(s, "Copy", base, 0, "file://p/wm.jpg")

        val removed = PassportReviewFlow.withWatermark(s, null)

        assertNull(removed.watermarkText)
        assertNull(removed.watermarkRenderedUri)
        assertFalse(removed.watermarkRenderPending)
        assertEquals(base, removed.displayedUri)
    }

    @Test
    fun confirmIsBlockedWhileAnyRenderIsPending() {
        val filterPending = PassportReviewFlow.applyFilter(state(), DocumentFilter.ENHANCE)
        assertNull("filter render in flight blocks save", PassportReviewFlow.beginSave(filterPending))

        val watermarkPending = PassportReviewFlow.withWatermark(state(), "Copy")
        assertNull("watermark render in flight blocks save", PassportReviewFlow.beginSave(watermarkPending))
    }

    // --- B5: filter/watermark failure states must be truthful and never save a lie ---

    @Test
    fun watermarkRenderFailureIsAnExplicitRecoverableErrorThatBlocksConfirm() {
        val requested = PassportReviewFlow.withWatermark(state(), "Copy")

        val failed = PassportReviewFlow.withWatermarkFailed(requested, "Copy")

        assertTrue("failure is explicit, not silent", failed.watermarkError)
        assertFalse("nothing is in flight after failure", failed.watermarkRenderPending)
        assertNull("no authoritative watermark exists", failed.watermarkRenderedUri)
        assertEquals("text kept so Retry/Remove is possible", "Copy", failed.watermarkText)
        // The toolbar still shows the watermark active — Confirm must be disabled so the page is
        // never saved un-watermarked while a watermark is advertised.
        assertTrue(failed.watermarkUnresolved)
        assertFalse(failed.canConfirm)
        assertNull("save is refused while the watermark is unresolved", PassportReviewFlow.beginSave(failed))
    }

    @Test
    fun retryingAWatermarkAfterFailureClearsTheErrorAndCanSucceed() {
        val failed = PassportReviewFlow.withWatermarkFailed(
            PassportReviewFlow.withWatermark(state(), "Copy"),
            "Copy"
        )

        // Re-applying the same text (the dialog's Apply) is a retry: error clears, render re-arms.
        val retry = PassportReviewFlow.withWatermark(failed, "Copy")
        assertFalse(retry.watermarkError)
        assertTrue(retry.watermarkRenderPending)
        assertNull("still cannot save until the retry lands", PassportReviewFlow.beginSave(retry))

        val rendered = PassportReviewFlow.withWatermarkRendered(retry, "Copy", base, 0, "file://p/wm.jpg")
        assertEquals("file://p/wm.jpg", rendered.displayedUri)
        assertFalse(rendered.watermarkError)
        assertTrue(rendered.canConfirm)
        assertTrue(PassportReviewFlow.beginSave(rendered) != null)
    }

    @Test
    fun removingAWatermarkAfterFailureReturnsToAConfirmableNoWatermarkState() {
        val failed = PassportReviewFlow.withWatermarkFailed(
            PassportReviewFlow.withWatermark(state(), "Copy"),
            "Copy"
        )

        val removed = PassportReviewFlow.withWatermark(failed, null)

        assertNull(removed.watermarkText)
        assertFalse(removed.watermarkError)
        assertEquals("preview returns to the un-watermarked page", base, removed.displayedUri)
        assertTrue(removed.canConfirm)
        assertTrue(PassportReviewFlow.beginSave(removed) != null)
    }

    @Test
    fun filterFailureWithAnActiveWatermarkLeavesTheWatermarkArmedNotPermanentlyStuck() {
        // A settled ENHANCE filter with a rendered watermark on top.
        var s = PassportReviewFlow.applyFilter(state(), DocumentFilter.ENHANCE)
        s = PassportReviewFlow.withRenderedFilter(s, DocumentFilter.ENHANCE, base, "file://p/e.jpg")
        s = PassportReviewFlow.withWatermark(s, "Copy")
        s = PassportReviewFlow.withWatermarkRendered(s, "Copy", "file://p/e.jpg", 0, "file://p/wm.jpg")

        // Switch to SEPIA (invalidates the watermark → pending) then that filter render FAILS.
        s = PassportReviewFlow.applyFilter(s, DocumentFilter.SEPIA)
        val failed = PassportReviewFlow.withRenderFailed(s, DocumentFilter.SEPIA, base)

        assertEquals("reverts to the filter whose pixels are on screen", DocumentFilter.ENHANCE, failed.selectedFilter)
        // The watermark is still ARMED (pending), not silently dropped and not stuck-unresolved:
        // the orchestrator re-renders it over the restored filtered page.
        assertTrue("watermark stays armed for re-render", failed.watermarkRenderPending)
        assertNull(failed.watermarkRenderedUri)
        assertFalse(failed.canConfirm)

        // And a subsequent successful watermark render fully recovers Confirm eligibility.
        val recovered = PassportReviewFlow.withWatermarkRendered(failed, "Copy", "file://p/e.jpg", 0, "file://p/wm2.jpg")
        assertTrue(recovered.canConfirm)
    }

    @Test
    fun firstFilterFailureWithActiveWatermarkFallsBackToOriginalWithWatermarkStillArmed() {
        // No prior settled filter: ORIGINAL + a rendered watermark.
        var s = PassportReviewFlow.withWatermark(state(), "Copy")
        s = PassportReviewFlow.withWatermarkRendered(s, "Copy", base, 0, "file://p/wm.jpg")

        s = PassportReviewFlow.applyFilter(s, DocumentFilter.ENHANCE)
        val failed = PassportReviewFlow.withRenderFailed(s, DocumentFilter.ENHANCE, base)

        assertEquals(DocumentFilter.ORIGINAL, failed.selectedFilter)
        assertEquals(base, failed.filteredUri)
        assertTrue("watermark re-render is armed over the restored base", failed.watermarkRenderPending)
        assertFalse(failed.canConfirm)
    }

    @Test
    fun confirmEligibilityTracksWatermarkResolutionAcrossTheWholeLifecycle() {
        assertTrue("clean base is confirmable", state().canConfirm)

        val pending = PassportReviewFlow.withWatermark(state(), "Copy")
        assertFalse("pending watermark blocks confirm", pending.canConfirm)

        val failed = PassportReviewFlow.withWatermarkFailed(pending, "Copy")
        assertFalse("failed watermark blocks confirm", failed.canConfirm)

        val rendered = PassportReviewFlow.withWatermarkRendered(pending, "Copy", base, 0, "file://p/wm.jpg")
        assertTrue("resolved watermark allows confirm", rendered.canConfirm)
    }

    // --- crop → downstream invalidation & regeneration from the cropped base ---

    @Test
    fun applyingACropReplacesTheBaseAndReRendersEveryActiveDownstreamSelection() {
        // Active filter (settled), a settled rotation, and a rendered watermark before crop.
        var s = PassportReviewFlow.applyFilter(state(), DocumentFilter.ENHANCE)
        s = PassportReviewFlow.withRenderedFilter(s, DocumentFilter.ENHANCE, base, "file://p/e.jpg")
        s = PassportReviewFlow.rotate(s)
        s = PassportReviewFlow.withRotationRendered(s, "file://p/e.jpg", 90, "file://p/rot90.jpg")
        s = PassportReviewFlow.withWatermark(s, "Copy")
        s = PassportReviewFlow.withWatermarkRendered(s, "Copy", "file://p/e.jpg", 90, "file://p/wm.jpg")
        assertTrue(s.canConfirm)

        val cropped = PassportReviewFlow.withCroppedBase(s, "file://p/cropped.jpg")

        // The cropped page IS the new canonical base — downstream uses cropped pixels.
        assertEquals("file://p/cropped.jpg", cropped.baseUri)
        assertEquals("file://p/cropped.jpg", cropped.filteredUri)
        // Filter, watermark are invalidated and re-render pending; rotation is preserved as the
        // requested angle and re-bakes from the cropped base.
        assertNull(cropped.renderedFilter)
        assertTrue("filter re-renders from the cropped base", cropped.renderPending)
        assertTrue("watermark re-renders", cropped.watermarkRenderPending)
        assertNull(cropped.watermarkRenderedUri)
        assertEquals("rotation request preserved", 90, cropped.requestedRotationDegrees)
        // Confirm is blocked until everything re-settles from the cropped pixels.
        assertFalse(cropped.canConfirm)
    }

    // --- B6: settled-vs-requested rotation preview — NEVER a display-rotation transform ---

    @Test
    fun rotateKeepsThePreviousSettledDisplayedUriUntilTheBakePublishes() {
        // Establish a settled 90° first (previous settled image is a baked landscape file).
        var s = PassportReviewFlow.rotate(state())
        s = PassportReviewFlow.withRotationRendered(s, base, 90, "file://p/rot90.jpg")
        assertEquals("file://p/rot90.jpg", s.displayedUri)

        // Requesting 180° must keep showing the SETTLED 90° image (fully contained) while 180 bakes.
        val requested = PassportReviewFlow.rotate(s)
        assertEquals(180, requested.requestedRotationDegrees)
        assertEquals("settled angle unchanged until the new bake lands", 90, requested.settledRotationDegrees)
        assertEquals("keeps the previous settled image", "file://p/rot90.jpg", requested.displayedUri)
        assertTrue(requested.rotationRenderPending)
        assertTrue(requested.rotationUnresolved)
        assertFalse("Confirm disabled while the requested rotation is unresolved", requested.canConfirm)
    }

    @Test
    fun theDisplayedUriIsAlwaysASettledFileNeverAnUnbakedPageUnderATransform() {
        // First rotate from upright: the previous settled image is the upright filtered page, so
        // that (fully contained) page is shown — never the target angle under a transform.
        val firstRotate = PassportReviewFlow.rotate(state())
        assertEquals(90, firstRotate.requestedRotationDegrees)
        assertEquals(0, firstRotate.settledRotationDegrees)
        assertEquals("previous settled upright page shown, no transform", base, firstRotate.displayedUri)

        // There is no state that asks the UI to rotate the image: displayedUri is always a settled
        // baked file and the settled angle is baked into its pixels.
        val settled = PassportReviewFlow.withRotationRendered(firstRotate, base, 90, "file://p/rot90.jpg")
        assertEquals(90, settled.settledRotationDegrees)
        assertEquals("file://p/rot90.jpg", settled.displayedUri)
    }

    @Test
    fun confirmIsDisabledWhileRotationRenderPending() {
        val requested = PassportReviewFlow.rotate(state())

        assertTrue(requested.rotationRenderPending)
        assertFalse(requested.canConfirm)
        assertNull("save is refused while rotation is unresolved", PassportReviewFlow.beginSave(requested))
    }

    @Test
    fun editingIsLockedWhileRotationIsUnresolved() {
        val requested = PassportReviewFlow.rotate(state())

        // rotationUnresolved is the single flag the screen disables Crop/Filter/Watermark on while
        // a rotation is resolving (and it also blocks Confirm).
        assertTrue("Crop/Filter/Watermark are locked while rotation resolves", requested.rotationUnresolved)

        // And it clears once the bake settles, re-enabling editing + Confirm.
        val settled = PassportReviewFlow.withRotationRendered(requested, base, 90, "file://p/rot90.jpg")
        assertFalse(settled.rotationUnresolved)
        assertTrue(settled.canConfirm)
    }

    @Test
    fun aMatchingRotationResultBecomesTheDisplayedUri() {
        val requested = PassportReviewFlow.rotate(state())

        val settled = PassportReviewFlow.withRotationRendered(requested, base, 90, "file://p/rot90.jpg")

        assertEquals("file://p/rot90.jpg", settled.displayedUri)
        assertEquals(90, settled.settledRotationDegrees)
        assertFalse(settled.rotationRenderPending)
        assertTrue(settled.canConfirm)
    }

    @Test
    fun aStaleRotationResultIsRejectedSoItsFileCanBeDeleted() {
        val requested = PassportReviewFlow.rotate(state()) // requests 90

        // A bake that finished for a superseded angle is ignored (the orchestrator deletes its file).
        val staleAngle = PassportReviewFlow.withRotationRendered(requested, base, 180, "file://p/rot180.jpg")
        assertEquals("stale angle rejected", requested, staleAngle)

        // A bake from a superseded filtered page is likewise rejected.
        val cropped = PassportReviewFlow.withCroppedBase(requested, "file://p/cropped.jpg")
        val staleSource = PassportReviewFlow.withRotationRendered(cropped, base, 90, "file://p/rot90.jpg")
        assertNull(staleSource.rotationRenderedUri)
    }

    @Test
    fun aFailedRotationBakeIsTruthfulRetryableAndNeverShownClipped() {
        var s = PassportReviewFlow.rotate(state())
        s = PassportReviewFlow.withRotationRendered(s, base, 90, "file://p/rot90.jpg") // settled 90
        val requested = PassportReviewFlow.rotate(s) // requests 180, keeps showing settled 90

        val failed = PassportReviewFlow.withRotationFailed(requested, 180)

        assertTrue(failed.rotationRenderError)
        assertFalse(failed.rotationRenderPending)
        assertTrue("still unresolved → Confirm disabled", failed.rotationUnresolved)
        assertFalse(failed.canConfirm)
        // The PREVIOUS settled (fully contained) image is still shown — never a clipped transform.
        assertEquals("file://p/rot90.jpg", failed.displayedUri)
        // Retryable: another Rotate tap clears the error and re-arms a bake.
        val retried = PassportReviewFlow.rotate(failed)
        assertFalse(retried.rotationRenderError)
        assertTrue(retried.rotationRenderPending)
    }

    @Test
    fun ninetyDegreesSettlesToABakedFileShownAsTheDisplayedImage() {
        val requested = PassportReviewFlow.rotate(state()) // 90
        val settled = PassportReviewFlow.withRotationRendered(requested, base, 90, "file://p/rot90-landscape.jpg")

        // The 90° pixels live in a distinct baked file that IS the displayed image (ContentScale.Fit
        // in the screen), not the upright base.
        assertEquals(90, settled.settledRotationDegrees)
        assertEquals("file://p/rot90-landscape.jpg", settled.displayedUri)
        assertNotEquals(base, settled.displayedUri)
    }

    @Test
    fun twoHundredSeventyDegreesSettlesToABakedFileShownAsTheDisplayedImage() {
        var s = state()
        var uri = ""
        listOf(90, 180, 270).forEach { degrees ->
            s = PassportReviewFlow.rotate(s)
            uri = "file://p/rot$degrees-landscape.jpg"
            s = PassportReviewFlow.withRotationRendered(s, base, degrees, uri)
        }
        assertEquals(270, s.settledRotationDegrees)
        assertEquals("file://p/rot270-landscape.jpg", s.displayedUri)
        assertNotEquals(base, s.displayedUri)
    }

    @Test
    fun fourSuccessfulRotationsReturnExactlyToTheOriginalUprightPage() {
        var s = state()
        listOf(90, 180, 270).forEach { degrees ->
            s = PassportReviewFlow.rotate(s)
            s = PassportReviewFlow.withRotationRendered(s, base, degrees, "file://p/rot$degrees.jpg")
        }
        // The fourth quarter turn returns to upright: settles instantly to the base, no bake, no
        // pending, no transform.
        s = PassportReviewFlow.rotate(s)
        assertEquals(0, s.requestedRotationDegrees)
        assertEquals(0, s.settledRotationDegrees)
        assertNull(s.rotationRenderedUri)
        assertFalse(s.rotationRenderPending)
        assertFalse(s.rotationUnresolved)
        assertEquals(base, s.displayedUri)
        assertTrue(s.canConfirm)
    }

    @Test
    fun theSaveInputEqualsTheDisplayedSettledRotationUri() {
        val requested = PassportReviewFlow.rotate(state())
        val settled = PassportReviewFlow.withRotationRendered(requested, base, 90, "file://p/rot90.jpg")

        // MainActivity.passportFinalUri(state) == Uri.parse(state.displayedUri); Confirm is gated on
        // canConfirm, so the save input is exactly this settled, contained image.
        assertTrue(settled.canConfirm)
        assertEquals("file://p/rot90.jpg", settled.displayedUri)
    }

    @Test
    fun theWatermarkIsRenderedFromTheSettledRotation() {
        // Rotate to a settled 90°, then add a watermark — the watermark render uses the REQUESTED
        // (== settled) rotation, and its result becomes the displayed/saved image.
        var s = PassportReviewFlow.rotate(state())
        s = PassportReviewFlow.withRotationRendered(s, base, 90, "file://p/rot90.jpg")
        s = PassportReviewFlow.withWatermark(s, "Copy")

        val rendered = PassportReviewFlow.withWatermarkRendered(s, "Copy", s.filteredUri, 90, "file://p/wm90.jpg")
        assertEquals("file://p/wm90.jpg", rendered.displayedUri)
        assertTrue(rendered.canConfirm)

        // A watermark result baked at a different rotation than requested is rejected.
        val stale = PassportReviewFlow.withWatermarkRendered(s, "Copy", s.filteredUri, 0, "file://p/wm0.jpg")
        assertNull(stale.watermarkRenderedUri)
    }
}
