package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import com.dev.docscannerpdf.domain.detection.QuadStabilizer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin

/**
 * Per-pixel edge evidence shared by detection and refinement.
 *
 * Carries the gradient components alongside the mask because refinement needs the direction of each
 * transition, not merely that one exists: a single object boundary changes tone the same way all
 * along its length, and that is one of the signals separating a document's own edge from an
 * unrelated background line lying beside it.
 */
class MainScanEdgeEvidence(
    val mask: BooleanArray,
    val gradientX: FloatArray,
    val gradientY: FloatArray,
    val width: Int,
    val height: Int
) {
    fun hasEdgeNear(x: Float, y: Float, radius: Int): Boolean {
        val cx = x.roundToInt()
        val cy = y.roundToInt()
        for (dy in -radius..radius) {
            val yy = cy + dy
            if (yy < 0 || yy >= height) continue
            for (dx in -radius..radius) {
                val xx = cx + dx
                if (xx < 0 || xx >= width) continue
                if (mask[yy * width + xx]) return true
            }
        }
        return false
    }

    /** Unit gradient direction at the nearest pixel, or null where there is no usable gradient. */
    fun gradientDirection(x: Float, y: Float): CropPoint? {
        val cx = x.roundToInt().coerceIn(0, width - 1)
        val cy = y.roundToInt().coerceIn(0, height - 1)
        val index = cy * width + cx
        val gx = gradientX[index]
        val gy = gradientY[index]
        val length = hypot(gx, gy)
        if (length <= 0f) return null
        return CropPoint(gx / length, gy / length)
    }
}

/** Tunables for [MainScanQuadRefiner]. */
data class MainScanRefineConfig(
    /** Search band per side, as a fraction of the distance to the opposite side. */
    val searchFraction: Float = 0.16f,
    /** Hard bounds on that band in pixels, so the search can never reach across the frame. */
    val minSearchPixels: Float = 3f,
    val maxSearchPixels: Float = 22f,
    /** Parallel offsets tried either side of the selected line. */
    val offsetSteps: Int = 7,
    /** Small angular corrections tried either side, in degrees. */
    val angleSteps: Int = 2,
    val angleStepDegrees: Float = 1.5f,
    /** Candidate lines kept per side for the coherence pass. Bounded: this many to the fourth power. */
    val candidatesPerSide: Int = 4,
    /** Shortlisted lines must differ by at least this many pixels, so they are real alternatives. */
    val minCandidateSeparation: Float = 3f,
    val sideSamples: Int = 32,
    val supportRadius: Int = 1,
    /** Length of the inward walk along each side when testing a corner, in pixels. */
    val connectionLength: Float = 7f,
    val connectionSamples: Int = 8,
    /** Largest mean corner movement refinement may introduce, in normalized units. */
    val maxMovement: Float = 0.10f
)

/**
 * Bounded refinement of an already-selected quadrilateral.
 *
 * ## The defect this closes
 *
 * Selection reached the right object, but one side still attached to a nearby stronger parallel
 * background line: on device the top edge sat about 50px above the package, on the bright browser
 * strip behind it. Right, bottom and left were correct. So this is not a candidate-selection problem
 * and the pipeline ahead of it is left alone — it is a side-localization problem, fixed after
 * selection by looking only in a narrow band around each chosen side.
 *
 * ## Why sides cannot be refined independently
 *
 * Scoring each side on its own evidence does not fix the overshoot, because the background strip is
 * a perfectly good line by every per-side measure: it is long, continuous, and supported right
 * across the object's width. Judged alone it can beat the object's real edge, and the distance
 * penalty then keeps it where it is.
 *
 * What the strip cannot do is *connect*. Where the strip meets the package's left edge there is no
 * corner — walking inward from that intersection along the left side crosses the empty gap between
 * the strip and the top of the package, and finds nothing. The package's own top edge meets the left
 * edge at a real corner, supported in both directions. So refinement keeps several candidates per
 * side and then chooses the **combination** whose four corners actually connect, which is the
 * property that distinguishes one object's boundary from a coincidence of separate lines.
 *
 * Gradient polarity contributes as supporting evidence only, never as a veto: lighting and tone vary
 * around a real document, and demanding identical polarity on all four sides would reject valid
 * pages.
 *
 * Refinement is strictly conservative. It searches a band proportional to the quad's own depth and
 * hard-capped in pixels, so it cannot jump to a table, wall or laptop edge; and if the refined quad
 * fails any geometric or scoring check it is discarded and the original returned unchanged.
 */
