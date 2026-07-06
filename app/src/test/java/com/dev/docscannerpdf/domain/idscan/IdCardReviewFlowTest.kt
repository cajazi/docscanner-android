package com.dev.docscannerpdf.domain.idscan

import org.junit.Assert.assertEquals
import org.junit.Test

class IdCardReviewFlowTest {

    private fun frontOnly() = IdCardReviewState(frontImageUri = "content://scan/front.jpg")

    private fun frontAndBack() = IdCardReviewState(
        frontImageUri = "content://scan/front.jpg",
        backImageUri = "content://scan/back.jpg"
    )

    @Test
    fun startsSelectedOnFrontWithNoRotation() {
        val state = frontAndBack()

        assertEquals(IdCardReviewSide.FRONT, state.selectedSide)
        assertEquals(0, state.frontRotationDegrees)
        assertEquals(0, state.backRotationDegrees)
    }

    @Test
    fun selectingBackChangesSelectedSide() {
        val selected = IdCardReviewFlow.selectSide(frontAndBack(), IdCardReviewSide.BACK)

        assertEquals(IdCardReviewSide.BACK, selected.selectedSide)
    }

    @Test
    fun selectingBackWhenNoneCapturedIsNoOp() {
        val state = frontOnly()

        val selected = IdCardReviewFlow.selectSide(state, IdCardReviewSide.BACK)

        assertEquals(state, selected)
    }

    @Test
    fun rotatingSelectedFrontOnlyChangesFrontRotation() {
        val rotated = IdCardReviewFlow.rotateSelected(frontAndBack())

        assertEquals(90, rotated.frontRotationDegrees)
        assertEquals(0, rotated.backRotationDegrees)
    }

    @Test
    fun rotatingSelectedBackOnlyChangesBackRotation() {
        val backSelected = IdCardReviewFlow.selectSide(frontAndBack(), IdCardReviewSide.BACK)

        val rotated = IdCardReviewFlow.rotateSelected(backSelected)

        assertEquals(0, rotated.frontRotationDegrees)
        assertEquals(90, rotated.backRotationDegrees)
    }

    @Test
    fun rotationWrapsAt360() {
        var state = frontAndBack()
        repeat(4) { state = IdCardReviewFlow.rotateSelected(state) }

        assertEquals(0, state.frontRotationDegrees)
    }

    @Test
    fun withUpdatedImageReplacesOnlyTheGivenSide() {
        val state = frontAndBack()

        val updated = IdCardReviewFlow.withUpdatedImage(state, IdCardReviewSide.FRONT, "file:///cropped/front-2.jpg")

        assertEquals("file:///cropped/front-2.jpg", updated.frontImageUri)
        assertEquals("content://scan/back.jpg", updated.backImageUri)
    }

    @Test
    fun imageUriAndRotationDegreesLookUpBySide() {
        val state = IdCardReviewFlow.rotateSelected(frontAndBack())

        assertEquals("content://scan/front.jpg", state.imageUri(IdCardReviewSide.FRONT))
        assertEquals("content://scan/back.jpg", state.imageUri(IdCardReviewSide.BACK))
        assertEquals(90, state.rotationDegrees(IdCardReviewSide.FRONT))
        assertEquals(0, state.rotationDegrees(IdCardReviewSide.BACK))
    }
}
