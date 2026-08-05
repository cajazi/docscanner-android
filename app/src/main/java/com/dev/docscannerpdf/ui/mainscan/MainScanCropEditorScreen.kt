package com.dev.docscannerpdf.ui.mainscan

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.mainscan.MainScanCropEditor
import com.dev.docscannerpdf.domain.mainscan.MainScanCropHandle
import com.dev.docscannerpdf.domain.mainscan.MainScanCropState
import com.dev.docscannerpdf.domain.mainscan.MainScanMagnifier
import com.dev.docscannerpdf.domain.mainscan.MainScanRotation
import com.dev.docscannerpdf.ui.idcard.DarkSystemBarsEffect
import kotlin.math.roundToInt

private val CropChrome = Color(0xFF101114)

/** Touch radius for grabbing a handle, as a fraction of the shorter rendered edge. */
private const val HANDLE_TOUCH_RADIUS_FRACTION = 0.075f

/**
 * The Main Scanner crop editor: the retained capture with an editable document quadrilateral.
 *
 * Behaviour is taken from the locked reference (`docs/main-scanner-reference.md`, stage 4):
 *
 * - The captured page is shown whole (`ContentScale.Fit`), letterboxed on dark chrome. It is never
 *   cropped to fill, because the user must be able to reach a corner that sits near the frame edge.
 * - Eight handles — four corners and four edge midpoints — are draggable.
 * - A precision loupe appears while dragging and vanishes on release.
 * - `Left` / `Right` rotate, `All` resets to the full frame, `Next` advances.
 *
 * All coordinate work is delegated to the tested pure layer: [MainScanCropEditor] owns polygon
 * constraints and [MainScanMagnifier] owns loupe placement and sampling. This file maps between
 * normalized image space and viewport pixels, and draws.
 */
@Composable
fun MainScanCropEditorScreen(
    image: MainScanWorkingImage?,
    cropState: MainScanCropState,
    onCropStateChange: (MainScanCropState) -> Unit,
    onRotate: (MainScanRotation) -> Unit,
    onResetAll: () -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit
) {
    DarkSystemBarsEffect()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(CropChrome)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Text(
                    text = "Crop",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (image == null) {
                    CircularProgressIndicator(color = MainScanAccent)
                } else {
                    CropSurface(
                        image = image,
                        cropState = cropState,
                        onCropStateChange = onCropStateChange
                    )
                }
            }

            CropActionBar(
                nextEnabled = image != null && MainScanCropEditor.isApplicable(cropState),
                onRotateLeft = { onRotate(MainScanRotation.LEFT) },
                onRotateRight = { onRotate(MainScanRotation.RIGHT) },
                onResetAll = onResetAll,
                onNext = onNext
            )
        }
    }
}

/**
 * The interactive surface: the working bitmap plus the polygon, handles and loupe.
 *
 * The rendered image rectangle is computed from the container and the bitmap under `Fit`, and every
 * normalized coordinate is mapped through it. Touches are converted back the same way, so a handle
 * sits exactly under the finger regardless of letterboxing.
 */
