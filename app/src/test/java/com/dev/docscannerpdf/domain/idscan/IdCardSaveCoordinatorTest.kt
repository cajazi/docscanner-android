package com.dev.docscannerpdf.domain.idscan

import com.dev.docscannerpdf.domain.filter.DocumentFilter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdCardSaveCoordinatorTest {

    private fun frontOnlyReview() = IdCardReviewState(
        frontBaseImageUri = "file://base/front.jpg",
        title = "ID Card Test"
    )

    private fun frontAndBackReview() = frontOnlyReview().copy(
        backBaseImageUri = "file://base/back.jpg"
    )

    /** Coordinator whose stages all succeed, recording ordered events for assertions. */
    private class Harness(
        frontResult: String? = "file://final/front.jpg",
        backResult: String? = "file://final/back.jpg",
        combinedResult: String? = "file://final/combined.jpg",
        persistResult: IdCardSaveCoordinator.PersistResult = IdCardSaveCoordinator.PersistResult.Success
    ) {
        val events = mutableListOf<String>()
        var persistCalls = 0
        var persistedTitle: String? = null
        var persistedPageCount: Int? = null
        var persistedCombinedUri: String? = null

        val coordinator = IdCardSaveCoordinator(
            produceSideImage = { side ->
                events.add("side:${side.side}")
                if (side.side == IdCardReviewSide.FRONT) frontResult else backResult
            },
            renderCombinedPage = { _, _ ->
                events.add("combined")
                combinedResult
            },
            persistDocument = { title, pageCount, combinedImageUri ->
                events.add("persist")
                persistCalls++
                persistedTitle = title
                persistedPageCount = pageCount
                persistedCombinedUri = combinedImageUri
                persistResult
            }
        )
    }

    @Test
    fun firstConfirmAcquiresGuardAndConcurrentConfirmIsRejected() = runBlocking {
        val gate = CompletableDeferred<String>()
        var persistCalls = 0
        val coordinator = IdCardSaveCoordinator(
            produceSideImage = { gate.await() },
            renderCombinedPage = { _, _ -> "file://final/combined.jpg" },
            persistDocument = { _, _, _ ->
                persistCalls++
                IdCardSaveCoordinator.PersistResult.Success
            }
        )

        assertFalse(coordinator.isSaving)
        val first = launch { coordinator.confirm(frontOnlyReview()) {} }
        yield()
        assertTrue("first confirm must acquire the guard", coordinator.isSaving)

        val second = coordinator.confirm(frontOnlyReview()) {}
        assertEquals(IdCardSaveCoordinator.Outcome.AlreadySaving, second)

        gate.complete("file://final/front.jpg")
        first.join()
        assertFalse("guard released after completion", coordinator.isSaving)
        assertEquals("only the first confirm persists", 1, persistCalls)
    }

    @Test
    fun frontRenderFailureAbortsWithNoPersistenceRequest() = runBlocking {
        val harness = Harness(frontResult = null)

        val outcome = harness.coordinator.confirm(frontAndBackReview()) {}

        val aborted = outcome as IdCardSaveCoordinator.Outcome.Aborted
        assertEquals(frontAndBackReview(), aborted.review)
        assertEquals(0, harness.persistCalls)
        assertFalse(harness.coordinator.isSaving)
    }

    @Test
    fun backRenderFailureAbortsWithNoPersistenceRequest() = runBlocking {
        val harness = Harness(backResult = null)

        val outcome = harness.coordinator.confirm(frontAndBackReview()) {}

        assertTrue(outcome is IdCardSaveCoordinator.Outcome.Aborted)
        assertEquals(0, harness.persistCalls)
    }

    @Test
    fun rotationBakeFailureAbortsWithNoPersistenceRequest() = runBlocking {
        // In production, a failed non-zero rotation bake surfaces as produceSideImage == null;
        // the review carries the rotation so the abort path is exercised with it set.
        val review = frontOnlyReview().copy(frontRotationDegrees = 90)
        val harness = Harness(frontResult = null)

        val outcome = harness.coordinator.confirm(review) {}

        val aborted = outcome as IdCardSaveCoordinator.Outcome.Aborted
        assertEquals(review, aborted.review)
        assertEquals(90, aborted.review.frontRotationDegrees)
        assertEquals(0, harness.persistCalls)
    }

    @Test
    fun combinedPageFailureAbortsWithNoPersistenceRequest() = runBlocking {
        val harness = Harness(combinedResult = null)

        val outcome = harness.coordinator.confirm(frontAndBackReview()) {}

        assertTrue(outcome is IdCardSaveCoordinator.Outcome.Aborted)
        assertEquals(0, harness.persistCalls)
    }

    @Test
    fun persistenceFailureRestoresRetryableReview() = runBlocking {
        val harness = Harness(
            persistResult = IdCardSaveCoordinator.PersistResult.Failure("database unavailable")
        )
        val review = frontAndBackReview()

        val outcome = harness.coordinator.confirm(review) {}

        val aborted = outcome as IdCardSaveCoordinator.Outcome.Aborted
        assertEquals("the exact review state comes back for retry", review, aborted.review)
        assertEquals("database unavailable", aborted.message)
        assertEquals(1, harness.persistCalls)
        assertFalse(harness.coordinator.isSaving)
    }

    @Test
    fun persistenceSuccessProducesOneSavedDocumentRequest() = runBlocking {
        val harness = Harness()

        val outcome = harness.coordinator.confirm(frontAndBackReview()) {}

        assertTrue(outcome is IdCardSaveCoordinator.Outcome.Completed)
        assertEquals(1, harness.persistCalls)
        assertEquals("ID Card Test", harness.persistedTitle)
        assertEquals(1, harness.persistedPageCount)
        assertEquals("file://final/combined.jpg", harness.persistedCombinedUri)
    }

    @Test
    fun completionEventOccursOnlyAfterPersistenceSuccess() = runBlocking {
        val harness = Harness()

        harness.coordinator.confirm(frontAndBackReview()) { harness.events.add("completed") }

        assertEquals(
            listOf("side:FRONT", "side:BACK", "combined", "persist", "completed"),
            harness.events
        )
    }

    @Test
    fun completionIsNeverInvokedWhenPersistenceFails() = runBlocking {
        val harness = Harness(
            persistResult = IdCardSaveCoordinator.PersistResult.Failure("nope")
        )
        var completedCalls = 0

        harness.coordinator.confirm(frontOnlyReview()) { completedCalls++ }

        assertEquals(0, completedCalls)
    }

    @Test
    fun callbackFailureAfterPersistenceSuccessIsNotRetryable() = runBlocking {
        val harness = Harness()

        val outcome = harness.coordinator.confirm(frontAndBackReview()) {
            throw IllegalStateException("preview UI failed")
        }

        val completed = outcome as IdCardSaveCoordinator.Outcome.CompletedWithCallbackFailure
        assertEquals("preview UI failed", completed.failure.message)
        assertEquals("still exactly one insertion", 1, harness.persistCalls)
        assertFalse(harness.coordinator.isSaving)
    }

    @Test
    fun frontOnlySaveIsValidWithPageCountOneAndNoBackSide() = runBlocking {
        val harness = Harness()

        val outcome = harness.coordinator.confirm(frontOnlyReview()) {}

        val completed = outcome as IdCardSaveCoordinator.Outcome.Completed
        assertEquals(1, completed.result.pageCount)
        assertNull(completed.result.backImageUri)
        assertEquals("file://final/combined.jpg", completed.result.combinedImageUri)
        assertEquals(listOf("side:FRONT", "combined", "persist"), harness.events)
    }

    @Test
    fun frontAndBackSaveCarriesBothFinalSideUris() = runBlocking {
        val harness = Harness()
        val review = frontAndBackReview().copy(
            frontFilter = DocumentFilter.BW,
            backFilter = DocumentFilter.ORIGINAL
        )

        val outcome = harness.coordinator.confirm(review) {}

        val completed = outcome as IdCardSaveCoordinator.Outcome.Completed
        assertEquals("file://final/front.jpg", completed.result.frontImageUri)
        assertEquals("file://final/back.jpg", completed.result.backImageUri)
        assertEquals(1, completed.result.pageCount)
    }

    @Test
    fun invalidReviewProducesNoWorkAtAll() = runBlocking {
        val harness = Harness()

        val outcome = harness.coordinator.confirm(
            IdCardReviewState(frontBaseImageUri = "   ")
        ) {}

        assertTrue(outcome is IdCardSaveCoordinator.Outcome.Invalid)
        assertTrue(harness.events.isEmpty())
        assertFalse(harness.coordinator.isSaving)
    }
}
