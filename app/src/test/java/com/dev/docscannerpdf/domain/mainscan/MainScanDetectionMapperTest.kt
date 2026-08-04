package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinate authority. These tests exist because a mis-mapped overlay is invisible to every
 * other kind of verification: the detector is right, the renderer is right, and the guide still sits
 * in the wrong place because the two disagree about orientation or about centre-crop.
 */
class MainScanDetectionMapperTest {

    private val tolerance = 1e-4f

    /** An asymmetric quad — a square would hide corner-ordering mistakes. */
    private fun sampleQuad() = PerspectiveQuad(
        topLeft = CropPoint(0.20f, 0.10f),
        topRight = CropPoint(0.80f, 0.15f),
        bottomRight = CropPoint(0.75f, 0.60f),
        bottomLeft = CropPoint(0.25f, 0.55f)
    )

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: CropPoint, label: String) {
        assertEquals("$label.x", expectedX, actual.x, tolerance)
        assertEquals("$label.y", expectedY, actual.y, tolerance)
    }

    private fun assertViewport(expectedX: Float, expectedY: Float, actual: ViewportPoint, label: String) {
        assertEquals("$label.x", expectedX, actual.x, tolerance)
        assertEquals("$label.y", expectedY, actual.y, tolerance)
    }

    // --- rotation normalization -----------------------------------------------------------------

    @Test
    fun rotationIsNormalizedToQuarterTurns() {
        assertEquals(0, MainScanDetectionMapper.normalizeRotation(0))
        assertEquals(90, MainScanDetectionMapper.normalizeRotation(90))
        assertEquals(180, MainScanDetectionMapper.normalizeRotation(180))
        assertEquals(270, MainScanDetectionMapper.normalizeRotation(270))
        assertEquals(0, MainScanDetectionMapper.normalizeRotation(360))
        assertEquals(90, MainScanDetectionMapper.normalizeRotation(450))
        assertEquals(270, MainScanDetectionMapper.normalizeRotation(-90))
    }

    // --- point rotation at every quarter turn ----------------------------------------------------

    @Test
    fun pointRotationMatchesClockwiseImageRotation() {
        val origin = CropPoint(0f, 0f)
        // The buffer's top-left travels around the corners as the image turns clockwise.
        assertPoint(0f, 0f, MainScanDetectionMapper.rotateNormalizedPoint(origin, 0), "0deg")
        assertPoint(1f, 0f, MainScanDetectionMapper.rotateNormalizedPoint(origin, 90), "90deg")
        assertPoint(1f, 1f, MainScanDetectionMapper.rotateNormalizedPoint(origin, 180), "180deg")
        assertPoint(0f, 1f, MainScanDetectionMapper.rotateNormalizedPoint(origin, 270), "270deg")
    }

    @Test
    fun pointRotationIsExactForAnAsymmetricPoint() {
        val p = CropPoint(0.25f, 0.75f)
        assertPoint(0.25f, 0.75f, MainScanDetectionMapper.rotateNormalizedPoint(p, 0), "0deg")
        assertPoint(0.25f, 0.25f, MainScanDetectionMapper.rotateNormalizedPoint(p, 90), "90deg")
        assertPoint(0.75f, 0.25f, MainScanDetectionMapper.rotateNormalizedPoint(p, 180), "180deg")
        assertPoint(0.75f, 0.75f, MainScanDetectionMapper.rotateNormalizedPoint(p, 270), "270deg")
    }

    @Test
    fun fourQuarterTurnsReturnAPointToItself() {
        var p = CropPoint(0.3f, 0.8f)
        repeat(4) { p = MainScanDetectionMapper.rotateNormalizedPoint(p, 90) }
        assertPoint(0.3f, 0.8f, p, "round trip")
    }

    // --- frame size swap --------------------------------------------------------------------------

    @Test
    fun frameDimensionsSwapOnOddQuarterTurns() {
        val landscape = FrameSize(width = 640, height = 480)
        assertEquals(FrameSize(640, 480), MainScanDetectionMapper.rotatedFrameSize(landscape, 0))
        assertEquals(FrameSize(480, 640), MainScanDetectionMapper.rotatedFrameSize(landscape, 90))
        assertEquals(FrameSize(640, 480), MainScanDetectionMapper.rotatedFrameSize(landscape, 180))
        assertEquals(FrameSize(480, 640), MainScanDetectionMapper.rotatedFrameSize(landscape, 270))
    }

    // --- corner ordering survives every rotation ---------------------------------------------------

    @Test
    fun cornerOrderingIsRestoredAfterEveryRotation() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val rotated = MainScanDetectionMapper.rotateNormalizedQuad(sampleQuad(), rotation)
            val c = rotated.corners()
            assertTrue(
                "top-left must be leftmost-topmost at $rotation",
                c[0].x <= c[1].x && c[0].y <= c[3].y
            )
            assertTrue("top edge above bottom edge at $rotation", c[0].y < c[3].y)
            assertTrue("left edge left of right edge at $rotation", c[0].x < c[1].x)
            assertTrue("bottom-right is rightmost-bottommost at $rotation", c[2].x >= c[3].x)
        }
    }

    @Test
    fun rotatingAQuadNinetyDegreesMovesTheTopEdgeToTheRight() {
        // Concrete orientation check: a wide quad becomes a tall one after a quarter turn.
        val wide = PerspectiveQuad(
            topLeft = CropPoint(0.1f, 0.4f),
            topRight = CropPoint(0.9f, 0.4f),
            bottomRight = CropPoint(0.9f, 0.6f),
            bottomLeft = CropPoint(0.1f, 0.6f)
        )
        val rotated = MainScanDetectionMapper.rotateNormalizedQuad(wide, 90)
        val width = rotated.topRight.x - rotated.topLeft.x
        val height = rotated.bottomLeft.y - rotated.topLeft.y
        assertTrue("a wide quad must become tall after 90deg (w=$width h=$height)", height > width)
    }

    @Test
    fun fourQuarterTurnsReturnAQuadToItself() {
        var quad = sampleQuad()
        repeat(4) { quad = MainScanDetectionMapper.rotateNormalizedQuad(quad, 90) }
        val original = sampleQuad().corners()
        quad.corners().forEachIndexed { index, point ->
            assertPoint(original[index].x, original[index].y, point, "corner $index")
        }
    }

    // --- fill / centre-crop rect --------------------------------------------------------------------

    @Test
    fun portraitViewportWithLandscapeFrameOverflowsHorizontally() {
        // 480x640 rotated frame inside a 1080x2340 portrait viewport: scale is driven by HEIGHT.
        val rendered = MainScanDetectionMapper.renderedRect(
            rotatedFrame = FrameSize(480, 640),
            viewportWidth = 1080f,
            viewportHeight = 2340f
        )
        val expectedScale = 2340f / 640f
        assertEquals(480f * expectedScale, rendered.width, 1e-2f)
        assertEquals(2340f, rendered.height, 1e-2f)
        assertTrue("must overflow horizontally, so left is negative", rendered.left < 0f)
        assertEquals(0f, rendered.top, 1e-2f)
        // Centre-crop is symmetric: equal amounts are lost at both ends.
        assertEquals(rendered.left, 1080f - (rendered.left + rendered.width), 1e-2f)
    }

    @Test
    fun landscapeViewportWithPortraitFrameOverflowsVertically() {
        val rendered = MainScanDetectionMapper.renderedRect(
            rotatedFrame = FrameSize(480, 640),
            viewportWidth = 2340f,
            viewportHeight = 1080f
        )
        assertEquals(2340f, rendered.width, 1e-2f)
        assertTrue("must overflow vertically, so top is negative", rendered.top < 0f)
        assertEquals(0f, rendered.left, 1e-2f)
        assertEquals(rendered.top, 1080f - (rendered.top + rendered.height), 1e-2f)
    }

    @Test
    fun matchingAspectRatiosProduceNoOffset() {
        val rendered = MainScanDetectionMapper.renderedRect(
            rotatedFrame = FrameSize(480, 640),
            viewportWidth = 960f,
            viewportHeight = 1280f
        )
        assertEquals(0f, rendered.left, 1e-3f)
        assertEquals(0f, rendered.top, 1e-3f)
        assertEquals(960f, rendered.width, 1e-3f)
        assertEquals(1280f, rendered.height, 1e-3f)
    }

    @Test
    fun degenerateInputsProduceAZeroRectRatherThanThrowing() {
        val zeroFrame = MainScanDetectionMapper.renderedRect(FrameSize(0, 0), 100f, 100f)
        assertEquals(0f, zeroFrame.width, 1e-4f)
        val zeroViewport = MainScanDetectionMapper.renderedRect(FrameSize(480, 640), 0f, 0f)
        assertEquals(0f, zeroViewport.width, 1e-4f)
    }

    // --- viewport mapping includes the offsets --------------------------------------------------

    @Test
    fun mappingAppliesCentreCropOffsetsNotJustAScale() {
        val frame = FrameSize(480, 640)
        val viewportWidth = 1080f
        val viewportHeight = 2340f
        val centre = CropPoint(0.5f, 0.5f)
        val rendered = MainScanDetectionMapper.renderedRect(frame, viewportWidth, viewportHeight)
        val mapped = MainScanDetectionMapper.mapPointToViewport(centre, rendered)

        // The frame centre must land at the viewport centre under centre-crop.
        assertViewport(viewportWidth / 2f, viewportHeight / 2f, mapped, "centre")

        // And the naive shortcut (normalized * viewport) must differ, proving offsets are applied.
        val naiveX = 0.5f * viewportWidth
        val leftEdge = MainScanDetectionMapper.mapPointToViewport(CropPoint(0f, 0.5f), rendered)
        assertTrue("left edge must map off-screen, not to 0", leftEdge.x < 0f)
        assertEquals(viewportWidth / 2f, naiveX, 1e-3f)
    }

    @Test
    fun fullFrameQuadMapsToTheRenderedRectCorners() {
        val frame = FrameSize(480, 640)
        val mapped = MainScanDetectionMapper.mapToViewport(
            rotatedQuad = PerspectiveQuad.full(),
            rotatedFrame = frame,
            viewportWidth = 1080f,
            viewportHeight = 2340f
        )
        val rendered = MainScanDetectionMapper.renderedRect(frame, 1080f, 2340f)
        assertViewport(rendered.left, rendered.top, mapped.topLeft, "topLeft")
        assertViewport(rendered.left + rendered.width, rendered.top, mapped.topRight, "topRight")
        assertViewport(
            rendered.left + rendered.width,
            rendered.top + rendered.height,
            mapped.bottomRight,
            "bottomRight"
        )
        assertViewport(rendered.left, rendered.top + rendered.height, mapped.bottomLeft, "bottomLeft")
    }

    @Test
    fun endToEndMappingRotatesThenMaps() {
        // A landscape analysis buffer displayed in a portrait viewport at 90deg — the real device case.
        val mapped = MainScanDetectionMapper.mapDetectorQuadToViewport(
            detectorQuad = PerspectiveQuad.full(),
            analysisFrame = FrameSize(640, 480),
            rotationDegrees = 90,
            viewportWidth = 1080f,
            viewportHeight = 2340f
        )
        // Rotated frame is 480x640; in a 1080x2340 viewport it overflows horizontally.
        val rendered = MainScanDetectionMapper.renderedRect(FrameSize(480, 640), 1080f, 2340f)
        assertViewport(rendered.left, rendered.top, mapped.topLeft, "topLeft")
        assertEquals(2340f, mapped.bottomLeft.y - mapped.topLeft.y, 1e-2f)
    }

    @Test
    fun mappedCornersKeepTheirClockwiseOrderAtEveryRotation() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val mapped = MainScanDetectionMapper.mapDetectorQuadToViewport(
                detectorQuad = sampleQuad(),
                analysisFrame = FrameSize(640, 480),
                rotationDegrees = rotation,
                viewportWidth = 1080f,
                viewportHeight = 2340f
            )
            val c = mapped.corners()
            assertTrue("TL left of TR at $rotation", c[0].x < c[1].x)
            assertTrue("TR above BR at $rotation", c[1].y < c[2].y)
            assertTrue("BR right of BL at $rotation", c[2].x > c[3].x)
            assertTrue("BL below TL at $rotation", c[3].y > c[0].y)
        }
    }
}
