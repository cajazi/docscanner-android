package com.dev.docscannerpdf.domain.crop

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Output ORIENTATION of [PerspectiveTransformEngine.plan].
 *
 * ## What this suite is for
 *
 * [CropEngineTest] proves the homography maps `plan.sourcePixels` onto `plan.destinationPixels`.
 * That is a self-consistency check: it reads the plan's own inputs back, so a plan that silently
 * re-labelled the caller's corners still passes it. Nothing anywhere asserted the property the user
 * actually experiences — that the page which comes out of the warp has the orientation of the
 * polygon they confirmed.
 *
 * The gap was reachable. `plan` decided which corner becomes the output origin while the quad was
 * still in NORMALIZED coordinates, where x carries the width scale and y carries the height scale.
 * The anchor rule compares `x + y`, so on a non-square source that sum is measured in a stretched
 * space and the corner elected as the origin depends on the source aspect ratio. A portrait page
 * tilted 40 degrees in a 3060x4080 capture was warped a quarter turn away from its polygon, and the
 * output dimensions came back transposed — 2400x1800 for an 1800x2400 page.
 *
 * ## How correctness is defined here
 *
 * By SEMANTIC CORNER IDENTITY, never by comparing width against height. "Output should be portrait"
 * is not the contract: a landscape document confirmed as landscape must stay landscape. The contract
 * is that the caller's `topLeft` drives output (0,0), the caller's top edge pair drives the output
 * width, and the caller's left edge pair drives the output height — whatever those happen to
 * measure.
 *
 * ## The one place a threshold legitimately moves
 *
 * Past roughly half a turn, re-orienting the page IS the desired behaviour, and the anchor moves to
 * a different physical corner. That is not a defect, so this suite never asserts a specific flip
 * angle. It asserts that the flip happens at the SAME physical tilt for every source aspect ratio —
 * which is the actual invariant, and the thing the normalized-space rule could not provide.
 */
class PerspectiveOutputOrientationTest {

    private companion object {

        /**
         * Half a pixel. The largest positional error that cannot change which source pixel a
         * destination pixel samples, so it is the natural bound for "this is the same corner".
         * Actual float round-trip error over a ~4000px edge is around 1e-4 px.
         */
        const val CORNER_TOLERANCE_PX = 0.5f

        /**
         * One pixel on each output dimension — exactly what `roundToInt` in the engine permits.
         */
        const val DIMENSION_TOLERANCE_PX = 1

        /** Corner role names, indexed as [PerspectiveQuad.corners] returns them. */
        val ROLES = listOf("topLeft", "topRight", "bottomRight", "bottomLeft")
    }

    // --- deterministic physical geometry --------------------------------------------------------

    /**
     * A rectangular page of [pageWidthPx] x [pageHeightPx] source pixels, rotated [tiltDegrees]
     * clockwise about the centre of a [sourceWidth] x [sourceHeight] image, returned in normalized
     * coordinates in semantic TL/TR/BR/BL order.
     *
     * This is the whole point of the fixture: the SAME physical rectangle can be expressed against
     * any source size, and every assertion below compares what the engine does across those
     * expressions. Nothing is clamped away — callers keep the page small enough to fit at any angle.
     */
    private fun tiltedPage(
        tiltDegrees: Float,
        sourceWidth: Int,
        sourceHeight: Int,
        pageWidthPx: Float,
        pageHeightPx: Float
    ): PerspectiveQuad {
        val radians = Math.toRadians(tiltDegrees.toDouble())
        val cosine = cos(radians)
        val sine = sin(radians)
        val halfWidth = pageWidthPx / 2.0
        val halfHeight = pageHeightPx / 2.0
        // Corner offsets from the page centre, in the page's own frame, TL/TR/BR/BL.
        val local = listOf(
            -halfWidth to -halfHeight,
            halfWidth to -halfHeight,
            halfWidth to halfHeight,
            -halfWidth to halfHeight
        )
        val rotated = local.map { (x, y) ->
            CropPoint(
                x = ((sourceWidth / 2.0 + x * cosine - y * sine) / sourceWidth).toFloat(),
                y = ((sourceHeight / 2.0 + x * sine + y * cosine) / sourceHeight).toFloat()
            )
        }
        return PerspectiveQuad(rotated[0], rotated[1], rotated[2], rotated[3])
    }

