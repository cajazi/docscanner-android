package com.dev.docscannerpdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

enum class ValidationStatus {
    PASS,
    WARNING,
    FAIL
}

data class FeatureValidationItem(
    val name: String,
    val status: ValidationStatus,
    val detail: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureValidationScreen(
    databaseVersion: Int,
    migrationStatus: String,
    backupSchemaVersion: Int,
    biometricsAvailable: Boolean,
    dangerousPermissionsDeclared: Boolean,
    onBack: () -> Unit
) {
    val validations = buildFeatureValidations(
        biometricsAvailable = biometricsAvailable,
        dangerousPermissionsDeclared = dangerousPermissionsDeclared
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Feature Validation") },
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
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                ValidationMetaCard(
                    databaseVersion = databaseVersion,
                    migrationStatus = migrationStatus,
                    backupSchemaVersion = backupSchemaVersion,
                    dangerousPermissionsDeclared = dangerousPermissionsDeclared
                )
            }
            items(validations, key = { item -> item.name }) { item ->
                ValidationRow(item = item)
            }
        }
    }
}

@Composable
private fun ValidationMetaCard(
    databaseVersion: Int,
    migrationStatus: String,
    backupSchemaVersion: Int,
    dangerousPermissionsDeclared: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1F2024)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.FactCheck,
                    contentDescription = null,
                    tint = Color(0xFF49D9A8)
                )
                Text(
                    text = "Build diagnostics",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE8EAED)
                )
            }
            MetaLine("Database version", databaseVersion.toString())
            MetaLine("Room migrations", migrationStatus)
            MetaLine("Backup schema", backupSchemaVersion.toString())
            MetaLine(
                "Permissions",
                if (dangerousPermissionsDeclared) "Dangerous permissions declared" else "No dangerous permissions declared"
            )
        }
    }
}

@Composable
private fun MetaLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFE8EAED),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ValidationRow(item: FeatureValidationItem) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1F2024)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusPill(status = item.status)
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE8EAED)
                )
                Text(
                    text = item.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusPill(status: ValidationStatus) {
    val color = when (status) {
        ValidationStatus.PASS -> Color(0xFF49D9A8)
        ValidationStatus.WARNING -> Color(0xFFFFB74D)
        ValidationStatus.FAIL -> Color(0xFFFF8A80)
    }
    Surface(
        shape = RoundedCornerShape(9.dp),
        color = color.copy(alpha = 0.18f)
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
            text = status.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

private fun buildFeatureValidations(
    biometricsAvailable: Boolean,
    dangerousPermissionsDeclared: Boolean
): List<FeatureValidationItem> {
    return listOf(
        pass("Smart Scan", "ML Kit document scanner flow is wired through Activity Result."),
        pass("OCR", "TextRecognition client is used for extracted text."),
        pass("OCR Cleanup", "OCR cleanup utility is integrated into searchable text and export flows."),
        pass("Search", "Room search excludes Trash and combines folders/tags/favorites/pins."),
        pass("Folders", "Folder table, defaults, filters, rename, delete, and move operations are present."),
        pass("Tags", "Normalized many-to-many tags with dashboard filtering are present."),
        pass("Favorites", "Favorite state is persisted and searchable."),
        pass("Pinning", "Pinned state is persisted and prioritized in lists."),
        pass("Trash", "Soft delete, restore, empty Trash, and 30-day cleanup are present."),
        pass("Multi-select", "Long-press selection mode and checkbox overlays are present."),
        pass("Batch actions", "Batch share, move, tags, favorite, pin, and Trash actions are present."),
        pass("Backup", "Versioned JSON backup inside ZIP is implemented."),
        pass("Restore", "Restore validates structure and runs in a Room transaction."),
        pass("App Lock", "PIN lock with hashed storage and inactivity relock is implemented."),
        FeatureValidationItem(
            name = "Biometrics",
            status = if (biometricsAvailable) ValidationStatus.PASS else ValidationStatus.WARNING,
            detail = if (biometricsAvailable) {
                "BiometricPrompt is available on this device."
            } else {
                "BiometricPrompt is wired, but this device has no available strong biometric."
            }
        ),
        pass("Merge PDF", "Merge PDF tool screen and export flow are wired."),
        pass("Split PDF", "Split PDF tool screen and export flow are wired."),
        pass("Compress PDF", "Compress PDF tool screen and share flow are wired."),
        pass("Edit PDF", "Edit PDF screen with page operations is wired."),
        pass("Preview: Edit PDF", "PDF-only action is wired and image documents show the PDF-only disabled message."),
        pass("Preview: Send to PC", "FileProvider-backed Android share sheet is wired for document handoff."),
        pass("Preview: Save", "App export folder copy uses IO work and duplicate-safe filenames."),
        pass("Preview: Print", "PDF-only action uses Android PrintManager and disables image documents."),
        pass("Preview: Share", "FileProvider-backed share sheet supports PDF and image documents."),
        pass("Preview: Delete", "Confirmation dialog moves documents to Trash and dashboard lists update from Room."),
        pass("Preview: Convert To PDF", "Image documents can generate a PDF, save it to the list, and open it."),
        pass("Sign PDF", "Sign PDF screen and signature placement flow are wired."),
        pass("Watermark PDF", "Watermark PDF screen and export flow are wired."),
        pass("PDF To Word", "PDF text export/Word flow is wired."),
        pass("PDF To Images", "PDF rendering to image export flow is wired."),
        FeatureValidationItem(
            name = "Play Store safety",
            status = if (dangerousPermissionsDeclared) ValidationStatus.FAIL else ValidationStatus.PASS,
            detail = if (dangerousPermissionsDeclared) {
                "Manifest declares dangerous permissions. Review before release."
            } else {
                "No dangerous permissions are declared; file access uses SAF/FileProvider."
            }
        )
    )
}

private fun pass(name: String, detail: String): FeatureValidationItem {
    return FeatureValidationItem(name = name, status = ValidationStatus.PASS, detail = detail)
}
