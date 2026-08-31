package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.data.local.DocumentEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private fun testAuthoritative() = MainScanAuthoritativeArtifact(
    croppedUri = "file:///files/main_scan_cropped/cropped.jpg",
    enhancedUri = "file:///files/main_scan_enhanced/enhanced.jpg",
    pixelWidth = 2400,
    pixelHeight = 3200,
    sourceSampleSize = MainScanAuthoritativeRender.AUTHORITATIVE_SAMPLE_SIZE,
    rotationQuarterTurns = 0
)

class MainScanSaveCoordinatorTest {

    private fun authoritative() = MainScanAuthoritativeArtifact(
        croppedUri = "file:///files/main_scan_cropped/cropped.jpg",
        enhancedUri = "file:///files/main_scan_enhanced/enhanced.jpg",
        pixelWidth = 2400,
        pixelHeight = 3200,
        sourceSampleSize = MainScanAuthoritativeRender.AUTHORITATIVE_SAMPLE_SIZE,
        rotationQuarterTurns = 0
    )

    private class Harness(
        private val insertedId: Long = 73L,
        private val promoteFailure: Throwable? = null,
        private val persistFailure: Throwable? = null
    ) {
        val durable = MainScanSavedArtifact("file:///files/main_scan_saved/saved.jpg")
        val events = mutableListOf<String>()
        val drafts = mutableListOf<DocumentEntity>()
        val promotedArtifacts = mutableListOf<MainScanAuthoritativeArtifact>()
        val deletedArtifacts = mutableListOf<MainScanSavedArtifact>()
        var promoteCalls = 0
        var deleteCalls = 0
        var completionCalls = 0

        val coordinator = MainScanSaveCoordinator(
            promoteArtifact = { artifact ->
                promoteCalls++
                promotedArtifacts += artifact
                events += "promote"
                promoteFailure?.let { throw it }
                durable
            },
            deletePromotedArtifact = { artifact ->
                deleteCalls++
                deletedArtifacts += artifact
                events += "delete"
            },
            persistDocument = { draft ->
                drafts += draft
                events += "persist"
                persistFailure?.let { throw it }
                insertedId
            }
        )

        suspend fun confirm(
            artifact: MainScanAuthoritativeArtifact = testAuthoritative(),
            onPersisting: suspend () -> Unit = { events += "persisting" },
            callback: (DocumentEntity) -> Unit = { completionCalls++ }
        ) = coordinator.confirm(
            artifact = artifact,
            title = "Scan 29-08-2026 10.30",
            timestamp = 1_777_000_000_000L,
            onPersisting = onPersisting,
            onCompleted = callback
        )
    }

    @Test
    fun successfulConfirmPersistsExactlyOneDurableImageBackedRowAndExactReturnedId() = runBlocking {
        val harness = Harness(insertedId = 912L)

        val outcome = harness.confirm()

        val completed = outcome as MainScanSaveCoordinator.Outcome.Completed
        assertEquals(1, harness.promoteCalls)
        assertEquals(1, harness.drafts.size)
        assertEquals(1, harness.completionCalls)
        assertEquals(912L, completed.document.id)
        assertEquals(912L, (outcome as MainScanSaveCoordinator.Outcome.Completed).document.id)
        assertEquals("Scan 29-08-2026 10.30", completed.document.title)
        assertEquals(1_777_000_000_000L, completed.document.timestamp)
        assertEquals(1, completed.document.pageCount)
        assertEquals(harness.durable.uri, completed.document.localPdfUri)
        assertTrue(completed.document.localPdfUri.contains("/main_scan_saved/"))
        assertFalse(completed.document.localPdfUri.contains("/main_scan_enhanced/"))
        assertEquals(listOf("promote", "persisting", "persist"), harness.events)
        assertEquals(0, harness.deleteCalls)
    }

    @Test
    fun manyRapidConfirmsProduceOnePromotionAndOneInsertion() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        var promotions = 0
        var inserts = 0
        val coordinator = MainScanSaveCoordinator(
            promoteArtifact = {
                promotions++
                gate.await()
                MainScanSavedArtifact("file:///files/main_scan_saved/one.jpg")
            },
            deletePromotedArtifact = {},
            persistDocument = {
                inserts++
                51L
            }
        )

