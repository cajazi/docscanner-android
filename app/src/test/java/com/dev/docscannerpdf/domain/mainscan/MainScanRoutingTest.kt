package com.dev.docscannerpdf.domain.mainscan

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the primary Scan action cannot open the incomplete app-owned Main Scanner in a release
 * build — both as a decision ([MainScanRouting]) and as a source-level contract, because the
 * decision is only as good as its call sites.
 */
class MainScanRoutingTest {

    // --- The decision ---------------------------------------------------------------------------

    @Test
    fun releaseAlwaysRoutesToMlKit() {
        assertEquals(
            PrimaryScanTarget.ML_KIT,
            MainScanRouting.primaryScanTarget(isDebugBuild = false)
        )
    }

    @Test
    fun debugRoutesToTheAppOwnedMainScanner() {
        assertEquals(
            PrimaryScanTarget.MAIN_SCANNER,
            MainScanRouting.primaryScanTarget(isDebugBuild = true)
        )
    }

    @Test
    fun theDecisionIsTotalOverItsOnlyInputAndHasNoOtherOutcome() {
        // Exhaustive over the whole input domain: a Boolean has two values and both are pinned
        // above, so no runtime condition exists that could produce MAIN_SCANNER in release.
        val outcomes = listOf(true, false).map { MainScanRouting.primaryScanTarget(it) }
        assertEquals(listOf(PrimaryScanTarget.MAIN_SCANNER, PrimaryScanTarget.ML_KIT), outcomes)
        assertEquals(2, PrimaryScanTarget.entries.size)
    }

    // --- The source contract --------------------------------------------------------------------

    private fun mainSource(relative: String): File {
        // Unit tests run with the module directory as the working directory; tolerate the project
        // root too so the contract holds however the suite is invoked.
        val candidates = listOf(
            File("src/main/java/com/dev/docscannerpdf/$relative"),
            File("app/src/main/java/com/dev/docscannerpdf/$relative")
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull("could not locate $relative from ${File("").absolutePath}", found)
        return found!!
    }

    private fun mainSourceTree(): List<File> {
        val roots = listOf(File("src/main/java"), File("app/src/main/java"))
        val root = roots.firstOrNull { it.isDirectory }
        assertNotNull("could not locate the main source tree", root)
        return root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    @Test
    fun theOnlyCallSiteRoutesThroughTheBuildTypeDecision() {
        val declaringFile = "MainActivity.kt"
        val callers = mainSourceTree()
            .filter { it.name != declaringFile && it.readText().contains("startMainScanCapture(") }
            .map { it.name }
            .toSet()

        assertEquals(
            "startMainScanCapture must have exactly one call site, in the routed Scan action",
            setOf("DocScannerApp.kt"),
            callers
        )

        val host = mainSource("ui/DocScannerApp.kt").readText()
        assertTrue(
            "the Scan action must route through the build-type decision",
            host.contains("MainScanRouting.primaryScanTarget(BuildConfig.DEBUG)")
        )
        assertTrue(
            "the ML Kit branch must remain wired",
            host.contains("PrimaryScanTarget.ML_KIT -> host.startDocumentScanner")
        )
        assertTrue(
            "the Main Scanner branch must be reachable only from the MAIN_SCANNER outcome",
            host.contains("PrimaryScanTarget.MAIN_SCANNER -> host.startMainScanCapture()")
        )
    }

    @Test
    fun noManuallyFlippedFeatureConstantGovernsTheRouting() {
        // The superseded approach — a hand-edited constant defaulting to false — must not come back.
        val offenders = mainSourceTree().filter { file ->
            val text = file.readText()
            text.contains("ROUTE_PRIMARY_SCAN_TO_MAIN_SCANNER") || text.contains("MainScanFeature")
        }
        assertTrue(
            "routing must be build-type driven, not a manual constant: $offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun theMlKitFallbackRemainsIntactAndCallable() {
        val activity = mainSource("MainActivity.kt").readText()
        assertTrue(
            "startDocumentScanner must remain declared and callable",
            activity.contains("internal fun startDocumentScanner(")
        )
        // And it must still be reachable when the app-owned camera cannot initialize.
        assertTrue(
            activity.contains("internal fun onMainScanCameraUnavailable()") &&
                activity.contains("startDocumentScanner(pageLimit = 20)")
        )
    }

    @Test
    fun noUserFacingScannerSelectionSettingWasIntroduced() {
        val settingish = Regex(
            "(?i)(pref|preference|setting|toggle|switch)[A-Za-z]*\\s*[:=(].*" +
                "(mainScanner|useMainScan|scannerChoice|scannerSelection)"
        )
        val offenders = mainSourceTree().filter { settingish.containsMatchIn(it.readText()) }
        assertTrue("no scanner-selection setting may exist: $offenders", offenders.isEmpty())
    }

    @Test
    fun theReferenceDocumentContainsNoLocalAbsolutePaths() {
        val candidates = listOf(File("../docs/main-scanner-reference.md"), File("docs/main-scanner-reference.md"))
        val doc = candidates.firstOrNull { it.isFile }
        assertNotNull("the locked reference document must be part of this change", doc)
        val text = doc!!.readText()
        assertFalse("no Windows drive paths", Regex("[A-Za-z]:\\\\").containsMatchIn(text))
        assertFalse("no user home paths", text.contains("C:/Users"))
        assertFalse("no /home paths", text.contains("/home/"))
        assertFalse("no embedded images", text.contains("]("))
    }
}
