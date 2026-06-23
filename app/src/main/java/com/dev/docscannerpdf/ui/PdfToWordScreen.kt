package com.dev.docscannerpdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PdfToWordScreen(
    state: PdfToWordUiState,
    onBack: () -> Unit,
    onPickPdf: () -> Unit,
    onConvert: () -> Unit
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
                        text = "PDF to Word",
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
                        text = state.message ?: "Select a PDF and export OCR text as a Word-ready text file.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB8BDC4)
                    )
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.isLoading && !state.isConverting,
                onClick = onPickPdf,
                contentPadding = PaddingValues(vertical = 13.dp)
            ) {
                Icon(Icons.Default.PictureAsPdf, contentDescription = null)
                Text(modifier = Modifier.padding(start = 8.dp), text = "Select PDF")
            }

            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF1A1B1F)),
                elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Export format",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE8EAED)
                    )
                    Text(
                        text = "A structured .txt file will be created with page headings and separators. Future DOCX export can be added later with a dedicated document library.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB8BDC4)
                    )
                    if (state.pageCount > 0) {
                        Text(
                            text = "${state.pageCount} page${if (state.pageCount == 1) "" else "s"} detected",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF35D5B4)
                        )
                    }
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = state.selectedUri != null && !state.isLoading && !state.isConverting,
                onClick = onConvert,
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                if (state.isLoading || state.isConverting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Description, contentDescription = null)
                }
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = when {
                        state.isLoading -> "Loading"
                        state.isConverting -> "Converting"
                        else -> "Convert"
                    }
                )
            }
        }
    }
}

data class PdfToWordUiState(
    val selectedUri: String? = null,
    val selectedName: String? = null,
    val pageCount: Int = 0,
    val isLoading: Boolean = false,
    val isConverting: Boolean = false,
    val message: String? = null
)
