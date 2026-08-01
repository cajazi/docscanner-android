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
 * Side localization: the object is already selected correctly, but one side can still latch onto a
 * nearby stronger parallel background line.
 *
 * [topEdgeStaysOnThePackageNotTheStripAbove] reproduces the device failure directly — a small
 * package with a bright horizontal strip just above it — and is the reason this stage exists.
 */
class MainScanQuadRefinerTest {

    private val width = 120
    private val height = 160

    private fun filled(value: Int) = IntArray(width * height) { value }

    private fun IntArray.paintRect(l: Int, t: Int, r: Int, b: Int, v: Int) {
        for (y in t until b) for (x in l until r) {
            if (x in 0 until width && y in 0 until height) this[y * width + x] = v
        }
    }

    private fun IntArray.paintTilted(
        cx: Int,
        cy: Int,
        halfWidth: Int,
        halfHeight: Int,
        degrees: Float,
        value: Int
    ) {
        val radians = degrees * Math.PI.toFloat() / 180f
        val c = cos(radians)
        val s = sin(radians)
        for (y in 0 until height) for (x in 0 until width) {
            val dx = (x - cx).toFloat()
            val dy = (y - cy).toFloat()
            if (abs(dx * c + dy * s) <= halfWidth && abs(-dx * s + dy * c) <= halfHeight) {
                this[y * width + x] = value
            }
        }
    }

    private fun frame(luma: IntArray) = LumaFrame(width, height, luma)

    // --- the device failure ---------------------------------------------------------------------------

    @Test
    fun topEdgeStaysOnThePackageNotTheStripAbove() {
        // The scene that failed on hardware: a rectangular package, a bright horizontal strip a short
        // way above it (the browser window behind), and a strong clutter edge lower down.
        val luma = filled(150)
        luma.paintRect(20, 34, 100, 46, 245)   // bright strip above the package
        luma.paintRect(26, 58, 94, 128, 80)    // the package
        luma.paintRect(0, 140, 120, 160, 40)   // strong clutter edge below

        val quad = MainScanEdgeQuadFinder.find(frame(luma))
        assertNotNull("the package must be found", quad)

        // The package's top edge is at y=58 -> 0.365. The strip's lower edge is at y=46 -> 0.289.
        // The polygon must sit on the package, not reach up to the strip.
        val top = minOf(quad!!.topLeft.y, quad.topRight.y)
        assertTrue("top edge reached the strip: top=$top quad=$quad", top > 0.33f)
        assertEquals(0.365f, top, 0.05f)
    }

    @Test
    fun bottomEdgeStaysOnTheDocumentNotTheTableBoundaryBelow() {
        val luma = filled(210)
        luma.paintRect(26, 30, 94, 104, 70)   // document
        luma.paintRect(0, 116, 120, 160, 55)  // table boundary just below

        val quad = MainScanEdgeQuadFinder.find(frame(luma))!!
        val bottom = maxOf(quad.bottomLeft.y, quad.bottomRight.y)
        // Document bottom is y=104 -> 0.654; the table edge is y=116 -> 0.729.
        assertTrue("bottom reached the table: $quad", bottom < 0.70f)
    }

    @Test
    fun sideEdgeStaysOnTheDocumentNotTheScreenBezelBeside() {
        val luma = filled(200)
        luma.paintRect(34, 30, 92, 120, 75)   // document
        luma.paintRect(20, 20, 26, 140, 30)   // dark bezel just to the left

        val quad = MainScanEdgeQuadFinder.find(frame(luma))!!
        val left = minOf(quad.topLeft.x, quad.bottomLeft.x)
        // Document left is x=34 -> 0.286; the bezel is x=20..26 -> 0.168..0.218.
        assertTrue("left reached the bezel: $quad", left > 0.25f)
    }

    @Test
    fun aStrongLineCrossingBehindTheObjectDoesNotMoveASide() {
        val luma = filled(205)
        luma.paintRect(0, 74, 120, 80, 35)    // strong line straight across the frame
        luma.paintRect(28, 36, 92, 122, 120)  // the document, crossed by it

        val quad = MainScanEdgeQuadFinder.find(frame(luma))
        if (quad != null) {
            val top = minOf(quad.topLeft.y, quad.topRight.y)
            val bottom = maxOf(quad.bottomLeft.y, quad.bottomRight.y)
            // The crossing line at y=74..80 must not become the top or bottom side.
            assertTrue("a side collapsed onto the crossing line: $quad", top < 0.42f)
            assertTrue("a side collapsed onto the crossing line: $quad", bottom > 0.52f)
        }
    }

