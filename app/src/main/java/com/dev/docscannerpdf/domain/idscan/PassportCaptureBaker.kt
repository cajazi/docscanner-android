package com.dev.docscannerpdf.domain.idscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import com.dev.docscannerpdf.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bakes a raw guided passport capture (or imported gallery image) into a tight, PORTRAIT
 * information-page crop before it reaches review/save/PDF. Shares the exact crop mapping the
 * ID-card path uses ([IdCardFrameCropper.computeCropRect], which inverts PreviewView's
 * FILL_CENTER transform) and the same AndroidX-ExifInterface upright correction
 * ([IdScanPostProcessor.rotationDegreesFromExif]) — the ONLY behavioral difference from
 * [IdCardCaptureBaker] is that passport output stays portrait (a passport page is taller than
 * wide) instead of being force-rotated to landscape. The crop is a pixel-for-pixel region
 * extraction: no scaling for camera captures — the baked page is legitimately smaller than the
 * raw UHD capture only because it is a crop.
 *
 * FAIL-CLOSED: every required step (upright decode, EXIF orientation, required guide crop,
 * portrait normalization, compression) returns null on failure rather than silently passing the
 * source through. A caller therefore never receives a URI unless the pixels are genuinely
 * canonical-upright — and the `pixelOrientation=UPRIGHT` log is only ever emitted after the final
 * dimensions are verified portrait. There is no longer any path that yields a raw, un-cropped, or
 * stale-orientation page.
 */
object PassportCaptureBaker {

    private const val TAG = "IdCardCapture"
    private const val JPEG_QUALITY = 92

    /**
     * The longest edge an IMPORTED gallery image is decoded to. Imports can be arbitrarily large
     * (12000px+ phone-gallery panoramas), so they are safely downsampled with `inSampleSize` to
     * cap memory while retaining far more than the final page needs. CAMERA captures are never
     * downsampled — they keep their proven UHD/no-downsample pixels.
     */
    private const val MAX_IMPORT_EDGE = 4096

    /**
     * Bakes a CAMERA [sourceUri] using [frameRect]/[containerSize] to crop to the on-screen
     * portrait guide frame, saving a new JPEG under [outputDirectory]. When a frame + container
     * are supplied the guide crop is REQUIRED: its failure returns null (no un-cropped raw page
     * ever reaches review). Camera pixels are never downsampled. Returns null on any required-step
     * failure.
     */
    suspend fun bakeFromCameraCapture(
        context: Context,
        sourceUri: Uri,
        frameRect: IdCardGuideFrameRect?,
        containerSize: IdCardCaptureContainerSize?,
        outputDirectory: File,
        filePrefix: String
    ): Uri? = bake(
        context = context,
        sourceUri = sourceUri,
        frameRect = frameRect,
        containerSize = containerSize,
        outputDirectory = outputDirectory,
        filePrefix = filePrefix,
        allowDownsample = false
    )

    /**
     * Bakes an IMPORTED gallery passport image (EXIF-corrected upright, no guide-frame crop). The
     * decode is memory-safe (downsampled to [MAX_IMPORT_EDGE]); no camera UHD claim is made for an
     * import. Returns null on any required-step failure — the caller must NOT fall back to the
     * original content URI.
     */
    suspend fun bakeFromImport(
        context: Context,
        sourceUri: Uri,
        outputDirectory: File,
        filePrefix: String
    ): Uri? = bake(
        context = context,
        sourceUri = sourceUri,
        frameRect = null,
        containerSize = null,
        outputDirectory = outputDirectory,
        filePrefix = filePrefix,
        allowDownsample = true
    )

    private suspend fun bake(
        context: Context,
        sourceUri: Uri,
        frameRect: IdCardGuideFrameRect?,
        containerSize: IdCardCaptureContainerSize?,
        outputDirectory: File,
        filePrefix: String,
        allowDownsample: Boolean
    ): Uri? = withContext(Dispatchers.IO) {
        // Stage instrumentation (debug-only, content-free): proves exactly where a sideways or
        // un-cropped result would be introduced. Every stage below fails CLOSED.
        val rawBounds = readBounds(context, sourceUri)
        val exifRotation = IdScanPostProcessor.rotationDegreesFromExif(context, sourceUri)
        if (BuildConfig.DEBUG) {
            Log.i(
                TAG,
                "PASSPORT_CAPTURE_RAW width=${rawBounds?.first} height=${rawBounds?.second} " +
                    "exifRotation=$exifRotation source=${if (allowDownsample) "import" else "camera"}"
            )
        }

        val sampleSize = if (allowDownsample && rawBounds != null) {
            computeInSampleSize(rawBounds.first, rawBounds.second, MAX_IMPORT_EDGE)
        } else {
            1
        }

        // Required: upright decode (memory-safe for imports) with a SINGLE EXIF rotation applied.
        // A failed decode or a failed required rotation returns null — never a stale-orientation
        // image.
        val upright = decodeUpright(context, sourceUri, sampleSize) ?: run {
            if (BuildConfig.DEBUG) Log.w(TAG, "PASSPORT_CAPTURE_FAILED stage=decode_upright")
            return@withContext null
        }
        if (BuildConfig.DEBUG) {
            Log.i(
                TAG,
                "PASSPORT_CAPTURE_ORIENTED width=${upright.width} height=${upright.height} " +
                    "appliedRotation=$exifRotation downsampled=${sampleSize > 1}"
            )
        }

        // Required guide crop when (and only when) a measured frame + container are supplied. Its
        // failure fails the whole bake closed — the raw uncropped page must never reach review.
        val cropRequired = frameRect != null && containerSize != null
        val cropped = if (cropRequired) {
            cropToFrame(upright, frameRect!!, containerSize!!) ?: run {
                if (BuildConfig.DEBUG) Log.w(TAG, "PASSPORT_CAPTURE_FAILED stage=guide_crop")
                upright.recycle()
                return@withContext null
            }
        } else {
            upright
        }

        // Required portrait normalization: a landscape crop is rotated once; a failed rotation
        // fails closed rather than returning a sideways page.
        val canonical = ensurePortrait(cropped) ?: run {
            if (BuildConfig.DEBUG) Log.w(TAG, "PASSPORT_CAPTURE_FAILED stage=ensure_portrait")
            recycleAll(upright, cropped)
            return@withContext null
        }

        // Only NOW — after the pixels are proven non-empty and portrait — may UPRIGHT be claimed.
        if (canonical.width <= 0 || canonical.height <= 0 || canonical.height < canonical.width) {
            if (BuildConfig.DEBUG) {
                Log.w(
                    TAG,
                    "PASSPORT_CAPTURE_FAILED stage=orientation_check " +
                        "width=${canonical.width} height=${canonical.height}"
                )
            }
            recycleAll(upright, cropped, canonical)
            return@withContext null
        }
        if (BuildConfig.DEBUG) {
            Log.i(
                TAG,
                "PASSPORT_CAPTURE_BAKED width=${canonical.width} height=${canonical.height} " +
                    "sourceWidth=${upright.width} sourceHeight=${upright.height} " +
                    "pixelOrientation=UPRIGHT storedExifOrientation=NONE downsampled=${sampleSize > 1}"
            )
        }

        val uri = saveJpeg(canonical, outputDirectory, filePrefix)
        recycleAll(upright, cropped, canonical)
        uri
    }

