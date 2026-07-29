package com.dev.docscannerpdf.domain.mainscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the Main Scanner capture reducer. Each test names the locked-reference
 * invariant it protects (see `docs/main-scanner-reference.md`).
 */
class MainScanCaptureFlowTest {

    private val visit = MainScanCaptureFlow.beginVisit(null)

    private fun capture(
        state: MainScanCaptureState,
        uri: String = "file:///files/page.jpg"
    ): Pair<MainScanCaptureState, MainScanCaptureTicket> {
        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(state)!!
        return MainScanCaptureFlow.captureSucceeded(
            state = capturing,
            ticket = ticket,
            uri = uri,
            source = MainScanPageSource.CAMERA
        ) to ticket
    }

    // --- Manual shutter is first-class (invariant 2) --------------------------------------------

    @Test
    fun freshVisitArmsTheShutter() {
        assertTrue(visit.canCapture)
        assertTrue(visit.canImport)
        assertFalse(visit.isBusy)
        assertFalse(visit.hasPendingPage)
    }

    @Test
    fun shutterIsNotGatedByDetectionOrResolution() {
        // The state carries NO detection/confidence/stability/resolution field, so no such input
        // can ever withhold the shutter.
        assertTrue(MainScanCaptureState(stage = MainScanCaptureStage.IDLE).canCapture)
    }

    @Test
    fun beginCaptureIsSingleFlight() {
        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(visit)!!
        assertEquals(1L, ticket.generation)
        assertEquals(visit.sessionId, ticket.sessionId)
        assertEquals(MainScanCaptureStage.CAPTURING, capturing.stage)
        assertFalse("shutter disarmed while in flight", capturing.canCapture)
        assertNull("a second tap is rejected", MainScanCaptureFlow.beginCapture(capturing))
    }

    @Test
    fun shutterIsDisarmedWhileDiscardDialogIsOpen() {
        val confirming = MainScanCaptureFlow.requestDiscard(visit)
        assertFalse(confirming.canCapture)
        assertNull(MainScanCaptureFlow.beginCapture(confirming))
    }

    @Test
    fun shutterStaysDisarmedWhileHandingOffToCrop() {
        // Routing is not instantaneous. A tap landing in this window must not race the navigation
        // and overwrite the page the user is about to crop.
        val (handingOff, _) = capture(visit)
        assertEquals(MainScanCaptureStage.HANDING_OFF, handingOff.stage)
        assertTrue(handingOff.isBusy)
        assertFalse(handingOff.canCapture)
        assertNull(MainScanCaptureFlow.beginCapture(handingOff))
    }

    // --- No stale frame (invariant 5) -----------------------------------------------------------

    @Test
    fun captureSucceededPublishesThePendingPage() {
        val (published, ticket) = capture(visit)
        assertEquals(MainScanCaptureStage.HANDING_OFF, published.stage)
        assertNotNull(published.pendingPage)
        assertEquals(ticket.generation, published.pendingPage!!.captureGeneration)
        assertEquals(MainScanPageSource.CAMERA, published.pendingPage!!.source)
        assertTrue(published.hasPendingPage)
    }

    @Test
    fun staleGenerationWithinTheSameVisitIsRejectedUnchanged() {
        val (first, firstTicket) = MainScanCaptureFlow.beginCapture(visit)!!
        val failedFirst = MainScanCaptureFlow.captureFailed(first, firstTicket)
        val (second, _) = MainScanCaptureFlow.beginCapture(failedFirst)!!

        val stale = MainScanCaptureFlow.captureSucceeded(
            state = second,
            ticket = firstTicket,
            uri = "file:///files/stale.jpg",
            source = MainScanPageSource.CAMERA
        )
        assertSame("stale result must not mutate state", second, stale)
        assertNull(stale.pendingPage)
    }

    @Test
    fun blankUriIsNeverPublishedAsAPage() {
        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(visit)!!
        val published = MainScanCaptureFlow.captureSucceeded(
            state = capturing,
            ticket = ticket,
            uri = "   ",
            source = MainScanPageSource.CAMERA
        )
        assertNull(published.pendingPage)
    }

    // --- Leaving capture invalidates the session (late callback cannot navigate) ----------------

