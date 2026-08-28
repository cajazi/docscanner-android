package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.CropCorner
import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import com.dev.docscannerpdf.domain.crop.PerspectiveTransformEngine
import com.dev.docscannerpdf.ui.mainscan.MainScanCaptureImageLoader
import java.io.File
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinate frame the authoritative render must reproduce, at every quarter turn.
 *
 * ## The failure this suite is aimed at
 *
 * The confirmed polygon is normalized against the frame the crop editor was SHOWING: the capture
 * decoded, turned upright by EXIF, then turned again by the user's Left/Right presses. The
 * authoritative render decodes the original again at full resolution and has to rebuild exactly
 * that frame before the polygon means anything.
 *
 * Get it wrong and the result is uniquely nasty: at 0 and 180 degrees a source-frame polygon and an
 * editor-frame polygon coincide closely enough that nothing looks broken, so the mistake ships. At
 * 90 and 270 the two frames have TRANSPOSED dimensions, and the same numbers address a completely
 * different part of the page — the preview on screen stays perfect while the saved file is cropped
 * somewhere the user never indicated. There is no visual signal in the flow that would catch it.
 *
 * ## How correctness is defined here
 *
 * By semantic corner identity in SOURCE PIXELS, never by comparing width against height — the same
 * discipline [com.dev.docscannerpdf.domain.crop.PerspectiveOutputOrientationTest] establishes. A
 * quarter turn must move each confirmed corner to exactly the pixel a quarter turn moves it to, must
 * keep all four (none lost, none duplicated), must keep their winding, and must advance the output's
 * anchor by exactly that many positions around the cycle. Everything else follows from those.
 */
class MainScanRotationMappingTest {

    private companion object {

        /** Half a pixel: the largest positional error that cannot change which pixel is sampled. */
        const val PIXEL_TOLERANCE = 0.5f

        /** One pixel on each dimension — exactly what the engine's `roundToInt` permits. */
        const val DIMENSION_TOLERANCE = 1

        /** A real 12.5 MP capture, in both aspects the camera produces. */
        const val PORTRAIT_WIDTH = 3060
        const val PORTRAIT_HEIGHT = 4080
        const val LANDSCAPE_WIDTH = 4080
        const val LANDSCAPE_HEIGHT = 3060

        /**
         * An asymmetric, non-full-frame polygon with a mild tilt.
         *
         * Asymmetric so a transposition or a mirror cannot pass by coincidence; inset so a frame
         * mix-up cannot be hidden by clamping at the image edge; only mildly tilted so the anchor
         * rule has an unambiguous answer (a 45-degree diamond deliberately does not, and that
         * ambiguity is a separate contract owned by [PerspectiveGeometryOrderingTest]).
         */
        val UPRIGHT_QUAD = PerspectiveQuad(
            topLeft = CropPoint(0.14f, 0.09f),
            topRight = CropPoint(0.86f, 0.17f),
            bottomRight = CropPoint(0.79f, 0.88f),
            bottomLeft = CropPoint(0.11f, 0.81f)
        )
    }

    // --- the frame itself ------------------------------------------------------------------------

    @Test
    fun evenTurnsKeepTheFrameAndOddTurnsTransposeIt() {
        for ((width, height) in listOf(
            PORTRAIT_WIDTH to PORTRAIT_HEIGHT,
            LANDSCAPE_WIDTH to LANDSCAPE_HEIGHT
        )) {
            for (turns in 0..3) {
                val frame = MainScanAuthoritativeRender.orientedFrame(width, height, turns)
                if (turns % 2 == 0) {
                    assertEquals("q=$turns width", width, frame.width)
                    assertEquals("q=$turns height", height, frame.height)
                } else {
                    assertEquals("q=$turns width", height, frame.width)
                    assertEquals("q=$turns height", width, frame.height)
                }
                assertEquals(turns, frame.rotationQuarterTurns)
            }
        }
    }

