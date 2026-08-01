package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import com.dev.docscannerpdf.domain.detection.LumaFrame
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Edge-based finding, and specifically the scene that defeated brightness segmentation.
 *
 * [anObjectTonallySimilarToTheFloorIsStillFound] is the physical-QA failure reproduced: an object
 * whose luma nearly matches its background, surrounded by darker clutter (a hand, a shadow). Region
 * growing selects the clutter; edge finding selects the object.
 */
class MainScanEdgeQuadFinderTest {

    private val width = 120
    private val height = 160

    private fun frame(luma: IntArray) = LumaFrame(width, height, luma)

    private fun filled(value: Int) = IntArray(width * height) { value }

    private fun IntArray.paintRect(left: Int, top: Int, right: Int, bottom: Int, value: Int) {
        for (y in top until bottom) {
            for (x in left until right) {
                if (x in 0 until width && y in 0 until height) this[y * width + x] = value
            }
        }
    }

    /** Paints a rectangle rotated by [degrees] about its centre. */
    private fun IntArray.paintTilted(
        centreX: Int,
        centreY: Int,
        halfWidth: Int,
        halfHeight: Int,
        degrees: Float,
        value: Int
    ) {
        val radians = degrees * Math.PI.toFloat() / 180f
        val c = cos(radians)
        val s = sin(radians)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val dx = (x - centreX).toFloat()
                val dy = (y - centreY).toFloat()
                val localX = dx * c + dy * s
                val localY = -dx * s + dy * c
                if (abs(localX) <= halfWidth && abs(localY) <= halfHeight) {
                    this[y * width + x] = value
                }
            }
        }
    }

    // --- the regression this finder exists for -----------------------------------------------------

    @Test
    fun anObjectTonallySimilarToTheFloorIsStillFound() {
        // Floor at 180. The object at 172 is almost the same tone — brightness segmentation cannot
        // separate it. A hand (90) and its shadow (110) sprawl across the lower frame and ARE
        // tonally distinct, so a "largest foreground region" fit lands on them instead.
        val luma = filled(180)
        luma.paintRect(0, 120, 120, 160, 90)    // hand / dark clutter, large
        luma.paintRect(20, 100, 100, 130, 110)  // shadow, also large
        luma.paintRect(30, 30, 92, 108, 172)    // the object — barely differs from the floor

        val quad = MainScanEdgeQuadFinder.find(frame(luma))
        assertNotNull("the object's edges must be found despite matching tone", quad)
        val q = quad!!

        // The object spans x 30..91 (0.252..0.765) and y 30..107 (0.189..0.673).
        assertEquals(0.252f, q.topLeft.x, 0.06f)
        assertEquals(0.189f, q.topLeft.y, 0.06f)
        assertEquals(0.765f, q.bottomRight.x, 0.06f)
        assertEquals(0.673f, q.bottomRight.y, 0.06f)

        // And it must not have been dragged down into the clutter.
        assertTrue("must not reach the hand", q.bottomLeft.y < 0.80f)
    }

    @Test
    fun theRegionFinderFailsOnThatSameSceneWhichIsWhyEdgesRunFirst() {
        val luma = filled(180)
        luma.paintRect(0, 120, 120, 160, 90)
        luma.paintRect(20, 100, 100, 130, 110)
        luma.paintRect(30, 30, 92, 108, 172)

        val region = MainScanDocumentFinder.findByRegion(frame(luma))
        val edges = MainScanEdgeQuadFinder.find(frame(luma))!!
        if (region != null) {
            // Whatever the region fit produced, it is not the object: it reaches into the clutter.
            assertTrue(
                "region fit unexpectedly tight: $region",
                region.bottomLeft.y > 0.80f || abs(PerspectiveGeometry.signedArea(region)) >
                    abs(PerspectiveGeometry.signedArea(edges)) * 1.3f
            )
        }
    }

    @Test
    fun sidesAreNotBorrowedFromTwoDifferentObjects() {
        // Two objects that do not overlap in either axis: a tall narrow one on the left and a wide
        // flat one on the right-below. Their edges could be combined into a plausible-looking
        // rectangle whose every side is "drawn on" somewhere, but whose corners meet in empty space.
        // That is the on-device failure — the package's left edge with the laptop screen's top edge.
        val luma = filled(200)
        luma.paintRect(18, 20, 46, 140, 70)   // tall narrow object, left
        luma.paintRect(70, 96, 112, 126, 70)  // wide flat object, lower right

        val quad = MainScanEdgeQuadFinder.find(frame(luma))
        if (quad != null) {
            // Whatever is returned must be one of the two objects, not a hybrid spanning both.
            val left = minOf(quad.topLeft.x, quad.bottomLeft.x)
            val right = maxOf(quad.topRight.x, quad.bottomRight.x)
            val spansBoth = left < 46f / (width - 1) && right > 70f / (width - 1)
            assertTrue("quad spans both objects: $quad", !spansBoth)
        }
    }

    // --- ordinary documents ---------------------------------------------------------------------------

    @Test
    fun aPlainRectangleIsFoundTightly() {
        val luma = filled(210)
        luma.paintRect(25, 35, 95, 125, 60)
        val q = MainScanEdgeQuadFinder.find(frame(luma))!!
        assertEquals(0.210f, q.topLeft.x, 0.05f)
        assertEquals(0.220f, q.topLeft.y, 0.05f)
        assertEquals(0.790f, q.bottomRight.x, 0.05f)
        assertEquals(0.780f, q.bottomRight.y, 0.05f)
    }

    @Test
    fun aTiltedPageIsFollowedAlongItsEdges() {
        val luma = filled(215)
        luma.paintTilted(60, 80, 34, 48, 18f, 55)
        val q = MainScanEdgeQuadFinder.find(frame(luma))!!
        assertTrue(PerspectiveGeometry.isConvex(q))

        // A tilted page has corners that are NOT axis-aligned: the topmost corner must sit clearly
        // inboard of the leftmost one, otherwise we produced a bounding box.
        assertTrue("tilt must be followed, not boxed", abs(q.topLeft.x - q.bottomLeft.x) > 0.05f)

        // And the area must be near the true area (68 x 96 px), not the bounding box.
        val area = abs(PerspectiveGeometry.signedArea(q))
        val trueArea = (68f / (width - 1)) * (96f / (height - 1))
        assertEquals(trueArea, area, trueArea * 0.30f)
    }

    @Test
    fun theResultIsOrderedTopLeftFirst() {
        val luma = filled(200)
        luma.paintRect(30, 40, 90, 120, 70)
        val q = MainScanEdgeQuadFinder.find(frame(luma))!!
        assertTrue(q.topLeft.x < q.topRight.x)
        assertTrue(q.topLeft.y < q.bottomLeft.y)
        assertTrue(q.bottomRight.x > q.bottomLeft.x)
    }

    // --- temporal continuity ------------------------------------------------------------------------------

    @Test
    fun aTiedSceneResolvesTowardTheQuadAlreadyTracked() {
        // Two rectangles placed as exact mirrors about the frame centre, so they are identical on
        // every intrinsic term the scorer uses — same size, same shape, same distance to the frame
        // edge. Which one wins must therefore depend on what was being tracked, otherwise the choice
        // flips frame to frame and the guide never stabilises.
        val luma = filled(205)
        luma.paintRect(14, 22, 52, 74, 60)
        luma.paintRect(68, 86, 106, 138, 60)

        val upperLeft = MainScanEdgeQuadFinder.find(
            frame(luma),
            previous = MainScanEdgeQuadFinder.find(frame(makeSingle(14, 22, 52, 74)))
        )
        val lowerRight = MainScanEdgeQuadFinder.find(
            frame(luma),
            previous = MainScanEdgeQuadFinder.find(frame(makeSingle(68, 86, 106, 138)))
        )

        assertNotNull(upperLeft)
        assertNotNull(lowerRight)
        assertTrue(
            "tracking must decide the tie: upperLeft=$upperLeft lowerRight=$lowerRight",
            upperLeft!!.topLeft.y < lowerRight!!.topLeft.y
        )
    }

    private fun makeSingle(l: Int, t: Int, r: Int, b: Int): IntArray {
        val luma = filled(205)
        luma.paintRect(l, t, r, b, 60)
        return luma
    }

    @Test
    fun continuityCannotResurrectAQuadThatIsNoLongerThere() {
        // The previously tracked quad must never be returned on its own merits: an empty scene stays
        // empty, however confidently the last frame was tracked.
        val previous = MainScanEdgeQuadFinder.find(frame(makeSingle(25, 35, 95, 125)))
        assertNotNull(previous)
        assertNull(MainScanEdgeQuadFinder.find(frame(filled(128)), previous = previous))
    }

    @Test
    fun continuityDoesNotOverrideAClearlyBetterCandidate() {
        // A weak, partly-formed shape tracked previously must not hold the lock against a strong,
        // unambiguous document elsewhere in the frame.
        val luma = filled(210)
        luma.paintRect(28, 40, 96, 130, 55)
        val stale = PerspectiveQuadOf(0.02f, 0.02f, 0.20f, 0.20f)

        val quad = MainScanEdgeQuadFinder.find(frame(luma), previous = stale)!!
        assertEquals(0.235f, quad.topLeft.x, 0.06f)
        assertEquals(0.251f, quad.topLeft.y, 0.06f)
    }

    private fun PerspectiveQuadOf(l: Float, t: Float, r: Float, b: Float) =
        com.dev.docscannerpdf.domain.crop.PerspectiveQuad(
            com.dev.docscannerpdf.domain.crop.CropPoint(l, t),
            com.dev.docscannerpdf.domain.crop.CropPoint(r, t),
            com.dev.docscannerpdf.domain.crop.CropPoint(r, b),
            com.dev.docscannerpdf.domain.crop.CropPoint(l, b)
        )

    // --- rejection ----------------------------------------------------------------------------------------

    @Test
    fun aFlatSceneYieldsNothing() {
        assertNull(MainScanEdgeQuadFinder.find(frame(filled(128))))
    }

    @Test
    fun aFrameTooSmallToAnalyseYieldsNothing() {
        assertNull(MainScanEdgeQuadFinder.find(LumaFrame(8, 8, IntArray(64) { 40 })))
    }

    @Test
    fun aSingleEdgeIsNotADocument() {
        // A horizon: one strong line, no second pair. Must not be turned into a quad.
        val luma = filled(200)
        luma.paintRect(0, 0, 120, 70, 60)
        assertNull(MainScanEdgeQuadFinder.find(frame(luma)))
    }

    // --- determinism -----------------------------------------------------------------------------------------

    @Test
    fun findingIsDeterministic() {
        val luma = filled(205)
        luma.paintTilted(60, 80, 30, 44, 11f, 65)
        val first = MainScanEdgeQuadFinder.find(frame(luma))
        assertNotNull(first)
        repeat(4) { assertEquals(first, MainScanEdgeQuadFinder.find(frame(luma))) }
    }
}
