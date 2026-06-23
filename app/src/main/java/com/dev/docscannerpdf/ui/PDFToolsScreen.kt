package com.dev.docscannerpdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Merge
import androidx.compose.material.icons.filled.Splitscreen
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dev.docscannerpdf.data.local.DocumentEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PDFToolsScreen(
    documents: List<DocumentEntity>,
    onBack: () -> Unit,
    onMergePdf: () -> Unit,
    onSplitPdf: () -> Unit,
    onCompressPdf: () -> Unit,
    onPdfToImages: () -> Unit,
    onImagesToPdf: () -> Unit,
    onEditPdf: () -> Unit,
    onLockPdf: () -> Unit,
    onUnlockPdf: () -> Unit,
    onSignPdf: () -> Unit,
    onWatermarkPdf: () -> Unit,
    onPdfToWord: () -> Unit,
    onRenameDocument: (DocumentEntity, String) -> Unit,
    onShareExtractedText: (DocumentEntity) -> Unit,
    onShareCleanedText: (String, String) -> Unit,
    onExportCleanedText: (String, String, String) -> Unit,
    onComingSoon: (String) -> Unit
) {
    var showRenameDialog by remember { mutableStateOf(false) }
    var showExtractTextDialog by remember { mutableStateOf(false) }
    var showCleanTextDialog by remember { mutableStateOf(false) }
    val tools = listOf(
        PdfTool("Merge PDF", Icons.Default.Merge, Color(0xFF28D6B0), onMergePdf),
        PdfTool("Split PDF", Icons.Default.Splitscreen, Color(0xFF7AA7FF), onSplitPdf),
        PdfTool("Edit PDF", Icons.Default.Edit, Color(0xFF35D5B4), onEditPdf),
        PdfTool("Compress PDF", Icons.Default.Compress, Color(0xFFFFB74D), onCompressPdf),
        PdfTool("PDF to Word", Icons.Default.Description, Color(0xFF9CCC65), onPdfToWord),
        PdfTool("PDF to Images", Icons.Default.Image, Color(0xFFB388FF), onPdfToImages),
        PdfTool("Images to PDF", Icons.Default.Description, Color(0xFF46D9FF), onImagesToPdf),
        PdfTool("Sign PDF", Icons.Default.Edit, Color(0xFF28D6B0), onSignPdf),
        PdfTool("Watermark PDF", Icons.Default.TextFields, Color(0xFF80CBC4), onWatermarkPdf),
        PdfTool("Extract Text", Icons.Default.TextFields, Color(0xFF35D5B4)) {
            showExtractTextDialog = true
        },
        PdfTool("Clean OCR Text", Icons.Default.TextFields, Color(0xFFB2DFDB)) {
            showCleanTextDialog = true
        },
        PdfTool("Lock PDF", Icons.Default.Lock, Color(0xFFFF6B6B), onLockPdf),
        PdfTool("Unlock PDF", Icons.Default.LockOpen, Color(0xFF5EE08E), onUnlockPdf),
        PdfTool("Rename PDF", Icons.Default.TextFields, Color(0xFFFF8A65)) {
            showRenameDialog = true
        }
    )

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
                        text = "PDF Tools",
                        style = MaterialTheme.typography.titleLarge,
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
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF101114)),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tools, key = { it.label }) { tool ->
                PdfToolCard(tool = tool)
            }
        }
    }

    if (showRenameDialog) {
        RenameDocumentDialog(
            documents = documents,
            initialDocument = null,
            onDismiss = { showRenameDialog = false },
            onRename = onRenameDocument,
            onValidationError = onComingSoon
        )
    }

    if (showExtractTextDialog) {
        ExtractTextDialog(
            documents = documents,
            initialDocument = null,
            onDismiss = { showExtractTextDialog = false },
            onShareText = onShareExtractedText,
            onShareCleanedText = onShareCleanedText,
            onExportCleanedText = onExportCleanedText,
            onValidationError = onComingSoon
        )
    }

    if (showCleanTextDialog) {
        ExtractTextDialog(
            documents = documents,
            initialDocument = null,
            title = "Clean OCR Text",
            onDismiss = { showCleanTextDialog = false },
            onShareText = onShareExtractedText,
            onShareCleanedText = onShareCleanedText,
            onExportCleanedText = onExportCleanedText,
            onValidationError = onComingSoon
        )
    }
}

@Composable
private fun PdfToolCard(
    tool: PdfTool
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = tool.onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = Color(0xFF1F2024)
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = Color(0xFF2B2C31)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = tool.icon,
                        contentDescription = null,
                        tint = tool.tint,
                        modifier = Modifier.size(25.dp)
                    )
                }
            }
            Text(
                text = tool.label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE8EAED),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

private data class PdfTool(
    val label: String,
    val icon: ImageVector,
    val tint: Color,
    val onClick: () -> Unit
)
