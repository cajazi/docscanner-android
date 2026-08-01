package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import kotlin.math.abs
import kotlin.math.hypot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MainScanQuadValidityTest {

    @Test
    fun fullFrameIsApplicable() {
        assertTrue(MainScanQuadValidity.isApplicable(PerspectiveQuad.full()))
    }

    @Test
    fun ordinaryInsetPageIsApplicable() {
        assertTrue(MainScanQuadValidity.isApplicable(PerspectiveQuad.inset(0.12f)))
    }

    @Test
    fun rotatedConvexPageIsApplicable() {
        val quad = PerspectiveQuad(
            topLeft = CropPoint(0.50f, 0.10f),
            topRight = CropPoint(0.90f, 0.50f),
            bottomRight = CropPoint(0.50f, 0.90f),
            bottomLeft = CropPoint(0.10f, 0.50f)
        )

        assertTrue(MainScanQuadValidity.isApplicable(quad))
    }

    @Test
    fun perspectiveTrapezoidIsApplicable() {
        val quad = PerspectiveQuad(
            topLeft = CropPoint(0.20f, 0.10f),
            topRight = CropPoint(0.80f, 0.15f),
            bottomRight = CropPoint(0.70f, 0.90f),
            bottomLeft = CropPoint(0.25f, 0.85f)
        )

        assertTrue(MainScanQuadValidity.isApplicable(quad))
    }

    @Test
    fun longNarrowReceiptIsApplicableWhenThresholdsPass() {
        val quad = rectangle(
            left = 0.46f,
            top = 0.10f,
            right = 0.54f,
            bottom = 0.90f
        )

        assertTrue(MainScanQuadValidity.isApplicable(quad))
    }

    @Test
    fun selfIntersectingBowTieIsRejected() {
        val quad = PerspectiveQuad(
            topLeft = CropPoint(0.10f, 0.10f),
            topRight = CropPoint(0.90f, 0.90f),
            bottomRight = CropPoint(0.90f, 0.10f),
            bottomLeft = CropPoint(0.10f, 0.90f)
        )

        assertFalse(MainScanQuadValidity.isApplicable(quad))
    }

    @Test
    fun collinearQuadrilateralIsRejected() {
        val quad = PerspectiveQuad(
            topLeft = CropPoint(0.10f, 0.50f),
            topRight = CropPoint(0.30f, 0.50f),
            bottomRight = CropPoint(0.70f, 0.50f),
            bottomLeft = CropPoint(0.90f, 0.50f)
        )

        assertFalse(MainScanQuadValidity.isApplicable(quad))
    }

    @Test
    fun zeroAreaQuadrilateralIsRejected() {
        val point = CropPoint(0.50f, 0.50f)
        val quad = PerspectiveQuad(
            topLeft = point,
            topRight = point,
            bottomRight = point,
            bottomLeft = point
        )

        assertFalse(MainScanQuadValidity.isApplicable(quad))
    }

    @Test
    fun areaJustBelowMinimumIsRejected() {
        val quad = rectangle(
            left = 0.10f,
            top = 0.10f,
            right = 0.30f,
            bottom = 0.199f
        )

        assertTrue(abs(PerspectiveGeometry.signedArea(quad)) < 0.02f)
        assertFalse(MainScanQuadValidity.isApplicable(quad))
    }

    @Test
    fun areaAtMinimumIsAcceptedWhenSeparationsPass() {
        val quad = rectangle(
            left = 0.10f,
            top = 0.10f,
            right = 0.30f,
            bottom = 0.20f
        )

        assertTrue(abs(PerspectiveGeometry.signedArea(quad)) >= 0.02f)
        assertTrue(MainScanQuadValidity.isApplicable(quad))
    }

    @Test
    fun topBottomSeparationBelowMinimumIsRejected() {
        val quad = rectangle(
            left = 0.05f,
            top = 0.10f,
            right = 0.95f,
            bottom = 0.149f
        )

        assertTrue(abs(PerspectiveGeometry.signedArea(quad)) > 0.02f)
        assertFalse(MainScanQuadValidity.isApplicable(quad))
    }

    @Test
    fun topBottomSeparationAtMinimumIsAccepted() {
        val quad = rectangle(
            left = 0.10f,
            top = 0.10f,
            right = 0.90f,
            bottom = 0.15f
        )

        assertTrue(MainScanQuadValidity.isApplicable(quad))
    }

    @Test
    fun leftRightSeparationBelowMinimumIsRejected() {
        val quad = rectangle(
            left = 0.10f,
            top = 0.05f,
            right = 0.149f,
            bottom = 0.95f
        )

        assertTrue(abs(PerspectiveGeometry.signedArea(quad)) > 0.02f)
        assertFalse(MainScanQuadValidity.isApplicable(quad))
    }

    @Test
    fun leftRightSeparationAtMinimumIsAccepted() {
        val quad = rectangle(
            left = 0.10f,
            top = 0.10f,
            right = 0.15f,
            bottom = 0.90f
        )

        assertTrue(MainScanQuadValidity.isApplicable(quad))
    }

    @Test
    fun reversedWindingIsAcceptedWhenOtherRulesPass() {
        val quad = PerspectiveQuad(
            topLeft = CropPoint(0.10f, 0.10f),
            topRight = CropPoint(0.10f, 0.90f),
            bottomRight = CropPoint(0.90f, 0.90f),
            bottomLeft = CropPoint(0.90f, 0.10f)
        )

        assertTrue(PerspectiveGeometry.signedArea(quad) < 0f)
        assertTrue(MainScanQuadValidity.isApplicable(quad))
    }

    @Test
    fun qualifyingOutOfBoundsQuadrilateralIsAccepted() {
        val quad = rectangle(
            left = -0.10f,
            top = -0.10f,
            right = 1.10f,
            bottom = 1.10f
        )

        assertTrue(MainScanQuadValidity.isApplicable(quad))
    }

    @Test
    fun repeatedEvaluationIsDeterministic() {
        val quad = PerspectiveQuad.inset(0.17f)
        val expected = MainScanQuadValidity.isApplicable(quad)

        repeat(100) {
            assertEquals(expected, MainScanQuadValidity.isApplicable(quad))
        }
    }

    @Test
    fun subpixelCoordinatesAreDeterministic() {
        val quad = PerspectiveQuad(
            topLeft = CropPoint(0.1234f, 0.1111f),
            topRight = CropPoint(0.8765f, 0.1555f),
            bottomRight = CropPoint(0.8123f, 0.9012f),
            bottomLeft = CropPoint(0.1678f, 0.8444f)
        )

        assertTrue(MainScanQuadValidity.isApplicable(quad))

        repeat(100) {
            assertTrue(MainScanQuadValidity.isApplicable(quad))
        }
    }

    @Test
    fun extractedPredicateMatchesReferenceCorpus() {
        val corpus = listOf(
            PerspectiveQuad.full(),
            PerspectiveQuad.inset(0.12f),
            rectangle(0.10f, 0.10f, 0.30f, 0.199f),
            rectangle(0.10f, 0.10f, 0.30f, 0.20f),
            rectangle(0.05f, 0.10f, 0.95f, 0.149f),
            rectangle(0.10f, 0.10f, 0.90f, 0.15f),
            rectangle(0.10f, 0.05f, 0.149f, 0.95f),
            rectangle(0.10f, 0.10f, 0.15f, 0.90f),
            rectangle(-0.10f, -0.10f, 1.10f, 1.10f),
            PerspectiveQuad(
                topLeft = CropPoint(0.50f, 0.10f),
                topRight = CropPoint(0.90f, 0.50f),
                bottomRight = CropPoint(0.50f, 0.90f),
                bottomLeft = CropPoint(0.10f, 0.50f)
            ),
            PerspectiveQuad(
                topLeft = CropPoint(0.20f, 0.10f),
                topRight = CropPoint(0.80f, 0.15f),
                bottomRight = CropPoint(0.70f, 0.90f),
                bottomLeft = CropPoint(0.25f, 0.85f)
            ),
            PerspectiveQuad(
                topLeft = CropPoint(0.10f, 0.10f),
                topRight = CropPoint(0.90f, 0.90f),
                bottomRight = CropPoint(0.90f, 0.10f),
                bottomLeft = CropPoint(0.10f, 0.90f)
            ),
            PerspectiveQuad(
                topLeft = CropPoint(0.10f, 0.10f),
                topRight = CropPoint(0.10f, 0.90f),
                bottomRight = CropPoint(0.90f, 0.90f),
                bottomLeft = CropPoint(0.90f, 0.10f)
            ),
            PerspectiveQuad(
                topLeft = CropPoint(0.1234f, 0.1111f),
                topRight = CropPoint(0.8765f, 0.1555f),
                bottomRight = CropPoint(0.8123f, 0.9012f),
                bottomLeft = CropPoint(0.1678f, 0.8444f)
            )
        )

        corpus.forEachIndexed { index, quad ->
            assertEquals(
                "Predicate mismatch for corpus item $index: $quad",
                referenceIsApplicable(quad),
                MainScanQuadValidity.isApplicable(quad)
            )
        }
    }

    private fun referenceIsApplicable(quad: PerspectiveQuad): Boolean {
        if (!PerspectiveGeometry.isConvex(quad)) return false
        if (abs(PerspectiveGeometry.signedArea(quad)) < 0.02f) return false

        val topBottomSeparation = referenceMidpointDistance(
            quad.topLeft,
            quad.topRight,
            quad.bottomLeft,
            quad.bottomRight
        )

        val leftRightSeparation = referenceMidpointDistance(
            quad.topLeft,
            quad.bottomLeft,
            quad.topRight,
            quad.bottomRight
        )

        return topBottomSeparation >= 0.05f &&
            leftRightSeparation >= 0.05f
    }

    private fun referenceMidpointDistance(
        a1: CropPoint,
        a2: CropPoint,
        b1: CropPoint,
        b2: CropPoint
    ): Float {
        val firstMidpointX = (a1.x + a2.x) / 2f
        val firstMidpointY = (a1.y + a2.y) / 2f
        val secondMidpointX = (b1.x + b2.x) / 2f
        val secondMidpointY = (b1.y + b2.y) / 2f

        return hypot(
            firstMidpointX - secondMidpointX,
            firstMidpointY - secondMidpointY
        )
    }

    private fun rectangle(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): PerspectiveQuad = PerspectiveQuad(
        topLeft = CropPoint(left, top),
        topRight = CropPoint(right, top),
        bottomRight = CropPoint(right, bottom),
        bottomLeft = CropPoint(left, bottom)
    )
}