    @Test
    fun fourTurnsAreTheIdentityInBothDirections() {
        for ((width, height) in listOf(
            PORTRAIT_WIDTH to PORTRAIT_HEIGHT,
            LANDSCAPE_WIDTH to LANDSCAPE_HEIGHT
        )) {
            val zero = MainScanAuthoritativeRender.orientedFrame(width, height, 0)
            assertEquals(zero, MainScanAuthoritativeRender.orientedFrame(width, height, 4))
            assertEquals(zero, MainScanAuthoritativeRender.orientedFrame(width, height, -4))
            assertEquals(zero, MainScanAuthoritativeRender.orientedFrame(width, height, 8))
        }
        for (turns in -8..8) {
            assertTrue(MainScanAuthoritativeRender.normalizeQuarterTurns(turns) in 0..3)
            assertEquals(
                MainScanAuthoritativeRender.normalizeQuarterTurns(turns),
                MainScanAuthoritativeRender.normalizeQuarterTurns(turns + 4)
            )
        }
    }

    @Test
    fun aFullTurnOfThePolygonReturnsThePolygon() {
        var quad = UPRIGHT_QUAD
        repeat(4) { quad = MainScanAuthoritativeRender.toEditorQuad(quad, 1) }
        assertQuadsMatch(
            expected = PerspectiveGeometry.normalize(UPRIGHT_QUAD),
            actual = quad,
            scaleX = PORTRAIT_WIDTH.toFloat(),
            scaleY = PORTRAIT_HEIGHT.toFloat(),
            message = "four quarter turns must be the identity"
        )
    }

    @Test
    fun theEditorAndUprightQuadsAreInversesAtEveryTurn() {
        for (turns in 0..3) {
            val editor = MainScanAuthoritativeRender.toEditorQuad(UPRIGHT_QUAD, turns)
            val back = MainScanAuthoritativeRender.toUprightQuad(editor, turns)
            assertQuadsMatch(
                expected = PerspectiveGeometry.normalize(UPRIGHT_QUAD),
                actual = back,
                scaleX = PORTRAIT_WIDTH.toFloat(),
                scaleY = PORTRAIT_HEIGHT.toFloat(),
                message = "q=$turns round trip"
            )
        }
    }

    // --- the polygon lands in the reproduced frame -------------------------------------------------

    @Test
    fun theNormalizedPolygonAddressesTheSamePhysicalPixelsInEveryFrame() {
        forEachSourceAndTurn { width, height, turns ->
            val frame = MainScanAuthoritativeRender.orientedFrame(width, height, turns)
            val editorQuad = MainScanAuthoritativeRender.toEditorQuad(UPRIGHT_QUAD, turns)

            // Where the confirmed corners land, in the pixels of the frame the editor was showing.
            val editorPixels = editorQuad.corners().map {
                CropPoint(it.x * frame.width, it.y * frame.height)
            }
            // Where a quarter turn of the ORIGINAL pixels puts those same physical corners.
            val expectedPixels = UPRIGHT_QUAD.corners().map { corner ->
                rotatePixels(
                    point = CropPoint(corner.x * width, corner.y * height),
                    uprightWidth = width,
                    uprightHeight = height,
                    turns = turns
                )
            }

            assertPointSetsMatch(
                expected = expectedPixels,
                actual = editorPixels,
                message = "${width}x$height q=$turns: the polygon must land on the same pixels"
            )
        }
    }

    // --- the warp the authoritative path plans ------------------------------------------------------

    @Test
    fun theWarpKeepsAllFourConfirmedCornersAtEveryTurn() {
        forEachSourceAndTurn { width, height, turns ->
            val plan = planFor(width, height, turns)
            // Un-rotate the plan's source pixels back to the upright capture: they must be the same
            // four physical corners the user confirmed. A corner lost or duplicated here is the
            // bow-tie that makes a crop unusable.
            val unrotated = plan.sourcePixels.map {
                unrotatePixels(it, width, height, turns)
            }
            val expected = UPRIGHT_QUAD.corners().map { CropPoint(it.x * width, it.y * height) }
            assertPointSetsMatch(
                expected = expected,
                actual = unrotated,
                message = "${width}x$height q=$turns: warp corners"
            )
        }
    }

