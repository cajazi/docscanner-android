package com.dev.docscannerpdf.domain.mainscan

import android.util.Log
import com.dev.docscannerpdf.BuildConfig

/**
 * Debug-only, content-free trace of the Main Scanner shutter→Crop path. Every method short-circuits
 * in release builds, so a production binary carries no logging at all.
 *
 * The content rules are enforced BY CONSTRUCTION: no method accepts a file name, path, URI, bitmap,
 * OCR text, document title, or any user data. The parameters available are booleans, integers, longs
 * (session/generation tokens), enum names, and fixed string literals chosen from this file. Byte
 * length is reported as a number only — never the bytes, never the name. Any future trace point that
 * needs more must add a method here, and reviewing that method is the gate.
 *
 * This exists because the capture→Crop handoff spans three threads and two surfaces, so a stall can
 * only be localised with an ordered, timestamped trail: shutter → submit → callback → ticket verdict
 * → publication → surface change → mount, plus disposal and release.
 */
object MainScanTrace {

    private const val TAG = "MainScanCapture"

    private fun log(message: String) {
        if (!BuildConfig.DEBUG) return
        Log.d(TAG, message)
    }

    // --- shutter ------------------------------------------------------------------------------

    /** The tap passed the local gate and the reducer issued a ticket. */
    fun shutterAccepted(sessionId: Long, generation: Long) =
        log("MAIN_SCAN_SHUTTER accepted session=$sessionId generation=$generation")

    /** The tap was refused before any camera work. [reason] is a fixed literal from the call site. */
    fun shutterRejected(reason: String) = log("MAIN_SCAN_SHUTTER rejected reason=$reason")

    // --- camera submission ----------------------------------------------------------------------

    fun takePictureInvoked(sessionId: Long, generation: Long) =
        log("MAIN_SCAN_TAKEPICTURE invoked session=$sessionId generation=$generation")

    fun takePictureSubmitted(submitted: Boolean) =
        log("MAIN_SCAN_TAKEPICTURE submitted=$submitted")

    /** CameraX entered onImageSaved. [elapsedMs] is measured from the takePicture call. */
    fun imageSavedEntered(elapsedMs: Long) =
        log("MAIN_SCAN_CALLBACK onImageSaved=true elapsedMs=$elapsedMs")

    /** CameraX entered onError. [reason] is a fixed literal; no exception message is included. */
    fun errorEntered(elapsedMs: Long, reason: String) =
        log("MAIN_SCAN_CALLBACK onError=true elapsedMs=$elapsedMs reason=$reason")

    /**
     * No callback arrived within the watchdog bound. This is the line that distinguishes a genuine
     * CameraX stall from every other failure mode.
     */
    fun callbackTimedOut(elapsedMs: Long, sessionId: Long, generation: Long) =
        log("MAIN_SCAN_CALLBACK timedOut=true elapsedMs=$elapsedMs session=$sessionId generation=$generation")

    /** Output validation — existence and byte LENGTH only. Never the name or path. */
    fun outputValidated(exists: Boolean, lengthBytes: Long) =
        log("MAIN_SCAN_OUTPUT exists=$exists lengthBytes=$lengthBytes")

    // --- ticket verdict and publication ---------------------------------------------------------

    fun ticketAccepted(sessionId: Long, generation: Long) =
        log("MAIN_SCAN_TICKET accepted session=$sessionId generation=$generation")

    fun ticketRejected(
        ticketSessionId: Long,
        ticketGeneration: Long,
        liveSessionId: Long,
        liveGeneration: Long
    ) = log(
        "MAIN_SCAN_TICKET rejected ticketSession=$ticketSessionId ticketGeneration=$ticketGeneration " +
            "liveSession=$liveSessionId liveGeneration=$liveGeneration"
    )

    fun pendingPagePublished(sessionId: Long, generation: Long) =
        log("MAIN_SCAN_PENDING_PAGE published=true session=$sessionId generation=$generation")

    fun staleOutputDeleted(appOwned: Boolean) =
        log("MAIN_SCAN_OUTPUT staleDeleted=true appOwned=$appOwned")

    // --- surfaces -------------------------------------------------------------------------------

    /** The resolved surface immediately before and after a publication attempt. */
    fun surfaceTransition(before: String, after: String) =
        log("MAIN_SCAN_SURFACE before=$before after=$after")

    fun cropMounted(hasPageUri: Boolean) =
        log("MAIN_SCAN_CROP mounted=true hasPageUri=$hasPageUri")

    fun cropImageState(state: String) = log("MAIN_SCAN_CROP imageState=$state")

    // --- lifecycle ------------------------------------------------------------------------------

    fun captureScreenDisposed(controllerId: String) =
        log("MAIN_SCAN_SCREEN disposed=true controller=$controllerId")

    fun controllerReleased(controllerId: String, alreadyReleased: Boolean) =
        log("MAIN_SCAN_CONTROLLER released=true controller=$controllerId alreadyReleased=$alreadyReleased")
}
