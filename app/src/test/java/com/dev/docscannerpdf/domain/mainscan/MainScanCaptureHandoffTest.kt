package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.navigation.AppSurface
import com.dev.docscannerpdf.navigation.resolveAppSurface
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the capture→Crop handoff — the path that hung in physical QA.
 *
 * These model the host's decision sequence end to end (reducer verdict → ownership verdict → surface
 * verdict) rather than any single function, because the hang was a property of the SEQUENCE: each
 * step in isolation was correct.
 */
class MainScanCaptureHandoffTest {

    private val filesDir = "/data/user/0/com.dev.docscannerpdf/files"
    private val ownedPage = "file://$filesDir/main_scan_capture/main_scan-1.jpg"

    /** The surface the host would select for [state], mirroring the DocScannerApp inputs. */
    private fun surfaceFor(state: MainScanCaptureState, captureOpen: Boolean = true): AppSurface =
        resolveAppSurface(
            appLockActive = false,
            showOnboarding = false,
            showIdCardGuidedCapture = false,
            showPassportCapture = false,
            passportReviewOpen = false,
            mainScanCaptureOpen = captureOpen,
            mainScanPageUri = state.pendingPage?.uri,
            idCardReviewOpen = false
        )

    // 1 — success callback publishes exactly one pending page ------------------------------------

    @Test
    fun successCallbackPublishesExactlyOnePendingPage() {
        val visit = MainScanCaptureFlow.beginVisit(null)
        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(visit)!!

        val published = MainScanCaptureFlow.captureSucceeded(
            state = capturing,
            ticket = ticket,
            uri = ownedPage,
            source = MainScanPageSource.CAMERA
        )
        assertEquals(ownedPage, published.pendingPage?.uri)

        // A duplicated delivery of the SAME result cannot add a second page: the reducer is applied
        // to the already-published state, whose generation the ticket no longer matches.
        val duplicated = MainScanCaptureFlow.captureSucceeded(
            state = published,
            ticket = ticket,
            uri = ownedPage,
            source = MainScanPageSource.CAMERA
        )
        assertEquals("still exactly one page", ownedPage, duplicated.pendingPage?.uri)
        assertEquals("ledger records it once", setOf(ownedPage), duplicated.ownedUris)
    }

    // 2 — current ticket transitions to HANDING_OFF and selects Crop ------------------------------

    @Test
    fun currentTicketTransitionsToHandingOffAndCropIsSelected() {
        val visit = MainScanCaptureFlow.beginVisit(null)
        assertEquals(AppSurface.MAIN_SCAN_CAPTURE, surfaceFor(visit))

        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(visit)!!
        assertEquals(
            "still on capture while the shot is in flight",
            AppSurface.MAIN_SCAN_CAPTURE,
            surfaceFor(capturing)
        )

        val published = MainScanCaptureFlow.captureSucceeded(
            state = capturing,
            ticket = ticket,
            uri = ownedPage,
            source = MainScanPageSource.CAMERA
        )
        assertEquals(MainScanCaptureStage.HANDING_OFF, published.stage)
        assertEquals(AppSurface.MAIN_SCAN_CROP, surfaceFor(published))
    }

    // 3 — stale ticket output is rejected AND cleaned ---------------------------------------------

    @Test
    fun staleTicketOutputIsRejectedAndIdentifiedAsDeletableAppOwnedFile() {
        val visitA = MainScanCaptureFlow.beginVisit(null)
        val (capturingA, ticketA) = MainScanCaptureFlow.beginCapture(visitA)!!
        val visitB = MainScanCaptureFlow.beginVisit(capturingA)

        val after = MainScanCaptureFlow.captureSucceeded(
            state = visitB,
            ticket = ticketA,
            uri = ownedPage,
            source = MainScanPageSource.CAMERA
        )
        assertSame("rejected outright", visitB, after)
        assertNull(after.pendingPage)

        // The host deletes exactly when the new state does not reference the output...
        assertFalse(ownedPage in MainScanFileOwnership.referencedUris(after))
        // ...and only ever through the app-owned guard.
        assertTrue(MainScanFileOwnership.isOwnedFileUri(ownedPage, filesDir))
        // The surface stays on capture — a stale result cannot navigate.
        assertEquals(AppSurface.MAIN_SCAN_CAPTURE, surfaceFor(after))
    }

