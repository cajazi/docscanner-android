package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.CropPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The precision loupe. Its whole purpose is to show the pixels the fingertip is covering, so the two
 * properties that actually matter are that it never sits under the finger and never leaves the
 * viewport — both of which are easy to get subtly wrong at the corners, which is exactly where the
 * user needs it most.
 */
class MainScanMagnifierTest {

    private val width = 1080f
    private val height = 2340f

    private fun radius() = MainScanMagnifier.radiusFor(width, height)

    private fun distance(placement: MagnifierPlacement, x: Float, y: Float): Float =
        kotlin.math.hypot(placement.centerX - x, placement.centerY - y)

    // --- visibility ------------------------------------------------------------------------------

    @Test
    fun theLoupeIsVisibleForExactlyTheDragDuration() {
        val idle = MainScanCropEditor.initial(
            MainScanCropEditor.fullFrame(),
            MainScanPolygonSource.FULL_FRAME
        )
        assertFalse("hidden before any drag", MainScanMagnifier.isVisible(idle))

        val dragging = MainScanCropEditor.beginDrag(idle, MainScanCropHandle.TOP_LEFT)
        assertTrue("shown while dragging", MainScanMagnifier.isVisible(dragging))

        val released = MainScanCropEditor.endDrag(dragging)
        assertFalse("gone the instant the finger lifts", MainScanMagnifier.isVisible(released))
    }

    // --- placement -------------------------------------------------------------------------------

    @Test
    fun theLoupeNeverSitsUnderTheFinger() {
        // Sampled across the whole surface, including all four corners and the edges.
        for (fx in listOf(0.0f, 0.1f, 0.5f, 0.9f, 1.0f)) {
            for (fy in listOf(0.0f, 0.1f, 0.5f, 0.9f, 1.0f)) {
                val touchX = width * fx
                val touchY = height * fy
                val placement = MainScanMagnifier.place(touchX, touchY, width, height)
                assertTrue(
                    "loupe overlaps the touch at ($fx, $fy)",
                    distance(placement, touchX, touchY) >= placement.radius * 0.9f
                )
            }
        }
    }

    @Test
    fun theLoupeStaysFullyInsideTheViewport() {
        for (fx in listOf(0.0f, 0.02f, 0.5f, 0.98f, 1.0f)) {
            for (fy in listOf(0.0f, 0.02f, 0.5f, 0.98f, 1.0f)) {
                val placement = MainScanMagnifier.place(width * fx, height * fy, width, height)
                assertTrue("left edge at ($fx,$fy)", placement.centerX - placement.radius >= -0.5f)
                assertTrue("top edge at ($fx,$fy)", placement.centerY - placement.radius >= -0.5f)
                assertTrue(
                    "right edge at ($fx,$fy)",
                    placement.centerX + placement.radius <= width + 0.5f
                )
                assertTrue(
                    "bottom edge at ($fx,$fy)",
                    placement.centerY + placement.radius <= height + 0.5f
                )
            }
        }
    }

    @Test
    fun theLoupeFlipsRatherThanClampingNearTheTopLeftCorner() {
        // Clamping alone would slide it back under the hand exactly here.
        val placement = MainScanMagnifier.place(10f, 10f, width, height)
        assertTrue("must flip below the touch", placement.centerY > 10f)
        assertTrue("must flip right of the touch", placement.centerX > 10f)
        assertTrue(distance(placement, 10f, 10f) >= placement.radius * 0.9f)
    }

    @Test
    fun theDefaultPositionIsUpAndLeftOfTheTouch() {
        val placement = MainScanMagnifier.place(width * 0.7f, height * 0.7f, width, height)
        assertTrue(placement.centerX < width * 0.7f)
        assertTrue(placement.centerY < height * 0.7f)
    }

    @Test
    fun radiusScalesWithTheShorterViewportEdge() {
        // Portrait: 1080 x 2340, so the shorter edge is 1080.
        assertEquals(width * MainScanMagnifier.RADIUS_FRACTION, radius(), 1e-3f)
        // Landscape: 2340 x 1080 — the shorter edge is 1080 again, so the loupe keeps its size
        // rather than growing with the now-longer horizontal axis.
        assertEquals(
            1080f * MainScanMagnifier.RADIUS_FRACTION,
            MainScanMagnifier.radiusFor(height, width),
            1e-3f
        )
    }

    @Test
    fun degenerateViewportsProduceAZeroRadiusRatherThanThrowing() {
        val placement = MainScanMagnifier.place(0f, 0f, 0f, 0f)
        assertEquals(0f, placement.radius, 1e-4f)
    }

    // --- source sampling ---------------------------------------------------------------------------

    @Test
    fun theSourceWindowIsCentredOnTheDraggedPoint() {
        val point = CropPoint(0.42f, 0.63f)
        val window = MainScanMagnifier.sourceWindow(
            point = point,
            viewportWidth = width,
            viewportHeight = height,
            renderedWidth = 1080f,
            renderedHeight = 1440f
        )
        assertEquals(
            "the crosshair must mark the point actually being placed",
            0.42f,
            window.centerX,
            1e-5f
        )
        assertEquals(0.63f, window.centerY, 1e-5f)
        assertTrue(window.halfExtent > 0f)
    }

    @Test
    fun theSourceWindowIsNotClampedAtTheImageEdge() {
        // Clamping would slide the window sideways and the crosshair would stop marking the corner
        // being placed — the loupe would quietly start lying.
        val window = MainScanMagnifier.sourceWindow(
            point = CropPoint(0.0f, 0.0f),
            viewportWidth = width,
            viewportHeight = height,
            renderedWidth = 1080f,
            renderedHeight = 1440f
        )
        assertEquals(0f, window.centerX, 1e-5f)
        assertEquals(0f, window.centerY, 1e-5f)
        assertTrue("the window legitimately extends past the edge", window.left < 0f)
        assertTrue(window.top < 0f)
    }

    @Test
    fun aLargerRenderedImageYieldsATighterNormalizedWindow() {
        // Magnification is constant in SCREEN terms, so the normalized window shrinks as the
        // rendered image grows.
        val small = MainScanMagnifier.sourceWindow(
            CropPoint(0.5f, 0.5f), width, height, renderedWidth = 540f, renderedHeight = 720f
        )
        val large = MainScanMagnifier.sourceWindow(
            CropPoint(0.5f, 0.5f), width, height, renderedWidth = 2160f, renderedHeight = 2880f
        )
        assertTrue(large.halfExtent < small.halfExtent)
    }

    @Test
    fun degenerateRenderedSizeProducesAZeroWindowRatherThanThrowing() {
        val window = MainScanMagnifier.sourceWindow(
            CropPoint(0.5f, 0.5f), width, height, renderedWidth = 0f, renderedHeight = 0f
        )
        assertEquals(0f, window.halfExtent, 1e-5f)
    }
}
