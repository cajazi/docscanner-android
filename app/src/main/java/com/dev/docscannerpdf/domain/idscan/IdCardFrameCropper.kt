package com.dev.docscannerpdf.domain.idscan

/** Pixel-space crop rectangle, always clamped inside the source image's bounds. */
data class IdCardCropRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    val aspectRatio: Float get() = width.toFloat() / height.toFloat()
}

/**
 * Maps the guided ID-card capture's on-screen guide frame (a landscape, card-shaped rectangle
 * drawn over a full-screen camera preview) to the matching pixel rectangle inside the actual
 * captured photo, so the baked capture output is a tight landscape card crop instead of the raw
 * portrait photo.
 *
 * The camera preview (`PreviewView`) uses CameraX's default `FILL_CENTER` scale type: the image is
 * scaled up uniformly (preserving aspect ratio) until it covers the whole preview container, then
 * centered — cropping whichever dimension overflows. [computeCropRect] inverts exactly that
 * transform: it computes the same cover-scale, offsets the frame rect by how much the image was
 * shifted to center it, then divides back into image pixel space. Kept free of Android types
 * (no [android.graphics.Bitmap]/[android.graphics.RectF]) so the mapping math is directly
 * unit-testable on the JVM; the guided capture screen and its baker convert to/from Android
 * bitmap coordinates at the edges.
 */
object IdCardFrameCropper {

    /**
     * Computes the pixel rect inside a [imageWidth]x[imageHeight] captured image that corresponds
     * to the guide frame at ([frameLeft], [frameTop], [frameWidth]x[frameHeight]) within a
     * [containerWidth]x[containerHeight] preview container — the same container the frame was
     * drawn over. The result is clamped to the image bounds and always at least 1x1.
     */
    fun computeCropRect(
        containerWidth: Float,
        containerHeight: Float,
        frameLeft: Float,
        frameTop: Float,
        frameWidth: Float,
        frameHeight: Float,
        imageWidth: Int,
        imageHeight: Int
    ): IdCardCropRect {
        require(containerWidth > 0f && containerHeight > 0f) { "Container dimensions must be positive." }
        require(imageWidth > 0 && imageHeight > 0) { "Image dimensions must be positive." }

        // FILL_CENTER cover-scale: how much the image is blown up so it fills the container in
        // both dimensions, then center-cropped in whichever dimension overflows.
        val scale = maxOf(containerWidth / imageWidth, containerHeight / imageHeight)
        val displayedWidth = imageWidth * scale
        val displayedHeight = imageHeight * scale
        val offsetX = (displayedWidth - containerWidth) / 2f
        val offsetY = (displayedHeight - containerHeight) / 2f

        val left = (frameLeft + offsetX) / scale
        val top = (frameTop + offsetY) / scale
        val right = (frameLeft + frameWidth + offsetX) / scale
        val bottom = (frameTop + frameHeight + offsetY) / scale

        val clampedLeft = left.coerceIn(0f, imageWidth.toFloat())
        val clampedTop = top.coerceIn(0f, imageHeight.toFloat())
        val clampedRight = right.coerceIn(clampedLeft, imageWidth.toFloat())
        val clampedBottom = bottom.coerceIn(clampedTop, imageHeight.toFloat())

        val width = (clampedRight - clampedLeft).toInt().coerceAtLeast(1)
        val height = (clampedBottom - clampedTop).toInt().coerceAtLeast(1)
        return IdCardCropRect(clampedLeft.toInt(), clampedTop.toInt(), width, height)
    }
}
