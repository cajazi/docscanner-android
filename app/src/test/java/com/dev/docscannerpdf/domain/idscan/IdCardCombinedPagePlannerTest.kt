package com.dev.docscannerpdf.domain.idscan

import com.dev.docscannerpdf.domain.pdf.CardRect
import com.dev.docscannerpdf.domain.pdf.IdCardLayoutPlanner
import com.dev.docscannerpdf.util.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdCardCombinedPagePlannerTest {

    private val pageWidth = 1240

    @Test
    fun pageKeepsA4Proportions() {
        val plan = IdCardCombinedPagePlanner.plan(
            pageWidth = pageWidth,
            frontImageWidth = 1600,
            frontImageHeight = 1000
        )

        assertEquals(pageWidth, plan.pageWidth)
        val expectedHeight = pageWidth.toFloat() *
            AppConstants.A4_HEIGHT_POINTS / AppConstants.A4_WIDTH_POINTS
        assertEquals(expectedHeight, plan.pageHeight.toFloat(), 1f)
    }

    @Test
    fun cardSlotsMatchTheSharedIdCardLayoutPlanner() {
        val plan = IdCardCombinedPagePlanner.plan(
            pageWidth = pageWidth,
            frontImageWidth = 1600,
            frontImageHeight = 1000,
            backImageWidth = 1600,
            backImageHeight = 1000
        )

        val expected = IdCardLayoutPlanner.plan(
            sideCount = 2,
            pageWidth = plan.pageWidth.toFloat(),
            pageHeight = plan.pageHeight.toFloat()
        )
        assertEquals(expected[0], plan.front.cardRect)
        assertNotNull(plan.back)
        assertEquals(expected[1], plan.back?.cardRect)
    }

    @Test
    fun frontAndBackDestinationRectsAreExactlyEqualInSize() {
        // Deliberately different source ratios: the DESTINATION must still be the two equal
        // card slots — never a per-side shrunken fit rect.
        val plan = IdCardCombinedPagePlanner.plan(
            pageWidth = pageWidth,
            frontImageWidth = 1600,
            frontImageHeight = 1000,
            backImageWidth = 1400,
            backImageHeight = 1000
        )

        val back = plan.back
        assertNotNull(back)
        requireNotNull(back)
        assertEquals(plan.front.cardRect.width, back.cardRect.width, 0f)
        assertEquals(plan.front.cardRect.height, back.cardRect.height, 0f)
    }

    @Test
    fun landscapeImagesWithSlightlyDifferentRatiosFillEqualSlots() {
        // 1.6 vs 1.55 — the exact device-QA failure mode. Each crop must match its slot's
        // ratio so the drawn content fills the complete slot with no letterbox.
        val plan = IdCardCombinedPagePlanner.plan(
            pageWidth = pageWidth,
            frontImageWidth = 1600,
            frontImageHeight = 1000,
            backImageWidth = 1550,
            backImageHeight = 1000
        )

        val back = plan.back
        requireNotNull(back)
        assertCropMatchesSlotRatio(plan.front)
        assertCropMatchesSlotRatio(back)
        assertCropInsideSource(plan.front.sourceCropRect, 1600, 1000)
        assertCropInsideSource(back.sourceCropRect, 1550, 1000)
    }

    @Test
    fun portraitSourceIsCenterCroppedWithoutStretching() {
        val imageWidth = 1000
        val imageHeight = 1600
        val plan = IdCardCombinedPagePlanner.plan(
            pageWidth = pageWidth,
            frontImageWidth = imageWidth,
            frontImageHeight = imageHeight
        )

        val crop = plan.front.sourceCropRect
        // A portrait source keeps its full width; only height is trimmed.
        assertEquals(imageWidth.toFloat(), crop.width, 0.001f)
        assertTrue(crop.height < imageHeight)
        // Trimmed equally from top and bottom (centered).
        assertEquals(crop.top, imageHeight - crop.bottom, 0.01f)
        assertCropMatchesSlotRatio(plan.front)
        assertCropInsideSource(crop, imageWidth, imageHeight)
    }

    @Test
    fun widerThanCardSourceIsCenterCroppedHorizontally() {
        val imageWidth = 3200
        val imageHeight = 1000
        val plan = IdCardCombinedPagePlanner.plan(
            pageWidth = pageWidth,
            frontImageWidth = imageWidth,
            frontImageHeight = imageHeight
        )

        val crop = plan.front.sourceCropRect
        // A too-wide source keeps its full height; only width is trimmed.
        assertEquals(imageHeight.toFloat(), crop.height, 0.001f)
        assertTrue(crop.width < imageWidth)
        // Trimmed equally from left and right (centered).
        assertEquals(crop.left, imageWidth - crop.right, 0.01f)
        assertCropMatchesSlotRatio(plan.front)
        assertCropInsideSource(crop, imageWidth, imageHeight)
    }

    @Test
    fun cropRectsStayInsideSourceBoundsAcrossShapes() {
        val shapes = listOf(
            1600 to 1000,
            1000 to 1600,
            1586 to 1000,
            50 to 4000,
            4000 to 50,
            1 to 1
        )
        shapes.forEach { (width, height) ->
            val plan = IdCardCombinedPagePlanner.plan(
                pageWidth = pageWidth,
                frontImageWidth = width,
                frontImageHeight = height
            )
            assertCropInsideSource(plan.front.sourceCropRect, width, height)
            assertTrue(plan.front.sourceCropRect.width >= 1f)
            assertTrue(plan.front.sourceCropRect.height >= 1f)
        }
    }

    @Test
    fun cardRatioMatchedSourceIsNotCroppedAtAll() {
        // An image already at the slot's ratio should use (almost) its full area.
        val plan = IdCardCombinedPagePlanner.plan(
            pageWidth = pageWidth,
            frontImageWidth = 1586,
            frontImageHeight = 1000
        )

        val crop = plan.front.sourceCropRect
        assertEquals(0f, crop.left, 1f)
        assertEquals(0f, crop.top, 1f)
        assertEquals(1586f, crop.width, 2f)
        assertEquals(1000f, crop.height, 2f)
    }

    @Test
    fun frontOnlyPlanHasNoBackSide() {
        val plan = IdCardCombinedPagePlanner.plan(
            pageWidth = pageWidth,
            frontImageWidth = 1600,
            frontImageHeight = 1000
        )

        assertNull(plan.back)
        val expected = IdCardLayoutPlanner.plan(
            sideCount = 1,
            pageWidth = plan.pageWidth.toFloat(),
            pageHeight = plan.pageHeight.toFloat()
        )
        assertEquals(expected.single(), plan.front.cardRect)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositivePageWidthThrows() {
        IdCardCombinedPagePlanner.plan(pageWidth = 0, frontImageWidth = 100, frontImageHeight = 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveFrontDimensionsThrow() {
        IdCardCombinedPagePlanner.plan(pageWidth = pageWidth, frontImageWidth = 0, frontImageHeight = 100)
    }

    @Test(expected = IllegalArgumentException::class)
    fun partialBackDimensionsThrow() {
        IdCardCombinedPagePlanner.plan(
            pageWidth = pageWidth,
            frontImageWidth = 100,
            frontImageHeight = 100,
            backImageWidth = 100,
            backImageHeight = null
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonPositiveBackDimensionsThrow() {
        IdCardCombinedPagePlanner.plan(
            pageWidth = pageWidth,
            frontImageWidth = 100,
            frontImageHeight = 100,
            backImageWidth = -1,
            backImageHeight = 100
        )
    }

    /**
     * No stretching: because the destination is always the complete card slot, proportions are
     * preserved exactly when the source crop's aspect ratio equals the slot's aspect ratio.
     */
    private fun assertCropMatchesSlotRatio(draw: IdCardCombinedSideDraw) {
        val slotRatio = draw.cardRect.width / draw.cardRect.height
        val cropRatio = draw.sourceCropRect.width / draw.sourceCropRect.height
        assertEquals(slotRatio, cropRatio, 0.001f)
    }

    private fun assertCropInsideSource(crop: CardRect, imageWidth: Int, imageHeight: Int) {
        assertTrue(crop.left >= -0.01f)
        assertTrue(crop.top >= -0.01f)
        assertTrue(crop.right <= imageWidth + 0.01f)
        assertTrue(crop.bottom <= imageHeight + 0.01f)
    }
}
