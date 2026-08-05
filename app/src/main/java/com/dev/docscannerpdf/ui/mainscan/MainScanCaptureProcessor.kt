package com.dev.docscannerpdf.ui.mainscan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import androidx.core.graphics.createBitmap
import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import com.dev.docscannerpdf.domain.crop.PerspectiveTransformEngine
import com.dev.docscannerpdf.domain.mainscan.MainScanDocumentFinder
import com.dev.docscannerpdf.domain.filter.DocumentFilter
import com.dev.docscannerpdf.domain.filter.DocumentFilterPrimitives
import com.dev.docscannerpdf.ui.detection.LumaFrameFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The Android side of the Main Scanner's crop and enhancement stages. Every method suspends onto
 * [Dispatchers.Default] or [Dispatchers.IO] — full-resolution decode, warping and filtering must
 * never run on the UI thread, and the surfaces above stay responsive with the page still on screen.
 *
 * All geometry comes from the tested pure layer ([PerspectiveTransformEngine],
 * [PerspectiveGeometry]); this file only feeds matrices into a Canvas and writes files. Outputs go
 * exclusively into app-private directories supplied by the caller.
 */
object MainScanCaptureProcessor {

    private const val TAG = "MainScanCapture"
    private const val JPEG_QUALITY = 92

    /**
     * Re-runs document detection on the CAPTURED image. Used only when the live seed could not be
     * proven to map onto the still, so the common path never pays for it.
     *
     * Returns the detected quad in normalized captured-image coordinates, or null when nothing
     * plausible was found — the caller then falls back to full frame rather than guessing.
     */
    suspend fun detectOnCapture(bitmap: Bitmap): PerspectiveQuad? =
        withContext(Dispatchers.Default) {
            runCatching {
                val frame = LumaFrameFactory.fromBitmap(bitmap)
                MainScanDocumentFinder.find(frame)
            }.onFailure {
                Log.w(TAG, "MAIN_SCAN_STILL_DETECT failed: ${it.message}")
            }.getOrNull()
        }

    /**
     * Rotates the working bitmap a quarter turn so the displayed page turns with the polygon. The
     * crop editor's Left/Right must move BOTH — rotating only the polygon would leave the user
     * dragging corners that no longer sit on the document.
     *
     * Returns a new bitmap; the caller owns recycling the old one once Compose has stopped drawing it.
     */
    suspend fun rotate(bitmap: Bitmap, clockwise: Boolean): Bitmap? =
        withContext(Dispatchers.Default) {
            runCatching {
                val matrix = Matrix().apply { postRotate(if (clockwise) 90f else 270f) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }.onFailure {
                Log.w(TAG, "MAIN_SCAN_ROTATE failed: ${it.message}")
            }.getOrNull()
        }

    /**
     * Applies the user's polygon as a genuine perspective correction: the quad is de-skewed onto a
     * rectangle sized from its own edge lengths, so a page photographed at an angle comes out square
     * rather than merely cropped.
     *
     * The source bitmap is never modified — the result is a new bitmap, and the original capture
     * file on disk is untouched, so a failure here can never cost the user their photograph.
     */
    suspend fun perspectiveCrop(source: Bitmap, normalizedQuad: PerspectiveQuad): Bitmap? =
        withContext(Dispatchers.Default) {
            val quad = PerspectiveGeometry.normalize(normalizedQuad)
            if (!PerspectiveGeometry.isValid(quad)) {
                Log.w(TAG, "MAIN_SCAN_CROP rejected reason=invalid_quad")
                return@withContext null
            }
            runCatching {
                val plan = PerspectiveTransformEngine.plan(quad, source.width, source.height)
                // KTX `createBitmap` — the form lint prefers; identical behaviour.
                val output = createBitmap(plan.outputWidth, plan.outputHeight)
                val canvas = Canvas(output)
                canvas.drawColor(Color.WHITE)
                val matrix = Matrix().apply { setValues(plan.matrix.toFloatArray()) }
                canvas.drawBitmap(
                    source,
                    matrix,
                    Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
                )
                output
            }.onFailure {
                Log.w(TAG, "MAIN_SCAN_CROP failed: ${it.message}")
            }.getOrNull()
        }

    /**
     * Applies [filter] to [source], returning a NEW bitmap and never mutating the input.
     *
     * Always rendering from the untouched perspective-cropped page is what keeps filtering
     * non-destructive: re-applying a filter to an already-filtered image compounds sharpening and
     * contrast until the page is unusable, and there is no way back. ORIGINAL is a defensive copy
     * so the caller can recycle uniformly.
     */
    suspend fun applyFilter(source: Bitmap, filter: DocumentFilter): Bitmap? =
        withContext(Dispatchers.Default) {
            runCatching {
                if (filter == DocumentFilter.ORIGINAL || filter.isIdentity) {
                    return@runCatching source.copy(
                        source.config ?: Bitmap.Config.ARGB_8888,
                        false
                    )
                }
                var current: Bitmap = source
                filter.toneLut?.let { lut ->
                    val next = DocumentFilterPrimitives.applyToneLut(current, lut)
                    if (next !== current && current !== source) current.recycle()
                    current = next
                }
                filter.colorMatrix?.let { matrix ->
                    val next = DocumentFilterPrimitives.applyColorMatrix(current, matrix)
                    if (next !== current && current !== source) current.recycle()
                    current = next
                }
                if (filter.sharpenStrength > 0f) {
                    val next = DocumentFilterPrimitives.applySharpen(current, filter.sharpenStrength)
                    if (next !== current && current !== source) current.recycle()
                    current = next
                }
                val owned = current.copy(current.config ?: Bitmap.Config.ARGB_8888, false)
                if (owned !== current && current !== source) current.recycle()
                owned
            }.onFailure {
                Log.w(TAG, "MAIN_SCAN_FILTER failed: ${it.message}")
            }.getOrNull()
        }

    /**
     * Writes [bitmap] as a JPEG under [outputDirectory] (app-private). Honors the compress return
     * value: a false result, a throw, or a zero-length file deletes the partial and returns null, so
     * a URI is never handed back for an empty output.
     */
    suspend fun writeJpeg(
        bitmap: Bitmap,
        outputDirectory: File,
        filePrefix: String
    ): Uri? = withContext(Dispatchers.IO) {
        if (!(outputDirectory.mkdirs() || outputDirectory.isDirectory)) {
            Log.w(TAG, "MAIN_SCAN_WRITE rejected reason=directory_unavailable")
            return@withContext null
        }
        val file = File(outputDirectory, "$filePrefix-${System.currentTimeMillis()}.jpg")
        val ok = runCatching {
            file.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
        }.getOrDefault(false)
        if (!ok || !file.exists() || file.length() == 0L) {
            runCatching { file.delete() }
            Log.w(TAG, "MAIN_SCAN_WRITE failed reason=compress")
            return@withContext null
        }
        Uri.fromFile(file)
    }
}
