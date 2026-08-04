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

    // --- live detection -------------------------------------------------------------------------

    /**
     * One line per evaluated analysis frame. Every field is a number, a boolean or a fixed bucket
     * label — there is deliberately no parameter that could carry a corner coordinate, a pixel
     * sample, a path or a file name, so the live pipeline cannot leak image content through tracing.
     */
    fun guideEvaluated(
        rotationDegrees: Int,
        frameWidth: Int,
        frameHeight: Int,
        guideVisible: Boolean,
        confidenceBucket: String,
        stable: Boolean,
        mappingGeneration: Long
    ) = log(
        "MAIN_SCAN_GUIDE rotation=$rotationDegrees frame=${frameWidth}x$frameHeight " +
            "visible=$guideVisible confidence=$confidenceBucket stable=$stable " +
            "generation=$mappingGeneration"
    )

    /**
     * Buckets a confidence into a coarse label. Deliberately not the raw value: a per-frame float
     * stream is both noisy and a finer-grained signal about scene content than tracing needs.
     */
    fun confidenceBucket(confidence: Float): String = when {
        confidence <= 0f -> "none"
        confidence < 0.4f -> "low"
        confidence < 0.6f -> "medium"
        confidence < 0.8f -> "high"
        else -> "very_high"
    }

    /** Emitted once per accepted shutter: whether the crop opens on detected corners or full frame. */
    fun cropSeedFrozen(sessionId: Long, generation: Long, seeded: Boolean) =
        log("MAIN_SCAN_CROP_SEED session=$sessionId generation=$generation seeded=$seeded")

    // --- crop / processing pipeline ---------------------------------------------------------------

    /**
     * The crop stage is ready. [polygonSource] is an enum name, and the dimensions are the working
     * image's — no path, no file name, no pixel content can pass through here.
     */
    fun cropPrepared(polygonSource: String, imageWidth: Int, imageHeight: Int) =
        log("MAIN_SCAN_CROP_READY polygonSource=$polygonSource image=${imageWidth}x$imageHeight")

    /** A processing stage failed. [stage] is a fixed literal chosen at the call site. */
    fun processingFailed(stage: String) = log("MAIN_SCAN_PROCESSING failed=true stage=$stage")

    /** The enhancement review was reached, and whether enhancement itself succeeded. */
    fun reviewReached(enhanced: Boolean) =
        log("MAIN_SCAN_REVIEW reached=true enhanced=$enhanced")

    fun cropImageState(state: String) = log("MAIN_SCAN_CROP imageState=$state")

    // --- lifecycle ------------------------------------------------------------------------------

    fun captureScreenDisposed(controllerId: String) =
        log("MAIN_SCAN_SCREEN disposed=true controller=$controllerId")

    fun controllerReleased(controllerId: String, alreadyReleased: Boolean) =
        log("MAIN_SCAN_CONTROLLER released=true controller=$controllerId alreadyReleased=$alreadyReleased")
}
