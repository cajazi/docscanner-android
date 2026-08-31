package com.dev.docscannerpdf.domain.mainscan

import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Back out of a captured Main Scanner page, and the filter work that Back supersedes.
 *
 * ## The two defects this pins
 *
 * **One decision, not three.** The on-screen arrow, Android system Back and predictive Back were
 * separate implementations. The visible arrow in the enhancement review stepped back to crop
 * editing; system Back, routed through `handleSystemBack`, reached `requestMainScanDiscard()` and
 * raised the DISCARD dialog instead — so the same gesture meant "keep my page, let me re-crop it" or
 * "throw my page away" depending on which one the user happened to use. The locked reference has one
 * Back. There is now one host decision, `onMainScanPageBack`, and the only table it consults is
 * [MainScanWorkflow.backTarget].
 *
 * **A render that outlives the Back that superseded it.** Leaving the review returned to crop
 * editing while the review filter re-render was still in flight, and that coroutine ends in a plain
 * assignment publishing an authoritative artifact. So work belonging to a surface the user had
 * already left could hand a confirmable, full-resolution page to a crop they had gone back to
 * change. Cancellation alone does not close it — a coroutine can already be past its last suspension
 * point when it is cancelled, and nothing after that point suspends again — so the fix is both
 * halves: [MainScanReviewRender] gives the render an identity Back can cancel WITHOUT reaching the
 * shared job slot that Confirm persistence uses, and
 * [MainScanWorkflow.allowsAuthoritativePublication] refuses the publication once the visible stage
 * has moved.
 *
 * ## Why part of this is a source contract
 *
 * The wiring lives on `MainActivity` and in `DocScannerApp`, which this JVM-only module (JUnit, no
 * Robolectric, no mocking framework) cannot instantiate. So everything that CAN be executed is
 * executed — the pure stage rules, the tracker, and the publication gate under a real cancelled
 * coroutine — and only the plumbing that binds them to the Activity is asserted against the real
 * source, on the exact function bodies, in the idiom [MainScanPipelineTeardownTest] already uses.
 */
class MainScanBackDecisionTest {

    // --- A. the back decision, as a pure rule -----------------------------------------------------

    @Test
    fun theOnlyInWorkflowBackStepIsReviewToCropEditing() {
        // The whole table, asserted as a table: any stage that grew a second in-workflow Back target
        // would be a second policy, and two policies is what this slice removed.
        for (stage in MainScanStage.entries) {
            val expected =
                if (stage == MainScanStage.EnhancementReview) MainScanStage.CropEditing else null
            assertEquals(
                "backTarget($stage) must stay the single governed answer",
                expected,
                MainScanWorkflow.backTarget(stage)
            )
        }
    }

    @Test
    fun theReviewBackStepIsItselfALegalTransition() {
        assertTrue(
            "the step Back takes must be one the stage machine admits",
            MainScanWorkflow.canTransition(
                MainScanStage.EnhancementReview,
                MainScanStage.CropEditing
            )
        )
        assertTrue(
            "and it must not be a discard: the polygon and the capture both survive it",
            MainScanWorkflow.backNeedsConfirmation(MainScanStage.EnhancementReview)
        )
    }

    @Test
    fun croppingAndEnhancementPreparingLeaveTheWorkflowRatherThanStepBack() {
        // These two are the visible processing surfaces. They now carry a real Back arrow, and the
        // governed rule is what that arrow does: no in-workflow predecessor, so it resolves to the
        // ordinary discard decision — never a no-op the user can press forever.
        for (stage in listOf(MainScanStage.Cropping, MainScanStage.EnhancementPreparing)) {
            assertNull("$stage has nothing to step back to", MainScanWorkflow.backTarget(stage))
            assertTrue(
                "$stage holds a captured page, so leaving it is a decision the user must confirm",
                MainScanWorkflow.backNeedsConfirmation(stage)
            )
        }
    }

    @Test
    fun confirmingAndPersistingRemainUninterruptible() {
        for (stage in MainScanStage.entries) {
            assertEquals(
                "only the save transaction may consume Back",
                stage == MainScanStage.Confirming || stage == MainScanStage.Persisting,
                stage.blocksMainScanExit()
            )
        }
        assertNull(
            "a save in flight has no Back target either",
            MainScanWorkflow.backTarget(MainScanStage.Confirming)
        )
        assertNull(MainScanWorkflow.backTarget(MainScanStage.Persisting))
    }

