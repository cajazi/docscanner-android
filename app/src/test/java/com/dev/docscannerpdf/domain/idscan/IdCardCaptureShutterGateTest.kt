package com.dev.docscannerpdf.domain.idscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdCardCaptureShutterGateTest {

    private class RecordingShutterSound : CameraShutterSoundPlayer {
        var playCount = 0
        var throwOnPlay = false

        override fun play() {
            playCount++
            if (throwOnPlay) throw IllegalStateException("audio system unavailable")
        }

        override fun release() = Unit
    }

    // --- sound plays only on real submission ---

    @Test
    fun captureNotReadyProducesNoSound() {
        // Camera not bound yet: the tap is accepted, but the controller never submits, the
        // result callback reports null, and the gate is re-armed — all silently.
        val sound = RecordingShutterSound()
        val gate = IdCardCaptureShutterGate(sound)

        assertTrue(gate.onCaptureAccepted())
        // no onCaptureSubmitted — submission was impossible
        gate.onCaptureFinished()

        assertEquals(0, sound.playCount)
        assertFalse(gate.isCapturing)
    }

    @Test
    fun successfulSubmittedCapturePlaysExactlyOnce() {
        val sound = RecordingShutterSound()
        val gate = IdCardCaptureShutterGate(sound)

        assertTrue(gate.onCaptureAccepted())
        gate.onCaptureSubmitted()

        assertEquals(1, sound.playCount)
        assertTrue(gate.isCapturing)
    }

    @Test
    fun repeatedSubmissionForOneCapturePlaysOnce() {
        // Recomposition/observer-replay safety: a second submitted signal for the same
        // accepted capture must not double the sound.
        val sound = RecordingShutterSound()
        val gate = IdCardCaptureShutterGate(sound)

        gate.onCaptureAccepted()
        gate.onCaptureSubmitted()
        gate.onCaptureSubmitted()

        assertEquals(1, sound.playCount)
    }

    @Test
    fun submissionWithoutAcceptedCaptureIsSilent() {
        val sound = RecordingShutterSound()
        val gate = IdCardCaptureShutterGate(sound)

        gate.onCaptureSubmitted()

        assertEquals(0, sound.playCount)
    }

    @Test
    fun duplicateTapWhileBusyPlaysNoExtraSound() {
        val sound = RecordingShutterSound()
        val gate = IdCardCaptureShutterGate(sound)

        assertTrue(gate.onCaptureAccepted())
        gate.onCaptureSubmitted()
        assertFalse("in-flight capture must reject the tap", gate.onCaptureAccepted())
        assertFalse(gate.onCaptureAccepted())

        assertEquals("rapid taps play once total", 1, sound.playCount)
    }

    @Test
    fun frontThenBackCapturesPlayOnceEach() {
        val sound = RecordingShutterSound()
        val gate = IdCardCaptureShutterGate(sound)

        assertTrue(gate.onCaptureAccepted()) // front
        gate.onCaptureSubmitted()
        gate.onCaptureFinished()
        assertTrue(gate.onCaptureAccepted()) // back
        gate.onCaptureSubmitted()
        gate.onCaptureFinished()

        assertEquals(2, sound.playCount)
        assertFalse(gate.isCapturing)
    }

    @Test
    fun importNeverConsultsTheGate() {
        // Imports, crop, filter, rotation, retake navigation and green-check save never call
        // the gate — the sound is structurally impossible for them. This asserts the baseline.
        val sound = RecordingShutterSound()
        IdCardCaptureShutterGate(sound)

        assertEquals(0, sound.playCount)
    }

    // --- sound failure never blocks capture ---

    @Test
    fun soundFailureDoesNotThrowAndCaptureRemainsAcceptedAndBusy() {
        val sound = RecordingShutterSound().apply { throwOnPlay = true }
        val gate = IdCardCaptureShutterGate(sound)

        val accepted = gate.onCaptureAccepted()
        // Must NOT throw even with a hostile player — the caller never needs runCatching.
        gate.onCaptureSubmitted()

        assertTrue("capture stays accepted despite audio failure", accepted)
        assertTrue("gate stays busy until the capture completes", gate.isCapturing)

        // The capture continues and completes normally.
        gate.onCaptureFinished()
        assertFalse(gate.isCapturing)

        // And the whole flow works again afterwards.
        sound.throwOnPlay = false
        assertTrue(gate.onCaptureAccepted())
        gate.onCaptureSubmitted()
        assertEquals(2, sound.playCount)
    }

    // --- every completion path re-arms the gate (the screen calls finish from finally) ---

    @Test
    fun nullCaptureResultResetsGateForSilentRetry() {
        val sound = RecordingShutterSound()
        val gate = IdCardCaptureShutterGate(sound)

        gate.onCaptureAccepted()
        gate.onCaptureSubmitted()
        gate.onCaptureFinished() // controller reported null

        assertFalse(gate.isCapturing)
        assertTrue("retry is a fresh accepted capture", gate.onCaptureAccepted())
    }

    @Test
    fun bakingFailureResetsGate() {
        val sound = RecordingShutterSound()
        val gate = IdCardCaptureShutterGate(sound)

        gate.onCaptureAccepted()
        gate.onCaptureSubmitted()
        gate.onCaptureFinished() // screen's finally after a baking throw

        assertFalse(gate.isCapturing)
        assertTrue(gate.onCaptureAccepted())
        gate.onCaptureSubmitted()
        assertEquals("each accepted capture plays its own single sound", 2, sound.playCount)
    }

    @Test
    fun cancellationResetsGate() {
        val sound = RecordingShutterSound()
        val gate = IdCardCaptureShutterGate(sound)

        gate.onCaptureAccepted()
        gate.onCaptureSubmitted()
        gate.onCaptureFinished() // screen's finally when the processing coroutine is cancelled

        assertFalse(gate.isCapturing)
    }

    @Test
    fun submissionFailureRestoresRetryability() {
        val sound = RecordingShutterSound()
        val gate = IdCardCaptureShutterGate(sound)

        gate.onCaptureAccepted()
        gate.onCaptureFinished() // submission never happened (unbound camera), silent

        assertEquals(0, sound.playCount)
        assertTrue(gate.onCaptureAccepted())
        gate.onCaptureSubmitted()
        assertEquals("the retry's submission still plays exactly once", 1, sound.playCount)
    }
}
