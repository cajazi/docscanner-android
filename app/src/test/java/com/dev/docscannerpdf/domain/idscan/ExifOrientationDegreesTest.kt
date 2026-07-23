package com.dev.docscannerpdf.domain.idscan

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Test

class ExifOrientationDegreesTest {

    @Test
    fun normalOrientationNeedsNoRotation() {
        assertEquals(0, ExifOrientationDegrees.degreesFor(ExifOrientationDegrees.ORIENTATION_NORMAL))
    }

    @Test
    fun rotate90TagNeeds90DegreeCorrection() {
        assertEquals(90, ExifOrientationDegrees.degreesFor(ExifOrientationDegrees.ORIENTATION_ROTATE_90))
    }

    @Test
    fun rotate180TagNeeds180DegreeCorrection() {
        assertEquals(180, ExifOrientationDegrees.degreesFor(ExifOrientationDegrees.ORIENTATION_ROTATE_180))
    }

    @Test
    fun rotate270TagNeeds270DegreeCorrection() {
        assertEquals(270, ExifOrientationDegrees.degreesFor(ExifOrientationDegrees.ORIENTATION_ROTATE_270))
    }

    @Test
    fun unrecognizedTagDefaultsToNoRotation() {
        assertEquals(0, ExifOrientationDegrees.degreesFor(exifOrientation = 999))
    }

    /**
     * After the swap to AndroidX ExifInterface, the pure mirror constants must still match the
     * real library's ORIENTATION_* values (they are inlined compile-time constants, so this
     * runs on the JVM without loading the Android class) — otherwise a real content:// import
     * could read a valid tag that this mapping then misinterprets.
     */
    @Test
    fun mirrorConstantsMatchAndroidxExifInterface() {
        assertEquals(ExifInterface.ORIENTATION_NORMAL, ExifOrientationDegrees.ORIENTATION_NORMAL)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, ExifOrientationDegrees.ORIENTATION_ROTATE_90)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_180, ExifOrientationDegrees.ORIENTATION_ROTATE_180)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_270, ExifOrientationDegrees.ORIENTATION_ROTATE_270)
    }

    /**
     * The controlled fallback contract: on malformed/unreadable input both EXIF callers resolve
     * to ORIENTATION_NORMAL, which must map to no rotation — a safe upright default rather than
     * a crash or a wrong turn.
     */
    @Test
    fun fallbackNormalOrientationMapsToNoRotation() {
        assertEquals(0, ExifOrientationDegrees.degreesFor(ExifInterface.ORIENTATION_NORMAL))
    }
}
