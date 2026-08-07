package com.dev.docscannerpdf.domain.crop

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Corner-role assignment in [PerspectiveGeometry.orderCorners].
 *
 * The property that matters is STRUCTURAL: the four supplied points must come back as four roles,
 * each point used exactly once. The previous implementation chose each role with its own extremum
 * scan, which does not partition the input — on a symmetric shape one point could win two scans and
 * a fourth point was dropped. The resulting quad had a duplicated corner, failed [isConvex], and the
 * crop was refused. A page photographed at 45 degrees is an ordinary diamond, so this was reachable
 * from normal use rather than from a contrived input.
 *
 * These tests therefore assert the multiset of output coordinates as hard as they assert the roles,
 * and they sweep all 24 input permutations, because the defect only surfaced for particular
 * argument orders.
 */
class PerspectiveGeometryOrderingTest {

    private fun point(x: Float, y: Float) = CropPoint(x, y)

    /** The 24 orderings of four points — the defect was sensitive to which one the caller supplied. */
    private fun permutations(points: List<CropPoint>): List<List<CropPoint>> {
        if (points.size <= 1) return listOf(points)
        return points.flatMap { head ->
            permutations(points - listOf(head)).map { listOf(head) + it }
        }
    }

    private fun assertSameMultiset(expected: List<CropPoint>, actual: List<CropPoint>) {
        val expectedSorted = expected.sortedWith(compareBy({ it.x }, { it.y }))
        val actualSorted = actual.sortedWith(compareBy({ it.x }, { it.y }))
        assertEquals(
            "the output must be a permutation of the input — no point invented, none dropped",
            expectedSorted,
            actualSorted
        )
    }

    private fun assertFourDistinctCorners(quad: PerspectiveQuad) {
        val corners = quad.corners()
        assertEquals(
            "a role was assigned twice — the extrema defect",
            4,
            corners.toSet().size
        )
    }

    // --- existing behaviour that must not change -----------------------------------------------

    @Test
    fun anAxisAlignedRectangleKeepsItsRoles() {
        val topLeft = point(0.1f, 0.2f)
        val topRight = point(0.9f, 0.2f)
        val bottomRight = point(0.9f, 0.8f)
        val bottomLeft = point(0.1f, 0.8f)

        val ordered = PerspectiveGeometry.orderCorners(
            listOf(topLeft, topRight, bottomRight, bottomLeft)
        )

        assertEquals(topLeft, ordered.topLeft)
        assertEquals(topRight, ordered.topRight)
        assertEquals(bottomRight, ordered.bottomRight)
        assertEquals(bottomLeft, ordered.bottomLeft)
    }

    @Test
    fun theFullFrameQuadIsUnchanged() {
        val full = PerspectiveQuad.full()
        assertEquals(full, PerspectiveGeometry.orderCorners(full.corners()))
    }

    @Test
    fun aScrambledRectangleIsCorrected() {
        // The same fixture CropEngineTest pins, restated here so this class stands alone.
        val scrambled = listOf(
            point(1f, 1f), // BR
            point(0f, 0f), // TL
            point(0f, 1f), // BL
            point(1f, 0f)  // TR
        )

        val ordered = PerspectiveGeometry.orderCorners(scrambled)

        assertEquals(point(0f, 0f), ordered.topLeft)
        assertEquals(point(1f, 0f), ordered.topRight)
        assertEquals(point(1f, 1f), ordered.bottomRight)
        assertEquals(point(0f, 1f), ordered.bottomLeft)
    }

    @Test
    fun anOrdinaryPerspectiveQuadKeepsItsRoles() {
        // Non-symmetric, the common case: a page shot slightly off-axis.
        val topLeft = point(0.18f, 0.12f)
        val topRight = point(0.86f, 0.21f)
        val bottomRight = point(0.79f, 0.88f)
        val bottomLeft = point(0.11f, 0.74f)

        val ordered = PerspectiveGeometry.orderCorners(
            listOf(topLeft, topRight, bottomRight, bottomLeft)
        )

        assertEquals(topLeft, ordered.topLeft)
        assertEquals(topRight, ordered.topRight)
        assertEquals(bottomRight, ordered.bottomRight)
        assertEquals(bottomLeft, ordered.bottomLeft)
    }

    // --- the authorised defect -------------------------------------------------------------------

