package com.dev.docscannerpdf.domain.idscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdCardCaptureFlowTest {

    @Test
    fun startsAtFront() {
        val state = IdCardCaptureState()

        assertEquals(IdCardCaptureStage.FRONT, state.stage)
        assertNull(state.frontImageUri)
        assertNull(state.backImageUri)
    }

    @Test
    fun afterFrontCapturedMovesToBack() {
        val state = IdCardCaptureFlow.onSideCaptured(IdCardCaptureState(), "content://scan/front.jpg")

        assertEquals(IdCardCaptureStage.BACK, state.stage)
        assertEquals("content://scan/front.jpg", state.frontImageUri)
        assertNull(state.backImageUri)
    }

    @Test
    fun afterBackCapturedCompletesWithFrontAndBackUris() {
        val afterFront = IdCardCaptureFlow.onSideCaptured(IdCardCaptureState(), "content://scan/front.jpg")
        val afterBack = IdCardCaptureFlow.onSideCaptured(afterFront, "content://scan/back.jpg")

        assertEquals(IdCardCaptureStage.COMPLETE, afterBack.stage)
        assertEquals("content://scan/front.jpg", afterBack.frontImageUri)
        assertEquals("content://scan/back.jpg", afterBack.backImageUri)
    }

    @Test
    fun captureIsNoOpOnceComplete() {
        val afterFront = IdCardCaptureFlow.onSideCaptured(IdCardCaptureState(), "content://scan/front.jpg")
        val afterBack = IdCardCaptureFlow.onSideCaptured(afterFront, "content://scan/back.jpg")

        val extraCapture = IdCardCaptureFlow.onSideCaptured(afterBack, "content://scan/other.jpg")

        assertEquals(afterBack, extraCapture)
    }

    @Test
    fun skipBackFromBackStageCompletesFrontOnly() {
        val afterFront = IdCardCaptureFlow.onSideCaptured(IdCardCaptureState(), "content://scan/front.jpg")

        val skipped = IdCardCaptureFlow.skipBack(afterFront)

        assertEquals(IdCardCaptureStage.COMPLETE, skipped.stage)
        assertEquals("content://scan/front.jpg", skipped.frontImageUri)
        assertNull(skipped.backImageUri)
    }

    @Test
    fun skipBackIsNoOpOnFrontStage() {
        val initial = IdCardCaptureState()

        val skipped = IdCardCaptureFlow.skipBack(initial)

        assertEquals(initial, skipped)
    }

    @Test
    fun skipBackIsNoOpOnceComplete() {
        val afterFront = IdCardCaptureFlow.onSideCaptured(IdCardCaptureState(), "content://scan/front.jpg")
        val afterBack = IdCardCaptureFlow.onSideCaptured(afterFront, "content://scan/back.jpg")

        val skipped = IdCardCaptureFlow.skipBack(afterBack)

        assertEquals(afterBack, skipped)
    }
}
