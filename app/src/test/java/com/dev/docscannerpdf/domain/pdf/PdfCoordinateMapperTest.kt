package com.dev.docscannerpdf.domain.pdf

import android.graphics.PointF
import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import com.dev.docscannerpdf.domain.ocr.TransformedOCRBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfCoordinateMapperTest {

    // --- Pixel -> normalized space -------------------------------------------
    //
    // android.graphics.PointF's fields resolve to 0 under this project's plain-JUnit unit
    // tests (no Robolectric shadow), so only the null-dimension guard — which never reads a
    // point's coordinates — can be asserted here. The division itself is exercised end-to-end
    // by the instrumented/manual export flow instead.

    @Test
    fun normalizeReturnsNullForDegenerateImageDimensions() {
        val box = TransformedOCRBox(text = "x", points = listOf(PointF(1f, 1f)))
        assertNull(PdfCoordinateMapper.normalize(box, imageWidth = 0, imageHeight = 100))
    }

    // --- Crop homography projection ------------------------------------------

    @Test
    fun projectThroughCropIsIdentityWhenSourceQuadIsFull() {
        val boxes = listOf(PdfOcrBox(text = "word", points = listOf(0.2f to 0.3f, 0.6f to 0.7f)))

        val projected = PdfCoordinateMapper.projectThroughCrop(boxes, sourceQuad = PerspectiveQuad.full())

        assertEquals(0.2f, projected.single().points[0].first, 0.0001f)
        assertEquals(0.3f, projected.single().points[0].second, 0.0001f)
        assertEquals(0.6f, projected.single().points[1].first, 0.0001f)
        assertEquals(0.7f, projected.single().points[1].second, 0.0001f)
    }

    @Test
    fun projectThroughCropMapsPointAtQuadCenterNearOutputCenter() {
        // An inset quad's centroid should land close to the unit square's centroid once
        // projected onto the full (cropped-output) target quad.
        val quad = PerspectiveQuad(
            topLeft = CropPoint(0.1f, 0.1f),
            topRight = CropPoint(0.9f, 0.1f),
            bottomRight = CropPoint(0.9f, 0.9f),
            bottomLeft = CropPoint(0.1f, 0.9f)
        )
        val boxes = listOf(PdfOcrBox(text = "center", points = listOf(0.5f to 0.5f)))

        val projected = PdfCoordinateMapper.projectThroughCrop(boxes, sourceQuad = quad)

        val (x, y) = projected.single().points.single()
        assertTrue(x in 0.45f..0.55f)
        assertTrue(y in 0.45f..0.55f)
    }

    @Test
    fun projectThroughCropClampsResultsIntoUnitRange() {
        val quad = PerspectiveQuad(
            topLeft = CropPoint(0.3f, 0.3f),
            topRight = CropPoint(0.7f, 0.3f),
            bottomRight = CropPoint(0.7f, 0.7f),
            bottomLeft = CropPoint(0.3f, 0.7f)
        )
        // A point far outside the crop quad projects outside 0f..1f before clamping.
        val boxes = listOf(PdfOcrBox(text = "outlier", points = listOf(0f to 0f)))

        val projected = PdfCoordinateMapper.projectThroughCrop(boxes, sourceQuad = quad)

        val (x, y) = projected.single().points.single()
        assertTrue(x in 0f..1f)
        assertTrue(y in 0f..1f)
    }
}