@Composable
private fun CropSurface(
    image: MainScanWorkingImage,
    cropState: MainScanCropState,
    onCropStateChange: (MainScanCropState) -> Unit
) {
    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    val imageBitmap = remember(image.bitmap) { image.bitmap.asImageBitmap() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
    ) {
        if (containerSize.width <= 0 || containerSize.height <= 0) return@Box

        val rendered = remember(containerSize, image.width, image.height) {
            fitRect(
                containerWidth = containerSize.width.toFloat(),
                containerHeight = containerSize.height.toFloat(),
                sourceWidth = image.width,
                sourceHeight = image.height
            )
        }

        fun toViewport(point: CropPoint) = Offset(
            x = rendered.left + point.x * rendered.width,
            y = rendered.top + point.y * rendered.height
        )

        fun toNormalized(offset: Offset) = CropPoint(
            x = if (rendered.width <= 0f) 0f else (offset.x - rendered.left) / rendered.width,
            y = if (rendered.height <= 0f) 0f else (offset.y - rendered.top) / rendered.height
        )

        androidx.compose.foundation.Image(
            bitmap = imageBitmap,
            contentDescription = "Captured page",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )

        val touchRadiusPx = minOf(rendered.width, rendered.height) * HANDLE_TOUCH_RADIUS_FRACTION
        val touchRadiusNormalized = if (rendered.width <= 0f) {
            0f
        } else {
            touchRadiusPx / minOf(rendered.width, rendered.height)
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(rendered, image.width, image.height) {
                    detectDragGestures(
                        onDragStart = { position ->
                            val normalized = toNormalized(position)
                            val handle = MainScanCropEditor.handleAt(
                                quad = cropState.quad,
                                x = normalized.x,
                                y = normalized.y,
                                radius = touchRadiusNormalized
                            )
                            if (handle != null) {
                                onCropStateChange(MainScanCropEditor.beginDrag(cropState, handle))
                            }
                        },
                        onDrag = { change, _ ->
                            val handle = cropState.activeHandle ?: return@detectDragGestures
                            change.consume()
                            val normalized = toNormalized(change.position)
                            onCropStateChange(
                                MainScanCropEditor.moveHandle(
                                    state = cropState,
                                    handle = handle,
                                    x = normalized.x,
                                    y = normalized.y
                                )
                            )
                        },
                        onDragEnd = { onCropStateChange(MainScanCropEditor.endDrag(cropState)) },
                        onDragCancel = { onCropStateChange(MainScanCropEditor.endDrag(cropState)) }
                    )
                }
        ) {
            val corners = cropState.quad.corners().map { toViewport(it) }
            val path = Path().apply {
                moveTo(corners[0].x, corners[0].y)
                for (i in 1 until corners.size) lineTo(corners[i].x, corners[i].y)
                close()
            }
            drawPath(path, color = MainScanAccent.copy(alpha = 0.10f))
            drawPath(
                path = path,
                color = MainScanAccent,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )

            // Corner handles: filled discs. Edge handles: capsules oriented along their edge.
            corners.forEach { corner ->
                drawCircle(color = Color.White, radius = 7.dp.toPx(), center = corner)
                drawCircle(color = MainScanAccent, radius = 5.dp.toPx(), center = corner)
            }
            for (handle in listOf(
                MainScanCropHandle.TOP,
                MainScanCropHandle.RIGHT,
                MainScanCropHandle.BOTTOM,
                MainScanCropHandle.LEFT
            )) {
                val position = toViewport(
                    MainScanCropEditor.handlePosition(cropState.quad, handle)
                )
                val horizontal = handle == MainScanCropHandle.TOP ||
                    handle == MainScanCropHandle.BOTTOM
                val halfLong = 11.dp.toPx()
                val halfShort = 3.5f.dp.toPx()
                val start = if (horizontal) {
                    Offset(position.x - halfLong, position.y)
                } else {
                    Offset(position.x, position.y - halfLong)
                }
                val end = if (horizontal) {
                    Offset(position.x + halfLong, position.y)
                } else {
                    Offset(position.x, position.y + halfLong)
                }
                drawLine(
                    color = MainScanAccent,
                    start = start,
                    end = end,
                    strokeWidth = halfShort * 2f,
                    cap = StrokeCap.Round
                )
            }

            // The precision loupe, drawn last so nothing overlaps it.
            val activeHandle = cropState.activeHandle
            if (activeHandle != null) {
                val point = MainScanCropEditor.handlePosition(cropState.quad, activeHandle)
                val touch = toViewport(point)
                val placement = MainScanMagnifier.place(
                    touchX = touch.x,
                    touchY = touch.y,
                    viewportWidth = size.width,
                    viewportHeight = size.height
                )
                drawMagnifier(
                    imageBitmap = imageBitmap,
                    sourceWidth = image.width,
                    sourceHeight = image.height,
                    point = point,
                    placementX = placement.centerX,
                    placementY = placement.centerY,
                    radius = placement.radius
                )
            }
        }
    }
}

/**
 * Draws the loupe: a circular window of magnified source pixels with a crosshair marking the exact
 * control point, ringed so it reads as an instrument rather than part of the page.
 *
 * The magnified region is produced by drawing the whole bitmap scaled up and translated so the
 * dragged point lands at the loupe centre, clipped to the circle. Sampling the bitmap directly this
 * way is what keeps the loupe showing real captured detail rather than an upscaled preview.
 */
