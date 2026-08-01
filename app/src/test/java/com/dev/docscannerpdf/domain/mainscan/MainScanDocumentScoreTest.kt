package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Document-likeness scoring. Built after physical QA showed a wall corner beating the real package:
 * the previous rule asked only "is this a convex quad of reasonable area?", which a wall corner
 * satisfies perfectly.
 *
 * The tests come in pairs on purpose — every shape that must be rejected is matched against a
 * legitimate document that must NOT be, because the easy way to kill false positives is a threshold
 * that also kills real close-up pages, and that failure is invisible to the user.
 */
class MainScanDocumentScoreTest {

    /** A page photographed straight on, with visible margins. */
    private val cleanDocument = PerspectiveQuad(
        topLeft = CropPoint(0.14f, 0.18f),
        topRight = CropPoint(0.86f, 0.17f),
        bottomRight = CropPoint(0.87f, 0.82f),
        bottomLeft = CropPoint(0.13f, 0.83f)
    )

    /** A page shot at an angle — real perspective convergence, still clearly a page. */
    private val tiltedDocument = PerspectiveQuad(
        topLeft = CropPoint(0.22f, 0.20f),
        topRight = CropPoint(0.80f, 0.14f),
        bottomRight = CropPoint(0.86f, 0.79f),
        bottomLeft = CropPoint(0.16f, 0.85f)
    )

    /** A genuine close-up: large, but corners are not pinned to the border. */
    private val closeUpDocument = PerspectiveQuad(
        topLeft = CropPoint(0.06f, 0.07f),
        topRight = CropPoint(0.94f, 0.06f),
        bottomRight = CropPoint(0.95f, 0.93f),
        bottomLeft = CropPoint(0.05f, 0.94f)
    )

    /** The frame boundary itself — corners pinned to the edges. */
    private val frameBoundary = PerspectiveQuad(
        topLeft = CropPoint(0.005f, 0.005f),
        topRight = CropPoint(0.995f, 0.005f),
        bottomRight = CropPoint(0.995f, 0.995f),
        bottomLeft = CropPoint(0.005f, 0.995f)
    )

    /** A wall corner: a wedge whose edges diverge and whose corners are far from right. */
    private val wallCorner = PerspectiveQuad(
        topLeft = CropPoint(0.05f, 0.05f),
        topRight = CropPoint(0.95f, 0.45f),
        bottomRight = CropPoint(0.90f, 0.95f),
        bottomLeft = CropPoint(0.10f, 0.40f)
    )

    /**
     * A page rotated roughly 45 degrees. Diamond-SHAPED on screen, but it has right angles and
     * parallel opposite edges, so it is a genuine document and must NOT be penalised — people
     * photograph pages at an angle constantly.
     */
    private val rotatedDocument = PerspectiveQuad(
        topLeft = CropPoint(0.50f, 0.12f),
        topRight = CropPoint(0.88f, 0.50f),
        bottomRight = CropPoint(0.50f, 0.88f),
        bottomLeft = CropPoint(0.12f, 0.50f)
    )

    /** A sheared parallelogram: opposite sides parallel and equal, corners far from right. */
    private val parallelogram = PerspectiveQuad(
        topLeft = CropPoint(0.30f, 0.20f),
        topRight = CropPoint(0.90f, 0.20f),
        bottomRight = CropPoint(0.70f, 0.80f),
        bottomLeft = CropPoint(0.10f, 0.80f)
    )

    private fun score(quad: PerspectiveQuad) = MainScanDocumentScore.overall(quad)

    // --- genuine documents must score well -----------------------------------------------------

    @Test
    fun aCleanDocumentScoresHighly() {
        assertTrue("clean=${score(cleanDocument)}", score(cleanDocument) > 0.80f)
        assertTrue(MainScanDocumentScore.isViable(cleanDocument))
    }

    @Test
    fun aTiltedDocumentIsStillAccepted() {
        // Perspective must not be punished — this is the ordinary way people photograph pages.
        assertTrue("tilted=${score(tiltedDocument)}", MainScanDocumentScore.isViable(tiltedDocument))
    }

