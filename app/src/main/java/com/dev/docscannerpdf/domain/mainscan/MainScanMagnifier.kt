package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.CropPoint

/** Where the loupe is drawn, in viewport pixels, plus the radius it occupies. */
data class MagnifierPlacement(
    val centerX: Float,
    val centerY: Float,
    val radius: Float
)

/**
 * The region of the SOURCE image the loupe shows, in normalized image coordinates. A square window
 * centred on the dragged control point; the renderer samples exactly this and nothing else.
 */
data class MagnifierSource(
    val centerX: Float,
    val centerY: Float,
    val halfExtent: Float
) {
    val left: Float get() = centerX - halfExtent
    val top: Float get() = centerY - halfExtent
    val right: Float get() = centerX + halfExtent
    val bottom: Float get() = centerY + halfExtent
}

/**
 * The size the WHOLE rendered image is drawn at inside the loupe, in viewport pixels.
 *
 * Expressed against the rendered image rather than the source bitmap, because magnification is only
 * meaningful relative to what the user can actually see on screen.
 */
data class MagnifiedImageSize(
    val width: Float,
    val height: Float
)

/**
 * Pure geometry for the precision magnifier shown while a crop handle is dragged.
 *
 * The loupe exists because a fingertip covers roughly 8–10 mm of screen — far more than the corner
 * it is placing. Without magnification the user physically cannot see the pixel they are aligning
 * to, so corner placement becomes guesswork and the resulting crop is visibly off. The loupe shows
 * the covered pixels somewhere the finger is not.
 *
 * Two rules govern placement, and both matter:
 *
 * 1. It must never sit under the finger, or it shows the user their own hand.
 * 2. It must never leave the viewport, or the magnified content is clipped exactly when the user is
 *    working at an edge — which is precisely when corners are hardest to place.
 *
 * Framework-free so the placement flips and the sampling window are unit-testable; the renderer only
 * draws what this returns.
 */
object MainScanMagnifier {

    /** Loupe radius as a fraction of the shorter viewport edge. */
    const val RADIUS_FRACTION = 0.17f

    /** How far the loupe centre sits from the touch point, in loupe radii. */
    const val OFFSET_RADII = 1.7f

    /** Magnification applied to the source window. */
    const val MAGNIFICATION = 2.6f

    /** Minimum gap between the loupe edge and the viewport edge, in loupe radii. */
    private const val VIEWPORT_MARGIN_RADII = 0.12f

    fun radiusFor(viewportWidth: Float, viewportHeight: Float): Float =
        minOf(viewportWidth, viewportHeight) * RADIUS_FRACTION

    /**
     * Places the loupe for a touch at ([touchX], [touchY]) in viewport pixels.
     *
     * It is offset up and to the left by default — the natural resting position of a right hand
     * covers down and to the right. Each axis independently flips to the opposite side when the
     * preferred position would push the loupe out of the viewport, and only then is it clamped. The
     * flip is what keeps the loupe clear of the finger; clamping alone would slide it back under the
     * hand at the top-left corner.
     */
    fun place(
        touchX: Float,
        touchY: Float,
        viewportWidth: Float,
        viewportHeight: Float
    ): MagnifierPlacement {
        val radius = radiusFor(viewportWidth, viewportHeight)
        if (radius <= 0f) return MagnifierPlacement(touchX, touchY, 0f)
        val offset = radius * OFFSET_RADII
        val margin = radius * (1f + VIEWPORT_MARGIN_RADII)

        var centerX = touchX - offset
        if (centerX < margin) {
            val flipped = touchX + offset
            centerX = if (flipped + margin <= viewportWidth) flipped else margin
        }
        var centerY = touchY - offset
        if (centerY < margin) {
            val flipped = touchY + offset
            centerY = if (flipped + margin <= viewportHeight) flipped else margin
        }
        centerX = centerX.coerceIn(margin, (viewportWidth - margin).coerceAtLeast(margin))
        centerY = centerY.coerceIn(margin, (viewportHeight - margin).coerceAtLeast(margin))
        return MagnifierPlacement(centerX = centerX, centerY = centerY, radius = radius)
    }

    /**
     * The normalized source window the loupe magnifies, centred on the control point being dragged.
     *
     * The window is NOT clamped to the image. Clamping would slide the window sideways as the user
     * approached an edge, so the crosshair would stop marking the point actually being placed —
     * the loupe would quietly start lying about where the corner is. Out-of-range content renders as
     * empty instead, which is honest and stays aligned.
     */
    fun sourceWindow(
        point: CropPoint,
        viewportWidth: Float,
        viewportHeight: Float,
        renderedWidth: Float,
        renderedHeight: Float
    ): MagnifierSource {
        val radius = radiusFor(viewportWidth, viewportHeight)
        if (renderedWidth <= 0f || renderedHeight <= 0f || radius <= 0f) {
            return MagnifierSource(point.x, point.y, 0f)
        }
        // The window is the loupe's own radius scaled down by the magnification, expressed as a
        // fraction of the rendered image, so magnification stays constant regardless of image size.
        val halfExtentX = (radius / MAGNIFICATION) / renderedWidth
        val halfExtentY = (radius / MAGNIFICATION) / renderedHeight
        return MagnifierSource(
            centerX = point.x,
            centerY = point.y,
            halfExtent = maxOf(halfExtentX, halfExtentY)
        )
    }

    /**
     * The size the whole image is drawn at inside the loupe, given the size it is currently RENDERED
     * at behind the crop overlay ([renderedWidth] x [renderedHeight] under `ContentScale.Fit`).
     *
     * Deriving this from the rendered size is the whole point. Scaling from the SOURCE bitmap's pixel
     * dimensions instead makes the magnified drawing a fixed on-screen size, so the ratio the user
     * perceives drifts with the image's aspect and the viewport — on a portrait page in a portrait
     * viewport it lands near 0.88x, and the "magnifier" shows the page SMALLER than the page it is
     * drawn on top of. Against the rendered size the ratio is exactly [MAGNIFICATION], always.
     *
     * The rendered size already carries the image's aspect from `Fit`, so scaling both axes by the
     * same factor preserves it; no separate aspect handling is needed.
     *
     * Degenerate input fails closed at 0 x 0 rather than throwing or producing a NaN transform, so a
     * renderer can simply skip drawing.
     */
    fun magnifiedImageSize(renderedWidth: Float, renderedHeight: Float): MagnifiedImageSize {
        if (!renderedWidth.isFinite() || !renderedHeight.isFinite() ||
            renderedWidth <= 0f || renderedHeight <= 0f
        ) {
            return MagnifiedImageSize(0f, 0f)
        }
        return MagnifiedImageSize(
            width = renderedWidth * MAGNIFICATION,
            height = renderedHeight * MAGNIFICATION
        )
    }

    /**
     * Whether the loupe should be shown at all. Exactly "a handle is being dragged" — it appears on
     * touch-down and is gone on release, never lingering with stale content.
     */
    fun isVisible(state: MainScanCropState): Boolean = state.isDragging
}
