package com.dev.docscannerpdf.domain.idscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import com.dev.docscannerpdf.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Relative watermark geometry, shared by the on-screen preview and the baked output so "what
 * you see is what is saved" holds by construction rather than by coincidence. All values are
 * fractions of the image, so they scale identically at any resolution.
 */
object PassportWatermarkGeometry {
    /** Text size as a fraction of the image's shorter edge. */
    const val TEXT_SIZE_FRACTION = 0.085f

    /** Rotation, in degrees, applied around the image centre. */
    const val ROTATION_DEGREES = -30f

    /** Alpha of the drawn text (0..1), matching the preview overlay. */
    const val ALPHA = 0.45f
}

/**
 * Applies the user's watermark text locally to a copy of the image — entirely on-device, no
 * network, no bundled branding, and only when the user explicitly set text. The unwatermarked
 * source file is never modified, so removing/cancelling a watermark always recovers the clean
 * page. Writes into app-private [outputDirectory] only.
 */
object PassportWatermarkRenderer {

    private const val TAG = "IdCardCapture"
    private const val JPEG_QUALITY = 92

    /**
     * [generation] is the caller's async token, echoed into the debug logs so a stale render can
     * be identified in evidence. The logs are debug-only and carry NO image content, document
     * data or file paths — only the generation and the output dimensions.
     */
    suspend fun apply(
        context: Context,
        sourceUri: Uri,
        text: String,
        outputDirectory: File,
        generation: Long = 0L
    ): Uri? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext sourceUri
        if (BuildConfig.DEBUG) {
            Log.i(TAG, "PASSPORT_WATERMARK_STARTED generation=$generation sourceUri=present")
        }
        val source = decode(context, sourceUri)
        if (source == null) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "PASSPORT_WATERMARK_FAILED generation=$generation reason=decode_failed")
            }
            return@withContext null
        }
        var output: Bitmap? = null
        try {
            output = source.copy(Bitmap.Config.ARGB_8888, true)
            if (output == null) {
                if (BuildConfig.DEBUG) {
                    Log.w(TAG, "PASSPORT_WATERMARK_FAILED generation=$generation reason=copy_failed")
                }
                return@withContext null
            }
            val canvas = Canvas(output)
            val shorterEdge = minOf(output.width, output.height).toFloat()
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                alpha = (PassportWatermarkGeometry.ALPHA * 255f).toInt().coerceIn(0, 255)
                textSize = shorterEdge * PassportWatermarkGeometry.TEXT_SIZE_FRACTION
                isFakeBoldText = true
                textAlign = Paint.Align.CENTER
                setShadowLayer(shorterEdge * 0.006f, 0f, 0f, Color.BLACK)
            }
            val centerX = output.width / 2f
            val centerY = output.height / 2f
            canvas.save()
            canvas.rotate(PassportWatermarkGeometry.ROTATION_DEGREES, centerX, centerY)
            // Baseline offset so the text is vertically centred on the image centre.
            val baseline = centerY - (paint.descent() + paint.ascent()) / 2f
            canvas.drawText(text, centerX, baseline, paint)
            canvas.restore()
            val savedUri = save(output, outputDirectory)
            if (BuildConfig.DEBUG) {
                if (savedUri != null) {
                    Log.i(
                        TAG,
                        "PASSPORT_WATERMARK_COMPLETED generation=$generation " +
                            "width=${output.width} height=${output.height}"
                    )
                } else {
                    Log.w(TAG, "PASSPORT_WATERMARK_FAILED generation=$generation reason=save_failed")
                }
            }
            savedUri
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            if (BuildConfig.DEBUG) {
                Log.w(TAG, "PASSPORT_WATERMARK_FAILED generation=$generation reason=exception")
            }
            null
        } finally {
            output?.recycle()
            source.recycle()
        }
    }

    private fun decode(context: Context, uri: Uri): Bitmap? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
    }.getOrNull()

    private fun save(bitmap: Bitmap, outputDirectory: File): Uri? {
        if (!(outputDirectory.mkdirs() || outputDirectory.isDirectory)) return null
        val file = File(outputDirectory, "passport-watermark-${java.util.UUID.randomUUID()}.jpg")
        return try {
            val ok = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            if (ok) Uri.fromFile(file) else { file.delete(); null }
        } catch (throwable: Throwable) {
            file.delete()
            null
        }
    }
}
