package com.dev.docscannerpdf.domain.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.dev.docscannerpdf.domain.annotation.TransformedAnnotation
import com.dev.docscannerpdf.domain.ocr.TransformedOCRBox

object PdfExportFinalizer {

    fun renderPage(
        source: Bitmap,
        annotations: List<TransformedAnnotation>,
        ocrBoxes: List<TransformedOCRBox>
    ): Bitmap {

        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)

        val paint = Paint().apply {
            isAntiAlias = true
            strokeWidth = 4f
        }

        // OCR layer (light overlay)
        ocrBoxes.forEach { box ->
            paint.alpha = 80
            drawPolygon(canvas, paint, box.points)
        }

        // Annotation layer (strong overlay)
        annotations.forEach { ann ->
            when (ann.type) {
                "pen" -> {
                    paint.alpha = 255
                    paint.strokeWidth = 5f
                }
                "highlight" -> {
                    paint.alpha = 120
                    paint.strokeWidth = 12f
                }
                else -> {
                    paint.alpha = 255
                    paint.strokeWidth = 4f
                }
            }

            drawPolygon(canvas, paint, ann.points)
        }

        return output
    }

    private fun drawPolygon(
        canvas: Canvas,
        paint: Paint,
        points: List<android.graphics.PointF>
    ) {
        for (i in points.indices) {
            val p1 = points[i]
            val p2 = points[(i + 1) % points.size]
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint)
        }
    }
}