package com.dev.docscannerpdf.domain.filter

import com.dev.docscannerpdf.domain.idscan.IdScanPostProcessor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentFilterTest {

    private val delta = 0.0001f

    @Test
    fun catalogHasExactOrderAndDisplayNames() {
        assertEquals(
            listOf(
                "Original", "Enhance", "Brightness", "Contrast", "Sharpen",
                "B&W", "Sepia", "Gray", "Warm", "Cool"
            ),
            DocumentFilter.CATALOG.map { it.displayName }
        )
    }

    @Test
    fun catalogHasStableIdentifiers() {
        assertEquals(
            listOf(
                "ORIGINAL", "ENHANCE", "BRIGHTNESS", "CONTRAST", "SHARPEN",
                "BW", "SEPIA", "GRAY", "WARM", "COOL"
            ),
            DocumentFilter.CATALOG.map { it.name }
        )
    }

    @Test
    fun catalogContainsNoExtraFilters() {
        assertEquals(10, DocumentFilter.CATALOG.size)
        assertEquals(DocumentFilter.entries.size, DocumentFilter.CATALOG.size)
        // The unfinished editor-strip labels must never leak into the catalog.
        val names = DocumentFilter.CATALOG.map { it.displayName }
        listOf("Magic Pro", "No Watermark", "No Shadow", "No Handwriting").forEach { forbidden ->
            assertFalse("$forbidden must not be in the catalog", names.contains(forbidden))
        }
    }

    @Test
    fun originalIsIdentity() {
        assertNull(DocumentFilter.ORIGINAL.colorMatrix)
        assertEquals(0f, DocumentFilter.ORIGINAL.sharpenStrength, delta)
        assertTrue(DocumentFilter.ORIGINAL.isIdentity)
        // Original is the only identity entry — every other filter changes pixels.
        DocumentFilter.CATALOG.filter { it != DocumentFilter.ORIGINAL }.forEach { filter ->
            assertFalse("${filter.name} must not be identity", filter.isIdentity)
        }
    }

    @Test
    fun enhanceUsesContrast112AndSharpen018() {
        assertArrayEquals(contrastColorMatrix(1.12f), DocumentFilter.ENHANCE.colorMatrix, delta)
        assertEquals(-15.3f, requireNotNull(DocumentFilter.ENHANCE.colorMatrix)[4], 0.001f)
        assertEquals(0.18f, DocumentFilter.ENHANCE.sharpenStrength, delta)
    }

    @Test
    fun idScanPostProcessorDefaultsMatchEnhanceSpecification() {
        // Regression guard for the primitive extraction: IdScanPostProcessor delegates its
        // contrast/sharpen to DocumentFilterPrimitives with these Config defaults, and ENHANCE
        // is specified to reproduce them exactly — if either drifts, this fails.
        val config = IdScanPostProcessor.Config()
        assertEquals(1.12f, config.contrastBoost, delta)
        assertEquals(0.18f, config.sharpenStrength, delta)
        assertArrayEquals(
            contrastColorMatrix(config.contrastBoost),
            DocumentFilter.ENHANCE.colorMatrix,
            delta
        )
        assertEquals(config.sharpenStrength, DocumentFilter.ENHANCE.sharpenStrength, delta)
    }

    @Test
    fun brightnessMatrixIsExact() {
        assertArrayEquals(
            floatArrayOf(
                1f, 0f, 0f, 0f, 25f,
                0f, 1f, 0f, 0f, 25f,
                0f, 0f, 1f, 0f, 25f,
                0f, 0f, 0f, 1f, 0f
            ),
            DocumentFilter.BRIGHTNESS.colorMatrix,
            delta
        )
        assertEquals(0f, DocumentFilter.BRIGHTNESS.sharpenStrength, delta)
    }

    @Test
    fun contrastMatrixIsExact() {
        val matrix = requireNotNull(DocumentFilter.CONTRAST.colorMatrix)
        assertEquals(1.25f, matrix[0], delta)
        assertEquals(1.25f, matrix[6], delta)
        assertEquals(1.25f, matrix[12], delta)
        assertEquals(-31.875f, matrix[4], delta)
        assertEquals(-31.875f, matrix[9], delta)
        assertEquals(-31.875f, matrix[14], delta)
        assertArrayEquals(contrastColorMatrix(1.25f), matrix, delta)
    }

    @Test
    fun sharpenHasNoColorTransform() {
        assertNull(DocumentFilter.SHARPEN.colorMatrix)
        assertEquals(0.35f, DocumentFilter.SHARPEN.sharpenStrength, delta)
    }

    @Test
    fun bwMatrixIsHighContrastGrayscale() {
        val matrix = requireNotNull(DocumentFilter.BW.colorMatrix)
        val expectedRow = floatArrayOf(0.4635f, 0.9099f, 0.1767f, 0f, -70.125f)
        for (row in 0..2) {
            for (column in 0..4) {
                assertEquals(expectedRow[column], matrix[row * 5 + column], delta)
            }
        }
        assertAlphaRowIsIdentity(matrix)
        assertGrayscaleRows(matrix)
    }

    @Test
    fun grayMatrixIsRec601Luminance() {
        val matrix = requireNotNull(DocumentFilter.GRAY.colorMatrix)
        val expectedRow = floatArrayOf(0.299f, 0.587f, 0.114f, 0f, 0f)
        for (row in 0..2) {
            for (column in 0..4) {
                assertEquals(expectedRow[column], matrix[row * 5 + column], delta)
            }
        }
        assertAlphaRowIsIdentity(matrix)
        assertGrayscaleRows(matrix)
    }

    @Test
    fun sepiaMatrixIsExact() {
        assertArrayEquals(
            floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ),
            DocumentFilter.SEPIA.colorMatrix,
            delta
        )
    }

    @Test
    fun warmAndCoolMatricesAreExact() {
        assertArrayEquals(
            floatArrayOf(
                1.10f, 0f, 0f, 0f, 0f,
                0f, 1.03f, 0f, 0f, 0f,
                0f, 0f, 0.88f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ),
            DocumentFilter.WARM.colorMatrix,
            delta
        )
        assertArrayEquals(
            floatArrayOf(
                0.88f, 0f, 0f, 0f, 0f,
                0f, 1.02f, 0f, 0f, 0f,
                0f, 0f, 1.10f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ),
            DocumentFilter.COOL.colorMatrix,
            delta
        )
    }

    @Test
    fun everyColorMatrixIsFourByFiveWithIdentityAlpha() {
        DocumentFilter.CATALOG.mapNotNull { it.colorMatrix }.forEach { matrix ->
            assertEquals(20, matrix.size)
            assertAlphaRowIsIdentity(matrix)
        }
    }

    /** The alpha output row must be [0,0,0,1,0] — filters never touch transparency. */
    private fun assertAlphaRowIsIdentity(matrix: FloatArray) {
        assertArrayEquals(
            floatArrayOf(0f, 0f, 0f, 1f, 0f),
            matrix.copyOfRange(15, 20),
            delta
        )
    }

    /** A grayscale matrix produces identical R, G, and B output rows. */
    private fun assertGrayscaleRows(matrix: FloatArray) {
        val red = matrix.copyOfRange(0, 5)
        val green = matrix.copyOfRange(5, 10)
        val blue = matrix.copyOfRange(10, 15)
        assertArrayEquals(red, green, delta)
        assertArrayEquals(red, blue, delta)
    }
}
