package com.dev.docscannerpdf.domain.idscan

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A rectangular crop expressed in NORMALIZED source-image coordinates: [left]/[top]/[right]/
 * [bottom] each in 0f..1f with (0,0) at the image's top-left. Crop truth is stored ONLY here —
 * never in device pixels — so it is independent of the display size, letterboxing, or rotation.
 * The pure [PassportCropReducer] enforces every invariant (in-bounds, minimum size); the screen
 * maps between this and touch pixels via [PassportCropMapping].
 */
data class PassportCropRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top

    companion object {
        /** The whole image — the initial crop and the Reset target. */
        val FULL = PassportCropRect(0f, 0f, 1f, 1f)
    }
}

/** The eight grab points of a rectangular crop: four corners and four edge midpoints. */
enum class PassportCropHandle {
    TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT,
    TOP, BOTTOM, LEFT, RIGHT
}

/**
 * Pure reducer for the rectangular passport crop. Framework-free (no Bitmap/Canvas/Compose) so
 * every clamp and minimum-size rule is JVM-testable. All inputs/outputs are normalized 0..1.
 */
object PassportCropReducer {

    /** Minimum crop width/height as a fraction of the image — stops a degenerate/empty crop. */
    const val MIN_SIZE = 0.10f

    /** Crops within this of [PassportCropRect.FULL] on every edge are treated as "no crop". */
    private const val NOOP_EPSILON = 0.001f

    fun reset(): PassportCropRect = PassportCropRect.FULL

    /**
     * Moves one handle to the normalized point ([nx], [ny]). Corners move in both axes; edge
     * handles move only their axis. The moved edge(s) are clamped to 0..1 and kept at least
     * [MIN_SIZE] from the opposite edge, so the rectangle can never invert, leave the image, or
     * collapse below the minimum.
     */
    fun moveHandle(
        rect: PassportCropRect,
        handle: PassportCropHandle,
        nx: Float,
        ny: Float
    ): PassportCropRect {
        val x = nx.coerceIn(0f, 1f)
        val y = ny.coerceIn(0f, 1f)
        var left = rect.left
        var top = rect.top
        var right = rect.right
        var bottom = rect.bottom

        val movesLeft = handle == PassportCropHandle.TOP_LEFT ||
            handle == PassportCropHandle.BOTTOM_LEFT || handle == PassportCropHandle.LEFT
        val movesRight = handle == PassportCropHandle.TOP_RIGHT ||
            handle == PassportCropHandle.BOTTOM_RIGHT || handle == PassportCropHandle.RIGHT
        val movesTop = handle == PassportCropHandle.TOP_LEFT ||
            handle == PassportCropHandle.TOP_RIGHT || handle == PassportCropHandle.TOP
        val movesBottom = handle == PassportCropHandle.BOTTOM_LEFT ||
            handle == PassportCropHandle.BOTTOM_RIGHT || handle == PassportCropHandle.BOTTOM

        if (movesLeft) left = x.coerceAtMost(right - MIN_SIZE)
        if (movesRight) right = x.coerceAtLeast(left + MIN_SIZE)
        if (movesTop) top = y.coerceAtMost(bottom - MIN_SIZE)
        if (movesBottom) bottom = y.coerceAtLeast(top + MIN_SIZE)

        return PassportCropRect(
            left = left.coerceIn(0f, 1f),
            top = top.coerceIn(0f, 1f),
            right = right.coerceIn(0f, 1f),
            bottom = bottom.coerceIn(0f, 1f)
        )
    }

    /**
     * Translates the WHOLE rectangle by ([dnx], [dny]) in normalized units, preserving its size and
     * clamping so it stays fully within the image (0..1 on both axes).
     */
    fun moveBy(rect: PassportCropRect, dnx: Float, dny: Float): PassportCropRect {
        val w = rect.width
        val h = rect.height
        val left = (rect.left + dnx).coerceIn(0f, 1f - w)
        val top = (rect.top + dny).coerceIn(0f, 1f - h)
        return PassportCropRect(left, top, left + w, top + h)
    }

    /**
     * Whether the crop is meaningfully different from the full image. Apply uses this to treat an
     * unchanged crop as a no-op instead of generating an identical file.
     */
    fun isMeaningfulCrop(rect: PassportCropRect): Boolean =
        rect.left > NOOP_EPSILON ||
            rect.top > NOOP_EPSILON ||
            rect.right < 1f - NOOP_EPSILON ||
            rect.bottom < 1f - NOOP_EPSILON
}

/**
 * Pure mapping of a normalized crop rectangle between the DISPLAYED page (what the crop editor
 * shows — the review's settled image, whose rotation is baked into its pixels) and the CANONICAL
 * base page the authoritative crop is extracted from.
 *
 * The crop editor deliberately shows the same image the review shows, so a rectangle drawn at 90°
 * or 270° is expressed in the ROTATED frame. Crop truth, however, must stay in base coordinates:
 * the preview pipeline applies crop before rotation, and [PassportCropRenderer] extracts from the
 * base JPEG. Rotation by a multiple of 90° is a pure coordinate permutation of the unit square, so
 * the conversion is exact and lossless — no resampling, no display-only transform, and the
 * rectangle's in-bounds and minimum-size invariants are preserved (width and height simply swap on
 * an odd quarter turn).
 */
