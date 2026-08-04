package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Freezing the crop corners at shutter acceptance, and refusing to apply them anywhere else.
 *
 * The governing rule under test: a WRONG seed is worse than no seed. Every path that cannot be
 * proven to belong to this exact capture must resolve to the full frame.
 */
class MainScanCropSeedTest {

    private val detectedQuad = PerspectiveQuad.inset(0.12f)

    private fun eligibleResult(generation: Long = 1L) = MainScanAnalysisResult(
        quad = detectedQuad,
        analysisFrame = FrameSize(640, 480),
        rotationDegrees = 90,
        confidence = 0.92f,
        isStable = true,
        generation = generation,
        timestampMs = 0L
    )

    private fun visit(sessionId: Long) = MainScanCaptureState(sessionId = sessionId)

    // --- freezing ----------------------------------------------------------------------------------

    @Test
    fun anEligibleDetectionFreezesAgainstTheTicket() {
        val ticket = MainScanCaptureTicket(sessionId = 7L, generation = 3L)
        val seed = MainScanCropSeeding.freeze(ticket, eligibleResult(), guideVisible = true, timestampMs = 123L)

        assertNotNull(seed)
        assertEquals(7L, seed!!.sessionId)
        assertEquals(3L, seed.generation)
        assertEquals(detectedQuad, seed.quad)
        assertEquals(FrameSize(640, 480), seed.analysisFrame)
        assertEquals(90, seed.rotationDegrees)
        assertEquals(123L, seed.timestampMs)
        assertTrue(seed.matches(ticket))
    }

    @Test
    fun anIneligibleDetectionFreezesNothing() {
        val ticket = MainScanCaptureTicket(sessionId = 1L, generation = 1L)
        assertNull("no detection at all", MainScanCropSeeding.freeze(ticket, null, guideVisible = true, timestampMs = 0L))
        assertNull(
            "unstable detection",
            MainScanCropSeeding.freeze(ticket, eligibleResult().copy(isStable = false), guideVisible = true, timestampMs = 0L)
        )
        assertNull(
            "low confidence",
            MainScanCropSeeding.freeze(ticket, eligibleResult().copy(confidence = 0.2f), guideVisible = true, timestampMs = 0L)
        )
        assertNull(
            "no quad",
            MainScanCropSeeding.freeze(ticket, eligibleResult().copy(quad = null), guideVisible = true, timestampMs = 0L)
        )
    }

    @Test
    fun theSeedTheGuideWouldNotShowIsNeverFrozen() {
        // The promise is that the crop opens on the corners the user was just shown. Anything the
        // guide would have suppressed must not become the crop behind their back.
        val ticket = MainScanCaptureTicket(sessionId = 1L, generation = 1L)
        val marginal = eligibleResult().copy(confidence = MainScanGuideEligibility.MIN_CONFIDENCE - 0.01f)
        assertFalse(MainScanGuideEligibility.isEligible(marginal))
        assertNull(MainScanCropSeeding.freeze(ticket, marginal, guideVisible = true, timestampMs = 0L))
    }

    // --- the guide must have been VISIBLE, not merely eligible --------------------------------------

    @Test
    fun anEligibleDetectionIsNotFrozenWhileTheGuideIsHidden() {
        // The regression this pins: eligibility is a PER-FRAME verdict, while visibility requires
        // FRAMES_TO_SHOW consecutive eligible frames. Freezing on eligibility alone let a shutter tap
        // land on the first qualifying frame and seed the crop with corners that were never drawn.
        val ticket = MainScanCaptureTicket(sessionId = 1L, generation = 1L)
        assertTrue("the detection itself qualifies", MainScanGuideEligibility.isEligible(eligibleResult()))
        assertNull(
            "but nothing may be frozen until the user has actually seen it",
            MainScanCropSeeding.freeze(ticket, eligibleResult(), guideVisible = false, timestampMs = 0L)
        )
    }

    @Test
    fun aCaptureTakenBeforeTheGuideAppearsOpensFullFrame() {
        // Walk the real acquisition sequence: the guide needs consecutive eligible frames, so a tap
        // during acquisition must still produce a capture — just a full-frame one.
        var visibility = MainScanGuideVisibility()
        repeat(MainScanGuideVisibilityReducer.FRAMES_TO_SHOW - 1) {
            visibility = MainScanGuideVisibilityReducer.update(visibility, eligible = true)
        }
        assertFalse("guide is not on screen yet", visibility.visible)

        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(
            state = visit(sessionId = 9L),
            seedCandidate = eligibleResult(),
            guideVisible = visibility.visible,
            timestampMs = 0L
        )!!
        assertNull(capturing.frozenCropSeed)

        val published = MainScanCaptureFlow.captureSucceeded(
            state = capturing,
            ticket = ticket,
            uri = "file:///files/main_scan_capture/page.jpg",
            source = MainScanPageSource.CAMERA
        )
        assertNotNull("the capture itself is never gated on the guide", published.pendingPage)
        assertEquals(PerspectiveQuad.full(), published.pendingPage!!.cropSeedQuad)
    }

    @Test
    fun onceTheGuideIsVisibleTheSameQuadSeedsTheCrop() {
        var visibility = MainScanGuideVisibility()
        repeat(MainScanGuideVisibilityReducer.FRAMES_TO_SHOW) {
            visibility = MainScanGuideVisibilityReducer.update(visibility, eligible = true)
        }
        assertTrue(visibility.visible)

        val (capturing, _) = MainScanCaptureFlow.beginCapture(
            state = visit(sessionId = 9L),
            seedCandidate = eligibleResult(),
            guideVisible = visibility.visible,
            timestampMs = 0L
        )!!
        assertEquals(
            "the crop opens on exactly the boundary that was on screen",
            detectedQuad,
            capturing.frozenCropSeed!!.quad
        )
    }

