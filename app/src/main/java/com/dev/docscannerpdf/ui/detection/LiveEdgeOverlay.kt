package com.dev.docscannerpdf.ui.detection

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.dev.docscannerpdf.domain.detection.LiveDetectionUiState

private val Detecting = Color(0xFFF6C85F)
private val Stable = Color(0xFF16C89A)

/**
 * Real-time document-boundary overlay. Draws the detected quad over a camera preview, tints it
 * from amber (detecting) toward green (stable), fades it in with confidence, and fills it with a
 * soft green when capture is ready. Stateless — it renders whatever [state] the live pipeline
 * produces. (The camera preview itself is supplied by the host; this is the guidance layer.)
 */
@Composable
fun LiveEdgeOverlay(
    state: LiveDetectionUiState,
    modifier: Modifier = Modifier
) {
    val quad = state.quad
    val stability by animateFloatAsState(
        targetValue = if (state.isStable) 1f else 0f,
        label = "edge-stability"
    )
    val quadColor by animateColorAsState(
        targetValue = if (state.isStable) Stable else Detecting,
        label = "edge-color"
    )

    Box(modifier = modifier.fillMaxSize()) {
        if (quad == null) return@Box
        Canvas(modifier = Modifier.fillMaxSize()) {
            val pts = quad.corners().map { Offset(it.x * size.width, it.y * size.height) }

            // Soft fill that strengthens as the detection stabilizes / readies for capture.
            val fillAlpha = (0.10f + 0.20f * stability) * (if (state.readyToCapture) 1f else 0.6f)
            val path = Path().apply {
                moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
                close()
            }
            drawPath(path, color = Stable.copy(alpha = fillAlpha))

            // Edges: opacity follows confidence so weak detections look tentative.
            val edgeAlpha = (0.35f + 0.65f * state.confidence).coerceIn(0f, 1f)
            for (i in pts.indices) {
                drawLine(
                    color = quadColor.copy(alpha = edgeAlpha),
                    start = pts[i],
                    end = pts[(i + 1) % pts.size],
                    strokeWidth = (2f + 2f * stability).dp.toPx(),
                    cap = StrokeCap.Round
                )
            }

            // Corner ticks.
            pts.forEach { p ->
                drawCircle(color = quadColor, radius = 5.dp.toPx(), center = p)
            }
        }
    }
}