    /**
     * A page sized so its DIAGONAL fits the source's short edge, which guarantees all four corners
     * stay inside the image at every rotation. Without this a steeply tilted page would push corners
     * outside the unit square, `clampToUnit` would move them, and the test would be measuring
     * clamping rather than ordering.
     */
    private fun fittingPage(sourceWidth: Int, sourceHeight: Int): Pair<Float, Float> {
        val diagonal = 0.80 * minOf(sourceWidth, sourceHeight)
        // A 3:4 page — the same proportions as the 1800x2400 page in the characterized case.
        val scale = diagonal / hypot(3.0, 4.0)
        return (3.0 * scale).toFloat() to (4.0 * scale).toFloat()
    }

    /** The caller's corners expressed in source pixels — what `plan.sourcePixels` must contain. */
    private fun expectedSourcePixels(
        quad: PerspectiveQuad,
        sourceWidth: Int,
        sourceHeight: Int
    ): List<CropPoint> = quad.corners().map { CropPoint(it.x * sourceWidth, it.y * sourceHeight) }

    private fun distance(a: CropPoint, b: CropPoint): Float = hypot(a.x - b.x, a.y - b.y)

    private fun near(a: CropPoint, b: CropPoint): Boolean =
        abs(a.x - b.x) <= CORNER_TOLERANCE_PX && abs(a.y - b.y) <= CORNER_TOLERANCE_PX

    /**
     * Which of the caller's semantic roles the engine placed at `sourcePixels[index]`, or -1 when
     * the point is not one of the caller's corners at all — which would mean a coordinate was
     * invented rather than merely re-labelled.
     */
    private fun roleAt(
        plan: WarpPlan,
        index: Int,
        quad: PerspectiveQuad,
        sourceWidth: Int,
        sourceHeight: Int
    ): Int {
        val expected = expectedSourcePixels(quad, sourceWidth, sourceHeight)
        return expected.indexOfFirst { near(it, plan.sourcePixels[index]) }
    }

    private fun signedArea(points: List<CropPoint>): Float {
        var sum = 0f
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2f
    }

    /**
     * The full semantic contract for one plan: every caller role lands at its own index, so the
     * output's top-left really is the polygon's top-left.
     */
    private fun assertSemanticOrderPreserved(
        message: String,
        quad: PerspectiveQuad,
        sourceWidth: Int,
        sourceHeight: Int
    ): WarpPlan {
        val plan = PerspectiveTransformEngine.plan(quad, sourceWidth, sourceHeight)
        val expected = expectedSourcePixels(quad, sourceWidth, sourceHeight)
        for (index in 0 until 4) {
            assertTrue(
                "$message: sourcePixels[$index] should be the caller's ${ROLES[index]} " +
                    "${expected[index]} but was ${plan.sourcePixels[index]}",
                near(expected[index], plan.sourcePixels[index])
            )
        }
        return plan
    }

    // --- A. the characterized failure -----------------------------------------------------------

    /**
     * The exact case recorded during discovery, regenerated at full precision: an 1800x2400 portrait
     * page tilted 40 degrees clockwise inside a 3060x4080 EXIF-upright capture.
     *
     * Before the fix the engine returned the caller's bottomLeft at index 0 and an output of
     * 2400x1800 — the page quarter-turned, its dimensions transposed. The four normalized corners
     * are asserted against the recorded evidence first, so a future change to the fixture cannot
     * quietly move this away from the case that was actually measured.
     */
    @Test
    fun theCharacterizedQuarterTurnDoesNotHappen() {
        val sourceWidth = 3060
        val sourceHeight = 4080
        val quad = tiltedPage(40f, sourceWidth, sourceHeight, 1800f, 2400f)

        // Pinned to the discovery evidence — this is the quad that was measured, not a new one.
        val recorded = listOf(
            0.5268f to 0.1329f,
            0.9774f to 0.4165f,
            0.4732f to 0.8671f,
            0.0226f to 0.5835f
        )
        quad.corners().forEachIndexed { index, corner ->
            assertEquals(
                "${ROLES[index]}.x drifted from the characterized quad",
                recorded[index].first.toDouble(),
                corner.x.toDouble(),
                1e-4
            )
            assertEquals(
                "${ROLES[index]}.y drifted from the characterized quad",
                recorded[index].second.toDouble(),
                corner.y.toDouble(),
                1e-4
            )
        }

        val plan = assertSemanticOrderPreserved(
            "characterized 40 degree portrait page",
            quad,
            sourceWidth,
            sourceHeight
        )

        assertEquals(
            "the page is 1800 wide, so the output must be too — 2400 here means the quarter turn",
            1800.0,
            plan.outputWidth.toDouble(),
            DIMENSION_TOLERANCE_PX.toDouble()
        )
        assertEquals(
            "the page is 2400 tall, so the output must be too",
            2400.0,
            plan.outputHeight.toDouble(),
            DIMENSION_TOLERANCE_PX.toDouble()
        )
    }

