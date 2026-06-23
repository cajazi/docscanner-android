package com.dev.docscannerpdf.ui

import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignPdfScreen(
    state: SignPdfUiState,
    onBack: () -> Unit,
    onPickPdf: () -> Unit,
    onSelectPage: (Int) -> Unit,
    onSignatureSaved: (Bitmap) -> Unit,
    onClearSignature: () -> Unit,
    onMoveSignature: (Float, Float) -> Unit,
    onResizeSignature: (Float) -> Unit,
    onExport: () -> Unit
) {
    var showSignatureEditor by remember { mutableStateOf(false) }

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
                        text = "Sign PDF",
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                        text = state.message ?: "Select a PDF, choose a page, then place your signature.",
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

            if (state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (state.pageThumbnails.isEmpty()) {
                EmptySignState(modifier = Modifier.weight(1f))
            } else {
                SignaturePlacementPreview(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    state = state,
                    onMoveSignature = onMoveSignature
                )
            }

            if (state.pageThumbnails.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    itemsIndexed(state.pageThumbnails) { index, bitmap ->
                        SignPageThumbnail(
                            bitmap = bitmap,
                            pageNumber = index + 1,
                            selected = index == state.selectedPageIndex,
                            onClick = { onSelectPage(index) }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = state.pageThumbnails.isNotEmpty(),
                    onClick = { showSignatureEditor = true }
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null)
                    Text(modifier = Modifier.padding(start = 6.dp), text = "Draw")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = state.signatureBitmap != null,
                    onClick = onClearSignature
                ) {
                    Text(text = "Clear")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = state.signatureBitmap != null,
                    onClick = { onResizeSignature((state.signatureScale - 0.1f).coerceAtLeast(0.45f)) }
                ) {
                    Text(text = "Smaller")
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = state.signatureBitmap != null,
                    onClick = { onResizeSignature((state.signatureScale + 0.1f).coerceAtMost(1.8f)) }
                ) {
                    Text(text = "Larger")
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = state.selectedUri != null &&
                    state.signatureBitmap != null &&
                    !state.isLoading &&
                    !state.isExporting,
                onClick = onExport,
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                if (state.isExporting) {
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
                    text = if (state.isExporting) "Saving signed PDF" else "Save Signed PDF"
                )
            }
        }
    }

    if (showSignatureEditor) {
        SignatureEditorDialog(
            onDismiss = { showSignatureEditor = false },
            onSave = { bitmap ->
                onSignatureSaved(bitmap)
                showSignatureEditor = false
            }
        )
    }
}