    @Test
    fun aLateCaptureFromAnAbandonedVisitCannotPublishEvenWhenGenerationsMatch() {
        // The collision a generation-only token would allow: visit A's capture is generation 1, the
        // user leaves, visit B starts and its first capture is ALSO generation 1. Without the visit
        // id, A's late result would satisfy B's check and navigate B to crop with A's pixels.
        val visitA = MainScanCaptureFlow.beginVisit(null)
        val (capturingA, ticketA) = MainScanCaptureFlow.beginCapture(visitA)!!

        val visitB = MainScanCaptureFlow.beginVisit(capturingA)
        val (capturingB, ticketB) = MainScanCaptureFlow.beginCapture(visitB)!!

        assertEquals("the collision this guards against", ticketA.generation, ticketB.generation)
        assertNotEquals(ticketA.sessionId, ticketB.sessionId)

        val late = MainScanCaptureFlow.captureSucceeded(
            state = capturingB,
            ticket = ticketA,
            uri = "file:///files/visit-a.jpg",
            source = MainScanPageSource.CAMERA
        )
        assertSame("visit A's late result must be rejected outright", capturingB, late)
        assertNull("it must not become visit B's page", late.pendingPage)
        assertFalse(late.hasPendingPage)
    }

    @Test
    fun beginVisitAlwaysAdvancesTheSessionIdAndClearsEverything() {
        val (published, _) = capture(visit)
        val next = MainScanCaptureFlow.beginVisit(published)

        assertEquals(published.sessionId + 1L, next.sessionId)
        assertNull(next.pendingPage)
        assertTrue(next.ownedUris.isEmpty())
        assertEquals(0L, next.captureGeneration)
        assertEquals(MainScanCaptureStage.IDLE, next.stage)
        assertFalse(next.discardConfirmVisible)
    }

    @Test
    fun isCurrentRequiresBothVisitAndGeneration() {
        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(visit)!!
        assertTrue(MainScanCaptureFlow.isCurrent(capturing, ticket))
        assertFalse(
            MainScanCaptureFlow.isCurrent(capturing, ticket.copy(sessionId = ticket.sessionId + 1))
        )
        assertFalse(
            MainScanCaptureFlow.isCurrent(capturing, ticket.copy(generation = ticket.generation + 1))
        )
    }

    // --- Failed capture never routes forward (no raw fallback) ----------------------------------

    @Test
    fun captureFailedRearmsShutterAndCreatesNoPage() {
        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(visit)!!
        val failed = MainScanCaptureFlow.captureFailed(capturing, ticket)
        assertEquals(MainScanCaptureStage.IDLE, failed.stage)
        assertTrue(failed.canCapture)
        assertNull(failed.pendingPage)
    }

    @Test
    fun staleFailureIsIgnoredSoNoSpuriousErrorIsShown() {
        val visitA = MainScanCaptureFlow.beginVisit(null)
        val (capturingA, ticketA) = MainScanCaptureFlow.beginCapture(visitA)!!
        val visitB = MainScanCaptureFlow.beginVisit(capturingA)
        assertSame(visitB, MainScanCaptureFlow.captureFailed(visitB, ticketA))
    }

    // --- Ledger + discard (invariant 8) --------------------------------------------------------

    @Test
    fun ownedUrisAccumulateAndPublishedPageIsRecorded() {
        val withOrphan = MainScanCaptureFlow.withOwnedUri(visit, "file:///files/orphan.jpg")
        val (published, _) = capture(withOrphan)
        assertEquals(
            setOf("file:///files/orphan.jpg", "file:///files/page.jpg"),
            published.ownedUris
        )
    }

    @Test
    fun blankOwnedUriIsIgnored() {
        assertSame(visit, MainScanCaptureFlow.withOwnedUri(visit, ""))
    }

    @Test
    fun confirmDiscardOpensANewVisitAndLeavesTheOldStateInspectable() {
        val (published, _) = capture(visit)
        val discarded = MainScanCaptureFlow.confirmDiscard(published)

        assertEquals(published.sessionId + 1L, discarded.sessionId)
        assertNull(discarded.pendingPage)
        assertTrue(discarded.ownedUris.isEmpty())
        // The old state is still intact for the caller's sweep — it must be able to see the orphans.
        assertEquals(setOf("file:///files/page.jpg"), published.ownedUris)
    }

    @Test
    fun cancelDiscardRestoresCaptureWithEverythingIntact() {
        val (published, _) = capture(visit)
        val restored = MainScanCaptureFlow.cancelDiscard(
            MainScanCaptureFlow.requestDiscard(published)
        )
        assertEquals(published, restored)
    }

