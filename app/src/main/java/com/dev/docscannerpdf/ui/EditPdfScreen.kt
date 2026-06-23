package com.dev.docscannerpdf.ui

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPdfScreen(
    state: EditPdfUiState,
    onBack: () -> Unit,
    onPickPdf: () -> Unit,
    onTitleChange: (String) -> Unit,
    onTogglePage: (String) -> Unit,
    onMovePage: (Int, Int) -> Unit,
    onDeleteSelected: () -> Unit,
    onRotateSelected: () -> Unit,
    onDuplicateSelected: () -> Unit,
    onSave: () -> Unit
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
                        text = "Edit PDF",
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
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        text = state.message ?: "Select pages, reorder, rotate, duplicate, or delete before saving.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB8BDC4)
                    )
                    TextField(
                        modifier = Modifier.fillMaxWidth(),
                        value = state.title,
                        onValueChange = { onTitleChange(it.take(80)) },
                        singleLine = true,
                        label = { Text(text = "Document title") }
                    )
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading && !state.isSaving,
                onClick = onPickPdf,
                contentPadding = PaddingValues(vertical = 13.dp)
            ) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                Text(modifier = Modifier.padding(start = 8.dp), text = "Select PDF")
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isLoading -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    state.pages.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(
                                text = "Page thumbnails appear here",
                                color = Color(0xFFB8BDC4),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            itemsIndexed(state.pages, key = { _, page -> page.id }) { index, page ->
                                EditPdfPageCard(
                                    page = page,
                                    index = index,
                                    selected = page.id in state.selectedPageIds,
                                    isFirst = index == 0,
                                    isLast = index == state.pages.lastIndex,
                                    onToggle = { onTogglePage(page.id) },
                                    onMoveUp = { onMovePage(index, -1) },
                                    onMoveDown = { onMovePage(index, 1) }
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = "${state.selectedPageIds.size} selected",
                color = Color(0xFFB8BDC4),
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = state.selectedPageIds.isNotEmpty() && !state.isSaving,
                    onClick = onDeleteSelected
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = state.selectedPageIds.isNotEmpty() && !state.isSaving,
                    onClick = onRotateSelected
                ) {
                    Icon(Icons.Default.RotateRight, contentDescription = null)
                }
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = state.selectedPageIds.isNotEmpty() && !state.isSaving,
                    onClick = onDuplicateSelected
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null)
                }
                Button(
                    modifier = Modifier.weight(1.25f),
                    enabled = state.pages.isNotEmpty() && !state.isSaving && !state.isLoading,
                    onClick = onSave
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Save, contentDescription = null)
                    }
                    Text(modifier = Modifier.padding(start = 6.dp), text = "Save")
                }
            }
        }
    }
}

@Composable
private fun EditPdfPageCard(
    page: EditPdfPage,
    index: Int,
    selected: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF1F2024)),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f / 1.414f)
                    .border(
                        width = if (selected) 2.dp else 1.dp,
                        color = if (selected) Color(0xFF12BFA0) else Color.White.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(6.dp)
                    )
                    .clickable(onClick = onToggle),
                shape = RoundedCornerShape(6.dp),
                color = Color.White
            ) {
                Box {
                    Image(
                        bitmap = page.thumbnail.asImageBitmap(),
                        contentDescription = "Page ${index + 1}",
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(rotationZ = page.rotation.toFloat()),
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
                            text = "${index + 1}",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    if (selected) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(5.dp)
                                .size(22.dp),
                            shape = CircleShape,
                            color = Color(0xFF12BFA0)
                        ) {}
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Source ${page.sourcePageIndex + 1}",
                    color = Color(0xFFB8BDC4),
                    style = MaterialTheme.typography.bodySmall
                )
                Row {
                    IconButton(enabled = !isFirst, onClick = onMoveUp) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Move page up")
                    }
                    IconButton(enabled = !isLast, onClick = onMoveDown) {
                        Icon(Icons.Default.ArrowDownward, contentDescription = "Move page down")
                    }
                }
            }
        }
    }
}

data class EditPdfPage(
    val id: String,
    val sourcePageIndex: Int,
    val rotation: Int = 0,
    val thumbnail: Bitmap
)

data class EditPdfUiState(
    val selectedUri: String? = null,
    val selectedName: String? = null,
    val title: String = "Edited PDF",
    val pages: List<EditPdfPage> = emptyList(),
    val selectedPageIds: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val message: String? = null
)
