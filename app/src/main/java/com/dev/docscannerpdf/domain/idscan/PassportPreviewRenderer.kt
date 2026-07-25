package com.dev.docscannerpdf.domain.idscan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.dev.docscannerpdf.domain.filter.DocumentFilter
import com.dev.docscannerpdf.domain.filter.DocumentFilterPrimitives
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min

/**
 * Pure stateless in-memory renderer that composes a passport preview from a cached downscaled
 * base bitmap by applying the requested crop, rotation, filter, and watermark IN THAT ORDER.
 * Runs on [Dispatchers.Default] (CPU-only image work; no I/O).
 *
 * The preview order is: crop first (the page's geometric shape), then rotation (90/180/270), then
 * filter (tone LUT + color matrix + sharpen), then watermark (overlay). The authoritative
 * full-resolution pipeline runs the two middle steps the other way round (crop → filter → rotation
 * → watermark, because the filter renders from the canonical base and the rotation bakes from the
 * filtered page). The results are nonetheless pixel-equivalent, which is why both are safe: the
 * tone LUT and colour matrix are per-pixel, and the sharpen kernel is the 4-neighbour Laplacian —
 * invariant under a 90° rotation, with a border pass-through whose border set is likewise mapped
 * onto itself — so filter ∘ rotate == rotate ∘ filter for every recipe in the grid. The watermark
 * is applied LAST in both pipelines, so its placement matches exactly. Every function returns a
 * NEW bitmap and NEVER mutates or recycles its input — callers retain
 * ownership of every bitmap they pass in. The composed [compose] function is the single
 * entry point used by the viewmodel for every preview publication.
 *
 * Watermark placement shares [PassportWatermarkGeometry] with the authoritative
 * [PassportWatermarkRenderer] so the preview and the final file use the same fraction-based
 * text size, rotation, alpha, and centering — "what you see is what is saved" by construction.
 * The watermark text is NEVER logged.
 */
object PassportPreviewRenderer {

    /**
     * Composes a preview by applying [chain] in canonical order to [base], returning a NEW
     * bitmap. Returns a defensive copy of [base] when [chain] is the identity so the caller
     * cannot mutate the cached base through the returned reference. A no-op filter (ORIGINAL)
     * is still threaded through the pipeline so the rendered bitmap has the same ARGB_8888
     * config the rest of the pipeline expects.
     */
    suspend fun compose(
        base: Bitmap,
        chain: PassportEffectChain
    ): Bitmap = withContext(Dispatchers.Default) {
        // Step 1: crop (or pass-through when FULL).
        val cropped = if (chain.crop == PassportCropRect.FULL) {
            base.copy(base.config ?: Bitmap.Config.ARGB_8888, false)
        } else {
            cropInMemory(base, chain.crop)
        }
        // Step 2: rotation.
        val rotated = rotateInMemory(cropped, chain.rotationQuarterTurns)
        if (rotated !== cropped) cropped.recycle()
        // Step 3: filter.
        val filtered = applyFilterInMemory(rotated, chain.filter)
        if (filtered !== rotated) rotated.recycle()
        // Step 4: watermark.
        val watermarked = if (chain.watermarkText.isNullOrBlank()) {
            filtered
        } else {
            drawWatermarkInMemory(filtered, chain.watermarkText)
        }
        if (watermarked !== filtered) filtered.recycle()
        watermarked
    }

    /**
     * Crops [source] to the normalized [rect] using the same letterbox-aware mapping the screen
     * uses (see [PassportCropMapping.toSourcePixels]). Returns a NEW bitmap. Identity crop
     * (FULL) is handled by the caller.
     */
    internal fun cropInMemory(source: Bitmap, rect: PassportCropRect): Bitmap {
        val pixels = PassportCropMapping.toSourcePixels(rect, source.width, source.height)
        return Bitmap.createBitmap(source, pixels.left, pixels.top, pixels.width, pixels.height)
    }

