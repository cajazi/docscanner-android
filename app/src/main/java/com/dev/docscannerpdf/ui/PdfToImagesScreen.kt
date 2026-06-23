package com.dev.docscannerpdf.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
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
fun PdfToImagesScreen(
    state: PdfToImagesUiState,
    onBack: () -> Unit,
    onPickPdf: () -> Unit,
    onShareImages: () -> Unit,
    onSaveToApp: () -> Unit
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
                        text = "PDF to Images",
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
                        text = state.message ?: "Select a PDF to render each page as an image.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB8BDC4)
                    )
                    if (state.outputPaths.isNotEmpty()) {
                        Text(
                            text = "${state.outputPaths.size} image${if (state.outputPaths.size == 1) "" else "s"} generated",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF35D5B4)
                        )
                    }
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isRendering,
                onClick = onPickPdf,
                contentPadding = PaddingValues(vertical = 13.dp)
            ) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                Text(modifier = Modifier.padding(start = 8.dp), text = "Select PDF")
            }

            Box(modifier = Modifier.weight(1f)) {
                when {
                    state.isRendering -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator()
                        }
                    }
                    state.thumbnails.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Generated thumbnails appear here",
                                color = Color(0xFFB8BDC4),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(3),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            itemsIndexed(state.thumbnails) { index, bitmap ->
                                PdfImageThumbnail(
                                    bitmap = bitmap,
                                    pageNumber = index + 1
                                )
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    modifier = Modifier.weight(1f),
                    enabled = state.outputPaths.isNotEmpty() && !state.isRendering,
                    onClick = onShareImages
                ) {
                    Icon(Icons.Default.Share, contentDescription = null)
                    Text(modifier = Modifier.padding(start = 6.dp), text = "Share Images")
                }
                Button(
                    modifier = Modifier.weight(1f),
                    enabled = state.outputPaths.isNotEmpty() && !state.isRendering,
                    onClick = onSaveToApp
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Text(modifier = Modifier.padding(start = 6.dp), text = "Save to App")
                }
            }
        }
    }
}

@Composable
private fun PdfImageThumbnail(
    bitmap: Bitmap,
    pageNumber: Int
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f / 1.414f)
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp)),
        shape = RoundedCornerShape(6.dp),
        color = Color.White
    ) {
        Box {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Generated image $pageNumber",
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
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = Color(0xFF35D5B4),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(5.dp)
            )
        }
    }
}

data class PdfToImagesUiState(
    val selectedUri: String? = null,
    val selectedName: String? = null,
    val outputPaths: List<String> = emptyList(),
    val thumbnails: List<Bitmap> = emptyList(),
    val isRendering: Boolean = false,
    val savedToApp: Boolean = false,
    val message: String? = null
)
