package com.dev.docscannerpdf.domain.idscan

import com.dev.docscannerpdf.domain.pdf.IdCardLayoutPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdCardFrameCropperTest {

    @Test
    fun cropRectMatchesFrameExactlyWhenImageAspectMatchesContainer() {
        val rect = IdCardFrameCropper.computeCropRect(
            containerWidth = 1000f,
            containerHeight = 2000f,
            frameLeft = 40f,
            frameTop = 500f,
            frameWidth = 900f,
            frameHeight = 560f,
            imageWidth = 1000,
            imageHeight = 2000
        )

        assertEquals(40, rect.left)
        assertEquals(500, rect.top)
        assertEquals(900, rect.width)
        assertEquals(560, rect.height)
    }

    @Test
    fun cropRectScalesUniformlyWithHigherResolutionSameAspectImage() {
        val rect = IdCardFrameCropper.computeCropRect(
            containerWidth = 1000f,
            containerHeight = 2000f,
            frameLeft = 40f,
            frameTop = 500f,
            frameWidth = 900f,
            frameHeight = 560f,
            imageWidth = 2000,
            imageHeight = 4000
        )

        // Same aspect image at 2x resolution -> scale is 2x, so every frame coordinate doubles.
        assertEquals(80, rect.left)
        assertEquals(1000, rect.top)
        assertEquals(1800, rect.width)
        assertEquals(1120, rect.height)
    }

    @Test
    fun cropRectStaysLandscapeAndCardShapedEvenWhenImageAspectDiffersFromContainer() {
        // A wider-sensor image than the portrait preview container it was shown center-cropped in
        // (e.g. a 4:3 sensor photo behind a narrower on-screen preview).
        val containerWidth = 1080f
        val containerHeight = 2280f
        val frameWidth = containerWidth - 168f
        val frameHeight = frameWidth / IdCardLayoutPlanner.CARD_ASPECT_RATIO
        val frameLeft = (containerWidth - frameWidth) / 2f
        val frameTop = (containerHeight - frameHeight) / 2f

        val rect = IdCardFrameCropper.computeCropRect(
            containerWidth = containerWidth,
            containerHeight = containerHeight,
            frameLeft = frameLeft,
            frameTop = frameTop,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            imageWidth = 3000,
            imageHeight = 4000
        )

        assertTrue("baked crop must be landscape, was ${rect.width}x${rect.height}", rect.width > rect.height)
        assertEquals(IdCardLayoutPlanner.CARD_ASPECT_RATIO, rect.aspectRatio, 0.02f)
    }

    @Test
    fun cropRectClampsToImageBoundsWhenFrameExtendsOutside() {
        val rect = IdCardFrameCropper.computeCropRect(
            containerWidth = 1000f,
            containerHeight = 1000f,
            frameLeft = -200f,
            frameTop = -200f,
            frameWidth = 1400f,
            frameHeight = 1400f,
            imageWidth = 800,
            imageHeight = 800
        )

        assertEquals(0, rect.left)
        assertEquals(0, rect.top)
        assertTrue(rect.width <= 800)
        assertTrue(rect.height <= 800)
        assertTrue(rect.width >= 1)
        assertTrue(rect.height >= 1)
    }
}
