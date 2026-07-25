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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.dev.docscannerpdf.BuildConfig
import com.dev.docscannerpdf.domain.idscan.IdCardCaptureContainerSize
import com.dev.docscannerpdf.domain.idscan.IdCardCaptureShutterGate
import com.dev.docscannerpdf.domain.idscan.IdCardGuideFrameRect
import com.dev.docscannerpdf.domain.idscan.IdCardRawCaptureInspector
import com.dev.docscannerpdf.domain.idscan.PassportCaptureBaker
import com.dev.docscannerpdf.domain.idscan.PassportGuideGeometry
import com.dev.docscannerpdf.domain.idscan.PassportIllustrationLayout
import com.dev.docscannerpdf.domain.idscan.UhdSupportState
import com.dev.docscannerpdf.domain.idscan.captureClickRejection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val PassportAccent = Color(0xFF16C89A)

private fun logPassport(message: String) {
    if (BuildConfig.DEBUG) Log.d("IdCardCapture", "PASSPORT_CAPTURE $message")
}

/**
 * CamScanner-style guided PASSPORT capture: a full-screen CameraX preview behind black top/
 * bottom control bars, a portrait passport guide frame (dimmed mask outside, corner brackets, a
 * dotted alignment line and an original head-and-shoulders illustration inside) and a single
 * shutter. Exactly one page is captured and handed back through [onCaptureComplete]; the page is
 * baked into a tight PORTRAIT crop by [PassportCaptureBaker] before it reaches review/save/PDF.
 *
 * Camera ownership mirrors the proven [IdCardGuidedCaptureScreen] architecture exactly — one
 * [IdCardCameraController] (and one bind) per screen visit via keyless top-level remember, one
 * shutter sound and gate, submission-ordered sound, strict raw-UHD validation before baking,
 * finally-guaranteed cleanup, and single-flight preview recovery only after a genuine failure —
 * so this screen inherits the same 4080x3060 UHD attachment, no controller churn, and no camera
 * leak. Images are written only to [outputDirectory] (app-private). This is a distinct single-
 * page flow: it shares no state with the ID-card Front/Back capture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassportGuidedCaptureScreen(
    outputDirectory: File,
    onBack: () -> Unit,
    onCaptureComplete: (Uri) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val screenSessionId = rememberSaveable { java.lang.Long.toHexString(System.nanoTime()) }
    DisposableEffect(Unit) {
        logPassport("SCREEN mounted=$screenSessionId")
        onDispose { logPassport("SCREEN disposed=$screenSessionId") }
    }

    DarkSystemBarsEffect()

    var isProcessing by remember { mutableStateOf(false) }
    var flashOn by remember { mutableStateOf(false) }
    var containerBounds by remember { mutableStateOf<Rect?>(null) }
    var frameBounds by remember { mutableStateOf<Rect?>(null) }
    // The bottom control panel's REAL measured height becomes the camera viewport's bottom
    // reserve. Measuring beats estimating: an under-estimate would hide the guide's bottom
    // corners behind the opaque panel, an over-estimate shrinks the guide (the 158dp estimate
    // was what capped the frame to 85% width). No circular dependency — the panel's height
    // does not depend on the guide — and it never touches CameraX, so no rebind/controller
    // churn can result from a layout measurement.
    var bottomPanelHeightPx by remember { mutableStateOf<Float?>(null) }
    // A single-flight guard so a stray recomposition/duplicate result can never fire the
    // completion twice — this guards the one-shot navigation callback.
    var pageCommitted by remember { mutableStateOf(false) }

    val shutterSound = remember { MediaActionShutterSoundPlayer() }
    val captureShutterGate = remember { IdCardCaptureShutterGate(shutterSound) }
    DisposableEffect(Unit) {
        onDispose { shutterSound.release() }
    }

    var uhdSupportState by remember { mutableStateOf(UhdSupportState.CHECKING) }
    val controller = remember {
        IdCardCameraController(
            context = context,
            lifecycleOwner = lifecycleOwner,
            owner = CameraSurfaceOwner.PASSPORT_GUIDED_CAPTURE
        ).also { created ->
            created.onSupportStateChanged = { state -> uhdSupportState = state }
        }
    }
    LaunchedEffect(flashOn) {
        controller.setFlashMode(if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF)
    }
    DisposableEffect(Unit) {
        onDispose { controller.unbind() }
    }

    fun completePage(uri: Uri) {
        if (pageCommitted) return
        pageCommitted = true
        logPassport("state_committed")
        logPassport("review_navigation")
        onCaptureComplete(uri)
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
            isProcessing = true
            scope.launch {
                // FAIL CLOSED: a successful import must produce an app-private CANONICAL copy.
                // If baking fails, stay on capture and surface an error — never hand the original
                // (possibly expiring, non-app-private, un-canonicalized) content:// URI to review,
                // and create no document.
                val baked = PassportCaptureBaker.bakeFromImport(
                    context = context,
                    sourceUri = uri,
                    outputDirectory = outputDirectory,
                    filePrefix = "passport_import"
                )
                isProcessing = false
                if (baked == null) {
                    logPassport("failed step=import_bake reason=canonicalization")
                    Toast.makeText(
                        context,
                        "Couldn't import this image. Please try another.",
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    completePage(baked)
                }
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
            // Import stays independent of CAMERA: a user who declines the camera can still bring
            // in a passport page from the gallery, which is baked into an app-private canonical
            // copy exactly like a camera capture.
            PassportCameraPermissionPrompt(
                onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onImport = { importLauncher.launch("image/*") },
                onBack = onBack
            )
            if (isProcessing) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }
        } else {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { containerBounds = it.boundsInRoot() },
                factory = { ctx -> PreviewView(ctx).also { previewView -> controller.bind(previewView) } }
            )

            PassportGuideOverlay(
                bottomReservePx = bottomPanelHeightPx,
                onFrameBoundsChanged = { frameBounds = it }
            )

            if (isProcessing) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color.White)
                }
            }

            PassportCaptureTopBar(
                flashOn = flashOn,
                onFlashToggle = { flashOn = !flashOn },
                onClose = onBack,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            PassportCaptureControls(
                enabled = !isProcessing,
                supportState = uhdSupportState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .onGloballyPositioned { coordinates ->
                        val measured = coordinates.size.height.toFloat()
                        // Only adopt a materially different measurement so recomposition can
                        // never thrash the guide geometry (no visual jumping).
                        if (bottomPanelHeightPx?.let { kotlin.math.abs(it - measured) > 1f } != false) {
                            bottomPanelHeightPx = measured
                        }
                    },
                onImport = { importLauncher.launch("image/*") },
                onBack = onBack,
                onCapture = onCapture@{
                    val rejection = captureClickRejection(
                        supportState = uhdSupportState,
                        isProcessing = isProcessing,
                        gateBusy = captureShutterGate.isCapturing
                    )
                    if (rejection != null) {
                        logPassport("click_rejected reason=$rejection")
                        return@onCapture
                    }
                    if (!captureShutterGate.onCaptureAccepted()) {
                        logPassport("click_rejected reason=gate_busy")
                        return@onCapture
                    }
                    logPassport("request_accepted")
                    isProcessing = true
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
                        filePrefix = "passport-raw",
                        onCaptureSubmitted = {
                            logPassport("submission_succeeded")
                            captureShutterGate.onCaptureSubmitted()
                        }
                    ) onCaptured@{ uri ->
                        logPassport("camera_result received=${uri != null}")
                        if (uri == null) {
                            captureShutterGate.onCaptureFinished()
                            isProcessing = false
                            controller.ensurePreviewStreaming()
                            return@onCaptured
                        }
                        scope.launch {
                            var committed = false
                            try {
                                val rawInfo = IdCardRawCaptureInspector.inspect(context, uri)
                                if (rawInfo == null) {
                                    logPassport("failed step=raw_validation reason=unreadable")
                                    withContext(Dispatchers.IO) { runCatching { uri.path?.let { File(it).delete() } } }
                                    Toast.makeText(
                                        context,
                                        "Could not verify the capture. Please try again.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@launch
                                }
                                if (!rawInfo.meetsUhd) {
                                    logPassport("failed step=raw_validation reason=below_uhd")
                                    withContext(Dispatchers.IO) { runCatching { uri.path?.let { File(it).delete() } } }
                                    Toast.makeText(
                                        context,
                                        "Capture quality was below 4K. Please try again.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@launch
                                }
                                logPassport("raw_validated meetsUhd=true")
                                // FAIL CLOSED: never let the raw, un-cropped, possibly
                                // stale-orientation capture reach review. If canonical baking
                                // fails, delete the app-owned raw file and let the finally block
                                // release the gate, clear processing and restore the preview so the
                                // user can retake — no completePage, no document.
                                val baked = PassportCaptureBaker.bakeFromCameraCapture(
                                    context = context,
                                    sourceUri = uri,
                                    frameRect = frameRect,
                                    containerSize = containerSize,
                                    outputDirectory = outputDirectory,
                                    filePrefix = "passport"
                                )
                                if (baked == null) {
                                    logPassport("failed step=camera_bake reason=canonicalization")
                                    withContext(Dispatchers.IO) { runCatching { uri.path?.let { File(it).delete() } } }
                                    Toast.makeText(
                                        context,
                                        "Couldn't process the capture. Please try again.",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    return@launch
                                }
                                // The baked canonical copy now owns the page; the raw UHD file is
                                // no longer referenced, so delete it (temp-file ownership transfer).
                                withContext(Dispatchers.IO) { runCatching { uri.path?.let { File(it).delete() } } }
                                committed = true
                                captureShutterGate.onCaptureFinished()
                                isProcessing = false
                                completePage(baked)
                            } finally {
                                if (!committed) {
                                    captureShutterGate.onCaptureFinished()
                                    isProcessing = false
                                    controller.ensurePreviewStreaming()
                                }
                            }
                        }
                    }
                }
            )
        }
    }
}

/** Black top camera bar: close, flash and an HD indicator — DocScanner's own chrome. */
@Composable
private fun PassportCaptureTopBar(
    flashOn: Boolean,
    onFlashToggle: () -> Unit,
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
                        tint = if (flashOn) PassportAccent else Color.White
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
            }
        }
    }
}

