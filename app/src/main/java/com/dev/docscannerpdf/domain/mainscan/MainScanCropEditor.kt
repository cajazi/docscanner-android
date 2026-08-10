package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The eight grab points of the Main Scanner crop polygon: four corners and four edge midpoints,
 * matching the locked reference. Corners move one point; an edge handle moves the two corners of
 * that edge together, which is how a user straightens a whole side in one gesture.
 */
enum class MainScanCropHandle {
    TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT,
    TOP, RIGHT, BOTTOM, LEFT;

    val isCorner: Boolean
        get() = this == TOP_LEFT || this == TOP_RIGHT || this == BOTTOM_RIGHT || this == BOTTOM_LEFT
}

/** Which way [MainScanCropEditor.rotate] turns the page. */
enum class MainScanRotation { LEFT, RIGHT }

/**
 * Pure state of the crop editor. [quad] is in normalized image coordinates of the CURRENT displayed
 * orientation; [rotationQuarterTurns] records how far the page has been turned from the captured
 * original, so the eventual perspective crop can un-rotate back to the source pixels.
 *
 * [source] is retained for diagnostics and for a later slice's decision about whether a polygon is
 * trustworthy enough to skip review.
 */
data class MainScanCropState(
    val quad: PerspectiveQuad,
    val rotationQuarterTurns: Int = 0,
    val source: MainScanPolygonSource = MainScanPolygonSource.FULL_FRAME,
    val activeHandle: MainScanCropHandle? = null
) {
    /** True while the user is dragging — the magnifier is shown for exactly this condition. */
    val isDragging: Boolean get() = activeHandle != null
}

/**
 * Pure editing rules for the crop polygon.
 *
 * The hard requirement these encode is that the polygon can never become unusable through dragging.
 * A user pulling one corner past its neighbours would otherwise produce a bow-tie, which the warp
 * engine cannot interpret and which looks to the user like the app has broken. Rather than letting
 * that happen and rejecting it at Next, movement itself is constrained: a drag that would invert the
 * polygon is refused and the previous position is kept, so the shape on screen is always applicable.
 */
object MainScanCropEditor {

    /** Minimum separation between opposite edges, as a fraction of the image. */
    const val MIN_EDGE_SEPARATION = 0.05f

    /** Corners may be dragged this far outside the image, so a document bleeding off-frame is still croppable. */
    const val OUT_OF_BOUNDS_ALLOWANCE = 0.0f

    /** The full-image polygon — the All action's target and the fallback when nothing is detected. */
    fun fullFrame(): PerspectiveQuad = PerspectiveQuad.full()

    /**
     * Builds the initial state, normalising whatever the caller supplies. A seed that arrives
     * mis-ordered or slightly inverted is corrected rather than rejected, because refusing it would
     * drop the user to a full-frame crop for what is usually a recoverable ordering problem.
     */
    fun initial(
        quad: PerspectiveQuad,
        source: MainScanPolygonSource
    ): MainScanCropState {
        val normalized = PerspectiveGeometry.normalize(quad)
        val usable = if (isApplicable(normalized)) normalized else fullFrame()
        return MainScanCropState(
            quad = usable,
            source = if (usable == fullFrame() && source != MainScanPolygonSource.FULL_FRAME) {
                MainScanPolygonSource.FULL_FRAME
            } else {
                source
            }
        )
    }

    /** Begins a drag on [handle]. Recorded so the magnifier shows for exactly the drag duration. */
    fun beginDrag(state: MainScanCropState, handle: MainScanCropHandle): MainScanCropState =
        state.copy(activeHandle = handle)

    /** Ends any drag. The magnifier disappears immediately — it never lingers with stale content. */
    fun endDrag(state: MainScanCropState): MainScanCropState = state.copy(activeHandle = null)

