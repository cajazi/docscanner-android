package com.dev.docscannerpdf.domain.idscan

import kotlinx.coroutines.CancellationException

/**
 * The green-check save state machine, extracted from the activity so its guarantees are unit
 * testable: single-flight guarding, strict abort-on-failure ordering (side images → combined
 * page → persistence → completion), review restoration ONLY before persistence succeeds, and
 * the persisted-but-completion-failed case never being presented as retryable.
 *
 * All image work and persistence are injected as suspend functions working on URI STRINGS —
 * no Android bitmap processing lives here. The activity supplies:
 * - [produceSideImage]: one side's FINAL image (selected filter rendered from the base, then
 *   rotation baked); null aborts the save (a non-Original filter must never silently fall back
 *   to the unfiltered base, and a non-zero rotation must never be silently dropped);
 * - [renderCombinedPage]: the equal-size combined front/back page; null aborts;
 * - [persistDocument]: exactly one repository insertion attempt for the combined page.
 *
 * [CancellationException] always propagates ([isSaving] is still released by the finally).
 */
class IdCardSaveCoordinator(
    private val produceSideImage: suspend (IdCardSideSavePlan) -> String?,
    private val renderCombinedPage: suspend (frontImageUri: String, backImageUri: String?) -> String?,
    private val persistDocument: suspend (title: String, pageCount: Int, combinedImageUri: String) -> PersistResult
) {

    /** What the injected persistence step reports back. */
    sealed interface PersistResult {
        object Success : PersistResult
        data class Failure(val message: String) : PersistResult
    }

    /** Everything the completed save produced, for the Document Ready preview and PDF export. */
    data class SaveResult(
        val title: String,
        val pageCount: Int,
        val frontImageUri: String,
        val backImageUri: String?,
        val combinedImageUri: String
    )

    sealed interface Outcome {
        /** A save is already in flight; this confirm was a guarded no-op. */
        object AlreadySaving : Outcome

        /** The review can't be saved at all (no front); nothing started. */
        data class Invalid(val reason: String) : Outcome

        /**
         * The save failed BEFORE or AT persistence — nothing was inserted. [review] is the
         * exact state to restore so the user can retry; [message] is user-presentable.
         */
        data class Aborted(val review: IdCardReviewState, val message: String) : Outcome

        /** Persisted and the completion callback ran cleanly. */
        data class Completed(val result: SaveResult) : Outcome

        /**
         * Persisted — the document exists — but the completion (navigation/preview) callback
         * threw. Callers log [failure]; they must NOT restore the review or offer a retry,
         * which would duplicate the already-saved document.
         */
        data class CompletedWithCallbackFailure(
            val result: SaveResult,
            val failure: Throwable
        ) : Outcome
    }

    /** True while a confirm is in flight; concurrent confirms return [Outcome.AlreadySaving]. */
    var isSaving: Boolean = false
        private set

    suspend fun confirm(
        review: IdCardReviewState,
        onCompleted: (SaveResult) -> Unit
    ): Outcome {
        if (isSaving) return Outcome.AlreadySaving
        val plan = when (val planned = IdCardSavePlanner.plan(review)) {
            is IdCardSavePlan.Invalid -> return Outcome.Invalid(planned.reason)
            is IdCardSavePlan.Ready -> planned
        }
        isSaving = true
        try {
            val frontImageUri = produceSideImage(plan.front)
                ?: return Outcome.Aborted(review, "Unable to prepare the front image. Try again or change its filter.")
            var backImageUri: String? = null
            val backPlan = plan.back
            if (backPlan != null) {
                backImageUri = produceSideImage(backPlan)
                    ?: return Outcome.Aborted(review, "Unable to prepare the back image. Try again or change its filter.")
            }
            val combinedImageUri = renderCombinedPage(frontImageUri, backImageUri)
                ?: return Outcome.Aborted(review, "Unable to create the ID card page. Please try again.")

            when (val persisted = persistDocument(plan.title, plan.pageCount, combinedImageUri)) {
                is PersistResult.Failure -> return Outcome.Aborted(review, persisted.message)
                is PersistResult.Success -> Unit
            }

            val result = SaveResult(
                title = plan.title,
                pageCount = plan.pageCount,
                frontImageUri = frontImageUri,
                backImageUri = backImageUri,
                combinedImageUri = combinedImageUri
            )
            return try {
                onCompleted(result)
                Outcome.Completed(result)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Outcome.CompletedWithCallbackFailure(result, throwable)
            }
        } finally {
            isSaving = false
        }
    }
}
