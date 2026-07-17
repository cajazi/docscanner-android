package com.dev.docscannerpdf.domain.filter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral tests of the calibrated ENHANCE recipe, evaluated against the SHIPPED production
 * transformation: the exact [DocumentFilter.ENHANCE.toneLut] table followed by the exact
 * [DocumentFilter.ENHANCE.colorMatrix] — the same two operations [DocumentFilterRenderer]
 * performs on real bitmaps, applied to synthetic RGB values so the algorithm's visual
 * guarantees (brightening, highlight separation, neutrality, readability) are testable on the
 * JVM rather than only its constants.
 */
class EnhanceRecipeTest {

    /** Applies ENHANCE's shipped tone LUT + color matrix to one RGB pixel, clamped like the renderer. */
    private fun enhance(r: Int, g: Int, b: Int): IntArray {
        val lut = requireNotNull(DocumentFilter.ENHANCE.toneLut)
        val lr = lut[r].toFloat()
        val lg = lut[g].toFloat()
        val lb = lut[b].toFloat()
        val m = requireNotNull(DocumentFilter.ENHANCE.colorMatrix)
        fun channel(row: Int): Int {
            val value = m[row * 5] * lr + m[row * 5 + 1] * lg + m[row * 5 + 2] * lb + m[row * 5 + 4]
            return Math.round(value).coerceIn(0, 255)
        }
        return intArrayOf(channel(0), channel(1), channel(2))
    }

    private fun enhanceNeutral(value: Int): Int = enhance(value, value, value)[0]

    @Test
    fun originalRemainsIdentity() {
        assertTrue(DocumentFilter.ORIGINAL.isIdentity)
        // ENHANCE is the only tone-curved recipe; every other filter stays matrix/sharpen only.
        assertEquals(
            listOf(DocumentFilter.ENHANCE),
            DocumentFilter.entries.filter { it.toneLut != null }
        )
    }

    @Test
    fun darkInputComesOutBrighterButRetainsDepth() {
        val out = enhanceNeutral(60)

        assertTrue("dark 60 must brighten (was $out)", out > 60)
        assertTrue("but retain dark depth (was $out)", out <= 75)
        // The retune keeps shadows deeper than the washed-out 0.86 curve did.
        val previousCurve = shoulderedGammaLut(0.86f, ENHANCE_SHOULDER_START)
        val lut = requireNotNull(DocumentFilter.ENHANCE.toneLut)
        assertTrue("30 is deeper than the 0.86 curve", lut[30] < previousCurve[30])
        assertTrue("64 is deeper than the 0.86 curve", lut[64] < previousCurve[64])
    }

    @Test
    fun midtoneLuminanceLiftIsWithinTheCalibratedRange() {
        val out = enhanceNeutral(128)

        // Retuned band: brighter than Original, less lifted than the washed-out 0.86 curve.
        assertTrue("midtone 128 lifted (was $out)", out >= 136)
        assertTrue("midtone lift bounded (was $out)", out <= 142)
        val previousCurve = shoulderedGammaLut(0.86f, ENHANCE_SHOULDER_START)
        assertTrue("less lifted than the 0.86 curve", out < previousCurve[128])
    }

    @Test
    fun highlightsKeepStrictSeparationAndNeverCollapseToWhite() {
        val e230 = enhanceNeutral(230)
        val e240 = enhanceNeutral(240)
        val e250 = enhanceNeutral(250)
        val e255 = enhanceNeutral(255)

        // The previous recipe's exact defect: everything >= ~235 collapsed into 255.
        assertTrue("230 stays below white (was $e230)", e230 < 255)
        assertTrue("240 stays below white (was $e240)", e240 < 255)
        assertTrue("250 stays below white (was $e250)", e250 < 255)
        assertEquals("pure white stays exactly white", 255, e255)
        // Strict ordering: near-white detail keeps its separation.
        assertTrue("230 < 240 mapping ($e230 vs $e240)", e230 < e240)
        assertTrue("240 < 250 mapping ($e240 vs $e250)", e240 < e250)
        assertTrue("250 < 255 mapping ($e250 vs $e255)", e250 < e255)
    }

