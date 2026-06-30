package com.dev.docscannerpdf.domain.ocr

import android.graphics.PointF
import com.dev.docscannerpdf.domain.crop.PerspectiveTransformEngine
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad

data class OCRBox(
    val text: String,
    val points: List<PointF>
)

data class TransformedOCRBox(
    val text: String,
    val points: List<PointF>
)

object OCRCropHomographyEngine {

    fun applyCropAwareTransform(
        boxes: List<OCRBox>,
        quad: PerspectiveQuad?,
        imageWidth: Float,
        imageHeight: Float
    ): List<TransformedOCRBox> {

        if (quad == null) {
            return boxes.map {
                TransformedOCRBox(it.text, it.points)
            }
        }

        val matrix = PerspectiveTransformEngine.quadToQuadMatrix(
            srcWidth = imageWidth,
            srcHeight = imageHeight,
            quad = quad
        )

        return boxes.map { box ->
            TransformedOCRBox(
                text = box.text,
                points = box.points.map { p ->
                    applyMatrix(matrix, p)
                }
            )
        }
    }

    private fun applyMatrix(matrix: FloatArray, p: PointF): PointF {
        val x = p.x
        val y = p.y

        return PointF(
            matrix[0] * x + matrix[1] * y + matrix[2],
            matrix[3] * x + matrix[4] * y + matrix[5]
        )
    }
}
