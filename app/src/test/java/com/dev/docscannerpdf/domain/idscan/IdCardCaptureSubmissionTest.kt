package com.dev.docscannerpdf.domain.idscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the controller's submission ordering (sound only AFTER a successful takePicture
 * return) via the extracted [IdCardCaptureSubmission], plus its interaction with
 * [IdCardCaptureShutterGate] for the full accepted-tap-to-sound pipeline.
 */
class IdCardCaptureSubmissionTest {

    private class RecordingShutterSound : CameraShutterSoundPlayer {
        var playCount = 0
        override fun play() {
            playCount++
        }

        override fun release() = Unit
    }

    @Test
    fun successfulSubmissionFiresOnSubmittedExactlyOnceAfterSubmit() {
        val order = mutableListOf<String>()

        val submitted = IdCardCaptureSubmission.submit(
            submit = { order.add("takePicture") },
            onSubmitted = { order.add("sound") }
        )

        assertTrue(submitted)
        assertEquals(listOf("takePicture", "sound"), order)
    }

    @Test
    fun synchronousThrowProducesNoSoundAndReportsErrorOnce() {
        var sounds = 0
        val errors = mutableListOf<Throwable>()

        val submitted = IdCardCaptureSubmission.submit(
            submit = { throw IllegalStateException("camera is closed") },
            onSubmitted = { sounds++ },
            onSubmissionError = { errors.add(it) }
        )

        assertFalse(submitted)
        assertEquals("no sound without a real request", 0, sounds)
        assertEquals(1, errors.size)
        assertEquals("camera is closed", errors.single().message)
    }

    @Test
    fun throwingOnSubmittedStillCountsAsSubmitted() {
        var errors = 0

        val submitted = IdCardCaptureSubmission.submit(
            submit = { },
            onSubmitted = { throw IllegalStateException("audio died") },
            onSubmissionError = { errors++ }
        )

        // The request already reached the camera — a sound failure must not un-submit it.
        assertTrue(submitted)
        assertEquals(0, errors)
    }

    // --- full pipeline with the gate (mirrors the controller + screen wiring) ---

    @Test
    fun cameraUnavailablePathProducesNoSoundAndStaysRetryable() {
        val sound = RecordingShutterSound()
        val gate = IdCardCaptureShutterGate(sound)

        // Controller early-returns before submission when imageCapture is null (same shape for
        // an invalid output directory): the gate was acquired, never submitted, then the UI's
        // null-result path finishes it.
        assertTrue(gate.onCaptureAccepted())
        gate.onCaptureFinished()

        assertEquals(0, sound.playCount)
        assertTrue("immediate retry is possible", gate.onCaptureAccepted())
    }

    @Test
    fun synchronousTakePictureFailureRestoresRetryabilityWithoutSound() {
        val sound = RecordingShutterSound()
        val gate = IdCardCaptureShutterGate(sound)

        assertTrue(gate.onCaptureAccepted())
        val submitted = IdCardCaptureSubmission.submit(
            submit = { throw IllegalStateException("use case not bound") },
            onSubmitted = gate::onCaptureSubmitted
        )
        assertFalse(submitted)
        // UI null-result path:
        gate.onCaptureFinished()

        assertEquals(0, sound.playCount)

        // Retry succeeds and plays exactly once.
        assertTrue(gate.onCaptureAccepted())
        assertTrue(
            IdCardCaptureSubmission.submit(
                submit = { },
                onSubmitted = gate::onCaptureSubmitted
            )
        )
        assertEquals(1, sound.playCount)
    }

    @Test
    fun duplicateTapDuringSubmittedCaptureAddsNoSound() {
        val sound = RecordingShutterSound()
        val gate = IdCardCaptureShutterGate(sound)

        assertTrue(gate.onCaptureAccepted())
        IdCardCaptureSubmission.submit(submit = { }, onSubmitted = gate::onCaptureSubmitted)
        assertFalse("busy gate rejects the tap", gate.onCaptureAccepted())

        assertEquals(1, sound.playCount)
    }

    @Test
    fun soundFailureNeitherBlocksTheCaptureNorWedgesTheGate() {
        val hostile = object : CameraShutterSoundPlayer {
            override fun play(): Unit = throw IllegalStateException("audio system unavailable")
            override fun release() = Unit
        }
        val gate = IdCardCaptureShutterGate(hostile)

        assertTrue(gate.onCaptureAccepted())
        val submitted = IdCardCaptureSubmission.submit(
            submit = { },
            onSubmitted = gate::onCaptureSubmitted
        )

        assertTrue("capture proceeds despite audio failure", submitted)
        assertTrue("gate stays correctly busy", gate.isCapturing)
        gate.onCaptureFinished()
        assertFalse(gate.isCapturing)
    }
}
