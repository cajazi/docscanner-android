package com.dev.docscannerpdf.domain.idscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.media.ExifInterface
import android.net.Uri
import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import com.dev.docscannerpdf.domain.crop.PerspectiveTransformEngine
import com.dev.docscannerpdf.domain.detection.DocumentEdgeDetector
import com.dev.docscannerpdf.ui.detection.LumaFrameFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/**
 * Post-processes an already ML-Kit-cropped ID scan for CamScanner-level readability. This never
 * replaces the ML Kit crop or touches the scanner/OCR/export pipelines themselves — it sits
 * purely as an extra, best-effort enhancement step on the bitmap ML Kit already produced:
 *
 * 1. A second, local edge pass ([DocumentEdgeDetector] — the same deterministic, gradient/
 *    contrast-based detector the live scanner uses) looks for a tighter, better-aligned card
 *    boundary *inside* the existing crop, and perspective-warps to it if found with confidence.
 * 2. A mild contrast boost and a light sharpen convolution improve readability without visibly
 *    altering the image.
 *
 * Every step is guarded to fail safe: if refinement can't confidently find a tighter boundary,
 * or any step throws, processing falls back to the original ML Kit crop untouched. Pure/offline
 * — no network calls, no new permissions, no long-running background work.
 */
object IdScanPostProcessor {

    data class Config(
        /** Attempt the second local edge-detection + perspective-correction pass at all. */
        val edgeRefinementEnabled: Boolean = true,
        /** >1f increases contrast; 1.12f reads as a mild, non-destructive bump. */
        val contrastBoost: Float = 1.12f,
        /** 0f disables sharpening; ~0.15-0.25 is a mild unsharp-style boost. */
        val sharpenStrength: Float = 0.18f,
        /** Skip refinement if the detected quad already covers this much of the frame or more
         *  (the ML Kit crop is already tight — nothing meaningful left to refine). */
        val maxQuadCoverageToRefine: Float = 0.85f,
        /** Skip refinement below this detector confidence rather than risk a worse crop. */
        val minConfidenceToRefine: Float = 0.55f
    )

    /**
     * Runs the full enhancement pipeline on [source] and returns a new, enhanced [Bitmap].
     * [source] itself is never recycled or mutated — the caller keeps ownership of it. Never
     * throws: any internal failure simply skips that step and continues with the best bitmap
     * produced so far.
     */
    fun process(source: Bitmap, config: Config = Config()): Bitmap {
        if (source.width <= 0 || source.height <= 0) return source

        val refined = if (config.edgeRefinementEnabled) {
            refineCrop(source, config) ?: source
        } else {
            source
        }

        val enhanced = applyEnhancements(refined, config)
        if (refined !== source) refined.recycle()
        return enhanced
    }

    /**
     * URI convenience overload for [ScannerViewModel][com.dev.docscannerpdf.presentation.ScannerViewModel]
     * callers: decodes [sourceUri], corrects its orientation using the file's own EXIF tag (see
     * [rotationDegreesFromExif] — camera/gallery JPEGs are very commonly stored in the sensor's
     * native orientation with an EXIF tag saying how much to rotate for display, and plain
     * [BitmapFactory] decoding never applies that tag itself), runs [process] on the now-upright
     * bitmap, saves the result as a private-storage JPEG under [outputDirectory] (created if
     * needed — always inside the app's own sandbox, never external storage), and returns its file
     * [Uri]. Returns null on any failure (bad URI, decode failure, save failure) so callers can
     * fall back to the original [sourceUri].
     */
    suspend fun processUri(
        context: Context,
        sourceUri: Uri,
        outputDirectory: File,
        config: Config = Config()
    ): Uri? = withContext(Dispatchers.IO) {
        val decoded = runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull() ?: return@withContext null

        val orientationDegrees = rotationDegreesFromExif(context, sourceUri)
        val source = rotateBitmap(decoded, orientationDegrees)

        val enhanced = runCatching { process(source, config) }.getOrNull()
        if (source !== enhanced) source.recycle()
        if (enhanced == null) return@withContext null

        try {
            runCatching {
                if (!outputDirectory.exists()) outputDirectory.mkdirs()
                val file = File(outputDirectory, "id-scan-enhanced-${System.currentTimeMillis()}.jpg")
                file.outputStream().use { output ->
                    enhanced.compress(Bitmap.CompressFormat.JPEG, 92, output)
                }
                Uri.fromFile(file)
            }.getOrNull()
        } finally {
            enhanced.recycle()
        }
    }