    @Test
    fun aLargeGenuineCloseUpIsAccepted() {
        // The regression risk of fixing the wall corner with an area threshold: this must survive.
        assertTrue("closeUp=${score(closeUpDocument)}", MainScanDocumentScore.isViable(closeUpDocument))
    }

    // --- non-documents must lose ------------------------------------------------------------------

    @Test
    fun aWallCornerScoresBelowEveryGenuineDocument() {
        val wall = score(wallCorner)
        assertTrue("wall=$wall vs clean=${score(cleanDocument)}", wall < score(cleanDocument))
        assertTrue("wall=$wall vs tilted=${score(tiltedDocument)}", wall < score(tiltedDocument))
        assertTrue("wall=$wall vs closeUp=${score(closeUpDocument)}", wall < score(closeUpDocument))
    }

    @Test
    fun aRotatedPageIsNotPenalisedForBeingDiamondShaped() {
        // The over-tuning trap: rejecting "diamonds" outright would reject every angled page.
        // Right angles and parallel edges are what matter, not screen-axis alignment.
        assertTrue(
            "rotatedDocument=${score(rotatedDocument)}",
            MainScanDocumentScore.isViable(rotatedDocument)
        )
        assertTrue(score(rotatedDocument) > 0.80f)
    }

    @Test
    fun aParallelogramIsPenalisedDespitePerfectSideSymmetry() {
        // Opposite sides are exactly equal and parallel — the detector's own confidence rates this
        // highly. Corner angles are what expose it.
        assertTrue(
            "parallelogram=${score(parallelogram)}",
            score(parallelogram) < score(cleanDocument)
        )
    }

    @Test
    fun theFrameBoundaryIsPenalisedForHuggingTheEdges() {
        // Near-perfect rectangle, so angles and parallelism are ideal; only the boundary and
        // coverage components can tell it apart from a document.
        assertTrue(
            "boundary=${score(frameBoundary)} closeUp=${score(closeUpDocument)}",
            score(frameBoundary) < score(closeUpDocument)
        )
    }

    @Test
    fun aNonConvexShapeScoresZero() {
        val crossed = PerspectiveQuad(
            topLeft = CropPoint(0.1f, 0.1f),
            topRight = CropPoint(0.9f, 0.9f),
            bottomRight = CropPoint(0.9f, 0.1f),
            bottomLeft = CropPoint(0.1f, 0.9f)
        )
        assertEquals(0f, score(crossed), 1e-5f)
        assertFalse(MainScanDocumentScore.isViable(crossed))
    }

    @Test
    fun aSliverIsNotViable() {
        val sliver = PerspectiveQuad(
            topLeft = CropPoint(0.05f, 0.50f),
            topRight = CropPoint(0.95f, 0.50f),
            bottomRight = CropPoint(0.95f, 0.54f),
            bottomLeft = CropPoint(0.05f, 0.54f)
        )
        assertFalse(MainScanDocumentScore.isViable(sliver))
    }

    // --- determinism -------------------------------------------------------------------------------

    @Test
    fun scoringIsDeterministic() {
        repeat(5) {
            assertEquals(score(cleanDocument), score(cleanDocument), 0f)
            assertEquals(score(wallCorner), score(wallCorner), 0f)
        }
    }

    @Test
    fun everyComponentStaysInRange() {
        for (quad in listOf(
            cleanDocument, tiltedDocument, closeUpDocument,
            frameBoundary, wallCorner, rotatedDocument, parallelogram
        )) {
            val s = MainScanDocumentScore.score(quad)
            for ((name, value) in listOf(
                "cornerAngle" to s.cornerAngle,
                "parallelism" to s.parallelism,
                "coverage" to s.coverage,
                "boundary" to s.boundary,
                "minimumSide" to s.minimumSide,
                "overall" to s.overall
            )) {
                assertTrue("$name out of range: $value", value in 0f..1f)
            }
        }
    }
}
