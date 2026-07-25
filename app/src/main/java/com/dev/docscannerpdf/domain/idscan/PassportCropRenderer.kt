package com.dev.docscannerpdf.domain.idscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.dev.docscannerpdf.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Renders a rectangular crop of the passport base image into a NEW app-private JPEG. Fail-closed
 * throughout (mirrors [PassportCaptureBaker]): a decode, crop, or compress failure returns null so
 * the caller never publishes a bad crop, and an incomplete output file is deleted. The source is
 * decoded with an OOM-safe subsample for very large images while keeping ample resolution for the
 * cropped page. Debug logs carry NO paths, image content, or personal data.
 */
object PassportCropRenderer {

    private const val TAG = "IdCardCapture"
    private const val JPEG_QUALITY = 95

    /** Cap on the decoded source's longer edge; large gallery imports subsample down to this. */
    private const val MAX_SOURCE_LONG_EDGE = 4096

    /**
     * Crops [sourceUri] to the normalized [crop] rectangle and writes the result under
     * [outputDirectory] (app-private). Returns the output [Uri], or null on any failure.
     */
    suspend fun crop(
        context: Context,
        sourceUri: Uri,
        crop: PassportCropRect,
        outputDirectory: File
    ): Uri? = withContext(Dispatchers.IO) {
        val source = decodeSafely(context, sourceUri)
        if (source == null) {
            if (BuildConfig.DEBUG) Log.w(TAG, "PASSPORT_CROP_FAILED stage=decode")
            return@withContext null
        }
        var cropped: Bitmap? = null
        try {
            val pixels = PassportCropMapping.toSourcePixels(crop, source.width, source.height)
            if (pixels.width <= 0 || pixels.height <= 0) {
                if (BuildConfig.DEBUG) Log.w(TAG, "PASSPORT_CROP_FAILED stage=empty_bounds")
                return@withContext null
            }
            cropped = try {
                Bitmap.createBitmap(source, pixels.left, pixels.top, pixels.width, pixels.height)
            } catch (throwable: IllegalArgumentException) {
                if (BuildConfig.DEBUG) Log.w(TAG, "PASSPORT_CROP_FAILED stage=create")
                null
            } ?: return@withContext null

            val output = saveJpeg(cropped, outputDirectory)
            if (output == null && BuildConfig.DEBUG) {
                Log.w(TAG, "PASSPORT_CROP_FAILED stage=save")
            }
            output
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            if (BuildConfig.DEBUG) Log.w(TAG, "PASSPORT_CROP_FAILED stage=exception")
            null
        } finally {
            if (cropped != null && cropped != source) cropped.recycle()
            source.recycle()
        }
    }

    private fun decodeSafely(context: Context, uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (longEdge / sample > MAX_SOURCE_LONG_EDGE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        openStream(context, uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    private fun openStream(context: Context, uri: Uri) = when (uri.scheme) {
        "content" -> context.contentResolver.openInputStream(uri)
        else -> uri.path?.let { File(it).inputStream() }
    }

    private fun saveJpeg(bitmap: Bitmap, outputDirectory: File): Uri? {
        if (!(outputDirectory.mkdirs() || outputDirectory.isDirectory)) return null
        val file = File(outputDirectory, "passport-crop-${java.util.UUID.randomUUID()}.jpg")
        return try {
            val ok = file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            // Fail closed: never return a URI for a failed compress or an empty file.
            if (ok && file.length() > 0L) {
                if (BuildConfig.DEBUG) {
                    Log.i(TAG, "PASSPORT_CROP_SAVED width=${bitmap.width} height=${bitmap.height}")
                }
                Uri.fromFile(file)
            } else {
                file.delete()
                null
            }
        } catch (throwable: Throwable) {
            file.delete()
            null
        }
    }
}
