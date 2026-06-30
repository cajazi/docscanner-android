package com.dev.docscannerpdf.domain.detection

import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class DocumentEdgeDetectorTest {

    private val background = 210
    private val document = 40

    private fun frame(width: Int, height: Int, inside: (Int, Int) -> Boolean): LumaFrame {
        val luma = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (inside(x, y)) document else background
        }
        return LumaFrame(width, height, luma)
    }

    // --- Quad detection correctness: axis-aligned rectangle -----------------

    @Test
    fun detectsAxisAlignedRectangleCorners() {
        val frame = frame(100, 150) { x, y -> x in 20..79 && y in 30..119 }
        val detected = DocumentEdgeDetector.detect(frame)

        assertNotNull(detected)
        val quad = detected!!.quad
        val eps = 0.02f
        assertEquals(20f / 99f, quad.topLeft.x, eps)
        assertEquals(30f / 149f, quad.topLeft.y, eps)
        assertEquals(79f / 99f, quad.topRight.x, eps)
        assertEquals(30f / 149f, quad.topRight.y, eps)
        assertEquals(79f / 99f, quad.bottomRight.x, eps)
        assertEquals(119f / 149f, quad.bottomRight.y, eps)
        assertEquals(20f / 99f, quad.bottomLeft.x, eps)
        assertEquals(119f / 149f, quad.bottomLeft.y, eps)
        assertTrue(detected.confidence > 0.6f)
    }

    // --- Rotation support ---------------------------------------------------

    @Test
    fun detectsRotatedRectangle() {
        val cx = 100.0; val cy = 100.0
        val hw = 60.0; val hh = 40.0
        val theta = Math.toRadians(20.0)
        val cosT = cos(theta); val sinT = sin(theta)
        val frame = frame(200, 200) { x, y ->
            val dx = x - cx; val dy = y - cy
            val u = dx * cosT + dy * sinT
            val v = -dx * sinT + dy * cosT
            kotlin.math.abs(u) <= hw && kotlin.math.abs(v) <= hh
        }

        val detected = DocumentEdgeDetector.detect(frame)
        assertNotNull(detected)
        val quad = detected!!.quad
        assertTrue(PerspectiveGeometry.isConvex(quad))
        // Centroid stays at the rotated rectangle's center.
        assertEquals(0.5f, quad.centroid.x, 0.05f)
        assertEquals(0.5f, quad.centroid.y, 0.05f)
        assertTrue(detected.confidence > 0.5f)
    }

    // --- Null-case handling -------------------------------------------------

    @Test
    fun returnsNullForUniformFrame() {
        val blank = frame(100, 100) { _, _ -> false }
        assertNull(DocumentEdgeDetector.detect(blank))
    }

    @Test
    fun returnsNullWhenForegroundIsTooSmall() {
        // A 3x3 speck in a 100x100 frame is well under the minimum coverage.
        val speck = frame(100, 100) { x, y -> x in 1..3 && y in 1..3 }
        assertNull(DocumentEdgeDetector.detect(speck))
    }

    // --- Deterministic output -----------------------------------------------

    @Test
    fun detectionIsDeterministic() {
        val frame = frame(120, 90) { x, y -> x in 10..100 && y in 15..70 }
        val first = DocumentEdgeDetector.detect(frame)
        val second = DocumentEdgeDetector.detect(frame)
        assertEquals(first, second)
    }
}
