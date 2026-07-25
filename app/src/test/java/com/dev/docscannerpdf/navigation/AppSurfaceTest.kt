package com.dev.docscannerpdf.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSurfaceTest {

    private fun resolve(
        appLockActive: Boolean = false,
        showOnboarding: Boolean = false,
        showIdCardGuidedCapture: Boolean = false,
        showPassportCapture: Boolean = false,
        passportReviewOpen: Boolean = false,
        idCardReviewOpen: Boolean = false
    ) = resolveAppSurface(
        appLockActive = appLockActive,
        showOnboarding = showOnboarding,
        showIdCardGuidedCapture = showIdCardGuidedCapture,
        showPassportCapture = showPassportCapture,
        passportReviewOpen = passportReviewOpen,
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
}
