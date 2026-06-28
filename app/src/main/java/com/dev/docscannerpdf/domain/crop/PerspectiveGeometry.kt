package com.dev.docscannerpdf.domain.crop

import kotlin.math.abs

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
     * Reorders four arbitrary points into a clockwise TL/TR/BR/BL quad. Uses the standard
     * sum/difference heuristic: top-left has the smallest x+y, bottom-right the largest;
     * top-right has the smallest y-x, bottom-left the largest. This corrects inverted or
     * out-of-order corner inputs.
     */
    fun orderCorners(points: List<CropPoint>): PerspectiveQuad {
        require(points.size == 4) { "A quad requires exactly 4 points" }
        val topLeft = points.minByOrNull { it.x + it.y }!!
        val bottomRight = points.maxByOrNull { it.x + it.y }!!
        val topRight = points.minByOrNull { it.y - it.x }!!
        val bottomLeft = points.maxByOrNull { it.y - it.x }!!
        return PerspectiveQuad(topLeft, topRight, bottomRight, bottomLeft)
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
