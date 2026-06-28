package com.dev.docscannerpdf.domain.annotation

import com.dev.docscannerpdf.domain.crop.Matrix3x3
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import com.dev.docscannerpdf.domain.crop.PerspectiveTransformEngine

/**
 * Projects annotations through the same homography the crop engine applies to the image, so a
 * pen/highlight stroke or text note keeps its position relative to the document after a
 * perspective crop. Both annotations and the crop quad live in normalized (0f..1f) space, so the
 * transform is resolution-independent.
 *
 * Every function is pure and returns new annotation instances — the input list and its objects
 * are never mutated, keeping the canonical annotation store untouched.
 */
object AnnotationHomographyMapper {

    /**
     * Homography mapping points from [sourceQuad]'s space into [targetQuad]'s space. Reuses the
     * crop engine's calculator so image and annotation layers share one transform definition.
     */
    fun computeMatrix(sourceQuad: PerspectiveQuad, targetQuad: PerspectiveQuad): Matrix3x3 =
        PerspectiveTransformEngine.quadToQuadMatrix(sourceQuad, targetQuad)

    /** Maps a single normalized point through [matrix]. */
    fun transformPoint(matrix: Matrix3x3, point: AnnotationPoint): AnnotationPoint {
        val (x, y) = matrix.mapPoint(point.x.toDouble(), point.y.toDouble())
        return AnnotationPoint(x.toFloat(), y.toFloat())
    }

    /** Maps every annotation's geometry through [matrix], preserving all other fields. */
    fun applyMatrix(annotations: List<Annotation>, matrix: Matrix3x3): List<Annotation> =
        annotations.map { transformAnnotation(it, matrix) }

    /**
     * Projects [annotations] from [sourceQuad] onto [targetQuad] (the cropped output, the unit
     * square by default). Use this before rendering/exporting annotations over a cropped image.
     */
    fun applyQuadTransform(
        annotations: List<Annotation>,
        sourceQuad: PerspectiveQuad,
        targetQuad: PerspectiveQuad = PerspectiveQuad.full()
    ): List<Annotation> = applyMatrix(annotations, computeMatrix(sourceQuad, targetQuad))

    /**
     * The reverse of [applyQuadTransform]: maps annotations from [targetQuad] space back to
     * [sourceQuad] space. Use this to store input captured over the cropped image back into the
     * canonical (pre-crop) coordinate space.
     */
    fun inverseQuadTransform(
        annotations: List<Annotation>,
        sourceQuad: PerspectiveQuad,
        targetQuad: PerspectiveQuad = PerspectiveQuad.full()
    ): List<Annotation> = applyMatrix(annotations, computeMatrix(targetQuad, sourceQuad))

    private fun transformAnnotation(annotation: Annotation, matrix: Matrix3x3): Annotation =
        when (annotation) {
            is AnnotationStroke -> annotation.copy(
                points = annotation.points.map { transformPoint(matrix, it) }
            )
            is AnnotationText -> annotation.copy(
                position = transformPoint(matrix, annotation.position)
            )
        }
}
