package com.dev.docscannerpdf.domain.idscan

/** Which step a guided front/back ID-card capture session is currently on. */
enum class IdCardCaptureStage { FRONT, BACK, COMPLETE }

/**
 * Pure state for the guided ID-card capture flow: starts on [IdCardCaptureStage.FRONT], moves to
 * [IdCardCaptureStage.BACK] once the front is captured, and reaches
 * [IdCardCaptureStage.COMPLETE] once the back is captured (or explicitly skipped for a
 * front-only card). Captured images are plain strings rather than [android.net.Uri] so this
 * model stays framework-free and unit-testable on the JVM; the guided capture screen converts
 * to/from [android.net.Uri] at its edges.
 */
data class IdCardCaptureState(
    val stage: IdCardCaptureStage = IdCardCaptureStage.FRONT,
    val frontImageUri: String? = null,
    val backImageUri: String? = null
)

/**
 * Pure reducer driving [IdCardCaptureState] transitions. Kept free of Android/CameraX types so
 * the front-then-back sequencing can be unit tested directly, independent of the guided capture
 * screen's camera plumbing.
 */
object IdCardCaptureFlow {

    /**
     * Records a just-captured side's [imageUri]. On [IdCardCaptureStage.FRONT] this stores the
     * front image and advances to [IdCardCaptureStage.BACK]; on [IdCardCaptureStage.BACK] this
     * stores the back image and completes the flow. A capture while already
     * [IdCardCaptureStage.COMPLETE] is a no-op.
     */
    fun onSideCaptured(state: IdCardCaptureState, imageUri: String): IdCardCaptureState =
        when (state.stage) {
            IdCardCaptureStage.FRONT -> state.copy(stage = IdCardCaptureStage.BACK, frontImageUri = imageUri)
            IdCardCaptureStage.BACK -> state.copy(stage = IdCardCaptureStage.COMPLETE, backImageUri = imageUri)
            IdCardCaptureStage.COMPLETE -> state
        }

    /**
     * Lets the user finish with a front-only card by skipping/exiting out of the back-capture
     * step. Only valid from [IdCardCaptureStage.BACK] (there is nothing to skip on
     * [IdCardCaptureStage.FRONT], and [IdCardCaptureStage.COMPLETE] is already done).
     */
    fun skipBack(state: IdCardCaptureState): IdCardCaptureState =
        if (state.stage == IdCardCaptureStage.BACK) {
            state.copy(stage = IdCardCaptureStage.COMPLETE)
        } else {
            state
        }

    /**
     * The CamScanner-style undo arrow next to the shutter: steps back one capture. From
     * [IdCardCaptureStage.BACK] it returns to [IdCardCaptureStage.FRONT], discarding the stored
     * front image so it can be retaken. There is nothing to undo on [IdCardCaptureStage.FRONT],
     * and [IdCardCaptureStage.COMPLETE] has already left the capture screen — both are no-ops.
     */
    fun undoCapture(state: IdCardCaptureState): IdCardCaptureState =
        if (state.stage == IdCardCaptureStage.BACK) {
            state.copy(stage = IdCardCaptureStage.FRONT, frontImageUri = null)
        } else {
            state
        }
}
