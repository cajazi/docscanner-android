package com.dev.docscannerpdf.ui.idcard

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
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
import com.dev.docscannerpdf.BuildConfig
import com.dev.docscannerpdf.domain.idscan.IdCardCaptureShutterGate
import com.dev.docscannerpdf.domain.idscan.UhdSupportState
import com.dev.docscannerpdf.domain.idscan.captureClickRejection
import com.dev.docscannerpdf.domain.idscan.IdCardRawCaptureInspector
import com.dev.docscannerpdf.domain.idscan.IdCardCaptureStage
import com.dev.docscannerpdf.domain.idscan.IdCardCaptureState
import com.dev.docscannerpdf.domain.idscan.IdCardGuideFrameRect
import com.dev.docscannerpdf.domain.pdf.IdCardLayoutPlanner
import kotlinx.coroutines.launch
import java.io.File

private val GreenAccent = Color(0xFF16C89A)

// Debug-only, content-free capture diagnostics (Play-safe: nothing device- or user-identifying,
// stripped from release behavior by the BuildConfig gate).
private fun logCaptureClick(message: String) {
    if (BuildConfig.DEBUG) Log.d("IdCardCapture", "ID_CARD_CAPTURE_CLICK $message")
}

private fun logCaptureFlow(message: String) {
    if (BuildConfig.DEBUG) Log.d("IdCardCapture", "ID_CARD_CAPTURE_FLOW $message")
}

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

    // One stable identity per screen session (survives recomposition AND state restoration):
    // the mounted/disposed pair proves whether Compose replaced this screen's node — the only
    // mechanism that can reconstruct the camera controller now that every owned object below
    // uses keyless remember at the screen's top level.
    val screenSessionId = rememberSaveable { java.lang.Long.toHexString(System.nanoTime()) }
    DisposableEffect(Unit) {
        if (BuildConfig.DEBUG) Log.d("IdCardCapture", "ID_CARD_CAPTURE_SCREEN mounted=$screenSessionId")
        onDispose {
            if (BuildConfig.DEBUG) Log.d("IdCardCapture", "ID_CARD_CAPTURE_SCREEN disposed=$screenSessionId")
        }
    }

    DarkSystemBarsEffect()

    var captureState by remember { mutableStateOf(IdCardCaptureState()) }
    var isProcessing by remember { mutableStateOf(false) }
    var flashOn by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var containerBounds by remember { mutableStateOf<Rect?>(null) }
    var frameBounds by remember { mutableStateOf<Rect?>(null) }

    // Shutter sound, owned by this capture layer: created once, released when the screen leaves
    // composition. The gate (pure, unit-tested) plays it exactly once per SUBMITTED camera
    // capture — the controller confirms its ImageCapture use case exists and fires the
    // submission callback immediately before the real takePicture. Rejected duplicate taps,
    // unbound-camera taps, imports, and every review-screen action stay silent.
    val shutterSound = remember { MediaActionShutterSoundPlayer() }
    val captureShutterGate = remember { IdCardCaptureShutterGate(shutterSound) }
    DisposableEffect(Unit) {
        onDispose { shutterSound.release() }
    }

    // Controller ownership lives at the SCREEN's top level — outside every conditional branch
    // — so neither permission flips nor support-state recomposition can ever reconstruct it:
    // exactly one controller (and one bind) per screen visit, released once on disposal. The
    // support-state callback writes into a stable MutableState object, so publishing SUPPORTED
    // recomposes the controls without touching controller identity, and the state survives
    // recomposition (no reset to CHECKING).
    var uhdSupportState by remember { mutableStateOf(UhdSupportState.CHECKING) }
    val controller = remember {
        IdCardCameraController(context = context, lifecycleOwner = lifecycleOwner).also { created ->
            created.onSupportStateChanged = { state -> uhdSupportState = state }
        }
    }
    LaunchedEffect(flashOn) {
        controller.setFlashMode(if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
    }
    DisposableEffect(Unit) {
        onDispose { controller.unbind() }
    }

    LaunchedEffect(captureState.stage) {
        logCaptureFlow("stage_changed stage=${captureState.stage}")
        when (captureState.stage) {
            IdCardCaptureStage.BACK -> logCaptureFlow("back_ui_ready")
            IdCardCaptureStage.COMPLETE -> {
                val front = captureState.frontImageUri?.let(Uri::parse) ?: return@LaunchedEffect
                logCaptureFlow("review_navigation")
                onCaptureComplete(front, captureState.backImageUri?.let(Uri::parse))
            }
            IdCardCaptureStage.FRONT -> Unit
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
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { containerBounds = it.boundsInRoot() },
                factory = { ctx -> PreviewView(ctx).also { previewView -> controller.bind(previewView) } }
            )

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
                supportState = uhdSupportState,
                modifier = Modifier.align(Alignment.BottomCenter),
                onImport = { importLauncher.launch("image/*") },
                onCapture = onCapture@{
                    val side = if (captureState.stage == IdCardCaptureStage.FRONT) "front" else "back"
                    // Pure, unit-tested guard order; every rejection logs its exact reason so a
                    // dead shutter tap is never a mystery again.
                    val rejection = captureClickRejection(
                        supportState = uhdSupportState,
                        isProcessing = isProcessing,
                        gateBusy = captureShutterGate.isCapturing
                    )
                    if (rejection != null) {
                        logCaptureClick("rejected reason=$rejection")
                        return@onCapture
                    }
                    // Acquire the single-flight gate SILENTLY — the shutter sound plays only
                    // when the controller confirms a real takePicture submission (below).
                    if (!captureShutterGate.onCaptureAccepted()) {
                        logCaptureClick("rejected reason=gate_busy")
                        return@onCapture
                    }
                    logCaptureClick("accepted stage=${captureState.stage}")
                    logCaptureFlow("${side}_request_accepted")
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
                    controller.capture(
                        outputDirectory = outputDirectory,
                        filePrefix = "$prefix-raw",
                        // Fires only when the ImageCapture use case exists, immediately before
                        // the real takePicture — the sole shutter-sound trigger. An unbound
                        // camera returns null with no submission and therefore no sound.
                        onCaptureSubmitted = {
                            logCaptureFlow("${side}_submission_succeeded")
                            captureShutterGate.onCaptureSubmitted()
                        }
                    ) onCaptured@{ uri ->
                        logCaptureFlow("${side}_camera_result received=${uri != null}")
                        if (uri == null) {
                            // Submission failed or capture errored: re-arm silently for retry
                            // and make sure the live preview didn't freeze in the process.
                            logCaptureFlow("failed side=${side.uppercase()} step=camera_result reason=null_result")
                            captureShutterGate.onCaptureFinished()
                            isProcessing = false
                            controller.ensurePreviewStreaming()
                            return@onCaptured
                        }
                        scope.launch {
                            // finally guarantees BOTH flags reset on every exit: success,
                            // quality rejection, baking failure, cancellation, or an
                            // unexpected throw — a wedged gate/spinner must be impossible. On
                            // cancellation the try exits before onSideCaptured, so
                            // captureState is never updated. Preview recovery runs ONLY on
                            // failure — a successful Front commit transitions to Back with the
                            // live camera untouched, and a successful Back leaves the screen.
                            var committed = false
                            try {
                                // Runtime PROOF of the 4K requirement: read the raw JPEG's
                                // actual bounds (header only) and reject a sub-UHD capture in
                                // a controlled way — no review state, raw file deleted, gate
                                // re-armed by the finally, no second sound (the gate plays
                                // per accepted submission only).
                                val rawInfo = IdCardRawCaptureInspector.inspect(context, uri)
                                if (rawInfo == null) {
                                    logCaptureFlow("failed side=${side.uppercase()} step=raw_validation reason=unreadable")
                                    runCatching { uri.path?.let { File(it).delete() } }
                                    Toast.makeText(
                                        context,
                                        "Could not verify the capture. Please try again.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@launch
                                }
                                if (!rawInfo.meetsUhd) {
                                    logCaptureFlow("failed side=${side.uppercase()} step=raw_validation reason=below_uhd")
                                    runCatching { uri.path?.let { File(it).delete() } }
                                    Toast.makeText(
                                        context,
                                        "Capture quality was below 4K. Please try again.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@launch
                                }
                                logCaptureFlow("${side}_raw_validated meetsUhd=true")
                                logCaptureFlow("${side}_bake_started")
                                val baked = IdCardCaptureBaker.bakeFromCameraCapture(
                                    context = context,
                                    sourceUri = uri,
                                    frameRect = frameRect,
                                    containerSize = containerSize,
                                    outputDirectory = outputDirectory,
                                    filePrefix = prefix
                                ) ?: uri
                                logCaptureFlow("${side}_bake_completed")
                                captureState = IdCardCaptureFlow.onSideCaptured(captureState, baked.toString())
                                committed = true
                                logCaptureFlow("${side}_state_committed")
                            } finally {
                                captureShutterGate.onCaptureFinished()
                                isProcessing = false
                                if (!committed) {
                                    // Failure only: never recovery-bind across a successful
                                    // Front->Back transition or while leaving for review.
                                    controller.ensurePreviewStreaming()
                                }
                            }
                        }
                    }
                },
                onSkipBack = { captureState = IdCardCaptureFlow.skipBack(captureState) },
                onUndo = {
                    // On the back step, undo returns to retake the front; with nothing captured
                    // yet it backs out of the capture screen entirely.
                    if (captureState.stage == IdCardCaptureStage.BACK) {
                        captureState = IdCardCaptureFlow.undoCapture(captureState)
                    } else {
                        onBack()
                    }
                }
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

/**
 * CamScanner-matching guide overlay: everything OUTSIDE the card-ratio frame is dimmed and the
 * frame's interior gets a subtle white lift, so the card window visibly pops out of the preview;
 * thick rounded corner brackets mark the frame and a "Front"/"Back" pill floats near the top of
 * the preview. No instruction caption — the highlighted window is the instruction. The frame
 * rect is reported through [onFrameBoundsChanged] in root coordinates, exactly like before, so
 * [IdCardCaptureBaker]'s guide-frame crop keeps working unchanged.
 */
@Composable
private fun IdCardGuideOverlay(
    stage: IdCardCaptureStage,
    onFrameBoundsChanged: (Rect) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val frameMargin = 24.dp
        val frameWidth = maxWidth - frameMargin * 2
        val frameHeight = frameWidth / IdCardLayoutPlanner.CARD_ASPECT_RATIO
        // Slightly above vertical center, like CamScanner, so the frame clears the bottom bar.
        val frameTop = (maxHeight - frameHeight) * 0.45f

        Canvas(modifier = Modifier.fillMaxSize()) {
            val frameRect = Rect(
                offset = Offset(frameMargin.toPx(), frameTop.toPx()),
                size = Size(frameWidth.toPx(), frameHeight.toPx())
            )
            val cornerRadius = CornerRadius(14.dp.toPx())
            val framePath = Path().apply { addRoundRect(RoundRect(frameRect, cornerRadius)) }
            // Dim everything outside the card window.
            clipPath(framePath, ClipOp.Difference) {
                drawRect(color = Color.Black.copy(alpha = 0.45f))
            }
            // Subtle white lift inside the window so it reads brighter than the scene around it.
            drawRoundRect(
                color = Color.White.copy(alpha = 0.10f),
                topLeft = frameRect.topLeft,
                size = frameRect.size,
                cornerRadius = cornerRadius
            )
            drawCornerBrackets(frameRect)
        }

        // Invisible box tracking the exact frame geometry purely to report root bounds for the
        // capture baker's guide-frame crop.
        Box(
            modifier = Modifier
                .offset(x = frameMargin, y = frameTop)
                .size(width = frameWidth, height = frameHeight)
                .onGloballyPositioned { onFrameBoundsChanged(it.boundsInRoot()) }
        )

        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 76.dp),
            shape = RoundedCornerShape(100.dp),
            color = Color(0x99202124)
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                text = if (stage == IdCardCaptureStage.BACK) "Back" else "Front",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Four thick, round-capped white L-brackets on [frameRect]'s corners, CamScanner-style. */
private fun DrawScope.drawCornerBrackets(frameRect: Rect) {
    val strokeWidthPx = 4.5.dp.toPx()
    val bracketLength = frameRect.height * 0.16f
    val corners = listOf(
        Offset(frameRect.left, frameRect.top) to Offset(1f, 1f),
        Offset(frameRect.right, frameRect.top) to Offset(-1f, 1f),
        Offset(frameRect.left, frameRect.bottom) to Offset(1f, -1f),
        Offset(frameRect.right, frameRect.bottom) to Offset(-1f, -1f)
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

/**
 * CamScanner-matching bottom capture bar: a teal mode indicator dash over an "ID Card" label,
 * then undo (left) / plain white shutter in a teal ring (center) / "Import Images" with its icon
 * stacked above the label (right). The shutter is a bare circle — no glyph — per the reference.
 */
@Composable
private fun IdCardCaptureControls(
    stage: IdCardCaptureStage,
    enabled: Boolean,
    // Authoritative strict-4K state from the CameraX bind: CHECKING quietly disables the
    // shutter until the attached resolution is known; only a PROVEN sub-UHD attachment shows
    // the unsupported warning; ERROR explains a failed camera start. Import Images stays fully
    // available in every state, and a SUPPORTED publication clears any warning in place.
    supportState: UhdSupportState,
    modifier: Modifier = Modifier,
    onImport: () -> Unit,
    onCapture: () -> Unit,
    onSkipBack: () -> Unit,
    onUndo: () -> Unit
) {
    val captureEnabled = supportState == UhdSupportState.SUPPORTED
    Surface(modifier = modifier.fillMaxWidth(), color = Color.Black) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 18.dp, top = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val warningText = when (supportState) {
                UhdSupportState.UNSUPPORTED ->
                    "This camera does not support the required 4K ID-card capture. " +
                        "Use Import Images or another camera."
                UhdSupportState.ERROR ->
                    "The camera could not start. Close and reopen this screen, or use Import Images."
                UhdSupportState.CHECKING, UhdSupportState.SUPPORTED -> null
            }
            if (warningText != null) {
                Text(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                    text = warningText,
                    color = Color(0xFFF6C85F),
                    style = MaterialTheme.typography.labelSmall
                )
            }
            if (stage == IdCardCaptureStage.BACK) {
                TextButton(onClick = onSkipBack, enabled = enabled) {
                    Text("Use front only", color = Color.White)
                }
            }
            Box(
                modifier = Modifier
                    .size(width = 24.dp, height = 3.dp)
                    .background(GreenAccent, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "ID Card",
                color = GreenAccent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(12.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = onUndo,
                    enabled = enabled,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 24.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Undo,
                        contentDescription = "Undo capture",
                        tint = Color.White
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(74.dp)
                        .border(3.dp, if (captureEnabled) GreenAccent else Color(0xFF5E6067), CircleShape)
                        .padding(7.dp)
                        .clip(CircleShape)
                        .background(if (enabled && captureEnabled) Color.White else Color(0xFFB8BDC4))
                        .clickable(
                            enabled = enabled && captureEnabled,
                            onClick = onCapture,
                            onClickLabel = "Capture"
                        )
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 20.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = enabled, onClick = onImport)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(Icons.Default.Image, contentDescription = null, tint = Color.White)
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