    // --- B. source aspect-ratio invariance ------------------------------------------------------

    /** The five representative source shapes, from square to strongly tall and strongly wide. */
    private val sourceShapes = listOf(
        "square 1:1" to (2048 to 2048),
        "4:3 landscape" to (4080 to 3060),
        "3:4 portrait" to (3060 to 4080),
        "strongly tall 1:4" to (1000 to 4000),
        "strongly wide 4:1" to (4000 to 1000)
    )

    /**
     * The same physical page, at the same physical tilt, expressed against every source shape. The
     * role the engine places at index 0 must be identical across all five — the anchor may not
     * depend on how the image happens to be proportioned.
     *
     * The assertion is deliberately "all five agree" rather than "all five are topLeft": at a tilt
     * past half a turn the anchor legitimately moves, and it must move for every shape together.
     */
    @Test
    fun theOutputAnchorIsIdenticalAcrossEverySourceAspectRatio() {
        for (tilt in listOf(0f, 10f, 20f, 30f, 40f, 50f, 70f)) {
            val roles = sourceShapes.map { (name, size) ->
                val (width, height) = size
                val (pageWidth, pageHeight) = fittingPage(width, height)
                val quad = tiltedPage(tilt, width, height, pageWidth, pageHeight)
                val plan = PerspectiveTransformEngine.plan(quad, width, height)
                val role = roleAt(plan, 0, quad, width, height)
                assertTrue(
                    "$name at $tilt deg: sourcePixels[0] is not one of the caller's corners",
                    role >= 0
                )
                name to role
            }
            val distinct = roles.map { it.second }.toSet()
            assertEquals(
                "at $tilt deg the anchor differs by source aspect ratio: " +
                    roles.joinToString { "${it.first}=${ROLES[it.second]}" },
                1,
                distinct.size
            )
        }
    }

    // --- C. semantic anchor stability -----------------------------------------------------------

    /**
     * Inside the band where the page is still recognisably upright, every caller role must land at
     * its own index on every source shape. This is the assertion the old rule could not satisfy:
     * on a 3:4 source it failed from 37 degrees, and on a 1:4 source from 14 degrees.
     */
    @Test
    fun everySemanticCornerKeepsItsIndexOnEverySourceShape() {
        for ((name, size) in sourceShapes) {
            val (width, height) = size
            val (pageWidth, pageHeight) = fittingPage(width, height)
            for (tilt in listOf(0f, 5f, 10f, 20f, 30f, 35f, 40f, 42f)) {
                assertSemanticOrderPreserved(
                    "$name at $tilt deg",
                    tiltedPage(tilt, width, height, pageWidth, pageHeight),
                    width,
                    height
                )
            }
        }
    }

    /** A landscape page must stay landscape — orientation is preserved, not normalised to portrait. */
    @Test
    fun aLandscapePageConfirmedAsLandscapeStaysLandscape() {
        val sourceWidth = 3060
        val sourceHeight = 4080
        val quad = tiltedPage(20f, sourceWidth, sourceHeight, 2400f, 1800f)

        val plan = assertSemanticOrderPreserved(
            "landscape page in a portrait source",
            quad,
            sourceWidth,
            sourceHeight
        )
        assertEquals(2400.0, plan.outputWidth.toDouble(), DIMENSION_TOLERANCE_PX.toDouble())
        assertEquals(1800.0, plan.outputHeight.toDouble(), DIMENSION_TOLERANCE_PX.toDouble())
        assertTrue(
            "a landscape page must not be turned upright by the planner",
            plan.outputWidth > plan.outputHeight
        )
    }

