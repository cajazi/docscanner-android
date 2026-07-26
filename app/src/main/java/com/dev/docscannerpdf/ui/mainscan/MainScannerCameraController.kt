package com.dev.docscannerpdf.ui.mainscan

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.dev.docscannerpdf.BuildConfig
import com.dev.docscannerpdf.domain.detection.FrameDropPolicy
import com.dev.docscannerpdf.domain.detection.FrameRateLimiter
import com.dev.docscannerpdf.domain.detection.LiveDetectionSession
import com.dev.docscannerpdf.domain.detection.StabilizerState
import com.dev.docscannerpdf.domain.detection.YuvLumaConverter
import com.dev.docscannerpdf.domain.idscan.IdCardCaptureSubmission
import com.dev.docscannerpdf.domain.idscan.PreviewRecoveryGuard
import com.dev.docscannerpdf.domain.mainscan.MainScanCameraLifecycle
import com.dev.docscannerpdf.domain.mainscan.MainScanCameraState
import com.dev.docscannerpdf.domain.mainscan.MainScanTrace
import com.dev.docscannerpdf.ui.idcard.CameraOwnershipLog
import com.dev.docscannerpdf.ui.idcard.CameraSurfaceOwner
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives the app-owned Main Scanner capture surface: a CameraX preview, a full-resolution still
 * [ImageCapture], and a SILENT [ImageAnalysis] stream that seeds document-corner detection.
 *
 * Deliberate differences from [com.dev.docscannerpdf.ui.idcard.IdCardCameraController], each
 * traceable to the locked capture reference (`docs/main-scanner-reference.md`):
 *
 * 1. **Exactly one lifecycle bind, carrying all three use cases, or a controlled failure.**
 *    Preview + ImageCapture + ImageAnalysis attach together in a single [bind] so no later slice has
 *    to rebind (a rebind mid-visit is exactly the camera churn the ID-card work eliminated). If that
 *    bind fails, the attempt is RELEASED and the controller reports
 *    [MainScanCameraState.FAILED] through [onStateChanged] — it never retries with a reduced
 *    configuration. A scanner that silently lacks ImageAnalysis would present working chrome over a
 *    pipeline that cannot seed the crop polygon, and the user would have no way to know; the ML Kit
 *    path is the fallback instead. [MainScanCameraLifecycle] holds these rules as pure, tested
 *    predicates so "no second, degraded bind" is structural rather than a matter of reading this
 *    method carefully.
 * 2. **The shutter is never gated on resolution.** The ID-card flow disables capture until strict
 *    UHD attaches, because a sub-UHD card scan is unusable. A document page is not a card: the
 *    reference exposes an always-armed manual shutter, so this controller reports the attached
 *    resolution for diagnostics and lets every capture proceed.
 * 3. **Detection draws nothing.** [latestSeedState] is written from the analysis thread and read
 *    only at capture time to seed the crop polygon. It is intentionally NOT exposed as a Flow and
 *    no overlay consumes it — the reference preview is clean, with no live quadrilateral.
 *
 * Captured JPEGs are written only into the caller's [outputDirectory], always under the app's own
 * private storage — never external storage, never a `content://` target.
 */
class MainScannerCameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val detectionSession: LiveDetectionSession = LiveDetectionSession(),
    private val targetAnalysisFps: Int = 10
) {
    val owner: CameraSurfaceOwner = CameraSurfaceOwner.MAIN_SCAN_CAPTURE

    /** Stable, process-unique id, greppable across this controller's whole lifecycle. */
    val controllerId: String = "m${NEXT_CONTROLLER_ID.incrementAndGet()}"

    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val frameDropPolicy = FrameDropPolicy(maxInFlight = 1)
    private val frameRateLimiter = FrameRateLimiter(targetAnalysisFps)
    private val previewRecoveryGuard = PreviewRecoveryGuard()

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var boundPreviewView: PreviewView? = null
    private var bindInFlight = false
    private var pendingFlashMode: Int = ImageCapture.FLASH_MODE_OFF

    /**
     * The most recent silent detection result, or null when analysis is unavailable/undetected.
     * Written on the analysis thread, read on the main thread at capture time — hence the atomic.
     *
     * NOT rendered anywhere. Slice 2 adds the sensor→display coordinate mapping this needs before
     * it can address captured pixels, and Slice 4 consumes the mapped quad to seed the crop
     * polygon. Until then this is recorded and left unused, on purpose: the 3-use-case binding it
     * requires is what Slice 1 must prove on real hardware.
     */
    private val latestSeed = AtomicReference<StabilizerState?>(null)

    val latestSeedState: StabilizerState? get() = latestSeed.get()

    /**
     * Initialization state. Starts [MainScanCameraState.BINDING]; the single bind attempt resolves it
     * to READY or FAILED exactly once, and [onStateChanged] fires on the main thread so the surface
     * can present a controlled failure instead of a degraded scanner.
     */
    var cameraState: MainScanCameraState = MainScanCameraState.BINDING
        private set

    var onStateChanged: ((MainScanCameraState) -> Unit)? = null

    private fun publishState(next: MainScanCameraState) {
        if (cameraState == next) return
        cameraState = next
        onStateChanged?.invoke(next)
    }

    /** The still resolution CameraX actually attached, for diagnostics only. Never gates capture. */
    var attachedCaptureWidth: Int? = null
        private set
    var attachedCaptureHeight: Int? = null
        private set

    init {
        if (BuildConfig.DEBUG) {
            Log.d(TAG, CameraOwnershipLog.created(owner, controllerId))
        }
    }

    /**
     * Performs THE bind: one lifecycle bind carrying Preview + ImageCapture + ImageAnalysis.
     *
     * Admitted only while [MainScanCameraLifecycle.allowsBindAttempt] holds, so a resolved camera is
     * never implicitly re-bound and a FAILED one is never given a second attempt. On failure the
     * partially-built use cases are released ([releaseFailedAttempt]) and the state becomes
     * [MainScanCameraState.FAILED] — there is no reduced-configuration retry.
     */
    fun bind(previewView: PreviewView, reason: String = "initial") {
        if (!MainScanCameraLifecycle.allowsBindAttempt(cameraState)) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "MAIN_SCAN_BIND skipped state=$cameraState controller=$controllerId")
            }
            return
        }
        boundPreviewView = previewView
        bindInFlight = true
        if (BuildConfig.DEBUG) {
            Log.d(TAG, CameraOwnershipLog.bind(owner, reason, controllerId))
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            var analysis: ImageAnalysis? = null
            try {
                val provider = runCatching { future.get() }.getOrNull()
                if (provider == null) {
                    Log.w(TAG, "MAIN_SCAN_BIND failed reason=no_provider")
                    publishState(MainScanCameraLifecycle.afterBindAttempt(success = false))
                    return@addListener
                }
                cameraProvider = provider

                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                val capture = buildImageCapture()
                analysis = buildImageAnalysis()

                val bound = runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        capture,
                        analysis
                    )
                }
                if (bound.isFailure) {
                    Log.w(
                        TAG,
                        "MAIN_SCAN_BIND failed reason=three_use_case_bind " +
                            "detail=${bound.exceptionOrNull()?.message}"
                    )
                    releaseFailedAttempt(provider, analysis)
                    publishState(MainScanCameraLifecycle.afterBindAttempt(success = false))
                    return@addListener
                }

                imageCapture = capture
                val attached = capture.resolutionInfo?.resolution
                attachedCaptureWidth = attached?.width
                attachedCaptureHeight = attached?.height
                if (BuildConfig.DEBUG) {
                    Log.d(
                        TAG,
                        "MAIN_SCAN_CONFIG attachedResolution=${attached?.width}x${attached?.height} " +
                            "useCases=preview+imageCapture+imageAnalysis controller=$controllerId"
                    )
                }
                publishState(MainScanCameraLifecycle.afterBindAttempt(success = true))
            } catch (throwable: Throwable) {
                Log.w(TAG, "MAIN_SCAN_BIND failed reason=unexpected detail=${throwable.message}")
                releaseFailedAttempt(cameraProvider, analysis)
                publishState(MainScanCameraLifecycle.afterBindAttempt(success = false))
            } finally {
                bindInFlight = false
                previewRecoveryGuard.onRecoveryComplete()
            }
        }, mainExecutor)
    }

    /**
     * Releases a failed bind attempt so nothing is left half-attached: the analyzer is detached, the
     * provider is unbound, and every reference this attempt created is dropped. Leaves the camera
     * available to other surfaces (notably the ML Kit fallback) rather than holding it open.
     */
    private fun releaseFailedAttempt(provider: ProcessCameraProvider?, analysis: ImageAnalysis?) {
        runCatching { analysis?.clearAnalyzer() }
        runCatching { provider?.unbindAll() }
        imageCapture = null
        boundPreviewView = null
        attachedCaptureWidth = null
        attachedCaptureHeight = null
        latestSeed.set(null)
    }

    /**
     * Highest-available still capture. `PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE` unlocks the
     * camera's high-resolution output list (where the largest stills live on several device
     * families); 4:3 with auto fallback matches the sensor's native document-friendly aspect.
     * Unlike the ID-card controller there is no UHD *requirement* — see the class doc.
     */
    private fun buildImageCapture(): ImageCapture =
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                    .setAllowedResolutionMode(
                        ResolutionSelector.PREFER_HIGHER_RESOLUTION_OVER_CAPTURE_RATE
                    )
                    .build()
            )
            .setFlashMode(pendingFlashMode)
            .build()

    private fun buildImageAnalysis(): ImageAnalysis =
        ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .apply { setAnalyzer(analysisExecutor, ::analyzeFrame) }

    /**
     * Silent analysis: throttle to the target rate, drop if one frame is still in flight, convert
     * the luma plane, fold it into the reused detection pipeline, and store the result. Publishes
     * nothing and triggers no capture — the reference has no auto-capture and no live overlay.
     */
    private fun analyzeFrame(image: ImageProxy) {
        try {
            if (!frameRateLimiter.shouldProcess(System.currentTimeMillis())) return
            if (!frameDropPolicy.tryAcquire()) return
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val bytes = ByteArray(buffer.remaining())
                buffer.get(bytes)
                val frame = YuvLumaConverter.fromLumaPlane(
                    yPlane = bytes,
                    width = image.width,
                    height = image.height,
                    rowStride = plane.rowStride,
                    pixelStride = plane.pixelStride
                )
                val result = detectionSession.process(frame)
                // Store only. `shouldCapture` is intentionally IGNORED: auto-capture is not part of
                // the locked reference, and the shutter is the sole trigger.
                latestSeed.set(result.stabilizerState)
            } finally {
                frameDropPolicy.release()
            }
        } catch (throwable: Throwable) {
            Log.w(TAG, "MAIN_SCAN_ANALYSIS failed: ${throwable.message}")
        } finally {
            image.close()
        }
    }

    /**
     * Captures a full-resolution still into a new file under [outputDirectory], invoking [onResult]
     * exactly once with its [Uri] on success or null on any failure (camera not bound, unusable
     * directory, synchronous submission throw, asynchronous capture error).
     *
     * [onCaptureSubmitted] fires exactly once and ONLY after `takePicture` genuinely reached the
     * camera, via the pure, unit-tested [IdCardCaptureSubmission] ordering — so the shutter sound
     * and the on-screen shutter flash can never fire for a capture that never happened.
     *
     * The preview is NOT stopped, blanked, or unbound here: the reference keeps streaming through
     * capture, and the captured frame becomes visible on the crop surface instead.
     */
    fun capture(
        outputDirectory: File,
        filePrefix: String,
        traceSessionId: Long = -1L,
        traceGeneration: Long = -1L,
        onCaptureSubmitted: () -> Unit = {},
        onResult: (Uri?) -> Unit
    ) {
        if (!MainScanCameraLifecycle.allowsCapture(cameraState)) {
            Log.w(TAG, "MAIN_SCAN_CAPTURE rejected reason=camera_state_$cameraState")
            onResult(null)
            return
        }
        val capture = imageCapture
        if (capture == null) {
            Log.w(TAG, "MAIN_SCAN_CAPTURE rejected reason=not_bound")
            onResult(null)
            return
        }
        if (!outputDirectory.isDirectory) {
            // The directory is created off the main thread when the visit opens, so the shutter path
            // performs no mkdirs. A missing directory here is a genuine failure, not something to
            // repair synchronously on the UI thread.
            Log.w(TAG, "MAIN_SCAN_CAPTURE rejected reason=output_directory_unavailable")
            onResult(null)
            return
        }
        val file = File(outputDirectory, "$filePrefix-${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        // Exactly-once delivery, enforced here rather than assumed of CameraX: a duplicated
        // onImageSaved/onError (or an error arriving after a save) can otherwise publish twice or
        // publish after the surface moved on.
        val delivered = java.util.concurrent.atomic.AtomicBoolean(false)
        fun deliverOnce(uri: Uri?) {
            if (!delivered.compareAndSet(false, true)) {
                Log.w(TAG, "MAIN_SCAN_CALLBACK duplicate=true ignored=true")
                return
            }
            onResult(uri)
        }
        val startedAtMs = System.currentTimeMillis()
        MainScanTrace.takePictureInvoked(traceSessionId, traceGeneration)
        val submitted = IdCardCaptureSubmission.submit(
            submit = {
                capture.takePicture(
                    options,
                    mainExecutor,
                    object : ImageCapture.OnImageSavedCallback {
                        override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                            // Lightweight validation ONLY — an existence check and a length check.
                            // No decode, no EXIF read, no bitmap work happens on this callback.
                            val elapsed = System.currentTimeMillis() - startedAtMs
                            MainScanTrace.imageSavedEntered(elapsed)
                            val exists = file.exists()
                            val length = if (exists) file.length() else 0L
                            MainScanTrace.outputValidated(exists = exists, lengthBytes = length)
                            if (!exists || length == 0L) {
                                // FAIL CLOSED: an empty or missing output must never be published as
                                // a page. Delete the husk and report failure.
                                runCatching { if (exists) file.delete() }
                                deliverOnce(null)
                                return
                            }
                            deliverOnce(Uri.fromFile(file))
                        }

                        override fun onError(exception: ImageCaptureException) {
                            val elapsed = System.currentTimeMillis() - startedAtMs
                            MainScanTrace.errorEntered(elapsed, reason = "image_capture_error")
                            Log.w(TAG, "MAIN_SCAN_CAPTURE failed code=${exception.imageCaptureError}")
                            // CameraX may already have created (and partially written) the target
                            // file. The session ledger never learns about a failed capture, so the
                            // partial is deleted HERE — the orphan is prevented rather than swept.
                            // Safe by construction: this path only ever touches the file this call
                            // just created inside the caller's app-private directory.
                            runCatching { if (file.exists()) file.delete() }
                            deliverOnce(null)
                        }
                    }
                )
            },
            onSubmitted = onCaptureSubmitted,
            onSubmissionError = { throwable ->
                Log.w(TAG, "MAIN_SCAN_CAPTURE submission failed: ${throwable.message}")
            }
        )
        MainScanTrace.takePictureSubmitted(submitted)
        if (!submitted) {
            runCatching { file.delete() }
            deliverOnce(null)
        }
    }

    /**
     * Restores a frozen preview after a failed capture: rebinds only when the PreviewView reports
     * it is not streaming AND no bind is already in flight, at most one attempt at a time. A
     * streaming or currently-binding preview makes this a cheap no-op, so it is safe to call after
     * every capture completion. This is how "never show a blank or stale frame" is kept true on the
     * capture surface itself.
     */
    fun ensurePreviewStreaming() {
        val view = boundPreviewView ?: return
        val streaming = view.previewStreamState.value == PreviewView.StreamState.STREAMING
        // A FAILED or still-BINDING camera never recovers automatically, so a failed three-use-case
        // bind can never be laundered into a second, degraded attempt through this path.
        if (!MainScanCameraLifecycle.allowsAutomaticRecovery(
                state = cameraState,
                previewStreaming = streaming,
                bindInFlight = bindInFlight
            )
        ) {
            return
        }
        if (!previewRecoveryGuard.shouldAttemptRecovery(streaming)) return
        Log.i(TAG, "MAIN_SCAN_RECOVERY rebinding preview (state=${view.previewStreamState.value})")
        // Re-arm the single-bind gate for this ONE deliberate recovery of an already-READY camera.
        cameraState = MainScanCameraState.BINDING
        bind(view, reason = "recovery")
    }

    fun setFlashMode(mode: Int) {
        pendingFlashMode = mode
        imageCapture?.flashMode = mode
    }

    /** Resets the silent detection state — call when a visit ends so no seed leaks across visits. */
    fun resetDetection() {
        detectionSession.reset()
        latestSeed.set(null)
    }

    /**
     * Releases the camera. IDEMPOTENT: a second call is a no-op, so disposal happens exactly once
     * even if a lifecycle event and an explicit teardown both fire. Emits the `released` ownership
     * line only on the call that actually releases, keeping the create/bind/release counts in the
     * evidence exact.
     */
    fun unbind() {
        if (released) {
            MainScanTrace.controllerReleased(controllerId, alreadyReleased = true)
            return
        }
        released = true
        MainScanTrace.controllerReleased(controllerId, alreadyReleased = false)
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
        imageCapture = null
        boundPreviewView = null
        latestSeed.set(null)
        runCatching { analysisExecutor.shutdown() }
        if (BuildConfig.DEBUG) {
            Log.d(TAG, CameraOwnershipLog.released(owner, controllerId))
        }
    }

    private var released = false

    private companion object {
        const val TAG = "MainScanCapture"

        /** Per-process source of stable, unique [controllerId] values. */
        private val NEXT_CONTROLLER_ID = AtomicInteger(0)
    }
}