    @Test
    fun theOutputAnchorAdvancesByExactlyTheNumberOfTurns() {
        // The semantic contract. Turning the page one quarter clockwise makes the corner that WAS
        // bottom-left the new top-left, so the output's origin moves one step backwards around the
        // confirmed cycle — and the whole cycle comes with it, unbroken.
        forEachSourceAndTurn { width, height, turns ->
            val baseline = planFor(width, height, 0).sourcePixels
            val rotated = planFor(width, height, turns).sourcePixels.map {
                unrotatePixels(it, width, height, turns)
            }
            for (index in 0..3) {
                val expected = baseline[((index - turns) % 4 + 4) % 4]
                assertPointsMatch(
                    expected = expected,
                    actual = rotated[index],
                    message = "${width}x$height q=$turns: output corner $index"
                )
            }
        }
    }

    @Test
    fun theOutputTransposesAtNinetyAndTwoSeventyAndOnlyThere() {
        for ((width, height) in listOf(
            PORTRAIT_WIDTH to PORTRAIT_HEIGHT,
            LANDSCAPE_WIDTH to LANDSCAPE_HEIGHT
        )) {
            val base = planFor(width, height, 0)
            for (turns in 0..3) {
                val plan = planFor(width, height, turns)
                val label = "${width}x$height q=$turns"
                if (turns % 2 == 0) {
                    assertDimension("$label width", base.outputWidth, plan.outputWidth)
                    assertDimension("$label height", base.outputHeight, plan.outputHeight)
                } else {
                    // A quarter-turned page IS a quarter-turned output. Anything else would mean the
                    // rotate buttons changed what the user sees and not what they get.
                    assertDimension("$label width", base.outputHeight, plan.outputWidth)
                    assertDimension("$label height", base.outputWidth, plan.outputHeight)
                }
            }
        }
    }

    @Test
    fun theOutputIsNeverTheFullFrameForAnInsetPolygon() {
        // Guards the whole suite against a silent fallback: if the polygon were ever dropped and the
        // full frame used instead, every corner assertion above would still be comparing a rectangle
        // to itself at some turns.
        forEachSourceAndTurn { width, height, turns ->
            val frame = MainScanAuthoritativeRender.orientedFrame(width, height, turns)
            val plan = planFor(width, height, turns)
            assertTrue(
                "${width}x$height q=$turns: an inset polygon must not warp to the whole frame",
                plan.outputWidth < frame.width && plan.outputHeight < frame.height
            )
        }
    }

    // --- the engine is invoked exactly once ----------------------------------------------------------

    @Test
    fun theAuthoritativePathPlansTheWarpExactlyOnce() {
        val source = processorSource()
        val body = functionBody(source, "renderAuthoritative")
        assertEquals(
            "the authoritative path must plan the warp once — a second plan would describe a " +
                "different transform from the one admission control approved",
            1,
            occurrences(body, "PerspectiveTransformEngine.plan(")
        )
    }

    @Test
    fun theAuthoritativePathAppliesThatOnePlanExactlyOnce() {
        val body = functionBody(processorSource(), "renderAuthoritativeContained")
        assertEquals(
            "the plan must be drawn once; warping twice would compound the perspective correction",
            1,
            occurrences(body, "drawWarp(")
        )
        assertEquals(
            "the allocating half must not plan a warp of its own",
            0,
            occurrences(body, "PerspectiveTransformEngine.plan(")
        )
    }

    @Test
    fun exifAndTheUserTurnAreEachAppliedExactlyOnce() {
        val body = functionBody(processorSource(), "orientToEditorFrame")
        assertEquals(
            "EXIF must be applied exactly once",
            1,
            occurrences(body, "postRotate(exifDegrees")
        )
        assertEquals(
            "the user's quarter turns must be applied exactly once",
            1,
            occurrences(body, "postRotate((rotationQuarterTurns")
        )
        assertEquals(
            "both turns go into ONE bitmap, so no intermediate full-resolution copy exists",
            1,
            occurrences(body, "Bitmap.createBitmap(")
        )
    }

