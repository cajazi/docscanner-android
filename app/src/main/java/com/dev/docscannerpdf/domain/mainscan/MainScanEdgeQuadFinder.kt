package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import com.dev.docscannerpdf.domain.detection.LumaFrame
import com.dev.docscannerpdf.domain.detection.QuadStabilizer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** A straight line in normal form: `rho = x*cos(theta) + y*sin(theta)`, theta in radians. */
data class DetectedLine(val rho: Float, val theta: Float, val votes: Int)

/** Tunables for [MainScanEdgeQuadFinder]. Deterministic -- no randomness anywhere. */
data class MainScanEdgeConfig(
    /** Fraction of all pixels kept as edge evidence, after local normalization. */
    val edgePixelFraction: Float = 0.10f,
    /** Sobel magnitude below which a pixel is treated as sensor noise, whatever its local contrast. */
    val minimumGradient: Float = 20f,
    /** Angular resolution of the Hough accumulator, in degrees. */
    val thetaStepDegrees: Int = 2,
    /** A peak must reach this fraction of the strongest peak to count as a line. */
    val minPeakFraction: Float = 0.30f,
    /** Opposite edges of a document differ in angle by at most this much (perspective). */
    val parallelToleranceDegrees: Float = 22f,
    /** Adjacent edges are roughly perpendicular, within this tolerance. */
    val perpendicularToleranceDegrees: Float = 32f,
    /** Opposite edges must be separated by at least this fraction of the frame diagonal. */
    val minSeparationFraction: Float = 0.18f
)

/**
 * Finds a document by its EDGES rather than by brightness.
 *
 * ## Why brightness segmentation is the wrong basis
 *
 * The earlier approaches asked "which pixels differ from the background-- and fitted a shape to the
 * answer. On a real scene that question has the wrong answer: a red-and-yellow package photographed
 * on a pale tiled floor has roughly the FLOOR's luma, while the hand holding it, the shadow beneath
 * it and the dark shelving behind it differ strongly. The largest "foreground" region is therefore
 * the dark surround, and the fitted quad wraps the hand and the floor instead of the object. That is
 * exactly what physical QA showed.
 *
 * What actually distinguishes a document from its surroundings is not its tone but its **four
 * straight edges**. This finder looks for those directly:
 *
 * 1. **Blur** lightly to suppress texture noise (tile grout, print, packaging gloss).
 * 2. **Sobel gradient** magnitude -- high along any boundary, whatever its tone.
 * 3. **Normalize each pixel against its local neighbourhood** and keep the strongest
 *    [MainScanEdgeConfig.edgePixelFraction] of the result as the edge mask.
 * 4. **Hough transform** into (rho, theta): a straight edge, however faint per-pixel, accumulates
 *    votes along one line, while texture scatters. This is what makes a page edge beat a busy floor.
 * 5. Enumerate every quad formed by two near-parallel pairs that are roughly perpendicular to each
 *    other and well separated, then **score** them and keep the best.
 *
 * ## Why the edge threshold is local
 *
 * A single global threshold cannot serve one frame containing both a faint object and strong
 * clutter. Measured on the failing scene: the object's own boundary carries a Sobel magnitude near
 * 32, while the hand below it reaches 360, and any global cut that keeps a sane number of pixels
 * lands around 120 -- so the object's four edges are discarded before the Hough transform ever sees
 * them. Dividing by the mean magnitude of the surrounding tile fixes this, because it asks whether a
 * pixel stands out *where it is* rather than compared with the loudest thing in the frame. A
 * [MainScanEdgeConfig.minimumGradient] floor still applies, so amplifying an empty tile cannot
 * promote sensor noise into an edge.
 *
 * ## Why candidates are scored rather than taken greedily
 *
 * Taking the strongest line and building outward picks the most contrasty edge in the scene, which
 * is rarely the document's. In the failing scene the strongest line is the hand's upper edge, and it
 * pairs quite happily with the object's top edge to produce a quad that swallows the shadow between
 * them. Both of those lines are real; the quad they form is not.
 *
 * Scoring separates them on evidence the greedy walk never consults:
 * - **Edge support** -- every side must actually be drawn on, sampled along the segment between the
 *   corners. Two real lines can still intersect somewhere no boundary exists.
 * - **Interior crossing** -- a strong, well-supported line cutting across the middle means the true
 *   boundary is inside the candidate. This is what rejects the quad that reaches past the object's
 *   own bottom edge down to the hand.
 * - **[MainScanDocumentScore]** -- the shape must look like a document to begin with.
 *
 * Perspective is accommodated by tolerances rather than assumed away: opposite edges may differ by
 * up to ~22 degrees and adjacent ones need only be perpendicular within ~32 degrees, which covers an
 * ordinary hand-held angle without admitting arbitrary wedges.
 *
 * Returns null whenever four credible edges are not found; the caller then falls back to the region
 * finder and ultimately to the full frame, so a failure here is never a wrong crop.
 */
