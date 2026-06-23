package com.dev.docscannerpdf.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WatermarkPdfScreen(
    state: WatermarkPdfUiState,
    onBack: () -> Unit,
    onPickPdf: () -> Unit,
    onTextChange: (String) -> Unit,
    onOpacityChange: (Float) -> Unit,
    onRotationChange: (Float) -> Unit,
    onPositionChange: (WatermarkPosition) -> Unit,
    onApply: () -> Unit
) {
    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(
                        text = "Watermark PDF",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF151619),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF101114)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF101114))
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF1F2024)),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = state.selectedName ?: "No PDF selected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE8EAED),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = state.message ?: "Choose a PDF and enter watermark text.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB8BDC4)
                    )
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onPickPdf,
                contentPadding = PaddingValues(vertical = 13.dp)
            ) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                Text(modifier = Modifier.padding(start = 8.dp), text = "Select PDF")
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f / 1.414f),
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF0A0B0D)
            ) {
                Box(
                    modifier = Modifier.padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator()
                    } else if (state.previewBitmap != null) {
                        WatermarkPreviewPage(state = state)
                    } else {
                        Text(
                            text = "Live preview appears here",
                            color = Color(0xFFB8BDC4),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            TextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.watermarkText,
                onValueChange = { onTextChange(it.take(80)) },
                singleLine = true,
                label = { Text(text = "Watermark text") },
                leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null) }
            )

            SliderBlock(
                title = "Opacity ${(state.opacity * 100).roundToInt()}%",
                value = state.opacity,
                valueRange = 0.1f..0.85f,
                onValueChange = onOpacityChange
            )

            SliderBlock(
                title = "Rotation ${state.rotation.roundToInt()}°",
                value = state.rotation,
                valueRange = -60f..60f,
                onValueChange = onRotationChange
            )

            Text(
                text = "Position",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE8EAED)
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                WatermarkPosition.entries.forEach { position ->
                    FilterChip(
                        selected = state.position == position,
                        onClick = { onPositionChange(position) },
                        label = { Text(text = position.label) }
                    )
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = state.selectedUri != null && !state.isLoading && !state.isApplying,
                onClick = onApply,
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                if (state.isApplying) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Check, contentDescription = null)
                }
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = if (state.isApplying) "Applying" else "Apply Watermark"
                )
            }
        }
    }
}

@Composable
private fun WatermarkPreviewPage(state: WatermarkPdfUiState) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .drawWithContent {
                drawContent()
                val text = state.watermarkText.ifBlank { "Watermark" }
                val color = Color.Black.copy(alpha = state.opacity)
                if (state.position == WatermarkPosition.RepeatedDiagonal) {
                    for (y in -size.height.toInt()..(size.height * 1.5f).toInt() step 120) {
                        for (x in -size.width.toInt()..(size.width * 1.5f).toInt() step 210) {
                            rotate(degrees = state.rotation, pivot = androidx.compose.ui.geometry.Offset(x.toFloat(), y.toFloat())) {
                                drawContext.canvas.nativeCanvas.drawText(
                                    text,
                                    x.toFloat(),
                                    y.toFloat(),
                                    android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                                        this.color = android.graphics.Color.argb(
                                            (state.opacity * 255).roundToInt(),
                                            0,
                                            0,
                                            0
                                        )
                                        textSize = 28f
                                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                                    }
                                )
                            }
                        }
                    }
                } else {
                    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                        this.color = android.graphics.Color.argb((state.opacity * 255).roundToInt(), 0, 0, 0)
                        textSize = 34f
                        typeface = android.graphics.Typeface.DEFAULT_BOLD
                        textAlign = android.graphics.Paint.Align.CENTER
                    }
                    val point = previewWatermarkPoint(state.position, size.width, size.height)
                    rotate(degrees = state.rotation, pivot = point) {
                        drawContext.canvas.nativeCanvas.drawText(text, point.x, point.y, paint)
                    }
                }
            },
        shape = RoundedCornerShape(2.dp),
        color = Color.White
    ) {
        Image(
            bitmap = requireNotNull(state.previewBitmap).asImageBitmap(),
            contentDescription = "Watermark preview",
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Fit
        )
    }
}

private fun previewWatermarkPoint(
    position: WatermarkPosition,
    width: Float,
    height: Float
): androidx.compose.ui.geometry.Offset {
    val margin = 42f
    return when (position) {
        WatermarkPosition.Center -> androidx.compose.ui.geometry.Offset(width / 2f, height / 2f)
        WatermarkPosition.TopLeft -> androidx.compose.ui.geometry.Offset(width * 0.28f, margin)
        WatermarkPosition.TopRight -> androidx.compose.ui.geometry.Offset(width * 0.72f, margin)
        WatermarkPosition.BottomLeft -> androidx.compose.ui.geometry.Offset(width * 0.28f, height - margin)
        WatermarkPosition.BottomRight -> androidx.compose.ui.geometry.Offset(width * 0.72f, height - margin)
        WatermarkPosition.RepeatedDiagonal -> androidx.compose.ui.geometry.Offset(width / 2f, height / 2f)
    }
}

@Composable
private fun SliderBlock(
    title: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE8EAED)
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange
        )
    }
}

enum class WatermarkPosition(val label: String) {
    Center("Center"),
    TopLeft("Top Left"),
    TopRight("Top Right"),
    BottomLeft("Bottom Left"),
    BottomRight("Bottom Right"),
    RepeatedDiagonal("Repeated")
}

data class WatermarkPdfUiState(
    val selectedUri: String? = null,
    val selectedName: String? = null,
    val previewBitmap: Bitmap? = null,
    val pageCount: Int = 0,
    val watermarkText: String = "",
    val opacity: Float = 0.28f,
    val rotation: Float = -28f,
    val position: WatermarkPosition = WatermarkPosition.Center,
    val isLoading: Boolean = false,
    val isApplying: Boolean = false,
    val message: String? = null
)
