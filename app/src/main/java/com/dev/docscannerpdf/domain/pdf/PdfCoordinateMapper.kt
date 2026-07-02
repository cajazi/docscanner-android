package com.dev.docscannerpdf.domain.pdf

import android.graphics.RectF
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import com.dev.docscannerpdf.domain.crop.PerspectiveTransformEngine
import com.dev.docscannerpdf.domain.ocr.TransformedOCRBox

/**
 * Converts OCR geometry between the coordinate spaces a searchable-PDF export passes through:
 * raw image pixels (ML Kit's native space), normalized 0f..1f page space (the convention
 * [com.dev.docscannerpdf.domain.annotation.AnnotationPoint] and [PdfTextSpan] already share),
 * and the final PDF canvas rect of a specific export (points, letterboxed onto an A4 page).
 * Keeping this in one pure, Android-drawing-free object means [PdfExportService] places the
 * image and the invisible text layer through the exact same rule, so text can never drift from
 * the word it corresponds to.
 */
object PdfCoordinateMapper {

    /**
     * Normalizes a single [TransformedOCRBox] (raw pixel points, image-space) into a
     * [PdfOcrBox] (0f..1f points) by dividing by the source image's own dimensions. Returns
     * null for a degenerate (zero-sized) image rather than dividing by zero.
     */
    fun normalize(box: TransformedOCRBox, imageWidth: Int, imageHeight: Int): PdfOcrBox? {
        if (imageWidth <= 0 || imageHeight <= 0) return null
        val points = box.points.map { point ->
            (point.x / imageWidth).coerceIn(0f, 1f) to (point.y / imageHeight).coerceIn(0f, 1f)
        }
        return PdfOcrBox(text = box.text, points = points)
    }

    /** Batch variant of [normalize]; boxes that fail to normalize are dropped. */
    fun normalize(
        boxes: List<TransformedOCRBox>,
        imageWidth: Int,
        imageHeight: Int
    ): List<PdfOcrBox> = boxes.mapNotNull { normalize(it, imageWidth, imageHeight) }

    /**
     * Projects normalized [boxes] from [sourceQuad]'s space (the base image the OCR ran
     * against) onto [targetQuad]'s space (the cropped output, the unit square by default).
     * Mirrors [com.dev.docscannerpdf.domain.annotation.AnnotationHomographyMapper] so OCR text
     * lines up with an applied crop the same way annotations do.
     */
    fun projectThroughCrop(
        boxes: List<PdfOcrBox>,
        sourceQuad: PerspectiveQuad,
        targetQuad: PerspectiveQuad = PerspectiveQuad.full()
    ): List<PdfOcrBox> {
        val matrix = PerspectiveTransformEngine.quadToQuadMatrix(sourceQuad, targetQuad)
        return boxes.map { box ->
            box.copy(
                points = box.points.map { (x, y) ->
                    val (mappedX, mappedY) = matrix.mapPoint(x.toDouble(), y.toDouble())
                    mappedX.toFloat().coerceIn(0f, 1f) to mappedY.toFloat().coerceIn(0f, 1f)
                }
            )
        }
    }

    /**
     * The rect a bitmap of [imageWidth]x[imageHeight] is drawn into on a page of
     * [pageWidth]x[pageHeight] points: scaled to fit (never cropped or stretched) and centered.
     * [PdfExportService] must place the bitmap and every positioned text span through this same
     * rect, or the invisible text layer will drift whenever the image's aspect ratio isn't an
     * exact match for the page's.
     */
    fun imageDestinationRect(
        pageWidth: Float,
        pageHeight: Float,
        imageWidth: Int,
        imageHeight: Int
    ): RectF {
        val scale = minOf(pageWidth / imageWidth.toFloat(), pageHeight / imageHeight.toFloat())
        val destWidth = imageWidth * scale
        val destHeight = imageHeight * scale
        val left = (pageWidth - destWidth) / 2f
        val top = (pageHeight - destHeight) / 2f
        return RectF(left, top, left + destWidth, top + destHeight)
    }

    /**
     * Maps a normalized (0f..1f) [PdfRect] — already in the exported image's own normalized
     * space — onto the final PDF canvas, using [imageRect] (from [imageDestinationRect]) as the
     * image's placement on the page.
     */
    fun mapToPage(bounds: PdfRect, imageRect: RectF): RectF = RectF(
        imageRect.left + bounds.left * imageRect.width(),
        imageRect.top + bounds.top * imageRect.height(),
        imageRect.left + bounds.right * imageRect.width(),
        imageRect.top + bounds.bottom * imageRect.height()
    )
}
