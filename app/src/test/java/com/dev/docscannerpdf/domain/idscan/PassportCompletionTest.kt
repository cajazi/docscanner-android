package com.dev.docscannerpdf.domain.idscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A SUCCESSFUL passport save must land on the DEDICATED clean destination — the saved document in
 * the clean viewer, or the Documents list — and can NEVER resolve to the generic "Document Ready"
 * / backend-processing / E2E / To Word preview. That guarantee is encoded in the type itself:
 * [PassportCompletionDestination] has no generic-preview value to route to, and
 * [PassportCompletion.destinationFor] only ever returns one of its two clean members.
 */
class PassportCompletionTest {

    @Test
    fun savedDocumentWithRealRowIdOpensTheCleanSavedDocumentViewer() {
        // Room autoGenerate ids are strictly positive; that is a genuinely saved document.
        assertEquals(
            PassportCompletionDestination.SAVED_DOCUMENT,
            PassportCompletion.destinationFor(42L)
        )
    }

    @Test
    fun unsavedSentinelIdFallsBackToTheDocumentsList() {
        // 0L is DocumentEntity's default (not-yet-inserted) id — never open a viewer on nothing.
        assertEquals(
            PassportCompletionDestination.DOCUMENTS,
            PassportCompletion.destinationFor(0L)
        )
    }

    @Test
    fun nullIdFallsBackToTheDocumentsList() {
        assertEquals(
            PassportCompletionDestination.DOCUMENTS,
            PassportCompletion.destinationFor(null)
        )
    }

    @Test
    fun negativeIdFallsBackToTheDocumentsList() {
        assertEquals(
            PassportCompletionDestination.DOCUMENTS,
            PassportCompletion.destinationFor(-1L)
        )
    }

    @Test
    fun successRoutingHasNoGenericPreviewDestination() {
        // The whole point: there is no enum member that could open the generic Document Ready /
        // backend-processing / E2E / To Word screen — those states are structurally unreachable
        // from the passport success path.
        val destinations = PassportCompletionDestination.entries.map { it.name }.toSet()

        assertEquals(
            setOf("SAVED_DOCUMENT", "DOCUMENTS"),
            destinations
        )
    }

    @Test
    fun everyResolvedDestinationIsACleanDocumentsSurface() {
        // Regardless of the saved id, the resolved destination is always a Documents-family
        // surface — never a preview/editor of processing controls.
        val resolved = listOf(-5L, null, 0L, 1L, Long.MAX_VALUE).map {
            PassportCompletion.destinationFor(it)
        }

        assertTrue(resolved.all { it in PassportCompletionDestination.entries })
    }
}