    /**
     * Moves the active handle toward the normalized point ([x], [y]). Corners move directly, while
     * edges retain only pointer travel along their normal in the current image's pixel metric.
     *
     * The proposed polygon is accepted only when it stays applicable (convex, non-degenerate, with
     * opposite edges at least [MIN_EDGE_SEPARATION] apart). A rejected move returns the state
     * unchanged, so the handle simply stops rather than the shape folding over.
     */
    fun moveHandle(
        state: MainScanCropState,
        handle: MainScanCropHandle,
        x: Float,
        y: Float,
        imageWidth: Int,
        imageHeight: Int
    ): MainScanCropState {
        val proposed = if (handle.isCorner) {
            val point = CropPoint(
                x = x.coerceIn(-OUT_OF_BOUNDS_ALLOWANCE, 1f + OUT_OF_BOUNDS_ALLOWANCE),
                y = y.coerceIn(-OUT_OF_BOUNDS_ALLOWANCE, 1f + OUT_OF_BOUNDS_ALLOWANCE)
            )
            moveCorner(state.quad, handle, point)
        } else {
            if (!x.isFinite() || !y.isFinite() || imageWidth <= 0 || imageHeight <= 0) return state
            moveEdge(
                quad = state.quad,
                handle = handle,
                point = CropPoint(x, y),
                imageWidth = imageWidth,
                imageHeight = imageHeight
            )
        }
        if (!isApplicable(proposed)) return state
        // Convexity and area are not sufficient on their own: dragging the top edge clean past the
        // bottom one yields a perfectly convex rectangle whose winding has REVERSED. Warping that
        // produces a mirrored page — visually plausible on screen, wrong in the saved document — so
        // a move that flips the orientation is refused as well.
        if (!preservesWinding(state.quad, proposed)) return state
        return state.copy(quad = proposed, activeHandle = handle)
    }

    /** True when [proposed] keeps the same corner winding direction as [current]. */
    private fun preservesWinding(current: PerspectiveQuad, proposed: PerspectiveQuad): Boolean {
        val before = PerspectiveGeometry.signedArea(current)
        val after = PerspectiveGeometry.signedArea(proposed)
        if (before == 0f || after == 0f) return false
        return (before > 0f) == (after > 0f)
    }

    private fun moveCorner(
        quad: PerspectiveQuad,
        handle: MainScanCropHandle,
        point: CropPoint
    ): PerspectiveQuad = when (handle) {
        MainScanCropHandle.TOP_LEFT -> quad.copy(topLeft = point)
        MainScanCropHandle.TOP_RIGHT -> quad.copy(topRight = point)
        MainScanCropHandle.BOTTOM_RIGHT -> quad.copy(bottomRight = point)
        MainScanCropHandle.BOTTOM_LEFT -> quad.copy(bottomLeft = point)
        else -> quad
    }

