package com.dev.docscannerpdf.domain.idscan

import android.content.Context
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** What one raw CameraX capture actually contained, read from the file's metadata only. */
data class RawCaptureInfo(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val sizeBytes: Long
) {
    /** The strict orientation-independent UHD floor — see [meetsUhdCaptureRequirement]. */
    val meetsUhd: Boolean get() = meetsUhdCaptureRequirement(width, height)
}

/**
 * Runtime PROOF of capture resolution: reads the raw CameraX JPEG's actual pixel bounds
 * (header-only decode via `inJustDecodeBounds` — the full bitmap is never decoded here), its
 * EXIF rotation, and its file size, then logs one deterministic entry:
 *
 * `ID_CARD_CAPTURE_RAW width=4000 height=3000 rotation=90 sizeBytes=… meetsUhd=true`
 *
 * The guided capture screen gates on [RawCaptureInfo.meetsUhd] BEFORE guide-frame baking — a
 * selector preference alone proves nothing about what the device really produced.
 */
object IdCardRawCaptureInspector {

    private const val TAG = "IdCardCapture"

    suspend fun inspect(context: Context, rawUri: Uri): RawCaptureInfo? = withContext(Dispatchers.IO) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // NOTE: with inJustDecodeBounds the decode call ALWAYS returns null by API contract —
        // success is judged by the stream opening and by bounds.outWidth/outHeight being
        // populated, never by the decode's return value. (Judging the return value made this
        // inspector reject EVERY capture as unreadable, blocking the whole flow with a false
        // "below 4K" error.)
        val streamRead = runCatching {
            context.contentResolver.openInputStream(rawUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
                true
            } ?: false
        }.getOrDefault(false)
        if (!streamRead) {
            Log.w(TAG, "ID_CARD_CAPTURE_RAW unreadable reason=stream_open_failed")
            return@withContext null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "ID_CARD_CAPTURE_RAW unreadable reason=no_bounds")
            return@withContext null
        }

        val exifOrientation = runCatching {
            when (rawUri.scheme) {
                "file", null, "" -> rawUri.path?.let { ExifInterface(it) }
                else -> context.contentResolver.openInputStream(rawUri)?.use { ExifInterface(it) }
            }?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val sizeBytes = runCatching { rawUri.path?.let { File(it).length() } }.getOrNull() ?: 0L

        val info = RawCaptureInfo(
            width = bounds.outWidth,
            height = bounds.outHeight,
            rotationDegrees = ExifOrientationDegrees.degreesFor(exifOrientation),
            sizeBytes = sizeBytes
        )
        Log.i(
            TAG,
            "ID_CARD_CAPTURE_RAW width=${info.width} height=${info.height} " +
                "rotation=${info.rotationDegrees} sizeBytes=${info.sizeBytes} meetsUhd=${info.meetsUhd}"
        )
        info
    }
}