/**
 * Portrait passport guide overlay: dims everything outside the frame, lifts the interior, draws
 * corner brackets, a dotted horizontal alignment line and an original head-and-shoulders
 * silhouette with placeholder text lines (no real passport, emblem, flag or personal data), and
 * a "Fit it into the borders" instruction just above the frame. Geometry comes from the pure
 * [PassportGuideGeometry] (responsive; adapts to the measured region), and the frame's root
 * bounds are reported through [onFrameBoundsChanged] for [PassportCaptureBaker]'s crop.
 */
@Composable
private fun PassportGuideOverlay(
    bottomReservePx: Float?,
    onFrameBoundsChanged: (Rect) -> Unit
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = androidx.compose.ui.platform.LocalDensity.current
        // The CAMERA VIEWPORT: the region actually available between the top toolbar and the
        // bottom Passport controls (system bars/cutouts are already removed by the screen's
        // safeDrawingPadding). The bottom reserve is the panel's REAL measured height, with a
        // 115dp fallback for the very first frame — an estimated 158dp reserve was what forced
        // the height-cap branch and shrank the frame to 85% width.
        val topReserve = 60.dp
        val minMargin = 12.dp

        val viewportWidthPx = with(density) { maxWidth.toPx() }
        val topReservePx = with(density) { topReserve.toPx() }
        val effectiveBottomReservePx = bottomReservePx ?: with(density) { 115.dp.toPx() }
        val viewportHeightPx = (with(density) { maxHeight.toPx() } - topReservePx - effectiveBottomReservePx)
            .coerceAtLeast(1f)
        if (BuildConfig.DEBUG) {
            LaunchedEffect(effectiveBottomReservePx) {
                Log.d(
                    "IdCardCapture",
                    "PASSPORT_CONTROLS panelHeight=$effectiveBottomReservePx " +
                        "measured=${bottomReservePx != null} " +
                        "panelHeightDp=${effectiveBottomReservePx / density.density}"
                )
            }
        }
        val layout = remember(viewportWidthPx, viewportHeightPx) {
            PassportGuideGeometry.planPassportGuide(
                viewportWidth = viewportWidthPx,
                viewportHeight = viewportHeightPx,
                minimumHorizontalMargin = with(density) { minMargin.toPx() }
            )
        }
        val illustration = remember(layout) { PassportGuideGeometry.planIllustration(layout) }
        if (BuildConfig.DEBUG) {
            LaunchedEffect(layout) {
                val screenH = with(density) { maxHeight.toPx() }
                Log.d(
                    "IdCardCapture",
                    "PASSPORT_LAYOUT screenWidth=$viewportWidthPx screenHeight=$screenH " +
                        "cameraViewportTop=$topReservePx cameraViewportHeight=$viewportHeightPx " +
                        "frameLeft=${layout.left} frameTop=${layout.top + topReservePx} " +
                        "frameRight=${layout.right} frameBottom=${layout.bottom + topReservePx} " +
                        "frameWidthFraction=${layout.width / viewportWidthPx} " +
                        "frameHeightFraction=${layout.height / viewportHeightPx} " +
                        "alignmentFraction=${(layout.alignmentY - layout.top) / layout.height}"
                )
            }
        }
        // Absolute frame rect within the full container (shift down by the reserved top region).
        val frameLeft = layout.left
        val frameTop = layout.top + topReservePx
        val frameRight = layout.right
        val frameBottom = layout.bottom + topReservePx
        val alignmentY = layout.alignmentY + topReservePx

        Canvas(modifier = Modifier.fillMaxSize()) {
            val frameRect = Rect(
                offset = Offset(frameLeft, frameTop),
                size = Size(frameRight - frameLeft, frameBottom - frameTop)
            )
            val cornerRadius = CornerRadius(14.dp.toPx())
            val framePath = Path().apply { addRoundRect(RoundRect(frameRect, cornerRadius)) }
            clipPath(framePath, ClipOp.Difference) {
                drawRect(color = Color.Black.copy(alpha = 0.45f))
            }
            drawRoundRect(
                color = Color.White.copy(alpha = 0.08f),
                topLeft = frameRect.topLeft,
                size = frameRect.size,
                cornerRadius = cornerRadius
            )
            drawPassportCornerBrackets(frameRect)
            drawDottedAlignmentLine(frameRect, alignmentY)
            drawPassportIllustration(illustration, topReservePx)
        }

        // Invisible box tracking the exact frame geometry for the baker's crop mapping.
        Box(
            modifier = Modifier
                .offset(
                    x = with(density) { frameLeft.toDp() },
                    y = with(density) { frameTop.toDp() }
                )
                .size(
                    width = with(density) { (frameRight - frameLeft).toDp() },
                    height = with(density) { (frameBottom - frameTop).toDp() }
                )
                .onGloballyPositioned { onFrameBoundsChanged(it.boundsInRoot()) }
        )

        // "Fit it into the borders": centered and straddling the frame's top border, so it
        // reads as part of the frame and consumes no layout height of its own.
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = with(density) { (frameTop - 16.dp.toPx()).toDp() })
                .semantics { contentDescription = "Fit the passport page into the borders" },
            shape = RoundedCornerShape(100.dp),
            color = Color(0x99202124)
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                text = "Fit it into the borders",
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * Four thick, round-capped white L-brackets — reference proportions: horizontal arms ~12% of the
 * frame width, vertical arms ~8.5% of the frame height (never a full rectangular border).
 */
