package com.dev.docscannerpdf.domain.mainscan

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the Main Scanner visit is owned by a RETAINED store rather than by the Activity, so an
 * Activity recreation or a Compose remount inside a living process cannot silently start a new
 * visit over a captured page the user still has.
 *
 * ## The defect
 *
 * Every field of a visit — the pending page, the owned-file ledger, the stage, the polygon, the
 * previews, the authoritative artifact and the job advancing them — was an activity-local
 * `mutableStateOf`. A configuration change, a theme or locale switch, a foldable size change or the
 * return from an app-lock unlock recreates the Activity, and the recreated one started from the
 * field initialisers: session id back to zero, no pending page, an EMPTY ledger. The captured JPEG
 * was still on disk and nothing in memory referenced it, so it could be neither finished nor swept —
 * and the crop surface's page-keyed effect immediately re-entered preparation, discarding whatever
 * the user had already done.
 *
 * ## What this test can and cannot prove
 *
 * This is a SOURCE CONTRACT, exactly like [MainScanPipelineTeardownTest] and for the same reason:
 * this module's unit tests run on the plain JVM with JUnit only — no Robolectric, no mocking
 * framework — so neither `MainActivity` nor an AndroidX `ViewModel` can be instantiated here.
 *
 * So it proves the OWNERSHIP ARCHITECTURE: that the visit is declared on the retained store, that
 * the activity only forwards to it, that the processing job runs in the visit's scope rather than
 * the activity's, that the store cannot touch the filesystem, and that crop preparation is guarded
 * before it can destroy anything. It does NOT execute an Android recreation and must not be read as
 * evidence that one was performed — that is device validation, and it is reported separately.
 *
 * It also does not claim process-death survival: nothing here is serialized, and that is deliberate
 * (see [MainScanVisitStore]).
 */
class MainScanVisitSurvivalTest {

    // --- sources under contract -------------------------------------------------------------------

    private fun sourceFile(relative: String): File {
        // Unit tests run with the module directory as the working directory; tolerate the project
        // root too so the contract holds however the suite is invoked.
        val candidates = listOf(File("src/main/java/$relative"), File("app/src/main/java/$relative"))
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull("could not locate $relative from ${File("").absolutePath}", found)
        return found!!
    }

    private fun activitySource(): String =
        sourceFile("com/dev/docscannerpdf/MainActivity.kt").readText()

    private fun storeSource(): String =
        sourceFile("com/dev/docscannerpdf/domain/mainscan/MainScanVisitStore.kt").readText()

    /**
     * The body of [name] in [source], by brace matching from its declaration. Deliberately not a
     * whole-file `contains`: a call must be inside the function that is responsible for it, so
     * moving it elsewhere still fails.
     */
    private fun functionBody(source: String, name: String): String {
        val declaration = source.indexOf("fun $name(")
        assertTrue("$name must exist", declaration >= 0)
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

    /**
     * [source] with comments and string literals blanked out, so an assertion that the store never
     * NAMES a filesystem API cannot be satisfied — or broken — by prose about the filesystem.
     *
     * Coarse by design, and used only on the store, which has no string literals at all.
     */
    private fun codeOnly(source: String): String {
        val withoutBlocks = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL).replace(source, " ")
        val withoutLines = withoutBlocks.lineSequence()
            .joinToString("\n") { it.substringBefore("//") }
        return Regex("\"(\\\\.|[^\"\\\\])*\"").replace(withoutLines, "\"\"")
    }

    /**
     * Activity field -> the retained field it forwards to. This list IS the visit: a Main Scanner
     * field added to the activity later and not added here is a field that will not survive a
     * recreation, which is exactly the class of bug this test exists to prevent.
     */
    private val delegatedVisitFields = listOf(
        "showMainScanCapture" to "captureSurfaceVisible",
        "mainScanState" to "captureState",
        "mainScanStage" to "stage",
        "mainScanWorkingImage" to "workingImage",
        "mainScanCropState" to "cropState",
        "mainScanCroppedImage" to "croppedImage",
        "mainScanEnhancedImage" to "enhancedImage",
        "mainScanAuthoritative" to "authoritative",
        "mainScanAuthoritativeFailure" to "authoritativeFailure",
        "mainScanProcessingJob" to "processingJob"
    )

    // --- the retained owner exists and is the right kind of thing ---------------------------------

    @Test
    fun theVisitIsOwnedByAnActivityScopedRetainedViewModel() {
        val store = storeSource()
        assertTrue(
            "the visit store must be an AndroidX ViewModel — that is what makes it survive an " +
                "Activity recreation without any save/restore of its own",
            store.contains("import androidx.lifecycle.ViewModel") &&
                store.contains("class MainScanVisitStore : ViewModel()")
        )
    }

