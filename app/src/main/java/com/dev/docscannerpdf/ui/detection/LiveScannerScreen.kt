package com.dev.docscannerpdf.ui.detection

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

/**
 * Full live-scanning screen: a CameraX preview with the real-time [LiveEdgeOverlay] drawn on top.
 * The overlay updates from the controller's [kotlinx.coroutines.flow.StateFlow], so detected quad,
 * confidence, and stability reflect the live stream. Camera analysis runs off the main thread in
 * [CameraPreviewController]; this screen only wires the preview, overlay, and permission.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiveScannerScreen(
    onBack: () -> Unit,
    onReadyToCapture: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text("Live Detection", fontWeight = FontWeight.Bold, color = Color.White)
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF151619),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        containerColor = Color.Black
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
        ) {
            if (!hasPermission) {
                CameraPermissionPrompt(
                    onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                )
            } else {
                val controller = remember(lifecycleOwner) {
                    CameraPreviewController(
                        context = context,
                        lifecycleOwner = lifecycleOwner,
                        onReadyToCapture = onReadyToCapture
                    )
                }
                val uiState by controller.uiState.collectAsState()

                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        PreviewView(ctx).also { previewView -> controller.bind(previewView) }
                    }
                )
                LiveEdgeOverlay(state = uiState, modifier = Modifier.fillMaxSize())
                DetectionHud(
                    confidence = uiState.confidence,
                    isStable = uiState.isStable,
                    readyToCapture = uiState.readyToCapture,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp)
                )

                DisposableEffect(controller) {
                    onDispose { controller.shutdown() }
                }
            }
        }
    }
}

@Composable
private fun CameraPermissionPrompt(onGrant: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "Camera access is needed for live document detection.",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onGrant) { Text("Grant camera access") }
        }
    }
}

@Composable
private fun DetectionHud(
    confidence: Float,
    isStable: Boolean,
    readyToCapture: Boolean,
    modifier: Modifier = Modifier
) {
    val label = when {
        readyToCapture -> "Ready to capture"
        isStable -> "Stable"
        confidence > 0f -> "Detecting…"
        else -> "Searching for document…"
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xCC1B1C20)
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            text = "$label   •   confidence ${(confidence * 100).roundToInt()}%",
            color = if (isStable) Color(0xFF16C89A) else Color(0xFFF6C85F),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold
        )
    }
}
