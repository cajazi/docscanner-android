package com.dev.docscannerpdf.domain.mainscan

/**
 * Initialization state of the Main Scanner camera. Typed rather than a pair of booleans so
 * "bound but degraded" is not representable.
 */
enum class MainScanCameraState {
    /** The single bind attempt is in flight. No capture may be submitted yet. */
    BINDING,

    /** One lifecycle bind carrying Preview + ImageCapture + ImageAnalysis attached successfully. */
    READY,

    /**
     * The bind attempt failed and its use cases were released. This is TERMINAL for the visit: the
     * surface reports a controlled initialization failure instead of presenting a camera, and no
     * further automatic bind is attempted.
     */
    FAILED
}

/**
 * Pure rules for the Main Scanner camera's single-bind contract.
 *
 * The Slice 1 contract is one lifecycle bind containing all three use cases — Preview, ImageCapture
 * and ImageAnalysis — or a controlled failure. There is deliberately NO reduced configuration:
 * silently dropping ImageAnalysis would present a scanner whose crop seeding cannot work, which is
 * exactly the kind of invisible degradation the reference flow must not have. When the bind fails
 * the attempt is released and ML Kit remains the available fallback path.
 *
 * Expected ownership for a normal visit, which these rules make structural rather than incidental:
 *
 *     controller creations: 1   (one keyless remember per screen visit)
 *     initial binds:        1   ([allowsBindAttempt] admits exactly the first attempt)
 *     releases:             1   (one idempotent dispose)
 *     recovery binds:       0   ([allowsAutomaticRecovery] requires a streaming failure on a
 *                                READY camera, which does not occur in a clean visit)
 */
object MainScanCameraLifecycle {

    /** The state after the one bind attempt resolves. */
    fun afterBindAttempt(success: Boolean): MainScanCameraState =
        if (success) MainScanCameraState.READY else MainScanCameraState.FAILED

    /**
     * Whether a bind attempt may be made from [state]. Only [MainScanCameraState.BINDING] admits
     * one, so a resolved camera is never re-bound implicitly and — critically — a
     * [MainScanCameraState.FAILED] camera is never given a second, degraded attempt.
     */
    fun allowsBindAttempt(state: MainScanCameraState): Boolean =
        state == MainScanCameraState.BINDING

    /** Capture may only be submitted to a fully attached three-use-case camera. */
    fun allowsCapture(state: MainScanCameraState): Boolean =
        state == MainScanCameraState.READY

    /**
     * Whether the surface must present a controlled initialization failure instead of a preview.
     * A scanner is never shown in this state — the user is told, and the ML Kit fallback is offered.
     */
    fun requiresFailureSurface(state: MainScanCameraState): Boolean =
        state == MainScanCameraState.FAILED

    /**
     * Whether an automatic preview-recovery rebind is permitted. Requires a camera that genuinely
     * attached ([MainScanCameraState.READY]) whose preview has since stopped streaming. A FAILED or
     * still-BINDING camera never recovers automatically, so a failed three-use-case bind can never
     * be laundered into a second attempt through the recovery path.
     */
    fun allowsAutomaticRecovery(
        state: MainScanCameraState,
        previewStreaming: Boolean,
        bindInFlight: Boolean
    ): Boolean = state == MainScanCameraState.READY && !previewStreaming && !bindInFlight
}