    @Test
    fun theActivityObtainsTheVisitThroughTheViewModelMechanism() {
        assertTrue(
            "MainActivity must obtain the retained store, not construct one per instance",
            activitySource().contains("private val mainScanVisit: MainScanVisitStore by viewModels()")
        )
    }

    // --- no Main Scanner visit field is activity-local any more -----------------------------------

    @Test
    fun noVisitFieldIsStillDeclaredAsActivityLocalState() {
        val activity = activitySource()
        for ((field, _) in delegatedVisitFields) {
            assertFalse(
                "$field must not be activity-local state: a recreation resets it and the visit is lost",
                activity.contains("$field by mutableStateOf")
            )
        }
        assertFalse(
            "the capture session must not be re-initialised on the activity",
            activity.contains("mutableStateOf(MainScanCaptureState())")
        )
    }

    @Test
    fun everyVisitFieldForwardsToTheRetainedStore() {
        val activity = activitySource()
        val store = codeOnly(storeSource())
        for ((field, retained) in delegatedVisitFields) {
            assertTrue(
                "$field must read from the retained visit",
                activity.contains("get() = mainScanVisit.$retained")
            )
            assertTrue(
                "$field must write to the retained visit",
                activity.contains("mainScanVisit.$retained = value")
            )
            assertTrue(
                "the store must actually declare $retained",
                store.contains("var $retained")
            )
        }
    }

    @Test
    fun theRetainedFieldsKeepComposeSnapshotSemantics() {
        // Forwarding is only equivalent if the retained field is still snapshot state: otherwise a
        // read from composition registers no observation and the surface stops recomposing.
        val store = codeOnly(storeSource())
        for (retained in delegatedVisitFields.map { it.second } - "processingJob") {
            assertTrue(
                "$retained must remain Compose snapshot state",
                store.contains("var $retained by mutableStateOf")
            )
        }
    }

    // --- the capture state, ledger included, is retained as ONE value -----------------------------

    @Test
    fun theWholeCaptureStateIsRetainedAsOneValue() {
        val store = codeOnly(storeSource())
        val activity = activitySource()
        assertTrue(
            "the capture session must be retained whole",
            store.contains("var captureState by mutableStateOf(MainScanCaptureState())")
        )
        // Decomposing it is the failure mode: a restored pending page without the ledger it came
        // with leaves every file the visit wrote unnameable by any sweep.
        for (part in listOf(
            "var sessionId",
            "var pendingPage",
            "var ownedUris",
            "var captureGeneration",
            "var discardConfirmVisible",
            "var importInFlight",
            "var frozenCropSeed"
        )) {
            assertFalse(
                "the capture state must not be split apart into `$part`",
                store.contains(part)
            )
            assertFalse(
                "the capture state must not be split apart into `$part` on the activity either",
                activity.contains("internal $part")
            )
        }
    }

    @Test
    fun theRetainedValueGenuinelyCarriesTheCompleteOwnedUriLedger() {
        // The pure half: what crosses the lifecycle boundary is one MainScanCaptureState, so this
        // proves that value really does carry every file the visit produced — the capture, and both
        // authoritative siblings added during the render.
        val visit = MainScanCaptureFlow.beginVisit(null)
        val (capturing, ticket) = MainScanCaptureFlow.beginCapture(visit)!!
        val captured = MainScanCaptureFlow.captureSucceeded(
            state = capturing,
            ticket = ticket,
            uri = "file:///files/main_scan_capture/page.jpg",
            source = MainScanPageSource.CAMERA
        )
        val rendered = MainScanCaptureFlow.withOwnedUri(
            MainScanCaptureFlow.withOwnedUri(
                captured,
                "file:///files/main_scan_cropped/page.jpg"
            ),
            "file:///files/main_scan_enhanced/page.jpg"
        )

        assertEquals(
            "the single retained value must name every file the visit owns",
            setOf(
                "file:///files/main_scan_capture/page.jpg",
                "file:///files/main_scan_cropped/page.jpg",
                "file:///files/main_scan_enhanced/page.jpg"
            ),
            rendered.ownedUris
        )
        assertNotNull("and the page it owns them for", rendered.pendingPage)
        assertEquals(
            "and the session identity that makes an in-flight result adoptable",
            visit.sessionId,
            rendered.sessionId
        )
        assertEquals(
            "the visit-ending sweep must still be able to find every one of them",
            3,
            MainScanFileOwnership.visitOrphans(rendered, emptySet()).size
        )
    }

    @Test
    fun aWithinProcessRecreationDoesNotOpenANewVisitId() {
        // A NEW session id is what invalidates in-flight captures, so it must be reached only by an
        // explicit visit boundary — never as a side effect of the activity being rebuilt. With the
        // state retained, the only remaining callers are the two that genuinely open or abandon one.
        val activity = activitySource()
        assertEquals(
            "beginVisit may only be called where a visit actually begins or is abandoned",
            2,
            occurrences(activity, "MainScanCaptureFlow.beginVisit(")
        )
        assertTrue(
            functionBody(activity, "startMainScanCapture").contains("MainScanCaptureFlow.beginVisit(")
        )
        assertTrue(
            functionBody(activity, "closeMainScanCapture").contains("MainScanCaptureFlow.beginVisit(")
        )
    }

