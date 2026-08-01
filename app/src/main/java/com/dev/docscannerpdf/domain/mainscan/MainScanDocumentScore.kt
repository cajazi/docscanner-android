package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.hypot
import kotlin.math.min

/** The component scores behind a document-likeness verdict, kept separate so failures are legible. */
data class MainScanShapeScore(
    val cornerAngle: Float,
    val parallelism: Float,
    val coverage: Float,
    val boundary: Float,
    val minimumSide: Float,
    val convex: Boolean
) {
    /**
     * Weighted overall score in 0..1. A non-convex shape scores zero outright — it is not a
     * quadrilateral a page could occupy, so no combination of other merits should rescue it.
     *
     * [boundary] is applied as a MULTIPLIER rather than another weighted term. As an additive
     * component it cannot do its job: the image boundary is a geometrically perfect rectangle, so it
     * scores maximum on angles, parallelism and side length, and a 20% additive penalty still leaves
     * it comfortably above any threshold. It has to scale the whole result, because "this shape is
     * the frame" invalidates the rectangularity evidence rather than merely offsetting it.
     */
    val overall: Float
        get() = if (!convex) {
            0f
        } else {
            val shape = W_CORNER_ANGLE * cornerAngle +
                W_PARALLELISM * parallelism +
                W_COVERAGE * coverage +
                W_MINIMUM_SIDE * minimumSide
            shape * (1f - BOUNDARY_INFLUENCE * (1f - boundary))
        }

    private companion object {
        const val W_CORNER_ANGLE = 0.36f
        const val W_PARALLELISM = 0.31f
        const val W_COVERAGE = 0.23f
        const val W_MINIMUM_SIDE = 0.10f

        /** How much a fully edge-hugging shape is discounted: 0.75 leaves it a quarter of its score. */
        const val BOUNDARY_INFLUENCE = 0.75f
    }
}

/**
 * Scores how much a quadrilateral looks like a photographed document.
 *
 * ## Why this exists
 *
 * The detector's own confidence measures opposite-side LENGTH symmetry and frame coverage. Those
 * are satisfied by a great many things that are not documents: a wall corner, a table edge, a large
 * trapezoid of shadow, or the image boundary itself. In physical QA a seed covering almost the whole
 * frame was selected over the actual package, because the previous resolver asked only "is this a
 * convex quad of reasonable area?" — and a wall corner is exactly that.
 *
 * What separates a page from those shapes is its GEOMETRY: a rectangle viewed through a camera has
 * near-right corners, near-parallel opposite edges, and sits inside the frame rather than hugging
 * its border. This scores those properties.
 *
 * ## Deliberately graded, not thresholded
 *
 * Every component returns a continuous 0..1 value rather than a pass/fail. A single hard threshold
 * tuned to reject wall corners would also reject a legitimate close-up page shot at a steep angle —
 * trading a visible false positive for an invisible false negative, which is the worse failure
 * because the user cannot tell it happened. Grading lets a strong candidate beat a weak one on the
 * balance of evidence, which is what [MainScanPolygonResolver] needs.
 */
object MainScanDocumentScore {

    /** Corners nearer than this to the image border count as hugging it (fraction of the frame). */
    const val BOUNDARY_MARGIN = 0.02f

    /** Below this overall score a candidate is not offered at all. */
    const val VIABILITY_FLOOR = 0.45f

    /** Angular deviation from 90 degrees at which corner quality reaches zero. */
    private const val CORNER_TOLERANCE_DEGREES = 55f

    /** Angle between opposite edges at which parallelism reaches zero. */
    private const val PARALLEL_TOLERANCE_DEGREES = 40f

    /** Sensible document coverage band; outside it, coverage tapers rather than cliff-edges. */
    private const val COVERAGE_IDEAL_LOW = 0.12f
    private const val COVERAGE_IDEAL_HIGH = 0.88f

    /** Shortest side, as a fraction of the frame, at which the side score reaches 1. */
    private const val GOOD_MIN_SIDE = 0.25f

    fun score(quad: PerspectiveQuad): MainScanShapeScore {
        val convex = PerspectiveGeometry.isConvex(quad)
        return MainScanShapeScore(
            cornerAngle = cornerAngleScore(quad),
            parallelism = parallelismScore(quad),
            coverage = coverageScore(quad),
            boundary = boundaryScore(quad),
            minimumSide = minimumSideScore(quad),
            convex = convex
        )
    }

    fun overall(quad: PerspectiveQuad): Float = score(quad).overall

    /** Whether a candidate is good enough to be offered as a starting polygon at all. */
    fun isViable(quad: PerspectiveQuad): Boolean =
        MainScanQuadValidity.isApplicable(quad) && overall(quad) >= VIABILITY_FLOOR

