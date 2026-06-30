package com.dev.docscannerpdf.domain.ocr

import android.graphics.PointF

data class OCRBox(
    val text: String,
    val points: List<PointF>
)

data class TransformedOCRBox(
    val text: String,
    val points: List<PointF>
)

object OCRTransformEngine {

    fun applyTransform(
        boxes: List<OCRBox>,
        transform: (PointF) -> PointF
    ): List<TransformedOCRBox> {
        return boxes.map { box ->
            TransformedOCRBox(
                text = box.text,
                points = box.points.map(transform)
            )
        }
    }

    fun identity(point: PointF): PointF = point
}
