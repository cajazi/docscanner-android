package com.dev.docscannerpdf.domain.idscan

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A single frame the Compose layer can draw — either a preview bitmap (instant feedback, never
 * persisted) or a final bitmap (the authoritative, fully-baked output). The frame carries
 * the operation that produced it, the [previewGeneration] / [finalRenderGeneration] token
 * that authored it, and the [isFinal] flag distinguishing the two.
 *
 * Generation discipline: the viewmodel calls [begin] to obtain a new token, then either
 * [publishPreview] or [publishFinal] exactly once. The bus gates publication against
 * [session]'s current generation — a stale completion (because the user tapped again) is
 * silently dropped and its bitmap ownership is released via [releasedToPool] for reuse.
 */
data class PassportPreviewFrame(
    val bitmap: Bitmap,
    val operation: PassportPreviewOperation,
    val generation: Int,
    val isFinal: Boolean
)

/** The four operations the bus recognises — also the tag on each timing log. */
enum class PassportPreviewOperation { CROP, ROTATE, FILTER, WATERMARK }

/**
 * Single in-process bus the Compose layer collects to render the latest preview. Holds at
 * most one frame at a time; the screen's `key(bitmap)` ensures Compose releases the old
 * bitmap reference before the next one is drawn (so the brief's "do not recycle a Bitmap
 * while Compose may still display it" rule is honored by Compose itself, not by this class).
 */
class PassportPreviewBus {

    private val _frame = MutableStateFlow<PassportPreviewFrame?>(null)
    val frame: StateFlow<PassportPreviewFrame?> = _frame.asStateFlow()

    /**
     * The current generation token to publish against. Mirrors [PassportReviewSession] —
     * the session is the source of truth for generation, the bus is just the publisher.
     */
    fun isPreviewCurrent(session: PassportReviewSession, generation: Int): Boolean =
        session.isPreviewCurrent(generation)

    fun isFinalCurrent(session: PassportReviewSession, generation: Int): Boolean =
        session.isFinalCurrent(generation)

    /** Publish a preview frame; silently dropped if the session generation has moved on. */
    fun publishPreview(
        session: PassportReviewSession,
        generation: Int,
        operation: PassportPreviewOperation,
        bitmap: Bitmap
    ) {
        if (!session.isPreviewCurrent(generation)) return
        _frame.value = PassportPreviewFrame(
            bitmap = bitmap,
            operation = operation,
            generation = generation,
            isFinal = false
        )
    }

    /** Publish a final frame; silently dropped if the session generation has moved on. */
    fun publishFinal(
        session: PassportReviewSession,
        generation: Int,
        operation: PassportPreviewOperation,
        bitmap: Bitmap
    ) {
        if (!session.isFinalCurrent(generation)) return
        _frame.value = PassportPreviewFrame(
            bitmap = bitmap,
            operation = operation,
            generation = generation,
            isFinal = true
        )
    }

    /** Clears the bus — e.g. when the review ends. The screen stops drawing immediately. */
    fun clear() {
        _frame.value = null
    }
}