    @Test
    fun backConfirmsOnlyWhenSomethingWouldBeLost() {
        assertFalse("pristine surface exits directly", MainScanCaptureFlow.backNeedsConfirmation(visit))

        val withOwned = MainScanCaptureFlow.withOwnedUri(visit, "file:///files/a.jpg")
        assertTrue(MainScanCaptureFlow.backNeedsConfirmation(withOwned))

        val (capturing, _) = MainScanCaptureFlow.beginCapture(visit)!!
        assertTrue("an in-flight capture must confirm", MainScanCaptureFlow.backNeedsConfirmation(capturing))

        val (published, _) = capture(visit)
        assertTrue(MainScanCaptureFlow.backNeedsConfirmation(published))
    }

    // --- Hand-off (invariant 4/6) ---------------------------------------------------------------

    @Test
    fun pendingPageConsumedRearmsCaptureButKeepsTheLedger() {
        val (published, _) = capture(visit)
        val consumed = MainScanCaptureFlow.pendingPageConsumed(published)
        assertNull(consumed.pendingPage)
        assertEquals(MainScanCaptureStage.IDLE, consumed.stage)
        assertTrue(consumed.canCapture)
        assertEquals(
            "the file still exists; the crop workflow owns its fate now",
            setOf("file:///files/page.jpg"),
            consumed.ownedUris
        )
    }

    // --- Import shares the single-flight slot ---------------------------------------------------

    @Test
    fun importOccupiesTheSameSingleFlightSlotAsTheShutter() {
        val (importing, ticket) = MainScanCaptureFlow.beginImport(visit)!!
        assertTrue(importing.importInFlight)
        assertFalse(importing.canCapture)
        assertNull(MainScanCaptureFlow.beginCapture(importing))
        assertNull(MainScanCaptureFlow.beginImport(importing))

        val done = MainScanCaptureFlow.captureSucceeded(
            state = importing,
            ticket = ticket,
            uri = "file:///files/imported.jpg",
            source = MainScanPageSource.IMPORT
        )
        assertFalse(done.importInFlight)
        assertEquals(MainScanPageSource.IMPORT, done.pendingPage!!.source)
    }

    // --- Capture never persists (invariant 7) --------------------------------------------------

    @Test
    fun noStateCarriesADocumentIdentity() {
        // Guard against a future slice smuggling persistence into capture: the pending page has
        // exactly three fields, none of which is a document id or row reference.
        val (published, ticket) = capture(visit)
        assertEquals(
            MainScanPendingPage(
                uri = "file:///files/page.jpg",
                source = MainScanPageSource.CAMERA,
                captureGeneration = ticket.generation
            ),
            published.pendingPage
        )
    }

    // --- Back on the crop surface must be answerable -----------------------------------------------

    /**
     * Back from a captured page raises a decision that SOMETHING must then answer.
     *
     * On device this was inert: the crop surface set this flag and rendered no dialog, so Back had no
     * visible effect. Worse, the next Back press cancelled the invisible dialog, so the two presses
     * alternated between unseen states and the crop screen could not be left at all.
     *
     * The rendering fix lives in the composition, but this pins the state contract it relies on: the
     * flag really is raised, cancelling really does clear it, and neither transition quietly drops
     * the captured page — so a surface that renders the dialog can always resolve it.
     */
    @Test
    fun backFromACapturedPageRaisesAResolvableDiscardDecision() {
        val (captured, _) = capture(visit)
        assertTrue("a captured page must require confirmation", captured.hasPendingPage)
        assertTrue(MainScanCaptureFlow.backNeedsConfirmation(captured))

        val asking = MainScanCaptureFlow.requestDiscard(captured)
        assertTrue("Back must raise the decision", asking.discardConfirmVisible)
        assertNotNull("the page survives the question", asking.pendingPage)

        val cancelled = MainScanCaptureFlow.cancelDiscard(asking)
        assertFalse(cancelled.discardConfirmVisible)
        assertEquals("cancelling loses nothing", captured.pendingPage, cancelled.pendingPage)

        // The alternation the missing dialog produced must remain harmless: however many times the
        // question is raised and dismissed, the captured page is still there to act on.
        var cycled = cancelled
        repeat(3) {
            cycled = MainScanCaptureFlow.cancelDiscard(MainScanCaptureFlow.requestDiscard(cycled))
        }
        assertEquals(captured.pendingPage, cycled.pendingPage)

        // And confirming genuinely ends the visit rather than returning to the same question.
        val discarded = MainScanCaptureFlow.confirmDiscard(MainScanCaptureFlow.requestDiscard(cycled))
        assertFalse(discarded.discardConfirmVisible)
        assertNull(discarded.pendingPage)
        assertNotEquals(captured.sessionId, discarded.sessionId)
    }
}
