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

    // --- crop preparation is a first preparation, never a repeat of one -----------------------------
    //
    // The crop surface asks for preparation from a Compose effect keyed on the pending page, and a
    // REMOUNT replays that effect with the same page — an Activity recreation, an app-lock unlock,
    // any re-entry of the composition. Now that the visit survives those, replaying preparation
    // would cancel the live pipeline, force the stage back to CropPreparing and re-resolve the
    // polygon over corners the user had already dragged. These tests pin exactly which stages may
    // still be prepared from.

    /** The complete admitted set, spelled out rather than derived, so widening it is a diff. */
    private val cropPreparationStages = setOf(
        MainScanStage.CameraReady,
        MainScanStage.Capturing,
        MainScanStage.CaptureAccepted
    )

    @Test
    fun cropPreparationIsDecidedForEveryStageAndAdmittedOnlyBeforeThePipelineStarts() {
        for (stage in MainScanStage.entries) {
            assertEquals(
                "crop preparation gate for $stage",
                stage in cropPreparationStages,
                MainScanWorkflow.allowsCropPreparation(stage)
            )
        }
    }

    @Test
    fun theLegitimateFirstPreparationIsStillAdmitted() {
        // The real entry point: a visit sits at CameraReady until preparation moves it on, so
        // refusing here would leave a captured page permanently undecoded.
        assertTrue(
            "the first preparation of a captured page must run",
            MainScanWorkflow.allowsCropPreparation(MainScanStage.CameraReady)
        )
        assertTrue(
            "an accepted capture has produced nothing yet, so preparing it destroys nothing",
            MainScanWorkflow.allowsCropPreparation(MainScanStage.CaptureAccepted)
        )
        assertTrue(
            "nor has a capture still in flight",
            MainScanWorkflow.allowsCropPreparation(MainScanStage.Capturing)
        )
    }

    @Test
    fun aRemountDuringCropEditingMayNotRePrepare() {
        // The polygon the user has been dragging exists only in memory. Re-preparing resolves a
        // fresh one from the frozen seed, so a rotation mid-edit would silently undo their work.
        assertFalse(
            "CropEditing must refuse preparation — the edited polygon would be overwritten",
            MainScanWorkflow.allowsCropPreparation(MainScanStage.CropEditing)
        )
        assertTrue(
            "precondition: this is the stage the polygon is actually editable in",
            MainScanWorkflow.allowsPolygonEditing(MainScanStage.CropEditing)
        )
    }

    @Test
    fun aRemountDuringEnhancementReviewMayNotRegressToPreparation() {
        // The worst case: returning from an app-lock unlock at review. Re-preparing would regress a
        // reviewed page to CropPreparing, release the derived previews, and strand the authoritative
        // artifact against a polygon that had been reset out from under it.
        assertFalse(
            "EnhancementReview must refuse preparation",
            MainScanWorkflow.allowsCropPreparation(MainScanStage.EnhancementReview)
        )
        assertTrue(
            "precondition: review is a stage with real derived pixels on screen",
            MainScanWorkflow.requiresVisibleImage(MainScanStage.EnhancementReview)
        )
    }

    @Test
    fun everyProcessingStageRefusesPreparationSoNoLivePipelineIsRestarted() {
        for (stage in listOf(
            MainScanStage.CropPreparing,
            MainScanStage.Cropping,
            MainScanStage.EnhancementPreparing,
            MainScanStage.Confirming,
            MainScanStage.Persisting
        )) {
            assertFalse(
                "$stage has a job advancing it; preparation would cancel and restart it",
                MainScanWorkflow.allowsCropPreparation(stage)
            )
        }
    }

    @Test
    fun terminalAndErrorStagesRefusePreparation() {
        // Completed and Discarded have no visit left to prepare. Failed has one, but its recovery is
        // an explicit user action — a remount silently retrying a decode that already failed would
        // loop the failure surface rather than resolve it.
        for (stage in listOf(
            MainScanStage.Completed,
            MainScanStage.Discarded,
            MainScanStage.Failed
        )) {
            assertFalse(
                "$stage must refuse preparation",
                MainScanWorkflow.allowsCropPreparation(stage)
            )
        }
    }

    @Test
    fun preparationIsRefusedFromEveryStageThatRequiresARetainedImage() {
        // Structural rather than enumerated: if a stage promises the user pixels are on screen, the
        // work that produced those pixels has already happened, so preparation cannot be a first one.
        for (stage in MainScanStage.entries) {
            if (MainScanWorkflow.requiresVisibleImage(stage)) {
                assertFalse(
                    "$stage keeps an image on screen, so preparation would discard live state",
                    MainScanWorkflow.allowsCropPreparation(stage)
                )
            }
        }
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
