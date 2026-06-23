package com.dev.docscannerpdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.Slideshow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import com.dev.docscannerpdf.domain.analytics.ObservabilitySettings
import com.dev.docscannerpdf.domain.security.AppLockSettings

@Composable
fun AppLockScreen(
    pinLength: Int,
    biometricsAvailable: Boolean,
    biometricsEnabled: Boolean,
    errorMessage: String?,
    onPinComplete: (String) -> Unit,
    onBiometricClick: () -> Unit
) {
    var pin by remember { mutableStateOf("") }

    fun appendDigit(digit: String) {
        if (pin.length >= pinLength) return
        pin += digit
        if (pin.length == pinLength) {
            onPinComplete(pin)
            pin = ""
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            Surface(
                modifier = Modifier.size(76.dp),
                shape = CircleShape,
                color = Color(0xFF1F2024)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(34.dp),
                        tint = Color(0xFF49D9A8)
                    )
                }
            }
            Text(
                text = "App locked",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE8EAED)
            )
            PinDots(pinLength = pinLength, entered = pin.length)
            Text(
                text = errorMessage ?: "Enter your PIN to continue",
                style = MaterialTheme.typography.bodyMedium,
                color = if (errorMessage == null) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFFFF8A80),
                textAlign = TextAlign.Center
            )
            PinKeypad(
                onDigit = ::appendDigit,
                onBackspace = { pin = pin.dropLast(1) },
                biometricEnabled = biometricsAvailable && biometricsEnabled,
                onBiometricClick = onBiometricClick
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppLockSettingsScreen(
    settings: AppLockSettings,
    observabilitySettings: ObservabilitySettings,
    biometricsAvailable: Boolean,
    onBack: () -> Unit,
    onCreatePin: (String, Boolean) -> Unit,
    onChangePin: (String) -> Unit,
    onLockEnabledChange: (Boolean) -> Unit,
    onBiometricsEnabledChange: (Boolean) -> Unit,
    onDisableLock: () -> Unit,
    onAnalyticsEnabledChange: (Boolean) -> Unit,
    onCrashReportingEnabledChange: (Boolean) -> Unit,
    onViewOnboardingAgain: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    onOpenCloudSync: () -> Unit,
    onOpenFeatureValidation: () -> Unit
) {
    var showCreatePin by remember { mutableStateOf(false) }
    var showChangePin by remember { mutableStateOf(false) }
    var biometricSetupEnabled by remember { mutableStateOf(biometricsAvailable) }
    var developerTapCount by remember { mutableStateOf(0) }
    var developerUnlocked by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "App Lock") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SettingsRow(
                icon = Icons.Default.CloudSync,
                title = "Cloud Sync",
                subtitle = "Premium metadata sync with Google Sign-In.",
                onClick = onOpenCloudSync
            )
            SettingsRow(
                icon = Icons.Default.Backup,
                title = "Backup & Restore",
                subtitle = "Export or restore your documents, folders, and tags.",
                onClick = onOpenBackupRestore
            )
            SettingsSwitchRow(
                icon = Icons.Default.Insights,
                title = "Analytics",
                subtitle = "Send feature usage metadata without document content or OCR text.",
                checked = observabilitySettings.analyticsEnabled,
                onCheckedChange = onAnalyticsEnabledChange
            )
            SettingsSwitchRow(
                icon = Icons.Default.BugReport,
                title = "Crash reporting",
                subtitle = "Send crash and non-fatal failure reports without private document data.",
                checked = observabilitySettings.crashReportingEnabled,
                onCheckedChange = onCrashReportingEnabledChange
            )
            SettingsRow(
                icon = Icons.Default.Slideshow,
                title = "View Onboarding Again",
                subtitle = "Replay the first-run tour and reset the completed state.",
                onClick = onViewOnboardingAgain
            )
            if (developerUnlocked) {
                SettingsRow(
                    icon = Icons.Default.Backup,
                    title = "Feature Validation Center",
                    subtitle = "Run passive developer checks for app features and release safety.",
                    onClick = onOpenFeatureValidation
                )
            }
            LockSettingsHeader(
                settings = settings,
                onDeveloperTap = {
                    developerTapCount += 1
                    if (developerTapCount >= 5) {
                        developerUnlocked = true
                    }
                }
            )
            if (!settings.pinCreated) {
                SettingsRow(
                    icon = Icons.Default.Password,
                    title = "Create PIN",
                    subtitle = "Set a private PIN before enabling App Lock.",
                    onClick = { showCreatePin = true }
                )
                SettingsSwitchRow(
                    icon = Icons.Default.Fingerprint,
                    title = "Enable biometrics after setup",
                    subtitle = if (biometricsAvailable) "Use fingerprint or device biometrics." else "Biometrics are not available on this device.",
                    checked = biometricSetupEnabled && biometricsAvailable,
                    enabled = biometricsAvailable,
                    onCheckedChange = { biometricSetupEnabled = it }
                )
            } else {
                SettingsSwitchRow(
                    icon = Icons.Default.Lock,
                    title = "App Lock",
                    subtitle = "Require unlock on launch and after 5 minutes away.",
                    checked = settings.lockEnabled,
                    onCheckedChange = onLockEnabledChange
                )
                SettingsRow(
                    icon = Icons.Default.Password,
                    title = "Change PIN",
                    subtitle = "Replace your current PIN with a new one.",
                    onClick = { showChangePin = true }
                )
                SettingsSwitchRow(
                    icon = Icons.Default.Fingerprint,
                    title = "Biometric unlock",
                    subtitle = if (biometricsAvailable) "Use fingerprint or device biometrics." else "Biometrics are not available on this device.",
                    checked = settings.biometricsEnabled && biometricsAvailable,
                    enabled = settings.lockEnabled && biometricsAvailable,
                    onCheckedChange = onBiometricsEnabledChange
                )
                SettingsRow(
                    icon = Icons.Default.LockOpen,
                    title = "Disable and remove PIN",
                    subtitle = "Turn off App Lock and clear the saved PIN hash.",
                    onClick = onDisableLock,
                    tint = Color(0xFFFF8A80)
                )
            }
        }
    }

    if (showCreatePin) {
        PinSetupDialog(
            title = "Create PIN",
            onDismiss = { showCreatePin = false },
            onPinConfirmed = { pin ->
                showCreatePin = false
                onCreatePin(pin, biometricSetupEnabled && biometricsAvailable)
            }
        )
    }

    if (showChangePin) {
        PinSetupDialog(
            title = "Change PIN",
            onDismiss = { showChangePin = false },
            onPinConfirmed = { pin ->
                showChangePin = false
                onChangePin(pin)
            }
        )
    }
}

