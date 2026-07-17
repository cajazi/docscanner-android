package com.dev.docscannerpdf.domain.idscan

/**
 * Single-flight guard for camera-preview recovery: a rejected capture must never leave the
 * live preview frozen, but recovery (a CameraX rebind) must run at most once at a time and
 * only when the preview is actually not streaming. Pure and unit-testable; the camera
 * controller consults it before rebinding and reports completion when the bind attempt ends
 * (success or failure), keeping repeated recovery requests idempotent — no delays, no loops.
 */
class PreviewRecoveryGuard {

    var isRecovering: Boolean = false
        private set

    /**
     * True exactly when a recovery attempt should start now: the preview is not streaming and
     * no other attempt is in flight. A true return ACQUIRES the guard.
     */
    fun shouldAttemptRecovery(isPreviewStreaming: Boolean): Boolean {
        if (isPreviewStreaming || isRecovering) return false
        isRecovering = true
        return true
    }

    /** Called when the recovery bind attempt finishes (either way), re-arming the guard. */
    fun onRecoveryComplete() {
        isRecovering = false
    }
}
