package com.dev.docscannerpdf.domain.idscan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Bounds-first, OOM-safe downscaled decoder for passport preview base bitmaps. Decoded on
 * [Dispatchers.IO] — never the main thread. The output long edge is bounded by
 * [MAX_PREVIEW_LONG_EDGE] (1280px), chosen so a portrait passport page fits comfortably on a
 * mid-range SM-A165F without exceeding the 1080-1440px target the brief specifies, while still
 * keeping memory small enough that the in-memory preview is cheap to recompose on every
 * interactive tap.
 *
 * The function is fail-soft: a decode failure or non-positive bounds returns null, never
 * throws. Callers must check the return value before publishing. The function never recycles
 * the source stream — [BitmapFactory.decodeStream] owns it and the resulting bitmap is the
 * caller's responsibility to release via the GC (per the brief's "do not recycle a Bitmap
 * while Compose may still display it" rule).
 */
object PassportPreviewDecoder {

    /** The maximum allowed long edge of the downscaled base, in pixels. */
    const val MAX_PREVIEW_LONG_EDGE = 1280

    /**
     * Decodes [uri] downscaled so its long edge is at most [MAX_PREVIEW_LONG_EDGE] pixels,
     * preserving the source aspect ratio. Returns null on any failure (bounds read failure,
     * decode failure, OOM).
     */
    suspend fun decodeDownscaled(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        runCatching {
            // 1) bounds-only pass — never allocates pixels.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            openStream(context, uri).use { stream ->
                BitmapFactory.decodeStream(stream, null, bounds)
            }
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return@runCatching null

            // 2) compute the smallest power-of-two sample size that brings the long edge at or
            //    below MAX_PREVIEW_LONG_EDGE.
            var sample = 1
            val longEdge = maxOf(srcW, srcH)
            while (longEdge / (sample * 2) >= MAX_PREVIEW_LONG_EDGE) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            val sampled = openStream(context, uri).use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            } ?: return@runCatching null

            // 3) If the sampled decode is still larger than the target on the long edge, scale
            //    precisely to the target.
            val sampledLong = maxOf(sampled.width, sampled.height)
            if (sampledLong <= MAX_PREVIEW_LONG_EDGE) {
                sampled
            } else {
                val scale = MAX_PREVIEW_LONG_EDGE.toFloat() / sampledLong.toFloat()
                val targetW = (sampled.width * scale).toInt().coerceAtLeast(1)
                val targetH = (sampled.height * scale).toInt().coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(sampled, targetW, targetH, true)
                // The source may be a fresh decode OR the SAME bitmap returned as a result of
                // createScaledBitmap — only recycle the source when it is distinct.
                if (scaled !== sampled) sampled.recycle()
                scaled
            }
        }.getOrNull()
    }

    /**
     * Decodes a small thumbnail (long edge <= 128px) of [uri] for filter-grid thumbnails.
     * Generated exactly once per base generation so all 8 filter thumbnails share the SAME
     * source bitmap (the brief's "all filter thumbnails share one cached thumbnail source"
     * requirement). Returns null on any failure.
     */
    suspend fun decodeThumbnail(context: Context, uri: Uri, longEdge: Int = 128): Bitmap? =
        withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                openStream(context, uri).use { stream ->
                    BitmapFactory.decodeStream(stream, null, bounds)
                }
                val srcW = bounds.outWidth
                val srcH = bounds.outHeight
                if (srcW <= 0 || srcH <= 0) return@runCatching null
                var sample = 1
                val sourceLong = maxOf(srcW, srcH)
                while (sourceLong / (sample * 2) >= longEdge) {
                    sample *= 2
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val sampled = openStream(context, uri).use { stream ->
                    BitmapFactory.decodeStream(stream, null, options)
                } ?: return@runCatching null
                val sampledLong = maxOf(sampled.width, sampled.height)
                if (sampledLong <= longEdge) {
                    sampled
                } else {
                    val scale = longEdge.toFloat() / sampledLong.toFloat()
                    val targetW = (sampled.width * scale).toInt().coerceAtLeast(1)
                    val targetH = (sampled.height * scale).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(sampled, targetW, targetH, true)
                    if (scaled !== sampled) sampled.recycle()
                    scaled
                }
            }.getOrNull()
        }

    private fun openStream(context: Context, uri: Uri) = when (uri.scheme) {
        "content" -> context.contentResolver.openInputStream(uri)
            ?: error("Cannot open content URI: $uri")
        else -> uri.path?.let { File(it).inputStream() }
            ?: error("Cannot open file URI: $uri")
    }
}