@Composable
private fun EmptySignState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "Pick a PDF to preview pages.",
            color = Color(0xFFB8BDC4),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun SignaturePlacementPreview(
    modifier: Modifier,
    state: SignPdfUiState,
    onMoveSignature: (Float, Float) -> Unit
) {
    val selectedPage = state.pageThumbnails.getOrNull(state.selectedPageIndex)
    var boxSize by remember { mutableStateOf(IntSize.Zero) }

    Surface(
        modifier = modifier,
        color = Color(0xFF0A0B0D),
        shape = RoundedCornerShape(18.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f / 1.414f)
                    .onSizeChanged { boxSize = it },
                shape = RoundedCornerShape(2.dp),
                color = Color.White
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (selectedPage != null) {
                        Image(
                            bitmap = selectedPage.asImageBitmap(),
                            contentDescription = "Selected PDF page",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    }
                    state.signatureBitmap?.let { signature ->
                        Image(
                            bitmap = signature.asImageBitmap(),
                            contentDescription = "Signature placement",
                            modifier = Modifier
                                .width((150.dp * state.signatureScale).coerceAtLeast(70.dp))
                                .offset {
                                    IntOffset(
                                        (state.signatureOffsetX * boxSize.width).roundToInt(),
                                        (state.signatureOffsetY * boxSize.height).roundToInt()
                                    )
                                }
                                .pointerInput(boxSize, state.signatureOffsetX, state.signatureOffsetY) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        if (boxSize.width > 0 && boxSize.height > 0) {
                                            onMoveSignature(
                                                (state.signatureOffsetX + dragAmount.x / boxSize.width)
                                                    .coerceIn(-0.48f, 0.48f),
                                                (state.signatureOffsetY + dragAmount.y / boxSize.height)
                                                    .coerceIn(-0.48f, 0.48f)
                                            )
                                        }
                                    }
                                },
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SignPageThumbnail(
    bitmap: Bitmap,
    pageNumber: Int,
    selected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(68.dp)
            .aspectRatio(1f / 1.414f)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) Color(0xFF12BFA0) else Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(6.dp),
        color = Color.White
    ) {
        Box {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Page $pageNumber",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(5.dp),
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.62f)
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                    text = "$pageNumber",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun SignatureEditorDialog(
    onDismiss: () -> Unit,
    onSave: (Bitmap) -> Unit
) {
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var textSignature by remember { mutableStateOf("") }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(18.dp),
        color = Color.Transparent
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center
        ) {
            ElevatedCard(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF1F2024)),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Create Signature",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        TextButton(onClick = onDismiss) {
                            Text(text = "Close")
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                            .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                        shape = RoundedCornerShape(12.dp),
                        color = Color.Transparent
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .onSizeChanged { canvasSize = it }
                                .pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { offset -> currentStroke = listOf(offset) },
                                        onDragEnd = {
                                            if (currentStroke.size > 1) {
                                                strokes.add(currentStroke)
                                            }
                                            currentStroke = emptyList()
                                        },
                                        onDragCancel = { currentStroke = emptyList() }
                                    ) { change, dragAmount ->
                                        change.consume()
                                        val next = (currentStroke.lastOrNull() ?: change.position) + dragAmount
                                        currentStroke = currentStroke + next
                                    }
                                }
                        ) {
                            val allStrokes = strokes + listOf(currentStroke)
                            allStrokes.forEach { stroke ->
                                for (index in 0 until stroke.lastIndex) {
                                    drawLine(
                                        color = Color.White,
                                        start = stroke[index],
                                        end = stroke[index + 1],
                                        strokeWidth = 6f,
                                        cap = StrokeCap.Round
                                    )
                                }
                            }
                        }
                    }

                    TextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = textSignature,
                        onValueChange = { textSignature = it.take(50) },
                        singleLine = true,
                        label = { Text(text = "Optional text signature") },
                        leadingIcon = {
                            Icon(Icons.Default.TextFields, contentDescription = null)
                        }
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = {
                                strokes.clear()
                                currentStroke = emptyList()
                            }
                        ) {
                            Text(text = "Clear")
                        }
                        Button(
                            modifier = Modifier.weight(1f),
                            enabled = strokes.isNotEmpty() || currentStroke.isNotEmpty() || textSignature.isNotBlank(),
                            onClick = {
                                onSave(
                                    createTransparentSignatureBitmap(
                                        strokes = strokes.toList(),
                                        currentStroke = currentStroke,
                                        textSignature = textSignature.trim(),
                                        sourceWidth = canvasSize.width.coerceAtLeast(1),
                                        sourceHeight = canvasSize.height.coerceAtLeast(1)
                                    )
                                )
                            }
                        ) {
                            Text(text = "Save")
                        }
                    }
                }
            }
        }
    }
}

private fun createTransparentSignatureBitmap(
    strokes: List<List<Offset>>,
    currentStroke: List<Offset>,
    textSignature: String,
    sourceWidth: Int,
    sourceHeight: Int
): Bitmap {
    val targetWidth = 900
    val targetHeight = 320
    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(AndroidColor.TRANSPARENT)
    val canvas = android.graphics.Canvas(bitmap)
    val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = AndroidColor.BLACK
        strokeWidth = 8f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    val scaleX = targetWidth / sourceWidth.toFloat()
    val scaleY = targetHeight / sourceHeight.toFloat()
    (strokes + listOf(currentStroke)).forEach { stroke ->
        if (stroke.size > 1) {
            val path = Path().apply {
                moveTo(stroke.first().x * scaleX, stroke.first().y * scaleY)
                stroke.drop(1).forEach { point ->
                    lineTo(point.x * scaleX, point.y * scaleY)
                }
            }
            canvas.drawPath(path, strokePaint)
        }
    }
    if (textSignature.isNotBlank()) {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = AndroidColor.BLACK
            textSize = 72f
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
        }
        canvas.drawText(textSignature, 34f, targetHeight - 64f, textPaint)
    }
    return bitmap
}

data class SignPdfUiState(
    val selectedUri: String? = null,
    val selectedName: String? = null,
    val pageThumbnails: List<Bitmap> = emptyList(),
    val selectedPageIndex: Int = 0,
    val signatureBitmap: Bitmap? = null,
    val signatureOffsetX: Float = 0f,
    val signatureOffsetY: Float = 0.25f,
    val signatureScale: Float = 1f,
    val isLoading: Boolean = false,
    val isExporting: Boolean = false,
    val message: String? = null
)
