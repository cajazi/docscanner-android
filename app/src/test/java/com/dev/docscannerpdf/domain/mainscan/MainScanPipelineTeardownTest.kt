package com.dev.docscannerpdf.domain.mainscan

import java.io.File
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
            "mainScanWorkingImage"
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
}
