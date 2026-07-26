package com.dev.docscannerpdf.domain.mainscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Slice 1 camera contract: ONE lifecycle bind carrying Preview + ImageCapture + ImageAnalysis,
 * or a controlled failure. These tests exist because the failure mode being prevented — silently
 * dropping ImageAnalysis and presenting a scanner anyway — is invisible at runtime.
 */
class MainScanCameraLifecycleTest {

    @Test
    fun theOnlyBindOutcomesAreReadyOrFailed() {
        assertEquals(MainScanCameraState.READY, MainScanCameraLifecycle.afterBindAttempt(true))
        assertEquals(MainScanCameraState.FAILED, MainScanCameraLifecycle.afterBindAttempt(false))
        // There is no third, "degraded" state to land in.
        assertEquals(3, MainScanCameraState.entries.size)
    }

    @Test
    fun exactlyOneBindAttemptIsAdmitted() {
        assertTrue("the initial attempt", MainScanCameraLifecycle.allowsBindAttempt(MainScanCameraState.BINDING))
        assertFalse("an attached camera is never re-bound implicitly", MainScanCameraLifecycle.allowsBindAttempt(MainScanCameraState.READY))
        assertFalse("a failed bind never gets a second attempt", MainScanCameraLifecycle.allowsBindAttempt(MainScanCameraState.FAILED))
    }

    @Test
    fun captureRequiresAFullyAttachedCamera() {
        assertTrue(MainScanCameraLifecycle.allowsCapture(MainScanCameraState.READY))
        assertFalse(MainScanCameraLifecycle.allowsCapture(MainScanCameraState.BINDING))
        assertFalse(MainScanCameraLifecycle.allowsCapture(MainScanCameraState.FAILED))
    }

    @Test
    fun aFailedBindShowsTheFailureSurfaceInsteadOfAScanner() {
        assertTrue(MainScanCameraLifecycle.requiresFailureSurface(MainScanCameraState.FAILED))
        assertFalse(MainScanCameraLifecycle.requiresFailureSurface(MainScanCameraState.READY))
        assertFalse(
            "still binding is not a failure — no premature error surface",
            MainScanCameraLifecycle.requiresFailureSurface(MainScanCameraState.BINDING)
        )
    }

    @Test
    fun aFailedBindCanNeverBeLaunderedIntoASecondBindViaRecovery() {
        // The concrete regression: recovery must not become a back door to a reduced-configuration
        // bind. A FAILED camera never recovers, whatever the preview reports.
        for (streaming in listOf(true, false)) {
            for (inFlight in listOf(true, false)) {
                assertFalse(
                    "FAILED must never recover (streaming=$streaming inFlight=$inFlight)",
                    MainScanCameraLifecycle.allowsAutomaticRecovery(
                        state = MainScanCameraState.FAILED,
                        previewStreaming = streaming,
                        bindInFlight = inFlight
                    )
                )
                assertFalse(
                    "BINDING must never recover (streaming=$streaming inFlight=$inFlight)",
                    MainScanCameraLifecycle.allowsAutomaticRecovery(
                        state = MainScanCameraState.BINDING,
                        previewStreaming = streaming,
                        bindInFlight = inFlight
                    )
                )
            }
        }
    }

    @Test
    fun aCleanVisitPerformsZeroRecoveryBinds() {
        // Normal visit: the camera attached and the preview is streaming, so nothing recovers.
        assertFalse(
            MainScanCameraLifecycle.allowsAutomaticRecovery(
                state = MainScanCameraState.READY,
                previewStreaming = true,
                bindInFlight = false
            )
        )
    }

    @Test
    fun recoveryIsPermittedOnlyForAnAttachedCameraWhosePreviewStopped() {
        assertTrue(
            MainScanCameraLifecycle.allowsAutomaticRecovery(
                state = MainScanCameraState.READY,
                previewStreaming = false,
                bindInFlight = false
            )
        )
        assertFalse(
            "never while a bind is already in flight",
            MainScanCameraLifecycle.allowsAutomaticRecovery(
                state = MainScanCameraState.READY,
                previewStreaming = false,
                bindInFlight = true
            )
        )
    }
}
