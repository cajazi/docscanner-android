package com.dev.docscannerpdf.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val ScreenBackground = Color(0xFF101114)
private val PanelBackground = Color(0xFF1B1C20)
private val Accent = Color(0xFF6C8CFF)
private val FavoriteColor = Color(0xFFF6C85F)
private val MutedText = Color(0xFFB8BDC4)

private val dateFormatter = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())

/**
 * Local-first, CamScanner-style document library. Renders saved documents straight from the
 * Room-backed [DocumentLibraryState] with search, sort, and per-document actions. The screen
 * is stateless — all data and actions are driven by the host — so opening a document routes
 * through the host to the best existing screen (unified result screen or PDF viewer).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentLibraryScreen(
    state: DocumentLibraryState,
    onBack: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSortChange: (DocumentLibrarySort) -> Unit,
    onOpenDocument: (DocumentLibraryItem) -> Unit,
    onToggleFavorite: (DocumentLibraryItem) -> Unit,
    onRenameDocument: (DocumentLibraryItem) -> Unit,
    onDeleteDocument: (DocumentLibraryItem) -> Unit
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
                    Text(
                        text = "Documents",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                actions = { LibrarySortMenu(current = state.sort, onSortChange = onSortChange) },
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
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.query,
                onValueChange = onQueryChange,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                placeholder = { Text("Search title or recognized text") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp)
            )

            when {
                state.isLoading -> LibraryStatusMessage(loading = true, message = "Loading documents…")
                state.isError -> LibraryStatusMessage(
                    loading = false,
                    message = state.errorMessage ?: "Unable to load documents."
                )
                state.isEmpty -> LibraryStatusMessage(
                    loading = false,
                    message = "No documents yet. Scanned and imported documents will appear here."
                )
                state.isFilteredEmpty -> LibraryStatusMessage(
                    loading = false,
                    message = "No documents match \"${state.query.trim()}\"."
                )
                else -> {
                    Text(
                        text = "${state.items.size} of ${state.totalCount} document(s)",
                        style = MaterialTheme.typography.labelMedium,
                        color = MutedText
                    )
                    LazyColumn(
                        contentPadding = PaddingValues(bottom = 96.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.items, key = { it.id }) { item ->
                            DocumentLibraryRow(
                                item = item,
                                onOpen = { onOpenDocument(item) },
                                onToggleFavorite = { onToggleFavorite(item) },
                                onRename = { onRenameDocument(item) },
                                onDelete = { onDeleteDocument(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySortMenu(
    current: DocumentLibrarySort,
    onSortChange: (DocumentLibrarySort) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    IconButton(onClick = { expanded = true }) {
        Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
    }
    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
        DocumentLibrarySort.entries.forEach { sort ->
            DropdownMenuItem(
                text = {
                    Text(
                        text = sort.label,
                        fontWeight = if (sort == current) FontWeight.Bold else FontWeight.Normal
                    )
                },
                onClick = {
                    expanded = false
                    onSortChange(sort)
                }
            )
        }
    }
}

@Composable
private fun LibraryStatusMessage(loading: Boolean, message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (loading) {
                CircularProgressIndicator(color = Accent, strokeWidth = 2.dp)
            }
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MutedText
            )
        }
    }
}

@Composable
private fun DocumentLibraryRow(
    item: DocumentLibraryItem,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onOpen),
        color = PanelBackground,
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            LibraryThumbnail(item)

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = dateFormatter.format(Date(item.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MutedText
                )
                if (item.hasSnippet) {
                    Text(
                        text = item.snippet!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFCED2D8),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LibraryChip(text = "${item.pageCount} page${if (item.pageCount == 1) "" else "s"}")
                    if (item.hasGeneratedPdf) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.PictureAsPdf,
                                contentDescription = "Has generated PDF",
                                tint = Accent,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("PDF", style = MaterialTheme.typography.labelSmall, color = Accent)
                        }
                    }
                }
            }

            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (item.isFavorite) Icons.Default.Star else Icons.Default.StarBorder,
                    contentDescription = if (item.isFavorite) "Unfavorite" else "Favorite",
                    tint = if (item.isFavorite) FavoriteColor else MutedText
                )
            }

            LibraryRowMenu(onOpen = onOpen, onRename = onRename, onDelete = onDelete)
        }
    }
}

@Composable
private fun LibraryThumbnail(item: DocumentLibraryItem) {
    Box(
        modifier = Modifier
            .size(width = 54.dp, height = 72.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2B2C30)),
        contentAlignment = Alignment.Center
    ) {
        if (item.hasThumbnail) {
            AsyncImage(
                model = item.thumbnailUri,
                contentDescription = "${item.title} thumbnail",
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Icon(
                imageVector = if (item.hasGeneratedPdf) Icons.Default.PictureAsPdf else Icons.Default.Description,
                contentDescription = null,
                tint = Accent
            )
        }
    }
}

@Composable
private fun LibraryChip(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFF2B2C30)
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MutedText
        )
    }
}

@Composable
private fun LibraryRowMenu(
    onOpen: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.MoreVert, contentDescription = "More actions", tint = Color.White)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Open") },
                onClick = { expanded = false; onOpen() }
            )
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = { expanded = false; onRename() }
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = { expanded = false; onDelete() }
            )
        }
    }
}