    @Test
    fun theExactDiamondKeepsAllFourCorners() {
        // Top and left tips share x+y = 0.5, and right and bottom tips share x+y = 1.5. Under four
        // independent extremum scans one tip won two roles and another was dropped.
        val diamond = listOf(
            point(0.5f, 0.0f),
            point(1.0f, 0.5f),
            point(0.5f, 1.0f),
            point(0.0f, 0.5f)
        )

        val ordered = PerspectiveGeometry.orderCorners(diamond)

        assertFourDistinctCorners(ordered)
        assertSameMultiset(diamond, ordered.corners())
        assertTrue("the diamond must survive as a usable quad", PerspectiveGeometry.isValid(ordered))
    }

    @Test
    fun theNearDiamondRegressionKeepsAllFourCorners() {
        // The audit reproduction: nudged off exact symmetry, which is what a real 45-degree capture
        // produces. The extrema scans still collided here, so approximate symmetry was enough.
        val nearDiamond = listOf(
            point(0.5f, 0.02f),
            point(1.0f, 0.5f),
            point(0.5f, 1.0f),
            point(0.0f, 0.5f)
        )

        val ordered = PerspectiveGeometry.orderCorners(nearDiamond)

        assertFourDistinctCorners(ordered)
        assertSameMultiset(nearDiamond, ordered.corners())
        assertTrue(PerspectiveGeometry.isValid(ordered))
    }

    @Test
    fun aRotatedRectangleIsReturnedInCyclicOrder() {
        // A 4:3 rectangle turned ~30 degrees — no two corners share an axis.
        val rotated = listOf(
            point(0.30f, 0.10f),
            point(0.85f, 0.42f),
            point(0.65f, 0.86f),
            point(0.10f, 0.54f)
        )

        val ordered = PerspectiveGeometry.orderCorners(rotated)

        assertFourDistinctCorners(ordered)
        assertSameMultiset(rotated, ordered.corners())
        assertTrue("cyclic order means no crossed edges", PerspectiveGeometry.isConvex(ordered))
    }

    // --- permutation invariance ------------------------------------------------------------------

    @Test
    fun allTwentyFourPermutationsOfAConvexQuadAgree() {
        val quad = listOf(
            point(0.18f, 0.12f),
            point(0.86f, 0.21f),
            point(0.79f, 0.88f),
            point(0.11f, 0.74f)
        )
        val permutations = permutations(quad)
        assertEquals(24, permutations.size)

        val expected = PerspectiveGeometry.orderCorners(quad)
        for (permutation in permutations) {
            assertEquals(
                "argument order must not change the roles — was $permutation",
                expected,
                PerspectiveGeometry.orderCorners(permutation)
            )
        }
    }

    @Test
    fun allTwentyFourPermutationsOfTheDiamondAgree() {
        val diamond = listOf(
            point(0.5f, 0.0f),
            point(1.0f, 0.5f),
            point(0.5f, 1.0f),
            point(0.0f, 0.5f)
        )
        val permutations = permutations(diamond)
        assertEquals(24, permutations.size)

        val expected = PerspectiveGeometry.orderCorners(diamond)
        for (permutation in permutations) {
            val ordered = PerspectiveGeometry.orderCorners(permutation)
            assertEquals(
                "the symmetric tie must resolve identically for every input order — was $permutation",
                expected,
                ordered
            )
            assertFourDistinctCorners(ordered)
        }
    }

    // --- structural guarantees -------------------------------------------------------------------

    @Test
    fun theOutputCoordinatesAreExactlyTheInputCoordinates() {
        val inputs = listOf(
            listOf(point(0.1f, 0.2f), point(0.9f, 0.2f), point(0.9f, 0.8f), point(0.1f, 0.8f)),
            listOf(point(0.5f, 0f), point(1f, 0.5f), point(0.5f, 1f), point(0f, 0.5f)),
            listOf(point(0.30f, 0.10f), point(0.85f, 0.42f), point(0.65f, 0.86f), point(0.10f, 0.54f)),
            listOf(point(-0.2f, 0.1f), point(1.4f, 0.05f), point(1.1f, 1.3f), point(0.05f, 0.95f))
        )

        for (input in inputs) {
            val ordered = PerspectiveGeometry.orderCorners(input)
            assertSameMultiset(input, ordered.corners())
        }
    }

