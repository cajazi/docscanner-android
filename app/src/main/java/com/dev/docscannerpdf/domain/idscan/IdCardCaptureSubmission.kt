package com.dev.docscannerpdf.domain.idscan

/**
 * Pure ordering contract for one camera-capture submission attempt: the capture request is the
 * authority for the shutter sound, so [onSubmitted] (which plays it) runs ONLY AFTER [submit]
 * (the real `takePicture` call) returned successfully. A synchronous [submit] throw means no
 * request exists — no sound, and the failure is reported through [onSubmissionError] instead of
 * rethrowing into the UI. A throwing [onSubmitted] can never un-submit the already-accepted
 * request: the attempt still counts as submitted. Extracted from the CameraX controller so this
 * ordering is unit-testable on the JVM.
 */
object IdCardCaptureSubmission {

    /**
     * Runs [submit]; returns true iff it completed without throwing (the request was really
     * handed to the camera). [onSubmitted] fires exactly once after a successful submit, with
     * its own failures swallowed. [onSubmissionError] fires exactly once with the synchronous
     * failure otherwise — the caller then cleans up (delete the output file, report null).
     */
    fun submit(
        submit: () -> Unit,
        onSubmitted: () -> Unit,
        onSubmissionError: (Throwable) -> Unit = {}
    ): Boolean {
        try {
            submit()
        } catch (throwable: Throwable) {
            onSubmissionError(throwable)
            return false
        }
        runCatching { onSubmitted() }
        return true
    }
}