    /**
     * Projects pointer displacement onto the edge normal in image pixels, then bounds that one
     * scalar travel value so both endpoints remain inside the image. Both corners receive the same
     * normalized translation and the opposite edge
     * is untouched — dragging the top edge down shortens the page without skewing it.
     */
    private fun moveEdge(
        quad: PerspectiveQuad,
        handle: MainScanCropHandle,
        point: CropPoint,
        imageWidth: Int,
        imageHeight: Int
    ): PerspectiveQuad {
        val (startCorner, endCorner) = edgeCorners(handle)
        val start = quad.corner(startCorner.toCropCorner())
        val end = quad.corner(endCorner.toCropCorner())
        if (!start.x.isFinite() || !start.y.isFinite() || !end.x.isFinite() || !end.y.isFinite()) {
            return quad
        }

        val width = imageWidth.toFloat()
        val height = imageHeight.toFloat()
        val edgePixelX = (end.x - start.x) * width
        val edgePixelY = (end.y - start.y) * height
        val edgeLength = hypot(edgePixelX, edgePixelY)
        if (!edgeLength.isFinite() || edgeLength <= DEGENERATE_PIXEL_EDGE_LENGTH) return quad

        val normalPixelX = -edgePixelY / edgeLength
        val normalPixelY = edgePixelX / edgeLength
        val midpoint = CropPoint((start.x + end.x) / 2f, (start.y + end.y) / 2f)
        val displacementPixelX = (point.x - midpoint.x) * width
        val displacementPixelY = (point.y - midpoint.y) * height
        val requestedScalar =
            displacementPixelX * normalPixelX + displacementPixelY * normalPixelY
        if (!requestedScalar.isFinite()) return quad

        val qx = normalPixelX / width
        val qy = normalPixelY / height
        if (!qx.isFinite() || !qy.isFinite()) return quad

        var scalarMin = Float.NEGATIVE_INFINITY
        var scalarMax = Float.POSITIVE_INFINITY

        fun intersectCoordinate(coordinate: Float, q: Float): Boolean {
            if (q == 0f) return coordinate in 0f..1f
            val first = -coordinate / q
            val second = (1f - coordinate) / q
            val lower = minOf(first, second)
            val upper = maxOf(first, second)
            if (!lower.isFinite() || !upper.isFinite()) return false
            scalarMin = maxOf(scalarMin, lower)
            scalarMax = minOf(scalarMax, upper)
            return scalarMin <= scalarMax
        }

        if (!intersectCoordinate(start.x, qx) ||
            !intersectCoordinate(start.y, qy) ||
            !intersectCoordinate(end.x, qx) ||
            !intersectCoordinate(end.y, qy)
        ) {
            return quad
        }

        val boundedScalar = requestedScalar.coerceIn(scalarMin, scalarMax)
        val boundedDx = boundedScalar * qx
        val boundedDy = boundedScalar * qy
        val movedStart = CropPoint(start.x + boundedDx, start.y + boundedDy)
        val movedEnd = CropPoint(end.x + boundedDx, end.y + boundedDy)
        return quad
            .withCorner(startCorner.toCropCorner(), movedStart)
            .withCorner(endCorner.toCropCorner(), movedEnd)
    }

    /** The two corners bounding an edge handle, in clockwise order. */
    fun edgeCorners(handle: MainScanCropHandle): Pair<MainScanCropHandle, MainScanCropHandle> =
        when (handle) {
            MainScanCropHandle.TOP -> MainScanCropHandle.TOP_LEFT to MainScanCropHandle.TOP_RIGHT
            MainScanCropHandle.RIGHT -> MainScanCropHandle.TOP_RIGHT to MainScanCropHandle.BOTTOM_RIGHT
            MainScanCropHandle.BOTTOM -> MainScanCropHandle.BOTTOM_RIGHT to MainScanCropHandle.BOTTOM_LEFT
            MainScanCropHandle.LEFT -> MainScanCropHandle.BOTTOM_LEFT to MainScanCropHandle.TOP_LEFT
            else -> handle to handle
        }