object MainScanQuadRefiner {

    /** A side in normal form: `rho = x*cos(theta) + y*sin(theta)`. */
    private data class Side(val theta: Float, val rho: Float)

    private data class ScoredSide(val side: Side, val score: Float, val offset: Float)

    fun refine(
        quad: PerspectiveQuad,
        evidence: MainScanEdgeEvidence,
        config: MainScanRefineConfig = MainScanRefineConfig()
    ): PerspectiveQuad {
        val width = evidence.width
        val height = evidence.height
        if (width < 16 || height < 16) return quad

        val corners = pixelCorners(quad, width, height)
        val centre = centroid(corners)
        val originalSides = List(4) { sideThrough(corners[it], corners[(it + 1) % 4]) }

        val perSide = List(4) { i ->
            candidatesFor(i, corners, originalSides, centre, evidence, config)
        }
        if (perSide.any { it.isEmpty() }) return quad

        val refined = bestCoherentCombination(perSide, evidence, config) ?: return quad
        return validateOrKeepOriginal(refined, quad, corners, evidence, width, height, config)
    }

    // --- per-side candidates ---------------------------------------------------------------------------

    /** The best few lines within the permitted band around side [index], strongest first. */
    private fun candidatesFor(
        index: Int,
        corners: List<CropPoint>,
        originalSides: List<Side>,
        centre: CropPoint,
        evidence: MainScanEdgeEvidence,
        config: MainScanRefineConfig
    ): List<ScoredSide> {
        val a = corners[index]
        val b = corners[(index + 1) % 4]
        val base = originalSides[index]
        val opposite = originalSides[(index + 2) % 4]
        val midpoint = CropPoint((a.x + b.x) / 2f, (a.y + b.y) / 2f)

        // The band is a fraction of how deep the quad is in this direction, so it scales with the
        // document and can never reach a distant background edge.
        val depth = abs(distanceToLine(midpoint, opposite))
        val maxOffset = (depth * config.searchFraction)
            .coerceIn(config.minSearchPixels, config.maxSearchPixels)

        val outward = outwardNormal(base, midpoint, centre)

        // One entry per parallel offset, keeping that offset's best small angular correction.
        val perOffset = ArrayList<ScoredSide>()
        for (offsetStep in -config.offsetSteps..config.offsetSteps) {
            val offset = offsetStep * (maxOffset / config.offsetSteps)
            var best: ScoredSide? = null
            for (angleStep in -config.angleSteps..config.angleSteps) {
                val dTheta = angleStep * config.angleStepDegrees * DEGREES_TO_RADIANS
                val candidate = shifted(base, dTheta, offset, midpoint)
                val score = scoreSide(candidate, a, b, outward, offset, maxOffset, evidence, config)
                if (score > 0f && (best == null || score > best.score)) {
                    best = ScoredSide(candidate, score, offset)
                }
            }
            best?.let { perOffset.add(it) }
        }

        return separated(perOffset, config)
    }

    /**
     * The shortlist, forced to contain genuinely DIFFERENT lines.
     *
     * Taking the highest-scoring candidates outright fills the shortlist with near-duplicates of the
     * same line, because every offset within the support radius of a strong edge scores almost
     * identically. Measured on the device scene: all three slots went to the background strip and the
     * package's real edge, twelve pixels away, was never considered — so the coherence pass had
     * nothing better to choose. Requiring separation is what puts a real alternative in front of it.
     */
    private fun separated(
        candidates: List<ScoredSide>,
        config: MainScanRefineConfig
    ): List<ScoredSide> {
        val kept = ArrayList<ScoredSide>(config.candidatesPerSide)
        for (candidate in candidates.sortedByDescending { it.score }) {
            if (kept.size >= config.candidatesPerSide) break
            if (kept.none { abs(it.offset - candidate.offset) < config.minCandidateSeparation }) {
                kept.add(candidate)
            }
        }
        return kept
    }

