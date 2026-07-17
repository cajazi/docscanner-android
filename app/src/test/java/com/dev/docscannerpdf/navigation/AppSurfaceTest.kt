package com.dev.docscannerpdf.navigation

import org.junit.Assert.assertEquals
import org.junit.Test

class AppSurfaceTest {

    @Test
    fun captureHoldsPriorityWhileActive() {
        // Even with review state simultaneously open, an active capture session wins.
        assertEquals(
            AppSurface.ID_CARD_CAPTURE,
            resolveAppSurface(
                appLockActive = false,
                showOnboarding = false,
                showIdCardGuidedCapture = true,
                idCardReviewOpen = true
            )
        )
    }

    @Test
    fun unrelatedStateCannotDeselectCaptureByConstruction() {
        // The selector takes exactly four inputs: document lists, preview state, camera
        // support state, capture stage, frame geometry and permission recomposition are not
        // parameters, so no change to them can ever alter the selected surface. Deterministic:
        val first = resolveAppSurface(false, false, true, false)
        val second = resolveAppSurface(false, false, true, false)

        assertEquals(AppSurface.ID_CARD_CAPTURE, first)
        assertEquals(first, second)
    }

    @Test
    fun appLockOutranksCapture() {
        assertEquals(
            AppSurface.APP_LOCK,
            resolveAppSurface(
                appLockActive = true,
                showOnboarding = true,
                showIdCardGuidedCapture = true,
                idCardReviewOpen = true
            )
        )
    }

    @Test
    fun onboardingOutranksCaptureButNotAppLock() {
        assertEquals(
            AppSurface.ONBOARDING,
            resolveAppSurface(
                appLockActive = false,
                showOnboarding = true,
                showIdCardGuidedCapture = true,
                idCardReviewOpen = false
            )
        )
    }

    @Test
    fun completeExplicitlyMovesCaptureToReview() {
        // beginIdCardReview clears the capture flag and opens the review in one transition.
        val duringCapture = resolveAppSurface(false, false, true, false)
        val afterComplete = resolveAppSurface(false, false, false, true)

        assertEquals(AppSurface.ID_CARD_CAPTURE, duringCapture)
        assertEquals(AppSurface.ID_CARD_REVIEW, afterComplete)
    }

    @Test
    fun explicitExitLeavesCapture() {
        assertEquals(
            AppSurface.OTHER,
            resolveAppSurface(
                appLockActive = false,
                showOnboarding = false,
                showIdCardGuidedCapture = false,
                idCardReviewOpen = false
            )
        )
    }

    @Test
    fun unlockingAndFinishingOnboardingReturnToCaptureWhenStillActive() {
        // Higher-priority surfaces ending must fall back to the still-active capture flag —
        // not accidentally remount something else.
        val locked = resolveAppSurface(true, false, true, false)
        val unlocked = resolveAppSurface(false, false, true, false)

        assertEquals(AppSurface.APP_LOCK, locked)
        assertEquals(AppSurface.ID_CARD_CAPTURE, unlocked)
    }
}
