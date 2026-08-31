package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.data.local.DocumentEntity
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Back and every discard entry point are inert while Confirm owns the save transaction. */
internal fun MainScanStage.blocksMainScanExit(): Boolean =
    this == MainScanStage.Confirming || this == MainScanStage.Persisting

/**
 * Pure/injected Confirm transaction: durable copy -> exact Room insert -> retained completion.
 *
 * No Android owner is retained here. File work and repository persistence are injected, making the
 * single-flight and failure ordering executable in local JVM tests. A successful insertion is the
 * ownership boundary: before it, a promoted copy is removed on every handled failure; after it, the
 * copy is never deleted and the exact repository-returned id is carried by the completed document.
 */
class MainScanSaveCoordinator(
    private val promoteArtifact: suspend (MainScanAuthoritativeArtifact) -> MainScanSavedArtifact,
    private val deletePromotedArtifact: suspend (MainScanSavedArtifact) -> Unit,
    private val persistDocument: suspend (DocumentEntity) -> Long,
    private val saveTimeoutMillis: Long = DEFAULT_SAVE_TIMEOUT_MILLIS
) {

    init {
        require(saveTimeoutMillis > 0L) { "The Main Scanner save timeout must be positive." }
    }

    sealed interface Outcome {
        /** Another Confirm already owns the transaction. */
        object AlreadySaving : Outcome

        /** Nothing was inserted and the original authoritative artifact remains retryable. */
        data class Aborted(
            val message: String,
            /** Preserved for truthful diagnostics; callers still present only the safe message. */
            val failure: Throwable? = null
        ) : Outcome

        /** The exact inserted document was retained successfully. */
        data class Completed(val document: DocumentEntity) : Outcome

        /**
         * Room insertion succeeded, but the completion callback failed. This is completed, not
         * retryable: the durable file and exact inserted identity must survive.
         */
        data class CompletedWithCallbackFailure(
            val document: DocumentEntity,
            val failure: Throwable
        ) : Outcome
    }

    private val saving = AtomicBoolean(false)

    val isSaving: Boolean get() = saving.get()

    /**
     * Runs one Confirm. The atomic admission happens before the first suspension point.
     *
     * [onPersisting] advances Confirming -> Persisting after the durable copy exists and before the
     * insert. [onCompleted] publishes retained completion immediately after insertion, with the
     * exact returned id already copied onto the document.
     */
    suspend fun confirm(
        artifact: MainScanAuthoritativeArtifact,
        title: String,
        timestamp: Long,
        onPersisting: suspend () -> Unit,
        onCompleted: (DocumentEntity) -> Unit
    ): Outcome {
        if (!saving.compareAndSet(false, true)) return Outcome.AlreadySaving

        var promoted: MainScanSavedArtifact? = null
        var insertedDocument: DocumentEntity? = null
        var promotionFinished = false
        var completionPublished = false

        fun completionOutcome(saved: DocumentEntity): Outcome = try {
            onCompleted(saved)
            completionPublished = true
            Outcome.Completed(saved)
        } catch (throwable: Throwable) {
            Outcome.CompletedWithCallbackFailure(saved, throwable)
        }

        fun retainedOutcome(saved: DocumentEntity): Outcome =
            if (completionPublished) Outcome.Completed(saved) else completionOutcome(saved)

        try {
            return withTimeout(saveTimeoutMillis) {
                promoted = promoteArtifact(artifact)
                promotionFinished = true
                onPersisting()

                // Cancellation observed here is still pre-insert and therefore safely retryable.
                currentCoroutineContext().ensureActive()
                val draft = DocumentEntity(
                    title = title,
                    timestamp = timestamp,
                    pageCount = 1,
                    localPdfUri = requireNotNull(promoted).uri
                )

                // This is the ownership boundary. Room may commit immediately before resuming its
                // caller, so insertion, exact-id materialisation and publication to the catch paths
                // must be one cancellation-atomic operation. The retained value intentionally has
                // the draft's searchableText; DocumentRepository derives the stored searchable row,
                // and the documents Flow replaces this fast-path viewer value when Room emits it.
                val saved = withContext(NonCancellable) {
                    val insertedId = persistDocument(draft)
                    val inserted = draft.copy(id = insertedId)
                    insertedDocument = inserted
                    inserted
                }

                completionOutcome(saved)
            }
        } catch (timeout: TimeoutCancellationException) {
            insertedDocument?.let { saved -> return retainedOutcome(saved) }
            cleanup(promoted, timeout)
            return Outcome.Aborted(SAVE_TIMEOUT_MESSAGE, timeout)
        } catch (cancellation: CancellationException) {
            insertedDocument?.let { saved -> return retainedOutcome(saved) }
            cleanup(promoted, cancellation)
            throw cancellation
        } catch (throwable: Throwable) {
            insertedDocument?.let { saved ->
                val retained = retainedOutcome(saved)
                return if (retained is Outcome.CompletedWithCallbackFailure) retained else {
                    Outcome.CompletedWithCallbackFailure(saved, throwable)
                }
            }
            cleanup(promoted, throwable)
            val message = if (promotionFinished) {
                "Couldn't save the document. Please try again."
            } else {
                "Couldn't create the durable full-quality page. Please try again."
            }
            return Outcome.Aborted(message, throwable)
        } finally {
            saving.set(false)
        }
    }

    /** Failure cleanup must still finish when cancellation is what aborted the pre-insert work. */
    private suspend fun cleanup(promoted: MainScanSavedArtifact?, failure: Throwable) {
        if (promoted == null) return
        withContext(NonCancellable) {
            runCatching { deletePromotedArtifact(promoted) }
                .exceptionOrNull()
                ?.let(failure::addSuppressed)
        }
    }

    private companion object {
        const val DEFAULT_SAVE_TIMEOUT_MILLIS = 30_000L
        const val SAVE_TIMEOUT_MESSAGE =
            "Saving took too long before the document was committed. Please try again."
    }
}
