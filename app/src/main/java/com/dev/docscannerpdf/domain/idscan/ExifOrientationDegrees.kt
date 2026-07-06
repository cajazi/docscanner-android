package com.dev.docscannerpdf.domain.idscan

/**
 * Pure mapping from an EXIF `Orientation` tag value to the clockwise rotation, in degrees,
 * needed to display the image upright. The int values mirror `android.media.ExifInterface`'s
 * `ORIENTATION_*` constants without depending on that framework class, so this mapping is a pure
 * function directly unit-testable on the JVM. [com.dev.docscannerpdf.domain.idscan.IdScanPostProcessor]
 * is the Android-facing caller that reads the real EXIF tag and applies the rotation.
 */
object ExifOrientationDegrees {
    const val ORIENTATION_NORMAL = 1
    const val ORIENTATION_ROTATE_180 = 3
    const val ORIENTATION_ROTATE_90 = 6
    const val ORIENTATION_ROTATE_270 = 8

    /** Degrees to rotate clockwise so the image displays upright; 0 for anything unrecognized. */
    fun degreesFor(exifOrientation: Int): Int = when (exifOrientation) {
        ORIENTATION_ROTATE_90 -> 90
        ORIENTATION_ROTATE_180 -> 180
        ORIENTATION_ROTATE_270 -> 270
        else -> 0
    }
}
