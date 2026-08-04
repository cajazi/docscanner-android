package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three-tier polygon priority. The property that matters most is the negative one: a seed whose
 * field of view cannot be proven to match the still is REFUSED rather than approximated, because a
 * confidently-wrong crop is offset by exactly the mismatched band and shows no symptom until the
 * saved page is inspected.
 */
class MainScanPolygonResolverTest {

    private val detectedQuad = PerspectiveQuad.inset(0.12f)

    private fun seed(
        analysisFrame: FrameSize = FrameSize(640, 480),
        rotationDegrees: Int = 90,
        quad: PerspectiveQuad = detectedQuad
    ) = MainScanCropSeed(
        sessionId = 1L,
        generation = 1L,
        quad = quad,
        analysisFrame = analysisFrame,
        rotationDegrees = rotationDegrees,
        timestampMs = 0L
    )

    // --- mappability ------------------------------------------------------------------------------

    @Test
    fun aMatchingAspectRatioIsMappable() {
        // 640x480 analysis rotated 90 -> 480x640 (3:4). Capture 3060x4080 (3:4). Same FOV.
        assertTrue(
            MainScanPolygonResolver.isSeedMappable(
                seed = seed(),
                captureWidth = 3060,
                captureHeight = 4080
            )
        )
    }

    @Test
    fun orientationDoesNotAffectMappability() {
        // The same 4:3 relationship, capture reported landscape.
        assertTrue(
            MainScanPolygonResolver.isSeedMappable(seed(), captureWidth = 4080, captureHeight = 3060)
        )
    }

    @Test
    fun aMismatchedAspectRatioIsNotMappable() {
        // A 16:9 analysis stream cropped from a 4:3 sensor: the seed's corners describe a narrower
        // slice of the scene than the still contains, so applying them directly would be offset.
        assertFalse(
            MainScanPolygonResolver.isSeedMappable(
                seed = seed(analysisFrame = FrameSize(1280, 720)),
                captureWidth = 3060,
                captureHeight = 4080
            )
        )
    }

    @Test
    fun roundingDifferencesAreToleratedButFormatDifferencesAreNot() {
        // 640x480 vs 3060x4080 differ only in the fourth decimal — that must pass.
        assertTrue(
            MainScanPolygonResolver.isSeedMappable(seed(), 3060, 4080)
        )
        // 3:2 against 4:3 is a genuine format difference — that must fail.
        assertFalse(
            MainScanPolygonResolver.isSeedMappable(
                seed = seed(analysisFrame = FrameSize(720, 480)),
                captureWidth = 3060,
                captureHeight = 4080
            )
        )
    }

    @Test
    fun aNullSeedOrDegenerateCaptureIsNotMappable() {
        assertFalse(MainScanPolygonResolver.isSeedMappable(null, 3060, 4080))
        assertFalse(MainScanPolygonResolver.isSeedMappable(seed(), 0, 0))
        assertFalse(
            MainScanPolygonResolver.isSeedMappable(
                seed = seed(analysisFrame = FrameSize(0, 0)),
                captureWidth = 3060,
                captureHeight = 4080
            )
        )
    }

    // --- resolution priority -----------------------------------------------------------------------

    @Test
    fun aProvenSeedWins() {
        val resolved = MainScanPolygonResolver.resolve(
            seed = seed(),
            captureWidth = 3060,
            captureHeight = 4080,
            stillDetection = PerspectiveQuad.inset(0.30f)
        )
        assertEquals(MainScanPolygonSource.FROZEN_SEED, resolved.source)
        assertEquals(detectedQuad, resolved.quad)
    }

    @Test
    fun anUnprovableSeedFallsThroughToStillDetection() {
        val still = PerspectiveQuad.inset(0.20f)
        val resolved = MainScanPolygonResolver.resolve(
            seed = seed(analysisFrame = FrameSize(1280, 720)),
            captureWidth = 3060,
            captureHeight = 4080,
            stillDetection = still
        )
        assertEquals(MainScanPolygonSource.STILL_DETECTION, resolved.source)
        assertEquals(still, resolved.quad)
    }

    @Test
    fun noSeedAndNoDetectionFallsBackToFullFrame() {
        val resolved = MainScanPolygonResolver.resolve(
            seed = null,
            captureWidth = 3060,
            captureHeight = 4080,
            stillDetection = null
        )
        assertEquals(MainScanPolygonSource.FULL_FRAME, resolved.source)
        assertEquals(MainScanCropEditor.fullFrame(), resolved.quad)
    }

    @Test
    fun anUnusableStillDetectionAlsoFallsBackToFullFrame() {
        val sliver = PerspectiveQuad(
            topLeft = CropPoint(0.1f, 0.50f),
            topRight = CropPoint(0.9f, 0.50f),
            bottomRight = CropPoint(0.9f, 0.505f),
            bottomLeft = CropPoint(0.1f, 0.505f)
        )
        val resolved = MainScanPolygonResolver.resolve(
            seed = null,
            captureWidth = 3060,
            captureHeight = 4080,
            stillDetection = sliver
        )
        assertEquals(MainScanPolygonSource.FULL_FRAME, resolved.source)
    }

    @Test
    fun aMappableButUnusableSeedFallsThroughRatherThanBeingForced() {
        val degenerate = PerspectiveQuad(
            topLeft = CropPoint(0.5f, 0.5f),
            topRight = CropPoint(0.5f, 0.5f),
            bottomRight = CropPoint(0.5f, 0.5f),
            bottomLeft = CropPoint(0.5f, 0.5f)
        )
        val still = PerspectiveQuad.inset(0.18f)
        val resolved = MainScanPolygonResolver.resolve(
            seed = seed(quad = degenerate),
            captureWidth = 3060,
            captureHeight = 4080,
            stillDetection = still
        )
        assertEquals(MainScanPolygonSource.STILL_DETECTION, resolved.source)
    }

