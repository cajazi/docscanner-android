package com.dev.docscannerpdf.domain.pdf

/**
 * Axis-aligned bounds in normalized (0f..1f) page space — the same convention
 * [com.dev.docscannerpdf.domain.annotation.AnnotationPoint] uses, so a searchable text span
 * lines up with the image and annotation layers once the crop homography has already been
 * applied upstream. Kept as a plain domain type (no android.graphics.RectF) so this model has
 * no Android dependency.
 */
data class PdfRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** A single unit of recognized text ready to be embedded as an invisible, selectable span. */
data class PdfTextSpan(
    val text: String,
    val bounds: PdfRect
)