    @Test
    fun staleOutputThatIsNotAppOwnedIsNeverDeletable() {
        val external = "content://media/external/images/media/99"
        assertFalse(MainScanFileOwnership.isOwnedFileUri(external, filesDir))
    }

    // 4 — a valid output is NOT deleted before Crop consumes it -----------------------------------

    @Test
    fun validOutputIsNeverDeletedWhileCropStillNeedsIt() {
        val visit = MainScanCaptureFlow.beginVisit(null)
        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(visit)!!
        val published = MainScanCaptureFlow.captureSucceeded(
            state = capturing,
            ticket = ticket,
            uri = ownedPage,
            source = MainScanPageSource.CAMERA
        )

        // The host's delete condition is "not referenced by the new state" — it must be false here.
        assertTrue(ownedPage in MainScanFileOwnership.referencedUris(published))
        // Nothing is superseded by the publication itself.
        assertTrue(MainScanFileOwnership.supersededUris(capturing, published).isEmpty())
        // And a visit sweep that retains the handed-forward page leaves it alone.
        assertTrue(
            MainScanFileOwnership.visitOrphans(published, retainUris = setOf(ownedPage)).isEmpty()
        )
    }

    // 5 — zero-length output fails closed --------------------------------------------------------

    @Test
    fun zeroLengthOutputIsTreatedAsFailureNotAPage() {
        // The controller validates existence + length and delivers null for an empty file, so the
        // reducer sees the FAILURE path. Modelled here as the failure transition it produces.
        val visit = MainScanCaptureFlow.beginVisit(null)
        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(visit)!!
        val failed = MainScanCaptureFlow.captureFailed(capturing, ticket)

        assertNull("no page from an empty output", failed.pendingPage)
        assertEquals(MainScanCaptureStage.IDLE, failed.stage)
        assertTrue("shutter re-armed", failed.canCapture)
        assertEquals(AppSurface.MAIN_SCAN_CAPTURE, surfaceFor(failed))
    }

    @Test
    fun blankUriCanNeverBecomeAPageEvenWithACurrentTicket() {
        val visit = MainScanCaptureFlow.beginVisit(null)
        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(visit)!!
        for (bad in listOf("", "   ")) {
            val after = MainScanCaptureFlow.captureSucceeded(
                state = capturing,
                ticket = ticket,
                uri = bad,
                source = MainScanPageSource.CAMERA
            )
            assertNull(after.pendingPage)
        }
    }

    // 6 — MAIN_SCAN_CROP wins whenever a pendingPage exists --------------------------------------

    @Test
    fun cropOutranksCaptureWheneverAPendingPageExists() {
        val visit = MainScanCaptureFlow.beginVisit(null)
        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(visit)!!
        val published = MainScanCaptureFlow.captureSucceeded(
            state = capturing,
            ticket = ticket,
            uri = ownedPage,
            source = MainScanPageSource.CAMERA
        )
        // Crop wins whether or not the capture flag has settled — this is what stops the captured
        // pixels being replaced by a re-mounted preview.
        assertEquals(AppSurface.MAIN_SCAN_CROP, surfaceFor(published, captureOpen = true))
        assertEquals(AppSurface.MAIN_SCAN_CROP, surfaceFor(published, captureOpen = false))
    }

    // 7 — a callback after exit cannot navigate ---------------------------------------------------

