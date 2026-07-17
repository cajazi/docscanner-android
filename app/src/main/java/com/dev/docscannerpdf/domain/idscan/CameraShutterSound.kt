package com.dev.docscannerpdf.domain.idscan

/**
 * Plays the camera shutter sound for an accepted still capture. The production implementation
 * wraps Android's `MediaActionSound` (see `MediaActionShutterSoundPlayer` in the UI layer);
 * tests substitute a recording fake. [play] must never throw — an audio problem must never
 * block or crash the capture itself — and [release] must be idempotent.
 */
interface CameraShutterSoundPlayer {
    fun play()
    fun release()
}

/**
 * Pure single-flight gate for camera capture taps plus the shutter-sound policy. Accepting a
 * tap ([onCaptureAccepted]) is deliberately SILENT — the sound plays only in
 * [onCaptureSubmitted], which the camera controller invokes immediately before the real
 * CameraX takePicture call, AFTER confirming its ImageCapture use case actually exists. A tap
 * whose submission never happens (camera not bound yet) therefore never makes a sound.
 *
 * Everything that is not an accepted-and-submitted camera capture stays silent by
 * construction, because nothing else calls into this gate: rejected rapid duplicate taps
 * (gate already busy), gallery imports, crop confirmation, filter selection, rotation, retake
 * navigation, and the green-check save all bypass it entirely. Recomposition can't double-fire
 * either — [onCaptureSubmitted] plays at most once per accepted capture, and the busy flag
 * flips synchronously inside the accept call. Owned by the capture layer; the review screen
 * never touches it.
 */
class IdCardCaptureShutterGate(private val shutterSound: CameraShutterSoundPlayer) {

    /** True while an accepted capture is in flight; further taps are rejected silently. */
    var isCapturing: Boolean = false
        private set

    private var soundPlayedForCurrentCapture = false

    /**
     * Called when the capture button is pressed AFTER the caller's own permission/processing
     * guards pass. Returns true when the tap is accepted (single-flight acquired), false while
     * a previous capture is still in flight. Plays NO sound — submission does that.
     */
    fun onCaptureAccepted(): Boolean {
        if (isCapturing) return false
        isCapturing = true
        soundPlayedForCurrentCapture = false
        return true
    }

    /**
     * Called by the camera controller immediately before the real takePicture submission.
     * Plays the shutter exactly once per accepted capture; a repeat call for the same capture
     * is a no-op. Never throws — an audio failure must never cancel, block, or crash the
     * capture that was just submitted — and never affects the gate's busy state.
     */
    fun onCaptureSubmitted() {
        if (!isCapturing || soundPlayedForCurrentCapture) return
        soundPlayedForCurrentCapture = true
        runCatching { shutterSound.play() }
    }

    /**
     * Called when the in-flight capture finishes — success, null result, baking failure,
     * cancellation, or a submission that never happened — re-arming the gate for a retry.
     */
    fun onCaptureFinished() {
        isCapturing = false
        soundPlayedForCurrentCapture = false
    }
}