private fun DrawScope.drawPassportCornerBrackets(frameRect: Rect) {
    val strokeWidthPx = 5.dp.toPx()
    val horizontalArm = frameRect.width * 0.12f
    val verticalArm = frameRect.height * 0.085f
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
            end = corner + Offset(direction.x * horizontalArm, 0f),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White,
            start = corner,
            end = corner + Offset(0f, direction.y * verticalArm),
            strokeWidth = strokeWidthPx,
            cap = StrokeCap.Round
        )
    }
}

private fun DrawScope.drawDottedAlignmentLine(frameRect: Rect, alignmentY: Float) {
    val inset = frameRect.width * 0.08f
    drawLine(
        color = Color.White.copy(alpha = 0.85f),
        start = Offset(frameRect.left + inset, alignmentY),
        end = Offset(frameRect.right - inset, alignmentY),
        strokeWidth = 2.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f), 0f)
    )
}

/**
 * Original DocScanner passport illustration, drawn from the pure
 * [PassportGuideGeometry.planIllustration] layout so it scales with the enlarged guide frame: a
 * simplified head-and-shoulders silhouette on the lower left with placeholder text lines beside
 * and below it, plus one long line across the lower frame. Generic artwork only — no real
 * passport template, government emblem, flag, seal or personal data. [verticalOffset] shifts the
 * planner's viewport-relative coordinates into the full container's space.
 */
