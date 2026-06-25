package com.dev.docscannerpdf.ui.debug

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.dev.docscannerpdf.network.ProcessJobStatus
import com.dev.docscannerpdf.process.ProcessDocumentUiState
import com.dev.docscannerpdf.process.ProcessedImageResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApiHealthScreen(
    onBack: () -> Unit,
    viewModel: ApiHealthViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            viewModel.processImage(context, uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "API Status") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        enabled = !state.isLoading,
                        onClick = viewModel::refresh
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "Retry")
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                BackendStatusCard(
                    state = state,
                    onRetry = viewModel::refresh
                )
            }
            item {
                ProcessDocumentCard(
                    processState = state.processState,
                    onPickImage = { imagePicker.launch("image/*") }
                )
            }
            item {
                DetailCard(title = "Health") {
                    val health = state.health
                    if (health == null) {
                        EmptyValue(text = if (state.isLoading) "Loading health..." else "No health response.")
                    } else {
                        DetailLine("Status", health.status)
                        DetailLine("Service", health.service ?: "Not provided")
                        DetailLine("Version", health.version ?: "Not provided")
                    }
                }
            }
            item {
                DetailCard(title = "Capabilities") {
                    val capabilities = state.capabilities
                    if (capabilities == null) {
                        EmptyValue(text = if (state.isLoading) "Loading capabilities..." else "No capabilities response.")
                    } else {
                        DetailLine("Capabilities", capabilities.capabilities.joinToString().ifBlank { "None reported" })
                        DetailLine("Formats", capabilities.formats.joinToString().ifBlank { "None reported" })
                        DetailLine("Max pages", capabilities.maxPages?.toString() ?: "Not provided")
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessDocumentCard(
    processState: ProcessDocumentUiState,
    onPickImage: () -> Unit
) {
    DetailCard(title = "Process Captured Image") {
        when (processState) {
            ProcessDocumentUiState.Idle -> {
                EmptyValue(text = "No backend process request has been started.")
            }
            is ProcessDocumentUiState.Uploading -> {
                DetailLine("State", "Uploading")
                DetailLine(
                    "Progress",
                    processState.progressFraction?.let { "${(it * 100).toInt()}%" } ?: "Starting"
                )
            }
            ProcessDocumentUiState.CreatingPage -> DetailLine("State", "Creating page")
            ProcessDocumentUiState.Processing -> DetailLine("State", "Processing")
            is ProcessDocumentUiState.Polling -> {
                DetailLine("State", "Polling (${processState.attempt}/${processState.maxAttempts})")
                ProcessJobStatusLines(processState.latestStatus)
            }
            is ProcessDocumentUiState.ProcessingCompletedNoImageUrl -> {
                DetailLine("State", "Processing completed")
                DetailLine("Image result", processState.reason)
            }
            is ProcessDocumentUiState.Success -> {
                DetailLine("State", "Backend accepted request")
                DetailLine("Document ID", processState.result.documentId)
                DetailLine("Page ID", processState.result.pageId)
                DetailLine("Process Job ID", processState.result.processJobId)
                DetailLine("Processing started", processState.result.processingStartedAt)
                processState.result.latestJobStatus?.let { latestStatus ->
                    ProcessJobStatusLines(latestStatus)
                }
                ProcessedImageResultView(processState.result.imageResult)
            }
            is ProcessDocumentUiState.Error -> {
                DetailLine("State", "Error")
                DetailLine("Error message", processState.message)
            }
            is ProcessDocumentUiState.Timeout -> {
                DetailLine("State", "Timeout")
                DetailLine("Process Job ID", processState.processJobId)
                DetailLine("Error message", processState.message)
                processState.latestStatus?.let { latestStatus ->
                    ProcessJobStatusLines(latestStatus)
                }
            }
        }
        Button(onClick = onPickImage) {
            Text(text = "Pick image")
        }
    }
}

@Composable
private fun ProcessJobStatusLines(status: ProcessJobStatus) {
    DetailLine("Latest job status", status.status)
    DetailLine("Completed stages", status.completedStages.joinToString().ifBlank { "None" })
    DetailLine(
        "Failed stages",
        status.failedStages.joinToString { failure ->
            listOfNotNull(failure.stage, failure.errorMessage).joinToString(": ")
        }.ifBlank { "None" }
    )
    DetailLine("Fallback stages", status.fallbackStages.joinToString().ifBlank { "None" })
    DetailLine("Final image role", status.finalImageRole ?: "Not available")
    DetailLine("Searchable ready", status.searchableReady.toString())
    status.errorMessage?.takeIf { it.isNotBlank() }?.let { error ->
        DetailLine("Job error", error)
    }
}

@Composable
private fun ProcessedImageResultView(result: ProcessedImageResult) {
    when (result) {
        is ProcessedImageResult.SuccessWithImage -> {
            DetailLine("Image result", result.url)
            AsyncImage(
                model = result.url,
                contentDescription = "Processed image result",
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.72f)
            )
        }
        is ProcessedImageResult.SuccessWithoutImage -> {
            DetailLine("Image result", result.reason)
        }
        is ProcessedImageResult.Error -> {
            DetailLine("Image result", result.message)
        }
    }
}

@Composable
private fun BackendStatusCard(
    state: ApiHealthUiState,
    onRetry: () -> Unit
) {
    val statusColor = when {
        state.isConnected -> Color(0xFF49D9A8)
        state.isLoading -> Color(0xFFFFB74D)
        else -> Color(0xFFFF8A80)
    }
    val cardColor = when {
        state.isConnected -> Color(0xFF163A2E)
        state.isLoading -> Color(0xFF3A3018)
        else -> Color(0xFF3A1D1D)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = cardColor
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Backend Status",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE8EAED)
                    )
                    Text(
                        text = state.connectionStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = statusColor
                    )
                }
                if (state.isLoading) {
                    CircularProgressIndicator(color = statusColor)
                }
            }
            DetailLine("Base URL", state.baseUrl)
            DetailLine("Response time", state.responseTimeMs?.let { "$it ms" } ?: "Pending")
            DetailLine("Connection status", state.connectionStatus)
            if (!state.errorMessage.isNullOrBlank()) {
                DetailLine("Error message", state.errorMessage)
            }
            Button(
                enabled = !state.isLoading,
                onClick = onRetry
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = if (state.isLoading) "Checking" else "Retry"
                )
            }
        }
    }
}

@Composable
private fun DetailCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1F2024)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE8EAED)
            )
            content()
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            modifier = Modifier.weight(0.42f),
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            modifier = Modifier.weight(0.58f),
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFE8EAED),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EmptyValue(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
