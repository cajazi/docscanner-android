package com.dev.docscannerpdf.presentation

import com.dev.docscannerpdf.data.local.DocumentEntity
import kotlinx.coroutines.CancellationException

/**
 * Separates PERSISTENCE failure from COMPLETION-CALLBACK failure when saving a generated
 * document. Running the repository insert and the caller's success callback inside one
 * try/catch had a dangerous failure mode: the insert succeeded, the callback threw, the catch
 * reported a save "failure", and the caller restored a retryable state — retrying would then
 * insert a DUPLICATE of the already-persisted document.
 *
 * Contract:
 * - [persist] failing → [onPersistFailed] is invoked exactly once, [onPersisted] never runs,
 *   and the outcome is [Outcome.Failed]. No document exists.
 * - [persist] succeeding → [onPersisted] is invoked exactly once. If it THROWS, the outcome is
 *   [Outcome.SavedButCallbackFailed] — [onPersistFailed] is still never invoked, because the
 *   document genuinely exists and must not be presented as retryable.
 * - [CancellationException] is never swallowed — it always propagates.
 *
 * Pure JVM (repository access is injected as [persist]) so this contract is unit-testable with
 * a fake persist lambda, without constructing the ViewModel.
 */
object GeneratedDocumentSaver {

    const val DEFAULT_ERROR_MESSAGE = "Unable to save generated PDF."

    sealed interface Outcome {
        /** Persisted and the completion callback ran cleanly. */
        data class Saved(val document: DocumentEntity) : Outcome

        /**
         * Persisted — the document exists and must be kept — but the completion callback threw.
         * Callers log this; they must NOT surface it as a save failure or offer a retry.
         */
        data class SavedButCallbackFailed(
            val document: DocumentEntity,
            val callbackFailure: Throwable
        ) : Outcome

        /** Persistence itself failed; nothing was inserted. [message] was passed to onPersistFailed. */
        data class Failed(val message: String) : Outcome
    }

    suspend fun save(
        document: DocumentEntity,
        persist: suspend (DocumentEntity) -> Long,
        onPersistFailed: (String) -> Unit,
        onPersisted: (DocumentEntity) -> Unit
    ): Outcome {
        val saved = try {
            document.copy(id = persist(document))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            val message = throwable.message ?: DEFAULT_ERROR_MESSAGE
            onPersistFailed(message)
            return Outcome.Failed(message)
        }
        return try {
            onPersisted(saved)
            Outcome.Saved(saved)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Outcome.SavedButCallbackFailed(saved, throwable)
        }
    }
}