    /** Header-only bounds read (never decodes the full bitmap) for stage instrumentation. */
    private fun readBounds(context: Context, uri: Uri): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }
        if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }.getOrNull()

    /** Largest power-of-two `inSampleSize` keeping both edges at/under [maxEdge]. */
    private fun computeInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0) return 1
        var sample = 1
        while (width / sample > maxEdge || height / sample > maxEdge) {
            sample *= 2
        }
        return sample
    }

    /**
     * Decodes [sourceUri] at [inSampleSize] and applies the single required EXIF rotation.
     * Returns null on decode failure OR on a required-but-failed rotation — the caller then fails
     * closed instead of surfacing a stale-orientation image.
     */
    private fun decodeUpright(context: Context, sourceUri: Uri, inSampleSize: Int): Bitmap? {
        val decoded = runCatching {
            val options = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize.coerceAtLeast(1) }
            context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        }.getOrNull() ?: return null
        val degrees = IdScanPostProcessor.rotationDegreesFromExif(context, sourceUri)
        if (degrees == 0) return decoded
        val rotated = runCatching {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }.getOrNull()
        if (rotated == null) {
            decoded.recycle()
            return null
        }
        if (rotated !== decoded) decoded.recycle()
        return rotated
    }

    /**
     * Extracts the guide-frame crop. Returns null when the crop rect can't be computed or the
     * region extraction fails, so a required crop failing aborts the bake (no uncropped fallback).
     */
    private fun cropToFrame(
        source: Bitmap,
        frameRect: IdCardGuideFrameRect,
        containerSize: IdCardCaptureContainerSize
    ): Bitmap? {
        val rect = runCatching {
            IdCardFrameCropper.computeCropRect(
                containerWidth = containerSize.width,
                containerHeight = containerSize.height,
                frameLeft = frameRect.left,
                frameTop = frameRect.top,
                frameWidth = frameRect.width,
                frameHeight = frameRect.height,
                imageWidth = source.width,
                imageHeight = source.height
            )
        }.getOrNull() ?: return null
        if (BuildConfig.DEBUG) {
            Log.i(
                TAG,
                "PASSPORT_CAPTURE_CROP left=${rect.left} top=${rect.top} " +
                    "right=${rect.left + rect.width} bottom=${rect.top + rect.height} " +
                    "width=${rect.width} height=${rect.height}"
            )
        }
        return runCatching {
            Bitmap.createBitmap(source, rect.left, rect.top, rect.width, rect.height)
        }.getOrNull()
    }

    /**
     * Guarantees a portrait (taller-than-wide) canonical page. Already-portrait pixels are
     * returned unchanged; a landscape crop is rotated 90° exactly once (never in addition to the
     * EXIF correction). Returns null if that required rotation fails.
     */
    private fun ensurePortrait(source: Bitmap): Bitmap? {
        if (source.height >= source.width) return source
        return runCatching {
            val matrix = Matrix().apply { postRotate(90f) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }.getOrNull()
    }

    /**
     * Compresses [bitmap] to a JPEG under [outputDirectory]. Honors the [Bitmap.compress] return
     * value: a false result (or any thrown error, or a zero-length file) deletes the partial file
     * and returns null, so a URI is never handed back for an empty or failed output.
     */
    private fun saveJpeg(bitmap: Bitmap, outputDirectory: File, filePrefix: String): Uri? {
        if (!(outputDirectory.mkdirs() || outputDirectory.isDirectory)) return null
        val file = File(outputDirectory, "$filePrefix-${System.currentTimeMillis()}.jpg")
        val ok = runCatching {
            file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }
        }.getOrDefault(false)
        if (!ok || !file.exists() || file.length() == 0L) {
            runCatching { file.delete() }
            if (BuildConfig.DEBUG) Log.w(TAG, "PASSPORT_CAPTURE_FAILED stage=compress")
            return null
        }
        return Uri.fromFile(file)
    }

    /** Recycles each DISTINCT bitmap once (aliases from unchanged stages are deduplicated). */
    private fun recycleAll(vararg bitmaps: Bitmap) {
        bitmaps.toSet().forEach { runCatching { it.recycle() } }
    }
}
