package com.dev.docscannerpdf.domain.mainscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The workflow stage machine. These tests exist mainly to pin the two properties that cause the
 * worst user-visible failures: persisting before the user confirmed, and blanking the page behind a
 * progress overlay.
 */
class MainScanWorkflowTest {

    private val preConfirmStages = listOf(
        MainScanStage.CameraReady,
        MainScanStage.Capturing,
        MainScanStage.CaptureAccepted,
        MainScanStage.CropPreparing,
        MainScanStage.CropEditing,
        MainScanStage.Cropping,
        MainScanStage.EnhancementPreparing,
        MainScanStage.EnhancementReview,
        MainScanStage.Confirming
    )

    // --- nothing is written before confirmation ----------------------------------------------------

    @Test
    fun noStageBeforePersistingMayWriteToTheDatabase() {
        for (stage in preConfirmStages) {
            assertFalse(
                "$stage must not be allowed to persist",
                MainScanWorkflow.allowsPersistence(stage)
            )
        }
        assertTrue(MainScanWorkflow.allowsPersistence(MainScanStage.Persisting))
    }

    @Test
    fun abandoningAtAnyWorkingStageLeavesNothingPersisted() {
        for (stage in preConfirmStages) {
            assertTrue(MainScanWorkflow.canTransition(stage, MainScanStage.Discarded))
            assertFalse(MainScanWorkflow.allowsPersistence(stage))
        }
    }

    // --- confirmation is single-flight -------------------------------------------------------------

    @Test
    fun confirmIsAcceptedOnlyFromReviewSoRepeatedTapsCannotDuplicate() {
        assertTrue(MainScanWorkflow.allowsConfirm(MainScanStage.EnhancementReview))
        assertFalse(
            "a second tap while confirming must be refused",
            MainScanWorkflow.allowsConfirm(MainScanStage.Confirming)
        )
        assertFalse(
            "and while persisting",
            MainScanWorkflow.allowsConfirm(MainScanStage.Persisting)
        )
        assertFalse(MainScanWorkflow.allowsConfirm(MainScanStage.Completed))
    }

    @Test
    fun aCompletedWorkflowIsTerminal() {
        for (stage in MainScanStage.entries) {
            assertFalse(
                "nothing may follow Completed (tried $stage)",
                MainScanWorkflow.canTransition(MainScanStage.Completed, stage)
            )
        }
    }

    // --- the page is never blanked -----------------------------------------------------------------

    @Test
    fun everyProcessingStageKeepsTheImageOnScreen() {
        // The reference draws progress OVER the retained page; a blank surface during processing is
        // the specific defect this prevents.
        for (stage in listOf(
            MainScanStage.Cropping,
            MainScanStage.EnhancementPreparing,
            MainScanStage.Confirming,
            MainScanStage.Persisting
        )) {
            assertTrue("$stage is busy", MainScanWorkflow.isBusy(stage))
            assertTrue("$stage must still show the image", MainScanWorkflow.requiresVisibleImage(stage))
        }
    }

    @Test
    fun theCameraStageDoesNotClaimToShowACapturedImage() {
        assertFalse(MainScanWorkflow.requiresVisibleImage(MainScanStage.CameraReady))
        assertFalse(MainScanWorkflow.requiresVisibleImage(MainScanStage.Capturing))
    }

    // --- editing and filtering are stage-gated -----------------------------------------------------

    @Test
    fun thePolygonIsEditableOnlyWhileCropEditing() {
        for (stage in MainScanStage.entries) {
            assertEquals(
                "editing gate for $stage",
                stage == MainScanStage.CropEditing,
                MainScanWorkflow.allowsPolygonEditing(stage)
            )
        }
    }