    /**
     * How well a candidate line is supported along the stretch the side actually spans.
     *
     * Coverage alone is not enough — a line clipping the corner of some other object can cover part
     * of the span. Continuity rewards one unbroken run rather than scattered hits, and the endpoint
     * term requires evidence near BOTH ends, which is what rules out support concentrated in the
     * middle or piled up at one end.
     */
    private fun scoreSide(
        side: Side,
        from: CropPoint,
        to: CropPoint,
        outward: CropPoint,
        offset: Float,
        maxOffset: Float,
        evidence: MainScanEdgeEvidence,
        config: MainScanRefineConfig
    ): Float {
        val dirX = -sin(side.theta)
        val dirY = cos(side.theta)
        val footX = side.rho * cos(side.theta)
        val footY = side.rho * sin(side.theta)
        val tFrom = from.x * dirX + from.y * dirY
        val tTo = to.x * dirX + to.y * dirY

        val samples = config.sideSamples
        val supported = BooleanArray(samples)
        var hits = 0
        var polaritySum = 0f
        var polarityCount = 0

        for (s in 0 until samples) {
            val t = tFrom + (tTo - tFrom) * (s.toFloat() / (samples - 1))
            val x = footX + dirX * t
            val y = footY + dirY * t
            if (!evidence.hasEdgeNear(x, y, config.supportRadius)) continue
            supported[s] = true
            hits++
            evidence.gradientDirection(x, y)?.let { g ->
                polaritySum += g.x * outward.x + g.y * outward.y
                polarityCount++
            }
        }
        if (hits == 0) return 0f

        val coverage = hits.toFloat() / samples
        val continuity = longestRun(supported).toFloat() / samples
        val endpoints = endpointSupport(supported, config)
        // Consistency, not direction: a page may be lighter or darker than its surroundings, so only
        // the steadiness of the transition along the side carries information here.
        val polarity = if (polarityCount == 0) 0f else abs(polaritySum / polarityCount)
        val drift = if (maxOffset <= 0f) 0f else abs(offset) / maxOffset

        return W_COVERAGE * coverage +
            W_CONTINUITY * continuity +
            W_ENDPOINTS * endpoints +
            W_POLARITY * polarity -
            W_DRIFT * drift
    }

    private fun longestRun(supported: BooleanArray): Int {
        var best = 0
        var run = 0
        for (value in supported) {
            run = if (value) run + 1 else 0
            if (run > best) best = run
        }
        return best
    }

    /** The weaker of the two ends, so one well-supported end cannot carry an otherwise absent side. */
    private fun endpointSupport(supported: BooleanArray, config: MainScanRefineConfig): Float {
        val zone = (supported.size * ENDPOINT_ZONE).toInt().coerceAtLeast(2)
        var head = 0
        var tail = 0
        for (i in 0 until zone) {
            if (supported[i]) head++
            if (supported[supported.size - 1 - i]) tail++
        }
        return minOf(head, tail).toFloat() / zone
    }

    // --- coherence across the four sides ---------------------------------------------------------------

    /**
     * Chooses the combination of per-side candidates whose corners genuinely connect.
     *
     * Bounded by construction: [MainScanRefineConfig.candidatesPerSide] to the fourth power, which is
     * 81 combinations at the default of three.
     */
    private fun bestCoherentCombination(
        perSide: List<List<ScoredSide>>,
        evidence: MainScanEdgeEvidence,
        config: MainScanRefineConfig
    ): List<CropPoint>? {
        var best: List<CropPoint>? = null
        var bestScore = 0f

        for (c0 in perSide[0]) for (c1 in perSide[1]) for (c2 in perSide[2]) for (c3 in perSide[3]) {
            val sides = listOf(c0.side, c1.side, c2.side, c3.side)
            val corners = intersectSides(sides) ?: continue
            if (corners.any { !withinBounds(it, evidence) }) continue

            val connection = cornerConnection(corners, evidence, config)
            if (connection <= 0f) continue

            val sideScore = (c0.score + c1.score + c2.score + c3.score) / 4f
            val total = sideScore * (1f - CONNECTION_INFLUENCE + CONNECTION_INFLUENCE * connection)
            if (total > bestScore) {
                bestScore = total
                best = corners
            }
        }
        // Nothing coherent scored at all; the caller keeps the original.
        return if (bestScore <= 0f) null else best
    }