    // --- resolution and fallback --------------------------------------------------------------------

    @Test
    fun aMatchingSeedResolvesToItsCorners() {
        val ticket = MainScanCaptureTicket(sessionId = 2L, generation = 5L)
        val seed = MainScanCropSeeding.freeze(ticket, eligibleResult(), guideVisible = true, timestampMs = 0L)
        assertEquals(detectedQuad, MainScanCropSeeding.resolveQuad(seed, ticket))
        assertTrue(MainScanCropSeeding.hasUsableSeed(seed, ticket))
    }

    @Test
    fun noSeedFallsBackToFullFrame() {
        val ticket = MainScanCaptureTicket(sessionId = 2L, generation = 5L)
        assertEquals(PerspectiveQuad.full(), MainScanCropSeeding.resolveQuad(null, ticket))
        assertFalse(MainScanCropSeeding.hasUsableSeed(null, ticket))
    }

    @Test
    fun aSeedFromAnEarlierVisitIsRejected() {
        val oldTicket = MainScanCaptureTicket(sessionId = 1L, generation = 1L)
        val seed = MainScanCropSeeding.freeze(oldTicket, eligibleResult(), guideVisible = true, timestampMs = 0L)
        // The user backed out and re-entered: same generation number, different visit.
        val newTicket = MainScanCaptureTicket(sessionId = 2L, generation = 1L)

        assertFalse(seed!!.matches(newTicket))
        assertEquals(
            "must fall back to full frame, never the previous visit's corners",
            PerspectiveQuad.full(),
            MainScanCropSeeding.resolveQuad(seed, newTicket)
        )
    }

    @Test
    fun aSeedFromASupersededCaptureInTheSameVisitIsRejected() {
        val first = MainScanCaptureTicket(sessionId = 3L, generation = 1L)
        val seed = MainScanCropSeeding.freeze(first, eligibleResult(), guideVisible = true, timestampMs = 0L)
        val retake = MainScanCaptureTicket(sessionId = 3L, generation = 2L)

        assertFalse(seed!!.matches(retake))
        assertEquals(PerspectiveQuad.full(), MainScanCropSeeding.resolveQuad(seed, retake))
    }

    // --- integration with the capture reducer -------------------------------------------------------

    @Test
    fun beginCaptureFreezesTheSeedOntoTheIssuedTicket() {
        val (state, ticket) = MainScanCaptureFlow.beginCapture(
            state = visit(sessionId = 4L),
            seedCandidate = eligibleResult(),
            guideVisible = true,
            timestampMs = 99L
        )!!
        assertNotNull(state.frozenCropSeed)
        assertTrue(state.frozenCropSeed!!.matches(ticket))
        assertEquals(detectedQuad, state.frozenCropSeed.quad)
    }

    @Test
    fun aLaterDetectionCannotAlterTheFrozenSeed() {
        // The detector keeps running during the exposure. Whatever it produces next is irrelevant:
        // the seed was frozen at acceptance and the state carries that value unchanged.
        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(
            state = visit(sessionId = 4L),
            seedCandidate = eligibleResult(generation = 1L),
            guideVisible = true,
            timestampMs = 10L
        )!!
        val frozen = capturing.frozenCropSeed

        val published = MainScanCaptureFlow.captureSucceeded(
            state = capturing,
            ticket = ticket,
            uri = "file:///files/main_scan_capture/page.jpg",
            source = MainScanPageSource.CAMERA
        )
        assertEquals("the seed is untouched by publication", frozen, published.frozenCropSeed)
        assertEquals(detectedQuad, published.pendingPage!!.cropSeedQuad)
    }

    @Test
    fun capturingWithNoDetectionStillSucceedsAndOpensFullFrame() {
        // The shutter is never gated on detection — this is the ordinary "point at a dark desk" case.
        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(
            state = visit(sessionId = 5L),
            seedCandidate = null,
            guideVisible = false,
            timestampMs = 0L
        )!!
        assertNull(capturing.frozenCropSeed)

        val published = MainScanCaptureFlow.captureSucceeded(
            state = capturing,
            ticket = ticket,
            uri = "file:///files/main_scan_capture/page.jpg",
            source = MainScanPageSource.CAMERA
        )
        assertNotNull("capture must still succeed", published.pendingPage)
        assertEquals(PerspectiveQuad.full(), published.pendingPage!!.cropSeedQuad)
    }

    @Test
    fun aNewVisitDoesNotInheritThePreviousVisitsSeed() {
        val (capturing, _) = MainScanCaptureFlow.beginCapture(
            state = visit(sessionId = 6L),
            seedCandidate = eligibleResult(),
            guideVisible = true,
            timestampMs = 0L
        )!!
        assertNotNull(capturing.frozenCropSeed)

        val nextVisit = MainScanCaptureFlow.beginVisit(capturing)
        assertNull("a fresh visit starts with no seed", nextVisit.frozenCropSeed)
    }

    @Test
    fun aStaleCaptureResultPublishesNoPageAndThereforeNoSeed() {
        val visitA = visit(sessionId = 8L)
        val (capturingA, ticketA) = MainScanCaptureFlow.beginCapture(
            state = visitA,
            seedCandidate = eligibleResult(),
            guideVisible = true,
            timestampMs = 0L
        )!!
        val visitB = MainScanCaptureFlow.beginVisit(capturingA)

        val late = MainScanCaptureFlow.captureSucceeded(
            state = visitB,
            ticket = ticketA,
            uri = "file:///files/main_scan_capture/stale.jpg",
            source = MainScanPageSource.CAMERA
        )
        assertNull(late.pendingPage)
        assertNull(late.frozenCropSeed)
    }
}
