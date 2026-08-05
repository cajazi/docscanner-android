package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import kotlin.math.max

/**
 * A point in PREVIEW VIEWPORT pixels — the Compose overlay's own coordinate space. Values may fall
 * outside the viewport: under fill/centre-crop the rendered frame overflows the viewport on one
 * axis, so a document edge lying in the cropped-away region legitimately maps off-screen. Callers
 * clip when drawing; the mapper never silently clamps, because clamping would drag a corner onto
 * the viewport edge and draw a guide line that does not follow the real document.
 */
data class ViewportPoint(val x: Float, val y: Float)

/** The four mapped corners, in the same clockwise TL/TR/BR/BL order as their source quad. */
data class ViewportQuad(
    val topLeft: ViewportPoint,
    val topRight: ViewportPoint,
    val bottomRight: ViewportPoint,
    val bottomLeft: ViewportPoint
) {
    fun corners(): List<ViewportPoint> = listOf(topLeft, topRight, bottomRight, bottomLeft)
}

/** Size of a frame in pixels. Used for both the analysis buffer and its rotated presentation. */
data class FrameSize(val width: Int, val height: Int)

/**
 * The rectangle the rotated analysis frame occupies inside the preview viewport under fill /
 * centre-crop, in viewport pixels. [left]/[top] are negative on the overflowing axis — that is the
 * centre-crop offset, and it is exactly what makes normalized coordinates land on the right pixels.
 */
data class RenderedFrameRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float
)

/**
 * THE coordinate authority for Main Scanner live detection. Pure and framework-free, so every
 * rotation and every centre-crop offset is provable on the JVM rather than by squinting at a device.
 *
 * ## The coordinate spaces, in order
 *
 * 1. **Analysis buffer** — `ImageProxy.width` x `height`, in SENSOR orientation. On this device
 *    family that is landscape (e.g. 640x480) even when the UI is portrait.
 * 2. **Downscaled luma frame** — [com.dev.docscannerpdf.domain.detection.YuvLumaConverter] caps the
 *    longest edge (240px) while scaling both axes by the same factor, so the aspect ratio is
 *    preserved and normalized coordinates in this space equal normalized coordinates in space 1.
 * 3. **Normalized detector coordinates** — 0..1 within space 2, origin top-left, UNROTATED.
 * 4. **Rotated normalized coordinates** — space 3 with `imageInfo.rotationDegrees` applied, so the
 *    frame is upright as the user sees it. At 90 deg and 270 deg the frame's width and height swap
 *    ([rotatedFrameSize]); corner ROLES change, so the quad is re-ordered ([rotateNormalizedQuad]).
 * 5. **Rendered frame rect** — space 4 scaled to cover the viewport and centred ([renderedRect]).
 * 6. **Preview viewport pixels** — the Compose overlay's space ([mapToViewport]).
 *
 * ## What this class deliberately does NOT do
 *
 * It never maps onto the captured JPEG. The still is 4:3 at 4080x3060 while the analysis buffer is a
 * different resolution and potentially a different sensor crop region; proving that relationship
 * needs the capture-side metadata Slice 3 introduces. Slice 2 therefore keeps the crop seed in
 * space 4 (rotated normalized), which is the last space provably shared by what the user SAW and
 * what the capture contains, and records the frame size and rotation alongside it so Slice 3 can
 * finish the mapping. Where the relationship cannot be proven, the crop falls back to full frame
 * rather than guessing — see [MainScanCropSeed].
 *
 * Mirroring: the rear camera is NOT mirrored, and nothing here applies a horizontal flip. If a front
 * camera is ever added, a flip must be introduced HERE as an explicit parameter — not assumed away.
 */
object MainScanDetectionMapper {

    /** Normalizes any rotation to one of 0/90/180/270. Negative and >360 inputs are accepted. */
    fun normalizeRotation(rotationDegrees: Int): Int {
        val quarter = ((rotationDegrees / 90) % 4 + 4) % 4
        return quarter * 90
    }

    /**
     * The frame's size as PRESENTED after [rotationDegrees] clockwise. Width and height swap on an
     * odd quarter turn — the single most common source of mis-mapped overlays.
     */
    fun rotatedFrameSize(size: FrameSize, rotationDegrees: Int): FrameSize =
        when (normalizeRotation(rotationDegrees)) {
            90, 270 -> FrameSize(width = size.height, height = size.width)
            else -> size
        }

