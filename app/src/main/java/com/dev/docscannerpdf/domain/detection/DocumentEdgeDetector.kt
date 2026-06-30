package com.dev.docscannerpdf.domain.detection

import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Deterministic document-boundary detector over a [LumaFrame]. It estimates the background from
 * the frame border, marks pixels that contrast against it as document foreground, then takes the
 * four extreme foreground points (min/max of x+y and y-x) as the quad corners. Because it works
 * from extreme points of the foreground region, it naturally tolerates rotation, perspective
 * distortion, and partial occlusion, and returns null when no plausible document is present.
 *
 * Identical input always yields identical output — there is no randomness or time dependence.
 */
object DocumentEdgeDetector {

    fun detect(frame: LumaFrame, config: DetectionConfig = DetectionConfig()): DetectedQuad? {
        if (frame.width < 3 || frame.height < 3) return null

        val background = estimateBackgroundLuma(frame)

        var count = 0
        // Extreme trackers for the four corners (sum = x+y, diff = y-x).
        var minSum = Float.MAX_VALUE; var maxSum = -Float.MAX_VALUE
        var minDiff = Float.MAX_VALUE; var maxDiff = -Float.MAX_VALUE
        var tl = CropPoint(0f, 0f) // min sum
        var br = CropPoint(0f, 0f) // max sum
        var tr = CropPoint(0f, 0f) // min diff
        var bl = CropPoint(0f, 0f) // max diff

        for (y in 0 until frame.height) {
            val rowBase = y * frame.width
            for (x in 0 until frame.width) {
                val luma = frame.luma[rowBase + x]
                if (abs(luma - background) < config.contrastThreshold) continue
                count++
                val fx = x.toFloat()
                val fy = y.toFloat()
                val sum = fx + fy
                val diff = fy - fx
                if (sum < minSum) { minSum = sum; tl = CropPoint(fx, fy) }
                if (sum > maxSum) { maxSum = sum; br = CropPoint(fx, fy) }
                if (diff < minDiff) { minDiff = diff; tr = CropPoint(fx, fy) }
                if (diff > maxDiff) { maxDiff = diff; bl = CropPoint(fx, fy) }
            }
        }

        val fraction = count.toFloat() / (frame.width * frame.height).toFloat()
        if (fraction < config.minForegroundFraction || fraction > config.maxForegroundFraction) {
            return null
        }

        val wScale = (frame.width - 1).coerceAtLeast(1).toFloat()
        val hScale = (frame.height - 1).coerceAtLeast(1).toFloat()
        val normalized = listOf(tl, tr, br, bl).map { CropPoint(it.x / wScale, it.y / hScale) }
        val quad = PerspectiveGeometry.orderCorners(normalized)

        if (!PerspectiveGeometry.isValid(quad)) return null

        return DetectedQuad(quad = quad, confidence = computeConfidence(quad, fraction))
    }

    /** Median luma of the border ring — a robust, deterministic background estimate. */
    private fun estimateBackgroundLuma(frame: LumaFrame): Int {
        val border = ArrayList<Int>(2 * (frame.width + frame.height))
        for (x in 0 until frame.width) {
            border.add(frame.at(x, 0))
            border.add(frame.at(x, frame.height - 1))
        }
        for (y in 1 until frame.height - 1) {
            border.add(frame.at(0, y))
            border.add(frame.at(frame.width - 1, y))
        }
        border.sort()
        return border[border.size / 2]
    }

    /**
     * Confidence from how rectangular the quad is (opposite-side symmetry) and how reasonable its
     * coverage of the frame is. Deterministic and bounded to 0f..1f.
     */
    private fun computeConfidence(quad: PerspectiveQuad, fraction: Float): Float {
        val top = distance(quad.topLeft, quad.topRight)
        val bottom = distance(quad.bottomLeft, quad.bottomRight)
        val left = distance(quad.topLeft, quad.bottomLeft)
        val right = distance(quad.topRight, quad.bottomRight)

        val horizontalSymmetry = ratio(top, bottom)
        val verticalSymmetry = ratio(left, right)
        val rectScore = (horizontalSymmetry + verticalSymmetry) / 2f
        val coverageScore = (fraction / 0.6f).coerceIn(0f, 1f)

        return (0.65f * rectScore + 0.35f * coverageScore).coerceIn(0f, 1f)
    }

    private fun ratio(a: Float, b: Float): Float {
        val hi = maxOf(a, b)
        if (hi <= 0f) return 0f
        return minOf(a, b) / hi
    }

    private fun distance(a: CropPoint, b: CropPoint): Float = hypot(a.x - b.x, a.y - b.y)
}
