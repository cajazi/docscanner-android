package com.dev.docscannerpdf.ui.mainscan

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.dev.docscannerpdf.domain.mainscan.ViewportQuad

/**
 * The live document guide: four thin edges with short corner accents, drawn over the preview.
 *
 * Design constraints, all from the locked reference and the Slice 2 brief:
 *
 * - **Original DocScannerPDF visuals.** Geometry generated at runtime from the detected corners —
 *   no imported artwork, no icons, no product names.
 * - **No filled mask.** The document stays fully visible; only a very faint tint marks the detected
 *   area, so the guide reads as an annotation rather than something covering the page.
 * - **Not permanent.** The caller shows this only while a valid, stable boundary exists.
 * - **Touch-transparent.** This composable declares no input modifiers at all, so every tap falls
 *   through to the controls beneath it — the manual shutter can never be blocked by the guide.
 * - **Restrained motion.** A single short fade on appearance and withdrawal. No pulsing, no
 *   marching ants, nothing that competes with the viewfinder for attention.
 *
 * [quad] is already in VIEWPORT PIXELS. This composable performs no rotation, no normalization and
 * no centre-crop arithmetic — that belongs to
 * [com.dev.docscannerpdf.domain.mainscan.MainScanDetectionMapper], where it is unit-tested. Doing it
 * here would put untested coordinate math inside a draw call that runs sixty times a second.
 */
@Composable
fun MainScanGuideOverlay(
    quad: ViewportQuad?,
    visible: Boolean,
    modifier: Modifier = Modifier
) {
    // Fading rather than snapping avoids a hard pop when the detector re-acquires; the visibility
    // hysteresis upstream already prevents rapid toggling, so this stays short.
    val fade by animateFloatAsState(
        targetValue = if (visible && quad != null) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "main-scan-guide-fade"
    )
    if (quad == null || fade <= 0.01f) return

    Canvas(modifier = modifier.fillMaxSize().alpha(fade)) {
        val points = quad.corners().map { Offset(it.x, it.y) }
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            for (i in 1 until points.size) lineTo(points[i].x, points[i].y)
            close()
        }

        // A barely-there tint: enough to read the enclosed region as "this is the page", far too
        // faint to obscure what the user is framing.
        drawPath(path, color = MainScanAccent.copy(alpha = 0.12f))

        // Thin, high-contrast edges.
        drawPath(
            path = path,
            color = MainScanAccent,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )

        // Corner accents: short thickened runs along both edges meeting at each corner. These make
        // the four corners readable at a glance without the heavy L-brackets a fixed frame needs.
        val accentLength = minOf(size.width, size.height) * ACCENT_LENGTH_FRACTION
        val accentStroke = 4.dp.toPx()
        for (i in points.indices) {
            val corner = points[i]
            val next = points[(i + 1) % points.size]
            val previous = points[(i + 3) % points.size]
            drawLine(
                color = MainScanAccent,
                start = corner,
                end = pointToward(corner, next, accentLength),
                strokeWidth = accentStroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = MainScanAccent,
                start = corner,
                end = pointToward(corner, previous, accentLength),
                strokeWidth = accentStroke,
                cap = StrokeCap.Round
            )
        }
    }
}

/** A fraction of the shorter viewport edge, so accents scale with the surface rather than the quad. */
private const val ACCENT_LENGTH_FRACTION = 0.045f

/**
 * A point [distance] from [from] along the segment toward [to], clamped so an accent can never
 * overshoot a short edge and cross the corner opposite it.
 */
private fun pointToward(from: Offset, to: Offset, distance: Float): Offset {
    val dx = to.x - from.x
    val dy = to.y - from.y
    val length = kotlin.math.hypot(dx, dy)
    if (length <= 0.0001f) return from
    val travel = minOf(distance, length * 0.4f)
    val ratio = travel / length
    return Offset(x = from.x + dx * ratio, y = from.y + dy * ratio)
}