    // --- D. winding -----------------------------------------------------------------------------

    /**
     * The planned source quad must stay clockwise and convex. `MainScanCropEditor.preservesWinding`
     * compares this sign across a drag, and a reversed winding warps to a mirrored page — visually
     * plausible on screen and wrong in the saved document.
     */
    @Test
    fun thePlannedSourceQuadStaysClockwiseAndConvex() {
        for ((name, size) in sourceShapes) {
            val (width, height) = size
            val (pageWidth, pageHeight) = fittingPage(width, height)
            for (tilt in listOf(0f, 15f, 37f, 45f, 60f, 85f)) {
                val plan = PerspectiveTransformEngine.plan(
                    tiltedPage(tilt, width, height, pageWidth, pageHeight),
                    width,
                    height
                )
                assertTrue(
                    "$name at $tilt deg: planned source quad lost its clockwise winding",
                    signedArea(plan.sourcePixels) > 0f
                )
                val asQuad = PerspectiveQuad(
                    plan.sourcePixels[0],
                    plan.sourcePixels[1],
                    plan.sourcePixels[2],
                    plan.sourcePixels[3]
                )
                assertTrue(
                    "$name at $tilt deg: planned source quad is not convex",
                    PerspectiveGeometry.isConvex(asQuad)
                )
            }
        }
    }

    // --- E/F. output dimensions come from the semantic edge pairs -------------------------------

    /**
     * Output width from the caller's TOP and BOTTOM edges, output height from the caller's LEFT and
     * RIGHT edges, each the longer of its pair. The formula itself is unchanged by this slice; what
     * is asserted here is that it is fed the caller's semantic pairs rather than re-labelled ones.
     */
    @Test
    fun outputDimensionsDeriveFromTheCallersSemanticEdgePairs() {
        for ((name, size) in sourceShapes) {
            val (width, height) = size
            val (pageWidth, pageHeight) = fittingPage(width, height)
            for (tilt in listOf(0f, 12f, 25f, 40f)) {
                val quad = tiltedPage(tilt, width, height, pageWidth, pageHeight)
                val corners = expectedSourcePixels(quad, width, height)
                val plan = PerspectiveTransformEngine.plan(quad, width, height)

                val expectedWidth = maxOf(
                    distance(corners[0], corners[1]), // topLeft -> topRight
                    distance(corners[3], corners[2])  // bottomLeft -> bottomRight
                )
                val expectedHeight = maxOf(
                    distance(corners[0], corners[3]), // topLeft -> bottomLeft
                    distance(corners[1], corners[2])  // topRight -> bottomRight
                )
                assertEquals(
                    "$name at $tilt deg: output width must come from the top/bottom edge pair",
                    Math.round(expectedWidth).toDouble(),
                    plan.outputWidth.toDouble(),
                    DIMENSION_TOLERANCE_PX.toDouble()
                )
                assertEquals(
                    "$name at $tilt deg: output height must come from the left/right edge pair",
                    Math.round(expectedHeight).toDouble(),
                    plan.outputHeight.toDouble(),
                    DIMENSION_TOLERANCE_PX.toDouble()
                )
            }
        }
    }

    // --- G/H. shape coverage --------------------------------------------------------------------

    /** An irregular convex quad — no symmetry for an extremum rule to tie on. */
    private val asymmetricQuad = PerspectiveQuad(
        topLeft = CropPoint(0.30f, 0.08f),
        topRight = CropPoint(0.94f, 0.30f),
        bottomRight = CropPoint(0.62f, 0.92f),
        bottomLeft = CropPoint(0.06f, 0.55f)
    )

