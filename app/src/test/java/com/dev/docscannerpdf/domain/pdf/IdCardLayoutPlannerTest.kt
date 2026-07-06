package com.dev.docscannerpdf.domain.pdf

import com.dev.docscannerpdf.util.AppConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdCardLayoutPlannerTest {

    private val pageWidth = AppConstants.A4_WIDTH_POINTS.toFloat()
    private val pageHeight = AppConstants.A4_HEIGHT_POINTS.toFloat()

    @Test
    fun frontOnlyLayoutHasOneCenteredCardWithIdCardRatio() {
        val rects = IdCardLayoutPlanner.plan(sideCount = 1, pageWidth = pageWidth, pageHeight = pageHeight)

        assertEquals(1, rects.size)
        val card = rects.single()
        assertEquals(IdCardLayoutPlanner.CARD_ASPECT_RATIO, card.width / card.height, 0.001f)

        val horizontalCenter = card.left + card.width / 2f
        val verticalCenter = card.top + card.height / 2f
        assertEquals(pageWidth / 2f, horizontalCenter, 0.01f)
        assertEquals(pageHeight / 2f, verticalCenter, 0.01f)

        // Not stretched into a full-page/document-sized slot.
        assertTrue(card.height < pageHeight * 0.7f)
        assertTrue(card.width < pageWidth)
    }

    @Test
    fun frontAndBackLayoutHasTwoSameSizeCardsFrontAboveBack() {
        val rects = IdCardLayoutPlanner.plan(sideCount = 2, pageWidth = pageWidth, pageHeight = pageHeight)

        assertEquals(2, rects.size)
        val (front, back) = rects

        assertEquals(front.width, back.width, 0.001f)
        assertEquals(front.height, back.height, 0.001f)
        assertEquals(IdCardLayoutPlanner.CARD_ASPECT_RATIO, front.width / front.height, 0.001f)

        // Front is above back with no overlap.
        assertTrue(front.top < back.top)
        assertTrue(front.bottom <= back.top)
    }

    @Test
    fun frontAndBackRectsAreCenteredHorizontally() {
        val rects = IdCardLayoutPlanner.plan(sideCount = 2, pageWidth = pageWidth, pageHeight = pageHeight)

        val (front, back) = rects
        assertEquals(front.left, back.left, 0.001f)
        val expectedLeft = (pageWidth - front.width) / 2f
        assertEquals(expectedLeft, front.left, 0.01f)
    }

    @Test
    fun twoSideCardGroupFitsWithinPageBounds() {
        val rects = IdCardLayoutPlanner.plan(sideCount = 2, pageWidth = pageWidth, pageHeight = pageHeight)

        rects.forEach { rect ->
            assertTrue(rect.left >= 0f)
            assertTrue(rect.top >= 0f)
            assertTrue(rect.right <= pageWidth)
            assertTrue(rect.bottom <= pageHeight)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSideCountThrows() {
        IdCardLayoutPlanner.plan(sideCount = 3, pageWidth = pageWidth, pageHeight = pageHeight)
    }
}
