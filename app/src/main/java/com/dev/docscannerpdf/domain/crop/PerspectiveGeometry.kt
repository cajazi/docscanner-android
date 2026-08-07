package com.dev.docscannerpdf.domain.crop

import kotlin.math.abs
import kotlin.math.atan2

/**
 * Pure geometry helpers for crop quads: convexity validation, signed area, and reordering of
 * arbitrary corners into a proper clockwise TL/TR/BR/BL quad (which auto-fixes inverted or
 * crossed inputs). No Android dependencies, so all of this is unit-testable.
 */
object PerspectiveGeometry {

    private const val MIN_AREA = 1e-4f

    /** Shoelace signed area of the quad in its current corner order. */
    fun signedArea(quad: PerspectiveQuad): Float {
        val pts = quad.corners()
        var sum = 0f
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            sum += a.x * b.y - b.x * a.y
        }
        return sum / 2f
    }

    /**
     * True when the four corners form a strictly convex polygon (no crossed/collinear edges).
     * Checks that every consecutive edge turns the same direction.
     */
    fun isConvex(quad: PerspectiveQuad): Boolean {
        val pts = quad.corners()
        var sign = 0
        for (i in pts.indices) {
            val a = pts[i]
            val b = pts[(i + 1) % pts.size]
            val c = pts[(i + 2) % pts.size]
            val cross = (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
            if (abs(cross) < 1e-6f) return false // collinear -> degenerate
            val currentSign = if (cross > 0f) 1 else -1
            if (sign == 0) {
                sign = currentSign
            } else if (currentSign != sign) {
                return false
            }
        }
        return true
    }

    /** A quad is valid for warping when it is convex and encloses a non-trivial area. */
    fun isValid(quad: PerspectiveQuad): Boolean =
        isConvex(quad) && abs(signedArea(quad)) >= MIN_AREA

    /**
     * Reorders four arbitrary points into a clockwise TL/TR/BR/BL quad, correcting inverted or
     * out-of-order corner inputs.
     *
     * This walks the points around their centroid rather than selecting each role by its own
     * extremum. The previous implementation picked four roles with four INDEPENDENT scans —
     * smallest x+y for top-left, largest for bottom-right, smallest y-x for top-right, largest for
     * bottom-left. Those scans do not partition the input: on a symmetric or near-symmetric shape
     * two of them can land on the same point, so that point took two roles and a fourth point was
     * dropped entirely. A diamond is the plain case — its top and left tips share the same x+y —
     * and a diamond is just a page photographed at 45 degrees. The quad that came back had a
     * duplicated corner, failed [isConvex], and the whole crop was refused as unusable.
     *
     * A cyclic walk cannot lose or duplicate a point: the four sorted positions are consumed once
     * each, so the output is always a permutation of the input. Only the starting corner needs
     * choosing, and that choice is made from the coordinates alone.
     *
     * The supplied coordinates are passed through exactly. The centroid is used only to measure
     * angles; nothing is averaged, clamped, projected or synthesised, so a genuinely degenerate
     * input (two identical points) stays degenerate and is still rejected downstream rather than
     * being quietly repaired into a plausible-looking quad.
     */
    fun orderCorners(points: List<CropPoint>): PerspectiveQuad {
        require(points.size == 4) { "A quad requires exactly 4 points" }

        val centerX = points.sumOf { it.x.toDouble() } / 4.0
        val centerY = points.sumOf { it.y.toDouble() } / 4.0

        // Normalized image space has y pointing DOWN, so increasing atan2 walks clockwise on screen
        // — exactly the TL -> TR -> BR -> BL direction the quad contract expects. The trailing keys
        // apply only when two points share an angle (they are collinear with the centroid, which is
        // already degenerate); they keep the order a function of the COORDINATES rather than of the
        // caller's argument order, so every permutation of the same four points sorts identically.
        val clockwise = points.sortedWith(
            compareBy<CropPoint>(
                { atan2(it.y - centerY, it.x - centerX) },
                { (it.x + it.y).toDouble() },
                { it.y.toDouble() },
                { it.x.toDouble() }
            )
        )

        // Anchor the cycle at the most top-left corner: smallest x+y, ties broken by the higher
        // point, then the more leftward one. A diamond ties two corners on x+y, and resolving that
        // tie is precisely what the old heuristic got wrong; here it only rotates the starting
        // point of a cycle that already contains all four corners. A remaining exact tie means two
        // identical coordinates, and the earliest cyclic position wins so the result stays total.
        var start = 0
        for (index in 1 until 4) {
            if (isBetterTopLeftAnchor(clockwise[index], clockwise[start])) start = index
        }

        return PerspectiveQuad(
            topLeft = clockwise[start],
            topRight = clockwise[(start + 1) % 4],
            bottomRight = clockwise[(start + 2) % 4],
            bottomLeft = clockwise[(start + 3) % 4]
        )
    }

    /** Strictly "sits further top-left than": smallest x+y, then highest, then leftmost. */
    private fun isBetterTopLeftAnchor(candidate: CropPoint, incumbent: CropPoint): Boolean {
        val candidateSum = candidate.x + candidate.y
        val incumbentSum = incumbent.x + incumbent.y
        if (candidateSum != incumbentSum) return candidateSum < incumbentSum
        if (candidate.y != incumbent.y) return candidate.y < incumbent.y
        return candidate.x < incumbent.x
    }

    /** Reorders the quad's own corners so the result is a proper, non-inverted clockwise quad. */
    fun autoFixInverted(quad: PerspectiveQuad): PerspectiveQuad = orderCorners(quad.corners())

    /** Clamps every corner into the unit square. */
    fun clampToUnit(quad: PerspectiveQuad): PerspectiveQuad = PerspectiveQuad(
        topLeft = quad.topLeft.clampedToUnit(),
        topRight = quad.topRight.clampedToUnit(),
        bottomRight = quad.bottomRight.clampedToUnit(),
        bottomLeft = quad.bottomLeft.clampedToUnit()
    )

    /** Clamp + reorder into a valid, warp-ready quad. */
    fun normalize(quad: PerspectiveQuad): PerspectiveQuad = autoFixInverted(clampToUnit(quad))
}
