package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import com.dev.docscannerpdf.domain.detection.LumaFrame
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accuracy gate for automatic document-boundary detection on a real scene.
 *
 * ## Why this suite exists
 *
 * The fixture pack in `test/resources/mainscan/edge-fixtures` has carried an annotated outer
 * boundary for the package scene for some time, but nothing ever ran the shipping detector against
 * it: [MainScanRealSceneFixtureIntegrityTest] deliberately asserts only that the pack is intact, and
 * every other detector suite works on synthetic shapes. Accuracy was therefore unmeasured, and
 * rounds of tuning were evaluated by eye — which is how changes that made this real scene worse got
 * as far as they did.
 *
 * This suite closes that gap. It runs the real production entry point,
 * [MainScanDocumentFinder.find], over the three committed real-device luma frames and measures the
 * output against the committed ground truth. From here on, detector work has a number.
 *
 * ## What the ceiling means
 *
 * The ceiling is [PRODUCT_TARGET_MEAN_CORNER_ERROR] — the tolerance the fixture pack itself was
 * annotated with, and the bar at which the drawn boundary sits ON the package's real outer edge
 * rather than near it. It was earned rather than assumed: this replaced a characterization ceiling
 * of 0.30, which had stood while the detector selected a floor-seam rectangle spanning the frame
 * instead of the package, at an error of roughly 0.235.
 *
 * The headroom between the measured errors and the ceiling is deliberate and small. It is there for
 * incidental float drift, not for a regression to hide in: falling back onto background structure
 * moves the error by about 0.18, which this catches outright.
 *
 * Ground truth stays evaluation-only. It is read here and nowhere else; the detector never sees it.
 */
class MainScanDetectorAccuracyTest {

    private companion object {

        /**
         * PRODUCT_TARGET_MEAN_CORNER_ERROR — the enforced ceiling, per frame and in aggregate.
         *
         * This is [MainScanRealSceneFixtures.TOLERANCE], the annotation tolerance of the fixture
         * pack. Do not raise it to accommodate a change: the pack describes the package, not the
         * detector, and a detector that cannot hold this is not on the outer boundary.
         */
        const val PRODUCT_TARGET_MEAN_CORNER_ERROR = 0.07f

        /**
         * The error produced while the detector selected the floor-seam rectangle rather than the
         * package. Asserted as a level the detector must stay far below, so that the specific
         * regression this slice fixed cannot return quietly under a loosened ceiling.
         */
        const val FLOOR_SEAM_REGRESSION_ERROR = 0.23f
    }

    /** One frame's measurement, kept together so failure messages can name the frame. */
    private data class Measurement(val name: String, val quad: PerspectiveQuad, val error: Float)

    private fun detect(frame: LumaFrame): PerspectiveQuad? =
        MainScanDocumentFinder.find(frame, previous = null)

    private fun measureAll(): List<Measurement> =
        MainScanRealSceneFixtures.frameNames.map { name ->
            val frame = MainScanRealSceneFixtures.load(name)
            val quad = requireNotNull(detect(frame)) { "detector returned no quad for " + name }
            Measurement(
                name = name,
                quad = quad,
                error = MainScanRealSceneFixtures.meanCornerError(
                    quad,
                    MainScanRealSceneFixtures.packageQuad
                )
            )
        }

    // --- the gate ----------------------------------------------------------------------------------

    @Test
    fun theDetectorReturnsACandidateForEveryRealSceneFrame() {
        for (name in MainScanRealSceneFixtures.frameNames) {
            val quad = detect(MainScanRealSceneFixtures.load(name))
            assertNotNull(
                "detector found nothing on $name; the package is plainly present in this frame",
                quad
            )
        }
    }

    @Test
    fun aggregateMeanCornerErrorStaysUnderTheCharacterizationCeiling() {
        val measurements = measureAll()
        val aggregate = measurements.map { it.error }.average().toFloat()

        val report = measurements.joinToString(separator = "\n") {
            "  %s error=%.4f quad=%s".format(
                it.name,
                it.error,
                MainScanRealSceneFixtures.describe(it.quad)
            )
        }

        assertTrue(
            "real-scene accuracy regressed.\n" +
                "aggregate mean corner error %.4f exceeds the product ceiling %.2f\n".format(
                    aggregate,
                    PRODUCT_TARGET_MEAN_CORNER_ERROR
                ) +
                "per frame:\n%s".format(report),
            aggregate <= PRODUCT_TARGET_MEAN_CORNER_ERROR
        )
    }

    /**
     * Per-frame ceiling as well as aggregate.
     *
     * Without this, one frame could collapse entirely while the other two absorbed it in the mean —
     * and a detector that loses the object on one frame in three has not improved.
     */
    @Test
    fun everyFrameIndividuallyStaysUnderTheProductCeiling() {
        for (measurement in measureAll()) {
            assertTrue(
                "%s mean corner error %.4f exceeds the product ceiling %.2f; quad=%s".format(
                    measurement.name,
                    measurement.error,
                    PRODUCT_TARGET_MEAN_CORNER_ERROR,
                    MainScanRealSceneFixtures.describe(measurement.quad)
                ),
                measurement.error <= PRODUCT_TARGET_MEAN_CORNER_ERROR
            )
        }
    }

    /**
     * The specific defect this slice removed: the detector used to return a long, tilted rectangle
     * built from the floor's seams, roughly 1.5x the package's area and overlapping it barely.
     *
     * Stated as its own assertion rather than left implicit in the ceiling, because it is the
     * failure a future change is most likely to reintroduce, and because it names what "wrong" looks
     * like on this scene instead of only how far away it is.
     */
    @Test
    fun theDetectorIsNowhereNearTheFloorSeamRectangleItUsedToReturn() {
        for (measurement in measureAll()) {
            assertTrue(
                "%s is back on background structure: error %.4f is at the floor-seam level %.2f; quad=%s"
                    .format(
                        measurement.name,
                        measurement.error,
                        FLOOR_SEAM_REGRESSION_ERROR,
                        MainScanRealSceneFixtures.describe(measurement.quad)
                    ),
                measurement.error < FLOOR_SEAM_REGRESSION_ERROR
            )
        }
    }