private fun DrawScope.drawMagnifier(
    imageBitmap: androidx.compose.ui.graphics.ImageBitmap,
    sourceWidth: Int,
    sourceHeight: Int,
    point: CropPoint,
    placementX: Float,
    placementY: Float,
    radius: Float
) {
    if (radius <= 0f || sourceWidth <= 0 || sourceHeight <= 0) return

    val magnification = MainScanMagnifier.MAGNIFICATION
    // Scale the source so one source pixel covers `magnification` screen pixels relative to how the
    // image is currently rendered; using the loupe diameter keeps it independent of image size.
    val baseScale = (radius * 2f) / minOf(sourceWidth, sourceHeight).toFloat()
    val scale = baseScale * magnification
    val scaledWidth = sourceWidth * scale
    val scaledHeight = sourceHeight * scale

    val circle = Path().apply {
        addOval(
            androidx.compose.ui.geometry.Rect(
                left = placementX - radius,
                top = placementY - radius,
                right = placementX + radius,
                bottom = placementY + radius
            )
        )
    }

    clipPath(circle) {
        drawRect(
            color = Color.Black,
            topLeft = Offset(placementX - radius, placementY - radius),
            size = Size(radius * 2f, radius * 2f)
        )
        // Offset so the dragged point sits exactly at the loupe centre.
        val originX = placementX - point.x * scaledWidth
        val originY = placementY - point.y * scaledHeight
        translate(left = originX, top = originY) {
            drawImage(
                image = imageBitmap,
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(scaledWidth.roundToInt(), scaledHeight.roundToInt())
            )
        }
        // Crosshair marking the precise control point.
        val arm = radius * 0.55f
        drawLine(
            color = MainScanAccent,
            start = Offset(placementX - arm, placementY),
            end = Offset(placementX + arm, placementY),
            strokeWidth = 1.5.dp.toPx()
        )
        drawLine(
            color = MainScanAccent,
            start = Offset(placementX, placementY - arm),
            end = Offset(placementX, placementY + arm),
            strokeWidth = 1.5.dp.toPx()
        )
    }
    drawCircle(
        color = Color.White,
        radius = radius,
        center = Offset(placementX, placementY),
        style = Stroke(width = 2.dp.toPx())
    )
}

/** The reference's four crop actions, in the same order and with the same functions. */
@Composable
private fun CropActionBar(
    nextEnabled: Boolean,
    onRotateLeft: () -> Unit,
    onRotateRight: () -> Unit,
    onResetAll: () -> Unit,
    onNext: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(CropChrome)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CropAction("Left", Icons.Filled.RotateLeft, enabled = true, onClick = onRotateLeft)
        CropAction("Right", Icons.Filled.RotateRight, enabled = true, onClick = onRotateRight)
        CropAction("All", Icons.Filled.CropFree, enabled = true, onClick = onResetAll)
        CropAction(
            label = "Next",
            icon = Icons.AutoMirrored.Filled.ArrowForward,
            enabled = nextEnabled,
            tint = MainScanAccent,
            onClick = onNext
        )
    }
}

@Composable
private fun CropAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.alpha(if (enabled) 1f else 0.4f)
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(24.dp))
        }
        Text(
            text = label,
            color = tint,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

/** The rectangle a source of [sourceWidth]x[sourceHeight] occupies under `Fit`, letterbox-aware. */
private data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float)

private fun fitRect(
    containerWidth: Float,
    containerHeight: Float,
    sourceWidth: Int,
    sourceHeight: Int
): FitRect {
    if (containerWidth <= 0f || containerHeight <= 0f || sourceWidth <= 0 || sourceHeight <= 0) {
        return FitRect(0f, 0f, 0f, 0f)
    }
    val scale = minOf(containerWidth / sourceWidth, containerHeight / sourceHeight)
    val width = sourceWidth * scale
    val height = sourceHeight * scale
    return FitRect(
        left = (containerWidth - width) / 2f,
        top = (containerHeight - height) / 2f,
        width = width,
        height = height
    )
}