    /**
     * Reads [uri]'s EXIF `Orientation` tag (falling back to "normal"/no rotation on any failure —
     * a missing or malformed tag must never crash the scan flow) and maps it through the pure
     * [ExifOrientationDegrees] to a clockwise rotation in degrees.
     */
    fun rotationDegreesFromExif(context: Context, uri: Uri): Int = runCatching {
        val exif = when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.let { ExifInterface(it) }
            else -> context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) }
        }
        val tag = exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            ?: ExifInterface.ORIENTATION_NORMAL
        ExifOrientationDegrees.degreesFor(tag)
    }.getOrDefault(0)

    /**
     * Rotates [source] clockwise by [degrees] (a fresh bitmap; [source] is recycled when a new
     * one is produced) or returns [source] unchanged when [degrees] is 0.
     */
    private fun rotateBitmap(source: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return source
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source) source.recycle()
        return rotated
    }

    /**
     * User-driven manual rotation: decodes [sourceUri] (no further enhancement/refinement — the
     * image has typically already been through [processUri] once), rotates it clockwise by
     * [degrees], and saves the result as a new private-storage JPEG under [outputDirectory].
     * Used to bake in a rotation the user explicitly requested on the ID-card review screen, since
     * neither the Document Ready preview nor the exported PDF apply any rotation transform of
     * their own — the saved pixels must already be correctly oriented. Returns null on failure so
     * callers can fall back to the un-rotated image.
     */
    suspend fun rotateAndSave(
        context: Context,
        sourceUri: Uri,
        degrees: Int,
        outputDirectory: File
    ): Uri? = withContext(Dispatchers.IO) {
        if (degrees == 0) return@withContext sourceUri
        val decoded = runCatching {
            context.contentResolver.openInputStream(sourceUri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        }.getOrNull() ?: return@withContext null

        val rotated = rotateBitmap(decoded, degrees)
        try {
            runCatching {
                if (!outputDirectory.exists()) outputDirectory.mkdirs()
                val file = File(outputDirectory, "id-scan-rotated-${System.currentTimeMillis()}.jpg")
                file.outputStream().use { output ->
                    rotated.compress(Bitmap.CompressFormat.JPEG, 92, output)
                }
                Uri.fromFile(file)
            }.getOrNull()
        } finally {
            rotated.recycle()
        }
    }

    /**
     * Looks for a tighter card boundary inside the already-cropped [source] and perspective-warps
     * to it. Returns null (leaving the ML Kit crop untouched) whenever no confident, meaningfully
     * tighter boundary is found, or the warp itself fails for any reason.
     */
    private fun refineCrop(source: Bitmap, config: Config): Bitmap? {
        val detected = runCatching {
            DocumentEdgeDetector.detect(LumaFrameFactory.fromBitmap(source))
        }.getOrNull() ?: return null

        if (detected.confidence < config.minConfidenceToRefine) return null
        val coverage = abs(PerspectiveGeometry.signedArea(detected.quad))
        if (coverage >= config.maxQuadCoverageToRefine) return null

        return runCatching {
            val plan = PerspectiveTransformEngine.plan(detected.quad, source.width, source.height)
            val output = Bitmap.createBitmap(plan.outputWidth, plan.outputHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawColor(Color.WHITE)
            val matrix = Matrix().apply { setValues(plan.matrix.toFloatArray()) }
            canvas.drawBitmap(source, matrix, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
            output
        }.getOrNull()
    }

    /** Mild contrast boost then a light sharpen — each step produces a fresh, non-destructive bitmap. */
    private fun applyEnhancements(source: Bitmap, config: Config): Bitmap {
        val contrasted = applyContrast(source, config.contrastBoost)
        if (config.sharpenStrength <= 0f) return contrasted
        val sharpened = applySharpen(contrasted, config.sharpenStrength)
        if (sharpened !== contrasted) contrasted.recycle()
        return sharpened
    }

    private fun applyContrast(source: Bitmap, amount: Float): Bitmap {
        val translate = (-0.5f * amount + 0.5f) * 255f
        val matrix = ColorMatrix(
            floatArrayOf(
                amount, 0f, 0f, 0f, translate,
                0f, amount, 0f, 0f, translate,
                0f, 0f, amount, 0f, translate,
                0f, 0f, 0f, 1f, 0f
            )
        )
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(matrix)
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    /**
     * Lightweight unsharp-mask-style 3x3 convolution over bulk pixel arrays (no per-pixel
     * getPixel/setPixel calls, no native/RenderScript dependency) — cheap and deterministic
     * enough to run inline on a single scan's image, and bounded to 0..255 per channel so a mild
     * [strength] can never overflow into visible artifacts.
     */
    private fun applySharpen(source: Bitmap, strength: Float): Bitmap {
        val width = source.width
        val height = source.height
        if (width < 3 || height < 3) return source

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val output = IntArray(pixels.size)

        val center = 1f + 4f * strength
        val edge = -strength

        for (y in 0 until height) {
            for (x in 0 until width) {
                val idx = y * width + x
                if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
                    output[idx] = pixels[idx]
                    continue
                }
                val p = pixels[idx]
                val up = pixels[idx - width]
                val down = pixels[idx + width]
                val left = pixels[idx - 1]
                val right = pixels[idx + 1]

                val r = sharpenChannel(p, up, down, left, right, center, edge, 16)
                val g = sharpenChannel(p, up, down, left, right, center, edge, 8)
                val b = sharpenChannel(p, up, down, left, right, center, edge, 0)
                val a = (p ushr 24) and 0xFF

                output[idx] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(output, 0, width, 0, 0, width, height)
        return result
    }

    private fun sharpenChannel(
        p: Int,
        up: Int,
        down: Int,
        left: Int,
        right: Int,
        center: Float,
        edge: Float,
        shift: Int
    ): Int {
        fun channel(pixel: Int) = (pixel ushr shift) and 0xFF
        val value = center * channel(p) + edge * (channel(up) + channel(down) + channel(left) + channel(right))
        return value.toInt().coerceIn(0, 255)
    }
}