object PassportCropRotationMapping {

    /**
     * Converts [rect], drawn on a page displayed at [quarterTurns] clockwise quarter turns, into
     * the equivalent rectangle in canonical base coordinates. 0 turns is the identity.
     *
     * Derivation (clockwise, normalized): a base point (x, y) appears at (1 - y, x) after one
     * quarter turn, so the inverse maps a displayed point (u, v) back to (v, 1 - u); the remaining
     * turns compose that mapping. Edges are re-ordered so left < right and top < bottom always.
     */
    fun toBaseRect(rect: PassportCropRect, quarterTurns: Int): PassportCropRect {
        return when (((quarterTurns % 4) + 4) % 4) {
            0 -> rect
            // Displayed = base rotated 90° CW: base x comes from displayed y, base y from 1 - x.
            1 -> PassportCropRect(
                left = rect.top,
                top = 1f - rect.right,
                right = rect.bottom,
                bottom = 1f - rect.left
            )
            // 180°: both axes are mirrored.
            2 -> PassportCropRect(
                left = 1f - rect.right,
                top = 1f - rect.bottom,
                right = 1f - rect.left,
                bottom = 1f - rect.top
            )
            // 270° CW (= 90° CCW): base x from 1 - displayed y, base y from displayed x.
            else -> PassportCropRect(
                left = 1f - rect.bottom,
                top = rect.left,
                right = 1f - rect.top,
                bottom = rect.right
            )
        }
    }

    /**
     * The inverse of [toBaseRect]: converts a base-coordinate [rect] into the frame of a page
     * displayed at [quarterTurns]. Exists so the round trip is directly assertable in tests (and
     * for any future caller that needs to re-project a stored crop onto a rotated preview).
     */
    fun toDisplayedRect(rect: PassportCropRect, quarterTurns: Int): PassportCropRect =
        toBaseRect(rect, -quarterTurns)
}

/** The rectangle a ContentScale.Fit image occupies inside its container, in container pixels. */
data class DisplayedImageRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/** Integer pixel crop bounds in the SOURCE bitmap (right/bottom exclusive). */
data class SourcePixelCrop(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int
)

/**
 * Pure mapping between container touch pixels, the ContentScale.Fit displayed image rectangle
 * (letterbox-aware), normalized crop coordinates, and final source-bitmap pixel bounds. Framework
 * -free so the whole coordinate pipeline is unit-testable.
 */
object PassportCropMapping {

    /**
     * The rectangle a bitmap of [srcWidth]×[srcHeight] occupies inside a [containerWidth]×
     * [containerHeight] box under ContentScale.Fit — i.e. scaled by the smaller ratio and centred,
     * with the leftover space as horizontal OR vertical letterboxing. Touches/handles must be
     * mapped against THIS rectangle, never the full container.
     */
    fun fitRect(
        containerWidth: Float,
        containerHeight: Float,
        srcWidth: Int,
        srcHeight: Int
    ): DisplayedImageRect {
        if (containerWidth <= 0f || containerHeight <= 0f || srcWidth <= 0 || srcHeight <= 0) {
            return DisplayedImageRect(0f, 0f, max(containerWidth, 0f), max(containerHeight, 0f))
        }
        val scale = min(containerWidth / srcWidth, containerHeight / srcHeight)
        val w = srcWidth * scale
        val h = srcHeight * scale
        return DisplayedImageRect(
            left = (containerWidth - w) / 2f,
            top = (containerHeight - h) / 2f,
            width = w,
            height = h
        )
    }

    /**
     * Maps a container touch point to a normalized image coordinate (0..1), clamped — a touch in
     * the letterbox maps to the nearest image edge, never outside the bitmap.
     */
    fun toNormalized(touchX: Float, touchY: Float, displayed: DisplayedImageRect): Pair<Float, Float> {
        val nx = if (displayed.width <= 0f) 0f else ((touchX - displayed.left) / displayed.width).coerceIn(0f, 1f)
        val ny = if (displayed.height <= 0f) 0f else ((touchY - displayed.top) / displayed.height).coerceIn(0f, 1f)
        return nx to ny
    }

    /** Maps a normalized image coordinate to a container pixel point (for drawing handles/frame). */
    fun toContainerX(nx: Float, displayed: DisplayedImageRect): Float = displayed.left + nx * displayed.width

    fun toContainerY(ny: Float, displayed: DisplayedImageRect): Float = displayed.top + ny * displayed.height

    /**
     * Converts a normalized crop rectangle to integer SOURCE pixel bounds, clamped to the bitmap
     * and guaranteed at least 1×1. This is the exact region the renderer copies out of the source.
     */
    fun toSourcePixels(rect: PassportCropRect, srcWidth: Int, srcHeight: Int): SourcePixelCrop {
        val left = (rect.left * srcWidth).roundToInt().coerceIn(0, max(0, srcWidth - 1))
        val top = (rect.top * srcHeight).roundToInt().coerceIn(0, max(0, srcHeight - 1))
        val right = (rect.right * srcWidth).roundToInt().coerceIn(left + 1, srcWidth)
        val bottom = (rect.bottom * srcHeight).roundToInt().coerceIn(top + 1, srcHeight)
        return SourcePixelCrop(left = left, top = top, width = right - left, height = bottom - top)
    }
}
