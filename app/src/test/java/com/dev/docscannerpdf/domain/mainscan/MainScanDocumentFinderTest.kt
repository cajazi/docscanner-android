package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import com.dev.docscannerpdf.domain.detection.DocumentEdgeDetector
import com.dev.docscannerpdf.domain.detection.LumaFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding the object rather than the frame.
 *
 * The decisive test here is [clutterInACornerCannotStretchTheQuad]: the previous detector took the
 * extreme points of every foreground pixel, so one dark blob in a corner dragged a corner out to
 * meet it and the "document" became the whole image. That is the physical-QA failure, reproduced
 * synthetically and asserted against.
 */
class MainScanDocumentFinderTest {

    private val width = 120
    private val height = 160
    private val background = 200
    private val objectLuma = 40

    /** Builds a frame with [background] everywhere, then paints the supplied rectangles darker. */
    private fun frameWith(vararg rects: IntArray): LumaFrame {
        val luma = IntArray(width * height) { background }
        for (r in rects) {
            val (left, top, right, bottom) = r
            for (y in top until bottom) {
                for (x in left until right) {
                    if (x in 0 until width && y in 0 until height) luma[y * width + x] = objectLuma
                }
            }
        }
        return LumaFrame(width, height, luma)
    }

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) =
        intArrayOf(left, top, right, bottom)

    // --- the object is found ----------------------------------------------------------------------

    @Test
    fun aCentredRectangleIsFoundTightly() {
        val quad = MainScanDocumentFinder.find(frameWith(rect(30, 40, 90, 120)))
        assertNotNull(quad)
        val q = quad!!
        // Expected normalized bounds: x 30/119..89/119, y 40/159..119/159.
        assertEquals(0.252f, q.topLeft.x, 0.04f)
        assertEquals(0.252f, q.topLeft.y, 0.04f)
        assertEquals(0.748f, q.bottomRight.x, 0.04f)
        assertEquals(0.748f, q.bottomRight.y, 0.04f)
    }

    @Test
    fun theResultIsConvexAndCorrectlyOrdered() {
        val quad = MainScanDocumentFinder.find(frameWith(rect(25, 35, 95, 125)))!!
        assertTrue(PerspectiveGeometry.isConvex(quad))
        assertTrue("TL left of TR", quad.topLeft.x < quad.topRight.x)
        assertTrue("TL above BL", quad.topLeft.y < quad.bottomLeft.y)
        assertTrue("BR right of BL", quad.bottomRight.x > quad.bottomLeft.x)
    }

    // --- the regression this exists for -------------------------------------------------------------

    @Test
    fun clutterInACornerCannotStretchTheQuad() {
        // A document in the middle plus an unrelated dark blob in the bottom-left corner — a shoe,
        // a shadow, anything. The quad must stay on the document.
        val cluttered = frameWith(
            rect(35, 40, 90, 115),   // the document
            rect(2, 145, 18, 158)    // clutter, far away, much smaller
        )

        val found = MainScanDocumentFinder.find(cluttered)!!
        assertTrue("must not reach the clutter corner", found.bottomLeft.x > 0.15f)
        assertTrue("must not reach the frame bottom", found.bottomLeft.y < 0.85f)

        // And demonstrate the contrast with the legacy approach on the identical frame: it is
        // dragged out to the corner, which is precisely why this finder exists.
        val legacy = DocumentEdgeDetector.detect(cluttered)?.quad
        if (legacy != null) {
            val legacyArea = kotlin.math.abs(PerspectiveGeometry.signedArea(legacy))
            val foundArea = kotlin.math.abs(PerspectiveGeometry.signedArea(found))
            assertTrue(
                "legacy=$legacyArea found=$foundArea — the fitted quad must be tighter",
                foundArea < legacyArea
            )
        }
    }

    @Test
    fun theLargestRegionWinsWhenTwoObjectsArePresent() {
        val quad = MainScanDocumentFinder.find(
            frameWith(
                rect(20, 20, 100, 110),  // large
                rect(40, 130, 60, 150)   // small
            )
        )!!
        // The small lower object must not be included.
        assertTrue("bottom edge stays above the small object", quad.bottomLeft.y < 0.80f)
    }

    @Test
    fun aTiltedObjectIsFollowedRatherThanBoundingBoxed() {
        // A diamond: its bounding box is much larger than the shape. Fitting the maximum-area quad
        // to the hull should track the tilted edges, so the corners land near the diamond's tips.
        val luma = IntArray(width * height) { background }
        val cx = width / 2
        val cy = height / 2
        val radius = 45
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (kotlin.math.abs(x - cx) + kotlin.math.abs(y - cy) <= radius) {
                    luma[y * width + x] = objectLuma
                }
            }
        }
        val quad = MainScanDocumentFinder.find(LumaFrame(width, height, luma))!!
        assertTrue(PerspectiveGeometry.isConvex(quad))
        val area = kotlin.math.abs(PerspectiveGeometry.signedArea(quad))
        // The diamond occupies about 2*r^2 pixels; its bounding box is 2r x 2r. A bounding-box fit
        // would score roughly twice the true area, so a tight fit must be well under that.
        val boundingBoxArea = (2f * radius / (width - 1)) * (2f * radius / (height - 1))
        assertTrue("area=$area boxArea=$boundingBoxArea", area < boundingBoxArea * 0.85f)
    }

    // --- rejection ------------------------------------------------------------------------------------

    @Test
    fun anEmptySceneYieldsNothing() {
        val flat = LumaFrame(width, height, IntArray(width * height) { background })
        assertNull(MainScanDocumentFinder.find(flat))
    }

    @Test
    fun aTinySpeckIsRejected() {
        assertNull(MainScanDocumentFinder.find(frameWith(rect(58, 78, 62, 82))))
    }

    @Test
    fun aFrameTooSmallToAnalyseYieldsNothing() {
        assertNull(MainScanDocumentFinder.find(LumaFrame(4, 4, IntArray(16) { 0 })))
    }

    // --- determinism ------------------------------------------------------------------------------------

    @Test
    fun findingIsDeterministic() {
        val frame = frameWith(rect(30, 40, 90, 120), rect(5, 150, 15, 158))
        val first = MainScanDocumentFinder.find(frame)
        repeat(4) { assertEquals(first, MainScanDocumentFinder.find(frame)) }
    }

    // --- geometry helpers --------------------------------------------------------------------------------

    @Test
    fun theConvexHullDropsInteriorPoints() {
        val points = listOf(
            com.dev.docscannerpdf.domain.crop.CropPoint(0f, 0f),
            com.dev.docscannerpdf.domain.crop.CropPoint(10f, 0f),
            com.dev.docscannerpdf.domain.crop.CropPoint(10f, 10f),
            com.dev.docscannerpdf.domain.crop.CropPoint(0f, 10f),
            com.dev.docscannerpdf.domain.crop.CropPoint(5f, 5f) // interior
        )
        val hull = MainScanDocumentFinder.convexHull(points)
        assertEquals(4, hull.size)
        assertTrue(hull.none { it.x == 5f && it.y == 5f })
    }

    @Test
    fun hullSimplificationKeepsTheDefiningCorners() {
        // A square with many collinear points along its edges must simplify back toward 4 corners.
        val points = ArrayList<com.dev.docscannerpdf.domain.crop.CropPoint>()
        for (i in 0..10) {
            points.add(com.dev.docscannerpdf.domain.crop.CropPoint(i.toFloat(), 0f))
            points.add(com.dev.docscannerpdf.domain.crop.CropPoint(i.toFloat(), 10f))
            points.add(com.dev.docscannerpdf.domain.crop.CropPoint(0f, i.toFloat()))
            points.add(com.dev.docscannerpdf.domain.crop.CropPoint(10f, i.toFloat()))
        }
        val hull = MainScanDocumentFinder.convexHull(points)
        val simplified = MainScanDocumentFinder.simplifyHull(hull, 4)
        assertEquals(4, simplified.size)
        val quad = MainScanDocumentFinder.maxAreaQuad(simplified)!!
        assertEquals(4, quad.size)
    }

    @Test
    fun maxAreaQuadPrefersTheWidestSpreadOfHullVertices() {
        val hull = listOf(
            com.dev.docscannerpdf.domain.crop.CropPoint(0f, 0f),
            com.dev.docscannerpdf.domain.crop.CropPoint(5f, 1f),
            com.dev.docscannerpdf.domain.crop.CropPoint(10f, 0f),
            com.dev.docscannerpdf.domain.crop.CropPoint(10f, 10f),
            com.dev.docscannerpdf.domain.crop.CropPoint(0f, 10f)
        )
        val quad = MainScanDocumentFinder.maxAreaQuad(hull)!!
        // The near-collinear midpoint (5,1) contributes nothing and must be dropped.
        assertTrue(quad.none { it.x == 5f && it.y == 1f })
    }
}
