package com.dev.docscannerpdf.ui.crop

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dev.docscannerpdf.domain.crop.CropCorner
import com.dev.docscannerpdf.domain.crop.CropState
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import kotlin.math.roundToInt

private val ScreenBackground = Color(0xFF101114)
private val Accent = Color(0xFF6C8CFF)
private val HandleFill = Color(0xFF6C8CFF)

/**
 * CamScanner-style crop / perspective editor. Shows the source image at its true aspect ratio
 * with four draggable corner handles and a live quad overlay, plus reset/cancel/apply controls.
 * Stateless: corner moves and lifecycle actions are driven by the host through
 * [com.dev.docscannerpdf.domain.crop.CropReducer]; the actual pixel warp happens on apply.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CropEditorScreen(
    state: CropState,
    sourceBitmap: Bitmap?,
    onMoveCorner: (CropCorner, Float, Float) -> Unit,
    onReset: () -> Unit,
    onApply: () -> Unit,
    onCancel: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel")
                    }
                },
                title = {
                    Text(
                        text = "Edit Crop",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF202124),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF202124))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onReset,
                    modifier = Modifier.weight(1f),
                    enabled = !state.isApplying,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("Reset") }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    enabled = !state.isApplying,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("Cancel") }
                Button(
                    onClick = onApply,
                    modifier = Modifier.weight(1f),
                    enabled = !state.isApplying && sourceBitmap != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text(if (state.isApplying) "Applying…" else "Apply") }
            }
        },
        containerColor = ScreenBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            if (sourceBitmap == null) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp)
            } else {
                val aspect = sourceBitmap.width.toFloat() / sourceBitmap.height.toFloat()
                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxSize()
                        .aspectRatio(aspect, matchHeightConstraintsFirst = aspect < 1f)
                ) {
                    val density = LocalDensity.current
                    val widthPx = with(density) { maxWidth.toPx() }
                    val heightPx = with(density) { maxHeight.toPx() }

                    Image(
                        bitmap = sourceBitmap.asImageBitmap(),
                        contentDescription = "Crop source",
                        modifier = Modifier.matchParentSize(),
                        contentScale = ContentScale.FillBounds
                    )
                    QuadOverlay(quad = state.quad, modifier = Modifier.matchParentSize())

                    CropCorner.entries.forEach { corner ->
                        CornerHandle(
                            corner = corner,
                            quad = state.quad,
                            widthPx = widthPx,
                            heightPx = heightPx,
                            onMoveCorner = onMoveCorner
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuadOverlay(quad: PerspectiveQuad, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val pts = quad.corners().map { Offset(it.x * size.width, it.y * size.height) }
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            drawLine(
                color = Accent,
                start = a,
                end = b,
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun CornerHandle(
    corner: CropCorner,
    quad: PerspectiveQuad,
    widthPx: Float,
    heightPx: Float,
    onMoveCorner: (CropCorner, Float, Float) -> Unit
) {
    val point = quad.corner(corner)
    val density = LocalDensity.current
    val handleSizeDp = 28.dp
    val radiusPx = with(density) { handleSizeDp.toPx() } / 2f
    // Read the latest quad inside the gesture without restarting the pointer pipeline.
    val latestQuad by rememberUpdatedState(quad)

    Box(
        modifier = Modifier
            .offset {
                IntOffset(
                    x = (point.x * widthPx - radiusPx).roundToInt(),
                    y = (point.y * heightPx - radiusPx).roundToInt()
                )
            }
            .size(handleSizeDp)
            .clip(CircleShape)
            .background(HandleFill.copy(alpha = 0.85f))
            .border(2.dp, Color.White, CircleShape)
            .pointerInput(corner) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val current = latestQuad.corner(corner)
                    val newX = current.x + (if (widthPx == 0f) 0f else dragAmount.x / widthPx)
                    val newY = current.y + (if (heightPx == 0f) 0f else dragAmount.y / heightPx)
                    onMoveCorner(corner, newX, newY)
                }
            }
    )
}