    /**
     * Rotates a single normalized point by [rotationDegrees] clockwise within the unit square.
     *
     * Derivation (clockwise): rotating the IMAGE 90 deg CW sends the buffer's top-left to the
     * displayed top-right, so (x, y) -> (1 - y, x). The remaining turns compose that mapping.
     */
    fun rotateNormalizedPoint(point: CropPoint, rotationDegrees: Int): CropPoint =
        when (normalizeRotation(rotationDegrees)) {
            90 -> CropPoint(x = 1f - point.y, y = point.x)
            180 -> CropPoint(x = 1f - point.x, y = 1f - point.y)
            270 -> CropPoint(x = point.y, y = 1f - point.x)
            else -> point
        }

    /**
     * Rotates a normalized quad into displayed orientation AND restores the TL/TR/BR/BL contract.
     *
     * Rotating the four points is not enough: after a quarter turn the point that was the top-left
     * is no longer in the top-left position, so a renderer walking the corners in order would draw a
     * bow-tie. Re-ordering through [PerspectiveGeometry.orderCorners] (the same sum/difference
     * heuristic the crop engine uses) re-assigns the roles from the rotated geometry itself, so the
     * order is correct by construction at every rotation rather than by luck at some of them.
     */
    fun rotateNormalizedQuad(quad: PerspectiveQuad, rotationDegrees: Int): PerspectiveQuad {
        val rotated = quad.corners().map { rotateNormalizedPoint(it, rotationDegrees) }
        return PerspectiveGeometry.orderCorners(rotated)
    }

    /**
     * The rectangle a [rotatedFrame] occupies inside a [viewportWidth] x [viewportHeight] preview
     * under fill / centre-crop — the presentation `PreviewView` uses by default.
     *
     * The frame is scaled by the LARGER ratio so it covers the viewport on both axes, then centred;
     * whichever axis overflows is cropped equally at both ends, which is why the corresponding
     * offset is negative. Returning a zero-size rect for degenerate inputs keeps callers total.
     */
    fun renderedRect(
        rotatedFrame: FrameSize,
        viewportWidth: Float,
        viewportHeight: Float
    ): RenderedFrameRect {
        if (rotatedFrame.width <= 0 || rotatedFrame.height <= 0 ||
            viewportWidth <= 0f || viewportHeight <= 0f
        ) {
            return RenderedFrameRect(left = 0f, top = 0f, width = 0f, height = 0f)
        }
        val scale = max(
            viewportWidth / rotatedFrame.width.toFloat(),
            viewportHeight / rotatedFrame.height.toFloat()
        )
        val renderedWidth = rotatedFrame.width * scale
        val renderedHeight = rotatedFrame.height * scale
        return RenderedFrameRect(
            left = (viewportWidth - renderedWidth) / 2f,
            top = (viewportHeight - renderedHeight) / 2f,
            width = renderedWidth,
            height = renderedHeight
        )
    }

    /**
     * Maps a point that is already in ROTATED normalized space (space 4) onto viewport pixels,
     * including the centre-crop offsets. Multiplying normalized coordinates by the viewport size —
     * the obvious shortcut — is wrong whenever the frame and viewport aspect ratios differ, which is
     * essentially always on a portrait phone.
     */
    fun mapPointToViewport(point: CropPoint, rendered: RenderedFrameRect): ViewportPoint =
        ViewportPoint(
            x = rendered.left + point.x * rendered.width,
            y = rendered.top + point.y * rendered.height
        )

    /**
     * Full pipeline for one already-rotated normalized quad: compute the rendered rect for the
     * rotated frame, then map all four corners, preserving order.
     */
    fun mapToViewport(
        rotatedQuad: PerspectiveQuad,
        rotatedFrame: FrameSize,
        viewportWidth: Float,
        viewportHeight: Float
    ): ViewportQuad {
        val rendered = renderedRect(rotatedFrame, viewportWidth, viewportHeight)
        return ViewportQuad(
            topLeft = mapPointToViewport(rotatedQuad.topLeft, rendered),
            topRight = mapPointToViewport(rotatedQuad.topRight, rendered),
            bottomRight = mapPointToViewport(rotatedQuad.bottomRight, rendered),
            bottomLeft = mapPointToViewport(rotatedQuad.bottomLeft, rendered)
        )
    }

    /**
     * Convenience for the whole chain from a raw detector quad (space 3) to viewport pixels: rotate
     * and re-order, then map. Kept separate from the steps above so tests can pin each stage.
     */
    fun mapDetectorQuadToViewport(
        detectorQuad: PerspectiveQuad,
        analysisFrame: FrameSize,
        rotationDegrees: Int,
        viewportWidth: Float,
        viewportHeight: Float
    ): ViewportQuad = mapToViewport(
        rotatedQuad = rotateNormalizedQuad(detectorQuad, rotationDegrees),
        rotatedFrame = rotatedFrameSize(analysisFrame, rotationDegrees),
        viewportWidth = viewportWidth,
        viewportHeight = viewportHeight
    )
}