    @Test
    fun theAuthoritativePathReusesTheSharedRotationCounter() {
        // There is exactly one user-rotation counter in this flow — MainScanCropState's. A second
        // one would drift from it the first time a rotate landed while a render was in flight, and
        // the drift would only be visible in the saved file.
        val body = functionBody(processorSource(), "renderAuthoritative")
        assertTrue(
            "the render must take the editor's quarter turns as given",
            body.contains("rotationQuarterTurns = rotationQuarterTurns")
        )
        assertEquals(
            "EXIF orientation must be read exactly once for the whole render",
            1,
            occurrences(body, "rotationDegreesFromExif(")
        )
        assertFalse(
            "the render must not invent a rotation of its own",
            body.contains("MainScanRotation") || body.contains("var rotation")
        )
    }

    // --- the PRODUCTION rotate control, bound to the authoritative mapping --------------------------
    //
    // Everything above establishes what the authoritative frame mapping IS. These bind it to the
    // control the user actually presses. MainScanCropEditor.rotate is what Left and Right call, and
    // MainScanCropState.rotationQuarterTurns is the single counter the render is handed — so if the
    // editor's turn ever stopped agreeing with the mapping the render reproduces, the polygon would
    // be applied in a frame it was not normalized against and nothing on screen would say so.
    //
    // Asserted BEHAVIOURALLY: real states through the real function, compared against real pixels.

    @Test
    fun theEditorsRightTurnIsTheAuthoritativeMappingAtEveryQuarterTurn() {
        assertEditorRotationMatchesMapping(MainScanRotation.RIGHT)
    }

    @Test
    fun theEditorsLeftTurnIsTheAuthoritativeMappingAtEveryQuarterTurn() {
        assertEditorRotationMatchesMapping(MainScanRotation.LEFT)
    }

    @Test
    fun theEditorsRightTurnLandsTheConfirmedCornersOnTheSamePhysicalPixels() {
        assertEditorRotationLandsOnTheSamePixels(MainScanRotation.RIGHT)
    }

    @Test
    fun theEditorsLeftTurnLandsTheConfirmedCornersOnTheSamePhysicalPixels() {
        assertEditorRotationLandsOnTheSamePixels(MainScanRotation.LEFT)
    }

    @Test
    fun theWarpThePipelineWouldRunKeepsTheEditorsOwnCornersAtEveryTurn() {
        // The full production chain in one assertion: the state the user's presses produced, warped
        // through the frame the authoritative render rebuilds, un-rotated back to the capture. The
        // four corners that come out must be the four the user confirmed before rotating at all.
        for (direction in MainScanRotation.entries) {
            forEachSourceAndTurn { width, height, presses ->
                val state = pressRotate(direction, presses)
                val frame = MainScanAuthoritativeRender.orientedFrame(
                    width,
                    height,
                    state.rotationQuarterTurns
                )
                val plan = PerspectiveTransformEngine.plan(state.quad, frame.width, frame.height)
                val unrotated = plan.sourcePixels.map {
                    unrotatePixels(it, width, height, state.rotationQuarterTurns)
                }
                assertPointSetsMatch(
                    expected = UPRIGHT_QUAD.corners().map {
                        CropPoint(it.x * width, it.y * height)
                    },
                    actual = unrotated,
                    message = "$direction x$presses ${width}x$height: production warp corners"
                )
            }
        }
    }

    @Test
    fun fourEditorPressesInEitherDirectionReturnTheStateExactly() {
        for (direction in MainScanRotation.entries) {
            val state = pressRotate(direction, 4)
            assertEquals(
                "four presses must return the counter to zero",
                0,
                state.rotationQuarterTurns
            )
            assertQuadsMatch(
                expected = PerspectiveGeometry.normalize(UPRIGHT_QUAD),
                actual = state.quad,
                scaleX = PORTRAIT_WIDTH.toFloat(),
                scaleY = PORTRAIT_HEIGHT.toFloat(),
                message = "$direction x4 must be the identity"
            )
        }
    }

