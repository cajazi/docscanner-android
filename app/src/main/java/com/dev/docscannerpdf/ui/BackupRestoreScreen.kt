package com.dev.docscannerpdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dev.docscannerpdf.domain.backup.BackupArchive
import com.dev.docscannerpdf.domain.backup.LastBackupInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(
    lastBackupInfo: LastBackupInfo?,
    isProcessing: Boolean,
    statusMessage: String?,
    pendingRestore: BackupArchive?,
    onBack: () -> Unit,
    onCreateBackup: () -> Unit,
    onRestoreBackup: () -> Unit,
    onConfirmRestore: () -> Unit,
    onDismissRestore: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Backup & Restore") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                BackupHeader(lastBackupInfo = lastBackupInfo)
            }
            item {
                BackupActionCard(
                    icon = Icons.Default.CloudUpload,
                    title = "Create Backup",
                    subtitle = "Export documents, folders, tags, favorites, and pinned state as a versioned ZIP backup.",
                    buttonLabel = "Create Backup",
                    enabled = !isProcessing,
                    onClick = onCreateBackup
                )
            }
            item {
                BackupActionCard(
                    icon = Icons.Default.Restore,
                    title = "Restore Backup",
                    subtitle = "Import a backup file, validate its structure, and review the summary before replacing local data.",
                    buttonLabel = "Restore Backup",
                    enabled = !isProcessing,
                    onClick = onRestoreBackup
                )
            }
            if (isProcessing || statusMessage != null) {
                item {
                    BackupStatusCard(
                        isProcessing = isProcessing,
                        statusMessage = statusMessage
                    )
                }
            }
        }
    }

    pendingRestore?.let { archive ->
        AlertDialog(
            onDismissRequest = onDismissRestore,
            title = { Text(text = "Restore backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(text = "Review this backup before restoring. Existing data is kept unless restore completes successfully.")
                    RestoreSummaryRow("Created", formatBackupTimestamp(archive.summary.createdAt))
                    RestoreSummaryRow("Documents", archive.summary.documentCount.toString())
                    RestoreSummaryRow("Folders", archive.summary.folderCount.toString())
                    RestoreSummaryRow("Tags", archive.summary.tagCount.toString())
                    RestoreSummaryRow("Favorites", archive.summary.favoriteCount.toString())
                    RestoreSummaryRow("Pinned", archive.summary.pinnedCount.toString())
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirmRestore) {
                    Text(text = "Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismissRestore) {
                    Text(text = "Cancel")
                }
            }
        )
    }
}

@Composable
private fun BackupHeader(lastBackupInfo: LastBackupInfo?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF1F2024)
    ) {
        Row(
            modifier = Modifier.padding(18.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(52.dp),
                shape = CircleShape,
                color = Color(0xFF243A31)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Backup,
                        contentDescription = null,
                        tint = Color(0xFF49D9A8)
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = "Local backups",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE8EAED)
                )
                Text(
                    text = lastBackupInfo?.let {
                        "Last backup ${formatBackupTimestamp(it.createdAt)} • ${it.documentCount} documents"
                    } ?: "No backup created yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun BackupActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    buttonLabel: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1F2024)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = Color(0xFF49D9A8))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE8EAED)
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = enabled,
                onClick = onClick
            ) {
                Text(text = buttonLabel)
            }
        }
    }
}

@Composable
private fun BackupStatusCard(
    isProcessing: Boolean,
    statusMessage: String?
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1F2024)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (isProcessing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = statusMessage ?: "Working in background",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFE8EAED)
            )
        }
    }
}

@Composable
private fun RestoreSummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = value, fontWeight = FontWeight.SemiBold)
    }
}

private fun formatBackupTimestamp(timestamp: Long): String {
    return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
}
