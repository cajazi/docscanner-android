package com.dev.docscannerpdf.domain.idscan

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
}
