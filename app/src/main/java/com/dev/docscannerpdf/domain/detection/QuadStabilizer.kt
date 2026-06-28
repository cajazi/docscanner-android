package com.dev.docscannerpdf.domain.detection

import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import kotlin.math.hypot

/**
 * Temporal smoothing + stability tracking for live detection. Pure reducer: [update] folds a
 * new per-frame [DetectedQuad] into the accumulated [StabilizerState] with no side effects, so
 * jitter suppression and the stability threshold are deterministic and unit-testable.
 *
 * Jitter is suppressed by exponential moving average of corner positions; a "stable" lock is
 * declared only after [StabilizationConfig.requiredStableFrames] consecutive frames whose
 * movement stays under threshold and whose confidence clears the bar.
 */
object QuadStabilizer {

    fun update(
        state: StabilizerState,
        detection: DetectedQuad?,
        config: StabilizationConfig = StabilizationConfig()
    ): StabilizerState {
        // Lost detection -> drop the lock (no document / occluded).
        if (detection == null) {
            return StabilizerState()
        }

        val previous = state.smoothedQuad
        if (previous == null) {
            // First acquisition: adopt the quad, no movement yet, one consistent frame.
            return StabilizerState(
                smoothedQuad = detection.quad,
                confidence = detection.confidence,
                movement = 0f,
                consistentFrames = 1,
                isStable = false
            )
        }

        val movement = averageCornerDistance(previous, detection.quad)
        val smoothed = lerpQuad(previous, detection.quad, config.smoothing)
        val consistent = movement <= config.movementThreshold &&
            detection.confidence >= config.confidenceThreshold
        val consistentFrames = if (consistent) state.consistentFrames + 1 else 0
        val isStable = consistentFrames >= config.requiredStableFrames &&
            detection.confidence >= config.confidenceThreshold &&
            movement <= config.movementThreshold

        return StabilizerState(
            smoothedQuad = smoothed,
            confidence = detection.confidence,
            movement = movement,
            consistentFrames = consistentFrames,
            isStable = isStable
        )
    }

    /** Mean Euclidean distance between corresponding corners (normalized space). */
    fun averageCornerDistance(a: PerspectiveQuad, b: PerspectiveQuad): Float {
        val ac = a.corners()
        val bc = b.corners()
        var total = 0f
        for (i in ac.indices) {
            total += hypot(ac[i].x - bc[i].x, ac[i].y - bc[i].y)
        }
        return total / ac.size
    }

    private fun lerpQuad(from: PerspectiveQuad, to: PerspectiveQuad, t: Float): PerspectiveQuad {
        val clamped = t.coerceIn(0f, 1f)
        val f = from.corners()
        val g = to.corners()
        val mixed = f.indices.map { i -> lerpPoint(f[i], g[i], clamped) }
        return PerspectiveQuad(mixed[0], mixed[1], mixed[2], mixed[3])
    }

    private fun lerpPoint(a: CropPoint, b: CropPoint, t: Float): CropPoint =
        CropPoint(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
}

/**
 * Auto-capture gate (requirement 4). Capture is allowed only when the detection is stable, the
 * confidence clears the threshold, and there is no significant movement.
 */
object AutoCapturePolicy {
    fun isReadyToCapture(
        state: StabilizerState,
        config: StabilizationConfig = StabilizationConfig()
    ): Boolean =
        state.isStable &&
            state.confidence >= config.confidenceThreshold &&
            state.movement <= config.movementThreshold

    /** Builds the overlay-facing UI state from the current stabilizer state. */
    fun toUiState(
        state: StabilizerState,
        config: StabilizationConfig = StabilizationConfig()
    ): LiveDetectionUiState = LiveDetectionUiState(
        quad = state.smoothedQuad,
        confidence = state.confidence,
        isStable = state.isStable,
        readyToCapture = isReadyToCapture(state, config)
    )
}
