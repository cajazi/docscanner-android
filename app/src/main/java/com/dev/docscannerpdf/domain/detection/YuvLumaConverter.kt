package com.dev.docscannerpdf.domain.detection

/**
 * Converts a camera image's luma (Y) plane into the framework-free [LumaFrame] the detection
 * pipeline consumes. For both NV21 and YUV_420_888 the Y plane already holds per-pixel
 * brightness, so no color math is needed — we just honour the plane's [rowStride]/[pixelStride]
 * (which can include padding) and nearest-neighbour downscale to keep analysis cheap.
 *
 * Pure and deterministic: identical bytes + parameters always yield an identical frame.
 */
object YuvLumaConverter {

    /**
     * @param yPlane raw bytes of the luma plane
     * @param width source image width in pixels
     * @param height source image height in pixels
     * @param rowStride bytes per row (>= width * pixelStride)
     * @param pixelStride bytes between consecutive luma samples in a row (usually 1)
     * @param targetMaxDimension longest edge of the output frame; the source is downscaled to fit
     */
    fun fromLumaPlane(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int = 1,
        targetMaxDimension: Int = 240
    ): LumaFrame {
        require(width > 0 && height > 0) { "Image must have positive dimensions" }
        require(rowStride >= width * pixelStride) { "rowStride too small for width/pixelStride" }

        val longest = maxOf(width, height)
        val scale = if (longest <= targetMaxDimension) 1f else targetMaxDimension.toFloat() / longest
        val outWidth = (width * scale).toInt().coerceIn(1, width)
        val outHeight = (height * scale).toInt().coerceIn(1, height)

        val luma = IntArray(outWidth * outHeight)
        for (oy in 0 until outHeight) {
            // Nearest-neighbour source row.
            val sy = if (outHeight == 1) 0 else oy * (height - 1) / (outHeight - 1).coerceAtLeast(1)
            val rowBase = sy * rowStride
            val outBase = oy * outWidth
            for (ox in 0 until outWidth) {
                val sx = if (outWidth == 1) 0 else ox * (width - 1) / (outWidth - 1).coerceAtLeast(1)
                luma[outBase + ox] = yPlane[rowBase + sx * pixelStride].toInt() and 0xFF
            }
        }
        return LumaFrame(outWidth, outHeight, luma)
    }
}
