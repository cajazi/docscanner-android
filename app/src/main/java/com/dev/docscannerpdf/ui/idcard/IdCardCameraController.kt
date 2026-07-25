package com.dev.docscannerpdf.ui.idcard

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.dev.docscannerpdf.BuildConfig
import com.dev.docscannerpdf.domain.idscan.CaptureSize
import com.dev.docscannerpdf.domain.idscan.IdCardCaptureSubmission
import com.dev.docscannerpdf.domain.idscan.PreviewRecoveryGuard
import com.dev.docscannerpdf.domain.idscan.UhdSupportState
import com.dev.docscannerpdf.domain.idscan.chooseUhdCaptureSize
import com.dev.docscannerpdf.domain.idscan.combineCaptureSizeLists
import com.dev.docscannerpdf.domain.idscan.orderCaptureSizesForUhd
import com.dev.docscannerpdf.domain.idscan.resolveUhdSupportState
import java.io.File

/**
 * Drives the guided ID-card capture screen's camera: a CameraX preview plus a still-image
 * [ImageCapture] use case. Captured JPEGs are written only into [outputDirectory] — always a
 * directory under the app's own private storage (e.g. `context.filesDir`), never external
 * storage or a content URI requiring extra permissions.
 */
class IdCardCameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    val owner: CameraSurfaceOwner
) {
    /**
     * Stable, process-unique id for THIS controller instance, greppable across its whole
     * lifecycle (created → bind → released). A monotonic counter — not an identity hash — so two
     * concurrently live controllers can never collide in the evidence.
     */
    val controllerId: String = "c${NEXT_CONTROLLER_ID.incrementAndGet()}"

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var pendingFlashMode: Int = ImageCapture.FLASH_MODE_OFF
    private var boundPreviewView: PreviewView? = null
    private val previewRecoveryGuard = PreviewRecoveryGuard()
    private var bindInFlight = false

    /**
     * Authoritative strict-4K support state, decided by the resolution CameraX actually
     * ATTACHES after binding — not by metadata. Starts [UhdSupportState.CHECKING]; each
     * completed bind publishes SUPPORTED/UNSUPPORTED/ERROR through [onSupportStateChanged]
     * (main thread). Metadata (the capability list) only helps ORDER candidates: on the
     * SM-A165F the normal JPEG list omits the 4080x3060 high-resolution still entirely, so an
     * early metadata "no UHD" must never permanently disable capture.
     */
    var supportState: UhdSupportState = UhdSupportState.CHECKING
        private set

    var onSupportStateChanged: ((UhdSupportState) -> Unit)? = null

    private fun publishSupportState(next: UhdSupportState) {
        if (supportState == next) return
        supportState = next
        onSupportStateChanged?.invoke(next)
    }

    /**
     * Camera2 JPEG capability of the default back camera: the UNION of the normal and
     * high-resolution output lists (see [combineCaptureSizeLists]). Used for candidate
     * ordering and diagnostics only — never as the final support decision.
     */
    data class UhdCaptureCapability(
        val cameraId: String?,
        val target: CaptureSize?,
        val supportedUhdCount: Int
    )

    val uhdCapability: UhdCaptureCapability = queryBackCameraUhdCapability(context)

    init {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, CameraOwnershipLog.created(owner, controllerId))
        }
    }

    private fun queryBackCameraUhdCapability(context: Context): UhdCaptureCapability = runCatching {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val backCameraId = manager.cameraIdList.firstOrNull { id ->
            manager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        } ?: return@runCatching UhdCaptureCapability(cameraId = null, target = null, supportedUhdCount = 0)
        val streamMap = manager.getCameraCharacteristics(backCameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val normalSizes = streamMap?.getOutputSizes(ImageFormat.JPEG)
            ?.map { CaptureSize(it.width, it.height) }
            .orEmpty()
        // The high-resolution list is what PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE unlocks;
        // on this device family the UHD stills (4080x3060, 4080x2296) live ONLY here.
        val highResolutionSizes = runCatching {
            streamMap?.getHighResolutionOutputSizes(ImageFormat.JPEG)
                ?.map { CaptureSize(it.width, it.height) }
        }.getOrNull()
        val combined = combineCaptureSizeLists(normalSizes, highResolutionSizes)
        val target = chooseUhdCaptureSize(combined)
        if (BuildConfig.DEBUG) {
            fun List<CaptureSize>.render() = joinToString(",") { "${it.width}x${it.height}" }
            Log.d(
                TAG,
                "ID_CARD_CAPTURE_CAPABILITY cameraId=$backCameraId " +
                    "normalSizes=[${normalSizes.render()}] " +
                    "highResolutionSizes=[${highResolutionSizes.orEmpty().render()}] " +
                    "uhdTarget=${target?.let { "${it.width}x${it.height}" }}"
            )
        }
        UhdCaptureCapability(
            cameraId = backCameraId,
            target = target,
            supportedUhdCount = combined.count { it.meetsUhd }
        )
    }.getOrElse { throwable ->
        Log.w(TAG, "Unable to query camera capabilities: ${throwable.message}")
        UhdCaptureCapability(cameraId = null, target = null, supportedUhdCount = 0)
    }

    /** Binds preview + still-capture to the camera lifecycle. Idempotent — recovery re-calls it. */
    fun bind(previewView: PreviewView, reason: String = "initial") {
        boundPreviewView = previewView
        bindInFlight = true
        if (BuildConfig.DEBUG) {
            Log.d(TAG, CameraOwnershipLog.bind(owner, reason, controllerId))
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = runCatching { future.get() }.getOrNull()
                if (provider == null) {
                    publishSupportState(UhdSupportState.ERROR)
                    return@addListener
                }
                cameraProvider = provider

                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                // UHD-or-better still capture: the resolution filter re-orders the candidates
                // CameraX supplies (which include the high-resolution list thanks to
                // PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE) so the policy winner — the
                // highest-area 4:3 UHD size, 4080x3060 on the SM-A165F, ahead of 4080x2296 —
                // comes first and every sub-UHD size is dropped WHEN a qualifying one exists;
                // with no qualifying candidate the list passes through untouched so preview
                // binding keeps working for Import flows. The filter never returns empty. The
                // resolution CameraX actually ATTACHES is then the authoritative support
                // decision (published below), and IdCardRawCaptureInspector still proves every
                // real capture file against the same UHD predicate.
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                            .setAllowedResolutionMode(ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE)
                            .setResolutionFilter { supportedSizes, _ ->
                                val ordered = orderCaptureSizesForUhd(
                                    supportedSizes.map { CaptureSize(it.width, it.height) }
                                )
                                if (BuildConfig.DEBUG) {
                                    Log.d(
                                        TAG,
                                        "ID_CARD_CAPTURE_FILTER " +
                                            "input=[${supportedSizes.joinToString(",") { "${it.width}x${it.height}" }}] " +
                                            "output=[${ordered.joinToString(",") { "${it.width}x${it.height}" }}]"
                                    )
                                }
                                ordered.map { Size(it.width, it.height) }
                            }
                            .build()
                    )
                    .setFlashMode(pendingFlashMode)
                    .build()
                imageCapture = capture

                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture
                    )
                }.onSuccess {
                    // The attached resolution is the authority: a bound UHD ImageCapture
                    // enables the shutter and clears any stale unsupported warning, no matter
                    // what the metadata preflight claimed.
                    val attached = capture.resolutionInfo?.resolution
                    val resolvedState = resolveUhdSupportState(
                        attachedWidth = attached?.width,
                        attachedHeight = attached?.height,
                        bindFailed = false
                    )
                    Log.i(
                        TAG,
                        "ID_CARD_CAPTURE_CONFIG cameraId=${uhdCapability.cameraId} " +
                            "attachedResolution=${attached?.width}x${attached?.height} " +
                            "meetsUhd=${resolvedState == UhdSupportState.SUPPORTED} " +
                            "supportedUhdCount=${uhdCapability.supportedUhdCount}"
                    )
                    publishSupportState(resolvedState)
                }.onFailure {
                    // Never silently rebind a lower resolution: the runtime inspector still
                    // rejects sub-UHD results, so a failed bind can't leak bad captures.
                    Log.w(TAG, "ID_CARD_CAPTURE_CONFIG bind failed: ${it.message}")
                    publishSupportState(UhdSupportState.ERROR)
                }
            } finally {
                bindInFlight = false
                previewRecoveryGuard.onRecoveryComplete()
            }
        }, mainExecutor)
    }

    /**
     * Restores a frozen live preview after a rejected/failed capture: rebinds ONLY when the
     * PreviewView reports it is not streaming AND no bind is already in flight, at most one
     * attempt at a time (see [PreviewRecoveryGuard]), lifecycle-safe via the normal [bind]
     * path, no delays. A streaming or currently-binding preview makes this a cheap no-op, so
     * callers may invoke it after every capture completion.
     */
    fun ensurePreviewStreaming() {
        val view = boundPreviewView ?: return
        if (bindInFlight) return
        val streaming = view.previewStreamState.value == PreviewView.StreamState.STREAMING
        if (!previewRecoveryGuard.shouldAttemptRecovery(streaming)) return
        Log.i(TAG, "ID_CARD_CAPTURE_RECOVERY rebinding preview (streamState=${view.previewStreamState.value})")
        bind(view, reason = "recovery")
    }

    /**
     * Captures a still image into a new file under [outputDirectory] (created if needed) and
     * invokes [onResult] with its [Uri] on success, or null exactly once on any failure: camera
     * not bound yet, un-creatable output directory, synchronous `takePicture` throw, or an
     * asynchronous capture error. [onCaptureSubmitted] is the authority for capture side
     * effects (the shutter sound): it fires exactly once and ONLY after `takePicture` returned
     * successfully — i.e. only when a request genuinely reached the camera — via the pure,
     * unit-tested [IdCardCaptureSubmission] ordering. A synchronous submission failure plays no
     * sound, deletes the unused output file, and reports null without rethrowing into the UI;
     * a throwing [onCaptureSubmitted] can never disturb the already-submitted capture.
     */
    fun capture(
        outputDirectory: File,
        filePrefix: String,
        onCaptureSubmitted: () -> Unit = {},
        onResult: (Uri?) -> Unit
    ) {
        val capture = imageCapture
        if (capture == null) {
            onResult(null)
            return
        }
        // Race-safe: created by this call OR already a directory (front/back run concurrently).
        if (!(outputDirectory.mkdirs() || outputDirectory.isDirectory)) {
            Log.w(TAG, "ID card capture output directory is unavailable.")
            onResult(null)
            return
        }
        val file = File(outputDirectory, "$filePrefix-${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        val submitted = IdCardCaptureSubmission.submit(
            submit = {
                capture.takePicture(
                    options,
                    mainExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            onResult(Uri.fromFile(file))
                        }

                        override fun onError(exception: ImageCaptureException) {
                            Log.w(TAG, "ID card capture failed: ${exception.message}")
                            onResult(null)
                        }
                    }
                )
            },
            onSubmitted = onCaptureSubmitted,
            onSubmissionError = { throwable ->
                Log.w(TAG, "ID card capture submission failed: ${throwable.message}")
            }
        )
        if (!submitted) {
            file.delete()
            onResult(null)
        }
    }

    /** Sets the flash mode (one of [ImageCapture]'s `FLASH_MODE_*` constants) for future captures. */
    fun setFlashMode(mode: Int) {
        pendingFlashMode = mode
        imageCapture?.flashMode = mode
    }

    fun unbind() {
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
        imageCapture = null
        boundPreviewView = null
        if (BuildConfig.DEBUG) {
            Log.d(TAG, CameraOwnershipLog.released(owner, controllerId))
        }
    }

    private companion object {
        const val TAG = "IdCardCameraController"

        /** Per-process source of stable, unique [controllerId] values. */
        private val NEXT_CONTROLLER_ID = java.util.concurrent.atomic.AtomicInteger(0)
    }
}