    // --- A. the host decision is genuinely one decision --------------------------------------------

    @Test
    fun oneHostDecisionServesTheArrowAndSystemBackAlike() {
        val body = functionBody(activitySource(), "onMainScanPageBack")

        val guard = body.indexOf("if (mainScanStage.blocksMainScanExit()) return")
        assertTrue("the save transaction must be rejected first", guard >= 0)

        val table = body.indexOf("MainScanWorkflow.backTarget(mainScanStage)")
        assertTrue("the decision must consult the governed table", table >= 0)
        assertTrue("and only after the save guard", guard < table)

        val step = body.indexOf("backFromMainScanReview()")
        val leave = body.indexOf("requestMainScanDiscard()")
        assertTrue("a non-null target must take the in-workflow step", step > table)
        assertTrue("a null target must fall through to the discard decision", leave > step)

        for (terminal in listOf("MainScanStage.Completed", "MainScanStage.Discarded")) {
            assertTrue(
                "$terminal must not raise a discard decision about a visit that is already over",
                body.contains(terminal)
            )
        }
    }

    @Test
    fun systemAndPredictiveBackReachThatSameDecisionForACapturedPage() {
        val navigation = navigationSource()
        assertTrue(
            "system Back on a captured page must delegate to the one host decision",
            navigation.contains("mainScanState.pendingPage != null -> onMainScanPageBack()")
        )
        assertFalse(
            "it must no longer request a discard directly — that was the divergence: the arrow " +
                "returned to crop editing while the gesture threw the page away",
            navigation.contains("mainScanState.pendingPage != null -> requestMainScanDiscard()")
        )
        // Predictive Back is the same registration: the androidx BackHandler is an
        // OnBackPressedCallback and the system drives the predictive gesture through it, so pinning
        // the single dispatch pins the predictive path with it.
        val app = appSource()
        assertEquals(
            "there must be exactly one system-Back registration for the whole host",
            1,
            occurrences(app, "BackHandler(")
        )
        assertTrue("and it must dispatch through handleSystemBack", app.contains("host.handleSystemBack()"))
    }

    @Test
    fun everyBackAffordanceOnTheCapturedPageSurfaceIsTheSameCall() {
        val stageRendering = blockBody(appSource(), "when (host.mainScanStage)")

        // The crop host, both processing stages, the review and the editor fallback.
        assertEquals(
            "every Back affordance on this surface must be the one host decision",
            5,
            occurrences(stageRendering, "onBack = host::onMainScanPageBack")
        )
        assertFalse(
            "the review must not keep its own private Back implementation",
            stageRendering.contains("onBack = host::backFromMainScanReview")
        )
        assertFalse(
            "and no surface may reach past the decision straight into the discard request",
            stageRendering.contains("onBack = host::requestMainScanDiscard")
        )
    }

    @Test
    fun theProcessingSurfacesExposeARealBackRatherThanNone() {
        // They had no arrow at all, which is how system Back and the visible surface came to
        // disagree about whether Back meant anything there.
        val stageRendering = blockBody(appSource(), "when (host.mainScanStage)")
        for (label in listOf("Cropping image", "Enhancing image")) {
            val branch = stageRendering.substringAfter(label).substringBefore(")")
            assertTrue(
                "the $label surface must expose the real Back affordance",
                branch.contains("onBack = host::onMainScanPageBack")
            )
        }
        assertTrue(
            "and the processing surface must only draw one when it is actually given one",
            processingScreenSource().contains("onBack: (() -> Unit)? = null")
        )
    }

    // --- B. the filter render has an identity Back can cancel, and only that identity --------------

    @Test
    fun cancellingTheReviewRenderCannotReachAnUntrackedJob() = runBlocking {
        val tracker = MainScanReviewRender()
        val persistenceMayFinish = CompletableDeferred<Unit>()

        // Stands in for what the SHARED processing slot can be holding: a save. Back must have no
        // way to reach it, which is the whole reason the review render is tracked separately.
        val persistence = launch(Dispatchers.Default) { persistenceMayFinish.await() }
        val renderStarted = CompletableDeferred<Unit>()
        val render = launch(Dispatchers.Default) {
            renderStarted.complete(Unit)
            awaitCancellation()
        }
        renderStarted.await()
        tracker.track(render)

        assertTrue("Back must find the review render", tracker.cancelActive())
        render.join()
        assertTrue("the review render must be cancelled", render.isCancelled)
        assertTrue("the untracked save must be untouched", persistence.isActive)

        assertFalse("a second Back must find nothing to cancel", tracker.cancelActive())
        assertTrue("and still must not reach the save", persistence.isActive)

        persistenceMayFinish.complete(Unit)
        persistence.join()
        assertFalse("the save must be allowed to finish normally", persistence.isCancelled)
    }