    /**
     * Every corner sits on the package, not merely near it on average.
     *
     * A mean can be met by a quad that is right on three corners and badly wrong on the fourth,
     * which is exactly the shape of a candidate that borrowed one side from the background.
     */
    @Test
    fun everyIndividualCornerLandsOnThePackage() {
        val expected = MainScanRealSceneFixtures.corners(MainScanRealSceneFixtures.packageQuad)
        for (measurement in measureAll()) {
            val actual = MainScanRealSceneFixtures.corners(measurement.quad)
            for (i in 0 until 4) {
                val distance = kotlin.math.hypot(
                    actual[i].x - expected[i].x,
                    actual[i].y - expected[i].y
                )
                assertTrue(
                    "%s corner %d is %.4f from the package corner; quad=%s".format(
                        measurement.name,
                        i,
                        distance,
                        MainScanRealSceneFixtures.describe(measurement.quad)
                    ),
                    distance <= MainScanRealSceneFixtures.SEQUENCE_TOLERANCE
                )
            }
        }
    }

    // --- properties the output must hold regardless of accuracy -------------------------------------

    @Test
    fun detectionIsDeterministicForIdenticalInput() {
        for (name in MainScanRealSceneFixtures.frameNames) {
            // A freshly loaded frame each time, so this also rules out state carried in the fixture.
            val first = detect(MainScanRealSceneFixtures.load(name))
            repeat(3) {
                assertEquals(
                    "$name detected differently on repeat",
                    first,
                    detect(MainScanRealSceneFixtures.load(name))
                )
            }

            // And repeated detection over one frame instance must agree too.
            val frame = MainScanRealSceneFixtures.load(name)
            assertEquals(
                "$name detected differently on the same frame instance",
                detect(frame),
                detect(frame)
            )
        }
    }

    @Test
    fun everyDetectedQuadSatisfiesTheMainScannerValidityContract() {
        for (measurement in measureAll()) {
            val quad = measurement.quad
            for (corner in MainScanRealSceneFixtures.corners(quad)) {
                assertTrue(
                    "${measurement.name} corner outside the normalized frame: $corner",
                    corner.x in 0f..1f && corner.y in 0f..1f
                )
            }
            assertTrue(
                "${measurement.name} produced a non-convex quad: " +
                    MainScanRealSceneFixtures.describe(quad),
                PerspectiveGeometry.isConvex(quad)
            )
            assertTrue(
                "${measurement.name} quad fails the Main Scanner applicability contract: " +
                    MainScanRealSceneFixtures.describe(quad),
                MainScanQuadValidity.isApplicable(quad)
            )
        }
    }

    @Test
    fun detectionNeverMutatesTheFixtureItWasGiven() {
        for (name in MainScanRealSceneFixtures.frameNames) {
            val frame = MainScanRealSceneFixtures.load(name)
            val before = frame.luma.copyOf()
            detect(frame)
            assertTrue(
                "$name luma plane was mutated by the detector",
                frame.luma.contentEquals(before)
            )
            assertEquals("$name width changed", MainScanRealSceneFixtures.WIDTH, frame.width)
            assertEquals("$name height changed", MainScanRealSceneFixtures.HEIGHT, frame.height)
        }
    }

    /**
     * The measurement is only honest while the detector cannot see the answer. This is narrower than
     * the pack-wide guard in [MainScanRealSceneFixtureIntegrityTest]: it asserts that the specific
     * production units this suite scores carry no fixture or ground-truth reference.
     */
    @Test
    fun theScoredDetectorSourcesCarryNoFixtureOrGroundTruthReference() {
        val root = listOf(
            File("src/main/java/com/dev/docscannerpdf/domain/mainscan"),
            File("app/src/main/java/com/dev/docscannerpdf/domain/mainscan")
        ).firstOrNull { it.isDirectory } ?: return

        val forbidden = listOf(
            "edge-fixtures",
            "package_bright_screen",
            "MainScanRealSceneFixtures",
            "packageQuad"
        )
        val offenders = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .mapNotNull { file ->
                val text = file.readText()
                forbidden.firstOrNull { text.contains(it) }?.let { "${file.name} references '$it'" }
            }
            .toList()
        assertTrue("detector source can see its own ground truth: $offenders", offenders.isEmpty())
    }

    // --- diagnostics --------------------------------------------------------------------------------

    /**
     * Prints the measurement rather than asserting on it, so the numbers behind the ceiling stay
     * readable in any test run without having to make a suite fail to see them.
     */
    @Test
    fun reportsTheCurrentRealSceneBaseline() {
        val measurements = measureAll()
        val aggregate = measurements.map { it.error }.average()
        println("--- MainScan real-scene detector accuracy ---")
        println(
            "ground truth: " +
                MainScanRealSceneFixtures.describe(MainScanRealSceneFixtures.packageQuad)
        )
        for (measurement in measurements) {
            println(
                "%s  meanCornerError=%.4f  quad=%s".format(
                    measurement.name,
                    measurement.error,
                    MainScanRealSceneFixtures.describe(measurement.quad)
                )
            )
        }
        println("AGGREGATE_MEAN_CORNER_ERROR=%.4f".format(aggregate))
        println("ENFORCED_CEILING=%.2f".format(PRODUCT_TARGET_MEAN_CORNER_ERROR))
        assertEquals(3, measurements.size)
    }
}
