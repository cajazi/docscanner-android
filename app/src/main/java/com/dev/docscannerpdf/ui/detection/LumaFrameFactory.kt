package com.dev.docscannerpdf.ui.detection

import android.graphics.Bitmap
import com.dev.docscannerpdf.domain.detection.LumaFrame

/**
 * Converts an Android [Bitmap] into the framework-free [LumaFrame] the detection pipeline
 * consumes. The bitmap is downscaled to [targetMaxDimension] before luma extraction so detection
 * stays fast and resolution-independent. This is the only Android bridge in the detection layer;
 * all analysis logic remains pure.
 */
object LumaFrameFactory {

    fun fromBitmap(bitmap: Bitmap, targetMaxDimension: Int = 240): LumaFrame {
        val scale = targetMaxDimension.toFloat() /
            maxOf(bitmap.width, bitmap.height).coerceAtLeast(1).toFloat()
        val width = (bitmap.width * scale).toInt().coerceIn(1, bitmap.width.coerceAtLeast(1))
        val height = (bitmap.height * scale).toInt().coerceIn(1, bitmap.height.coerceAtLeast(1))
        val scaled = if (width == bitmap.width && height == bitmap.height) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(bitmap, width, height, true)
        }

        val pixels = IntArray(width * height)
        scaled.getPixels(pixels, 0, width, 0, 0, width, height)
        val luma = IntArray(width * height) { i ->
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // Rec. 601 luma.
            (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
        }
        if (scaled !== bitmap) scaled.recycle()
        return LumaFrame(width, height, luma)
    }
}
