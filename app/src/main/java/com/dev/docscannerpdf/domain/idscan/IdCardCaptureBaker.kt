package com.dev.docscannerpdf.domain.idscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Pixel-space rect of the on-screen ID-card guide frame, in the same coordinate space as [IdCardCaptureContainerSize]. */
data class IdCardGuideFrameRect(val left: Float, val top: Float, val width: Float, val height: Float)

/** Pixel size of the camera preview container the guide frame was drawn over. */
data class IdCardCaptureContainerSize(val width: Float, val height: Float)

/**
 * Bakes a raw guided-capture photo (or an imported gallery image) down into a tight, landscape,
 * ID-card-shaped bitmap before it ever reaches the review screen or the exported PDF — fixing the
 * "portrait photo letterboxed into a landscape slot" bug by actually cropping to the on-screen
 * guide frame instead of relying on best-effort document-edge detection.
 *
 * EXIF orientation is corrected first so the decoded bitmap is upright in the same frame of
 * reference the live camera preview (and therefore the on-screen guide frame) was shown in, then
 * [IdCardFrameCropper] maps the guide frame into that bitmap's pixel space. As a final safety net
 * — e.g. for imported gallery images with no associated guide frame — the result is rotated 90°
 * whenever it would otherwise come out portrait, so the baked output is always landscape.
 */
object IdCardCaptureBaker {

    /**
     * Bakes [sourceUri] using [frameRect]/[containerSize] to crop to the guide frame, and saves
     * the result as a new JPEG under [outputDirectory]. Crops to the full upright image instead of
     * failing whenever [frameRect]/[containerSize] are unknown (e.g. layout hasn't measured yet
     * on a very fast tap), so capture is never blocked; returns null only on an outright
     * decode/save failure.
     */
    suspend fun bakeFromCameraCapture(
        context: Context,
        sourceUri: Uri,
        frameRect: IdCardGuideFrameRect?,
        containerSize: IdCardCaptureContainerSize?,
        outputDirectory: File,
        filePrefix: String
    ): Uri? = withContext(Dispatchers.IO) {
        val upright = decodeUpright(context, sourceUri) ?: return@withContext null
        val cropped = if (frameRect != null && containerSize != null) {
            cropToFrame(upright, frameRect, containerSize)
        } else {
            upright
        }
        val landscape = ensureLandscape(cropped)
        val uri = saveJpeg(landscape, outputDirectory, filePrefix)
        // Bitmap has no value equals/hashCode, so this set dedupes by reference identity —
        // every distinct intermediate bitmap gets recycled exactly once.
        setOf(upright, cropped, landscape).forEach { it.recycle() }
        uri
    }

    /**
     * Bakes an imported gallery image ([sourceUri]) with no associated on-screen guide frame:
     * corrects EXIF orientation and only applies the landscape safety-rotation, since there is no
     * reliable frame to crop to for an arbitrary imported photo.
     */
    suspend fun bakeFromImport(
        context: Context,
        sourceUri: Uri,
        outputDirectory: File,
        filePrefix: String
    ): Uri? = bakeFromCameraCapture(
        context = context,
        sourceUri = sourceUri,
        frameRect = null,
        containerSize = null,
        outputDirectory = outputDirectory,
        filePrefix = filePrefix
    )

    private fun decodeUpright(context: Context, sourceUri: Uri): Bitmap? {
        val decoded = runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull() ?: return null
        val degrees = IdScanPostProcessor.rotationDegreesFromExif(context, sourceUri)
        if (degrees == 0) return decoded
        return runCatching {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (rotated !== decoded) decoded.recycle()
            rotated
        }.getOrDefault(decoded)
    }

    private fun cropToFrame(
        source: Bitmap,
        frameRect: IdCardGuideFrameRect,
        containerSize: IdCardCaptureContainerSize
    ): Bitmap {
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
        }.getOrNull() ?: return source
        return runCatching {
            Bitmap.createBitmap(source, rect.left, rect.top, rect.width, rect.height)
        }.getOrDefault(source)
    }

    private fun ensureLandscape(source: Bitmap): Bitmap {
        if (source.width >= source.height) return source
        return runCatching {
            val matrix = Matrix().apply { postRotate(90f) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }.getOrDefault(source)
    }

    private fun saveJpeg(bitmap: Bitmap, outputDirectory: File, filePrefix: String): Uri? = runCatching {
        if (!outputDirectory.exists()) outputDirectory.mkdirs()
        val file = File(outputDirectory, "$filePrefix-${System.currentTimeMillis()}.jpg")
        file.outputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, output)
        }
        Uri.fromFile(file)
    }.getOrNull()
}