    /** The normalized position a handle currently occupies — corners directly, edges at midpoints. */
    fun handlePosition(quad: PerspectiveQuad, handle: MainScanCropHandle): CropPoint =
        if (handle.isCorner) {
            quad.corner(handle.toCropCorner())
        } else {
            val (start, end) = edgeCorners(handle)
            val a = quad.corner(start.toCropCorner())
            val b = quad.corner(end.toCropCorner())
            CropPoint((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        }

    /**
     * The handle nearest ([x], [y]) within [radius], or null. Corners win ties against edges: at a
     * short edge the two are close together, and the user reaching into a corner almost always means
     * the corner.
     */
    fun handleAt(
        quad: PerspectiveQuad,
        x: Float,
        y: Float,
        radius: Float
    ): MainScanCropHandle? {
        var best: MainScanCropHandle? = null
        var bestDistance = Float.MAX_VALUE
        for (handle in MainScanCropHandle.entries) {
            val position = handlePosition(quad, handle)
            val distance = hypot(position.x - x, position.y - y)
            if (distance > radius) continue
            val better = distance < bestDistance ||
                (abs(distance - bestDistance) < 1e-4f && handle.isCorner)
            if (better) {
                best = handle
                bestDistance = distance
            }
        }
        return best
    }

    /**
     * Rotates the page a quarter turn. The polygon rotates WITH the image — the user's crop must
     * survive a rotation, since losing it would make the rotate actions destructive. Only the All
     * action resets the polygon.
     */
    fun rotate(state: MainScanCropState, direction: MainScanRotation): MainScanCropState {
        val degrees = if (direction == MainScanRotation.RIGHT) 90 else 270
        val rotated = state.quad.corners().map {
            MainScanDetectionMapper.rotateNormalizedPoint(it, degrees)
        }
        val delta = if (direction == MainScanRotation.RIGHT) 1 else -1
        return state.copy(
            quad = PerspectiveGeometry.orderCorners(rotated),
            rotationQuarterTurns = ((state.rotationQuarterTurns + delta) % 4 + 4) % 4,
            activeHandle = null
        )
    }

    /** The All action: the polygon returns to the complete image bounds. Rotation is preserved. */
    fun resetToFullFrame(state: MainScanCropState): MainScanCropState =
        state.copy(
            quad = fullFrame(),
            source = MainScanPolygonSource.FULL_FRAME,
            activeHandle = null
        )

    /**
     * Whether the polygon may be advanced by Next: convex, non-degenerate, and with opposite edges
     * far enough apart to warp into a usable page.
     */
    fun isApplicable(quad: PerspectiveQuad): Boolean {
        if (!PerspectiveGeometry.isConvex(quad)) return false
        if (abs(PerspectiveGeometry.signedArea(quad)) < MIN_AREA) return false
        val top = midpointDistance(quad.topLeft, quad.topRight, quad.bottomLeft, quad.bottomRight)
        val side = midpointDistance(quad.topLeft, quad.bottomLeft, quad.topRight, quad.bottomRight)
        return top >= MIN_EDGE_SEPARATION && side >= MIN_EDGE_SEPARATION
    }

    fun isApplicable(state: MainScanCropState): Boolean = isApplicable(state.quad)

    /** Minimum polygon area as a fraction of the image — below this there is nothing to crop to. */
    private const val MIN_AREA = 0.02f

    /** Pixel-space edge lengths at or below this value have no stable normal. */
    private const val DEGENERATE_PIXEL_EDGE_LENGTH = 1e-6f

    private fun midpointDistance(
        a1: CropPoint,
        a2: CropPoint,
        b1: CropPoint,
        b2: CropPoint
    ): Float {
        val ax = (a1.x + a2.x) / 2f
        val ay = (a1.y + a2.y) / 2f
        val bx = (b1.x + b2.x) / 2f
        val by = (b1.y + b2.y) / 2f
        return hypot(ax - bx, ay - by)
    }
}

/** Maps the editor's corner handles onto the shared crop-geometry corner enum. */
internal fun MainScanCropHandle.toCropCorner(): com.dev.docscannerpdf.domain.crop.CropCorner =
    when (this) {
        MainScanCropHandle.TOP_LEFT -> com.dev.docscannerpdf.domain.crop.CropCorner.TOP_LEFT
        MainScanCropHandle.TOP_RIGHT -> com.dev.docscannerpdf.domain.crop.CropCorner.TOP_RIGHT
        MainScanCropHandle.BOTTOM_RIGHT -> com.dev.docscannerpdf.domain.crop.CropCorner.BOTTOM_RIGHT
        MainScanCropHandle.BOTTOM_LEFT -> com.dev.docscannerpdf.domain.crop.CropCorner.BOTTOM_LEFT
        else -> com.dev.docscannerpdf.domain.crop.CropCorner.TOP_LEFT
    }
