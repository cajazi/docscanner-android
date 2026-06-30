package com.dev.docscannerpdf.domain.annotation

import android.graphics.PointF
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import com.dev.docscannerpdf.domain.crop.PerspectiveTransformEngine

data class Annotation(
    val id: String,
    val points: List<PointF>,
    val type: String
)

data class TransformedAnnotation(
    val id: String,
    val points: List<PointF>,
    val type: String
)

object AnnotationHomographyEngine {

    fun apply(
        annotations: List<Annotation>,
        quad: PerspectiveQuad?,
        imageWidth: Float,
        imageHeight: Float
    ): List<TransformedAnnotation> {

        if (quad == null) {
            return annotations.map {
                TransformedAnnotation(
                    id = it.id,
                    points = it.points,
                    type = it.type
                )
            }
        }

        val matrix = PerspectiveTransformEngine.quadToQuadMatrix(
            srcWidth = imageWidth,
            srcHeight = imageHeight,
            quad = quad
        )

        return annotations.map { ann ->
            TransformedAnnotation(
                id = ann.id,
                type = ann.type,
                points = ann.points.map { p ->
                    applyMatrix(matrix, p)
                }
            )
        }
    }

    private fun applyMatrix(matrix: FloatArray, p: PointF): PointF {
        val x = p.x
        val y = p.y

        val newX = matrix[0] * x + matrix[1] * y + matrix[2]
        val newY = matrix[3] * x + matrix[4] * y + matrix[5]

        return PointF(newX, newY)
    }
}