    /**
     * Rotates [source] by [quarterTurns] (0/1/2/3) clockwise, returning a NEW bitmap. Quarter
     * turns are 0..3 mapped to 0°/90°/180°/270°. A 0° rotation returns a defensive copy so the
     * caller can recycle the input. A 180° rotation is performed via a single matrix so a
     * 360°-equivalent rotation is cheap.
     */
    internal fun rotateInMemory(source: Bitmap, quarterTurns: Int): Bitmap {
        val turns = ((quarterTurns % 4) + 4) % 4
        if (turns == 0) return source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
        val matrix = android.graphics.Matrix().apply {
            when (turns) {
                1 -> postRotate(90f)
                2 -> postRotate(180f)
                3 -> postRotate(270f)
            }
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /**
     * Applies [filter] in-memory using the same [DocumentFilterPrimitives] the authoritative
     * renderer uses, so the preview and the final file produce identical pixels for the same
     * recipe. ORIGINAL is the identity case: returns a defensive copy so the caller can keep
     * recycling the input without aliasing.
     */
    internal fun applyFilterInMemory(source: Bitmap, filter: DocumentFilter): Bitmap {
        if (filter == DocumentFilter.ORIGINAL || filter.isIdentity) {
            return source.copy(source.config ?: Bitmap.Config.ARGB_8888, false)
        }
        var current: Bitmap = source
        try {
            filter.toneLut?.let { lut ->
                val next = DocumentFilterPrimitives.applyToneLut(current, lut)
                if (next !== current) {
                    if (current !== source) current.recycle()
                    current = next
                }
            }
            filter.colorMatrix?.let { matrix ->
                val next = DocumentFilterPrimitives.applyColorMatrix(current, matrix)
                if (next !== current) {
                    if (current !== source) current.recycle()
                    current = next
                }
            }
            if (filter.sharpenStrength > 0f) {
                val next = DocumentFilterPrimitives.applySharpen(current, filter.sharpenStrength)
                if (next !== current) {
                    if (current !== source) current.recycle()
                    current = next
                }
            }
            // Return a copy the caller exclusively owns — defensively detach from the chain.
            val owned = current.copy(current.config ?: Bitmap.Config.ARGB_8888, false)
            if (owned !== current && current !== source) current.recycle()
            return owned
        } catch (throwable: Throwable) {
            if (current !== source) current.recycle()
            throw throwable
        }
    }

    /**
     * Draws the watermark text onto a copy of [source] using the SAME fraction-based geometry
     * ([PassportWatermarkGeometry]) the authoritative [PassportWatermarkRenderer] uses:
     * text size = shorter edge * [PassportWatermarkGeometry.TEXT_SIZE_FRACTION],
     * rotation = [PassportWatermarkGeometry.ROTATION_DEGREES],
     * alpha = [PassportWatermarkGeometry.ALPHA], centred on the image. Returns a NEW bitmap.
     * The text is NEVER logged at any log level.
     */
    internal fun drawWatermarkInMemory(source: Bitmap, text: String): Bitmap {
        val output = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(output)
        val shorterEdge = min(output.width, output.height).toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = (PassportWatermarkGeometry.ALPHA * 255f).toInt().coerceIn(0, 255)
            textSize = shorterEdge * PassportWatermarkGeometry.TEXT_SIZE_FRACTION
            isFakeBoldText = true
            textAlign = Paint.Align.CENTER
            setShadowLayer(max(1f, shorterEdge * 0.006f), 0f, 0f, Color.BLACK)
        }
        val centerX = output.width / 2f
        val centerY = output.height / 2f
        canvas.save()
        canvas.rotate(PassportWatermarkGeometry.ROTATION_DEGREES, centerX, centerY)
        val baseline = centerY - (paint.descent() + paint.ascent()) / 2f
        canvas.drawText(text, centerX, baseline, paint)
        canvas.restore()
        return output
    }

}
