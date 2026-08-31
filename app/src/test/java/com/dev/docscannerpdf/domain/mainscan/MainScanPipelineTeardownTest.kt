package com.dev.docscannerpdf.domain.mainscan

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the Main Scanner's in-memory crop pipeline is torn down on BOTH visit-ending paths.
 *
 * The pipeline state — the EXIF-upright working image, the perspective-cropped page, the enhanced
 * render and the polygon — lives on the activity as full-resolution bitmaps. `clearMainScanPipeline`
 * existed to release them but was never called, so a discarded or abandoned visit left every one of
 * them reachable: the next visit opened holding the previous page's pixels, and could compose a
 * stale crop state before its own decode had finished.
 *
 * ## Why this is a source contract rather than a state test
 *
 * The teardown mutates `MainActivity` fields. This module's unit tests run on the plain JVM with
 * JUnit only — there is no Robolectric and no mocking framework — so an activity cannot be
 * instantiated here, and moving the state into a pure holder purely to observe it would be a
 * production refactor this change is not scoped to make. The same trade-off is already resolved this
 * way for the routing contract (see [MainScanRoutingTest]): assert against the real source, on the
 * exact function bodies, so removing either call fails the build.
 *
 * The stage half of the contract IS pure and is asserted directly against [MainScanWorkflow].
 */
class MainScanPipelineTeardownTest {

