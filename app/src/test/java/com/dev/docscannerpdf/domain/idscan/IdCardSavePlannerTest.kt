package com.dev.docscannerpdf.domain.idscan

import com.dev.docscannerpdf.domain.filter.DocumentFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdCardSavePlannerTest {

    @Test
    fun frontOnlyReviewPlansOneSideWithPageCountOne() {
        val state = IdCardReviewState(
            frontBaseImageUri = "file://base/front.jpg",
            title = "ID Card 15-07-2026 12.00"
        )

        val plan = IdCardSavePlanner.plan(state) as IdCardSavePlan.Ready

        assertEquals("ID Card 15-07-2026 12.00", plan.title)
        assertEquals(1, plan.pageCount)
        assertEquals(IdCardReviewSide.FRONT, plan.front.side)
        assertEquals("file://base/front.jpg", plan.front.baseImageUri)
        assertNull(plan.back)
    }

    @Test
    fun frontAndBackReviewPlansBothSidesIndependently() {
        val state = IdCardReviewState(
            frontBaseImageUri = "file://base/front.jpg",
            backBaseImageUri = "file://base/back.jpg",
            frontFilter = DocumentFilter.BW,
            backFilter = DocumentFilter.ORIGINAL,
            frontRenderedImageUri = "file://rendered/front-bw.jpg",
            frontRotationDegrees = 90,
            backRotationDegrees = 0
        )

        val plan = IdCardSavePlanner.plan(state) as IdCardSavePlan.Ready

        assertEquals(1, plan.pageCount)
        assertEquals(DocumentFilter.BW, plan.front.filter)
        assertEquals("file://rendered/front-bw.jpg", plan.front.renderedImageUri)
        assertEquals(90, plan.front.rotationDegrees)
        val back = requireNotNull(plan.back)
        assertEquals(IdCardReviewSide.BACK, back.side)
        assertEquals(DocumentFilter.ORIGINAL, back.filter)
        assertEquals(0, back.rotationDegrees)
    }

    @Test
    fun blankFrontIsInvalid() {
        val plan = IdCardSavePlanner.plan(IdCardReviewState(frontBaseImageUri = "  "))

        assertTrue(plan is IdCardSavePlan.Invalid)
    }

    @Test
    fun requiresFilterRenderOnlyWhenNonOriginalAndUnrendered() {
        // ORIGINAL never needs a render, even with nothing published.
        val original = IdCardSideSavePlan(
            side = IdCardReviewSide.FRONT,
            baseImageUri = "file://base/front.jpg",
            filter = DocumentFilter.ORIGINAL,
            renderedImageUri = null,
            rotationDegrees = 0
        )
        assertFalse(original.requiresFilterRender)

        // A pending (unpublished) non-ORIGINAL render must be produced by the save flow itself.
        val pending = original.copy(filter = DocumentFilter.SEPIA)
        assertTrue(pending.requiresFilterRender)

        // An already-published render is reused as-is.
        val published = pending.copy(renderedImageUri = "file://rendered/front-sepia.jpg")
        assertFalse(published.requiresFilterRender)
    }

    @Test
    fun requiresRotationBakeOnlyForNonZeroDegrees() {
        val side = IdCardSideSavePlan(
            side = IdCardReviewSide.FRONT,
            baseImageUri = "file://base/front.jpg",
            filter = DocumentFilter.ENHANCE,
            renderedImageUri = "file://rendered/front.jpg",
            rotationDegrees = 0
        )

        assertFalse(side.requiresRotationBake)
        assertTrue(side.copy(rotationDegrees = 90).requiresRotationBake)
        assertTrue(side.copy(rotationDegrees = 270).requiresRotationBake)
    }

    @Test
    fun planCapturesTitleUsedAsDocumentTitle() {
        val state = IdCardReviewState(
            frontBaseImageUri = "file://base/front.jpg",
            title = "Renamed By User"
        )

        val plan = IdCardSavePlanner.plan(state) as IdCardSavePlan.Ready

        assertEquals("Renamed By User", plan.title)
    }
}
