package com.dev.docscannerpdf.ui.mainscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import com.dev.docscannerpdf.domain.idscan.IdScanPostProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A decoded, EXIF-upright capture plus the dimensions the crop polygon is normalized against. */
data class MainScanWorkingImage(
    val bitmap: Bitmap,
    val width: Int,
    val height: Int
)

/**
 * Decodes the captured JPEG into an upright working bitmap for the crop editor.
 *
 * Two things make this more than a `decodeStream` call:
 *
 * 1. **EXIF.** Camera JPEGs are very commonly stored in sensor orientation with the rotation carried
 *    only in EXIF, and `BitmapFactory` ignores it. Decoding without applying it gives a sideways
 *    editor, and — far worse — a crop polygon expressed against the wrong axes, so the saved page
 *    would be cropped somewhere the user never indicated. The rotation is applied ONCE here, and
 *    every downstream coordinate is normalized against the resulting upright bitmap.
 * 2. **Bounded memory.** The still is 4080x3060 (~50 MB as ARGB_8888). Holding that for an
 *    interactive editor risks an OOM on a mid-range device, so it is downsampled to a bound that
 *    still comfortably exceeds both the screen and the magnifier's needs.
 *
 * The original file on disk is never modified — this produces a working copy for display only, and
 * the authoritative crop is applied to the original in a later stage.
 */
object MainScanCaptureImageLoader {

    /**
     * Longest edge of the working bitmap. Chosen so a 2.6x loupe on a 1080px-wide screen still
     * samples real captured detail rather than upscaled mush, while keeping the bitmap around
     * 16 MB rather than 50 MB.
     */
    const val MAX_WORKING_EDGE = 2048

    /**
     * Loads [sourceUri] as an upright working image, or null when it cannot be decoded. Runs
     * entirely on [Dispatchers.IO]: full-resolution decode and rotation must never touch the UI
     * thread.
     */
    suspend fun load(context: Context, sourceUri: Uri): MainScanWorkingImage? =
        withContext(Dispatchers.IO) {
            val bounds = readBounds(context, sourceUri)
            val sampleSize = bounds?.let {
                computeInSampleSize(it.first, it.second, MAX_WORKING_EDGE)
            } ?: 1

            val decoded = runCatching {
                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize.coerceAtLeast(1)
                }
                context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                }
            }.getOrNull() ?: return@withContext null

            val degrees = IdScanPostProcessor.rotationDegreesFromExif(context, sourceUri)
            val upright = if (degrees == 0) {
                decoded
            } else {
                val rotated = runCatching {
                    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
                    Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
                }.getOrNull()
                if (rotated == null) {
                    // A failed rotation must not surface a sideways page: fail closed instead.
                    decoded.recycle()
                    return@withContext null
                }
                if (rotated !== decoded) decoded.recycle()
                rotated
            }

            MainScanWorkingImage(
                bitmap = upright,
                width = upright.width,
                height = upright.height
            )
        }

    /** Header-only bounds read — never decodes pixels. */
    private fun readBounds(context: Context, uri: Uri): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        if (options.outWidth > 0 && options.outHeight > 0) {
            options.outWidth to options.outHeight
        } else {
            null
        }
    }.onFailure { Log.w(TAG, "MAIN_SCAN_DECODE bounds failed: ${it.message}") }.getOrNull()

    /** Largest power-of-two sample size keeping both edges at or under [maxEdge]. */
    fun computeInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        var sample = 1
        while (width / sample > maxEdge || height / sample > maxEdge) {
            sample *= 2
        }
        return sample
    }

    private const val TAG = "MainScanCapture"
}
