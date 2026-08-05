package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import kotlin.math.abs

/**
 * The single immutable value the analysis thread publishes. Nothing else crosses the thread
 * boundary: the analyzer computes this, hands it over, and forgets it.
 *
 * [quad] is already in ROTATED normalized space (space 4 of [MainScanDetectionMapper]) with corner
 * roles restored, so the UI never re-does sensor math inside a draw call. [analysisFrame] is the
 * UNROTATED buffer size and [rotationDegrees] the rotation that was applied — both retained because
 * Slice 3 needs them to relate this quad to the captured still.
 *
 * [generation] is a monotonic analysis counter used to discard a result that arrives after a newer
 * one (the analyzer is single-threaded, but publication and consumption are not synchronised).
 */
data class MainScanAnalysisResult(
    val quad: PerspectiveQuad?,
    val analysisFrame: FrameSize,
    val rotationDegrees: Int,
    val confidence: Float,
    val isStable: Boolean,
    val generation: Long,
    val timestampMs: Long
)

/** Why a detection is not eligible to be shown or frozen. Drives tracing, never user-facing text. */
enum class MainScanGuideRejection {
    NONE,
    NO_QUAD,
    NOT_CONVEX,
    TOO_SMALL,
    OUT_OF_BOUNDS,
    LOW_CONFIDENCE,
    NOT_STABLE
}

/**
 * Pure eligibility rules for the live guide and the crop seed. A detection must clear EVERY rule:
 * a guide drawn around a non-convex or hairline polygon is worse than no guide at all, because the
 * user reasonably reads it as "the app has found my document".
 */
object MainScanGuideEligibility {

    /** A quad must cover at least this fraction of the frame to be a plausible document. */
    const val MIN_AREA_FRACTION = 0.10f

    /** Corners may sit slightly outside the frame (perspective) but not wildly so. */
    const val BOUNDS_TOLERANCE = 0.05f

    /** Detector confidence required before the guide is shown. */
    const val MIN_CONFIDENCE = 0.60f

    /**
     * Evaluates [result], returning [MainScanGuideRejection.NONE] when the detection may be shown
     * and frozen. Order is deliberate: cheap structural checks first, so a tracing caller sees the
     * most specific reason rather than a generic one.
     */
    fun evaluate(result: MainScanAnalysisResult?): MainScanGuideRejection {
        val quad = result?.quad ?: return MainScanGuideRejection.NO_QUAD
        if (!PerspectiveGeometry.isConvex(quad)) return MainScanGuideRejection.NOT_CONVEX
        if (abs(PerspectiveGeometry.signedArea(quad)) < MIN_AREA_FRACTION) {
            return MainScanGuideRejection.TOO_SMALL
        }
        if (!withinBounds(quad)) return MainScanGuideRejection.OUT_OF_BOUNDS
        if (result.confidence < MIN_CONFIDENCE) return MainScanGuideRejection.LOW_CONFIDENCE
        if (!result.isStable) return MainScanGuideRejection.NOT_STABLE
        return MainScanGuideRejection.NONE
    }

    fun isEligible(result: MainScanAnalysisResult?): Boolean =
        evaluate(result) == MainScanGuideRejection.NONE

    private fun withinBounds(quad: PerspectiveQuad): Boolean {
        val low = -BOUNDS_TOLERANCE
        val high = 1f + BOUNDS_TOLERANCE
        return quad.corners().all { it.x in low..high && it.y in low..high }
    }
}

/**
 * Visibility of the live guide, with hysteresis.
 *
 * The detector is per-frame and its verdict flickers at the eligibility boundary — a page at
 * marginal confidence can oscillate eligible/ineligible several times a second. Showing the raw
 * verdict produces a strobing outline, which reads as broken. So visibility is debounced
 * asymmetrically: appearing requires sustained evidence, disappearing tolerates a short gap. The
 * asymmetry matters — a guide that vanishes on a single dropped frame is as distracting as one that
 * strobes, and a hand shadow crossing the page drops exactly one or two frames.
 */
data class MainScanGuideVisibility(
    val visible: Boolean = false,
    val eligibleStreak: Int = 0,
    val ineligibleStreak: Int = 0
)

object MainScanGuideVisibilityReducer {

    /** Consecutive eligible frames required before the guide appears. */
    const val FRAMES_TO_SHOW = 3

    /** Consecutive ineligible frames tolerated before the guide is withdrawn. */
    const val FRAMES_TO_HIDE = 5

    /**
     * Folds one eligibility verdict into [state]. Pure, so the anti-flicker behaviour is asserted by
     * test rather than judged by eye on a device.
     */
    fun update(state: MainScanGuideVisibility, eligible: Boolean): MainScanGuideVisibility =
        if (eligible) {
            val streak = state.eligibleStreak + 1
            MainScanGuideVisibility(
                visible = state.visible || streak >= FRAMES_TO_SHOW,
                eligibleStreak = streak,
                ineligibleStreak = 0
            )
        } else {
            val streak = state.ineligibleStreak + 1
            MainScanGuideVisibility(
                visible = state.visible && streak < FRAMES_TO_HIDE,
                eligibleStreak = 0,
                ineligibleStreak = streak
            )
        }

    /** Clears visibility outright — used when the camera surface is left or a visit ends. */
    fun reset(): MainScanGuideVisibility = MainScanGuideVisibility()
}
