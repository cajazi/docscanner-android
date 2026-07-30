package com.dev.docscannerpdf.domain.mainscan

import java.io.File
import java.security.MessageDigest
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Integrity of the real-scene fixture pack.
 *
 * These three frames are the exact bounded luma buffers the Main Scanner analyzer produced on a real
 * device — a package on a diagonal tiled floor. They exist so future detector work can be measured
 * against a real scene instead of synthetic shapes on flat backgrounds, which is what allowed a
 * detector failure to survive several rounds of tuning.
 *
 * This suite verifies only that the pack is intact, well formed and correctly annotated. It asserts
 * NOTHING about detector output: the current detector does not find the package on this scene, and
 * encoding either that failure or a desired-but-unmet result here would give a red or misleading
 * suite. The strict accuracy expectations live with the detector correction, not with the fixtures.
 *
 * Ground truth is evaluation-only. Nothing here may influence runtime selection, and
 * [productionSourceNeverReferencesFixtures] enforces that.
 */
class MainScanRealSceneFixtureIntegrityTest {

    private val expectedChecksums = mapOf(
        "package_bright_screen_01.pgm" to "28FCA399B8DB954E",
        "package_bright_screen_02.pgm" to "1A887E8F30AFF52C",
        "package_bright_screen_03.pgm" to "1610FCF2DB7DF4AD"
    )

    private fun resource(name: String) = requireNotNull(
        javaClass.getResourceAsStream("/mainscan/edge-fixtures/$name")
    ) { "missing fixture resource $name" }

    // --- the pack itself ---------------------------------------------------------------------------

    @Test
    fun exactlyThreeFixtureFramesArePresent() {
        assertEquals(3, MainScanRealSceneFixtures.frameNames.size)
        assertEquals(expectedChecksums.keys.sorted(), MainScanRealSceneFixtures.frameNames.sorted())
    }

    @Test
    fun everyFixtureMatchesItsLockedChecksum() {
        for ((name, expected) in expectedChecksums) {
            val bytes = resource(name).use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            val hex = digest.joinToString("") { "%02X".format(it) }.take(expected.length)
            assertEquals("checksum drift in $name", expected, hex)
        }
    }

    @Test
    fun everyFixtureIsAWellFormedEightBitPgmOfTheProductionAnalyzerSize() {
        for (name in MainScanRealSceneFixtures.frameNames) {
            val header = resource(name).use { input ->
                val head = ByteArray(20)
                input.read(head)
                String(head, Charsets.US_ASCII)
            }
            assertTrue("$name is not a binary PGM: ${header.take(2)}", header.startsWith("P5"))
            assertTrue("$name is not 8-bit", header.contains("255"))

            val frame = MainScanRealSceneFixtures.load(name)
            assertEquals("$name width", 240, frame.width)
            assertEquals("$name height", 180, frame.height)
            assertEquals("$name pixel count", 240 * 180, frame.luma.size)
            assertTrue("$name has out-of-range luma", frame.luma.all { it in 0..255 })
        }
    }

    @Test
    fun fixtureOrderingAndParsingAreDeterministic() {
        assertEquals(MainScanRealSceneFixtures.frameNames, MainScanRealSceneFixtures.frameNames)
        for (name in MainScanRealSceneFixtures.frameNames) {
            val first = MainScanRealSceneFixtures.load(name)
            val second = MainScanRealSceneFixtures.load(name)
            assertEquals(first.width, second.width)
            assertEquals(first.height, second.height)
            assertTrue("$name parsed differently twice", first.luma.contentEquals(second.luma))
        }
    }

    @Test
    fun metadataReferencesOnlyExistingFixtures() {
        val json = resource("package_bright_screen_expected.json").use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        for (name in MainScanRealSceneFixtures.frameNames) {
            assertTrue("metadata does not mention $name", json.contains(name))
        }
        // And every .pgm the metadata names must actually be loadable.
        Regex("package_bright_screen_\\d+\\.pgm").findAll(json).map { it.value }.distinct()
            .forEach { MainScanRealSceneFixtures.load(it) }
    }

