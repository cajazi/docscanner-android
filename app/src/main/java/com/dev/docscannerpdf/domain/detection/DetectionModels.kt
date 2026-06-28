package com.dev.docscannerpdf.domain.detection

import com.dev.docscannerpdf.domain.crop.PerspectiveQuad

/**
 * A simplified, framework-free camera frame: a row-major grid of luma (brightness, 0..255)
 * values. Using a plain grid keeps the whole detection pipeline pure and deterministic — the
 * Android side converts a Bitmap/YUV image into this model before analysis.
 */
class LumaFrame(
    val width: Int,
    val height: Int,
    val luma: IntArray
) {
    init {
        require(width > 0 && height > 0) { "Frame must have positive dimensions" }
        require(luma.size == width * height) { "luma size must equal width*height" }
    }

    fun at(x: Int, y: Int): Int = luma[y * width + x]
}

/** A detected document boundary plus a 0f..1f confidence score. */
data class DetectedQuad(
    val quad: PerspectiveQuad,
    val confidence: Float
)

/** Tunables for [DocumentEdgeDetector]. No randomness — detection is fully deterministic. */
data class DetectionConfig(
    val contrastThreshold: Int = 36,
    val minForegroundFraction: Float = 0.03f,
    val maxForegroundFraction: Float = 0.97f
)

/** Tunables for [QuadStabilizer] temporal smoothing and the auto-capture gate. */
data class StabilizationConfig(
    val smoothing: Float = 0.5f,
    val movementThreshold: Float = 0.05f,
    val requiredStableFrames: Int = 5,
    val confidenceThreshold: Float = 0.6f
)

/**
 * Accumulated temporal state of the stabilizer. [smoothedQuad] is the jitter-suppressed quad,
 * [consistentFrames] counts consecutive low-movement high-confidence frames, and [isStable]
 * flips on once the required number is reached.
 */
data class StabilizerState(
    val smoothedQuad: PerspectiveQuad? = null,
    val confidence: Float = 0f,
    val movement: Float = 1f,
    val consistentFrames: Int = 0,
    val isStable: Boolean = false
) {
    val hasLock: Boolean get() = smoothedQuad != null
}

/** What the live overlay renders for the current frame. */
data class LiveDetectionUiState(
    val quad: PerspectiveQuad? = null,
    val confidence: Float = 0f,
    val isStable: Boolean = false,
    val readyToCapture: Boolean = false
)