    /**
     * Refinement acting directly on the exact defect, independent of what selection produces: a quad
     * whose three good sides sit on the package but whose TOP side sits on the strip above it.
     * Without a working refiner this quad comes back unchanged.
     */
    @Test
    fun anOvershootingTopSideIsPulledBackOntoTheObject() {
        val luma = filled(150)
        luma.paintRect(20, 34, 100, 46, 245)  // bright strip
        luma.paintRect(26, 58, 94, 128, 80)   // the package
        val evidence = MainScanEdgeQuadFinder.evidenceFor(frame(luma))!!

        val overshooting = quadOf(
            left = 26f, right = 94f,
            top = 46f,   // on the strip's lower edge, ~12px above the package
            bottom = 128f
        )
        val refined = MainScanQuadRefiner.refine(overshooting, evidence)

        val topBefore = overshooting.topLeft.y
        val topAfter = refined.topLeft.y
        assertTrue("refiner left the side on the strip: $refined", topAfter > topBefore + 0.02f)
        // The package's true top edge is y=58 -> 0.365.
        assertEquals(0.365f, topAfter, 0.04f)

        // The three already-correct sides must not have moved.
        assertEquals(overshooting.bottomLeft.y, refined.bottomLeft.y, 0.02f)
        assertEquals(overshooting.topLeft.x, refined.topLeft.x, 0.02f)
        assertEquals(overshooting.topRight.x, refined.topRight.x, 0.02f)
    }

    @Test
    fun aCorrectlyPlacedQuadIsLeftAlone() {
        val luma = filled(150)
        luma.paintRect(20, 34, 100, 46, 245)
        luma.paintRect(26, 58, 94, 128, 80)
        val evidence = MainScanEdgeQuadFinder.evidenceFor(frame(luma))!!

        val onTheObject = quadOf(left = 26f, right = 94f, top = 58f, bottom = 128f)
        val refined = MainScanQuadRefiner.refine(onTheObject, evidence)

        assertEquals(onTheObject.topLeft.y, refined.topLeft.y, 0.02f)
        assertEquals(onTheObject.topLeft.x, refined.topLeft.x, 0.02f)
        assertEquals(onTheObject.bottomRight.y, refined.bottomRight.y, 0.02f)
        assertEquals(onTheObject.bottomRight.x, refined.bottomRight.x, 0.02f)
    }

    /** Builds a normalized axis-aligned quad from pixel bounds. */
    private fun quadOf(left: Float, top: Float, right: Float, bottom: Float) =
        com.dev.docscannerpdf.domain.crop.PerspectiveQuad(
            com.dev.docscannerpdf.domain.crop.CropPoint(left / (width - 1), top / (height - 1)),
            com.dev.docscannerpdf.domain.crop.CropPoint(right / (width - 1), top / (height - 1)),
            com.dev.docscannerpdf.domain.crop.CropPoint(right / (width - 1), bottom / (height - 1)),
            com.dev.docscannerpdf.domain.crop.CropPoint(left / (width - 1), bottom / (height - 1))
        )

    // --- support shape ----------------------------------------------------------------------------------

    @Test
    fun aSideSupportedOnlyInTheMiddleIsNotPreferred() {
        // A short strong stub parallel to the document's top edge, centred on it but not reaching
        // either corner. Endpoint support must stop it from capturing the side.
        val luma = filled(200)
        luma.paintRect(30, 40, 90, 116, 85)   // document
        luma.paintRect(52, 30, 68, 33, 20)    // strong central stub above the top edge

        val quad = MainScanEdgeQuadFinder.find(frame(luma))!!
        val top = minOf(quad.topLeft.y, quad.topRight.y)
        // Document top is y=40 -> 0.252; the stub is y=30..33 -> 0.189..0.208.
        assertTrue("side moved onto the central stub: $quad", top > 0.225f)
    }

    @Test
    fun fourCoherentSidesWinOverAStrongerIncoherentLine() {
        // The document's own edges are faint; a much stronger line runs beside its top edge but
        // stops well short of both corners, so it cannot form the object's boundary.
        val luma = filled(180)
        luma.paintRect(30, 44, 90, 118, 172)  // faint document
        luma.paintRect(44, 34, 76, 37, 20)    // strong but disconnected line above

        val quad = MainScanEdgeQuadFinder.find(frame(luma))
        if (quad != null) {
            val top = minOf(quad.topLeft.y, quad.topRight.y)
            assertTrue("stronger disconnected line captured the side: $quad", top > 0.25f)
        }
    }

    // --- bounds and safety --------------------------------------------------------------------------------

    @Test
    fun refinementCannotJumpBeyondItsMaximumDistance() {
        // A very strong line far above the document. Even though it is the strongest thing in the
        // frame, it lies outside the permitted search band and must not be adopted.
        val luma = filled(200)
        luma.paintRect(0, 10, 120, 14, 20)    // strong distant line near the top of the frame
        luma.paintRect(30, 70, 90, 130, 90)   // document, far below it

        val quad = MainScanEdgeQuadFinder.find(frame(luma))
        if (quad != null) {
            val top = minOf(quad.topLeft.y, quad.topRight.y)
            // Document top y=70 -> 0.44; the distant line is y=14 -> 0.088.
            assertTrue("refinement jumped to a distant line: $quad", top > 0.30f)
        }
    }

