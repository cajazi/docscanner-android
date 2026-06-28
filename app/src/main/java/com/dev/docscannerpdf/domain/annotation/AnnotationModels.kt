package com.dev.docscannerpdf.domain.annotation

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Kind of annotation. [DRAW] and [HIGHLIGHT] are freehand strokes (see [AnnotationStroke]);
 * [TEXT] is a placed text note (see [AnnotationText]).
 */
@Serializable
enum class AnnotationType { DRAW, HIGHLIGHT, TEXT }

/**
 * A point in normalized page space: [x] and [y] are fractions in 0f..1f relative to the
 * displayed page. Storing normalized coordinates keeps annotations aligned across the
 * preview overlay and the PDF export, which render at different pixel sizes.
 */
@Serializable
data class AnnotationPoint(val x: Float, val y: Float)

/**
 * Common type for everything overlaid on a page. It is a sealed, [Serializable] hierarchy so
 * a page's mixed annotations persist as one polymorphic JSON list and undo/redo can treat
 * them uniformly. [color] is a packed ARGB value (stored as [Long] for stable serialization).
 */
@Serializable
sealed interface Annotation {
    val id: String
    val type: AnnotationType
    val color: Long
}

/** A freehand pen or highlighter stroke described by its normalized [points]. */
@Serializable
@SerialName("stroke")
data class AnnotationStroke(
    override val id: String,
    override val type: AnnotationType,
    val points: List<AnnotationPoint>,
    override val color: Long,
    val thickness: Float
) : Annotation

/** A text note anchored at a normalized [position]. */
@Serializable
@SerialName("text")
data class AnnotationText(
    override val id: String,
    val position: AnnotationPoint,
    val value: String,
    override val color: Long,
    val fontSize: Float,
    override val type: AnnotationType = AnnotationType.TEXT
) : Annotation

/** Serializable container persisted per document: a map of pageId -> its annotations. */
@Serializable
data class DocumentAnnotations(
    val pages: Map<String, List<Annotation>> = emptyMap()
)

/**
 * Validating factories for annotations. A stroke needs at least two points (a single tap is
 * not a stroke) and a text note must be non-blank — invalid input returns null rather than
 * producing a degenerate annotation.
 */
object AnnotationFactory {
    fun stroke(
        id: String,
        type: AnnotationType,
        points: List<AnnotationPoint>,
        color: Long,
        thickness: Float
    ): AnnotationStroke? {
        if (points.size < 2) return null
        val strokeType = if (type == AnnotationType.HIGHLIGHT) AnnotationType.HIGHLIGHT else AnnotationType.DRAW
        return AnnotationStroke(
            id = id,
            type = strokeType,
            points = points,
            color = color,
            thickness = thickness.coerceAtLeast(0.1f)
        )
    }

    fun text(
        id: String,
        position: AnnotationPoint,
        value: String,
        color: Long,
        fontSize: Float
    ): AnnotationText? {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        return AnnotationText(
            id = id,
            position = position,
            value = trimmed,
            color = color,
            fontSize = fontSize.coerceAtLeast(1f)
        )
    }
}