    /**
     * The asymmetric quad on the source shapes where the caller's labelling is the one the physical
     * geometry supports. Nothing about the shape ties or nearly ties, so the roles must survive.
     */
    @Test
    fun anAsymmetricQuadKeepsItsSemanticCorners() {
        val shapes = listOf(
            "square 1:1" to (2048 to 2048),
            "4:3 landscape" to (4080 to 3060),
            "3:4 portrait" to (3060 to 4080),
            "strongly tall 1:4" to (1000 to 4000)
        )
        for ((name, size) in shapes) {
            assertSemanticOrderPreserved(
                "asymmetric quad on $name",
                asymmetricQuad,
                size.first,
                size.second
            )
        }
    }

    /**
     * The honest boundary of the contract, asserted rather than left implicit.
     *
     * A quad fixed in NORMALIZED coordinates is not a fixed PHYSICAL quad: expressing it against a
     * 4000x1000 source stretches it four-fold horizontally, and the vertex the caller labelled
     * `bottomLeft` genuinely becomes the most top-left point of the real shape — (240, 550) against
     * the labelled `topLeft` at (1200, 80). The planner is right to anchor there, and this test
     * exists so that behaviour is a recorded decision rather than a surprise.
     *
     * What must hold even then is the structural contract: the four planned corners are the four
     * supplied corners, each used once, wound clockwise and convex.
     */
    @Test
    fun anExtremeAspectRatioReanchorsOnPhysicalGeometryWithoutLosingACorner() {
        val sourceWidth = 4000
        val sourceHeight = 1000
        val plan = PerspectiveTransformEngine.plan(asymmetricQuad, sourceWidth, sourceHeight)

        val roles = (0 until 4).map { roleAt(plan, it, asymmetricQuad, sourceWidth, sourceHeight) }
        assertTrue("a planned corner is not one of the caller's — $roles", roles.none { it < 0 })
        assertEquals("a caller corner was used twice or dropped — $roles", 4, roles.toSet().size)
        assertTrue("winding must stay clockwise", signedArea(plan.sourcePixels) > 0f)

        // The anchor follows the physical geometry: the smallest x + y in SOURCE PIXELS.
        val pixels = expectedSourcePixels(asymmetricQuad, sourceWidth, sourceHeight)
        val physicallyTopLeft = pixels.indices.minByOrNull { pixels[it].x + pixels[it].y }
        assertEquals(
            "the anchor must be the physically top-left corner, not the normalized-space one",
            physicallyTopLeft,
            roles[0]
        )
    }

    /** A quad hard against the image bounds, where clamping and ordering could interact. */
    @Test
    fun aNearEdgeQuadKeepsItsSemanticCornersAndFullExtent() {
        val quad = PerspectiveQuad(
            topLeft = CropPoint(0.001f, 0.001f),
            topRight = CropPoint(0.999f, 0.004f),
            bottomRight = CropPoint(0.997f, 0.996f),
            bottomLeft = CropPoint(0.003f, 0.999f)
        )
        val plan = assertSemanticOrderPreserved("near-edge quad", quad, 3060, 4080)
        assertTrue(
            "a near-full-frame quad must plan to nearly the full source width",
            plan.outputWidth >= 3040
        )
        assertTrue(
            "a near-full-frame quad must plan to nearly the full source height",
            plan.outputHeight >= 4050
        )
    }

    /** The full frame must plan to the identity: same dimensions, corners on the image corners. */
    @Test
    fun theFullFrameQuadPlansToTheIdentity() {
        val plan = assertSemanticOrderPreserved("full frame", PerspectiveQuad.full(), 3060, 4080)
        assertEquals(3060, plan.outputWidth)
        assertEquals(4080, plan.outputHeight)
    }

    // --- I. the previously unstable angle ranges ------------------------------------------------

    /**
     * The measured aspect-specific flip points on the old rule: 37 degrees for a 3:4 source, 14 for
     * a 1:4 source, 53 for 4:3 and 76 for 4:1, against 45 for a square. Each pair below straddles
     * one of those, and every case must keep the caller's semantic corners because none of these
     * tilts is anywhere near half a turn.
     */
    @Test
    fun tiltsAroundTheOldAspectSpecificFlipPointsStayStable() {
        val cases = listOf(
            Triple("3:4 portrait", 3060 to 4080, listOf(35f, 40f)),
            Triple("strongly tall 1:4", 1000 to 4000, listOf(10f, 20f)),
            Triple("4:3 landscape", 4080 to 3060, listOf(40f, 42f)),
            Triple("strongly wide 4:1", 4000 to 1000, listOf(40f, 42f)),
            Triple("square 1:1", 2048 to 2048, listOf(40f, 42f))
        )
        for ((name, size, tilts) in cases) {
            val (width, height) = size
            val (pageWidth, pageHeight) = fittingPage(width, height)
            for (tilt in tilts) {
                assertSemanticOrderPreserved(
                    "$name at $tilt deg — inside the old unstable band",
                    tiltedPage(tilt, width, height, pageWidth, pageHeight),
                    width,
                    height
                )
            }
        }
    }

