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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dev.docscannerpdf.domain.cloud.CloudSyncState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CloudSyncScreen(
    state: CloudSyncState,
    isPremium: Boolean,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onPremium: () -> Unit,
    onSyncEnabledChange: (Boolean) -> Unit,
    onSyncNow: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Cloud Sync") },
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
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = Color(0xFF1F2024)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.CloudSync, contentDescription = null, tint = Color(0xFF49D9A8))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (state.syncEnabled) "Sync enabled" else "Sync disabled",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFE8EAED)
                                )
                                Text(
                                    text = "Metadata only. PDFs are never uploaded automatically.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = state.syncEnabled,
                                enabled = isPremium && state.isSignedIn,
                                onCheckedChange = onSyncEnabledChange
                            )
                        }
                        if (state.isSyncing) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        StatusLine("Account", state.accountEmail ?: "Signed out")
                        StatusLine("Last sync", state.lastSyncAt?.let(::formatSyncTime) ?: "Never")
                        StatusLine("Queue", "${state.queuedPayloads} encrypted payloads")
                        StatusLine("Status", state.statusMessage)
                    }
                }
            }
            if (!isPremium) {
                item {
                    PremiumRequiredCard(onPremium = onPremium)
                }
            }
            item {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF1F2024)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "Google account",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE8EAED)
                        )
                        if (state.isSignedIn) {
                            OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = onSignOut) {
                                Text(text = "Sign out")
                            }
                        } else {
                            Button(modifier = Modifier.fillMaxWidth(), enabled = isPremium, onClick = onSignIn) {
                                Text(text = "Sign in with Google")
                            }
                        }
                    }
                }
            }
            item {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = isPremium && state.isSignedIn && state.syncEnabled && !state.isSyncing,
                    onClick = onSyncNow
                ) {
                    Text(text = "Sync now")
                }
            }
            item {
                SecurityCard()
            }
        }
    }
}

@Composable
private fun PremiumRequiredCard(onPremium: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1F2024)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.WorkspacePremium, contentDescription = null, tint = Color(0xFFFFC857))
            Column(modifier = Modifier.weight(1f)) {
                Text("Premium required", fontWeight = FontWeight.Bold, color = Color(0xFFE8EAED))
                Text("Cloud Sync is available for Premium users.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onPremium) { Text("Upgrade") }
        }
    }
}

@Composable
private fun SecurityCard() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1F2024)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Lock, contentDescription = null, tint = Color(0xFF49D9A8))
            Column {
                Text("Encrypted metadata", fontWeight = FontWeight.Bold, color = Color(0xFFE8EAED))
                Text(
                    "Sync payloads are encrypted locally with Android Keystore. No passwords are stored.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, color = Color(0xFFE8EAED), fontWeight = FontWeight.SemiBold)
    }
}

private fun formatSyncTime(timestamp: Long): String {
    return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
}
