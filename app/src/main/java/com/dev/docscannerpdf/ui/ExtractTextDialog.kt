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
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
    onSaveText: (DocumentEntity, String) -> Unit = { _, _ -> },
    onValidationError: (String) -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    var selectedTab by remember { mutableStateOf(TextTab.Original) }
    var selectedDocument by remember(documents, initialDocument) {
        mutableStateOf(initialDocument ?: documents.firstOrNull())
    }
    val selectedDocumentId = selectedDocument?.id
    var editableText by remember(selectedDocumentId) {
        mutableStateOf(selectedDocument?.extractedText.orEmpty())
    }
    val cleanedText = remember(editableText) { cleanOcrText(editableText) }
    val visibleText = if (selectedTab == TextTab.Original) editableText else cleanedText
    val hasText = editableText.isNotBlank()
    val hasUnsavedChanges = editableText != selectedDocument?.extractedText.orEmpty()

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
                        .padding(top = 2.dp),
                    text = "OCR layer text is used for document search and future searchable PDF export.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp, max = 280.dp),
                    value = visibleText,
                    onValueChange = { value ->
                        if (selectedTab == TextTab.Original) {
                            editableText = value
                        } else {
                            editableText = value
                            selectedTab = TextTab.Original
                        }
                    },
                    label = {
                        Text(
                            text = if (selectedTab == TextTab.Original) {
                                "Recognized text"
                            } else {
                                "Cleaned text preview"
                            }
                        )
                    },
                    placeholder = {
                        Text(text = "No text recognized yet. Paste or type OCR text here.")
                    },
                    textStyle = MaterialTheme.typography.bodyMedium,
                    minLines = 6
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = selectedDocument != null && hasUnsavedChanges,
                        onClick = {
                            val document = selectedDocument
                            if (document == null) {
                                onValidationError("Select a document first.")
                            } else {
                                onSaveText(document, editableText)
                            }
                        }
                    ) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = null)
                        Text(modifier = Modifier.padding(start = 6.dp), text = "Save")
                    }
                    Button(
                        modifier = Modifier.weight(1f),
                        enabled = hasText,
                        onClick = {
                            val copyText = if (selectedTab == TextTab.Cleaned) cleanedText else editableText
                            if (copyText.isBlank()) {
                                onValidationError("No OCR text is available to copy.")
                            } else {
                                clipboardManager.setText(AnnotatedString(copyText))
                                onValidationError("Text copied.")
                            }
                        }
                    ) {
                        Icon(imageVector = Icons.Default.ContentCopy, contentDescription = null)
                        Text(modifier = Modifier.padding(start = 6.dp), text = "Copy")
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
                            val shareText = if (selectedTab == TextTab.Cleaned) cleanedText else editableText
                            if (document == null || shareText.isBlank()) {
                                onValidationError("No OCR text is available for this document.")
                            } else {
                                onShareCleanedText(document.title, shareText)
                            }
                        }
                    ) {
                        Icon(imageVector = Icons.Default.Share, contentDescription = null)
                        Text(modifier = Modifier.padding(start = 4.dp), text = "Share")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = hasText,
                onClick = {
                    val document = selectedDocument
                    if (document == null || editableText.isBlank()) {
                        onValidationError("No OCR text is available for this document.")
                    } else {
                        onExportCleanedText(document.title, editableText, "doc")
                    }
                }
            ) {
                Text(text = "Export DOC")
            }
        },
        dismissButton = {
            Row {
                TextButton(
                    enabled = documents.isNotEmpty(),
                    onClick = {
                        val document = selectedDocument
                        if (document == null || editableText.isBlank()) {
                            onValidationError("No OCR text is available for this document.")
                        } else {
                            onShareCleanedText(document.title, editableText)
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