    // --- the processing job belongs to the visit, not to the activity -----------------------------

    @Test
    fun theProcessingJobIsOwnedByTheRetainedVisitLifetime() {
        val store = codeOnly(storeSource())
        assertTrue(
            "the job must be held by the visit",
            store.contains("var processingJob: Job? = null")
        )
        assertTrue(
            "and must run in the visit's own scope",
            store.contains("val processingScope: CoroutineScope get() = viewModelScope")
        )
        assertFalse(
            "an unmanaged global scope would outlive the visit itself",
            store.contains("GlobalScope")
        )
    }

    @Test
    fun everyStageAdvancingJobRunsInTheRetainedScope() {
        // A retained stage with a lifecycle-cancelled coroutine behind it is the invalid state this
        // slice removes: `Cropping` forever, over a page nothing is working on.
        val activity = activitySource()
        for (name in listOf("prepareMainScanCrop", "rotateMainScanCrop", "advanceMainScanCrop")) {
            val body = functionBody(activity, name)
            assertTrue(
                "$name must launch in the retained visit scope",
                body.contains("mainScanProcessingJob = mainScanVisit.processingScope.launch")
            )
            assertFalse(
                "$name must not be tied to the Activity lifecycle",
                body.contains("lifecycleScope")
            )
        }
    }

    @Test
    fun theRetainedJobIsStillCancelledByTheExistingVisitTeardown() {
        // Retaining the job must not weaken cancellation: discard and close still stop it, and each
        // new stage still supersedes the previous one.
        val activity = activitySource()
        val teardown = functionBody(activity, "clearMainScanPipeline")
        assertTrue(
            "visit teardown must still cancel the retained job",
            teardown.contains("mainScanProcessingJob?.cancel()") &&
                teardown.contains("mainScanProcessingJob = null")
        )
        for (name in listOf("prepareMainScanCrop", "rotateMainScanCrop", "advanceMainScanCrop")) {
            assertTrue(
                "$name must still supersede the job it replaces",
                functionBody(activity, name).contains("mainScanProcessingJob?.cancel()")
            )
        }
        assertTrue(
            "the store must cancel whatever is still running when the visit's owner goes away",
            codeOnly(functionBody(storeSource(), "onCleared")).contains("processingJob?.cancel()")
        )
    }

    @Test
    fun everyMainScannerDeleteIsBoundedByTheVisitRatherThanByTheActivity() {
        // The deletes are SCHEDULED from inside the retained pipeline — a superseded artifact is
        // retired by the render itself — and the retained pipeline now outlives the activity that
        // started it. Left on the activity's scope, a recreation landing in that window cancelled
        // the delete and left full-resolution files behind; for a rejected capture, which no ledger
        // names, it left them behind permanently. The guard does not move: only the lifetime does.
        val activity = activitySource()
        for (name in listOf(
            "sweepMainScanOwnedUris",
            "sweepMainScanSession",
            "onMainScanCaptureSucceeded"
        )) {
            val body = functionBody(activity, name)
            assertFalse(
                "$name schedules a delete that must survive an Activity recreation",
                body.contains("lifecycleScope")
            )
            assertTrue(
                "$name must schedule it in the retained visit scope",
                body.contains("mainScanVisit.processingScope.launch")
            )
        }
        // And the guard is still the only way a file leaves disk.
        val guard = functionBody(activity, "deleteMainScanFileBlocking")
        assertTrue(
            "the pure ownership barrier must still run first",
            guard.contains("MainScanFileOwnership.isOwnedFileUri(")
        )
        assertTrue(
            "and the parsed-path containment barrier must still run too",
            guard.contains("startsWith(filesDirPath)")
        )
    }

    // --- a new visit begins from a torn-down pipeline ----------------------------------------------

    @Test
    fun openingANewVisitTearsDownWhateverTheLastOneRetained() {
        // Resetting the stage used to be free — it was an activity-local field a recreation cleared.
        // Retained, it has to be explicit, and it is not cosmetic: preparation is admitted only from
        // the pre-pipeline stages, so a visit opening on a stale EnhancementReview or Failed would
        // have its brand-new capture refused preparation forever.
        val body = functionBody(activitySource(), "startMainScanCapture")
        assertTrue(
            "a new visit must not inherit the previous visit's stage, polygon or bitmaps",
            body.contains("clearMainScanPipeline()")
        )
    }

