package com.dev.docscannerpdf.ui.mainscan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.dev.docscannerpdf.domain.idscan.IdCardCaptureShutterGate
import com.dev.docscannerpdf.domain.mainscan.MainScanCameraLifecycle
import com.dev.docscannerpdf.domain.mainscan.MainScanCameraState
import com.dev.docscannerpdf.domain.mainscan.MainScanCaptureState
import com.dev.docscannerpdf.domain.mainscan.MainScanCaptureTicket
import com.dev.docscannerpdf.domain.mainscan.MainScanTrace
import kotlinx.coroutines.delay
import com.dev.docscannerpdf.ui.idcard.DarkSystemBarsEffect
import com.dev.docscannerpdf.ui.idcard.MediaActionShutterSoundPlayer
import java.io.File

/** Accent used across the Main Scanner surfaces. Our own value, not sampled from any other app. */
internal val MainScanAccent = Color(0xFF17C9A6)
private val MainScanChrome = Color(0xFF101114)

/**
 * How long a submitted capture may go without ANY camera callback before it is abandoned. Generous
 * on purpose — a maximum-quality full-resolution still on a mid-range device legitimately takes
 * seconds — so this only ever fires on a genuine stall, never on a slow-but-working capture.
 */
private const val CAPTURE_CALLBACK_TIMEOUT_MS = 15_000L

/**
 * The app-owned Main Scanner capture surface.
 *
 * Behavioural contract, taken from `docs/main-scanner-reference.md`:
 *
 * - **Clean preview.** The camera fills the screen with NOTHING drawn over it — no quadrilateral,
 *   no guide frame, no corner brackets, no confidence readout. Silent detection runs inside
 *   [MainScannerCameraController] and never reaches this composable.
 * - **Manual shutter is first-class.** It is armed whenever no capture is in flight and no dialog
 *   is open. It is never disabled by resolution support, detection state, or document presence.
 * - **The shutter reacts immediately.** The accent ring pulses on the DOWN of the tap, driven by
 *   local UI state rather than by capture completion, so feedback never waits on the camera.
 * - **The preview keeps streaming through capture.** No full-screen spinner, no freeze frame, no
 *   unbind. The captured page becomes visible on the crop surface instead, so there is never a
 *   blank, white, tiny, or stale frame.
 *
 * Capture outcomes are reported to the host, which owns [MainScanCaptureState] and its pure
 * reducer: [onCaptureStarted] returns the generation token for an accepted capture (or null when
 * the reducer rejects it), and exactly one of [onCaptureSucceeded]/[onCaptureFailed] follows.
 *
 * Gallery import is intentionally absent from this slice. An imported page must first be
 * canonicalized into an app-private copy (the passport flow's hard-won fail-closed rule); that
 * baker arrives with Slice 3, and a button that hands a raw `content://` URI forward would violate
 * both the ownership guard and the no-raw-fallback rule.
 */