    // --- the editor-frame binding the authoritative render fails closed on -------------------------

    @Test
    fun theRealEditorFrameIsAcceptedAtEveryQuarterTurn() {
        forEachSourceAndTurn { width, height, turns ->
            val preview = previewFrameFor(width, height, turns)
            val reproduced = MainScanAuthoritativeRender.orientedFrame(width, height, turns)
            assertTrue(
                "${width}x$height q=$turns: the frame the editor really shows must be accepted",
                MainScanAuthoritativeRender.reproducesEditorFrame(
                    reproducedWidth = reproduced.width,
                    reproducedHeight = reproduced.height,
                    editorFrameWidth = preview.width,
                    editorFrameHeight = preview.height
                )
            )
        }
    }

    @Test
    fun anEditorFrameFromADifferentTurnCountIsRefusedAtEveryQuarterTurn() {
        // The mismatch that matters: the render rebuilt the frame for one turn count while the user
        // confirmed against another. At 90 and 270 the two frames are TRANSPOSED, which is exactly
        // the case where the polygon addresses a completely different part of the page.
        forEachSourceAndTurn { width, height, turns ->
            val reproduced = MainScanAuthoritativeRender.orientedFrame(width, height, turns)
            for (wrongTurns in 0..3) {
                if (wrongTurns % 2 == turns % 2) continue
                val preview = previewFrameFor(width, height, wrongTurns)
                assertFalse(
                    "${width}x$height q=$turns: a q=$wrongTurns editor frame must be refused",
                    MainScanAuthoritativeRender.reproducesEditorFrame(
                        reproducedWidth = reproduced.width,
                        reproducedHeight = reproduced.height,
                        editorFrameWidth = preview.width,
                        editorFrameHeight = preview.height
                    )
                )
            }
        }
    }

    @Test
    fun thePipelinePassesTheEditorsOwnFrameRatherThanRederivingIt() {
        // A re-derivation would agree with the render by construction and could never disagree with
        // it — which is the whole point of the check. The dimensions must come from the working
        // image the crop editor was displaying.
        val body = functionBody(activitySource(), "renderMainScanAuthoritative")
        assertTrue(
            "the editor frame must be passed from the working image itself",
            body.contains("editorFrameWidth = editorFrame.width") &&
                body.contains("editorFrameHeight = editorFrame.height")
        )
        assertTrue(
            "and it must be the working image the advance actually handed over",
            functionBody(activitySource(), "advanceMainScanCrop")
                .contains("editorFrame = working")
        )
    }

    // --- helpers ---------------------------------------------------------------------------------

    /** [presses] of the real Left/Right control, from a fresh state holding [UPRIGHT_QUAD]. */
    private fun pressRotate(direction: MainScanRotation, presses: Int): MainScanCropState {
        var state = MainScanCropState(quad = PerspectiveGeometry.normalize(UPRIGHT_QUAD))
        repeat(presses) { state = MainScanCropEditor.rotate(state, direction) }
        return state
    }

    /** The quarter turns [presses] of [direction] leave on the counter the render is handed. */
    private fun expectedTurns(direction: MainScanRotation, presses: Int): Int =
        MainScanAuthoritativeRender.normalizeQuarterTurns(
            if (direction == MainScanRotation.RIGHT) presses else -presses
        )