        val first = launch {
            coordinator.confirm(testAuthoritative(), "Scan", 1L, {}, {})
        }
        yield()
        assertTrue(coordinator.isSaving)
        repeat(25) {
            assertEquals(
                MainScanSaveCoordinator.Outcome.AlreadySaving,
                coordinator.confirm(testAuthoritative(), "Scan", 1L, {}, {})
            )
        }
        gate.complete(Unit)
        first.join()

        assertEquals(1, promotions)
        assertEquals(1, inserts)
        assertFalse(coordinator.isSaving)
    }

    @Test
    fun durableCopyFailureInsertsNoRowAndLeavesOriginalArtifactAvailable() = runBlocking {
        val failure = IllegalStateException("copy failed")
        val harness = Harness(promoteFailure = failure)
        val source = authoritative()

        val outcome = harness.confirm(source)

        assertTrue(outcome is MainScanSaveCoordinator.Outcome.Aborted)
        assertTrue(harness.drafts.isEmpty())
        assertEquals(0, harness.deleteCalls)
        assertSame(
            "the exact authoritative instance must be the one offered for promotion",
            source,
            harness.promotedArtifacts.single()
        )
        assertTrue("no promoted artifact existed for cleanup to target", harness.deletedArtifacts.isEmpty())
        assertEquals("file:///files/main_scan_enhanced/enhanced.jpg", source.enhancedUri)
    }

    @Test
    fun repositoryFailureDeletesAttemptedDurableCopyAndPublishesNoCompletion() = runBlocking {
        val failure = IllegalStateException("database failed")
        val harness = Harness(persistFailure = failure)

        val outcome = harness.confirm()

        val aborted = outcome as MainScanSaveCoordinator.Outcome.Aborted
        assertEquals(failure::class.java, aborted.failure?.javaClass)
        assertEquals(failure.message, aborted.failure?.message)
        assertTrue(
            "the database failure must remain directly or causally available for diagnostics",
            aborted.failure === failure || aborted.failure?.cause === failure
        )
        assertEquals(1, harness.drafts.size)
        assertEquals(1, harness.deleteCalls)
        assertEquals(0, harness.completionCalls)
        assertEquals(listOf("promote", "persisting", "persist", "delete"), harness.events)
    }

    @Test
    fun completionCallbackFailureAfterInsertNeverDeletesOrReinserts() = runBlocking {
        val harness = Harness(insertedId = 404L)

        val outcome = harness.confirm {
            harness.completionCalls++
            throw IllegalStateException("viewer failed")
        }

        val completed = outcome as MainScanSaveCoordinator.Outcome.CompletedWithCallbackFailure
        assertEquals(404L, completed.document.id)
        assertEquals("viewer failed", completed.failure.message)
        assertEquals(1, harness.drafts.size)
        assertEquals(1, harness.completionCalls)
        assertEquals(0, harness.deleteCalls)
    }

    @Test
    fun cancellationBeforeInsertPropagatesAndRemovesPromotedCopy() = runBlocking {
        var deletes = 0
        var inserts = 0
        val coordinator = MainScanSaveCoordinator(
            promoteArtifact = {
                MainScanSavedArtifact("file:///files/main_scan_saved/cancelled.jpg")
            },
            deletePromotedArtifact = { deletes++ },
            persistDocument = {
                inserts++
                1L
            }
        )

        var cancellationPropagated = false
        try {
            coordinator.confirm(
                testAuthoritative(),
                "Scan",
                1L,
                onPersisting = { throw CancellationException("cancelled before insert") },
                onCompleted = {}
            )
        } catch (_: CancellationException) {
            cancellationPropagated = true
        }

        assertTrue(cancellationPropagated)
        assertEquals(0, inserts)
        assertEquals(1, deletes)
        assertFalse(coordinator.isSaving)
    }

    @Test
    fun cancellationAfterLogicalCommitRetainsExactIdAndCannotBecomeRetryable() = runBlocking {
        val insertCommitted = CompletableDeferred<Unit>()
        val allowInsertToResume = CompletableDeferred<Unit>()
        var inserts = 0
        var deletes = 0
        var retained: DocumentEntity? = null
        var outcome: MainScanSaveCoordinator.Outcome? = null
        val coordinator = MainScanSaveCoordinator(
            promoteArtifact = {
                MainScanSavedArtifact("file:///files/main_scan_saved/committed.jpg")
            },
            deletePromotedArtifact = { deletes++ },
            persistDocument = {
                inserts++
                insertCommitted.complete(Unit)
                allowInsertToResume.await()
                9_901L
            }
        )

        val save = launch {
            outcome = coordinator.confirm(
                testAuthoritative(),
                "Scan",
                1L,
                onPersisting = {},
                onCompleted = { retained = it }
            )
        }
        insertCommitted.await()
        save.cancel(CancellationException("resumption pressure after commit"))
        allowInsertToResume.complete(Unit)
        save.join()

        val completed = outcome as MainScanSaveCoordinator.Outcome.Completed
        assertEquals(1, inserts)
        assertEquals(0, deletes)
        assertEquals(9_901L, completed.document.id)
        assertSame("the exact inserted entity must reach retained completion", completed.document, retained)
        assertFalse("a committed insert must never become retryable", outcome is MainScanSaveCoordinator.Outcome.Aborted)
        assertFalse(coordinator.isSaving)
    }

    @Test
    fun timeoutBeforeInsertionCreatesNoRowAndDeletesPromotedAttempt() = runBlocking {
        var inserts = 0
        var deletes = 0
        val coordinator = MainScanSaveCoordinator(
            promoteArtifact = {
                MainScanSavedArtifact("file:///files/main_scan_saved/timed-out.jpg")
            },
            deletePromotedArtifact = { deletes++ },
            persistDocument = {
                inserts++
                8L
            },
            saveTimeoutMillis = 50L
        )

        val outcome = coordinator.confirm(
            testAuthoritative(),
            "Scan",
            1L,
            onPersisting = { awaitCancellation() },
            onCompleted = {}
        )

        val aborted = outcome as MainScanSaveCoordinator.Outcome.Aborted
        assertEquals(0, inserts)
        assertEquals(1, deletes)
        assertTrue(aborted.failure is TimeoutCancellationException)
        assertEquals(
            "Saving took too long before the document was committed. Please try again.",
            aborted.message
        )
        assertFalse(coordinator.isSaving)
    }

    @Test
    fun timeoutAfterInsertionStartsStillCompletesWithoutDeletingOrRetrying() = runBlocking {
        var inserts = 0
        var deletes = 0
        var retained: DocumentEntity? = null
        val coordinator = MainScanSaveCoordinator(
            promoteArtifact = {
                MainScanSavedArtifact("file:///files/main_scan_saved/slow-commit.jpg")
            },
            deletePromotedArtifact = { deletes++ },
            persistDocument = {
                inserts++
                // Models Room having committed before delayed continuation resumption.
                delay(100L)
                7_707L
            },
            saveTimeoutMillis = 25L
        )

        val outcome = coordinator.confirm(
            testAuthoritative(),
            "Scan",
            1L,
            onPersisting = {},
            onCompleted = { retained = it }
        )

        assertFalse(outcome is MainScanSaveCoordinator.Outcome.Aborted)
        val completed = outcome as MainScanSaveCoordinator.Outcome.Completed
        assertEquals(1, inserts)
        assertEquals(0, deletes)
        assertEquals(7_707L, completed.document.id)
        assertSame(completed.document, retained)
    }

    @Test
    fun saveInFlightExitGuardCoversExactlyConfirmingAndPersisting() {
        for (stage in MainScanStage.entries) {
            assertEquals(
                stage == MainScanStage.Confirming || stage == MainScanStage.Persisting,
                stage.blocksMainScanExit()
            )
        }
    }
}
