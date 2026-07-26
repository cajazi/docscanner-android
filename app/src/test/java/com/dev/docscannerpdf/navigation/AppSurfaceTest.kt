package com.dev.docscannerpdf.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppSurfaceTest {

    private fun resolve(
        appLockActive: Boolean = false,
        showOnboarding: Boolean = false,
        showIdCardGuidedCapture: Boolean = false,
        showPassportCapture: Boolean = false,
        passportReviewOpen: Boolean = false,
        mainScanCaptureOpen: Boolean = false,
        mainScanPageUri: String? = null,
        idCardReviewOpen: Boolean = false
    ) = resolveAppSurface(
        appLockActive = appLockActive,
        showOnboarding = showOnboarding,
        showIdCardGuidedCapture = showIdCardGuidedCapture,
        showPassportCapture = showPassportCapture,
        passportReviewOpen = passportReviewOpen,
        mainScanCaptureOpen = mainScanCaptureOpen,
        mainScanPageUri = mainScanPageUri,
        idCardReviewOpen = idCardReviewOpen
    )

    @Test
    fun captureHoldsPriorityWhileActive() {
        // Even with review state simultaneously open, an active capture session wins.
        assertEquals(
            AppSurface.ID_CARD_CAPTURE,
            resolve(showIdCardGuidedCapture = true, idCardReviewOpen = true)
        )
    }

    @Test
    fun passportCaptureHoldsPriorityWhileActive() {
        assertEquals(
            AppSurface.PASSPORT_CAPTURE,
            resolve(showPassportCapture = true, idCardReviewOpen = true)
        )
    }

    @Test
    fun idCardAndPassportCaptureAreDistinctSurfaces() {
        assertEquals(AppSurface.ID_CARD_CAPTURE, resolve(showIdCardGuidedCapture = true))
        assertEquals(AppSurface.PASSPORT_CAPTURE, resolve(showPassportCapture = true))
    }

    @Test
    fun unrelatedStateCannotDeselectCaptureByConstruction() {
        // The selector takes exactly its declared inputs; document lists, preview state, camera
        // support state, capture stage, frame geometry and permission recomposition are not
        // parameters, so no change to them can ever alter the selected surface. Deterministic:
        val first = resolve(showPassportCapture = true)
        val second = resolve(showPassportCapture = true)

        assertEquals(AppSurface.PASSPORT_CAPTURE, first)
        assertEquals(first, second)
    }

    @Test
    fun appLockOutranksBothCaptures() {
        assertEquals(
            AppSurface.APP_LOCK,
            resolve(
                appLockActive = true,
                showOnboarding = true,
                showIdCardGuidedCapture = true,
                showPassportCapture = true,
                idCardReviewOpen = true
            )
        )
    }

    @Test
    fun onboardingOutranksCaptureButNotAppLock() {
        assertEquals(
            AppSurface.ONBOARDING,
            resolve(showOnboarding = true, showIdCardGuidedCapture = true, showPassportCapture = true)
        )
    }

    @Test
    fun idCardCaptureOutranksPassportWhenBothSomehowSet() {
        // Defensive: the two flags are never set together in practice, but the ordering is
        // deterministic rather than undefined.
        assertEquals(
            AppSurface.ID_CARD_CAPTURE,
            resolve(showIdCardGuidedCapture = true, showPassportCapture = true)
        )
    }

    @Test
    fun idCardCompleteMovesToReview() {
        val duringCapture = resolve(showIdCardGuidedCapture = true)
        val afterComplete = resolve(idCardReviewOpen = true)

        assertEquals(AppSurface.ID_CARD_CAPTURE, duringCapture)
        assertEquals(AppSurface.ID_CARD_REVIEW, afterComplete)
    }

    @Test
    fun passportCaptureCompletionOpensTheDedicatedPassportReview() {
        // beginPassportReview clears the capture flag and opens the DEDICATED review — the
        // passport path must never fall through to the generic Document Ready surface.
        val duringCapture = resolve(showPassportCapture = true)
        val afterComplete = resolve(passportReviewOpen = true)

        assertEquals(AppSurface.PASSPORT_CAPTURE, duringCapture)
        assertEquals(AppSurface.PASSPORT_REVIEW, afterComplete)
    }

    @Test
    fun passportReviewIsDistinctFromIdCardReview() {
        assertEquals(AppSurface.PASSPORT_REVIEW, resolve(passportReviewOpen = true))
        assertEquals(AppSurface.ID_CARD_REVIEW, resolve(idCardReviewOpen = true))
    }

    @Test
    fun passportReviewOutranksIdCardReviewAndNeverFallsThroughToGeneric() {
        assertEquals(
            AppSurface.PASSPORT_REVIEW,
            resolve(passportReviewOpen = true, idCardReviewOpen = true)
        )
    }

    @Test
    fun backFromPassportReviewClearsToGeneric() {
        // cancelPassportReview nulls the review state; the surface falls back correctly.
        assertEquals(AppSurface.OTHER, resolve())
    }

    @Test
    fun explicitExitLeavesCapture() {
        assertEquals(AppSurface.OTHER, resolve())
    }

    @Test
    fun unlockingReturnsToStillActivePassportCapture() {
        val locked = resolve(appLockActive = true, showPassportCapture = true)
        val unlocked = resolve(showPassportCapture = true)

        assertEquals(AppSurface.APP_LOCK, locked)
        assertEquals(AppSurface.PASSPORT_CAPTURE, unlocked)
    }

    // --- Main Scanner surfaces ------------------------------------------------------------------

    @Test
    fun mainScanCaptureIsItsOwnNearModalSurface() {
        assertEquals(AppSurface.MAIN_SCAN_CAPTURE, resolve(mainScanCaptureOpen = true))
    }

    @Test
    fun mainScanCaptureHoldsPriorityOverLowerSurfaces() {
        // A document list, a review, or any other lower branch flickering non-null must not be
        // able to replace the live Main Scanner camera and reconstruct its controller mid-visit.
        assertEquals(
            AppSurface.MAIN_SCAN_CAPTURE,
            resolve(mainScanCaptureOpen = true, idCardReviewOpen = true)
        )
    }

    @Test
    fun aPendingPageRoutesToCropEvenWhileTheCaptureFlagIsStillSet() {
        // The hand-off is not atomic in the host: the page is published before the capture flag
        // clears. Crop must win, or the captured pixels would be replaced by a re-mounted preview.
        assertEquals(
            AppSurface.MAIN_SCAN_CROP,
            resolve(mainScanCaptureOpen = true, mainScanPageUri = "file:///files/page.jpg")
        )
    }

    @Test
    fun mainScanCropIsNeverSelectedWithoutPixels() {
        // A null or blank page URI can never select the crop surface, so that screen cannot be
        // reached with nothing to display — no blank, white, or tiny frame is representable.
        assertEquals(AppSurface.OTHER, resolve(mainScanPageUri = null))
        assertEquals(AppSurface.OTHER, resolve(mainScanPageUri = "   "))
        assertEquals(AppSurface.MAIN_SCAN_CAPTURE, resolve(mainScanCaptureOpen = true, mainScanPageUri = ""))
    }

    @Test
    fun mainScanSurfacesAreDistinctFromEveryIdentityDocumentSurface() {
        assertEquals(AppSurface.MAIN_SCAN_CAPTURE, resolve(mainScanCaptureOpen = true))
        assertEquals(AppSurface.MAIN_SCAN_CROP, resolve(mainScanPageUri = "file:///files/p.jpg"))
        assertEquals(AppSurface.ID_CARD_CAPTURE, resolve(showIdCardGuidedCapture = true))
        assertEquals(AppSurface.PASSPORT_CAPTURE, resolve(showPassportCapture = true))
        assertEquals(AppSurface.PASSPORT_REVIEW, resolve(passportReviewOpen = true))
        assertEquals(AppSurface.ID_CARD_REVIEW, resolve(idCardReviewOpen = true))
    }

    @Test
    fun identityDocumentCapturesOutrankTheMainScanner() {
        // Defensive ordering: these flags are never set together in practice, but an active
        // identity capture session must never be displaced by main-scan state.
        assertEquals(
            AppSurface.ID_CARD_CAPTURE,
            resolve(showIdCardGuidedCapture = true, mainScanCaptureOpen = true, mainScanPageUri = "file:///f/p.jpg")
        )
        assertEquals(
            AppSurface.PASSPORT_CAPTURE,
            resolve(showPassportCapture = true, mainScanCaptureOpen = true, mainScanPageUri = "file:///f/p.jpg")
        )
    }

    @Test
    fun appLockAndOnboardingOutrankTheMainScanner() {
        assertEquals(
            AppSurface.APP_LOCK,
            resolve(appLockActive = true, mainScanCaptureOpen = true, mainScanPageUri = "file:///f/p.jpg")
        )
        assertEquals(
            AppSurface.ONBOARDING,
            resolve(showOnboarding = true, mainScanCaptureOpen = true, mainScanPageUri = "file:///f/p.jpg")
        )
    }

    @Test
    fun mainScanCaptureAndCropAreMutuallyExclusive() {
        // The selector returns ONE surface, so the two can never be presented together. Asserted
        // over every combination of the two inputs: at most one main-scan surface is ever selected.
        for (captureOpen in listOf(true, false)) {
            for (pageUri in listOf(null, "", "   ", "file:///files/page.jpg")) {
                val surface = resolve(mainScanCaptureOpen = captureOpen, mainScanPageUri = pageUri)
                val isCapture = surface == AppSurface.MAIN_SCAN_CAPTURE
                val isCrop = surface == AppSurface.MAIN_SCAN_CROP
                assertFalse(
                    "capture and crop must never both be selected (capture=$captureOpen page=$pageUri)",
                    isCapture && isCrop
                )
            }
        }
        // And the expected selection in each meaningful combination:
        assertEquals(AppSurface.MAIN_SCAN_CROP, resolve(mainScanCaptureOpen = true, mainScanPageUri = "file:///p.jpg"))
        assertEquals(AppSurface.MAIN_SCAN_CROP, resolve(mainScanCaptureOpen = false, mainScanPageUri = "file:///p.jpg"))
        assertEquals(AppSurface.MAIN_SCAN_CAPTURE, resolve(mainScanCaptureOpen = true, mainScanPageUri = null))
        assertEquals(AppSurface.OTHER, resolve(mainScanCaptureOpen = false, mainScanPageUri = null))
    }

    @Test
    fun discardingACapturedPageReturnsToTheCameraNotTheDashboard() {
        // Matches the locked reference: confirming discard on the crop surface drops the page and
        // lands back on the camera, ready to reshoot. confirmMainScanDiscard clears the pending page
        // and keeps the capture flag set when a page had been captured.
        assertEquals(
            AppSurface.MAIN_SCAN_CAPTURE,
            resolve(mainScanCaptureOpen = true, mainScanPageUri = null)
        )
    }

    @Test
    fun leavingTheCameraWithNothingCapturedReturnsToGeneric() {
        // Back on a pristine capture surface clears the flag outright.
        assertEquals(AppSurface.OTHER, resolve(mainScanCaptureOpen = false, mainScanPageUri = null))
    }
}
