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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun LockPdfScreen(
    state: PdfPasswordToolState,
    onBack: () -> Unit,
    onPickPdf: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onLockPdf: () -> Unit
) {
    PdfPasswordToolScreen(
        title = "Lock PDF",
        icon = Icons.Default.Lock,
        actionLabel = if (state.isWorking) "Working" else "Lock PDF",
        state = state,
        onBack = onBack,
        onPickPdf = onPickPdf,
        onPasswordChange = onPasswordChange,
        onAction = onLockPdf
    )
}

@Composable
fun UnlockPdfScreen(
    state: PdfPasswordToolState,
    onBack: () -> Unit,
    onPickPdf: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onUnlockPdf: () -> Unit
) {
    PdfPasswordToolScreen(
        title = "Unlock PDF",
        icon = Icons.Default.LockOpen,
        actionLabel = "Unlock PDF",
        state = state,
        onBack = onBack,
        onPickPdf = onPickPdf,
        onPasswordChange = onPasswordChange,
        onAction = onUnlockPdf
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PdfPasswordToolScreen(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    actionLabel: String,
    state: PdfPasswordToolState,
    onBack: () -> Unit,
    onPickPdf: () -> Unit,
    onPasswordChange: (String) -> Unit,
    onAction: () -> Unit
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
                title = { Text(text = title, fontWeight = FontWeight.Bold) },
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
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = state.selectedName ?: "No PDF selected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE8EAED),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = state.message ?: "Select a PDF and enter a password.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFB8BDC4)
                    )
                }
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onPickPdf,
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Text(text = "Select PDF")
            }

            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.password,
                onValueChange = onPasswordChange,
                singleLine = true,
                label = { Text(text = "Password") },
                visualTransformation = PasswordVisualTransformation()
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = state.selectedUri != null && !state.isWorking,
                onClick = onAction,
                contentPadding = PaddingValues(vertical = 14.dp)
            ) {
                Icon(icon, contentDescription = null)
                Text(
                    modifier = Modifier.padding(start = 8.dp),
                    text = actionLabel
                )
            }
        }
    }
}

data class PdfPasswordToolState(
    val selectedUri: String? = null,
    val selectedName: String? = null,
    val password: String = "",
    val isWorking: Boolean = false,
    val message: String? = null
)