    /**
     * [MainScanStage.Failed] is not an editable crop stage, at any level of the gate.
     *
     * The crop surface used to compose the editor for every stage its `when` did not name, so a
     * decode failure — which has no working image and no polygon — landed on the editor's
     * empty-image branch: an indeterminate spinner over black with no work behind it, which never
     * resolves. The composition now names Failed explicitly, and these assertions are the contract
     * that keeps it from silently becoming an editing stage again: the polygon is not editable, and
     * Next cannot advance even if a caller claims the polygon is valid.
     */
    @Test
    fun theFailedStageIsNotAnEditableCropStage() {
        assertFalse(
            "Failed must not permit polygon editing",
            MainScanWorkflow.allowsPolygonEditing(MainScanStage.Failed)
        )
        assertFalse(
            "Failed must not advance even with a polygon claimed valid",
            MainScanWorkflow.allowsAdvanceFromCrop(MainScanStage.Failed, polygonValid = true)
        )
        // Nor may it masquerade as work in progress: a failure is not busy, and it has no image to
        // keep on screen, so a progress surface must never be chosen for it either.
        assertFalse(MainScanWorkflow.isBusy(MainScanStage.Failed))
        assertFalse(MainScanWorkflow.requiresVisibleImage(MainScanStage.Failed))
    }

    @Test
    fun filtersMayBeSelectedOnlyDuringReview() {
        for (stage in MainScanStage.entries) {
            assertEquals(
                "filter gate for $stage",
                stage == MainScanStage.EnhancementReview,
                MainScanWorkflow.allowsFilterSelection(stage)
            )
        }
    }

    // --- ordering -----------------------------------------------------------------------------------

    @Test
    fun theHappyPathFollowsTheLockedReferenceOrder() {
        val path = listOf(
            MainScanStage.CameraReady,
            MainScanStage.Capturing,
            MainScanStage.CaptureAccepted,
            MainScanStage.CropPreparing,
            MainScanStage.CropEditing,
            MainScanStage.Cropping,
            MainScanStage.EnhancementPreparing,
            MainScanStage.EnhancementReview,
            MainScanStage.Confirming,
            MainScanStage.Persisting,
            MainScanStage.Completed
        )
        path.zipWithNext { from, to ->
            assertTrue("$from -> $to must be legal", MainScanWorkflow.canTransition(from, to))
        }
    }

    @Test
    fun stagesCannotBeSkipped() {
        assertFalse(
            "crop cannot jump straight to review",
            MainScanWorkflow.canTransition(MainScanStage.CropEditing, MainScanStage.EnhancementReview)
        )
        assertFalse(
            "capture cannot jump straight to persistence",
            MainScanWorkflow.canTransition(MainScanStage.CaptureAccepted, MainScanStage.Persisting)
        )
        assertFalse(
            "review cannot jump straight to completed",
            MainScanWorkflow.canTransition(MainScanStage.EnhancementReview, MainScanStage.Completed)
        )
    }

    @Test
    fun failedPersistenceReturnsToReviewRatherThanCompleting() {
        assertTrue(
            MainScanWorkflow.canTransition(MainScanStage.Persisting, MainScanStage.EnhancementReview)
        )
    }

    // --- back behaviour -------------------------------------------------------------------------------

    @Test
    fun backConfirmsOnceAFrameHasBeenCaptured() {
        assertFalse(MainScanWorkflow.backNeedsConfirmation(MainScanStage.CameraReady))
        for (stage in listOf(
            MainScanStage.CaptureAccepted,
            MainScanStage.CropEditing,
            MainScanStage.EnhancementReview
        )) {
            assertTrue("$stage would lose work", MainScanWorkflow.backNeedsConfirmation(stage))
        }
    }

    @Test
    fun backFromReviewReturnsToCropRatherThanLosingThePolygon() {
        assertEquals(
            MainScanStage.CropEditing,
            MainScanWorkflow.backTarget(MainScanStage.EnhancementReview)
        )
        assertNull(
            "back from crop leaves the workflow",
            MainScanWorkflow.backTarget(MainScanStage.CropEditing)
        )
    }
}
