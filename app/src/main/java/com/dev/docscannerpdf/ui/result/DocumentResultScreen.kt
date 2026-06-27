package com.dev.docscannerpdf.ui.result

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.TextSnippet
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage

private val ScreenBackground = Color(0xFF101114)
private val PanelBackground = Color(0xFF1B1C20)
private val Accent = Color(0xFF6C8CFF)
private val SuccessColor = Color(0xFF16C89A)
private val WarningColor = Color(0xFFF6C85F)
private val ErrorColor = Color(0xFFFF6B6B)

/**
 * Production-ready, CamScanner-style unified document result screen. Renders one
 * [DocumentResultState] across header, image preview, OCR workspace, processing status,
 * and an action row. All backend values shown come straight from state — the screen
 * never fabricates image URLs or OCR text and treats PDF export as a placeholder.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentResultScreen(
    state: DocumentResultState,
    onBack: () -> Unit,
    onSaveOcrText: (String) -> Unit,
    onCopyTextConfirmed: () -> Unit,
    onShareText: (String) -> Unit,
    onExportTxt: (String) -> Unit,
    onExportDoc: (String) -> Unit,
    onPdfPlaceholder: () -> Unit,
    onRetry: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    // Reseed the editor whenever the backend OCR text changes (e.g. after a retry),
    // while preserving in-progress edits between backend updates.
    var editableText by remember(state.ocrText) { mutableStateOf(state.ocrText.orEmpty()) }
    val hasEdits = editableText != state.ocrText.orEmpty()
    val actions = state.availableActions()

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
                        text = "Document Result",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF202124),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = ScreenBackground
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ResultHeaderSection(state)
            ResultImageSection(state)
            ResultOcrSection(
                state = state,
                editableText = editableText,
                hasEdits = hasEdits,
                onTextChange = { editableText = it },
                onSave = { onSaveOcrText(editableText) },
                onRetry = onRetry
            )
            ResultProcessingSection(state, onRetry = onRetry)
            ResultActionsRow(
                actions = actions,
                onCopy = {
                    clipboardManager.setText(AnnotatedString(editableText))
                    onCopyTextConfirmed()
                },
                onShare = { onShareText(editableText) },
                onExportTxt = { onExportTxt(editableText) },
                onExportDoc = { onExportDoc(editableText) },
                onPdf = onPdfPlaceholder
            )
        }
    }
}

@Composable
private fun ResultHeaderSection(state: DocumentResultState) {
    val (statusColor, statusLabel) = when (state.loadingState) {
        ResultLoadingState.READY -> SuccessColor to "Ready"
        ResultLoadingState.ERROR -> ErrorColor to "Error"
        ResultLoadingState.LOADING -> Accent to "Processing"
        ResultLoadingState.IDLE -> WarningColor to "Idle"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = PanelBackground
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Document & Page",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = statusColor.copy(alpha = 0.18f)
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            ResultIdLine(label = "Document ID", value = state.documentId)
            ResultIdLine(label = "Page ID", value = state.pageId)
            ResultIdLine(label = "Process Job ID", value = state.processJobId)
            state.processingStatus?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFB8BDC4),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ResultIdLine(label: String, value: String?) {
    Text(
        text = "$label: ${value ?: "—"}",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFFB8BDC4),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun ResultImageSection(state: DocumentResultState) {
    val model = state.preferredImageModel
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = PanelBackground
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Preview",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            if (model.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f / 1.2f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No image available",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF8A8E96)
                    )
                }
            } else {
                AsyncImage(
                    model = model,
                    contentDescription = "Document result preview",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f / 1.2f)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black)
                )
                Text(
                    text = imageSourceLabel(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8A8E96)
                )
            }
        }
    }
}

private fun imageSourceLabel(state: DocumentResultState): String = when {
    !state.processedImageUrl.isNullOrBlank() -> "Source: processed (backend)"
    !state.enhancedImageUrl.isNullOrBlank() -> "Source: enhanced (backend)"
    !state.croppedImageUrl.isNullOrBlank() -> "Source: cropped (backend)"
    !state.originalImageUrl.isNullOrBlank() -> "Source: original (backend)"
    !state.localPreviewUri.isNullOrBlank() -> "Source: local preview"
    else -> ""
}

@Composable
private fun ResultOcrSection(
    state: DocumentResultState,
    editableText: String,
    hasEdits: Boolean,
    onTextChange: (String) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = PanelBackground
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "OCR Workspace",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )

            when (state.ocrStatus) {
                ResultOcrStatus.LOADING, ResultOcrStatus.PENDING -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Accent,
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = if (state.ocrStatus == ResultOcrStatus.LOADING) {
                                "Fetching OCR text…"
                            } else {
                                "OCR not started yet."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFB8BDC4)
                        )
                    }
                }
                ResultOcrStatus.FAILED -> {
                    Text(
                        text = state.errorMessage ?: "OCR fetch failed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = ErrorColor
                    )
                    OutlinedButton(
                        onClick = onRetry,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = ErrorColor)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Retry OCR")
                    }
                }
                ResultOcrStatus.EMPTY -> {
                    Text(
                        text = "Backend OCR returned no text for this page. You can type or paste text below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = WarningColor
                    )
                }
                ResultOcrStatus.AVAILABLE -> {
                    Text(
                        text = "OCR text is available and editable.",
                        style = MaterialTheme.typography.bodySmall,
                        color = SuccessColor
                    )
                }
            }

            // Editable text is always available so an EMPTY result can still be filled in.
            val editingEnabled = state.ocrStatus != ResultOcrStatus.LOADING &&
                state.ocrStatus != ResultOcrStatus.PENDING
            OutlinedTextField(
                value = editableText,
                onValueChange = onTextChange,
                enabled = editingEnabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 260.dp),
                placeholder = { Text("No text recognized yet. Type or paste OCR text here.") },
                textStyle = MaterialTheme.typography.bodyMedium,
                minLines = 5
            )

            Button(
                onClick = onSave,
                enabled = editingEnabled && hasEdits,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    disabledContainerColor = Color(0xFF4B4D53)
                )
            ) {
                Text("Save OCR text")
            }
        }
    }
}

@Composable
private fun ResultProcessingSection(
    state: DocumentResultState,
    onRetry: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = PanelBackground
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Processing Status",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = state.processingStatus ?: state.loadingState.name,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFD6D9DE)
            )
            if (state.loadingState == ResultLoadingState.LOADING) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Accent,
                    strokeWidth = 2.dp
                )
            }
            if (state.isError) {
                state.errorMessage?.let {
                    Text(
                        text = "Failure: $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = ErrorColor,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedButton(
                    onClick = onRetry,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Accent)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retry processing")
                }
            }
        }
    }
}

@Composable
private fun ResultActionsRow(
    actions: DocumentResultActions,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onExportTxt: () -> Unit,
    onExportDoc: () -> Unit,
    onPdf: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = PanelBackground
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "Actions",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ResultActionButton(
                    label = "Copy",
                    icon = Icons.Default.ContentCopy,
                    enabled = actions.canCopyText,
                    onClick = onCopy,
                    modifier = Modifier.weight(1f)
                )
                ResultActionButton(
                    label = "Share",
                    icon = Icons.Default.Share,
                    enabled = actions.canShareText,
                    onClick = onShare,
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ResultActionButton(
                    label = "TXT",
                    icon = Icons.AutoMirrored.Filled.TextSnippet,
                    enabled = actions.canExportTxt,
                    onClick = onExportTxt,
                    modifier = Modifier.weight(1f)
                )
                ResultActionButton(
                    label = "DOC",
                    icon = Icons.Default.Description,
                    enabled = actions.canExportDoc,
                    onClick = onExportDoc,
                    modifier = Modifier.weight(1f)
                )
                ResultActionButton(
                    label = "PDF",
                    icon = Icons.Default.PictureAsPdf,
                    enabled = actions.isPdfEnabled,
                    onClick = onPdf,
                    modifier = Modifier.weight(1f)
                )
            }
            if (!actions.isPdfEnabled) {
                Text(
                    text = "PDF, TXT and DOC export are placeholders in this build.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF8A8E96)
                )
            }
        }
    }
}

@Composable
private fun ResultActionButton(
    label: String,
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
        colors = ButtonDefaults.outlinedButtonColors(
            contentColor = Color.White,
            disabledContentColor = Color(0xFF6A6D74)
        )
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}
