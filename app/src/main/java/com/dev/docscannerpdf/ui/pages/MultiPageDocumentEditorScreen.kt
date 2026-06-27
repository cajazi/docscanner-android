package com.dev.docscannerpdf.ui.pages

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import com.dev.docscannerpdf.ui.PdfThumbnailLoader

private val ScreenBackground = Color(0xFF101114)
private val PanelBackground = Color(0xFF1B1C20)
private val Accent = Color(0xFF6C8CFF)
private val OcrColor = Color(0xFF16C89A)
private val MutedText = Color(0xFFB8BDC4)

/**
 * CamScanner-style multi-page editor foundation. Renders a [MultiPageEditorState]: a selected
 * page preview, a thumbnail strip, page count, and deterministic actions (add placeholder,
 * duplicate, rotate, delete with confirmation, and up/down reorder). Stateless — all edits
 * are driven by the host through [MultiPageEditorReducer]; reordering preserves each page's
 * OCR/export metadata.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiPageDocumentEditorScreen(
    state: MultiPageEditorState,
    onBack: () -> Unit,
    onSelectPage: (String) -> Unit,
    onMovePageUp: (String) -> Unit,
    onMovePageDown: (String) -> Unit,
    onAddPage: () -> Unit,
    onDuplicatePage: (String) -> Unit,
    onRotatePage: (String) -> Unit,
    onRequestDeletePage: (String) -> Unit,
    onConfirmDeletePage: () -> Unit,
    onCancelDeletePage: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Column {
                        Text(
                            text = state.title.ifBlank { "Document" },
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${state.pageCount} page${if (state.pageCount == 1) "" else "s"}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MutedText
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onAddPage) {
                        Icon(Icons.Default.Add, contentDescription = "Add page")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF202124),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        containerColor = ScreenBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when {
                state.status == EditorStatus.LOADING -> EditorStatusBox(loading = true, message = "Loading pages…")
                state.status == EditorStatus.ERROR -> EditorStatusBox(
                    loading = false,
                    message = state.errorMessage ?: "Unable to open this document."
                )
                state.isEmpty || state.pages.isEmpty() -> EditorStatusBox(
                    loading = false,
                    message = "This document has no pages."
                )
                else -> {
                    val selected = state.selectedPage ?: state.pages.first()
                    SelectedPagePreview(page = selected, modifier = Modifier.weight(1f))
                    PageActionBar(
                        selected = selected,
                        pageCount = state.pageCount,
                        onMoveUp = { onMovePageUp(selected.pageId) },
                        onMoveDown = { onMovePageDown(selected.pageId) },
                        onDuplicate = { onDuplicatePage(selected.pageId) },
                        onRotate = { onRotatePage(selected.pageId) },
                        onDelete = { onRequestDeletePage(selected.pageId) }
                    )
                    PageThumbnailStrip(
                        pages = state.pages,
                        selectedPageId = selected.pageId,
                        onSelectPage = onSelectPage
                    )
                }
            }
        }
    }

    state.pendingDeletePage?.let { page ->
        AlertDialog(
            onDismissRequest = onCancelDeletePage,
            title = { Text("Delete page ${page.pageNumber}?") },
            text = { Text("This removes the page from this editing session.") },
            confirmButton = {
                TextButton(onClick = onConfirmDeletePage) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = onCancelDeletePage) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun EditorStatusBox(loading: Boolean, message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (loading) CircularProgressIndicator(color = Accent, strokeWidth = 2.dp)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = MutedText)
        }
    }
}

@Composable
private fun SelectedPagePreview(page: EditorPage, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = PanelBackground
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            PageBitmap(
                sourceUri = page.thumbnailUri,
                pageIndex = page.sourcePageIndex,
                rotationDegrees = page.rotationDegrees,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        }
    }
}

@Composable
private fun PageActionBar(
    selected: EditorPage,
    pageCount: Int,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDuplicate: () -> Unit,
    onRotate: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = PanelBackground
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            EditorAction("Up", Icons.Default.ChevronLeft, enabled = selected.order > 0, onClick = onMoveUp)
            EditorAction("Down", Icons.Default.ChevronRight, enabled = selected.order < pageCount - 1, onClick = onMoveDown)
            EditorAction("Duplicate", Icons.Default.ContentCopy, onClick = onDuplicate)
            EditorAction("Rotate", Icons.AutoMirrored.Filled.RotateRight, onClick = onRotate)
            EditorAction("Delete", Icons.Default.Delete, tint = Color(0xFFFF6B6B), onClick = onDelete)
        }
    }
}

@Composable
private fun EditorAction(
    label: String,
    icon: ImageVector,
    enabled: Boolean = true,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val resolvedTint = if (enabled) tint else MutedText.copy(alpha = 0.4f)
        Icon(icon, contentDescription = label, tint = resolvedTint, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = resolvedTint)
    }
}

@Composable
private fun PageThumbnailStrip(
    pages: List<EditorPage>,
    selectedPageId: String?,
    onSelectPage: (String) -> Unit
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(pages, key = { it.pageId }) { page ->
            PageThumbnailCell(
                page = page,
                selected = page.pageId == selectedPageId,
                onClick = { onSelectPage(page.pageId) }
            )
        }
    }
}

@Composable
private fun PageThumbnailCell(
    page: EditorPage,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .height(84.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF2A2C31))
                .border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) Accent else Color.White.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(6.dp)
                )
                .clickable(onClick = onClick)
        ) {
            PageBitmap(
                sourceUri = page.thumbnailUri,
                pageIndex = page.sourcePageIndex,
                rotationDegrees = page.rotationDegrees,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            if (page.ocrAvailable) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(OcrColor)
                )
            }
        }
        Text(
            text = "${page.pageNumber}",
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Accent else MutedText,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
        )
    }
}

@Composable
private fun PageBitmap(
    sourceUri: String?,
    pageIndex: Int,
    rotationDegrees: Int,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    val context = LocalContext.current
    var bitmap by remember(sourceUri, pageIndex) { mutableStateOf<Bitmap?>(null) }
    var loadFinished by remember(sourceUri, pageIndex) { mutableStateOf(false) }

    LaunchedEffect(sourceUri, pageIndex) {
        loadFinished = false
        bitmap = sourceUri?.let {
            PdfThumbnailLoader.loadPageBitmap(context.applicationContext, it, pageIndex)
        }
        loadFinished = true
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val rendered = bitmap
        if (rendered != null) {
            Image(
                bitmap = rendered.asImageBitmap(),
                contentDescription = "Page ${pageIndex + 1}",
                modifier = Modifier
                    .fillMaxSize()
                    .rotate(rotationDegrees.toFloat()),
                contentScale = contentScale
            )
        } else if (loadFinished) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = "Preview unavailable",
                tint = Color(0xFF8F949C)
            )
        } else {
            CircularProgressIndicator(color = Accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        }
    }
}
