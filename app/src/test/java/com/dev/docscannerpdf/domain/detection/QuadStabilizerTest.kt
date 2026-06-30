package com.dev.docscannerpdf.domain.detection

import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuadStabilizerTest {

    private val config = StabilizationConfig(
        smoothing = 0.5f,
        movementThreshold = 0.05f,
        requiredStableFrames = 5,
        confidenceThreshold = 0.6f
    )

    private fun quad(offset: Float = 0f) = PerspectiveQuad(
        topLeft = CropPoint(0.1f + offset, 0.1f + offset),
        topRight = CropPoint(0.9f + offset, 0.1f + offset),
        bottomRight = CropPoint(0.9f + offset, 0.9f + offset),
        bottomLeft = CropPoint(0.1f + offset, 0.9f + offset)
    )

    private fun detection(offset: Float = 0f, confidence: Float = 0.9f) =
        DetectedQuad(quad(offset), confidence)

    // --- Stability threshold validation -------------------------------------

    @Test
    fun becomesStableOnlyAfterRequiredConsistentFrames() {
        var state = StabilizerState()
        // 4 consistent frames: locked but not yet stable.
        repeat(4) { state = QuadStabilizer.update(state, detection(), config) }
        assertTrue(state.hasLock)
        assertFalse(state.isStable)
        assertEquals(4, state.consistentFrames)

        // 5th consistent frame crosses the threshold.
        state = QuadStabilizer.update(state, detection(), config)
        assertTrue(state.isStable)
        assertTrue(AutoCapturePolicy.isReadyToCapture(state, config))
    }

    @Test
    fun lowConfidenceNeverBecomesStable() {
        var state = StabilizerState()
        repeat(10) { state = QuadStabilizer.update(state, detection(confidence = 0.4f), config) }
        assertFalse(state.isStable)
        assertFalse(AutoCapturePolicy.isReadyToCapture(state, config))
    }

    // --- Jitter suppression -------------------------------------------------

    @Test
    fun jitterIsSuppressedAndDoesNotPreventStability() {
        val noise = 0.02f
        var state = StabilizerState()
        repeat(12) { i ->
            val sign = if (i % 2 == 0) 1f else -1f
            state = QuadStabilizer.update(state, detection(offset = noise * sign), config)
        }
        // Small alternating jitter stays under the movement threshold, so it still locks stable.
        assertTrue(state.isStable)
        // The smoothed quad sits much closer to the base than the raw jitter amplitude.
        val deviation = QuadStabilizer.averageCornerDistance(state.smoothedQuad!!, quad(0f))
        assertTrue("smoothed deviation $deviation should be below jitter $noise", deviation < noise)
    }

    @Test
    fun largeMovementResetsStability() {
        var state = StabilizerState()
        repeat(6) { state = QuadStabilizer.update(state, detection(), config) }
        assertTrue(state.isStable)

        // A big jump (well beyond the movement threshold) breaks the lock.
        state = QuadStabilizer.update(state, detection(offset = 0.4f), config)
        assertFalse(state.isStable)
        assertEquals(0, state.consistentFrames)
    }

    // --- Null-case handling -------------------------------------------------

    @Test
    fun nullDetectionDropsTheLock() {
        var state = StabilizerState()
        repeat(6) { state = QuadStabilizer.update(state, detection(), config) }
        assertTrue(state.isStable)

        state = QuadStabilizer.update(state, null, config)
        assertFalse(state.hasLock)
        assertFalse(state.isStable)
        assertEquals(0, state.consistentFrames)
    }

    // --- Deterministic output -----------------------------------------------

    @Test
    fun stabilizationIsDeterministic() {
        fun run(): StabilizerState {
            var state = StabilizerState()
            val offsets = listOf(0f, 0.01f, -0.01f, 0.005f, 0f, 0.02f)
            offsets.forEach { state = QuadStabilizer.update(state, detection(offset = it), config) }
            return state
        }
        assertEquals(run(), run())
    }
}
