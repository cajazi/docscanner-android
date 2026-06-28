package com.dev.docscannerpdf.domain.crop

import kotlin.math.hypot
import kotlin.math.roundToInt

/** Result of planning a perspective warp: the matrix plus the source/destination pixel quads. */
data class WarpPlan(
    val matrix: Matrix3x3,
    val outputWidth: Int,
    val outputHeight: Int,
    val sourcePixels: List<CropPoint>,
    val destinationPixels: List<CropPoint>
)

/**
 * Pure orchestration of the crop warp. Given a normalized quad and the source image pixel
 * dimensions, it derives a de-skewed output rectangle (sized to the quad's edge lengths) and
 * the homography that maps the source corners onto it. Kept free of Android types so the warp
 * math is deterministic and unit-testable; the Android side only needs to feed the matrix into
 * a Canvas draw.
 */
object PerspectiveTransformEngine {

    /**
     * Plans the warp for [normalizedQuad] over a [sourceWidth] x [sourceHeight] image. The quad
     * is normalized (clamped + reordered) first so inverted corner input is auto-corrected.
     */
    fun plan(
        normalizedQuad: PerspectiveQuad,
        sourceWidth: Int,
        sourceHeight: Int
    ): WarpPlan {
        val quad = PerspectiveGeometry.normalize(normalizedQuad)
        val src = quad.corners().map { CropPoint(it.x * sourceWidth, it.y * sourceHeight) }

        val widthTop = distance(src[0], src[1])
        val widthBottom = distance(src[3], src[2])
        val heightLeft = distance(src[0], src[3])
        val heightRight = distance(src[1], src[2])

        val outWidth = maxOf(widthTop, widthBottom).roundToInt().coerceAtLeast(1)
        val outHeight = maxOf(heightLeft, heightRight).roundToInt().coerceAtLeast(1)

        val dst = listOf(
            CropPoint(0f, 0f),
            CropPoint(outWidth.toFloat(), 0f),
            CropPoint(outWidth.toFloat(), outHeight.toFloat()),
            CropPoint(0f, outHeight.toFloat())
        )

        return WarpPlan(
            matrix = WarpMatrixCalculator.computeHomography(src, dst),
            outputWidth = outWidth,
            outputHeight = outHeight,
            sourcePixels = src,
            destinationPixels = dst
        )
    }

    private fun distance(a: CropPoint, b: CropPoint): Float = hypot(a.x - b.x, a.y - b.y)
}