    // --- ground truth ------------------------------------------------------------------------------

    @Test
    fun groundTruthIsAValidConvexQuadInsideTheFrame() {
        val corners = MainScanRealSceneFixtures.corners(MainScanRealSceneFixtures.packageQuad)
        assertEquals(4, corners.size)

        for (corner in corners) {
            assertTrue("corner outside frame: $corner", corner.x in 0f..1f && corner.y in 0f..1f)
        }

        // Consistent winding: every cross product shares one sign, which is convexity.
        var sign = 0
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val c = corners[(i + 2) % 4]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            val current = if (cross > 0f) 1 else -1
            if (sign == 0) sign = current
            assertEquals("ground truth is not convex / winding flips", sign, current)
        }

        // Shoelace area: nonzero, and a plausible share of the frame for a hand-held object.
        var area = 0f
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            area += a.x * b.y - b.x * a.y
        }
        val normalized = abs(area) / 2f
        assertTrue("implausible ground-truth area $normalized", normalized > 0.02f && normalized < 0.9f)
    }

    @Test
    fun groundTruthParsingIsDeterministic() {
        assertEquals(MainScanRealSceneFixtures.packageQuad, MainScanRealSceneFixtures.packageQuad)
        assertEquals(
            0f,
            MainScanRealSceneFixtures.meanCornerError(
                MainScanRealSceneFixtures.packageQuad,
                MainScanRealSceneFixtures.packageQuad
            ),
            1e-6f
        )
    }

    // --- containment guards ------------------------------------------------------------------------

    /**
     * The fixtures and their annotation must never reach production. A detector that can see its own
     * ground truth is not being measured, it is being told the answer.
     */
    @Test
    fun productionSourceNeverReferencesFixtures() {
        val main = productionSourceRoot() ?: return
        val forbidden = listOf(
            "package_bright_screen",
            "edge-fixtures",
            "MainScanRealSceneFixtures",
            "MainScanFixtureExporter"
        )
        val offenders = main.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                val text = file.readText()
                forbidden.firstOrNull { text.contains(it) }?.let { "${file.name} contains '$it'" }
            }
            .toList()
        assertTrue("production source references fixtures: $offenders", offenders.isEmpty())
    }

    @Test
    fun noFixtureExporterOrCameraHookExistsOnThisBranch() {
        val main = productionSourceRoot() ?: return
        val exporters = main.walkTopDown()
            .filter { it.isFile && it.name.contains("FixtureExporter") }
            .map { it.name }
            .toList()
        assertTrue("fixture exporter present: $exporters", exporters.isEmpty())

        val controller = main.walkTopDown()
            .firstOrNull { it.name == "MainScannerCameraController.kt" }
        if (controller != null) {
            val text = controller.readText()
            assertTrue("controller has a fixture hook", !text.contains("Fixture"))
        }
    }

    @Test
    fun fixtureNamesAndMetadataCarryNoPrivateContent() {
        // Filenames describe the scene, not a person, place, account or document.
        for (name in MainScanRealSceneFixtures.frameNames) {
            assertTrue("unexpected fixture name $name", Regex("^package_bright_screen_\\d{2}\\.pgm$").matches(name))
        }
        val json = resource("package_bright_screen_expected.json").use {
            it.readBytes().toString(Charsets.UTF_8)
        }
        for (marker in listOf("@", "http://", "https://", "C:\\", "/Users/", "IMEI", "serial")) {
            assertTrue("metadata leaks '$marker'", !json.contains(marker))
        }
    }

    /** The module's `src/main` tree, or null when the test's working directory is unexpected. */
    private fun productionSourceRoot(): File? {
        val candidates = listOf(File("src/main/java"), File("app/src/main/java"))
        return candidates.firstOrNull { it.isDirectory }
    }
}