    @Test
    fun callbackArrivingAfterExitCannotNavigateToCrop() {
        val visit = MainScanCaptureFlow.beginVisit(null)
        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(visit)!!
        // The user leaves: the host opens a new visit id and clears the capture flag.
        val exited = MainScanCaptureFlow.beginVisit(capturing)

        val late = MainScanCaptureFlow.captureSucceeded(
            state = exited,
            ticket = ticket,
            uri = ownedPage,
            source = MainScanPageSource.CAMERA
        )
        assertSame(exited, late)
        assertEquals(
            "must land nowhere near Crop",
            AppSurface.OTHER,
            surfaceFor(late, captureOpen = false)
        )
    }

    // 8 — repeated shutter taps stay single-flight ------------------------------------------------

    @Test
    fun repeatedShutterTapsRemainSingleFlight() {
        val visit = MainScanCaptureFlow.beginVisit(null)
        val (capturing, _) = MainScanCaptureFlow.beginCapture(visit)!!
        repeat(5) { assertNull(MainScanCaptureFlow.beginCapture(capturing)) }

        val (published, _) = MainScanCaptureFlow.beginCapture(visit)!!.let { (state, ticket) ->
            MainScanCaptureFlow.captureSucceeded(
                state = state,
                ticket = ticket,
                uri = ownedPage,
                source = MainScanPageSource.CAMERA
            ) to ticket
        }
        repeat(5) { assertNull("no capture while handing off", MainScanCaptureFlow.beginCapture(published)) }
    }

    // --- the watchdog: CAPTURING is never terminal ----------------------------------------------

    @Test
    fun aCaptureThatNeverCallsBackIsAbandonedAndTheShutterReArms() {
        val visit = MainScanCaptureFlow.beginVisit(null)
        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(visit)!!
        assertFalse("dead shutter while in flight", capturing.canCapture)

        val abandoned = MainScanCaptureFlow.captureTimedOut(capturing, ticket)
        assertEquals(MainScanCaptureStage.IDLE, abandoned.stage)
        assertTrue("the shutter must come back", abandoned.canCapture)
        assertNull(abandoned.pendingPage)
    }

    @Test
    fun aResultArrivingAfterTheWatchdogFiredPublishesNothing() {
        val visit = MainScanCaptureFlow.beginVisit(null)
        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(visit)!!
        val abandoned = MainScanCaptureFlow.captureTimedOut(capturing, ticket)

        val late = MainScanCaptureFlow.captureSucceeded(
            state = abandoned,
            ticket = ticket,
            uri = ownedPage,
            source = MainScanPageSource.CAMERA
        )
        assertSame("the timeout advanced the generation, so this is stale", abandoned, late)
        assertNull(late.pendingPage)
        assertFalse(ownedPage in MainScanFileOwnership.referencedUris(late))
    }

    @Test
    fun aStaleWatchdogFiringIsIgnored() {
        val visit = MainScanCaptureFlow.beginVisit(null)
        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(visit)!!
        // The real result landed first; the watchdog then fires for the same ticket.
        val published = MainScanCaptureFlow.captureSucceeded(
            state = capturing,
            ticket = ticket,
            uri = ownedPage,
            source = MainScanPageSource.CAMERA
        )
        assertSame(
            "a late watchdog must not undo a successful publication",
            published,
            MainScanCaptureFlow.captureTimedOut(published, ticket)
        )
        assertEquals(ownedPage, published.pendingPage?.uri)
    }

    // --- no persistence anywhere in the handoff --------------------------------------------------

    @Test
    fun nothingInTheHandoffCarriesADocumentIdentity() {
        val visit = MainScanCaptureFlow.beginVisit(null)
        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(visit)!!
        val published = MainScanCaptureFlow.captureSucceeded(
            state = capturing,
            ticket = ticket,
            uri = ownedPage,
            source = MainScanPageSource.CAMERA
        )
        assertEquals(
            MainScanPendingPage(ownedPage, MainScanPageSource.CAMERA, ticket.generation),
            published.pendingPage
        )
    }
}
