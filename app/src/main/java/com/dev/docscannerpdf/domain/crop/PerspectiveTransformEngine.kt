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
     * Plans the warp for [normalizedQuad] over a [sourceWidth] x [sourceHeight] image. The quad is
     * clamped into the image and its corners reordered, so inverted corner input is auto-corrected.
     *
     * The reorder happens AFTER the conversion to source pixels, and that ordering is what decides
     * which corner becomes the output's top-left. Deciding it in normalized coordinates — as this
     * did previously, through [PerspectiveGeometry.normalize] — makes the answer depend on the
     * source's aspect ratio: x carries the width scale and y the height scale, so the anchor rule's
     * `x + y` comparison is taken in a stretched space. A portrait page tilted 40 degrees in a
     * 3060x4080 capture came out quarter-turned from the polygon the user had confirmed, with its
     * output dimensions transposed, while the identical physical page in a square image did not.
     * Source pixels are isotropic, so the same physical quad now yields the same physical anchor at
     * every aspect ratio.
     *
     * [PerspectiveGeometry.orderCorners] itself is unchanged and is still handed all four points: it
     * remains a total function over arbitrary corner order, which
     * [com.dev.docscannerpdf.domain.detection.DocumentEdgeDetector] relies on to partition the four
     * points its independent extremum scans produce.
     */
    fun plan(
        normalizedQuad: PerspectiveQuad,
        sourceWidth: Int,
        sourceHeight: Int
    ): WarpPlan {
        // Clamping stays a normalized-space operation — the unit square is what "inside the image"
        // means, and it is independent of the pixel dimensions.
        val clamped = PerspectiveGeometry.clampToUnit(normalizedQuad)
        val src = PerspectiveGeometry.orderCorners(
            clamped.corners().map { CropPoint(it.x * sourceWidth, it.y * sourceHeight) }
        ).corners()

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

    /**
     * Exposes the quad-to-quad homography directly so the same warp the crop applies to the
     * image can be reused to project other layers (e.g. annotations) through the identical
     * transform. Corners are paired in TL/TR/BR/BL order.
     */
    fun quadToQuadMatrix(source: PerspectiveQuad, target: PerspectiveQuad): Matrix3x3 =
        WarpMatrixCalculator.computeHomography(source.corners(), target.corners())
}
