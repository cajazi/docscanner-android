package com.dev.docscannerpdf.domain.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
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

        // Base paint (immutable pattern)
        val basePaint = Paint().apply {
            isAntiAlias = true
            strokeWidth = 4f
            style = Paint.Style.STROKE
        }

        // ----------------------------
        // OCR LAYER (light overlay)
        // ----------------------------
        ocrBoxes.forEach { box ->
            val paint = Paint(basePaint).apply {
                alpha = 80
                strokeWidth = 3f
            }
            drawPolygon(canvas, paint, box.points)
        }

        // ----------------------------
        // ANNOTATION LAYER (strong overlay)
        // ----------------------------
        annotations.forEach { ann ->
            val paint = Paint(basePaint).apply {
                when (ann.type) {
                    "pen" -> {
                        alpha = 255
                        strokeWidth = 5f
                    }
                    "highlight" -> {
                        alpha = 120
                        strokeWidth = 12f
                    }
                    else -> {
                        alpha = 255
                        strokeWidth = 4f
                    }
                }
            }

            drawPolygon(canvas, paint, ann.points)
        }

        return output
    }

    private fun drawPolygon(
        canvas: Canvas,
        paint: Paint,
        points: List<PointF>
    ) {
        if (points.size < 2) return

        for (i in points.indices) {
            val p1 = points[i]
            val p2 = points[(i + 1) % points.size]
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint)
        }
    }
}