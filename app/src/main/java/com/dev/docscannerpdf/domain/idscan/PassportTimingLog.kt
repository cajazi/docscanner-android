package com.dev.docscannerpdf.domain.idscan

import android.util.Log
import com.dev.docscannerpdf.BuildConfig

/**
 * Debug-only, content-free timing log for passport preview/final operations. Every method
 * short-circuits in release builds ([BuildConfig.DEBUG] is false) so production binaries
 * carry no log calls at all.
 *
 * The brief's content rules are enforced here by construction: only the four named fields
 * are accepted (`operation`, `generation`, `elapsedMs`, `width`/`height`). There is NO
 * overload that accepts a path, URI, file name, watermark string, image data, or device
 * identifier. Any future logging that needs more data must add a new method here, and the
 * review of that method must check the content rules.
 */
object PassportTimingLog {

    private const val TAG = "IdCardCapture"

    /**
     * Emitted at the moment an operation starts, before any work has happened. Carries the
     * operation and the generation token only — no preview/final timing yet.
     */
    fun started(operation: PassportPreviewOperation, generation: Int) {
        if (!BuildConfig.DEBUG) return
        Log.d(TAG, "PASSPORT_OPERATION_STARTED operation=${operation.name} generation=$generation")
    }

    /**
     * Emitted when a preview bitmap is ready (before the authoritative final). The width and
     * height are the preview bitmap's dimensions — they are NOT the source's, NOT the file's,
     * and not derived from any path or identifier. The watermark text is never accepted here.
     */
    fun previewReady(
        operation: PassportPreviewOperation,
        generation: Int,
        elapsedMs: Long,
        width: Int,
        height: Int
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            TAG,
            "PASSPORT_PREVIEW_READY operation=${operation.name} generation=$generation " +
                "elapsedMs=$elapsedMs width=$width height=$height"
        )
    }

    /**
     * Emitted when the authoritative full-resolution render settles and the preview is
     * replaced with its pixels. Width/height are the output bitmap's dimensions only.
     */
    fun finalReady(
        operation: PassportPreviewOperation,
        generation: Int,
        elapsedMs: Long,
        width: Int,
        height: Int
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            TAG,
            "PASSPORT_FINAL_READY operation=${operation.name} generation=$generation " +
                "elapsedMs=$elapsedMs width=$width height=$height"
        )
    }

    /**
     * Emitted when an operation fails. The [stage] distinguishes preview (in-memory pipeline)
     * from final (authoritative JPEG). No content, paths, URIs, text, or identifiers are
     * ever included.
     */
    fun failed(
        operation: PassportPreviewOperation,
        generation: Int,
        stage: PassportFailureStage,
        elapsedMs: Long
    ) {
        if (!BuildConfig.DEBUG) return
        Log.d(
            TAG,
            "PASSPORT_OPERATION_FAILED operation=${operation.name} generation=$generation " +
                "stage=${stage.name.lowercase()} elapsedMs=$elapsedMs"
        )
    }
}

/** Which stage of a passport op failed — used only by [PassportTimingLog]. */
enum class PassportFailureStage { PREVIEW, FINAL }
