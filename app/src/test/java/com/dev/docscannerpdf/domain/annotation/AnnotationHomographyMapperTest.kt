package com.dev.docscannerpdf.domain.annotation

import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnotationHomographyMapperTest {

    /** A quad covering the top-left quarter of the image; mapping it to full doubles coordinates. */
    private val quarterQuad = PerspectiveQuad(
        topLeft = CropPoint(0f, 0f),
        topRight = CropPoint(0.5f, 0f),
        bottomRight = CropPoint(0.5f, 0.5f),
        bottomLeft = CropPoint(0f, 0.5f)
    )

    private val perspectiveQuad = PerspectiveQuad(
        topLeft = CropPoint(0.1f, 0.1f),
        topRight = CropPoint(0.9f, 0.05f),
        bottomRight = CropPoint(0.95f, 0.92f),
        bottomLeft = CropPoint(0.05f, 0.9f)
    )

    private fun stroke(vararg pts: Pair<Float, Float>) = AnnotationStroke(
        id = "s1",
        type = AnnotationType.DRAW,
        points = pts.map { AnnotationPoint(it.first, it.second) },
        color = 0xFFE53935L,
        thickness = 6f
    )

    private fun assertPointNear(expected: AnnotationPoint, actual: AnnotationPoint, eps: Float = 1e-3f) {
        assertEquals(expected.x, actual.x, eps)
        assertEquals(expected.y, actual.y, eps)
    }

    // --- Annotation point transforms correctly after crop -------------------

    @Test
    fun pointTransformsThroughCropQuad() {
        val result = AnnotationHomographyMapper.applyQuadTransform(
            annotations = listOf(stroke(0.25f to 0.25f, 0.1f to 0.2f)),
            sourceQuad = quarterQuad
        )
        val mapped = (result.single() as AnnotationStroke).points
        // The top-left-quarter quad maps to the full image, doubling coordinates.
        assertPointNear(AnnotationPoint(0.5f, 0.5f), mapped[0])
        assertPointNear(AnnotationPoint(0.2f, 0.4f), mapped[1])
    }

    @Test
    fun textAnnotationPositionIsTransformed() {
        val text = AnnotationText("t1", AnnotationPoint(0.25f, 0.25f), "note", 0xFF00FF00L, 12f)
        val result = AnnotationHomographyMapper.applyQuadTransform(listOf(text), quarterQuad)
        val mapped = result.single() as AnnotationText
        assertPointNear(AnnotationPoint(0.5f, 0.5f), mapped.position)
        // Non-geometry fields are preserved.
        assertEquals("note", mapped.value)
        assertEquals(0xFF00FF00L, mapped.color)
        assertEquals(12f, mapped.fontSize, 0f)
    }

    // --- Inverse transform restores original positions ----------------------

    @Test
    fun inverseTransformRestoresOriginalPositions() {
        val original = stroke(0.5f to 0.5f, 0.3f to 0.7f, 0.8f to 0.2f)

        val forward = AnnotationHomographyMapper.applyQuadTransform(listOf(original), perspectiveQuad)
        val restored = AnnotationHomographyMapper.inverseQuadTransform(forward, perspectiveQuad)

        val restoredPoints = (restored.single() as AnnotationStroke).points
        original.points.forEachIndexed { i, point ->
            assertPointNear(point, restoredPoints[i], eps = 1e-2f)
        }
    }

    // --- Multi-point stroke consistency preserved ---------------------------

    @Test
    fun multiPointStrokeKeepsCountAndMetadata() {
        val original = stroke(0.1f to 0.1f, 0.2f to 0.2f, 0.3f to 0.4f, 0.45f to 0.49f)
        val mapped = AnnotationHomographyMapper
            .applyQuadTransform(listOf(original), quarterQuad)
            .single() as AnnotationStroke

        assertEquals(original.points.size, mapped.points.size)
        assertEquals(original.id, mapped.id)
        assertEquals(original.type, mapped.type)
        assertEquals(original.color, mapped.color)
        assertEquals(original.thickness, mapped.thickness, 0f)
    }

    // --- Deterministic output for same input quad ---------------------------

    @Test
    fun transformIsDeterministic() {
        val annotations = listOf(stroke(0.2f to 0.3f, 0.6f to 0.8f))
        val a = AnnotationHomographyMapper.applyQuadTransform(annotations, perspectiveQuad)
        val b = AnnotationHomographyMapper.applyQuadTransform(annotations, perspectiveQuad)
        assertEquals(a, b)
    }

    // --- No mutation of the original annotation store -----------------------

    @Test
    fun originalAnnotationsAreNotMutated() {
        val original = stroke(0.25f to 0.25f, 0.4f to 0.4f)
        val inputList = listOf(original)
        val snapshotPoints = original.points.toList()

        val result = AnnotationHomographyMapper.applyQuadTransform(inputList, quarterQuad)

        // Input objects are untouched; output is a distinct instance.
        assertEquals(snapshotPoints, original.points)
        assertEquals(1, inputList.size)
        assertNotSame(original, result.single())
        // And the transform actually changed the coordinates.
        assertTrue((result.single() as AnnotationStroke).points[0] != original.points[0])
    }
}