@Composable
private fun LockSettingsHeader(
    settings: AppLockSettings,
    onDeveloperTap: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onDeveloperTap),
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
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = Color(0xFF49D9A8)
                    )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (settings.lockEnabled) "Protected" else "Lock disabled",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE8EAED)
                )
                Text(
                    text = if (settings.pinCreated) "PIN is stored as a salted hash." else "Create a PIN to protect the app.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    tint: Color = Color(0xFF49D9A8)
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF1F2024)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(imageVector = icon, contentDescription = null, tint = tint)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFE8EAED))
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit
) {
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
            Icon(imageVector = icon, contentDescription = null, tint = Color(0xFF49D9A8))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = MaterialTheme.typography.bodyLarge, color = Color(0xFFE8EAED))
                Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(
                checked = checked,
                enabled = enabled,
                onCheckedChange = onCheckedChange
            )
        }
    }
}

@Composable
private fun PinSetupDialog(
    title: String,
    onDismiss: () -> Unit,
    onPinConfirmed: (String) -> Unit
) {
    var step by remember { mutableStateOf(PinSetupStep.Create) }
    var firstPin by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun appendDigit(digit: String) {
        if (pin.length >= APP_PIN_LENGTH) return
        pin += digit
        if (pin.length == APP_PIN_LENGTH) {
            if (step == PinSetupStep.Create) {
                firstPin = pin
                pin = ""
                step = PinSetupStep.Confirm
                error = null
            } else if (pin == firstPin) {
                onPinConfirmed(pin)
            } else {
                pin = ""
                step = PinSetupStep.Create
                firstPin = ""
                error = "PINs did not match. Try again."
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text(
                    text = if (step == PinSetupStep.Create) "Enter a new PIN" else "Confirm your PIN",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PinDots(pinLength = APP_PIN_LENGTH, entered = pin.length)
                error?.let {
                    Text(text = it, color = Color(0xFFFF8A80), style = MaterialTheme.typography.bodySmall)
                }
                PinKeypad(
                    onDigit = ::appendDigit,
                    onBackspace = { pin = pin.dropLast(1) },
                    biometricEnabled = false,
                    onBiometricClick = {}
                )
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel")
            }
        }
    )
}

@Composable
private fun PinDots(pinLength: Int, entered: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(pinLength) { index ->
            Surface(
                modifier = Modifier.size(14.dp),
                shape = CircleShape,
                color = if (index < entered) Color(0xFF49D9A8) else Color(0xFF34363B)
            ) {}
        }
    }
}

@Composable
private fun PinKeypad(
    onDigit: (String) -> Unit,
    onBackspace: () -> Unit,
    biometricEnabled: Boolean,
    onBiometricClick: () -> Unit
) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9")
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEach { digit ->
                    KeypadButton(label = digit, onClick = { onDigit(digit) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            KeypadIconButton(
                icon = Icons.Default.Fingerprint,
                enabled = biometricEnabled,
                onClick = onBiometricClick
            )
            KeypadButton(label = "0", onClick = { onDigit("0") })
            KeypadIconButton(
                icon = Icons.AutoMirrored.Filled.Backspace,
                enabled = true,
                onClick = onBackspace
            )
        }
    }
}

@Composable
private fun KeypadButton(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(70.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = Color(0xFF1F2024)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE8EAED)
            )
        }
    }
}

@Composable
private fun KeypadIconButton(
    icon: ImageVector,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(70.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        shape = CircleShape,
        color = if (enabled) Color(0xFF1F2024) else Color(0xFF17181B)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) Color(0xFF49D9A8) else Color(0xFF5F6368)
            )
        }
    }
}

private enum class PinSetupStep {
    Create,
    Confirm
}

const val APP_PIN_LENGTH = 4