    @Test
    fun theStageTeardownLandsOnAStagePreparationIsAdmittedFrom() {
        // The pure half of the coupling above, and the reason the guard cannot deadlock a visit:
        // whatever stage the teardown returns to must be one the crop-preparation gate admits.
        assertTrue(
            "teardown returns to CameraReady, so CameraReady must be preparable",
            MainScanWorkflow.allowsCropPreparation(MainScanStage.CameraReady)
        )
        assertTrue(
            "and it must be a stage with nothing on screen to lose",
            !MainScanWorkflow.requiresVisibleImage(MainScanStage.CameraReady) &&
                !MainScanWorkflow.isBusy(MainScanStage.CameraReady)
        )
    }

    // --- the store cannot touch the filesystem ----------------------------------------------------

    @Test
    fun theVisitStoreOwnsNoFilesystemAuthorityAtAll() {
        // The two-barrier ownership guard is only meaningful while there is exactly one place a
        // Main Scanner file can be removed from. The store remembers; it never reclaims.
        val store = codeOnly(storeSource())
        for (forbidden in listOf(
            "Context",
            "filesDir",
            "File(",
            "delete",
            "mkdirs",
            "listFiles",
            "contentResolver",
            "Uri",
            "sweep",
            "MainScanFileOwnership",
            "repository",
            "Dao",
            "Room"
        )) {
            assertFalse(
                "the visit store must not reference `$forbidden` — the filesystem contract stays " +
                    "on the activity that has filesDir",
                store.contains(forbidden)
            )
        }
    }

    @Test
    fun theVisitStoreClaimsNoProcessDeathSurvival() {
        // This slice covers recreation inside a living process and nothing more. A half-built
        // restore — a ledger naming files that no longer exist — is worse than none.
        val store = codeOnly(storeSource())
        for (forbidden in listOf(
            "SavedStateHandle",
            "Bundle",
            "onSaveInstanceState",
            "onRestoreInstanceState",
            "SharedPreferences",
            "DataStore",
            "Parcelable",
            "Serializable"
        )) {
            assertFalse("the visit store must not attempt $forbidden", store.contains(forbidden))
        }
    }

    @Test
    fun clearingTheStoreReleasesReferencesWithoutRecyclingOrDeleting() {
        val body = codeOnly(functionBody(storeSource(), "onCleared"))
        for (released in listOf(
            "enhancedImage = null",
            "croppedImage = null",
            "workingImage = null",
            "authoritative = null"
        )) {
            assertTrue("onCleared must release $released", body.contains(released))
        }
        assertFalse(
            "recycling a bitmap Compose may still be replaying is a crash runCatching cannot catch",
            body.contains("recycle")
        )
        assertFalse("onCleared must delete nothing", body.contains("delete"))
        assertFalse("onCleared must not persist the visit", body.contains("repository"))
        assertFalse(
            "onCleared is a lifecycle callback, not a user decision — it must not simulate a discard",
            body.contains("Discard")
        )
    }

    // --- crop preparation is guarded before it can destroy anything -------------------------------

    @Test
    fun cropPreparationConsultsTheGuardBeforeAnyDestructiveWork() {
        val body = functionBody(activitySource(), "prepareMainScanCrop")
        val guard = body.indexOf("MainScanWorkflow.allowsCropPreparation(")
        val stageWrite = body.indexOf("mainScanStage = MainScanStage.CropPreparing")
        val cancel = body.indexOf("mainScanProcessingJob?.cancel()")

        assertTrue("preparation must be guarded", guard >= 0)
        assertTrue("preparation must still enter CropPreparing when admitted", stageWrite >= 0)
        assertTrue("preparation must still supersede the previous job when admitted", cancel >= 0)
        assertTrue(
            "regressing the stage is itself the damage — the guard must run before the write",
            guard < stageWrite
        )
        assertTrue(
            "cancelling a valid retained pipeline is the other half of the damage",
            guard < cancel
        )
        assertTrue(
            "a refusal must leave the retained visit exactly as it was",
            body.substring(guard, stageWrite).contains("return")
        )
    }

    // --- the lifecycle and ownership functions stay where they were -------------------------------

    @Test
    fun theProcessingAndTeardownFunctionsRemainOnTheActivity() {
        val activity = activitySource()
        val store = storeSource()
        for (name in listOf(
            "prepareMainScanCrop",
            "advanceMainScanCrop",
            "renderMainScanAuthoritative",
            "backFromMainScanReview",
            "clearMainScanPipeline",
            "closeMainScanCapture",
            "confirmMainScanDiscard",
            "sweepMainScanSession",
            "sweepMainScanOwnedUris",
            "sweepMainScanAuthoritativeFiles",
            "deleteMainScanFileBlocking"
        )) {
            assertTrue("$name must remain declared in MainActivity", activity.contains("fun $name("))
            assertFalse(
                "$name must not move into the retained store",
                store.contains("fun $name(")
            )
        }
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
