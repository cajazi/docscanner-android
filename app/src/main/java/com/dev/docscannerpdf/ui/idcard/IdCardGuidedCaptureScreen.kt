package com.dev.docscannerpdf.ui.idcard

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.dev.docscannerpdf.domain.idscan.IdCardCaptureFlow
import com.dev.docscannerpdf.domain.idscan.IdCardCaptureStage
import com.dev.docscannerpdf.domain.idscan.IdCardCaptureState
import com.dev.docscannerpdf.domain.pdf.IdCardLayoutPlanner
import java.io.File

/**
 * CamScanner-style guided ID-card capture: a dark full-screen camera preview with an ID-card
 * shaped guide frame, captures the front first then the back, and hands both image [Uri]s back
 * through [onCaptureComplete]. Images are written only to [outputDirectory] (the app's own
 * private storage) — never external storage. Normal document scanning is untouched; this screen
 * is only reached from the ID-card "Make it now" action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdCardGuidedCaptureScreen(
    outputDirectory: File,
    onBack: () -> Unit,
    onCaptureComplete: (frontUri: Uri, backUri: Uri?) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var captureState by remember { mutableStateOf(IdCardCaptureState()) }

    LaunchedEffect(captureState.stage) {
        if (captureState.stage == IdCardCaptureStage.COMPLETE) {
            val front = captureState.frontImageUri?.let(Uri::parse) ?: return@LaunchedEffect
            onCaptureComplete(front, captureState.backImageUri?.let(Uri::parse))
        }
    }

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

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            captureState = IdCardCaptureFlow.onSideCaptured(captureState, uri.toString())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
    ) {
        if (!hasPermission) {
            IdCardCameraPermissionPrompt(
                onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onBack = onBack
            )
        } else {
            val controller = remember(lifecycleOwner) {
                IdCardCameraController(context = context, lifecycleOwner = lifecycleOwner)
            }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx -> PreviewView(ctx).also { previewView -> controller.bind(previewView) } }
            )
            DisposableEffect(controller) {
                onDispose { controller.unbind() }
            }

            IdCardGuideOverlay(stage = captureState.stage)

            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
            }

            IdCardCaptureControls(
                stage = captureState.stage,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 28.dp),
                onImport = { importLauncher.launch("image/*") },
                onCapture = {
                    val prefix = if (captureState.stage == IdCardCaptureStage.FRONT) "id_card_front" else "id_card_back"
                    controller.capture(outputDirectory = outputDirectory, filePrefix = prefix) { uri ->
                        if (uri != null) {
                            captureState = IdCardCaptureFlow.onSideCaptured(captureState, uri.toString())
                        }
                    }
                },
                onSkipBack = { captureState = IdCardCaptureFlow.skipBack(captureState) }
            )
        }
    }
}

/** ID-card-shaped guide frame with a "Front"/"Back" label, centered over the camera preview. */
@Composable
private fun IdCardGuideOverlay(stage: IdCardCaptureStage) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Color(0x99000000)
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                text = if (stage == IdCardCaptureStage.BACK) "Back" else "Front",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(IdCardLayoutPlanner.CARD_ASPECT_RATIO)
                .border(2.dp, Color.White, RoundedCornerShape(12.dp))
        )
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(12.dp))
        Text(
            text = if (stage == IdCardCaptureStage.BACK) {
                "Align the back of the card within the frame"
            } else {
                "Align the front of the card within the frame"
            },
            color = Color(0xFFD9DBE0),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun IdCardCaptureControls(
    stage: IdCardCaptureStage,
    modifier: Modifier = Modifier,
    onImport: () -> Unit,
    onCapture: () -> Unit,
    onSkipBack: () -> Unit
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (stage == IdCardCaptureStage.BACK) {
            TextButton(onClick = onSkipBack) {
                Text("Use front only", color = Color.White)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onImport, modifier = Modifier.padding(end = 32.dp)) {
                Icon(Icons.Default.Image, contentDescription = "Import from gallery", tint = Color.White)
            }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .border(3.dp, Color.White, CircleShape)
                    .padding(4.dp),
                contentAlignment = Alignment.Center
            ) {
                IconButton(
                    onClick = onCapture,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.White, CircleShape)
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "Capture", tint = Color.Black)
                }
            }
        }
    }
}

@Composable
private fun IdCardCameraPermissionPrompt(onGrant: () -> Unit, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "Camera access is needed to scan the ID card.",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = onGrant, colors = ButtonDefaults.buttonColors()) {
                Text("Grant camera access")
            }
            TextButton(onClick = onBack, contentPadding = PaddingValues(0.dp)) {
                Text("Cancel", color = Color(0xFFB8BDC4))
            }
        }
    }
}