    private fun assertEditorRotationMatchesMapping(direction: MainScanRotation) {
        for (presses in 0..3) {
            val state = pressRotate(direction, presses)
            val turns = expectedTurns(direction, presses)
            assertEquals(
                "$direction x$presses must leave $turns quarter turns on the shared counter",
                turns,
                state.rotationQuarterTurns
            )
            // Role by role, not merely as a set: after a quarter turn the corner that WAS
            // bottom-left is the new top-left, and a renderer walking a quad whose roles disagree
            // with the mapping draws a bow-tie.
            val mapped = MainScanAuthoritativeRender.toEditorQuad(UPRIGHT_QUAD, turns)
            for (corner in CropCorner.entries) {
                assertPointsMatch(
                    expected = CropPoint(
                        mapped.corner(corner).x * PORTRAIT_WIDTH,
                        mapped.corner(corner).y * PORTRAIT_HEIGHT
                    ),
                    actual = CropPoint(
                        state.quad.corner(corner).x * PORTRAIT_WIDTH,
                        state.quad.corner(corner).y * PORTRAIT_HEIGHT
                    ),
                    message = "$direction x$presses $corner"
                )
            }
        }
    }

    private fun assertEditorRotationLandsOnTheSamePixels(direction: MainScanRotation) {
        forEachSourceAndTurn { width, height, presses ->
            val state = pressRotate(direction, presses)
            val frame = MainScanAuthoritativeRender.orientedFrame(
                width,
                height,
                state.rotationQuarterTurns
            )
            val editorPixels = state.quad.corners().map {
                CropPoint(it.x * frame.width, it.y * frame.height)
            }
            val expectedPixels = UPRIGHT_QUAD.corners().map { corner ->
                rotatePixels(
                    point = CropPoint(corner.x * width, corner.y * height),
                    uprightWidth = width,
                    uprightHeight = height,
                    turns = state.rotationQuarterTurns
                )
            }
            assertPointSetsMatch(
                expected = expectedPixels,
                actual = editorPixels,
                message = "$direction x$presses ${width}x$height: same physical pixels"
            )
        }
    }

    /**
     * The frame the crop editor is REALLY showing for a capture of these dimensions at [turns]: the
     * interactive loader's own power-of-two downsample, then the same quarter turns. Built through
     * the production sample-size rule rather than a constant, so a change to the preview bound moves
     * this expectation with it.
     */
    private fun previewFrameFor(uprightWidth: Int, uprightHeight: Int, turns: Int) =
        MainScanCaptureImageLoader
            .computeInSampleSize(
                uprightWidth,
                uprightHeight,
                MainScanCaptureImageLoader.MAX_WORKING_EDGE
            )
            .let { sample ->
                MainScanAuthoritativeRender.orientedFrame(
                    uprightWidth / sample,
                    uprightHeight / sample,
                    turns
                )
            }

