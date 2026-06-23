package com.dev.docscannerpdf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dev.docscannerpdf.data.local.DocumentEntity

@Composable
fun ExtractTextDialog(
    documents: List<DocumentEntity>,
    initialDocument: DocumentEntity?,
    title: String = "Extract Text",
    onDismiss: () -> Unit,
    onShareText: (DocumentEntity) -> Unit,
    onShareCleanedText: (String, String) -> Unit,
    onExportCleanedText: (String, String, String) -> Unit,
    onValidationError: (String) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var selectedTab by remember { mutableStateOf(TextTab.Original) }
    var selectedDocument by remember(documents, initialDocument) {
        mutableStateOf(initialDocument ?: documents.firstOrNull())
    }
    val text = selectedDocument?.extractedText.orEmpty()
    val cleanedText = remember(text) { cleanOcrText(text) }
    val visibleText = if (selectedTab == TextTab.Original) text else cleanedText
    val hasText = text.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (documents.isEmpty()) {
                    Text(text = "No saved documents are available.")
                } else if (initialDocument == null) {
                    Text(
                        text = "Select document",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 160.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(documents, key = { it.id }) { document ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { selectedDocument = document }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedDocument?.id == document.id,
                                    onClick = { selectedDocument = document }
                                )
                                Text(
                                    modifier = Modifier.weight(1f),
                                    text = document.title,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (selectedDocument?.id == document.id) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null
                                    )
                                }
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedTab == TextTab.Original,
                        onClick = { selectedTab = TextTab.Original },
                        label = { Text(text = "Original") }
                    )
                    FilterChip(
                        selected = selectedTab == TextTab.Cleaned,
                        onClick = { selectedTab = TextTab.Cleaned },
                        label = { Text(text = "Cleaned") }
                    )
                    TextButton(
                        enabled = documents.isNotEmpty(),
                        onClick = {
                            if (cleanedText.isBlank()) {
                                onValidationError("No OCR text available to clean.")
                            } else {
                                selectedTab = TextTab.Cleaned
                            }
                        }
                    ) {
                        Text(text = "Clean OCR Text")
                    }
                }

                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 260.dp),
                    text = if (hasText) {
                        visibleText
                    } else {
                        "No text extracted yet. Re-scan or import a clearer document."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = hasText,
                        onClick = {
                            if (cleanedText.isBlank()) {
                                onValidationError("No OCR text available to clean.")
                            } else {
                                clipboardManager.setText(AnnotatedString(cleanedText))
                                onValidationError("Cleaned text copied.")
                            }
                        }
                    ) {
                        Text(text = "Copy Cleaned Text")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = hasText,
                        onClick = {
                            val document = selectedDocument
                            if (document == null || cleanedText.isBlank()) {
                                onValidationError("No OCR text available to clean.")
                            } else {
                                onShareCleanedText(document.title, cleanedText)
                            }
                        }
                    ) {
                        Text(text = "Share Cleaned Text")
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    TextButton(
                        enabled = hasText,
                        onClick = {
                            val document = selectedDocument
                            if (document == null || cleanedText.isBlank()) {
                                onValidationError("No OCR text available to clean.")
                            } else {
                                onExportCleanedText(document.title, cleanedText, "txt")
                            }
                        }
                    ) {
                        Text(text = "Export TXT")
                    }
                    TextButton(
                        enabled = hasText,
                        onClick = {
                            val document = selectedDocument
                            if (document == null || cleanedText.isBlank()) {
                                onValidationError("No OCR text available to clean.")
                            } else {
                                onExportCleanedText(document.title, cleanedText, "doc")
                            }
                        }
                    ) {
                        Text(text = "Export DOC")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = documents.isNotEmpty(),
                onClick = {
                    val document = selectedDocument
                    if (document == null || document.extractedText.isNullOrBlank()) {
                        onValidationError("No OCR text is available for this document.")
                    } else {
                        clipboardManager.setText(AnnotatedString(document.extractedText))
                        onValidationError("Text copied.")
                    }
                }
            ) {
                Text(text = "Copy text")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = documents.isNotEmpty(),
                    onClick = {
                        val document = selectedDocument
                        if (document == null || document.extractedText.isNullOrBlank()) {
                            onValidationError("No OCR text is available for this document.")
                        } else {
                            onShareText(document)
                        }
                    }
                ) {
                    Text(text = "Share text")
                }
                TextButton(onClick = onDismiss) {
                    Text(text = "Close")
                }
            }
        }
    )
}

private enum class TextTab {
    Original,
    Cleaned
}
