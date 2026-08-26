package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import com.dev.docscannerpdf.domain.detection.LumaFrame
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
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

    /** Paints a straight line of the given thickness between two points. */
    private fun IntArray.paintLine(
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        value: Int,
        thickness: Int = 1
    ) {
        val steps = maxOf(abs(x1 - x0), abs(y1 - y0)).coerceAtLeast(1)
        val reach = thickness / 2
        for (s in 0..steps) {
            val t = s.toFloat() / steps
            val x = (x0 + (x1 - x0) * t).roundToInt()
            val y = (y0 + (y1 - y0) * t).roundToInt()
            for (dy in -reach..reach) {
                for (dx in -reach..reach) {
                    val xx = x + dx
                    val yy = y + dy
                    if (xx in 0 until width && yy in 0 until height) this[yy * width + xx] = value
                }
            }
        }
    }

    /** A field of long parallel seams at [degrees], repeating every [spacing] pixels. */
    private fun IntArray.paintSeamField(degrees: Float, spacing: Int, value: Int, thickness: Int = 2) {
        val radians = degrees * Math.PI.toFloat() / 180f
        val dx = cos(radians)
        val dy = sin(radians)
        val span = width + height
        var offset = -span
        while (offset <= span) {
            // A point on this seam, and a direction along it.
            val px = width / 2f - dy * offset
            val py = height / 2f + dx * offset
            paintLine(
                (px - dx * span).roundToInt(),
                (py - dy * span).roundToInt(),
                (px + dx * span).roundToInt(),
                (py + dy * span).roundToInt(),
                value,
                thickness
            )
            offset += spacing
        }
    }

    /**
     * Fades a disc of the image toward [background], strongest at its centre.
     *
     * Models shadow pooling in a corner: the geometry is untouched and both sides still run all the
     * way in, but the contrast right at the corner is gone, so the edge mask has a hole there.
     */
    private fun IntArray.softenAround(cx: Int, cy: Int, radius: Int, background: Int) {
        for (y in (cy - radius)..(cy + radius)) {
            for (x in (cx - radius)..(cx + radius)) {
                if (x !in 0 until width || y !in 0 until height) continue
                val distance = kotlin.math.hypot((x - cx).toFloat(), (y - cy).toFloat())
                if (distance > radius) continue
                val strength = 1f - distance / radius
                val current = this[y * width + x]
                this[y * width + x] = (current + (background - current) * strength).roundToInt()
            }
        }
    }

    /** Asserts the quad outlines the pixel rectangle [left, right) x [top, bottom). */
    private fun assertOutlines(
        quad: PerspectiveQuad?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        tolerance: Float = 0.08f,
        message: String = ""
    ) {
        assertNotNull("$message: nothing detected", quad)
        val q = quad!!
        val wScale = (width - 1).toFloat()
        val hScale = (height - 1).toFloat()
        assertEquals("$message topLeft.x of $q", left / wScale, q.topLeft.x, tolerance)
        assertEquals("$message topLeft.y of $q", top / hScale, q.topLeft.y, tolerance)
        assertEquals("$message bottomRight.x of $q", (right - 1) / wScale, q.bottomRight.x, tolerance)
        assertEquals("$message bottomRight.y of $q", (bottom - 1) / hScale, q.bottomRight.y, tolerance)
    }

    /** Shortest side of a quad, in normalized units — the sliver detector. */
    private fun shortestSide(quad: PerspectiveQuad): Float {
        val c = listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
        var shortest = Float.MAX_VALUE
        for (i in c.indices) {
            val a = c[i]
            val b = c[(i + 1) % 4]
            shortest = minOf(shortest, kotlin.math.hypot(a.x - b.x, a.y - b.y))
        }
        return shortest
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

    // --- competing structure in the background --------------------------------------------------------

    /**
     * The shape of the real-scene failure, in synthetic form.
     *
     * A patterned surface — tiling, panelling, floorboards — supplies many long straight seams in a
     * couple of directions. Because a Hough peak's votes grow with how far its line runs across the
     * frame, those seams out-poll every edge of an object sitting on that surface, and four of them
     * meet in a perfectly convincing rectangle. The object has to win anyway.
     */
    @Test
    fun aDocumentBeatsARepeatingSeamFieldThatOutPollsIt() {
        val luma = filled(196)
        luma.paintSeamField(degrees = 34f, spacing = 26, value = 148)
        luma.paintSeamField(degrees = 124f, spacing = 30, value = 152)
        luma.paintRect(30, 44, 92, 118, 74)

        val quad = MainScanEdgeQuadFinder.find(frame(luma))
        assertOutlines(quad, 30, 44, 92, 118, message = "seam field beat the document")
    }

    @Test
    fun aSingleDominantSeamDirectionCannotCrowdOutThePerpendicularEdges() {
        // Only ONE background direction repeats, and it repeats strongly. The object's edges running
        // the same way are buried in that family; its perpendicular edges are the only ones left.
        // If the line budget is spent by strength alone, one whole side of the object is never
        // considered and no quad can close.
        val luma = filled(200)
        luma.paintSeamField(degrees = 0f, spacing = 12, value = 150, thickness = 2)
        luma.paintRect(28, 40, 94, 122, 70)

        val quad = MainScanEdgeQuadFinder.find(frame(luma))
        assertOutlines(quad, 28, 40, 94, 122, message = "dominant seam direction crowded out the object")
    }

    @Test
    fun aBackgroundRectangleDoesNotWinOverARealDocument() {
        // A clean, well-supported rectangle outlined on the background elsewhere in the frame — the
        // "floor grid cell" case. It is a flawless rectangle; it is simply not the object.
        val luma = filled(205)
        luma.paintLine(8, 10, 52, 10, 148, 2)
        luma.paintLine(8, 44, 52, 44, 148, 2)
        luma.paintLine(8, 10, 8, 44, 148, 2)
        luma.paintLine(52, 10, 52, 44, 148, 2)
        luma.paintRect(30, 62, 96, 134, 68)

        val quad = MainScanEdgeQuadFinder.find(frame(luma))
        assertOutlines(quad, 30, 62, 96, 134, message = "background rectangle won")
    }

    // --- evidence that is present but weak -------------------------------------------------------------

    @Test
    fun aShadowedCornerDoesNotDisqualifyAnOtherwiseSupportedDocument() {
        // Shadow pooled at one corner erases the contrast right there while leaving the geometry and
        // both adjoining sides intact. Requiring a mask pixel exactly at every corner rejects this —
        // and it is a real page, photographed the way real pages are lit.
        val luma = filled(206)
        luma.paintRect(26, 38, 94, 124, 66)
        luma.softenAround(cx = 26, cy = 38, radius = 7, background = 206)

        val quad = MainScanEdgeQuadFinder.find(frame(luma))
        assertOutlines(quad, 26, 38, 94, 124, message = "shadowed corner lost the document")
    }

    @Test
    fun acornerThatNeverExistedIsStillRejected() {
        // The other half of the corner rule, and the reason the fallback asks about the SIDES rather
        // than simply lowering the bar: two objects that do not touch must not be stitched into one
        // quad just because their lines happen to cross somewhere.
        val luma = filled(200)
        luma.paintRect(16, 22, 44, 138, 72)   // tall narrow object on the left
        luma.paintRect(68, 96, 110, 128, 72)  // separate flat object, lower right

        val quad = MainScanEdgeQuadFinder.find(frame(luma))
        if (quad != null) {
            val left = minOf(quad.topLeft.x, quad.bottomLeft.x)
            val right = maxOf(quad.topRight.x, quad.bottomRight.x)
            assertTrue(
                "stitched a quad across two separate objects: $quad",
                !(left < 44f / (width - 1) && right > 68f / (width - 1))
            )
        }
    }

    @Test
    fun aWeaklyContrastedSideStillClosesTheQuad() {
        // One side of the page sits against a surface close to its own tone. That edge polls far
        // below the other three, which is exactly the case a strength-ordered budget discards.
        val luma = filled(208)
        luma.paintRect(28, 40, 92, 120, 68)
        luma.paintRect(92, 40, 108, 120, 96) // low-contrast surround along the right edge only

        val quad = MainScanEdgeQuadFinder.find(frame(luma))
        assertOutlines(quad, 28, 40, 92, 120, message = "weak side was never paired")
    }

    // --- content inside a legitimate document -----------------------------------------------------------

    /**
     * Printed rules, folds and panel joins all cross a genuine page and are all well drawn on.
     * Treating an interior line as proof that the boundary must lie inside the candidate punishes
     * exactly the documents that have content on them — which is most of them. What these assert is
     * that content on a page cannot push that page below the background around it, and cannot drag
     * its boundary out onto something else.
     *
     * ## KNOWN LIMITATION, deliberately recorded rather than asserted
     *
     * When an interior mark is as strongly drawn as the outer boundary AND runs the full span of the
     * page, the region it cuts off is a clean rectangle bounded by four real, equally supported
     * lines — geometrically indistinguishable from a smaller document sitting there. Measured, the
     * detector then takes the sub-rectangle: the outer boundary leads only on coverage, by about
     * four percent, and any interior-crossing penalty at all erases that lead.
     *
     * This is architectural, not a tuning slip. It was checked three ways — a weaker interior mark,
     * an inset one, and a sharper corner rule — and none of them moves it, because nothing in the
     * edge evidence says which of two equally good rectangles is the object. The tempting fix,
     * leaning harder on interior crossing, is precisely the setting that lost the real scene, and
     * making larger area win outright would defeat the sliver correction that made this slice work.
     * Resolving it needs evidence this detector does not currently compute — region tone either side
     * of a candidate side, say — which is a separate piece of work.
     */
    @Test
    fun contentOnAPageDoesNotSurrenderItToTheBackground() {
        val luma = filled(210)
        luma.paintSeamField(degrees = 28f, spacing = 28, value = 156)
        luma.paintRect(26, 36, 94, 126, 64)
        luma.paintLine(38, 81, 82, 81, 132, 3) // a printed rule across the page

        val quad = MainScanEdgeQuadFinder.find(frame(luma))
        assertNotNull("a marked page in a patterned scene found nothing", quad)
        val q = quad!!
        // The page's own vertical boundary must survive: content may not drag a side onto a seam.
        assertEquals("left boundary of $q", 26 / (width - 1f), q.topLeft.x, 0.08f)
        assertEquals("right boundary of $q", 93 / (width - 1f), q.bottomRight.x, 0.08f)
        assertTrue("returned a sliver: $q", shortestSide(q) > 0.12f)
    }

    @Test
    fun aNestedPanelDoesNotDragTheBoundaryOffThePage() {
        val luma = filled(212)
        luma.paintRect(24, 34, 96, 128, 62)
        luma.paintRect(40, 56, 80, 104, 104) // a printed panel: real, and weaker than the silhouette

        val quad = MainScanEdgeQuadFinder.find(frame(luma))
        assertNotNull("a panelled page found nothing", quad)
        val q = quad!!
        // Whatever is chosen must lie ON the page — never wider than it, never off it entirely.
        for (corner in listOf(q.topLeft, q.topRight, q.bottomRight, q.bottomLeft)) {
            assertTrue("corner left of the page: $corner", corner.x > 24 / (width - 1f) - 0.06f)
            assertTrue("corner right of the page: $corner", corner.x < 95 / (width - 1f) + 0.06f)
            assertTrue("corner above the page: $corner", corner.y > 34 / (height - 1f) - 0.06f)
            assertTrue("corner below the page: $corner", corner.y < 127 / (height - 1f) + 0.06f)
        }
        assertTrue("returned a sliver: $q", shortestSide(q) > 0.12f)
    }

    // --- degenerate candidates ----------------------------------------------------------------------------

    @Test
    fun aSceneOfThinBandsDoesNotYieldASliverQuad() {
        // Slivers are geometrically perfect rectangles and there are enormous numbers of them among
        // arbitrary line intersections. Nothing here is a document, so either nothing is returned or
        // whatever is returned is not a hairline.
        val luma = filled(200)
        luma.paintLine(10, 40, 110, 40, 70, 2)
        luma.paintLine(10, 46, 110, 46, 70, 2)
        luma.paintLine(10, 120, 110, 120, 70, 2)
        luma.paintLine(10, 126, 110, 126, 70, 2)

        val quad = MainScanEdgeQuadFinder.find(frame(luma))
        if (quad != null) {
            assertTrue(
                "returned a sliver: shortestSide=${shortestSide(quad)} quad=$quad",
                shortestSide(quad) > 0.12f
            )
        }
    }

    @Test
    fun everyReturnedQuadHasRealWidthOnBothAxes() {
        // A property that must hold across all the scenes above, not just the degenerate one.
        val scenes = listOf(
            filled(205).also { it.paintRect(25, 35, 95, 125, 60) },
            filled(196).also {
                it.paintSeamField(degrees = 34f, spacing = 26, value = 148)
                it.paintRect(30, 44, 92, 118, 74)
            },
            filled(210).also {
                it.paintRect(26, 36, 94, 126, 64)
                it.paintLine(26, 81, 93, 81, 150, 3)
            }
        )
        for ((index, luma) in scenes.withIndex()) {
            val quad = MainScanEdgeQuadFinder.find(frame(luma)) ?: continue
            assertTrue(
                "scene $index returned a hairline: shortestSide=${shortestSide(quad)} quad=$quad",
                shortestSide(quad) > 0.12f
            )
        }
    }

    // --- tone, scale and framing ----------------------------------------------------------------------------

    @Test
    fun aBrightDocumentOnADarkBackgroundIsFound() {
        val luma = filled(48)
        luma.paintRect(28, 40, 92, 120, 208)
        assertOutlines(MainScanEdgeQuadFinder.find(frame(luma)), 28, 40, 92, 120, message = "bright on dark")
    }

    @Test
    fun aDarkDocumentOnABrightBackgroundIsFound() {
        val luma = filled(208)
        luma.paintRect(28, 40, 92, 120, 48)
        assertOutlines(MainScanEdgeQuadFinder.find(frame(luma)), 28, 40, 92, 120, message = "dark on bright")
    }

    @Test
    fun aSmallDocumentIsStillFound() {
        val luma = filled(206)
        luma.paintRect(40, 58, 80, 104, 66)
        assertOutlines(MainScanEdgeQuadFinder.find(frame(luma)), 40, 58, 80, 104, message = "small document")
    }

    @Test
    fun aDocumentFillingMostOfTheFrameIsStillFound() {
        val luma = filled(204)
        luma.paintRect(8, 12, 112, 148, 64)
        assertOutlines(MainScanEdgeQuadFinder.find(frame(luma)), 8, 12, 112, 148, message = "near-frame document")
    }

    @Test
    fun aDocumentRunningOffTheFrameDoesNotProduceAWildQuad() {
        // Two sides are outside the image, so there is no honest outer boundary to report. Whatever
        // comes back must at least stay inside the frame and remain a plausible shape.
        val luma = filled(206)
        luma.paintRect(-20, 40, 70, 200, 64)

        val quad = MainScanEdgeQuadFinder.find(frame(luma))
        if (quad != null) {
            for (corner in listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)) {
                assertTrue("corner outside the frame: $corner", corner.x in -0.01f..1.01f)
                assertTrue("corner outside the frame: $corner", corner.y in -0.01f..1.01f)
            }
            assertTrue("clipped document produced a sliver: $quad", shortestSide(quad) > 0.12f)
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