    @Test
    fun anUntrackedReviewCancelsNothingAtAll() {
        // Back out of a review whose filter was never changed: there is no render, and the call must
        // not invent one to cancel.
        assertFalse(MainScanReviewRender().cancelActive())
    }

    // --- B. a superseded render cannot publish -----------------------------------------------------

    @Test
    fun thePublicationGateAdmitsOnlyTheTwoStagesARenderIsStartedFrom() {
        for (stage in MainScanStage.entries) {
            val expected = stage == MainScanStage.EnhancementPreparing ||
                stage == MainScanStage.EnhancementReview
            assertEquals(
                "allowsAuthoritativePublication($stage)",
                expected,
                MainScanWorkflow.allowsAuthoritativePublication(stage)
            )
        }
        assertFalse(
            "the stage Back lands on must refuse the render it superseded",
            MainScanWorkflow.allowsAuthoritativePublication(
                MainScanWorkflow.backTarget(MainScanStage.EnhancementReview)!!
            )
        )
    }

    @Test
    fun aFilterRenderCancelledByBackCannotPublishAfterwards() = runBlocking {
        // The real hazard shape: the render is cancelled while suspended, but the publication that
        // follows its last suspension point is plain code and still executes. The gate is what
        // refuses it.
        val tracker = MainScanReviewRender()
        var visibleStage = MainScanStage.EnhancementReview
        val renderStarted = CompletableDeferred<Unit>()
        val publicationAdmitted = CompletableDeferred<Boolean>()

        val render = launch(Dispatchers.Default) {
            renderStarted.complete(Unit)
            try {
                awaitCancellation()
            } catch (_: CancellationException) {
                // Past the last suspension point. Nothing below suspends, so cancellation alone
                // cannot stop the assignment that used to happen here.
            }
            publicationAdmitted.complete(
                currentCoroutineContext().isActive &&
                    MainScanWorkflow.allowsAuthoritativePublication(visibleStage)
            )
        }
        renderStarted.await()
        tracker.track(render)

        // Back, in the production order: cancel the review render, then move the stage.
        assertTrue("Back must find the render this selection started", tracker.cancelActive())
        visibleStage = MainScanStage.CropEditing

        assertFalse(
            "a render superseded by Back must not publish an authoritative artifact",
            publicationAdmitted.await()
        )
        render.join()
    }

    @Test
    fun aLiveRenderIsStillRefusedOnceTheVisibleStageHasMovedOn() = runBlocking {
        // The other half, isolated: nothing cancelled this render at all. If the gate depended only
        // on cancellation, this is the case that would still publish onto a crop the user went back
        // to change.
        var visibleStage = MainScanStage.EnhancementReview
        val renderMayFinish = CompletableDeferred<Unit>()
        val publicationAdmitted = CompletableDeferred<Boolean>()

        val render = launch(Dispatchers.Default) {
            renderMayFinish.await()
            publicationAdmitted.complete(
                currentCoroutineContext().isActive &&
                    MainScanWorkflow.allowsAuthoritativePublication(visibleStage)
            )
        }

        visibleStage = MainScanStage.CropEditing
        renderMayFinish.complete(Unit)

        assertFalse(
            "the surface moved on, so there is nothing this render may publish onto",
            publicationAdmitted.await()
        )
        render.join()
        assertFalse("and it was never cancelled — the gate alone refused it", render.isCancelled)
    }

    @Test
    fun anUnsupersededRenderStillPublishesNormally() {
        // The gate must not become a way to lose good work: the ordinary path is still admitted.
        assertTrue(
            MainScanWorkflow.allowsAuthoritativePublication(MainScanStage.EnhancementPreparing)
        )
        assertTrue(MainScanWorkflow.allowsAuthoritativePublication(MainScanStage.EnhancementReview))
    }

    // --- B. the wiring that binds those two to the pipeline ----------------------------------------