    /**
     * Mean corner quality. A photographed rectangle keeps near-right angles; a rhombus or a wedge of
     * shadow does not. Perspective skews them, hence the generous tolerance.
     */
    private fun cornerAngleScore(quad: PerspectiveQuad): Float {
        val c = quad.corners()
        var total = 0f
        for (i in c.indices) {
            val previous = c[(i + 3) % 4]
            val current = c[i]
            val next = c[(i + 1) % 4]
            val angle = angleAt(previous, current, next)
            val deviation = abs(angle - 90f)
            total += (1f - deviation / CORNER_TOLERANCE_DEGREES).coerceIn(0f, 1f)
        }
        return total / c.size
    }

    /**
     * How parallel the two pairs of opposite edges are. A page's opposite edges converge only
     * slightly under perspective; a wall corner's diverge sharply.
     */
    private fun parallelismScore(quad: PerspectiveQuad): Float {
        val c = quad.corners()
        val topAngle = edgeAngle(c[0], c[1])
        val bottomAngle = edgeAngle(c[3], c[2])
        val leftAngle = edgeAngle(c[0], c[3])
        val rightAngle = edgeAngle(c[1], c[2])
        val horizontal = 1f - angularDistance(topAngle, bottomAngle) / PARALLEL_TOLERANCE_DEGREES
        val vertical = 1f - angularDistance(leftAngle, rightAngle) / PARALLEL_TOLERANCE_DEGREES
        return ((horizontal.coerceIn(0f, 1f) + vertical.coerceIn(0f, 1f)) / 2f)
    }

    /**
     * Area plausibility. A speck is not a document; neither, usually, is a shape occupying the
     * entire frame — that is far more often the image boundary than a page. Both ends taper.
     */
    private fun coverageScore(quad: PerspectiveQuad): Float {
        val area = abs(PerspectiveGeometry.signedArea(quad)).coerceIn(0f, 1f)
        return when {
            area < COVERAGE_IDEAL_LOW -> (area / COVERAGE_IDEAL_LOW).coerceIn(0f, 1f)
            area > COVERAGE_IDEAL_HIGH ->
                ((1f - area) / (1f - COVERAGE_IDEAL_HIGH)).coerceIn(0f, 1f)
            else -> 1f
        }
    }

    /**
     * Penalises corners pinned to the image border.
     *
     * This is the component that separates "large document photographed close up" from "the frame
     * itself". A genuine close-up page still leaves a sliver of background at its corners; a
     * boundary-latched detection has corners sitting exactly on the edge. Coverage alone cannot tell
     * them apart, which is why area is not simply thresholded.
     */
    private fun boundaryScore(quad: PerspectiveQuad): Float {
        val hugging = quad.corners().count { point ->
            point.x <= BOUNDARY_MARGIN || point.x >= 1f - BOUNDARY_MARGIN ||
                point.y <= BOUNDARY_MARGIN || point.y >= 1f - BOUNDARY_MARGIN
        }
        return (1f - hugging / 4f).coerceIn(0f, 1f)
    }

    /** Shortest side length, so slivers and hairlines cannot score well on other components. */
    private fun minimumSideScore(quad: PerspectiveQuad): Float {
        val c = quad.corners()
        var shortest = Float.MAX_VALUE
        for (i in c.indices) {
            val length = distance(c[i], c[(i + 1) % 4])
            shortest = min(shortest, length)
        }
        if (shortest == Float.MAX_VALUE) return 0f
        return (shortest / GOOD_MIN_SIDE).coerceIn(0f, 1f)
    }

    /** Interior angle at [vertex], in degrees. */
    private fun angleAt(previous: CropPoint, vertex: CropPoint, next: CropPoint): Float {
        val ax = previous.x - vertex.x
        val ay = previous.y - vertex.y
        val bx = next.x - vertex.x
        val by = next.y - vertex.y
        val magnitudeA = hypot(ax, ay)
        val magnitudeB = hypot(bx, by)
        if (magnitudeA <= 1e-6f || magnitudeB <= 1e-6f) return 0f
        val cosine = ((ax * bx + ay * by) / (magnitudeA * magnitudeB)).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine).toDouble()).toFloat()
    }

    /** Edge direction in degrees, folded to 0..180 so direction of travel does not matter. */
    private fun edgeAngle(from: CropPoint, to: CropPoint): Float {
        val degrees = Math.toDegrees(
            kotlin.math.atan2((to.y - from.y).toDouble(), (to.x - from.x).toDouble())
        ).toFloat()
        return ((degrees % 180f) + 180f) % 180f
    }

    /** Smallest separation between two folded edge angles. */
    private fun angularDistance(a: Float, b: Float): Float {
        val raw = abs(a - b)
        return min(raw, 180f - raw)
    }

    private fun distance(a: CropPoint, b: CropPoint): Float = hypot(a.x - b.x, a.y - b.y)
}