    @Test
    fun onlyPureWhiteMapsToWhiteInTheToneCurve() {
        val lut = requireNotNull(DocumentFilter.ENHANCE.toneLut)

        val whiteInputs = (0..255).filter { lut[it] == 255 }
        assertEquals("no 255 plateau — only 255 itself maps to 255", listOf(255), whiteInputs)
    }

    @Test
    fun upperMidtonesBrightenButStayBelowClipping() {
        val out = enhanceNeutral(200)

        assertTrue("200 brightens (was $out)", out > 200)
        assertTrue("200 stays far from clipping (was $out)", out < 240)
    }

    @Test
    fun mutedColorGainsControlledSaturation() {
        val input = intArrayOf(150, 120, 120)
        val out = enhance(input[0], input[1], input[2])

        val inputSpread = input.max() - input.min()
        val outputSpread = out.max() - out.min()
        assertTrue("chroma spread grows ($inputSpread -> $outputSpread)", outputSpread > inputSpread)
        assertTrue("saturation stays controlled (spread $outputSpread)", outputSpread <= inputSpread * 2)
    }

    @Test
    fun neutralGrayStaysPerfectlyNeutral() {
        listOf(32, 96, 128, 192, 230, 250).forEach { value ->
            val out = enhance(value, value, value)
            assertEquals("no cast at $value", out[0], out[1])
            assertEquals("no cast at $value", out[1], out[2])
        }
    }

    @Test
    fun alphaIsNeverAltered() {
        // The matrix's alpha output row is identity, and the tone LUT is applied to RGB only
        // (see DocumentFilterPrimitives.applyToneLut, which passes the alpha byte through).
        val m = requireNotNull(DocumentFilter.ENHANCE.colorMatrix)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, 0f), m.copyOfRange(15, 20), 0.0001f)
    }

    @Test
    fun darkTextRemainsReadablyDark() {
        val out = enhanceNeutral(30)

        assertTrue("text-dark 30 stays dark (was $out)", out <= 60)
        assertEquals("true black is pinned", 0, enhanceNeutral(0))
    }

    @Test
    fun enhancementIsDeterministic() {
        assertArrayEquals(enhance(97, 143, 61), enhance(97, 143, 61))
        assertArrayEquals(enhanceToneLut(), enhanceToneLut())
    }

    @Test
    fun toneCurveIsMonotonicPinnedAndShouldered() {
        val lut = requireNotNull(DocumentFilter.ENHANCE.toneLut)

        assertEquals(0, lut[0])
        assertEquals(255, lut[255])
        for (i in 1..255) {
            assertTrue("monotonic at $i", lut[i] >= lut[i - 1])
        }
        // The shoulder: the lift shrinks toward the highlights instead of staying constant.
        val midtoneLift = lut[128] - 128
        val highlightLift = lut[230] - 230
        assertTrue("midtones lifted (was +$midtoneLift)", midtoneLift >= 6)
        assertTrue("highlight lift tapers (was +$highlightLift)", highlightLift < midtoneLift / 3)
    }

    @Test
    fun neutralMappingsLandInsideTheAgreedRetuneBands() {
        // The device-QA-agreed target bands for the 0.91 retune.
        assertEquals(0, enhanceNeutral(0))
        assertTrue(enhanceNeutral(30) in 35..38)
        assertTrue(enhanceNeutral(64) in 72..76)
        assertTrue(enhanceNeutral(128) in 136..142)
        assertTrue(enhanceNeutral(200) in 202..207)
        assertTrue(enhanceNeutral(230) in 230..234)
        assertTrue(enhanceNeutral(240) in 240..244)
        assertTrue(enhanceNeutral(250) in 250..252)
        assertEquals(255, enhanceNeutral(255))
    }
}
