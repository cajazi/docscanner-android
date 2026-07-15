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

    @Test
    fun imagesAreFittedWithoutStretchingAndStayInsideTheirCardSlot() {
        // A landscape card photo and a portrait (mis-oriented) one: neither may be stretched.
        val plan = IdCardCombinedPagePlanner.plan(
            pageWidth = pageWidth,
            frontImageWidth = 1600,
            frontImageHeight = 1000,
            backImageWidth = 1000,
            backImageHeight = 1600
        )

        assertAspectPreserved(plan.front.imageRect, 1600, 1000)
        assertInside(plan.front.imageRect, plan.front.cardRect)
        val back = plan.back
        assertNotNull(back)
        requireNotNull(back)
        assertAspectPreserved(back.imageRect, 1000, 1600)
        assertInside(back.imageRect, back.cardRect)
    }

    @Test
    fun fittedImagesAreCenteredWithinTheirCardSlot() {
        val plan = IdCardCombinedPagePlanner.plan(
            pageWidth = pageWidth,
            frontImageWidth = 1000,
            frontImageHeight = 1600
        )

        val card = plan.front.cardRect
        val image = plan.front.imageRect
        assertEquals(card.left + card.width / 2f, image.left + image.width / 2f, 0.01f)
        assertEquals(card.top + card.height / 2f, image.top + image.height / 2f, 0.01f)
    }

    @Test
    fun cardAspectRatioMatchedImageFillsItsSlot() {
        // An image already at the ID-1 card ratio should occupy the whole slot.
        val plan = IdCardCombinedPagePlanner.plan(
            pageWidth = pageWidth,
            frontImageWidth = 1586,
            frontImageHeight = 1000
        )

        val card = plan.front.cardRect
        val image = plan.front.imageRect
        assertEquals(card.width, image.width, card.width * 0.01f)
        assertEquals(card.height, image.height, card.height * 0.01f)
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

    private fun assertAspectPreserved(imageRect: CardRect, imageWidth: Int, imageHeight: Int) {
        val sourceRatio = imageWidth.toFloat() / imageHeight
        assertEquals(sourceRatio, imageRect.width / imageRect.height, 0.001f)
    }

    private fun assertInside(inner: CardRect, outer: CardRect) {
        assertTrue(inner.left >= outer.left - 0.01f)
        assertTrue(inner.top >= outer.top - 0.01f)
        assertTrue(inner.right <= outer.right + 0.01f)
        assertTrue(inner.bottom <= outer.bottom + 0.01f)
    }
}
