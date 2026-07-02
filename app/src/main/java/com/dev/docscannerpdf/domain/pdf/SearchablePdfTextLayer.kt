package com.dev.docscannerpdf.domain.pdf

/**
 * A single detected word/line's bounding polygon in normalized (0f..1f) page space, already
 * mapped through the crop homography upstream. [points] holds the polygon's corners in order,
 * matching how OCR engines typically report a quad rather than an axis-aligned rect.
 */
data class PdfOcrBox(
    val text: String,
    val points: List<Pair<Float, Float>>
)

/**
 * Converts recognized OCR boxes into [PdfTextSpan]s ready for the invisible, selectable text
 * layer. Each polygon is reduced to its axis-aligned bounding rect, since PDF text placement
 * only needs a rectangle, not the exact quad an OCR engine reports.
 */
object SearchablePdfTextLayer {

    fun build(boxes: List<PdfOcrBox>): List<PdfTextSpan> =
        boxes.mapNotNull { box ->
            if (box.text.isBlank() || box.points.isEmpty()) return@mapNotNull null
            PdfTextSpan(text = box.text, bounds = boundingRect(box.points))
        }

    private fun boundingRect(points: List<Pair<Float, Float>>): PdfRect {
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for ((x, y) in points) {
            if (x < left) left = x
            if (y < top) top = y
            if (x > right) right = x
            if (y > bottom) bottom = y
        }
        return PdfRect(left, top, right, bottom)
    }
}
