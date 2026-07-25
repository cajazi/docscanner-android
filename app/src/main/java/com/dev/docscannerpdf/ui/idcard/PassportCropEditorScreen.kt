package com.dev.docscannerpdf.ui.idcard

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dev.docscannerpdf.domain.idscan.DisplayedImageRect
import com.dev.docscannerpdf.domain.idscan.PassportCropHandle
import com.dev.docscannerpdf.domain.idscan.PassportCropMapping
import com.dev.docscannerpdf.domain.idscan.PassportCropRect
import kotlin.math.roundToInt

private val ScreenBackground = Color(0xFF101114)
private val Accent = Color(0xFF16C89A)

/**
 * Interactive RECTANGULAR passport crop editor. Shows the source at true aspect (ContentScale.Fit),
 * a crop frame with four corner and four edge handles, a dimmed region outside the crop, and a
 * rule-of-thirds grid inside it. All crop math is in normalized 0..1 source coordinates via
 * [PassportCropMapping]; touches are mapped against the letterbox-aware displayed image rectangle,
 * never the full container. Stateless — the host owns the [crop] and applies reducer moves.
 *
 * [sourceBitmap] is the image the REVIEW is currently showing (its filter, its baked rotation and
 * its watermark included), so the rectangle the user drags always frames the pixels in front of
 * them. That image's rotation is baked into its pixels — never a display-only transform — so its
 * width/height are already the rotated ones and the letterbox fit here needs no special case. The
 * host converts the resulting rectangle out of that rotated frame and back into canonical base
 * coordinates ([PassportCropRotationMapping]) before extracting the authoritative full-resolution
 * crop, which is why the filter and rotation are re-rendered from the cropped base rather than
 * applied a second time.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassportCropEditorScreen(
    sourceBitmap: Bitmap?,
    crop: PassportCropRect,
    applying: Boolean,
    onMoveHandle: (PassportCropHandle, Float, Float) -> Unit,
    onMoveBy: (Float, Float) -> Unit,
    onReset: () -> Unit,
    onCancel: () -> Unit,
    onApply: () -> Unit
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
                        text = "Crop passport",
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
                    enabled = !applying,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("Reset") }
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.weight(1f),
                    enabled = !applying,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) { Text("Cancel") }
                Button(
                    onClick = onApply,
                    modifier = Modifier.weight(1f),
                    enabled = !applying && sourceBitmap != null,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent)
                ) { Text(if (applying) "Applying…" else "Apply") }
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
                CropSurface(
                    sourceBitmap = sourceBitmap,
                    crop = crop,
                    enabled = !applying,
                    onMoveHandle = onMoveHandle,
                    onMoveBy = onMoveBy
                )
            }
        }
    }
}

@Composable
private fun CropSurface(
    sourceBitmap: Bitmap,
    crop: PassportCropRect,
    enabled: Boolean,
    onMoveHandle: (PassportCropHandle, Float, Float) -> Unit,
    onMoveBy: (Float, Float) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val containerW = with(density) { maxWidth.toPx() }
        val containerH = with(density) { maxHeight.toPx() }
        // The letterbox-aware rectangle the image actually occupies — all crop math maps to THIS.
        val displayed = remember(containerW, containerH, sourceBitmap.width, sourceBitmap.height) {
            PassportCropMapping.fitRect(containerW, containerH, sourceBitmap.width, sourceBitmap.height)
        }

        Image(
            bitmap = sourceBitmap.asImageBitmap(),
            contentDescription = "Crop source",
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Fit
        )

        CropOverlay(crop = crop, displayed = displayed, modifier = Modifier.matchParentSize())

        // Whole-frame drag: a transparent hit box over the crop interior.
        val cropLeftPx = PassportCropMapping.toContainerX(crop.left, displayed)
        val cropTopPx = PassportCropMapping.toContainerY(crop.top, displayed)
        val cropRightPx = PassportCropMapping.toContainerX(crop.right, displayed)
        val cropBottomPx = PassportCropMapping.toContainerY(crop.bottom, displayed)
        val latestCrop by rememberUpdatedState(crop)
        Box(
            modifier = Modifier
                .offset { IntOffset(cropLeftPx.roundToInt(), cropTopPx.roundToInt()) }
                .size(
                    width = with(density) { (cropRightPx - cropLeftPx).toDp() },
                    height = with(density) { (cropBottomPx - cropTopPx).toDp() }
                )
                .semantics { contentDescription = "Move crop area" }
                .pointerInput(enabled, displayed) {
                    if (!enabled) return@pointerInput
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val dnx = if (displayed.width <= 0f) 0f else dragAmount.x / displayed.width
                        val dny = if (displayed.height <= 0f) 0f else dragAmount.y / displayed.height
                        onMoveBy(dnx, dny)
                    }
                }
        )

        PassportCropHandle.entries.forEach { handle ->
            CropHandle(
                handle = handle,
                crop = latestCrop,
                displayed = displayed,
                enabled = enabled,
                onMoveHandle = onMoveHandle
            )
        }
    }
}

/** Dimmed region outside the crop + crop border + rule-of-thirds grid, all in container pixels. */
@Composable
private fun CropOverlay(crop: PassportCropRect, displayed: DisplayedImageRect, modifier: Modifier) {
    Canvas(modifier = modifier) {
        val l = PassportCropMapping.toContainerX(crop.left, displayed)
        val t = PassportCropMapping.toContainerY(crop.top, displayed)
        val r = PassportCropMapping.toContainerX(crop.right, displayed)
        val b = PassportCropMapping.toContainerY(crop.bottom, displayed)
        val dim = Color.Black.copy(alpha = 0.55f)

        // Dim the four bands of the DISPLAYED image outside the crop (never the crop interior).
        drawRect(dim, topLeft = Offset(displayed.left, displayed.top), size = Size(displayed.width, t - displayed.top))
        drawRect(dim, topLeft = Offset(displayed.left, b), size = Size(displayed.width, displayed.bottom - b))
        drawRect(dim, topLeft = Offset(displayed.left, t), size = Size(l - displayed.left, b - t))
        drawRect(dim, topLeft = Offset(r, t), size = Size(displayed.right - r, b - t))

        // Rule-of-thirds grid inside the crop.
        val grid = Color.White.copy(alpha = 0.35f)
        val thirdW = (r - l) / 3f
        val thirdH = (b - t) / 3f
        for (i in 1..2) {
            drawLine(grid, Offset(l + thirdW * i, t), Offset(l + thirdW * i, b), strokeWidth = 1.dp.toPx())
            drawLine(grid, Offset(l, t + thirdH * i), Offset(r, t + thirdH * i), strokeWidth = 1.dp.toPx())
        }

        // Crop frame border.
        drawRect(
            color = Accent,
            topLeft = Offset(l, t),
            size = Size(r - l, b - t),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}

@Composable
private fun CropHandle(
    handle: PassportCropHandle,
    crop: PassportCropRect,
    displayed: DisplayedImageRect,
    enabled: Boolean,
    onMoveHandle: (PassportCropHandle, Float, Float) -> Unit
) {
    val density = LocalDensity.current
    val handleSize = if (handle.isCorner()) 26.dp else 22.dp
    val radiusPx = with(density) { handleSize.toPx() } / 2f
    val (anchorNx, anchorNy) = handle.anchor(crop)
    val cx = PassportCropMapping.toContainerX(anchorNx, displayed)
    val cy = PassportCropMapping.toContainerY(anchorNy, displayed)
    val latestCrop by rememberUpdatedState(crop)

    Box(
        modifier = Modifier
            .offset { IntOffset((cx - radiusPx).roundToInt(), (cy - radiusPx).roundToInt()) }
            .size(handleSize)
            .clip(CircleShape)
            .background(Accent.copy(alpha = if (handle.isCorner()) 0.95f else 0.75f))
            .border(2.dp, Color.White, CircleShape)
            .semantics { contentDescription = "Resize crop ${handle.name}" }
            .pointerInput(handle, enabled, displayed) {
                if (!enabled) return@pointerInput
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    val (nx, ny) = handle.anchor(latestCrop)
                    val newNx = nx + (if (displayed.width <= 0f) 0f else dragAmount.x / displayed.width)
                    val newNy = ny + (if (displayed.height <= 0f) 0f else dragAmount.y / displayed.height)
                    onMoveHandle(handle, newNx, newNy)
                }
            }
    )
}

private fun PassportCropHandle.isCorner(): Boolean = when (this) {
    PassportCropHandle.TOP_LEFT, PassportCropHandle.TOP_RIGHT,
    PassportCropHandle.BOTTOM_LEFT, PassportCropHandle.BOTTOM_RIGHT -> true
    else -> false
}

/** The handle's anchor point in normalized image coordinates. */
private fun PassportCropHandle.anchor(rect: PassportCropRect): Pair<Float, Float> {
    val midX = (rect.left + rect.right) / 2f
    val midY = (rect.top + rect.bottom) / 2f
    return when (this) {
        PassportCropHandle.TOP_LEFT -> rect.left to rect.top
        PassportCropHandle.TOP_RIGHT -> rect.right to rect.top
        PassportCropHandle.BOTTOM_LEFT -> rect.left to rect.bottom
        PassportCropHandle.BOTTOM_RIGHT -> rect.right to rect.bottom
        PassportCropHandle.TOP -> midX to rect.top
        PassportCropHandle.BOTTOM -> midX to rect.bottom
        PassportCropHandle.LEFT -> rect.left to midY
        PassportCropHandle.RIGHT -> rect.right to midY
    }
}