    @Test
    fun theReviewRenderIsTrackedWhenAFilterSelectionStartsIt() {
        val body = functionBody(activitySource(), "selectMainScanFilter")
        val started = body.indexOf("mainScanVisit.processingScope.launch")
        val track = body.indexOf("mainScanVisit.reviewRender.track(renderJob)")
        assertTrue("the filter render must be launched", started >= 0)
        assertTrue("and recorded under the review own handle", track > started)
        assertTrue(
            "the tracked job must be the very job that was launched",
            body.contains("mainScanProcessingJob = renderJob")
        )
    }

    @Test
    fun backCancelsTheReviewRenderBeforeTheStageMoves() {
        val body = functionBody(activitySource(), "backFromMainScanReview")
        val cancel = body.indexOf("mainScanVisit.reviewRender.cancelActive()")
        val clear = body.indexOf("mainScanAuthoritative = null")
        val stageWrite = body.indexOf("mainScanStage = MainScanStage.CropEditing")
        assertTrue("Back must cancel the review render", cancel >= 0)
        assertTrue("and do it before authority is revoked", cancel < clear)
        assertTrue("and before crop editing becomes the visible stage", cancel < stageWrite)
        for (reach in listOf("mainScanProcessingJob?.cancel()", "mainScanProcessingJob = ")) {
            assertFalse(
                "Back must NOT touch the shared processing slot through `$reach`: that slot can " +
                    "be holding the Confirm persistence, and persistence is not interruptible",
                body.contains(reach)
            )
        }
    }

    @Test
    fun theSaveIsNeverReachableThroughTheReviewHandle() {
        val activity = activitySource()
        for (name in listOf("confirmMainScan", "persistMainScanArtifact")) {
            assertFalse(
                "$name must never be tracked as a review render",
                functionBody(activity, name).contains("reviewRender")
            )
        }
        assertEquals(
            "only the filter selection may track a render",
            1,
            occurrences(activity, "reviewRender.track(")
        )
    }

    @Test
    fun thePublicationTransactionRefusesBeforeItPublishesOrRetiresAnything() {
        val body = functionBody(activitySource(), "renderMainScanAuthoritative")
        val gate = body.indexOf("MainScanWorkflow.allowsAuthoritativePublication(mainScanStage)")
        val liveness = body.indexOf("currentCoroutineContext().isActive")
        val publish = body.indexOf("mainScanAuthoritative = outcome.artifact")
        val retire = body.indexOf("sweepMainScanAuthoritativeFiles(")

        assertTrue("the transaction must check its own liveness", liveness >= 0)
        assertTrue("and the workflow publication gate", gate >= 0)
        assertTrue("both must be checked before anything is published", gate < publish)
        assertTrue("and before the replaced artifact is retired", gate < retire)

        val refusal = body.substring(gate, publish)
        assertTrue(
            "a refused render must still clean its own two candidate paths, through the owned-file " +
                "contract — refusing must not become a leak",
            refusal.contains("sweepMainScanOwnedUris(setOf(croppedUri, enhancedUri))")
        )
        assertFalse(
            "and must publish nothing on its way out",
            refusal.contains("mainScanAuthoritative = ")
        )
    }

    @Test
    fun theVisitTeardownStillForgetsTheReviewRender() {
        assertTrue(
            "a tracked job belonging to a destroyed page must not survive the visit",
            functionBody(activitySource(), "clearMainScanPipeline")
                .contains("mainScanVisit.reviewRender.cancelActive()")
        )
        assertTrue(
            "and the retained owner must release it when the visit owner goes away",
            functionBody(storeSource(), "onCleared").contains("reviewRender.cancelActive()")
        )
    }

    // --- helpers -----------------------------------------------------------------------------------

    private fun sourceOf(relative: String): String {
        val candidates = listOf(File("src/$relative"), File("app/src/$relative"))
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull("could not locate $relative from ${File("").absolutePath}", found)
        return found!!.readText()
    }

    private fun activitySource(): String =
        sourceOf("main/java/com/dev/docscannerpdf/MainActivity.kt")

    private fun appSource(): String =
        sourceOf("main/java/com/dev/docscannerpdf/ui/DocScannerApp.kt")

    private fun navigationSource(): String =
        sourceOf("main/java/com/dev/docscannerpdf/navigation/MainNavigation.kt")

    private fun storeSource(): String =
        sourceOf("main/java/com/dev/docscannerpdf/domain/mainscan/MainScanVisitStore.kt")

    private fun processingScreenSource(): String =
        sourceOf("main/java/com/dev/docscannerpdf/ui/mainscan/MainScanProcessingScreens.kt")

    private fun functionBody(source: String, name: String): String =
        blockBody(source, "fun $name(")

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

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}