object MainScanEdgeQuadFinder {

    /**
     * @param previous the quad tracked on the preceding frame, when there is one. Scenes routinely
     *   contain more than one real rectangle -- a package held up in front of a laptop screen gives
     *   two -- and their scores can be near-identical. Without a tie-breaker the choice flips frame to
     *   frame, the tracked corners never settle, and the overlay stays hidden even though detection
     *   succeeded every time. Preferring the candidate near the one already being followed resolves
     *   the ambiguity the way the user's framing already has.
     */
    fun find(
        frame: LumaFrame,
        config: MainScanEdgeConfig = MainScanEdgeConfig(),
        previous: PerspectiveQuad? = null
    ): PerspectiveQuad? {
        if (frame.width < 16 || frame.height < 16) return null
        val evidence = edgeMask(frame, config) ?: return null
        val lines = houghLines(evidence.mask, frame.width, frame.height, config)
        if (lines.size < 4) return null
        val selected = bestQuad(lines, evidence.mask, frame.width, frame.height, config, previous)
            ?: return null
        // Selection reaches the right object; refinement fixes a side that latched onto a nearby
        // parallel background line. It returns the input unchanged whenever it is not confident.
        return MainScanQuadRefiner.refine(selected, evidence)
    }

    // --- stage 1-3: gradient, local normalization, mask -------------------------------------------------

    /**
     * The edge evidence for [frame], exposed so refinement can be tested against a deliberately
     * misplaced side without having to reproduce the mask, gradient and normalization stages.
     */
    internal fun evidenceFor(
        frame: LumaFrame,
        config: MainScanEdgeConfig = MainScanEdgeConfig()
    ): MainScanEdgeEvidence? = edgeMask(frame, config)

    /** TEMPORARY diagnostic: the Hough peaks for a frame, strongest first. */
    internal fun debugLines(
        frame: LumaFrame,
        config: MainScanEdgeConfig = MainScanEdgeConfig()
    ): List<DetectedLine> {
        val evidence = edgeMask(frame, config) ?: return emptyList()
        return houghLines(evidence.mask, frame.width, frame.height, config)
    }

    /** Builds the edge evidence, or null when the frame carries no usable gradient at all. */
    private fun edgeMask(frame: LumaFrame, config: MainScanEdgeConfig): MainScanEdgeEvidence? {
        val blurred = blur(frame)
        val gradient = sobel(blurred, frame.width, frame.height)
        val magnitude = gradient.magnitude
        val normalized = normalizeLocally(magnitude, frame.width, frame.height, config)
        val threshold = topFractionThreshold(normalized, config.edgePixelFraction) ?: return null

        val mask = BooleanArray(normalized.size)
        var count = 0
        for (i in normalized.indices) {
            if (normalized[i] >= threshold && magnitude[i] >= config.minimumGradient) {
                mask[i] = true
                count++
            }
        }
        if (count < MIN_EDGE_PIXELS) return null
        return MainScanEdgeEvidence(
            mask = mask,
            gradientX = gradient.x,
            gradientY = gradient.y,
            width = frame.width,
            height = frame.height
        )
    }

    /** Sobel components alongside the magnitude; refinement needs the transition direction too. */
    private class Gradient(val x: FloatArray, val y: FloatArray, val magnitude: FloatArray)