private fun DrawScope.drawPassportIllustration(
    illustration: PassportIllustrationLayout,
    verticalOffset: Float
) {
    val ink = Color.White.copy(alpha = 0.55f)
    drawCircle(
        color = ink,
        radius = illustration.headRadius,
        center = Offset(illustration.headCenterX, illustration.headCenterY + verticalOffset)
    )
    val shoulderWidth = illustration.shoulderRight - illustration.shoulderLeft
    drawRoundRect(
        color = ink,
        topLeft = Offset(illustration.shoulderLeft, illustration.shoulderTop + verticalOffset),
        size = Size(shoulderWidth, illustration.shoulderBottom - illustration.shoulderTop),
        cornerRadius = CornerRadius(shoulderWidth * 0.5f, shoulderWidth * 0.5f)
    )
    illustration.lines.forEach { line ->
        drawRoundRect(
            color = ink,
            topLeft = Offset(line.left, line.top + verticalOffset),
            size = Size(line.width, line.height),
            cornerRadius = CornerRadius(line.height / 2f, line.height / 2f)
        )
    }
}

/**
 * Bottom control area: "Passport" accent mode label, back/undo (left), a large bare shutter in
 * an accent ring (center) and Import Images (right). Mirrors the ID-card controls' 48dp+ touch
 * targets, content descriptions, and truthful UHD support states.
 */
