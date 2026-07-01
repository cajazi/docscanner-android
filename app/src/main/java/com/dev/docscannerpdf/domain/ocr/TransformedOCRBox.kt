package com.dev.docscannerpdf.domain.ocr

import android.graphics.PointF

/**
 * Raw ML Kit OCR output for a single recognized word/element: its text plus the polygon
 * (image-pixel space, no normalization, scan orientation preserved) ML Kit reported for it.
 * This is the single source of truth for OCR going forward — callers that only need plain
 * text derive it from [text] rather than the pipeline producing a separate string-only path.
 */
data class TransformedOCRBox(
    val text: String,
    val points: List<PointF>
)