    /** 3x3 box blur. Cheap, and enough to stop grout lines and print from dominating the votes. */
    private fun blur(frame: LumaFrame): IntArray {
        val out = IntArray(frame.width * frame.height)
        for (y in 0 until frame.height) {
            for (x in 0 until frame.width) {
                var sum = 0
                var count = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= frame.height) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= frame.width) continue
                        sum += frame.luma[yy * frame.width + xx]
                        count++
                    }
                }
                out[y * frame.width + x] = if (count == 0) 0 else sum / count
            }
        }
        return out
    }

    /** Sobel gradient. Border pixels are left at zero -- they carry no reliable gradient. */
    private fun sobel(source: IntArray, width: Int, height: Int): Gradient {
        val outX = FloatArray(width * height)
        val outY = FloatArray(width * height)
        val out = FloatArray(width * height)
        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                val i = y * width + x
                val tl = source[i - width - 1]
                val tc = source[i - width]
                val tr = source[i - width + 1]
                val ml = source[i - 1]
                val mr = source[i + 1]
                val bl = source[i + width - 1]
                val bc = source[i + width]
                val br = source[i + width + 1]
                val gx = (tr + 2 * mr + br) - (tl + 2 * ml + bl)
                val gy = (bl + 2 * bc + br) - (tl + 2 * tc + tr)
                outX[i] = gx.toFloat()
                outY[i] = gy.toFloat()
                out[i] = hypot(gx.toFloat(), gy.toFloat())
            }
        }
        return Gradient(outX, outY, out)
    }

    /**
     * Divides each pixel by the mean magnitude of its tile, so "is this an edge-- becomes a local
     * question. Edges are sparse, so a tile's mean stays low and a genuine boundary lands many times
     * above it -- whether that boundary is a bright page on a desk or a dull package on a pale floor.
     */
    private fun normalizeLocally(
        magnitude: FloatArray,
        width: Int,
        height: Int,
        config: MainScanEdgeConfig
    ): FloatArray {
        val tile = max(TILE_MINIMUM, minOf(width, height) / TILE_DIVISOR)
        val tilesX = (width + tile - 1) / tile
        val tilesY = (height + tile - 1) / tile
        val sums = FloatArray(tilesX * tilesY)
        val counts = IntArray(tilesX * tilesY)

        for (y in 0 until height) {
            val ty = y / tile
            for (x in 0 until width) {
                val index = ty * tilesX + (x / tile)
                sums[index] += magnitude[y * width + x]
                counts[index]++
            }
        }

        var globalSum = 0f
        for (value in magnitude) globalSum += value
        val globalMean = if (magnitude.isEmpty()) 0f else globalSum / magnitude.size
        // A floor tied to the frame's own mean stops an almost-empty tile from amplifying noise into
        // an apparent edge, while still letting a quiet tile surface a faint but real boundary.
        val floor = max(globalMean * LOCAL_FLOOR_FRACTION, LOCAL_FLOOR_MINIMUM)

        val out = FloatArray(magnitude.size)
        for (y in 0 until height) {
            val ty = y / tile
            for (x in 0 until width) {
                val index = ty * tilesX + (x / tile)
                val mean = if (counts[index] == 0) 0f else sums[index] / counts[index]
                out[y * width + x] = magnitude[y * width + x] / max(mean, floor)
            }
        }
        return out
    }

    /**
     * The value above which roughly [fraction] of ALL pixels lie.
     *
     * A histogram rather than a sort: this runs on every analysis frame, and boxing tens of thousands
     * of floats per frame would cost more than the whole detection.
     */
    private fun topFractionThreshold(values: FloatArray, fraction: Float): Float? {
        var maxValue = 0f
        for (value in values) if (value > maxValue) maxValue = value
        if (maxValue <= 0f) return null

        val histogram = IntArray(HISTOGRAM_BINS)
        for (value in values) {
            if (value <= 0f) continue
            val bin = ((value / maxValue) * (HISTOGRAM_BINS - 1)).toInt().coerceIn(0, HISTOGRAM_BINS - 1)
            histogram[bin]++
        }

        val wanted = (values.size * fraction).toInt().coerceAtLeast(1)
        var counted = 0
        for (bin in HISTOGRAM_BINS - 1 downTo 0) {
            counted += histogram[bin]
            if (counted >= wanted) {
                val threshold = (bin.toFloat() / (HISTOGRAM_BINS - 1)) * maxValue
                return if (threshold <= 0f) Float.MIN_VALUE else threshold
            }
        }
        return Float.MIN_VALUE
    }

    // --- stage 4: Hough ------------------------------------------------------------------------------

    private fun houghLines(
        mask: BooleanArray,
        width: Int,
        height: Int,
        config: MainScanEdgeConfig
    ): List<DetectedLine> {
        val thetaSteps = 180 / config.thetaStepDegrees
        val diagonal = sqrt((width * width + height * height).toDouble()).toFloat()
        val rhoOffset = diagonal.roundToInt()
        val rhoBins = rhoOffset * 2 + 1
        val accumulator = IntArray(thetaSteps * rhoBins)

        val cosTable = FloatArray(thetaSteps)
        val sinTable = FloatArray(thetaSteps)
        for (t in 0 until thetaSteps) {
            val theta = (t * config.thetaStepDegrees) * PI.toFloat() / 180f
            cosTable[t] = cos(theta)
            sinTable[t] = sin(theta)
        }

        for (y in 1 until height - 1) {
            for (x in 1 until width - 1) {
                if (!mask[y * width + x]) continue
                for (t in 0 until thetaSteps) {
                    val rho = x * cosTable[t] + y * sinTable[t]
                    val bin = rho.roundToInt() + rhoOffset
                    if (bin in 0 until rhoBins) accumulator[t * rhoBins + bin]++
                }
            }
        }

        var maxVotes = 0
        for (value in accumulator) if (value > maxVotes) maxVotes = value
        if (maxVotes <= 0) return emptyList()
        val minVotes = (maxVotes * config.minPeakFraction).toInt().coerceAtLeast(MIN_LINE_VOTES)

        val peaks = ArrayList<DetectedLine>()
        for (t in 0 until thetaSteps) {
            for (bin in 1 until rhoBins - 1) {
                val votes = accumulator[t * rhoBins + bin]
                if (votes < minVotes) continue
                if (votes < accumulator[t * rhoBins + bin - 1]) continue
                if (votes < accumulator[t * rhoBins + bin + 1]) continue
                peaks.add(
                    DetectedLine(
                        rho = (bin - rhoOffset).toFloat(),
                        theta = (t * config.thetaStepDegrees) * PI.toFloat() / 180f,
                        votes = votes
                    )
                )
            }
        }
        return suppressDuplicates(peaks.sortedByDescending { it.votes })
    }

    /**
     * Keeps one line per physical edge.
     *
     * Suppression along rho alone is not enough: a single edge still peaks in several adjacent theta
     * rows, so one rectangle can yield a dozen near-identical lines. Left in, they crowd the pairing
     * stage with one orientation and the perpendicular partner never gets considered at all -- the
     * quad silently fails to form even though its four edges were all detected.
     */
    private fun suppressDuplicates(sorted: List<DetectedLine>): List<DetectedLine> {
        val kept = ArrayList<DetectedLine>()
        for (line in sorted) {
            if (kept.none { isSameLine(it, line) }) kept.add(line)
        }
        return kept
    }

    /**
     * Whether two (rho, theta) peaks describe the same physical line.
     *
     * The normal direction wraps: theta just under 180 degrees points opposite to theta just over 0,
     * and the same edge is then recorded with the opposite sign of rho. Comparing rho directly would
     * treat those as two different lines on opposite sides of the frame, so the antiparallel case is
     * matched explicitly.
     */
    private fun isSameLine(a: DetectedLine, b: DetectedLine): Boolean {
        if (angularDistance(a.theta, b.theta) > MERGE_THETA.toRadians()) return false
        val sameDirection = cos(a.theta) * cos(b.theta) + sin(a.theta) * sin(b.theta) >= 0f
        val distance = if (sameDirection) abs(a.rho - b.rho) else abs(a.rho + b.rho)
        return distance <= MERGE_RHO
    }

    // --- stage 5: candidate quads ----------------------------------------------------------------------

    /** One evaluated candidate, for diagnostics only. Carries geometry and scores, never pixels. */
    internal class CandidateDiagnostic(
        val quad: PerspectiveQuad,
        val totalScore: Float,
        val shapeScore: Float,
        val provenance: String
    )

    /**
     * Every candidate the regular path evaluates, with its score. Diagnostics only.
     *
     * Exists to answer one binary question that repeated tuning could not: on a scene where the
     * wrong object is selected, is the right one absent from the candidate list, or present and
     * out-scored? Those two failures need opposite corrections, and guessing between them is what
     * produced three reverted attempts.
     */
    internal fun debugCandidates(
        frame: LumaFrame,
        config: MainScanEdgeConfig = MainScanEdgeConfig(),
        previous: PerspectiveQuad? = null
    ): List<CandidateDiagnostic> {
        val evidence = edgeMask(frame, config) ?: return emptyList()
        val lines = houghLines(evidence.mask, frame.width, frame.height, config)
        if (lines.size < 4) return emptyList()
        val sink = ArrayList<CandidateDiagnostic>()
        bestQuad(lines, evidence.mask, frame.width, frame.height, config, previous, sink)
        return sink
    }

    /** Enumerates every plausible quad and returns the best-scoring one, or null if none convinces. */
    private fun bestQuad(
        lines: List<DetectedLine>,
        mask: BooleanArray,
        width: Int,
        height: Int,
        config: MainScanEdgeConfig,
        previous: PerspectiveQuad?,
        sink: MutableList<CandidateDiagnostic>? = null
    ): PerspectiveQuad? {
        val pairs = oppositeEdgePairs(lines, width, height, config)
        if (pairs.size < 2) return null
        val considered = lines.take(MAX_LINES_CONSIDERED)

        var best: PerspectiveQuad? = null
        var bestScore = 0f
        var evaluated = 0

        for (p in pairs.indices) {
            for (q in p + 1 until pairs.size) {
                if (evaluated >= MAX_CANDIDATES) break
                val first = pairs[p]
                val second = pairs[q]
                val delta = angularDistance(first.first.theta, second.first.theta)
                if (abs(delta - HALF_PI) > config.perpendicularToleranceDegrees.toRadians()) continue

                val corners = intersectPairs(
                    first.first, first.second, second.first, second.second, width, height
                ) ?: continue
                val ordered = MainScanDocumentFinder.orderCornersRadially(corners) ?: continue
                if (!PerspectiveGeometry.isConvex(ordered)) continue
                evaluated++

                val used = listOf(first.first, first.second, second.first, second.second)
                val score = scoreCandidate(ordered, used, considered, mask, width, height) *
                    continuityBonus(ordered, previous)
                sink?.add(
                    CandidateDiagnostic(
                        quad = ordered,
                        totalScore = score,
                        shapeScore = MainScanDocumentScore.overall(ordered),
                        provenance = "REGULAR_FOUR_LINE"
                    )
                )
                if (score > bestScore) {
                    bestScore = score
                    best = ordered
                }
            }
            if (evaluated >= MAX_CANDIDATES) break
        }

        return if (bestScore >= MIN_CANDIDATE_SCORE) best else null
    }

    /**
     * A multiplier in `1.0 .. 1 + CONTINUITY_WEIGHT` rewarding agreement with the tracked quad.
     *
     * Deliberately a mild nudge rather than a lock: it only decides between candidates that are
     * already close on their own merits, so a genuinely better quad -- or the user turning to a
     * different document -- still wins outright. With no previous quad it is exactly 1.0, leaving
     * first acquisition entirely to the evidence.
     */
    private fun continuityBonus(candidate: PerspectiveQuad, previous: PerspectiveQuad?): Float {
        if (previous == null) return 1f
        val drift = QuadStabilizer.averageCornerDistance(previous, candidate)
        if (drift >= CONTINUITY_RANGE) return 1f
        return 1f + CONTINUITY_WEIGHT * (1f - drift / CONTINUITY_RANGE)
    }

    /** Near-parallel line pairs far enough apart to bound something. */
    private fun oppositeEdgePairs(
        lines: List<DetectedLine>,
        width: Int,
        height: Int,
        config: MainScanEdgeConfig
    ): List<Pair<DetectedLine, DetectedLine>> {
        val diagonal = sqrt((width * width + height * height).toDouble()).toFloat()
        val minSeparation = diagonal * config.minSeparationFraction
        val considered = lines.take(MAX_LINES_CONSIDERED)
        val tolerance = config.parallelToleranceDegrees.toRadians()

        // Both members come from the strong lines.
        //
        // This is what currently prevents the tiled-floor fixture from ever assembling the package:
        // its top, right and bottom edges rank 1st, 2nd and 12th, but its shadowed left edge ranks
        // 160th of 161, so the right/left pair never forms. Two ways of widening the search were
        // measured and BOTH made the result worse, because the candidate scorer cannot tell the
        // package from any other well-supported quad:
        //
        //   both members from the long tail  -> mean corner error 0.237 -> 0.315 (thin slivers)
        //   strong anchor + any partner      -> mean corner error 0.237 -> 0.397 (corners off-frame)
        //
        // So the fix belongs in scoring, not here. Widening this loop again without first making the
        // scorer prefer a coherent object will only repeat those measurements.
        val pairs = ArrayList<Pair<DetectedLine, DetectedLine>>()
        for (i in considered.indices) {
            for (j in i + 1 until considered.size) {
                val a = considered[i]
                val b = considered[j]
                if (angularDistance(a.theta, b.theta) > tolerance) continue
                if (separation(a, b) < minSeparation) continue
                pairs.add(a to b)
                if (pairs.size >= MAX_PAIRS) return pairs
            }
        }
        return pairs
    }

    /** Perpendicular distance between two near-parallel lines, handling the antiparallel form. */
    private fun separation(a: DetectedLine, b: DetectedLine): Float {
        val sameDirection = cos(a.theta) * cos(b.theta) + sin(a.theta) * sin(b.theta) >= 0f
        return if (sameDirection) abs(a.rho - b.rho) else abs(a.rho + b.rho)
    }

    /**
     * Combines shape plausibility, how well the four sides are actually drawn on, and whether a
     * strong line cuts through the middle.
     *
     * The interior term is the one that matters most in cluttered scenes: a candidate stretching from
     * the object's top edge down to the hand below it looks like a perfectly good rectangle and has
     * four supported sides, but the object's own bottom edge runs straight across its middle. That is
     * the signature of a quad reaching past the real boundary.
     */
    private fun scoreCandidate(
        quad: PerspectiveQuad,
        used: List<DetectedLine>,
        allLines: List<DetectedLine>,
        mask: BooleanArray,
        width: Int,
        height: Int
    ): Float {
        val shape = MainScanDocumentScore.overall(quad)
        if (shape <= 0f) return 0f

        val corners = pixelCorners(quad, width, height)
        var minSideSupport = 1f
        var totalSupport = 0f
        for (i in 0 until 4) {
            val support = segmentSupport(corners[i], corners[(i + 1) % 4], mask, width, height)
            totalSupport += support
            if (support < minSideSupport) minSideSupport = support
        }
        if (minSideSupport < MIN_SIDE_SUPPORT) return 0f

        val cornerSupport = cornerSupport(corners, mask, width, height)
        if (cornerSupport < MIN_CORNER_SUPPORT) return 0f

        val interior = interiorCrossing(quad, used, allLines, mask, width, height)
        return shape * (totalSupport / 4f) * cornerSupport * (1f - INTERIOR_PENALTY * interior)
    }

    /**
     * Fraction of the four corners that have edge evidence where the sides actually meet.
     *
     * Side support alone lets a candidate borrow one edge from one object and the next from another:
     * on-device the quad took its left edge from the package being held up and its top and bottom
     * from the laptop screen behind it, and every side was "drawn on" so the fit scored well. What
     * gives that away is the corner -- the point where the package's left edge meets the screen's top
     * edge lies in empty space, because those two lines never meet on any real object. Sampling
     * whole sides barely notices one bad endpoint out of forty; checking corners directly makes it
     * decisive.
     */
    private fun cornerSupport(
        corners: List<CropPoint>,
        mask: BooleanArray,
        width: Int,
        height: Int
    ): Float {
        var supported = 0
        for (corner in corners) {
            if (hasEdgeWithin(corner, CORNER_RADIUS, mask, width, height)) supported++
        }
        return supported.toFloat() / corners.size
    }

    private fun hasEdgeWithin(
        point: CropPoint,
        radius: Int,
        mask: BooleanArray,
        width: Int,
        height: Int
    ): Boolean {
        val cx = point.x.roundToInt()
        val cy = point.y.roundToInt()
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

    private fun pixelCorners(quad: PerspectiveQuad, width: Int, height: Int): List<CropPoint> =
        listOf(quad.topLeft, quad.topRight, quad.bottomRight, quad.bottomLeft)
            .map { CropPoint(it.x * (width - 1), it.y * (height - 1)) }

    /** Fraction of sample points along a segment that sit next to real edge evidence. */
    private fun segmentSupport(
        from: CropPoint,
        to: CropPoint,
        mask: BooleanArray,
        width: Int,
        height: Int
    ): Float {
        var hits = 0
        for (s in 0 until SEGMENT_SAMPLES) {
            val t = s.toFloat() / (SEGMENT_SAMPLES - 1)
            val x = (from.x + (to.x - from.x) * t).roundToInt()
            val y = (from.y + (to.y - from.y) * t).roundToInt()
            if (hasEdgeNear(x, y, mask, width, height)) hits++
        }
        return hits.toFloat() / SEGMENT_SAMPLES
    }

    /** Allows a small radius so a slightly off line still counts -- Hough bins are discrete. */
    private fun hasEdgeNear(x: Int, y: Int, mask: BooleanArray, width: Int, height: Int): Boolean {
        for (dy in -SUPPORT_RADIUS..SUPPORT_RADIUS) {
            val yy = y + dy
            if (yy < 0 || yy >= height) continue
            for (dx in -SUPPORT_RADIUS..SUPPORT_RADIUS) {
                val xx = x + dx
                if (xx < 0 || xx >= width) continue
                if (mask[yy * width + xx]) return true
            }
        }
        return false
    }

    /**
     * How strongly some unused line cuts across the candidate's interior, as a fraction in 0..1.
     *
     * A line is only counted when it passes well inside the quad and is genuinely supported by edge
     * pixels there, so ordinary print and texture -- short and broken -- contribute nothing.
     */
    private fun interiorCrossing(
        quad: PerspectiveQuad,
        used: List<DetectedLine>,
        allLines: List<DetectedLine>,
        mask: BooleanArray,
        width: Int,
        height: Int
    ): Float {
        val corners = pixelCorners(quad, width, height)
        val centre = CropPoint(
            corners.sumOf { it.x.toDouble() }.toFloat() / 4f,
            corners.sumOf { it.y.toDouble() }.toFloat() / 4f
        )
        val inset = insetRadius(corners, centre)
        if (inset <= 0f) return 0f

        var worst = 0f
        for (line in allLines) {
            if (used.any { isSameLine(it, line) }) continue

            val distance = abs(centre.x * cos(line.theta) + centre.y * sin(line.theta) - line.rho)
            if (distance > inset * INTERIOR_BAND) continue

            // Walk the line across the quad and measure how much of that chord is real.
            val dirX = -sin(line.theta)
            val dirY = cos(line.theta)
            val footX = line.rho * cos(line.theta)
            val footY = line.rho * sin(line.theta)
            val centreOffset = centre.x * dirX + centre.y * dirY

            var hits = 0
            var samples = 0
            var step = -inset
            while (step <= inset) {
                val x = footX + dirX * (centreOffset + step)
                val y = footY + dirY * (centreOffset + step)
                if (pointInQuad(x, y, corners)) {
                    samples++
                    if (hasEdgeNear(x.roundToInt(), y.roundToInt(), mask, width, height)) hits++
                }
                step += INTERIOR_STEP
            }
            if (samples < INTERIOR_MIN_SAMPLES) continue
            val supported = hits.toFloat() / samples
            if (supported > worst) worst = supported
        }
        return worst.coerceIn(0f, 1f)
    }

    /** Smallest distance from the centre to any side -- the quad's half-width at its narrowest. */
    private fun insetRadius(corners: List<CropPoint>, centre: CropPoint): Float {
        var minDistance = Float.MAX_VALUE
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val length = hypot(b.x - a.x, b.y - a.y)
            if (length <= 0f) continue
            val distance = abs((b.x - a.x) * (a.y - centre.y) - (a.x - centre.x) * (b.y - a.y)) / length
            if (distance < minDistance) minDistance = distance
        }
        return if (minDistance == Float.MAX_VALUE) 0f else minDistance
    }

    private fun pointInQuad(x: Float, y: Float, corners: List<CropPoint>): Boolean {
        var sign = 0
        for (i in 0 until 4) {
            val a = corners[i]
            val b = corners[(i + 1) % 4]
            val cross = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            if (cross > 0f) {
                if (sign < 0) return false
                sign = 1
            } else if (cross < 0f) {
                if (sign > 0) return false
                sign = -1
            }
        }
        return true
    }

    /**
     * Intersects the two edge pairs into four corners, rejecting the result if any corner lands far
     * outside the frame -- an intersection off in the distance means the lines were not the sides of
     * one object.
     */
    private fun intersectPairs(
        a1: DetectedLine,
        a2: DetectedLine,
        b1: DetectedLine,
        b2: DetectedLine,
        width: Int,
        height: Int
    ): List<CropPoint>? {
        val corners = ArrayList<CropPoint>(4)
        val marginX = width * OUT_OF_FRAME_ALLOWANCE
        val marginY = height * OUT_OF_FRAME_ALLOWANCE
        for (a in listOf(a1, a2)) {
            for (b in listOf(b1, b2)) {
                val point = intersect(a, b) ?: return null
                if (point.x < -marginX || point.x > width + marginX) return null
                if (point.y < -marginY || point.y > height + marginY) return null
                corners.add(point)
            }
        }
        if (corners.size != 4) return null
        val wScale = (width - 1).coerceAtLeast(1).toFloat()
        val hScale = (height - 1).coerceAtLeast(1).toFloat()
        return corners.map { CropPoint(it.x / wScale, it.y / hScale) }
    }

    private fun intersect(a: DetectedLine, b: DetectedLine): CropPoint? {
        val determinant = sin(b.theta - a.theta)
        if (abs(determinant) < 1e-4f) return null
        val x = (a.rho * sin(b.theta) - b.rho * sin(a.theta)) / determinant
        val y = (b.rho * cos(a.theta) - a.rho * cos(b.theta)) / determinant
        return CropPoint(x, y)
    }

    /** Smallest angle between two undirected lines, in radians (0..pi/2). */
    private fun angularDistance(a: Float, b: Float): Float {
        var delta = abs(a - b) % PI.toFloat()
        if (delta > HALF_PI) delta = PI.toFloat() - delta
        return delta
    }

    private fun Float.toRadians(): Float = this * PI.toFloat() / 180f

    private const val HALF_PI = (PI / 2.0).toFloat()

    /** How far outside the frame a corner may fall before the fit is rejected. */
    private const val OUT_OF_FRAME_ALLOWANCE = 0.12f

    /**
     * Peaks examined when pairing edges.
     *
     * Measured on the tiled-floor fixture, this cut is why the package cannot be selected: its top,
     * right and bottom edges rank 1st, 2nd and 12th, but its shadowed left edge ranks 160th of 161,
     * so the quad can never be assembled. Simply raising the cut is NOT the answer -- at 200 the
     * extra lines produced thin slivers built from floor seams and the mean corner error rose from
     * 0.237 to 0.315. The real fix belongs in scoring, not in this cap.
     */
    private const val MAX_LINES_CONSIDERED = 40

    private const val HISTOGRAM_BINS = 256

    /** Local-normalization tile geometry. */
    private const val TILE_MINIMUM = 8
    private const val TILE_DIVISOR = 8
    private const val LOCAL_FLOOR_FRACTION = 0.35f
    private const val LOCAL_FLOOR_MINIMUM = 1f

    /** Below this many edge pixels there is nothing to fit. */
    private const val MIN_EDGE_PIXELS = 40

    /** A Hough peak needs at least this many votes regardless of the strongest peak. */
    private const val MIN_LINE_VOTES = 12

    /** Peaks within this distance and angle of a stronger one are the same edge. */
    private const val MERGE_RHO = 6f
    private const val MERGE_THETA = 8f

    /** Caps on enumeration, so a texture-rich frame cannot make detection unbounded. */
    private const val MAX_PAIRS = 120
    private const val MAX_CANDIDATES = 400

    /** Below this combined score nothing is returned and the caller falls back. */
    private const val MIN_CANDIDATE_SCORE = 0.18f

    /** Every side must be drawn on to at least this extent; a side in mid-air disqualifies the quad. */
    private const val MIN_SIDE_SUPPORT = 0.45f

    private const val SEGMENT_SAMPLES = 40
    private const val SUPPORT_RADIUS = 2

    /**
     * Corners must sit on real edge evidence. Slightly wider than [SUPPORT_RADIUS] because a corner
     * is where two quantised Hough lines meet, so its position carries both lines' rounding error.
     */
    private const val CORNER_RADIUS = 4

    /** At most one corner may hang in empty space; two means the sides came from different objects. */
    private const val MIN_CORNER_SUPPORT = 0.75f

    /** Weight of the interior-crossing penalty. */
    private const val INTERIOR_PENALTY = 0.85f

    /** A crossing line counts as interior only within this fraction of the quad's narrow half-width. */
    private const val INTERIOR_BAND = 0.65f

    private const val INTERIOR_STEP = 2f
    private const val INTERIOR_MIN_SAMPLES = 8

    /** Normalized corner drift beyond which the previous quad no longer informs the choice. */
    private const val CONTINUITY_RANGE = 0.12f

    /** Maximum proportional advantage given to the already-tracked quad. */
    private const val CONTINUITY_WEIGHT = 0.25f
}