@Composable
fun MainScannerCaptureScreen(
    outputDirectory: File,
    state: MainScanCaptureState,
    onCaptureStarted: () -> MainScanCaptureTicket?,
    onCaptureSucceeded: (ticket: MainScanCaptureTicket, uri: android.net.Uri) -> Unit,
    onCaptureFailed: (ticket: MainScanCaptureTicket) -> Unit,
    onCaptureTimedOut: (ticket: MainScanCaptureTicket) -> Unit,
    onCameraUnavailable: () -> Unit,
    onBack: () -> Unit,
    onCancelDiscard: () -> Unit,
    onConfirmDiscard: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    DarkSystemBarsEffect()

    var flashOn by remember { mutableStateOf(false) }
    // Purely visual, and deliberately decoupled from the camera: incrementing this counter is what
    // pulses the shutter, so feedback is driven by the TAP alone and can never wait on (or be
    // suppressed by) capture progress. A counter rather than a boolean so back-to-back taps each
    // produce their own pulse.
    var shutterTaps by remember { mutableStateOf(0) }

    val shutterSound = remember { MediaActionShutterSoundPlayer() }
    val shutterGate = remember { IdCardCaptureShutterGate(shutterSound) }
    DisposableEffect(Unit) { onDispose { shutterSound.release() } }

    // One controller, one bind, per screen visit — keyless remember, matching the proven ID-card
    // and passport ownership model so no recomposition can construct a second controller.
    var cameraState by remember { mutableStateOf(MainScanCameraState.BINDING) }
    val controller = remember {
        MainScannerCameraController(context = context, lifecycleOwner = lifecycleOwner).also { created ->
            created.onStateChanged = { next -> cameraState = next }
        }
    }
    LaunchedEffect(flashOn) {
        controller.setFlashMode(
            if (flashOn) ImageCapture.FLASH_MODE_ON else ImageCapture.FLASH_MODE_OFF
        )
    }
    DisposableEffect(Unit) {
        onDispose {
            MainScanTrace.captureScreenDisposed(controller.controllerId)
            controller.resetDetection()
            controller.unbind()
        }
    }

    // The capture currently awaiting a camera callback, or null. Drives the watchdog below.
    var inFlightTicket by remember { mutableStateOf<MainScanCaptureTicket?>(null) }

    // CAPTURING must never be terminal. If the camera returns neither onImageSaved nor onError
    // within the bound, the capture is abandoned: the shutter re-arms, a controlled error is shown,
    // and the generation advances so a late result publishes nothing. This is a failure-recovery
    // bound, not a pacing delay — it never sits in front of a working capture, because a callback
    // clears `inFlightTicket` and cancels this effect immediately.
    LaunchedEffect(inFlightTicket) {
        val ticket = inFlightTicket ?: return@LaunchedEffect
        delay(CAPTURE_CALLBACK_TIMEOUT_MS)
        MainScanTrace.callbackTimedOut(
            elapsedMs = CAPTURE_CALLBACK_TIMEOUT_MS,
            sessionId = ticket.sessionId,
            generation = ticket.generation
        )
        inFlightTicket = null
        shutterGate.onCaptureFinished()
        onCaptureTimedOut(ticket)
        controller.ensurePreviewStreaming()
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

    fun fireShutter() {
        // Immediate feedback first — it must never wait on the reducer or the camera.
        shutterTaps++
        if (!shutterGate.onCaptureAccepted()) {
            MainScanTrace.shutterRejected(reason = "gate_busy")
            return
        }
        val ticket = onCaptureStarted()
        if (ticket == null) {
            shutterGate.onCaptureFinished()
            return
        }
        inFlightTicket = ticket
        controller.capture(
            outputDirectory = outputDirectory,
            filePrefix = "main_scan",
            traceSessionId = ticket.sessionId,
            traceGeneration = ticket.generation,
            onCaptureSubmitted = { shutterGate.onCaptureSubmitted() },
            onResult = { uri ->
                // Clearing this first cancels the watchdog, so a callback that arrives close to the
                // bound can never be double-handled as both a success and a timeout.
                inFlightTicket = null
                shutterGate.onCaptureFinished()
                // The ticket carries the visit id, so a result arriving after this visit was left
                // is rejected by the host's reducer — it cannot navigate, and its output is deleted.
                if (uri == null) {
                    onCaptureFailed(ticket)
                    // A failed capture can leave the preview frozen; recover it so the surface is
                    // never left showing a stale frame.
                    controller.ensurePreviewStreaming()
                } else {
                    onCaptureSucceeded(ticket, uri)
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
    ) {
        if (!hasPermission) {
            MainScanPermissionPrompt(
                onGrant = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                onBack = onBack
            )
        } else if (MainScanCameraLifecycle.requiresFailureSurface(cameraState)) {
            // Controlled initialization failure. The camera could not attach all three required use
            // cases, so NO scanner is presented — showing one that silently lacks ImageAnalysis
            // would give working chrome over a pipeline that cannot seed the crop. The intact ML Kit
            // path is offered instead.
            MainScanCameraUnavailable(
                onUseStandardScanner = onCameraUnavailable,
                onBack = onBack
            )
        } else {
            // The clean preview. Nothing is composed above it except the chrome bars.
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PreviewView(ctx).also { view -> controller.bind(view) }
                }
            )

            MainScanCaptureTopBar(
                flashOn = flashOn,
                onFlashToggle = { flashOn = !flashOn },
                onClose = onBack,
                modifier = Modifier.align(Alignment.TopCenter)
            )

            MainScanCaptureControls(
                // Armed by the session reducer AND by a genuinely attached three-use-case camera.
                shutterEnabled = state.canCapture &&
                    MainScanCameraLifecycle.allowsCapture(cameraState),
                shutterTaps = shutterTaps,
                onShutter = { fireShutter() },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        if (state.discardConfirmVisible) {
            MainScanDiscardDialog(
                onCancel = onCancelDiscard,
                onDiscard = onConfirmDiscard
            )
        }
    }
}

@Composable
private fun MainScanCaptureTopBar(
    flashOn: Boolean,
    onFlashToggle: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(MainScanChrome)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onClose) {
            Icon(Icons.Filled.Close, contentDescription = "Close scanner", tint = Color.White)
        }
        Box(modifier = Modifier.weight(1f))
        IconButton(onClick = onFlashToggle) {
            Icon(
                imageVector = if (flashOn) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                contentDescription = if (flashOn) "Turn flash off" else "Turn flash on",
                tint = if (flashOn) MainScanAccent else Color.White
            )
        }
    }
}

/**
 * Bottom chrome: the active-mode indicator and the manual shutter.
 *
 * Only `Single` is offered because only `Single` exists. Batch capture arrives with Slice 6 and
 * will be added to this row then — the project does not render modes that do nothing.
 */
@Composable
private fun MainScanCaptureControls(
    shutterEnabled: Boolean,
    shutterTaps: Int,
    onShutter: () -> Unit,
    modifier: Modifier = Modifier
) {
    // A deterministic one-shot: on each tap the ring compresses and springs back over a fixed,
    // short duration. Keying the effect on the tap COUNT means the full down-up cycle always runs
    // to completion — it cannot be cut short or swallowed by a target value flipping back, and it
    // is entirely independent of how long the capture itself takes.
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(shutterTaps) {
        if (shutterTaps == 0) return@LaunchedEffect
        pulse.snapTo(1f)
        pulse.animateTo(0.86f, animationSpec = tween(durationMillis = 70))
        pulse.animateTo(1f, animationSpec = tween(durationMillis = 130))
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MainScanChrome)
            .padding(top = 10.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Single",
                color = MainScanAccent,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Box(
                modifier = Modifier
                    .padding(top = 3.dp)
                    .size(width = 18.dp, height = 2.dp)
                    .background(MainScanAccent, RoundedCornerShape(1.dp))
            )
        }

        Box(
            modifier = Modifier
                .size(72.dp)
                .scale(pulse.value)
                .alpha(if (shutterEnabled) 1f else 0.5f)
                .border(width = 3.dp, color = MainScanAccent, shape = CircleShape)
                .padding(6.dp)
                .background(Color.White, CircleShape)
                .clickable(enabled = shutterEnabled, onClick = onShutter)
        )
    }
}

/**
 * The discard confirmation the reference raises on Back once something would be lost. Cancel is
 * neutral; the destructive action is accent-filled and unambiguous.
 *
 * INTERNAL, not private: Back raises the same `discardConfirmVisible` decision from both the capture
 * and the crop surface, so both must be able to answer it. While this was private to this file the
 * crop surface set the flag and rendered nothing, so Back had no visible effect — and because the
 * next Back press cancelled the invisible dialog, the two presses alternated between unseen states
 * and the crop screen could not be left at all.
 */
@Composable
internal fun MainScanDiscardDialog(
    onCancel: () -> Unit,
    onDiscard: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Discard scan?") },
        text = { Text("This page has not been saved. Discarding removes it from this device.") },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
        confirmButton = {
            Button(
                onClick = onDiscard,
                colors = ButtonDefaults.buttonColors(containerColor = MainScanAccent)
            ) { Text("Discard", color = Color.White) }
        }
    )
}

/**
 * The controlled initialization-failure surface. Deliberately not a scanner: the camera could not
 * attach Preview + ImageCapture + ImageAnalysis together, and a preview without analysis would be
 * an invisible degradation. Offers the intact standard (ML Kit) scanner as the fallback path.
 */
@Composable
private fun MainScanCameraUnavailable(
    onUseStandardScanner: () -> Unit,
    onBack: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "This camera mode isn't available on this device.",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onUseStandardScanner,
                colors = ButtonDefaults.buttonColors(containerColor = MainScanAccent)
            ) { Text("Use the standard scanner", color = Color.White) }
            TextButton(onClick = onBack) { Text("Go back", color = Color.White) }
        }
    }
}

@Composable
private fun MainScanPermissionPrompt(
    onGrant: () -> Unit,
    onBack: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = "Camera access is needed to scan documents.",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium
            )
            Button(
                onClick = onGrant,
                colors = ButtonDefaults.buttonColors(containerColor = MainScanAccent)
            ) { Text("Grant camera access", color = Color.White) }
            TextButton(onClick = onBack) { Text("Go back", color = Color.White) }
        }
    }
}
