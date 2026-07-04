package com.dev.docscannerpdf.domain.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdCardPdfPlannerTest {

    @Test
    fun frontAndBackAreOrderedFrontFirstThenBack() {
        val plan = IdCardPdfPlanner.plan(
            IdCardPdfInput(
                frontImageUrl = "content://scan/front.jpg",
                backImageUrl = "content://scan/back.jpg"
            )
        )

        val ready = plan as IdCardPdfPlan.Ready
        assertEquals(
            listOf(
                IdCardSidePlan(IdCardSide.FRONT, "content://scan/front.jpg"),
                IdCardSidePlan(IdCardSide.BACK, "content://scan/back.jpg")
            ),
            ready.sides
        )
    }

    @Test
    fun singleFrontLayoutRemainsValid() {
        val plan = IdCardPdfPlanner.plan(
            IdCardPdfInput(frontImageUrl = "content://scan/front.jpg", backImageUrl = null)
        )

        val ready = plan as IdCardPdfPlan.Ready
        assertEquals(listOf(IdCardSidePlan(IdCardSide.FRONT, "content://scan/front.jpg")), ready.sides)
    }

    @Test
    fun blankBackImageIsTreatedAsFrontOnly() {
        val plan = IdCardPdfPlanner.plan(
            IdCardPdfInput(frontImageUrl = "content://scan/front.jpg", backImageUrl = "   ")
        )

        val ready = plan as IdCardPdfPlan.Ready
        assertEquals(listOf(IdCardSidePlan(IdCardSide.FRONT, "content://scan/front.jpg")), ready.sides)
    }

    @Test
    fun missingFrontImageIsInvalid() {
        val plan = IdCardPdfPlanner.plan(IdCardPdfInput(frontImageUrl = null, backImageUrl = "content://scan/back.jpg"))

        assertTrue(plan is IdCardPdfPlan.Invalid)
    }

    @Test
    fun blankOcrTextIsNormalizedToNull() {
        val plan = IdCardPdfPlanner.plan(
            IdCardPdfInput(frontImageUrl = "content://scan/front.jpg", ocrText = "   ")
        )

        val ready = plan as IdCardPdfPlan.Ready
        assertEquals(null, ready.ocrText)
    }
}
