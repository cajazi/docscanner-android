package com.dev.docscannerpdf.ui.idcard

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.dev.docscannerpdf.domain.idscan.IdCardCaptureBaker
import com.dev.docscannerpdf.domain.idscan.IdCardCaptureContainerSize
import com.dev.docscannerpdf.domain.idscan.IdCardCaptureFlow
import com.dev.docscannerpdf.domain.idscan.IdCardCaptureStage
import com.dev.docscannerpdf.domain.idscan.IdCardCaptureState
import com.dev.docscannerpdf.domain.idscan.IdCardGuideFrameRect
import com.dev.docscannerpdf.domain.pdf.IdCardLayoutPlanner
import kotlinx.coroutines.launch
import java.io.File

private val GreenAccent = Color(0xFF16C89A)

/**
 * CamScanner-style guided ID-card capture: a dark full-screen camera preview behind black top/
 * bottom control bars, a corner-bracket ID-card guide frame, captures the front first then the
 * back, and hands both image [Uri]s back through [onCaptureComplete]. Each captured (or imported)
 * side is baked into a tight landscape card crop — see [IdCardCaptureBaker] — before it's stored,
 * so the review screen and exported PDF never show a raw portrait photo letterboxed into a
 * landscape slot. Images are written only to [outputDirectory] (the app's own private storage) —
 * never external storage. Normal document scanning is untouched; this screen is only reached from
 * the ID-card "Make it now" action.
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
    val scope = rememberCoroutineScope()

    DarkSystemBarsEffect()

    var captureState by remember { mutableStateOf(IdCardCaptureState()) }
    var isProcessing by remember { mutableStateOf(false) }
    var flashOn by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var containerBounds by remember { mutableStateOf<Rect?>(null) }
    var frameBounds by remember { mutableStateOf<Rect?>(null) }

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
            val prefix = if (captureState.stage == IdCardCaptureStage.BACK) "id_card_back_import" else "id_card_front_import"
            isProcessing = true
            scope.launch {
                val baked = IdCardCaptureBaker.bakeFromImport(
                    context = context,
                    sourceUri = uri,
                    outputDirectory = outputDirectory,
                    filePrefix = prefix
                ) ?: uri
                captureState = IdCardCaptureFlow.onSideCaptured(captureState, baked.toString())
                isProcessing = false
            }
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
            LaunchedEffect(flashOn, controller) {
                controller.setFlashMode(if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
            }
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { containerBounds = it.boundsInRoot() },
                factory = { ctx -> PreviewView(ctx).also { previewView -> controller.bind(previewView) } }
            )
            DisposableEffect(controller) {
                onDispose { controller.unbind() }
            }

            IdCardGuideOverlay(
                stage = captureState.stage,
                onFrameBoundsChanged = { frameBounds = it }
            )

            if (isProcessing) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }

            IdCardCaptureTopBar(
                flashOn = flashOn,
                onFlashToggle = { flashOn = !flashOn },
                showOverflowMenu = showOverflowMenu,
                onOverflowClick = { showOverflowMenu = true },
                onOverflowDismiss = { showOverflowMenu = false },
                onClose = onBack,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            IdCardCaptureControls(
                stage = captureState.stage,
                enabled = !isProcessing,
                modifier = Modifier.align(Alignment.BottomCenter),
                onImport = { importLauncher.launch("image/*") },
                onCapture = onCapture@{
                    if (isProcessing) return@onCapture
                    isProcessing = true
                    val prefix = if (captureState.stage == IdCardCaptureStage.FRONT) "id_card_front" else "id_card_back"
                    val frameRect = frameBounds?.let { fb ->
                        containerBounds?.let { cb ->
                            IdCardGuideFrameRect(
                                left = fb.left - cb.left,
                                top = fb.top - cb.top,
                                width = fb.width,
                                height = fb.height
                            )
                        }
                    }
                    val containerSize = containerBounds?.let { IdCardCaptureContainerSize(it.width, it.height) }
                    controller.capture(outputDirectory = outputDirectory, filePrefix = "$prefix-raw") onCaptured@{ uri ->
                        if (uri == null) {
                            isProcessing = false
                            return@onCaptured
                        }
                        scope.launch {
                            val baked = IdCardCaptureBaker.bakeFromCameraCapture(
                                context = context,
                                sourceUri = uri,
                                frameRect = frameRect,
                                containerSize = containerSize,
                                outputDirectory = outputDirectory,
                                filePrefix = prefix
                            ) ?: uri
                            captureState = IdCardCaptureFlow.onSideCaptured(captureState, baked.toString())
                            isProcessing = false
                        }
                    }
                },
                onSkipBack = { captureState = IdCardCaptureFlow.skipBack(captureState) }
            )
        }
    }
}

/** Black CamScanner-style top camera bar: close, and flash/HD/overflow controls. */
@Composable
private fun IdCardCaptureTopBar(
    flashOn: Boolean,
    onFlashToggle: () -> Unit,
    showOverflowMenu: Boolean,
    onOverflowClick: () -> Unit,
    onOverflowDismiss: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.fillMaxWidth(), color = Color.Black) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onFlashToggle) {
                    Icon(
                        imageVector = if (flashOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Toggle flash",
                        tint = if (flashOn) GreenAccent else Color.White
                    )
                }
                Surface(shape = RoundedCornerShape(4.dp), color = Color(0x33FFFFFF)) {
                    Text(
                        text = "HD",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
                Box {
                    IconButton(onClick = onOverflowClick) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options", tint = Color.White)
                    }
                    DropdownMenu(expanded = showOverflowMenu, onDismissRequest = onOverflowDismiss) {
                        DropdownMenuItem(
                            text = { Text("More options coming soon") },
                            onClick = onOverflowDismiss
                        )
                    }
                }
            }
        }
    }
}

