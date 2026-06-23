package com.dev.docscannerpdf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dev.docscannerpdf.data.local.DocumentEntity

private const val MAX_TITLE_LENGTH = 80

@Composable
fun RenameDocumentDialog(
    documents: List<DocumentEntity>,
    initialDocument: DocumentEntity?,
    onDismiss: () -> Unit,
    onRename: (DocumentEntity, String) -> Unit,
    onValidationError: (String) -> Unit
) {
    var selectedDocument by remember(documents, initialDocument) {
        mutableStateOf(initialDocument ?: documents.firstOrNull())
    }
    var title by remember(initialDocument) {
        mutableStateOf(initialDocument?.title.orEmpty())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Rename PDF") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (documents.isEmpty()) {
                    Text(
                        text = "No saved documents are available.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    Text(
                        text = "Select document",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(documents, key = { it.id }) { document ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedDocument = document
                                        title = document.title
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = selectedDocument?.id == document.id,
                                    onClick = {
                                        selectedDocument = document
                                        title = document.title
                                    }
                                )
                                Text(
                                    modifier = Modifier.weight(1f),
                                    text = document.title,
                                    style = MaterialTheme.typography.bodyMedium,
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

                    Spacer(modifier = Modifier.height(2.dp))
                    TextField(
                        value = title,
                        onValueChange = { title = it.take(MAX_TITLE_LENGTH) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text(text = "New title") },
                        supportingText = {
                            Text(text = "${title.length}/$MAX_TITLE_LENGTH")
                        }
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = documents.isNotEmpty(),
                onClick = {
                    val document = selectedDocument
                    val normalizedTitle = title.trim()
                    when {
                        document == null -> onValidationError("Select a document to rename.")
                        normalizedTitle.isBlank() -> onValidationError("Title cannot be blank.")
                        normalizedTitle.length > MAX_TITLE_LENGTH -> {
                            onValidationError("Title must be 80 characters or fewer.")
                        }
                        else -> {
                            onRename(document, normalizedTitle)
                            onDismiss()
                        }
                    }
                }
            ) {
                Text(text = "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel")
            }
        }
    )
}