@Composable
private fun PassportCaptureControls(
    enabled: Boolean,
    supportState: UhdSupportState,
    modifier: Modifier = Modifier,
    onImport: () -> Unit,
    onBack: () -> Unit,
    onCapture: () -> Unit
) {
    val captureEnabled = supportState == UhdSupportState.SUPPORTED
    Surface(modifier = modifier.fillMaxWidth(), color = Color.Black) {
        // Compact vertical rhythm so the panel claims no more height than the reference: the
        // camera viewport (and therefore the guide) gets the space back instead.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 6.dp, top = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val warningText = when (supportState) {
                UhdSupportState.UNSUPPORTED ->
                    "This camera does not support the required 4K passport capture. " +
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
            Box(
                modifier = Modifier
                    .size(width = 24.dp, height = 3.dp)
                    .background(PassportAccent, RoundedCornerShape(2.dp))
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Passport",
                color = PassportAccent,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth()) {
                IconButton(
                    onClick = onBack,
                    enabled = enabled,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 24.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(74.dp)
                        .border(3.dp, if (captureEnabled) PassportAccent else Color(0xFF5E6067), CircleShape)
                        .padding(7.dp)
                        .clip(CircleShape)
                        .background(if (enabled && captureEnabled) Color.White else Color(0xFFB8BDC4))
                        .clickable(
                            enabled = enabled && captureEnabled,
                            onClick = onCapture,
                            onClickLabel = "Capture"
                        )
                        .semantics { contentDescription = "Capture passport page" }
                )
                Column(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 20.dp)
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
private fun PassportCameraPermissionPrompt(
    onGrant: () -> Unit,
    onImport: () -> Unit,
    onBack: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "Camera access is needed to scan the passport page. " +
                    "You can also import a passport image from your gallery.",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(containerColor = PassportAccent, contentColor = Color.White)
            ) {
                Text("Grant camera access")
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clickable(onClick = onImport)
                    .semantics { contentDescription = "Import passport image" }
                    .padding(8.dp)
            ) {
                Icon(Icons.Default.Image, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.size(8.dp))
                Text("Import Images", color = Color.White, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = "Go back",
                color = Color(0xFFB8BDC4),
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(8.dp)
            )
        }
    }
}
