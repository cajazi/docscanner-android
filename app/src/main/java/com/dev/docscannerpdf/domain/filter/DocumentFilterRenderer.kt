package com.dev.docscannerpdf.domain.filter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Race-safe output-directory validation: succeeds when this call created [directory] OR a
 * concurrent caller already did. The naive `!exists() && !mkdirs()` check failed under the
 * front/back concurrent render pattern — both observe the directory missing, one creates it,
 * the other's `mkdirs()` then returns false and a perfectly good render was discarded. Failure
 * is reported only when the path is not, and cannot become, a directory (e.g. a regular file
 * occupies it). Pure java.io, JVM-testable, no locking needed — `mkdirs()` itself is atomic
 * enough once "already exists" stops being treated as an error.
 */
fun ensureOutputDirectory(directory: File): Boolean =
    directory.mkdirs() || directory.isDirectory

/**
 * The shared deterministic bitmap-processing primitives behind every [DocumentFilter] recipe:
 * one ColorMatrix pass and one sharpen convolution. These were extracted verbatim from
 * [com.dev.docscannerpdf.domain.idscan.IdScanPostProcessor] (which now delegates here) so there
 * is exactly ONE implementation of contrast/sharpen in the app — the ID-scan auto-enhance and
 * every user-selectable filter produce pixels through the same code. Pure CPU, no network, no
 * GPU, no new dependencies. Every function returns a fresh bitmap and never mutates or recycles
 * its input — callers keep ownership.
 */
object DocumentFilterPrimitives {

    /** Draws [source] through a [ColorMatrixColorFilter] built from the 4x5 row-major [matrix]. */
    fun applyColorMatrix(source: Bitmap, matrix: FloatArray): Bitmap {
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix(matrix))
        }
        canvas.drawBitmap(source, 0f, 0f, paint)
        return output
    }

    /** Contrast scale of [amount] around mid-gray — the matrix comes from [contrastColorMatrix]. */
    fun applyContrast(source: Bitmap, amount: Float): Bitmap =
        applyColorMatrix(source, contrastColorMatrix(amount))

    /**
     * Per-channel tone curve via a pure 256-entry [lut] (e.g. [enhanceToneLut]'s shouldered
     * lift, which brightens shadows/midtones while tapering to identity at white so highlight
     * detail survives). Alpha is untouched.
     */
    fun applyToneLut(source: Bitmap, lut: IntArray): Bitmap {
        require(lut.size == 256) { "A tone LUT must have exactly 256 entries." }
        val pixels = IntArray(source.width * source.height)
        source.getPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val a = pixel ushr 24 and 0xFF
            val r = lut[pixel ushr 16 and 0xFF]
            val g = lut[pixel ushr 8 and 0xFF]
            val b = lut[pixel and 0xFF]
            pixels[index] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        val output = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        output.setPixels(pixels, 0, source.width, 0, 0, source.width, source.height)
        return output
    }

    /**
     * Lightweight unsharp-mask-style 3x3 convolution over bulk pixel arrays (no per-pixel
     * getPixel/setPixel calls, no native/RenderScript dependency) — cheap and deterministic
     * enough to run inline on a single scan's image, and bounded to 0..255 per channel so a mild
     * [strength] can never overflow into visible artifacts. Returns [source] itself for images
     * too small to convolve.
     */
    fun applySharpen(source: Bitmap, strength: Float): Bitmap {
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

/**
 * Renders a [DocumentFilter] recipe non-destructively: decodes the source, applies the recipe's
 * ColorMatrix and/or sharpen via [DocumentFilterPrimitives], and writes the result as a NEW
 * uniquely named JPEG in app-private storage — the source file is never modified, so re-rendering
 * any filter from the same base can never compound. [DocumentFilter.ORIGINAL] (and any identity
 * recipe) short-circuits to the source URI without writing a file. Not ID-card-specific; any
 * later slice can render normal-document filters through this same object.
 */
object DocumentFilterRenderer {

    private const val JPEG_QUALITY = 92

    /**
     * Renders [filter] applied to [sourceUri] into [outputDirectory] (created if missing) and
     * returns the new file's URI — or [sourceUri] itself for an identity recipe. Returns null on
     * decode or write failure so callers can surface an error instead of silently substituting
     * the unfiltered image. Cancellation is honored, never swallowed: a [CancellationException]
     * always propagates (an intentionally cancelled stale render must not look like a failure),
     * and the job is re-checked between the expensive stages so a cancelled render stops early.
     */
    suspend fun render(
        context: Context,
        sourceUri: Uri,
        filter: DocumentFilter,
        outputDirectory: File
    ): Uri? = withContext(Dispatchers.IO) {
        if (filter.isIdentity) return@withContext sourceUri

        ensureActive()
        val source = decode(context, sourceUri) ?: return@withContext null
        var current = source
        try {
            filter.toneLut?.let { lut ->
                ensureActive()
                val next = DocumentFilterPrimitives.applyToneLut(current, lut)
                if (next !== current && current !== source) current.recycle()
                current = next
            }
            filter.colorMatrix?.let { matrix ->
                ensureActive()
                val next = DocumentFilterPrimitives.applyColorMatrix(current, matrix)
                if (next !== current && current !== source) current.recycle()
                current = next
            }
            if (filter.sharpenStrength > 0f) {
                ensureActive()
                val next = DocumentFilterPrimitives.applySharpen(current, filter.sharpenStrength)
                if (next !== current && current !== source) current.recycle()
                current = next
            }
            ensureActive()
            save(current, filter, outputDirectory)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            null
        } finally {
            if (current !== source) current.recycle()
            source.recycle()
        }
    }

    /**
     * Decodes [uri] or returns null on a genuine open/decode failure. [CancellationException]
     * is rethrown, never swallowed — an intentionally cancelled stale render must propagate
     * silently instead of masquerading as a decode failure (which would surface an error toast
     * for a render the user deliberately superseded).
     */
    private fun decode(context: Context, uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            null
        }
    }

    /**
     * Writes [bitmap] as a JPEG under a collision-proof name (see [filterOutputFileName]) and
     * returns its URI only when the COMPLETE file was written: a failed or interrupted
     * compression deletes the partial file and returns null (or rethrows cancellation), so a
     * caller can never receive a URI to a truncated image.
     */
    private fun save(bitmap: Bitmap, filter: DocumentFilter, outputDirectory: File): Uri? {
        if (!ensureOutputDirectory(outputDirectory)) return null
        val file = File(outputDirectory, filterOutputFileName(filter))
        return try {
            val compressed = file.outputStream().use { output ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)
            }
            if (compressed) {
                Uri.fromFile(file)
            } else {
                file.delete()
                null
            }
        } catch (cancellation: CancellationException) {
            file.delete()
            throw cancellation
        } catch (throwable: Throwable) {
            file.delete()
            null
        }
    }
}