    // --- J. arbitrary input order -----------------------------------------------------------

    /**
     * [PerspectiveGeometry.orderCorners] accepts four points in any order — `DocumentEdgeDetector`
     * builds its four points from independent extremum scans and depends on that repair. Moving the
     * ordering into source-pixel space must not weaken it, so every one of the 24 permutations of a
     * quad's corners must still plan identically.
     */
    @Test
    fun allTwentyFourInputPermutationsPlanIdentically() {
        val quad = PerspectiveQuad(
            topLeft = CropPoint(0.20f, 0.15f),
            topRight = CropPoint(0.82f, 0.22f),
            bottomRight = CropPoint(0.78f, 0.85f),
            bottomLeft = CropPoint(0.15f, 0.79f)
        )
        val sourceWidth = 3060
        val sourceHeight = 4080
        val reference = PerspectiveTransformEngine.plan(quad, sourceWidth, sourceHeight)

        for (permutation in permutations(quad.corners())) {
            val shuffled = PerspectiveQuad(
                permutation[0],
                permutation[1],
                permutation[2],
                permutation[3]
            )
            val plan = PerspectiveTransformEngine.plan(shuffled, sourceWidth, sourceHeight)
            assertEquals(
                "output width changed with input order: $permutation",
                reference.outputWidth,
                plan.outputWidth
            )
            assertEquals(
                "output height changed with input order: $permutation",
                reference.outputHeight,
                plan.outputHeight
            )
            for (index in 0 until 4) {
                assertTrue(
                    "sourcePixels[$index] changed with input order: $permutation",
                    near(reference.sourcePixels[index], plan.sourcePixels[index])
                )
            }
        }
    }

    /** A counter-clockwise quad must be corrected to clockwise, exactly as it is today. */
    @Test
    fun counterClockwiseInputIsStillCorrected() {
        val counterClockwise = PerspectiveQuad(
            topLeft = CropPoint(0.1f, 0.2f),
            topRight = CropPoint(0.1f, 0.8f),
            bottomRight = CropPoint(0.9f, 0.8f),
            bottomLeft = CropPoint(0.9f, 0.2f)
        )
        val plan = PerspectiveTransformEngine.plan(counterClockwise, 3060, 4080)
        assertTrue(
            "counter-clockwise input must be corrected, not preserved",
            signedArea(plan.sourcePixels) > 0f
        )
    }

    /** The planner must never invent a coordinate — every output point is one of the four inputs. */
    @Test
    fun thePlannedCornersAreAlwaysThePermutedInputCorners() {
        for ((name, size) in sourceShapes) {
            val (width, height) = size
            val (pageWidth, pageHeight) = fittingPage(width, height)
            for (tilt in listOf(0f, 23f, 45f, 67f)) {
                val quad = tiltedPage(tilt, width, height, pageWidth, pageHeight)
                val plan = PerspectiveTransformEngine.plan(quad, width, height)
                val roles = (0 until 4).map { roleAt(plan, it, quad, width, height) }
                assertTrue(
                    "$name at $tilt deg: a planned corner is not one of the caller's — $roles",
                    roles.none { it < 0 }
                )
                assertEquals(
                    "$name at $tilt deg: a caller corner was used twice or dropped — $roles",
                    4,
                    roles.toSet().size
                )
            }
        }
    }

    /** The 24 orderings of four points. */
    private fun permutations(points: List<CropPoint>): List<List<CropPoint>> {
        if (points.size <= 1) return listOf(points)
        return points.flatMap { head ->
            permutations(points - listOf(head)).map { listOf(head) + it }
        }
    }
}