    @Test
    fun orderingAnAlreadyOrderedQuadIsIdempotent() {
        val inputs = listOf(
            PerspectiveQuad.full().corners(),
            PerspectiveQuad.inset(0.15f).corners(),
            listOf(point(0.5f, 0f), point(1f, 0.5f), point(0.5f, 1f), point(0f, 0.5f)),
            listOf(point(0.18f, 0.12f), point(0.86f, 0.21f), point(0.79f, 0.88f), point(0.11f, 0.74f))
        )

        for (input in inputs) {
            val once = PerspectiveGeometry.orderCorners(input)
            val twice = PerspectiveGeometry.orderCorners(once.corners())
            assertEquals("ordering must be a fixed point once applied", once, twice)
        }
    }

    @Test
    fun repeatedCallsAreDeterministic() {
        val quad = listOf(
            point(0.5f, 0.02f),
            point(1.0f, 0.5f),
            point(0.5f, 1.0f),
            point(0.0f, 0.5f)
        )
        val first = PerspectiveGeometry.orderCorners(quad)
        repeat(50) {
            assertEquals(first, PerspectiveGeometry.orderCorners(quad))
        }
    }

    @Test
    fun theResultCarriesTheWindingExistingConsumersExpect() {
        // TL -> TR -> BR -> BL over y-down coordinates is POSITIVE under the project's shoelace,
        // as PerspectiveQuad.full() is. MainScanCropEditor.preservesWinding compares this sign
        // across a drag, so flipping it here would silently invert every crop.
        assertTrue(PerspectiveGeometry.signedArea(PerspectiveQuad.full()) > 0f)

        val inputs = listOf(
            listOf(point(0.1f, 0.2f), point(0.9f, 0.2f), point(0.9f, 0.8f), point(0.1f, 0.8f)),
            listOf(point(0.5f, 0f), point(1f, 0.5f), point(0.5f, 1f), point(0f, 0.5f)),
            listOf(point(0.30f, 0.10f), point(0.85f, 0.42f), point(0.65f, 0.86f), point(0.10f, 0.54f)),
            // Supplied counter-clockwise: the ordering must correct it, not preserve it.
            listOf(point(0.1f, 0.2f), point(0.1f, 0.8f), point(0.9f, 0.8f), point(0.9f, 0.2f))
        )

        for (input in inputs) {
            val ordered = PerspectiveGeometry.orderCorners(input)
            assertTrue(
                "clockwise TL/TR/BR/BL must stay positively wound — was $ordered",
                PerspectiveGeometry.signedArea(ordered) > 0f
            )
        }
    }

    @Test
    fun validConvexInputStaysConvexAndValid() {
        val inputs = listOf(
            PerspectiveQuad.full().corners(),
            PerspectiveQuad.inset(0.2f).corners(),
            listOf(point(0.5f, 0f), point(1f, 0.5f), point(0.5f, 1f), point(0f, 0.5f)),
            listOf(point(0.5f, 0.02f), point(1f, 0.5f), point(0.5f, 1f), point(0f, 0.5f)),
            listOf(point(0.30f, 0.10f), point(0.85f, 0.42f), point(0.65f, 0.86f), point(0.10f, 0.54f)),
            listOf(point(0.18f, 0.12f), point(0.86f, 0.21f), point(0.79f, 0.88f), point(0.11f, 0.74f))
        )

        for (input in inputs) {
            val ordered = PerspectiveGeometry.orderCorners(input)
            assertTrue("convex for $input", PerspectiveGeometry.isConvex(ordered))
            assertTrue("valid for $input", PerspectiveGeometry.isValid(ordered))
        }
    }

    @Test
    fun duplicateInputDoesNotProduceAFabricatedCoordinate() {
        // Two identical points is not a quadrilateral. Ordering must not paper over that by
        // inventing a fourth corner — the shape has to stay detectably degenerate so isValid
        // rejects it, exactly as it did before.
        val duplicated = listOf(
            point(0.2f, 0.2f),
            point(0.2f, 0.2f),
            point(0.8f, 0.8f),
            point(0.2f, 0.8f)
        )

        val ordered = PerspectiveGeometry.orderCorners(duplicated)

        assertSameMultiset(duplicated, ordered.corners())
        assertEquals("the duplicate must survive as a duplicate", 3, ordered.corners().toSet().size)
        assertTrue(
            "a degenerate input must stay rejected",
            !PerspectiveGeometry.isValid(ordered)
        )
    }
}
