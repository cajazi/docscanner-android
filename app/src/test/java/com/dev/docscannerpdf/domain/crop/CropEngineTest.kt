package com.dev.docscannerpdf.domain.crop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CropEngineTest {

    private fun quad(
        tl: Pair<Float, Float>,
        tr: Pair<Float, Float>,
        br: Pair<Float, Float>,
        bl: Pair<Float, Float>
    ) = PerspectiveQuad(
        topLeft = CropPoint(tl.first, tl.second),
        topRight = CropPoint(tr.first, tr.second),
        bottomRight = CropPoint(br.first, br.second),
        bottomLeft = CropPoint(bl.first, bl.second)
    )

    // --- Quad validation correctness ----------------------------------------

    @Test
    fun fullQuadIsConvexAndValid() {
        val full = PerspectiveQuad.full()
        assertTrue(PerspectiveGeometry.isConvex(full))
        assertTrue(PerspectiveGeometry.isValid(full))
        assertEquals(1f, abs(PerspectiveGeometry.signedArea(full)), 1e-4f)
    }

    @Test
    fun selfIntersectingQuadIsNotConvex() {
        // A bow-tie: top edge and bottom edge cross.
        val bowTie = quad(0f to 0f, 1f to 1f, 1f to 0f, 0f to 1f)
        assertFalse(PerspectiveGeometry.isConvex(bowTie))
    }

    @Test
    fun degenerateZeroAreaQuadIsInvalid() {
        val collapsed = quad(0f to 0f, 0f to 0f, 1f to 1f, 1f to 1f)
        assertFalse(PerspectiveGeometry.isValid(collapsed))
    }

    // --- Inversion correction -----------------------------------------------

    @Test
    fun orderCornersFixesInvertedInput() {
        // Corners supplied in a scrambled order.
        val scrambled = listOf(
            CropPoint(1f, 1f), // BR
            CropPoint(0f, 0f), // TL
            CropPoint(0f, 1f), // BL
            CropPoint(1f, 0f)  // TR
        )
        val ordered = PerspectiveGeometry.orderCorners(scrambled)
        assertEquals(CropPoint(0f, 0f), ordered.topLeft)
        assertEquals(CropPoint(1f, 0f), ordered.topRight)
        assertEquals(CropPoint(1f, 1f), ordered.bottomRight)
        assertEquals(CropPoint(0f, 1f), ordered.bottomLeft)
        assertTrue(PerspectiveGeometry.isConvex(ordered))
    }

    @Test
    fun autoFixTurnsBowTieIntoConvexQuad() {
        val bowTie = quad(0f to 0f, 1f to 1f, 1f to 0f, 0f to 1f)
        val fixed = PerspectiveGeometry.autoFixInverted(bowTie)
        assertTrue(PerspectiveGeometry.isConvex(fixed))
        assertTrue(PerspectiveGeometry.isValid(fixed))
    }

    // --- Corner movement reducer logic --------------------------------------

    @Test
    fun moveCornerUpdatesOnlyThatCornerAndClamps() {
        val state = CropState(quad = PerspectiveQuad.full())
        val moved = CropReducer.moveCorner(state, CropCorner.TOP_LEFT, 0.2f, 0.3f)

        assertEquals(CropPoint(0.2f, 0.3f), moved.quad.topLeft)
        assertEquals(CropPoint(1f, 0f), moved.quad.topRight) // others unchanged
        assertEquals(CropMode.EDITING, moved.mode)

        // Out-of-bounds drag is clamped into the unit square.
        val clamped = CropReducer.moveCorner(moved, CropCorner.BOTTOM_RIGHT, 1.5f, -0.4f)
        assertEquals(CropPoint(1f, 0f), clamped.quad.bottomRight)
    }

    @Test
    fun resetQuadRestoresOriginal() {
        val original = PerspectiveQuad.inset(0.1f)
        val state = CropState(quad = original, originalQuad = original)
        val moved = CropReducer.moveCorner(state, CropCorner.TOP_LEFT, 0.5f, 0.5f)
        assertNotEquals(original, moved.quad)

        val reset = CropReducer.resetQuad(moved)
        assertEquals(original, reset.quad)
        assertEquals(CropMode.EDITING, reset.mode)
    }

    // --- Apply / cancel state transitions -----------------------------------

    @Test
    fun applyValidQuadEntersApplyingWithFixedCorners() {
        val state = CropState(
            quad = quad(0.1f to 0.1f, 0.9f to 0.1f, 0.9f to 0.9f, 0.1f to 0.9f),
            mode = CropMode.EDITING
        )
        val applied = CropReducer.applyCrop(state)
        assertEquals(CropMode.APPLYING, applied.mode)
        assertTrue(PerspectiveGeometry.isValid(applied.quad))
    }

    @Test
    fun applyInvalidQuadStaysEditing() {
        val degenerate = CropState(
            quad = quad(0f to 0f, 0f to 0f, 0f to 0f, 0f to 0f),
            mode = CropMode.EDITING
        )
        val applied = CropReducer.applyCrop(degenerate)
        assertEquals(CropMode.EDITING, applied.mode)
        assertFalse(CropReducer.canApply(degenerate))
    }

    @Test
    fun cancelRestoresOriginalAndIdles() {
        val original = PerspectiveQuad.full()
        val state = CropState(quad = original, originalQuad = original, mode = CropMode.EDITING)
        val moved = CropReducer.moveCorner(state, CropCorner.TOP_RIGHT, 0.7f, 0.2f)
        val cancelled = CropReducer.cancelCrop(moved)

        assertEquals(original, cancelled.quad)
        assertEquals(CropMode.IDLE, cancelled.mode)
    }

    @Test
    fun finishApplyResetsQuadForNewlyCroppedImage() {
        val state = CropState(
            quad = PerspectiveQuad.inset(0.2f),
            originalQuad = PerspectiveQuad.inset(0.2f),
            mode = CropMode.APPLYING
        )
        val finished = CropReducer.finishApply(state, "file:///cropped.jpg")
        assertEquals(PerspectiveQuad.full(), finished.quad)
        assertEquals(PerspectiveQuad.full(), finished.originalQuad)
        assertEquals(CropMode.IDLE, finished.mode)
        assertEquals("file:///cropped.jpg", finished.sourceImageUri)
    }

    // --- Deterministic transform output -------------------------------------

    @Test
    fun homographyMapsSourceCornersOntoDestination() {
        val src = listOf(
            CropPoint(10f, 20f),
            CropPoint(200f, 30f),
            CropPoint(220f, 260f),
            CropPoint(5f, 250f)
        )
        val dst = listOf(
            CropPoint(0f, 0f),
            CropPoint(300f, 0f),
            CropPoint(300f, 400f),
            CropPoint(0f, 400f)
        )
        val matrix = WarpMatrixCalculator.computeHomography(src, dst)

        src.forEachIndexed { i, point ->
            val (mx, my) = matrix.mapPoint(point.x.toDouble(), point.y.toDouble())
            assertEquals(dst[i].x.toDouble(), mx, 1e-3)
            assertEquals(dst[i].y.toDouble(), my, 1e-3)
        }
    }

    @Test
    fun homographyIsDeterministic() {
        val src = listOf(
            CropPoint(0f, 0f), CropPoint(100f, 0f), CropPoint(100f, 100f), CropPoint(0f, 100f)
        )
        val dst = listOf(
            CropPoint(5f, 5f), CropPoint(95f, 10f), CropPoint(90f, 90f), CropPoint(10f, 95f)
        )
        val a = WarpMatrixCalculator.computeHomography(src, dst)
        val b = WarpMatrixCalculator.computeHomography(src, dst)
        assertEquals(a, b)
        assertTrue(a.values.contentEquals(b.values))
    }

    @Test
    fun transformPlanProducesPositiveOutputAndMapsCornersToRect() {
        val quad = quad(0.1f to 0.1f, 0.9f to 0.12f, 0.88f to 0.9f, 0.12f to 0.88f)
        val plan = PerspectiveTransformEngine.plan(quad, sourceWidth = 1000, sourceHeight = 1400)

        assertTrue(plan.outputWidth > 0)
        assertTrue(plan.outputHeight > 0)
        // Source corners map onto the destination rectangle corners.
        plan.sourcePixels.forEachIndexed { i, point ->
            val (mx, my) = plan.matrix.mapPoint(point.x.toDouble(), point.y.toDouble())
            assertEquals(plan.destinationPixels[i].x.toDouble(), mx, 1e-2)
            assertEquals(plan.destinationPixels[i].y.toDouble(), my, 1e-2)
        }
    }
}