    /**
     * Mean over the four corners of how well both incident sides are drawn on near that corner.
     *
     * This is the measurement that rejects the background strip. Walking inward from the strip's
     * intersection with the package's left edge crosses the gap above the package and finds no
     * evidence, so that corner scores near zero however strong the strip itself is.
     */
    private fun cornerConnection(
        corners: List<CropPoint>,
        evidence: MainScanEdgeEvidence,
        config: MainScanRefineConfig
    ): Float {
        var total = 0f
        for (i in 0 until 4) {
            val corner = corners[i]
            val previous = corners[(i + 3) % 4]
            val next = corners[(i + 1) % 4]
            val incoming = walkSupport(corner, previous, evidence, config)
            val outgoing = walkSupport(corner, next, evidence, config)
            total += minOf(incoming, outgoing)
        }
        return total / 4f
    }

    /** Fraction of a short inward walk from [corner] toward [towards] that has edge evidence. */
    private fun walkSupport(
        corner: CropPoint,
        towards: CropPoint,
        evidence: MainScanEdgeEvidence,
        config: MainScanRefineConfig
    ): Float {
        val dx = towards.x - corner.x
        val dy = towards.y - corner.y
        val length = hypot(dx, dy)
        if (length <= 0f) return 0f
        val reach = minOf(config.connectionLength, length)
        val ux = dx / length
        val uy = dy / length

        var hits = 0
        for (s in 1..config.connectionSamples) {
            val t = reach * (s.toFloat() / config.connectionSamples)
            if (evidence.hasEdgeNear(corner.x + ux * t, corner.y + uy * t, config.supportRadius)) hits++
        }
        return hits.toFloat() / config.connectionSamples
    }

    // --- validation --------------------------------------------------------------------------------------

    /**
     * Accepts the refined quad only if it is geometrically sound, close to the original, and not a
     * worse document by [MainScanDocumentScore]. Anything else returns the original untouched, so an
     * uncertain refinement can never degrade a working detection.
     */
    private fun validateOrKeepOriginal(
        refinedCorners: List<CropPoint>,
        original: PerspectiveQuad,
        originalCorners: List<CropPoint>,
        evidence: MainScanEdgeEvidence,
        width: Int,
        height: Int,
        config: MainScanRefineConfig
    ): PerspectiveQuad {
        val wScale = (width - 1).coerceAtLeast(1).toFloat()
        val hScale = (height - 1).coerceAtLeast(1).toFloat()
        val normalized = refinedCorners.map { CropPoint(it.x / wScale, it.y / hScale) }

        val ordered = MainScanDocumentFinder.orderCornersRadially(normalized) ?: return original
        if (!PerspectiveGeometry.isConvex(ordered)) return original
        if (!MainScanQuadValidity.isApplicable(ordered)) return original

        // Winding must survive, or downstream perspective transforms mirror the page.
        val originalArea = PerspectiveGeometry.signedArea(original)
        val refinedArea = PerspectiveGeometry.signedArea(ordered)
        if (sign(originalArea) != sign(refinedArea)) return original

        // Refinement corrects a misplaced side; it never relocates the document.
        if (QuadStabilizer.averageCornerDistance(original, ordered) > config.maxMovement) return original

        // Sides may tighten, but a collapsed side means the lines were not really this object's.
        for (i in 0 until 4) {
            val a = refinedCorners[i]
            val b = refinedCorners[(i + 1) % 4]
            val originalLength = distance(originalCorners[i], originalCorners[(i + 1) % 4])
            if (distance(a, b) < originalLength * MIN_SIDE_RETENTION) return original
        }

        // Never allow refinement to grow out onto the frame edge.
        if (boundaryContact(ordered) > boundaryContact(original) + BOUNDARY_TOLERANCE) return original

        val refinedScore = MainScanDocumentScore.overall(ordered)
        val originalScore = MainScanDocumentScore.overall(original)
        if (refinedScore < originalScore * MIN_SCORE_RETENTION) return original

        // Finally, the refined corners themselves must connect.
        if (cornerConnection(refinedCorners, evidence, config) < MIN_ACCEPT_CONNECTION) return original

        return ordered
    }