    /** The real MainActivity source — the same two-candidate lookup [processorSource] uses. */
    private fun activitySource(): String {
        val candidates = listOf(
            File("src/main/java/com/dev/docscannerpdf/MainActivity.kt"),
            File("app/src/main/java/com/dev/docscannerpdf/MainActivity.kt")
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull("could not locate MainActivity.kt", found)
        return found!!.readText()
    }

    private fun forEachSourceAndTurn(block: (width: Int, height: Int, turns: Int) -> Unit) {
        for ((width, height) in listOf(
            PORTRAIT_WIDTH to PORTRAIT_HEIGHT,
            LANDSCAPE_WIDTH to LANDSCAPE_HEIGHT
        )) {
            for (turns in 0..3) block(width, height, turns)
        }
    }

    /** The plan the authoritative path would produce for [turns], built from the same two helpers. */
    private fun planFor(uprightWidth: Int, uprightHeight: Int, turns: Int) =
        PerspectiveTransformEngine.plan(
            normalizedQuad = MainScanAuthoritativeRender.toEditorQuad(UPRIGHT_QUAD, turns),
            sourceWidth = MainScanAuthoritativeRender.orientedFrame(
                uprightWidth,
                uprightHeight,
                turns
            ).width,
            sourceHeight = MainScanAuthoritativeRender.orientedFrame(
                uprightWidth,
                uprightHeight,
                turns
            ).height
        )

    /**
     * [turns] clockwise quarter turns of a pixel point in a [uprightWidth] x [uprightHeight] image.
     * One turn maps (x, y) to (height - y, x) and the image to height x width.
     */
    private fun rotatePixels(
        point: CropPoint,
        uprightWidth: Int,
        uprightHeight: Int,
        turns: Int
    ): CropPoint {
        var current = point
        var width = uprightWidth
        var height = uprightHeight
        repeat(MainScanAuthoritativeRender.normalizeQuarterTurns(turns)) {
            current = CropPoint(height - current.y, current.x)
            val previousWidth = width
            width = height
            height = previousWidth
        }
        return current
    }

    /** The inverse of [rotatePixels]: back from the turned frame to the upright capture. */
    private fun unrotatePixels(
        point: CropPoint,
        uprightWidth: Int,
        uprightHeight: Int,
        turns: Int
    ): CropPoint {
        var current = point
        var remaining = MainScanAuthoritativeRender.normalizeQuarterTurns(turns)
        while (remaining > 0) {
            val frame = MainScanAuthoritativeRender.orientedFrame(
                uprightWidth,
                uprightHeight,
                remaining
            )
            current = CropPoint(current.y, frame.width - current.x)
            remaining--
        }
        return current
    }

    private fun assertPointsMatch(expected: CropPoint, actual: CropPoint, message: String) {
        assertTrue(
            "$message: expected (${expected.x}, ${expected.y}) but was (${actual.x}, ${actual.y})",
            abs(expected.x - actual.x) <= PIXEL_TOLERANCE &&
                abs(expected.y - actual.y) <= PIXEL_TOLERANCE
        )
    }

    /** Set equality within tolerance — proves nothing was lost, duplicated or moved. */
    private fun assertPointSetsMatch(
        expected: List<CropPoint>,
        actual: List<CropPoint>,
        message: String
    ) {
        assertEquals("$message: corner count", expected.size, actual.size)
        val remaining = expected.toMutableList()
        for (point in actual) {
            val match = remaining.indexOfFirst {
                abs(it.x - point.x) <= PIXEL_TOLERANCE && abs(it.y - point.y) <= PIXEL_TOLERANCE
            }
            assertTrue(
                "$message: (${point.x}, ${point.y}) matches no confirmed corner",
                match >= 0
            )
            remaining.removeAt(match)
        }
        assertTrue("$message: ${remaining.size} confirmed corners were dropped", remaining.isEmpty())
    }

    private fun assertQuadsMatch(
        expected: PerspectiveQuad,
        actual: PerspectiveQuad,
        scaleX: Float,
        scaleY: Float,
        message: String
    ) = assertPointSetsMatch(
        expected = expected.corners().map { CropPoint(it.x * scaleX, it.y * scaleY) },
        actual = actual.corners().map { CropPoint(it.x * scaleX, it.y * scaleY) },
        message = message
    )

    private fun assertDimension(message: String, expected: Int, actual: Int) {
        assertTrue(
            "$message: expected $expected but was $actual",
            abs(expected - actual) <= DIMENSION_TOLERANCE
        )
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

    /**
     * "Applied exactly once" is a property of the SEQUENCE, and this module has JUnit only — no
     * Robolectric, no mocking framework — so a call cannot be counted at runtime. It is counted in
     * the real source instead, exactly as [MainScanPipelineTeardownTest] asserts its teardown
     * calls, so adding a second warp or a second rotation fails the build.
     */
    private fun processorSource(): String {
        val candidates = listOf(
            File("src/main/java/com/dev/docscannerpdf/ui/mainscan/MainScanCaptureProcessor.kt"),
            File("app/src/main/java/com/dev/docscannerpdf/ui/mainscan/MainScanCaptureProcessor.kt")
        )
        val found = candidates.firstOrNull { it.isFile }
        assertNotNull(
            "could not locate MainScanCaptureProcessor.kt from ${File("").absolutePath}",
            found
        )
        return found!!.readText()
    }

    /** The body of [name], by brace matching from its declaration. */
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
}
