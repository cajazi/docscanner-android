package com.dev.docscannerpdf.ui.annotation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import com.dev.docscannerpdf.domain.annotation.Annotation
import com.dev.docscannerpdf.domain.annotation.AnnotationFactory
import com.dev.docscannerpdf.domain.annotation.AnnotationPoint
import com.dev.docscannerpdf.domain.annotation.AnnotationStroke
import com.dev.docscannerpdf.domain.annotation.AnnotationText
import com.dev.docscannerpdf.domain.annotation.AnnotationTool
import com.dev.docscannerpdf.domain.annotation.AnnotationType
import java.util.UUID

/** Highlight strokes render thicker and translucent; pen strokes render solid. */
private const val HIGHLIGHT_WIDTH_FACTOR = 3.5f
private const val HIGHLIGHT_ALPHA = 0.35f

/**
 * A transparent Compose [Canvas] that sits on top of the document preview image and captures
 * freehand pen/highlight strokes while rendering already-placed annotations. It owns only the
 * in-progress stroke; committed annotations are hoisted to the caller via [onStrokeFinished],
 * so the overlay never reloads or replaces the underlying image.
 *
 * All captured coordinates are normalized to 0f..1f against the canvas size, matching the
 * normalized space used for persistence and PDF export.
 */
@Composable
fun AnnotationCanvasOverlay(
    annotations: List<Annotation>,
    tool: AnnotationTool,
    enabled: Boolean,
    strokeColor: Color,
    strokeThickness: Float,
    onStrokeFinished: (AnnotationStroke) -> Unit,
    modifier: Modifier = Modifier
) {
    val inProgress = remember { mutableStateListOf<AnnotationPoint>() }
    val drawsStrokes = tool == AnnotationTool.PEN || tool == AnnotationTool.HIGHLIGHT

    val gestureModifier = if (enabled && drawsStrokes) {
        Modifier.pointerInput(tool, strokeColor, strokeThickness) {
            detectDragGestures(
                onDragStart = { offset ->
                    inProgress.clear()
                    inProgress.add(offset.normalize(size.width.toFloat(), size.height.toFloat()))
                },
                onDrag = { change, _ ->
                    inProgress.add(change.position.normalize(size.width.toFloat(), size.height.toFloat()))
                    change.consume()
                },
                onDragEnd = {
                    val stroke = AnnotationFactory.stroke(
                        id = UUID.randomUUID().toString(),
                        type = if (tool == AnnotationTool.HIGHLIGHT) AnnotationType.HIGHLIGHT else AnnotationType.DRAW,
                        points = inProgress.toList(),
                        color = strokeColor.toArgb().toLong(),
                        thickness = strokeThickness
                    )
                    if (stroke != null) onStrokeFinished(stroke)
                    inProgress.clear()
                },
                onDragCancel = { inProgress.clear() }
            )
        }
    } else {
        Modifier
    }

    Canvas(modifier = modifier.then(gestureModifier)) {
        annotations.forEach { annotation ->
            when (annotation) {
                is AnnotationStroke -> drawAnnotationStroke(annotation)
                is AnnotationText -> drawAnnotationText(annotation)
            }
        }
        if (inProgress.size >= 2) {
            drawNormalizedPolyline(
                points = inProgress,
                color = strokeColor,
                thickness = strokeThickness,
                highlight = tool == AnnotationTool.HIGHLIGHT
            )
        }
    }
}

private fun Offset.normalize(width: Float, height: Float): AnnotationPoint =
    AnnotationPoint(
        x = if (width <= 0f) 0f else (x / width).coerceIn(0f, 1f),
        y = if (height <= 0f) 0f else (y / height).coerceIn(0f, 1f)
    )

private fun DrawScope.drawAnnotationStroke(stroke: AnnotationStroke) {
    drawNormalizedPolyline(
        points = stroke.points,
        color = Color(stroke.color.toInt()),
        thickness = stroke.thickness,
        highlight = stroke.type == AnnotationType.HIGHLIGHT
    )
}

private fun DrawScope.drawNormalizedPolyline(
    points: List<AnnotationPoint>,
    color: Color,
    thickness: Float,
    highlight: Boolean
) {
    if (points.size < 2) return
    val path = Path().apply {
        val first = points.first()
        moveTo(first.x * size.width, first.y * size.height)
        for (i in 1 until points.size) {
            lineTo(points[i].x * size.width, points[i].y * size.height)
        }
    }
    drawPath(
        path = path,
        color = if (highlight) color.copy(alpha = HIGHLIGHT_ALPHA) else color,
        style = Stroke(
            width = thickness * if (highlight) HIGHLIGHT_WIDTH_FACTOR else 1f,
            cap = StrokeCap.Round,
            join = StrokeJoin.Round
        )
    )
}

private fun DrawScope.drawAnnotationText(text: AnnotationText) {
    val paint = android.graphics.Paint().apply {
        isAntiAlias = true
        this.color = text.color.toInt()
        textSize = text.fontSize
    }
    drawContext.canvas.nativeCanvas.drawText(
        text.value,
        text.position.x * size.width,
        text.position.y * size.height,
        paint
    )
}
