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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SplitPdfScreen(
    state: SplitPdfUiState,
    onBack: () -> Unit,
    onPickPdf: () -> Unit,
    onModeChange: (SplitPdfMode) -> Unit,
    onRangeChange: (String) -> Unit,
    onTogglePage: (Int) -> Unit,
    onSplit: () -> Unit
) {
    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Text(
                        text = "Split PDF",
                        fontWeight = FontWeight.Bold
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
                colors = CardDefaults.elevatedCardColors(
                    containerColor = Color(0xFF1F2024)
                ),
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
                        text = state.message ?: "${state.pageThumbnails.size} page${if (state.pageThumbnails.size == 1) "" else "s"} ready",
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
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = "Select PDF"
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SplitModeChip(
                    label = "Custom range",
                    selected = state.mode == SplitPdfMode.CustomRange,
                    onClick = { onModeChange(SplitPdfMode.CustomRange) }
                )
                SplitModeChip(
                    label = "Every page",
                    selected = state.mode == SplitPdfMode.EveryPage,
                    onClick = { onModeChange(SplitPdfMode.EveryPage) }
                )
                SplitModeChip(
                    label = "Selected",
                    selected = state.mode == SplitPdfMode.SelectedPages,
                    onClick = { onModeChange(SplitPdfMode.SelectedPages) }
                )
            }

            if (state.mode == SplitPdfMode.CustomRange) {
                TextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.customRange,
                    onValueChange = onRangeChange,
                    singleLine = true,
                    label = { Text(text = "Pages, e.g. 1-3,5") }
                )
            }

            if (state.mode == SplitPdfMode.SelectedPages) {
                AssistChip(
                    onClick = {},
                    label = {
                        Text(text = "${state.selectedPages.size} selected")
                    }
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                if (state.isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(state.pageThumbnails) { index, bitmap ->
                            SplitPageThumbnail(
                                bitmap = bitmap,
                                pageNumber = index + 1,
                                selected = index in state.selectedPages,
                                selectable = state.mode == SplitPdfMode.SelectedPages,
                                onClick = { onTogglePage(index) }
                            )
                        }
                    }
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = state.selectedUri != null && !state.isLoading && !state.isSplitting,
                onClick = onSplit,
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                if (state.isSplitting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(end = 8.dp)
                            .size(18.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Splitscreen, contentDescription = null)
                }
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = if (state.isSplitting) "Splitting" else "Split PDF"
                )
            }
        }
    }
}

@Composable
private fun SplitModeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    )
}

@Composable
private fun SplitPageThumbnail(
    bitmap: Bitmap,
    pageNumber: Int,
    selected: Boolean,
    selectable: Boolean,
    onClick: () -> Unit
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
            .clickable(enabled = selectable, onClick = onClick),
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
            if (selected) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(5.dp)
                        .size(22.dp),
                    shape = CircleShape,
                    color = Color(0xFF12BFA0)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        modifier = Modifier.padding(4.dp),
                        tint = Color.White
                    )
                }
            }
        }
    }
}

enum class SplitPdfMode {
    CustomRange,
    EveryPage,
    SelectedPages
}

data class SplitPdfUiState(
    val selectedUri: String? = null,
    val selectedName: String? = null,
    val pageThumbnails: List<Bitmap> = emptyList(),
    val selectedPages: Set<Int> = emptySet(),
    val customRange: String = "",
    val mode: SplitPdfMode = SplitPdfMode.CustomRange,
    val isLoading: Boolean = false,
    val isSplitting: Boolean = false,
    val message: String? = null
)
