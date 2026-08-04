package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Eligibility and anti-flicker rules for the live guide. The flicker behaviour in particular is the
 * kind of thing normally "verified" by staring at a device and forming an impression; pinning it
 * here makes it an actual property.
 */
class MainScanGuideStateTest {

    private fun result(
        quad: PerspectiveQuad? = PerspectiveQuad.inset(0.15f),
        confidence: Float = 0.9f,
        isStable: Boolean = true,
        rotationDegrees: Int = 90
    ) = MainScanAnalysisResult(
        quad = quad,
        analysisFrame = FrameSize(640, 480),
        rotationDegrees = rotationDegrees,
        confidence = confidence,
        isStable = isStable,
        generation = 1L,
        timestampMs = 0L
    )

    // --- eligibility ------------------------------------------------------------------------------

    @Test
    fun aGoodDetectionIsEligible() {
        assertEquals(MainScanGuideRejection.NONE, MainScanGuideEligibility.evaluate(result()))
        assertTrue(MainScanGuideEligibility.isEligible(result()))
    }

    @Test
    fun nullResultOrNullQuadIsRejected() {
        assertEquals(MainScanGuideRejection.NO_QUAD, MainScanGuideEligibility.evaluate(null))
        assertEquals(
            MainScanGuideRejection.NO_QUAD,
            MainScanGuideEligibility.evaluate(result(quad = null))
        )
    }

    @Test
    fun nonConvexPolygonIsRejected() {
        // A bow-tie: the classic result of mis-ordered corners reaching the renderer.
        val crossed = PerspectiveQuad(
            topLeft = CropPoint(0.1f, 0.1f),
            topRight = CropPoint(0.9f, 0.9f),
            bottomRight = CropPoint(0.9f, 0.1f),
            bottomLeft = CropPoint(0.1f, 0.9f)
        )
        assertEquals(
            MainScanGuideRejection.NOT_CONVEX,
            MainScanGuideEligibility.evaluate(result(quad = crossed))
        )
    }

    @Test
    fun tinyPolygonIsRejected() {
        // Well under the 10% area floor — a speck, not a document.
        val tiny = PerspectiveQuad(
            topLeft = CropPoint(0.40f, 0.40f),
            topRight = CropPoint(0.50f, 0.40f),
            bottomRight = CropPoint(0.50f, 0.50f),
            bottomLeft = CropPoint(0.40f, 0.50f)
        )
        assertEquals(
            MainScanGuideRejection.TOO_SMALL,
            MainScanGuideEligibility.evaluate(result(quad = tiny))
        )
    }

    @Test
    fun wildlyOutOfBoundsPolygonIsRejected() {
        val escaped = PerspectiveQuad(
            topLeft = CropPoint(-0.4f, 0.1f),
            topRight = CropPoint(0.9f, 0.1f),
            bottomRight = CropPoint(0.9f, 0.9f),
            bottomLeft = CropPoint(-0.4f, 0.9f)
        )
        assertEquals(
            MainScanGuideRejection.OUT_OF_BOUNDS,
            MainScanGuideEligibility.evaluate(result(quad = escaped))
        )
    }

    @Test
    fun slightPerspectiveOvershootIsTolerated() {
        // Real perspective pushes a corner marginally outside the frame; that must still qualify.
        val overshoot = PerspectiveQuad(
            topLeft = CropPoint(-0.02f, 0.05f),
            topRight = CropPoint(1.01f, 0.08f),
            bottomRight = CropPoint(0.98f, 0.92f),
            bottomLeft = CropPoint(0.03f, 0.95f)
        )
        assertEquals(
            MainScanGuideRejection.NONE,
            MainScanGuideEligibility.evaluate(result(quad = overshoot))
        )
    }

    @Test
    fun lowConfidenceIsRejected() {
        assertEquals(
            MainScanGuideRejection.LOW_CONFIDENCE,
            MainScanGuideEligibility.evaluate(result(confidence = 0.4f))
        )
    }

    @Test
    fun unstableDetectionIsRejected() {
        assertEquals(
            MainScanGuideRejection.NOT_STABLE,
            MainScanGuideEligibility.evaluate(result(isStable = false))
        )
    }

    // --- visibility hysteresis ---------------------------------------------------------------------

    @Test
    fun guideAppearsOnlyAfterSustainedEligibility() {
        var state = MainScanGuideVisibility()
        repeat(MainScanGuideVisibilityReducer.FRAMES_TO_SHOW - 1) {
            state = MainScanGuideVisibilityReducer.update(state, eligible = true)
            assertFalse("must not appear on frame ${state.eligibleStreak}", state.visible)
        }
        state = MainScanGuideVisibilityReducer.update(state, eligible = true)
        assertTrue("appears once the streak is met", state.visible)
    }

    @Test
    fun guideSurvivesAShortDropout() {
        var state = MainScanGuideVisibility()
        repeat(MainScanGuideVisibilityReducer.FRAMES_TO_SHOW) {
            state = MainScanGuideVisibilityReducer.update(state, eligible = true)
        }
        assertTrue(state.visible)
        // A hand shadow crossing the page drops a frame or two — the guide must not blink out.
        repeat(MainScanGuideVisibilityReducer.FRAMES_TO_HIDE - 1) {
            state = MainScanGuideVisibilityReducer.update(state, eligible = false)
            assertTrue("must survive dropout ${state.ineligibleStreak}", state.visible)
        }
        state = MainScanGuideVisibilityReducer.update(state, eligible = false)
        assertFalse("withdrawn once the document is really gone", state.visible)
    }

    @Test
    fun alternatingVerdictsDoNotStrobe() {
        // The failure this prevents: eligible/ineligible alternating at the confidence boundary
        // producing a guide that flashes several times a second.
        var state = MainScanGuideVisibility()
        var transitions = 0
        var previous = state.visible
        repeat(40) { index ->
            state = MainScanGuideVisibilityReducer.update(state, eligible = index % 2 == 0)
            if (state.visible != previous) transitions++
            previous = state.visible
        }
        assertEquals("alternating input must never make the guide visible at all", 0, transitions)
        assertFalse(state.visible)
    }

    @Test
    fun aSingleEligibleFrameAmongNoiseNeverShowsTheGuide() {
        var state = MainScanGuideVisibility()
        repeat(10) {
            state = MainScanGuideVisibilityReducer.update(state, eligible = false)
            state = MainScanGuideVisibilityReducer.update(state, eligible = true)
        }
        assertFalse(state.visible)
    }

    @Test
    fun resetClearsVisibilityForANewVisit() {
        var state = MainScanGuideVisibility()
        repeat(MainScanGuideVisibilityReducer.FRAMES_TO_SHOW) {
            state = MainScanGuideVisibilityReducer.update(state, eligible = true)
        }
        assertTrue(state.visible)
        assertEquals(MainScanGuideVisibility(), MainScanGuideVisibilityReducer.reset())
    }

    @Test
    fun eligibleStreakResetsOnAnIneligibleFrame() {
        var state = MainScanGuideVisibility()
        state = MainScanGuideVisibilityReducer.update(state, eligible = true)
        state = MainScanGuideVisibilityReducer.update(state, eligible = true)
        assertEquals(2, state.eligibleStreak)
        state = MainScanGuideVisibilityReducer.update(state, eligible = false)
        assertEquals(0, state.eligibleStreak)
        assertEquals(1, state.ineligibleStreak)
    }
}