    private fun activitySource(): String {
        // Unit tests run with the module directory as the working directory; tolerate the project
        // root too so the contract holds however the suite is invoked.
        val candidates = listOf(
            File("src/main/java/com/dev/docscannerpdf/MainActivity.kt"),
            File("app/src/main/java/com/dev/docscannerpdf/MainActivity.kt")
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull("could not locate MainActivity.kt from ${File("").absolutePath}", found)
        return found!!.readText()
    }

    private fun appSource(): String {
        val candidates = listOf(
            File("src/main/java/com/dev/docscannerpdf/ui/DocScannerApp.kt"),
            File("app/src/main/java/com/dev/docscannerpdf/ui/DocScannerApp.kt")
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull("could not locate DocScannerApp.kt from ${File("").absolutePath}", found)
        return found!!.readText()
    }

    /**
     * The body of [name], by brace matching from its declaration.
     *
     * Deliberately not a whole-file `contains` check: the call must be inside the function that ends
     * the visit, so that deleting it from one path — while another path elsewhere keeps it — still
     * fails.
     */
    private fun functionBody(source: String, name: String): String {
        val declaration = source.indexOf("fun $name(")
        assertTrue("$name must exist in MainActivity", declaration >= 0)
        val open = source.indexOf('{', declaration)
        assertTrue("$name must have a block body", open >= 0)

        var depth = 0
        var index = open
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
            index++
        }
        throw AssertionError("unbalanced braces while reading $name")
    }

    private fun blockBody(source: String, marker: String): String {
        val declaration = source.indexOf(marker)
        assertTrue("$marker must exist", declaration >= 0)
        val open = source.indexOf('{', declaration)
        assertTrue("$marker must have a block body", open >= 0)

        var depth = 0
        var index = open
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, index)
                }
            }
            index++
        }
        throw AssertionError("unbalanced braces while reading $marker")
    }

    // --- both visit-ending paths tear the pipeline down --------------------------------------------

    @Test
    fun confirmedDiscardClearsThePipeline() {
        val body = functionBody(activitySource(), "confirmMainScanDiscard")
        assertTrue(
            "a confirmed discard destroys the page, so its derived state must go with it",
            body.contains("clearMainScanPipeline()")
        )
    }

    @Test
    fun closingTheCaptureSurfaceClearsThePipeline() {
        val body = functionBody(activitySource(), "closeMainScanCapture")
        assertTrue(
            "leaving the scanner ends the visit, so its derived state must go with it",
            body.contains("clearMainScanPipeline()")
        )
    }

    // --- what the teardown actually releases -------------------------------------------------------

    @Test
    fun theTeardownReleasesEveryRetainedImageAndThePolygon() {
        val body = functionBody(activitySource(), "clearMainScanPipeline")

        // Each retained full-resolution bitmap, by name. A field added to the pipeline later and not
        // released here leaves this list incomplete rather than silently leaking.
        for (field in listOf(
            "mainScanEnhancedImage",
            "mainScanCroppedImage",
            "mainScanWorkingImage",
            // The authoritative artifact belongs to the visit that produced it. Surviving teardown
            // would leave a previous page's full-resolution files as the thing a Confirm would save.
            "mainScanAuthoritative"
        )) {
            assertTrue(
                "$field must be released by the teardown",
                body.contains("$field = null")
            )
        }

        assertTrue(
            "the polygon must not survive the visit that produced it",
            body.contains("mainScanCropState = null")
        )
        assertTrue(
            "in-flight processing must be cancelled so it cannot repopulate the cleared state",
            body.contains("mainScanProcessingJob?.cancel()") &&
                body.contains("mainScanProcessingJob = null")
        )
    }

    @Test
    fun theTeardownReturnsTheWorkflowToCameraReady() {
        val body = functionBody(activitySource(), "clearMainScanPipeline")
        assertTrue(
            "a torn-down visit must return to CameraReady, not linger on a working stage",
            body.contains("mainScanStage = MainScanStage.CameraReady")
        )
    }

    @Test
    fun theTeardownWritesNothingAndDeletesNoCapture() {
        val body = functionBody(activitySource(), "clearMainScanPipeline")
        // The captured JPEG is owned by the capture session's ledger, which sweeps it separately.
        // Teardown touching the filesystem here would delete a file the ledger still accounts for.
        assertFalse("teardown must not delete files", body.contains("delete"))
        assertFalse("teardown must not write files", body.contains("writeJpeg"))
        assertFalse("teardown must never persist", body.contains("repository"))
    }

    // --- the stage it returns to is genuinely a fresh camera ---------------------------------------

    @Test
    fun cameraReadyIsAFreshSurfaceWithNothingRetained() {
        // Pure half of the contract: whatever the activity holds, the stage the teardown returns to
        // must not claim an image is on screen, must not be busy, and must not be editable.
        assertFalse(
            "CameraReady must not require a retained image",
            MainScanWorkflow.requiresVisibleImage(MainScanStage.CameraReady)
        )
        assertFalse(
            "CameraReady must not be busy",
            MainScanWorkflow.isBusy(MainScanStage.CameraReady)
        )
        assertFalse(
            "CameraReady must not be editable",
            MainScanWorkflow.allowsPolygonEditing(MainScanStage.CameraReady)
        )
        assertFalse(
            "a torn-down visit must not be able to leave anything in the library",
            MainScanWorkflow.allowsPersistence(MainScanStage.CameraReady)
        )
        assertFalse(
            "Back from a fresh camera has nothing to confirm",
            MainScanWorkflow.backNeedsConfirmation(MainScanStage.CameraReady)
        )
    }

    /**
     * The reducer half: a confirmed discard opens a NEW visit with no pending page and no ledger, so
     * nothing from the torn-down visit can be adopted by the one that follows.
     */
    @Test
    fun aConfirmedDiscardOpensACleanVisit() {
        val visit = MainScanCaptureFlow.beginVisit(null)
        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(visit)!!
        val captured = MainScanCaptureFlow.captureSucceeded(
            state = capturing,
            ticket = ticket,
            uri = "file:///files/main_scan_capture/page.jpg",
            source = MainScanPageSource.CAMERA
        )
        assertTrue("precondition: a page exists to discard", captured.hasPendingPage)

        val next = MainScanCaptureFlow.confirmDiscard(captured)
        assertTrue("the discarded page must not survive", next.pendingPage == null)
        assertTrue("the file ledger must not survive", next.ownedUris.isEmpty())
        assertTrue("no seed may cross the visit boundary", next.frozenCropSeed == null)
        assertTrue("the new visit must be capturable immediately", next.canCapture)
        assertTrue(
            "the visit id must advance so in-flight results cannot publish",
            next.sessionId > captured.sessionId
        )
    }

    // --- the authoritative artifact's publication transaction ------------------------------------
    //
    // The artifact is the only persistable output of this pipeline, and its whole value depends on
    // WHEN each step happens. Publishing before validating, deleting before publishing, or clearing
    // after a suspension all produce a state in which the UI is correct and the file a future
    // Confirm would write is not. Each of those orderings is asserted below on the real source.

    @Test
    fun theTargetPathsAreOwnedBeforeAnythingIsWritten() {
        val body = functionBody(activitySource(), "renderMainScanAuthoritative")
        val ledger = body.indexOf("MainScanCaptureFlow.withOwnedUri(")
        val render = body.indexOf("MainScanCaptureProcessor.renderAuthoritative(")
        assertTrue("both target paths must enter the ledger", ledger >= 0)
        assertTrue("the render must be reached", render >= 0)
        assertTrue(
            "a path created outside the ledger is a path no sweep can find, and the write is " +
                "exactly the window a failure lands in",
            ledger < render
        )
    }

    @Test
    fun supersededFilesAreDeletedOnlyAfterTheReplacementIsPublished() {
        val body = functionBody(activitySource(), "renderMainScanAuthoritative")
        val publish = body.indexOf("mainScanAuthoritative = outcome.artifact")
        val delete = body.indexOf("sweepMainScanAuthoritativeFiles(")
        assertTrue("the new artifact must be published", publish >= 0)
        assertTrue("the superseded artifact must be retired", delete >= 0)
        assertTrue(
            "deleting first would leave the field pointing at files that no longer exist if the " +
                "publication never happened",
            publish < delete
        )
    }

    @Test
    fun aFailedReplacementLeavesTheExistingArtifactAlone() {
        val body = functionBody(activitySource(), "renderMainScanAuthoritative")
        val failureBranch = body.substringAfter("is MainScanRenderOutcome.NonAuthoritative")
        assertTrue("the failure branch must exist", failureBranch.isNotEmpty())
        assertFalse(
            "a failed render must not touch the published artifact — the previous one is still " +
                "valid and is still the thing a Confirm would save",
            failureBranch.contains("mainScanAuthoritative = ")
        )
        assertTrue(
            "the failed candidate's own files must still be cleaned",
            failureBranch.contains("sweepMainScanOwnedUris(")
        )
    }

    @Test
    fun theAuthoritativeFieldIsAssignedOnlyInTheThreeApprovedPlaces() {
        // Publication, Back, and teardown. A fourth assignment anywhere would be a way for the
        // field to be set without the transaction that makes it trustworthy.
        val source = activitySource()
        assertEquals(
            "mainScanAuthoritative may only be published once and cleared twice",
            4,
            occurrences(source, "mainScanAuthoritative = ")
        )
        assertTrue(
            functionBody(source, "renderMainScanAuthoritative")
                .contains("mainScanAuthoritative = outcome.artifact")
        )
        assertTrue(
            functionBody(source, "backFromMainScanReview").contains("mainScanAuthoritative = null")
        )
        assertTrue(
            functionBody(source, "clearMainScanPipeline").contains("mainScanAuthoritative = null")
        )
    }

    @Test
    fun theArtifactIsNeverConstructedOutsideTheValidatedRender() {
        // The type validates its own invariants, but only the render has done the work that makes
        // them meaningful — the files exist, they decode at the expected size, and the decode was
        // full resolution. Building one anywhere else would satisfy the constructor and mean nothing.
        assertEquals(
            0,
            occurrences(activitySource(), "MainScanAuthoritativeArtifact(")
        )
    }

    @Test
    fun backInvalidatesAuthorityBeforeAnyIo() {
        val source = activitySource()
        val body = functionBody(source, "backFromMainScanReview")
        val clear = body.indexOf("mainScanAuthoritative = null")
        val sweep = body.indexOf("sweepMainScanAuthoritativeFiles(")
        assertTrue("Back must clear the artifact", clear >= 0)
        assertTrue("Back must clean the stale files", sweep >= 0)
        assertTrue(
            "the artifact describes a crop the user is about to change, so authority must go " +
                "first — before anything that could suspend",
            clear < sweep
        )
        assertFalse(
            "Back must be synchronous: a suspension before the clear is a window in which a stale " +
                "full-resolution page is still persistable",
            source.contains("suspend fun backFromMainScanReview(")
        )
        assertTrue(
            "the stale preview must go with it",
            body.contains("releaseMainScanDerivedImages()")
        )
    }

    @Test
    fun theAuthoritativeTransactionPersistsNothing() {
        val body = functionBody(activitySource(), "renderMainScanAuthoritative")
        assertFalse("the transaction must never reach the database", body.contains("repository"))
        assertFalse("no document may be created here", body.contains("viewModel.save"))
        assertFalse("no stage beyond review may be entered", body.contains("MainScanStage."))
    }

    // --- persistence is still unreachable --------------------------------------------------------

    @Test
    fun postReviewStageWritesAreConstrainedToTheirExactSaveFunctions() {
        val activity = activitySource()
        val contracts = listOf(
            Triple(
                "mainScanStage = MainScanStage.Confirming",
                "confirmMainScan",
                "Confirming"
            ),
            Triple(
                "transitionMainScanStage(MainScanStage.Persisting)",
                "persistMainScanArtifact",
                "Persisting"
            ),
            Triple(
                "transitionMainScanStage(MainScanStage.Completed)",
                "retainMainScanCompletion",
                "Completed"
            )
        )

        for ((write, owner, stage) in contracts) {
            assertEquals(
                "$stage must have exactly one production write",
                1,
                mainSourceFiles().sumOf { file -> occurrences(file.readText(), write) }
            )
            assertEquals(
                "$stage may be written only inside $owner",
                1,
                occurrences(functionBody(activity, owner), write)
            )
        }
        assertEquals(0, occurrences(activity, "mainScanStage = MainScanStage.Persisting"))
        assertEquals(0, occurrences(activity, "mainScanStage = MainScanStage.Completed"))
    }

    @Test
    fun completedAndDiscardedHaveExplicitTerminalRenderingAndCannotReachCropEditor() {
        val stageRendering = blockBody(appSource(), "when (host.mainScanStage)")
        val cropFallback = stageRendering.indexOf("else -> MainScanCropEditorScreen(")
        assertTrue("the ordinary editing fallback must remain present", cropFallback >= 0)

        for (stage in listOf("Completed", "Discarded")) {
            val terminalBranch = stageRendering.indexOf("MainScanStage.$stage")
            assertTrue("$stage must have an explicit stage branch", terminalBranch >= 0)
            assertTrue(
                "$stage must be handled before the Crop editor fallback",
                terminalBranch < cropFallback
            )
        }
        assertTrue(
            "terminal stages must reuse the neutral processing presentation",
            stageRendering.substringBefore("else -> MainScanCropEditorScreen(")
                .contains("MainScanProcessingScreen(")
        )
    }

    @Test
    fun thePersistenceGateStillNamesOnlyPersisting() {
        for (stage in MainScanStage.entries) {
            assertEquals(
                "only Persisting may allow persistence",
                stage == MainScanStage.Persisting,
                MainScanWorkflow.allowsPersistence(stage)
            )
        }
        assertFalse(
            "the review this slice ends at must not allow persistence",
            MainScanWorkflow.allowsPersistence(MainScanStage.EnhancementReview)
        )
    }

    // --- helpers for the additions above ----------------------------------------------------------

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    private fun mainSourceFiles(): List<File> {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = candidates.firstOrNull { it.isDirectory }
        assertNotNull("could not locate the main source set from ${File("").absolutePath}", root)
        return root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }
}
