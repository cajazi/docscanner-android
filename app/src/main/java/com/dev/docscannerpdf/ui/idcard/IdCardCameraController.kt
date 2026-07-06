package com.dev.docscannerpdf.ui.idcard

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File

/**
 * Drives the guided ID-card capture screen's camera: a CameraX preview plus a still-image
 * [ImageCapture] use case. Captured JPEGs are written only into [outputDirectory] — always a
 * directory under the app's own private storage (e.g. `context.filesDir`), never external
 * storage or a content URI requiring extra permissions.
 */
class IdCardCameraController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null

    /** Binds preview + still-capture to the camera lifecycle. */
    fun bind(previewView: PreviewView) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = runCatching { future.get() }.getOrNull() ?: return@addListener
            cameraProvider = provider

            val preview = Preview.Builder().build().apply {
                setSurfaceProvider(previewView.surfaceProvider)
            }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
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
            }.onFailure { Log.w(TAG, "Unable to bind camera: ${it.message}") }
        }, mainExecutor)
    }

    /**
     * Captures a still image into a new file under [outputDirectory] (created if needed) and
     * invokes [onResult] with its [Uri] on success, or null on failure/if the camera isn't bound
     * yet.
     */
    fun capture(outputDirectory: File, filePrefix: String, onResult: (Uri?) -> Unit) {
        val capture = imageCapture
        if (capture == null) {
            onResult(null)
            return
        }
        if (!outputDirectory.exists()) outputDirectory.mkdirs()
        val file = File(outputDirectory, "$filePrefix-${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
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
    }

    fun unbind() {
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
        imageCapture = null
    }

    private companion object {
        const val TAG = "IdCardCameraController"
    }
}