/** ID-card corner-bracket guide frame with a compact "Front"/"Back" label, centered over the camera preview. */
@Composable
private fun IdCardGuideOverlay(
    stage: IdCardCaptureStage,
    onFrameBoundsChanged: (Rect) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = Color(0x99000000)
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp),
                text = if (stage == IdCardCaptureStage.BACK) "Back" else "Front",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
        }
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(16.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(IdCardLayoutPlanner.CARD_ASPECT_RATIO)
                .onGloballyPositioned { onFrameBoundsChanged(it.boundsInRoot()) }
        ) {
            CornerBracketFrame(modifier = Modifier.fillMaxSize())
        }
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

/** Draws four white L-shaped corner brackets instead of a full rounded-rectangle border, CamScanner-style. */
@Composable
private fun CornerBracketFrame(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val strokeWidthPx = 4.dp.toPx()
        val bracketLength = size.minDimension * 0.14f
        val corners = listOf(
            Offset(0f, 0f) to Offset(1f, 1f),
            Offset(size.width, 0f) to Offset(-1f, 1f),
            Offset(0f, size.height) to Offset(1f, -1f),
            Offset(size.width, size.height) to Offset(-1f, -1f)
        )
        corners.forEach { (corner, direction) ->
            drawLine(
                color = Color.White,
                start = corner,
                end = corner + Offset(direction.x * bracketLength, 0f),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round
            )
            drawLine(
                color = Color.White,
                start = corner,
                end = corner + Offset(0f, direction.y * bracketLength),
                strokeWidth = strokeWidthPx,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun IdCardCaptureControls(
    stage: IdCardCaptureStage,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onImport: () -> Unit,
    onCapture: () -> Unit,
    onSkipBack: () -> Unit
) {
    Surface(modifier = modifier.fillMaxWidth(), color = Color.Black) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp, top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (stage == IdCardCaptureStage.BACK) {
                TextButton(onClick = onSkipBack, enabled = enabled) {
                    Text("Use front only", color = Color.White)
                }
            }
            Box(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(76.dp)
                        .border(3.dp, GreenAccent, CircleShape)
                        .padding(5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = onCapture,
                        enabled = enabled,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.White, CircleShape)
                    ) {
                        Icon(Icons.Default.Camera, contentDescription = "Capture", tint = Color.Black)
                    }
                }
                TextButton(
                    onClick = onImport,
                    enabled = enabled,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 16.dp)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, tint = Color.White)
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(6.dp))
                    Text("Import Images", color = Color.White, style = MaterialTheme.typography.labelSmall)
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
