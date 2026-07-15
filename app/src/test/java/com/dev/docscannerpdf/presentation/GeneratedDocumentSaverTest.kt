package com.dev.docscannerpdf.presentation

import com.dev.docscannerpdf.data.local.DocumentEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class GeneratedDocumentSaverTest {

    private fun document() = DocumentEntity(
        title = "ID Card 15-07-2026",
        timestamp = 1_752_000_000_000L,
        pageCount = 1,
        localPdfUri = "file://combined/id-card.jpg"
    )

    @Test
    fun repositorySuccessInvokesOnPersistedOnceAndNeverOnError() = runBlocking {
        var persistCalls = 0
        var persistedCalls = 0
        var errorCalls = 0

        val outcome = GeneratedDocumentSaver.save(
            document = document(),
            persist = { persistCalls++; 42L },
            onPersistFailed = { errorCalls++ },
            onPersisted = { saved ->
                persistedCalls++
                assertEquals(42L, saved.id)
            }
        )

        assertEquals(1, persistCalls)
        assertEquals(1, persistedCalls)
        assertEquals(0, errorCalls)
        assertTrue(outcome is GeneratedDocumentSaver.Outcome.Saved)
    }

    @Test
    fun throwingOnPersistedStillInsertsExactlyOneDocumentAndNeverInvokesOnError() = runBlocking {
        var persistCalls = 0
        var errorCalls = 0

        val outcome = GeneratedDocumentSaver.save(
            document = document(),
            persist = { persistCalls++; 7L },
            onPersistFailed = { errorCalls++ },
            onPersisted = { throw IllegalStateException("navigation blew up") }
        )

        assertEquals("exactly one insertion", 1, persistCalls)
        assertEquals("callback failure must never be reported as a save failure", 0, errorCalls)
        val saved = outcome as GeneratedDocumentSaver.Outcome.SavedButCallbackFailed
        assertEquals(7L, saved.document.id)
        assertEquals("navigation blew up", saved.callbackFailure.message)
    }

    @Test
    fun repositoryFailureInvokesOnErrorOnceAndNeverOnPersisted() = runBlocking {
        var persistCalls = 0
        var persistedCalls = 0
        val errors = mutableListOf<String>()

        val outcome = GeneratedDocumentSaver.save(
            document = document(),
            persist = { persistCalls++; throw IllegalStateException("disk full") },
            onPersistFailed = { errors.add(it) },
            onPersisted = { persistedCalls++ }
        )

        assertEquals(1, persistCalls)
        assertEquals(0, persistedCalls)
        assertEquals(listOf("disk full"), errors)
        assertEquals(GeneratedDocumentSaver.Outcome.Failed("disk full"), outcome)
    }

    @Test
    fun repositoryFailureWithoutMessageUsesDefaultMessage() = runBlocking {
        val errors = mutableListOf<String>()

        val outcome = GeneratedDocumentSaver.save(
            document = document(),
            persist = { throw IllegalStateException() },
            onPersistFailed = { errors.add(it) },
            onPersisted = { }
        )

        assertEquals(listOf(GeneratedDocumentSaver.DEFAULT_ERROR_MESSAGE), errors)
        assertTrue(outcome is GeneratedDocumentSaver.Outcome.Failed)
    }

    @Test
    fun cancellationDuringPersistPropagatesWithoutCallbacks() {
        var errorCalls = 0
        var persistedCalls = 0
        try {
            runBlocking {
                GeneratedDocumentSaver.save(
                    document = document(),
                    persist = { throw CancellationException("cancelled") },
                    onPersistFailed = { errorCalls++ },
                    onPersisted = { persistedCalls++ }
                )
            }
            fail("CancellationException must propagate")
        } catch (cancellation: CancellationException) {
            assertEquals(0, errorCalls)
            assertEquals(0, persistedCalls)
        }
    }
}