    // --- detection always runs, so the seed is never unopposed ---------------------------------------

    @Test
    fun stillDetectionRunsEvenWhenTheSeedLooksUsable() {
        // This previously returned false, which meant a mappable seed faced no competing candidate
        // and won by default — the mechanism behind the wall-corner selection found in device QA.
        assertTrue(MainScanPolygonResolver.needsStillDetection(seed(), 3060, 4080))
    }

    @Test
    fun stillDetectionIsRequiredWhenTheSeedCannotBeUsed() {
        assertTrue(MainScanPolygonResolver.needsStillDetection(null, 3060, 4080))
        assertTrue(
            MainScanPolygonResolver.needsStillDetection(
                seed = seed(analysisFrame = FrameSize(1280, 720)),
                captureWidth = 3060,
                captureHeight = 4080
            )
        )
    }

    @Test
    fun everyResolvedPolygonIsImmediatelyEditable() {
        val cases = listOf(
            MainScanPolygonResolver.resolve(seed(), 3060, 4080),
            MainScanPolygonResolver.resolve(null, 3060, 4080, PerspectiveQuad.inset(0.2f)),
            MainScanPolygonResolver.resolve(null, 3060, 4080, null)
        )
        for (case in cases) {
            assertTrue(
                "resolved polygon from ${case.source} must be applicable",
                MainScanCropEditor.isApplicable(case.quad)
            )
        }
    }

    // --- candidate comparison (the wall-corner blocker) --------------------------------------------

    /** A wall-corner wedge: convex, but diverging edges and corners far from right. */
    private val weakShape = PerspectiveQuad(
        topLeft = CropPoint(0.05f, 0.05f),
        topRight = CropPoint(0.95f, 0.45f),
        bottomRight = CropPoint(0.90f, 0.95f),
        bottomLeft = CropPoint(0.10f, 0.40f)
    )

    /** The frame boundary — the shape behind the physical-QA failure. Never viable. */
    private val frameBoundaryShape = PerspectiveQuad(
        topLeft = CropPoint(0.005f, 0.005f),
        topRight = CropPoint(0.995f, 0.005f),
        bottomRight = CropPoint(0.995f, 0.995f),
        bottomLeft = CropPoint(0.005f, 0.995f)
    )

    /** A clean page with visible margins. */
    private val strongShape = PerspectiveQuad(
        topLeft = CropPoint(0.16f, 0.20f),
        topRight = CropPoint(0.84f, 0.19f),
        bottomRight = CropPoint(0.85f, 0.80f),
        bottomLeft = CropPoint(0.15f, 0.81f)
    )

    @Test
    fun aWeakFrozenSeedLosesToAStrongCapturedCandidate() {
        // The exact physical-QA failure: a mappable but wall-corner-like seed must not win.
        val resolved = MainScanPolygonResolver.resolve(
            seed = seed(quad = weakShape),
            captureWidth = 3060,
            captureHeight = 4080,
            stillDetection = strongShape
        )
        assertEquals(MainScanPolygonSource.STILL_DETECTION, resolved.source)
        assertEquals(strongShape, resolved.quad)
    }

    @Test
    fun aGoodFrozenSeedBeatsAWeakCapturedCandidate() {
        val resolved = MainScanPolygonResolver.resolve(
            seed = seed(quad = strongShape),
            captureWidth = 3060,
            captureHeight = 4080,
            stillDetection = weakShape
        )
        assertEquals(MainScanPolygonSource.FROZEN_SEED, resolved.source)
        assertEquals(strongShape, resolved.quad)
    }

    @Test
    fun aNearFullFrameWeakSeedIsRejectedEntirely() {
        val resolved = MainScanPolygonResolver.resolve(
            seed = seed(quad = frameBoundaryShape),
            captureWidth = 3060,
            captureHeight = 4080,
            stillDetection = null
        )
        assertEquals(MainScanPolygonSource.FULL_FRAME, resolved.source)
    }

    @Test
    fun bothCandidatesInvalidFallsBackToFullFrame() {
        val resolved = MainScanPolygonResolver.resolve(
            seed = seed(quad = frameBoundaryShape),
            captureWidth = 3060,
            captureHeight = 4080,
            stillDetection = frameBoundaryShape
        )
        assertEquals(MainScanPolygonSource.FULL_FRAME, resolved.source)
    }

    @Test
    fun candidateSelectionIsDeterministic() {
        repeat(5) {
            val r = MainScanPolygonResolver.resolve(
                seed = seed(quad = weakShape),
                captureWidth = 3060,
                captureHeight = 4080,
                stillDetection = strongShape
            )
            assertEquals(MainScanPolygonSource.STILL_DETECTION, r.source)
        }
    }

    @Test
    fun anUnmappableSeedCannotCompeteEvenIfItScoresWell() {
        val resolved = MainScanPolygonResolver.resolve(
            seed = seed(analysisFrame = FrameSize(1280, 720), quad = strongShape),
            captureWidth = 3060,
            captureHeight = 4080,
            stillDetection = null
        )
        assertEquals(MainScanPolygonSource.FULL_FRAME, resolved.source)
    }

    @Test
    fun stillDetectionIsAlwaysRunSoTheSeedIsNeverUnopposed() {
        // Skipping detection when the seed looked mappable is how the weak seed won unopposed.
        assertTrue(MainScanPolygonResolver.needsStillDetection(seed(), 3060, 4080))
        assertTrue(MainScanPolygonResolver.needsStillDetection(null, 3060, 4080))
    }
}
