package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import kotlin.math.abs

/** The resolved starting polygon plus where it came from. */
data class MainScanInitialPolygon(
    val quad: PerspectiveQuad,
    val source: MainScanPolygonSource
)

/**
 * Chooses the polygon the crop editor opens on, in strict priority order:
 *
 *     proven frozen-seed mapping -> valid captured-image detection -> full-image bounds
 *
 * ## Why the seed needs proving at all
 *
 * The frozen seed is expressed in normalized coordinates of the ROTATED ANALYSIS frame. The crop
 * editor works in normalized coordinates of the EXIF-upright CAPTURED image. Normalized coordinates
 * only transfer between the two when both describe the same field of view — that is, when their
 * aspect ratios agree. CameraX gives the analysis and capture streams the same FOV when their
 * aspect ratios match (both 4:3 on this device family), but nothing guarantees it: a device may
 * hand back a 16:9 analysis stream cropped from a 4:3 sensor, and then the seed's corners refer to
 * a narrower slice of the scene than the still contains.
 *
 * Applying a seed across that mismatch produces a crop that is confidently wrong — offset by exactly
 * the cropped band, with no visible symptom until the saved page is inspected. So the aspect ratios
 * are compared explicitly and a mismatch drops to the next source rather than guessing a correction.
 *
 * [ASPECT_TOLERANCE] absorbs integer rounding in the buffer dimensions (640x480 against 3060x4080
 * differ in the fourth decimal), not genuine format differences.
 */
object MainScanPolygonResolver {

    /** Permitted relative difference between analysis and capture aspect ratios. */
    const val ASPECT_TOLERANCE = 0.02f

    /**
     * Whether [seed]'s normalized corners may be applied directly to a captured image of
     * [captureWidth] x [captureHeight].
     *
     * Both sides are reduced to a rotation-independent "long edge over short edge" ratio, because
     * the seed is already upright while the capture may be either orientation.
     */
    fun isSeedMappable(
        seed: MainScanCropSeed?,
        captureWidth: Int,
        captureHeight: Int
    ): Boolean {
        if (seed == null) return false
        if (captureWidth <= 0 || captureHeight <= 0) return false

        val rotatedAnalysis = MainScanDetectionMapper.rotatedFrameSize(
            size = seed.analysisFrame,
            rotationDegrees = seed.rotationDegrees
        )
        if (rotatedAnalysis.width <= 0 || rotatedAnalysis.height <= 0) return false

        val analysisRatio = orientationFreeRatio(rotatedAnalysis.width, rotatedAnalysis.height)
        val captureRatio = orientationFreeRatio(captureWidth, captureHeight)
        if (analysisRatio <= 0f || captureRatio <= 0f) return false

        return abs(analysisRatio - captureRatio) / captureRatio <= ASPECT_TOLERANCE
    }

    /** Long edge divided by short edge — identical for a frame and its 90-degree rotation. */
    private fun orientationFreeRatio(width: Int, height: Int): Float {
        val longEdge = maxOf(width, height).toFloat()
        val shortEdge = minOf(width, height).toFloat()
        return if (shortEdge <= 0f) 0f else longEdge / shortEdge
    }

    /**
     * Resolves the initial polygon.
     *
     * [stillDetection] is the result of re-running detection on the captured image, or null when it
     * was not run or found nothing. It is only consulted when the seed cannot be proven, so the
     * common path costs no extra detection pass.
     *
     * Every candidate must survive [MainScanCropEditor.isApplicable]; anything that does not drops
     * to the next source. The final fallback is the full frame, which is always valid and always
     * obviously editable — never a guessed correction.
     */
    fun resolve(
        seed: MainScanCropSeed?,
        captureWidth: Int,
        captureHeight: Int,
        stillDetection: PerspectiveQuad? = null
    ): MainScanInitialPolygon {
        // Both candidates are SCORED, then compared. Mappability only makes the frozen seed
        // eligible; it does not make it better.
        //
        // The earlier resolver returned the seed the moment it was mappable and structurally
        // applicable. Physical QA showed why that fails: a seed spanning almost the whole frame —
        // a wall corner, not the document — was selected over the real package, because
        // "convex quad of reasonable area" describes a wall corner perfectly well. Eligibility and
        // quality are different questions, and only the second one should decide.
        val frozenCandidate = seed
            ?.takeIf { isSeedMappable(it, captureWidth, captureHeight) }
            ?.quad
            ?.takeIf { MainScanDocumentScore.isViable(it) }
        val stillCandidate = stillDetection?.takeIf { MainScanDocumentScore.isViable(it) }

        val frozenScore = frozenCandidate?.let { MainScanDocumentScore.overall(it) } ?: -1f
        val stillScore = stillCandidate?.let { MainScanDocumentScore.overall(it) } ?: -1f

        return when {
            frozenCandidate != null && stillCandidate != null ->
                // Ties go to the frozen seed: it is the boundary the user actually saw on the live
                // preview, so preferring it keeps the crop consistent with their expectation.
                if (stillScore > frozenScore) {
                    MainScanInitialPolygon(stillCandidate, MainScanPolygonSource.STILL_DETECTION)
                } else {
                    MainScanInitialPolygon(frozenCandidate, MainScanPolygonSource.FROZEN_SEED)
                }
            frozenCandidate != null ->
                MainScanInitialPolygon(frozenCandidate, MainScanPolygonSource.FROZEN_SEED)
            stillCandidate != null ->
                MainScanInitialPolygon(stillCandidate, MainScanPolygonSource.STILL_DETECTION)
            else -> MainScanInitialPolygon(
                quad = MainScanCropEditor.fullFrame(),
                source = MainScanPolygonSource.FULL_FRAME
            )
        }
    }

    /**
     * Whether the captured image needs a detection pass.
     *
     * Always true. Detection on the still is what supplies the second candidate, and without it
     * there is nothing to compare the frozen seed against — which is precisely how a weak seed came
     * to win unopposed. The cost is one pass over a 240px luma frame, which is negligible next to
     * the decode that has already happened, and correctness here is worth far more than skipping it.
     *
     * Retained as a named function rather than inlined so the reasoning has somewhere to live and a
     * future optimisation cannot quietly reintroduce the unopposed-seed path.
     */
    @Suppress("UNUSED_PARAMETER")
    fun needsStillDetection(
        seed: MainScanCropSeed?,
        captureWidth: Int,
        captureHeight: Int
    ): Boolean = true
}