    @Test
    fun refinementPreservesARotatedPage() {
        val luma = filled(215)
        luma.paintTilted(60, 80, 32, 46, 16f, 60)
        val quad = MainScanEdgeQuadFinder.find(frame(luma))!!
        assertTrue(PerspectiveGeometry.isConvex(quad))
        assertTrue("tilt lost: $quad", abs(quad.topLeft.x - quad.bottomLeft.x) > 0.04f)

        val area = abs(PerspectiveGeometry.signedArea(quad))
        val trueArea = (64f / (width - 1)) * (92f / (height - 1))
        assertEquals(trueArea, area, trueArea * 0.32f)
    }

    @Test
    fun refinementPreservesAPerspectiveTrapezoid() {
        // A page viewed at an angle: the top edge is narrower than the bottom.
        val luma = filled(210)
        for (y in 40 until 124) {
            val t = (y - 40).toFloat() / (124 - 40)
            val halfWidth = 18f + 16f * t
            val left = (60f - halfWidth).toInt()
            val right = (60f + halfWidth).toInt()
            for (x in left until right) if (x in 0 until width) luma[y * width + x] = 70
        }

        val quad = MainScanEdgeQuadFinder.find(frame(luma))!!
        assertTrue(PerspectiveGeometry.isConvex(quad))
        val topWidth = quad.topRight.x - quad.topLeft.x
        val bottomWidth = quad.bottomRight.x - quad.bottomLeft.x
        assertTrue("trapezoid flattened into a rectangle: $quad", bottomWidth > topWidth * 1.2f)
    }

    @Test
    fun anInvalidRefinementRetainsTheOriginalCandidate() {
        // A plain document with nothing nearby to refine toward: the result must be the clean
        // rectangle, unchanged and still valid.
        val luma = filled(205)
        luma.paintRect(28, 38, 92, 122, 65)
        val quad = MainScanEdgeQuadFinder.find(frame(luma))!!

        assertTrue(PerspectiveGeometry.isConvex(quad))
        assertTrue(MainScanQuadValidity.isApplicable(quad))
        assertEquals(0.235f, quad.topLeft.x, 0.05f)
        assertEquals(0.239f, quad.topLeft.y, 0.05f)
        assertEquals(0.765f, quad.bottomRight.x, 0.05f)
        assertEquals(0.761f, quad.bottomRight.y, 0.05f)
    }

    @Test
    fun refinementNeverGrowsOntoTheFrameBoundary() {
        val luma = filled(200)
        luma.paintRect(10, 12, 110, 148, 80) // document filling most of the frame
        val quad = MainScanEdgeQuadFinder.find(frame(luma))
        if (quad != null) {
            for (corner in listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)) {
                assertTrue("corner reached the frame edge: $quad", corner.x > 0.01f && corner.x < 0.99f)
                assertTrue("corner reached the frame edge: $quad", corner.y > 0.01f && corner.y < 0.99f)
            }
        }
    }

    @Test
    fun refinementIsDeterministic() {
        val luma = filled(150)
        luma.paintRect(20, 34, 100, 46, 245)
        luma.paintRect(26, 58, 94, 128, 80)
        val first = MainScanEdgeQuadFinder.find(frame(luma))
        assertNotNull(first)
        repeat(4) { assertEquals(first, MainScanEdgeQuadFinder.find(frame(luma))) }
    }

    // --- temporal behaviour ---------------------------------------------------------------------------------

    @Test
    fun aClearlyBetterCandidateReplacesTheTemporalTrack() {
        val luma = filled(210)
        luma.paintRect(28, 40, 92, 124, 60)
        val stale = com.dev.docscannerpdf.domain.crop.PerspectiveQuad(
            com.dev.docscannerpdf.domain.crop.CropPoint(0.03f, 0.03f),
            com.dev.docscannerpdf.domain.crop.CropPoint(0.22f, 0.03f),
            com.dev.docscannerpdf.domain.crop.CropPoint(0.22f, 0.20f),
            com.dev.docscannerpdf.domain.crop.CropPoint(0.03f, 0.20f)
        )
        val quad = MainScanEdgeQuadFinder.find(frame(luma), previous = stale)!!
        assertEquals(0.235f, quad.topLeft.x, 0.06f)
        assertEquals(0.251f, quad.topLeft.y, 0.06f)
    }

    @Test
    fun aVanishedObjectCannotLeaveAStalePolygon() {
        val previous = MainScanEdgeQuadFinder.find(frame(filled(205).also { it.paintRect(28, 38, 92, 122, 65) }))
        assertNotNull(previous)
        assertNull(MainScanEdgeQuadFinder.find(frame(filled(140)), previous = previous))
    }

    @Test
    fun aNewSessionStartsWithNoTrackedQuad() {
        // The default really is "no history": a fresh call must equal an explicit null-previous call.
        val luma = filled(205)
        luma.paintRect(28, 38, 92, 122, 65)
        assertEquals(
            MainScanEdgeQuadFinder.find(frame(luma), previous = null),
            MainScanEdgeQuadFinder.find(frame(luma))
        )
    }
}
