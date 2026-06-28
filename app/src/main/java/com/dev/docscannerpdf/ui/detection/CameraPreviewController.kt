package com.dev.docscannerpdf.ui.detection

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.dev.docscannerpdf.domain.detection.FrameDropPolicy
import com.dev.docscannerpdf.domain.detection.FrameRateLimiter
import com.dev.docscannerpdf.domain.detection.LiveDetectionSession
import com.dev.docscannerpdf.domain.detection.LiveDetectionUiState
import com.dev.docscannerpdf.domain.detection.YuvLumaConverter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Drives the live scanning loop with CameraX:
 *
 *     CameraX ImageAnalysis -> YuvLumaConverter -> LiveDetectionSession -> uiState / capture
 *
 * Analysis runs on a dedicated single-thread executor (never the main thread). Backpressure is
 * handled two ways: CameraX keeps only the latest frame, and a [FrameDropPolicy] drops any frame
 * that arrives while one is still being analyzed, so a backlog can never form. A [FrameRateLimiter]
 * caps work at ~[targetFps]. The detection engine itself is reused unchanged — this class only
 * feeds it.
 */
class CameraPreviewController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val session: LiveDetectionSession = LiveDetectionSession(),
    private val targetFps: Int = 12,
    private val onReadyToCapture: () -> Unit = {}
) {
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val frameDropPolicy = FrameDropPolicy(maxInFlight = 1)
    private val frameRateLimiter = FrameRateLimiter(targetFps)
    private val mainExecutor = ContextCompat.getMainExecutor(context)

    private var cameraProvider: ProcessCameraProvider? = null

    private val _uiState = MutableStateFlow(LiveDetectionUiState())
    val uiState: StateFlow<LiveDetectionUiState> = _uiState.asStateFlow()

    /** Binds preview + analysis to the camera and starts streaming frames into the pipeline. */
    fun bind(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            cameraProvider = provider

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .apply { setAnalyzer(analysisExecutor, ::analyzeFrame) }

            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis
                )
            }.onFailure { Log.w(TAG, "Unable to bind camera: ${it.message}") }
        }, mainExecutor)
    }

    fun unbind() {
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
    }

    /** Releases the analysis thread. Call when the controller is no longer needed. */
    fun shutdown() {
        unbind()
        analysisExecutor.shutdown()
    }

    private fun analyzeFrame(image: ImageProxy) {
        try {
            // Throttle to target FPS, then drop if an analysis is already in flight.
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
                val result = session.process(frame)
                _uiState.value = result.uiState
                if (result.shouldCapture) {
                    mainExecutor.execute { onReadyToCapture() }
                }
            } finally {
                frameDropPolicy.release()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Frame analysis failed: ${t.message}")
        } finally {
            image.close()
        }
    }

    private companion object {
        const val TAG = "CameraPreviewController"
    }
}