    /** How close the quad comes to the frame edge, as a 0..1 measure — higher means closer. */
    private fun boundaryContact(quad: PerspectiveQuad): Float {
        var worst = 0f
        for (corner in listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)) {
            val margin = minOf(corner.x, corner.y, 1f - corner.x, 1f - corner.y)
            val contact = (1f - margin.coerceIn(0f, 1f) / BOUNDARY_REFERENCE).coerceIn(0f, 1f)
            if (contact > worst) worst = contact
        }
        return worst
    }

    // --- geometry helpers --------------------------------------------------------------------------------

    private fun pixelCorners(quad: PerspectiveQuad, width: Int, height: Int): List<CropPoint> =
        listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
            .map { CropPoint(it.x * (width - 1), it.y * (height - 1)) }

    private fun centroid(points: List<CropPoint>): CropPoint = CropPoint(
        points.sumOf { it.x.toDouble() }.toFloat() / points.size,
        points.sumOf { it.y.toDouble() }.toFloat() / points.size
    )

    private fun sideThrough(a: CropPoint, b: CropPoint): Side {
        val theta = atan2(b.x - a.x, -(b.y - a.y))
        return Side(theta, a.x * cos(theta) + a.y * sin(theta))
    }

    private fun shifted(base: Side, deltaTheta: Float, offset: Float, pivot: CropPoint): Side {
        val theta = base.theta + deltaTheta
        // Rotate about the side's midpoint so an angular tweak does not also translate the line.
        val rho = pivot.x * cos(theta) + pivot.y * sin(theta) + offset
        return Side(theta, rho)
    }

    private fun outwardNormal(side: Side, midpoint: CropPoint, centre: CropPoint): CropPoint {
        val nx = cos(side.theta)
        val ny = sin(side.theta)
        val towardsOutside = (midpoint.x - centre.x) * nx + (midpoint.y - centre.y) * ny
        return if (towardsOutside >= 0f) CropPoint(nx, ny) else CropPoint(-nx, -ny)
    }

    private fun distanceToLine(point: CropPoint, side: Side): Float =
        point.x * cos(side.theta) + point.y * sin(side.theta) - side.rho

    private fun distance(a: CropPoint, b: CropPoint): Float = hypot(b.x - a.x, b.y - a.y)

    private fun withinBounds(point: CropPoint, evidence: MainScanEdgeEvidence): Boolean {
        val marginX = evidence.width * OUT_OF_FRAME_ALLOWANCE
        val marginY = evidence.height * OUT_OF_FRAME_ALLOWANCE
        return point.x >= -marginX && point.x <= evidence.width + marginX &&
            point.y >= -marginY && point.y <= evidence.height + marginY
    }

    /** Corner `i` lies where side `i-1` meets side `i`. */
    private fun intersectSides(sides: List<Side>): List<CropPoint>? {
        val corners = ArrayList<CropPoint>(4)
        for (i in 0 until 4) {
            corners.add(intersect(sides[(i + 3) % 4], sides[i]) ?: return null)
        }
        return corners
    }

    private fun intersect(a: Side, b: Side): CropPoint? {
        val determinant = sin(b.theta - a.theta)
        if (abs(determinant) < 1e-4f) return null
        return CropPoint(
            (a.rho * sin(b.theta) - b.rho * sin(a.theta)) / determinant,
            (b.rho * cos(a.theta) - a.rho * cos(b.theta)) / determinant
        )
    }

    private const val DEGREES_TO_RADIANS = (PI / 180.0).toFloat()

    /** Side-score weights. Polarity is supporting evidence only, hence the smallest share. */
    private const val W_COVERAGE = 0.34f
    private const val W_CONTINUITY = 0.26f
    private const val W_ENDPOINTS = 0.28f
    private const val W_POLARITY = 0.12f

    /** Penalty for moving away from the selected line, so ties keep the original placement. */
    private const val W_DRIFT = 0.10f

    /** Fraction of a side treated as each endpoint zone. */
    private const val ENDPOINT_ZONE = 0.2f

    /** How much corner connection can swing the combined score. */
    private const val CONNECTION_INFLUENCE = 0.7f

    /** A refined quad must retain this much of each original side length. */
    private const val MIN_SIDE_RETENTION = 0.5f

    /** A refined quad must retain this much of the original document-likeness score. */
    private const val MIN_SCORE_RETENTION = 0.9f

    /** Corners of an accepted refinement must connect at least this well. */
    private const val MIN_ACCEPT_CONNECTION = 0.35f

    private const val BOUNDARY_REFERENCE = 0.04f
    private const val BOUNDARY_TOLERANCE = 0.01f
    private const val OUT_OF_FRAME_ALLOWANCE = 0.12f
}
