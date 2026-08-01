package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import com.dev.docscannerpdf.domain.detection.LumaFrame
import kotlin.math.abs

/** Tunables for [MainScanDocumentFinder]. No randomness — the result is fully deterministic. */
data class MainScanFinderConfig(
    /** Luma difference from the estimated background that marks a pixel as foreground. */
    val contrastThreshold: Int = 30,
    /** The winning region must cover at least this fraction of the frame. */
    val minRegionFraction: Float = 0.04f,
    /** Hull points kept before quadrilateral fitting. Keeps the search cheap and stable. */
    val maxHullPoints: Int = 24
)

/**
 * Finds the document/object quadrilateral in a downscaled luma frame.
 *
 * ## Why this replaces the extreme-points approach for the Main Scanner
 *
 * [com.dev.docscannerpdf.domain.detection.DocumentEdgeDetector] takes the four extreme points of
 * **every** foreground pixel in the frame. That has a fatal property: a single stray foreground
 * pixel anywhere — a shoe in one corner, clutter at an edge, a shadow band — drags a corner out to
 * meet it. The result is a quad spanning almost the whole image, which is exactly the near-full-frame
 * seed and the "wall corner" observed in physical QA. No amount of scoring downstream can recover a
 * shape that was never fitted to the object in the first place.
 *
 * This finder instead isolates the object before fitting anything:
 *
 * 1. **Foreground mask** — pixels whose luma differs from the border-estimated background.
 * 2. **Largest connected region** — the decisive step. Clutter and the object become separate
 *    components, and only the biggest one survives, so a shoe in the corner can no longer influence
 *    the corners of the page.
 * 3. **Convex hull** of that region (Andrew's monotone chain).
 * 4. **Maximum-area quadrilateral** inscribed in the simplified hull, which is what makes the result
 *    hug the object's real, possibly tilted, edges rather than its bounding box.
 *
 * Everything is integer/float arithmetic over a frame already downscaled to ~240px on its long edge,
 * so the whole pass is a few milliseconds and completely deterministic: identical input always gives
 * identical output.
 *
 * The legacy detector is deliberately left untouched — the live scanner surface still uses it, and
 * changing shared behaviour was not part of this correction.
 */
object MainScanDocumentFinder {

    /**
     * Finds the document quad, preferring EDGE evidence over brightness.
     *
     * [MainScanEdgeQuadFinder] runs first because a document is defined by its four straight edges,
     * not by its tone. Brightness segmentation fails on exactly the everyday case that matters — a
     * coloured package on a pale floor has roughly the floor's luma, while the hand and shadow around
     * it do not, so the "foreground" region is the surround rather than the object.
     *
     * The region fit below remains as a second chance for scenes with a clear tonal separation but
     * weak or broken edges (a dark page on a white desk, a page whose border is partly out of frame).
     * Whichever produces a quad, it must still be scored by the caller: neither is trusted blindly.
     */
    fun find(
        frame: LumaFrame,
        config: MainScanFinderConfig = MainScanFinderConfig(),
        previous: PerspectiveQuad? = null
    ): PerspectiveQuad? {
        if (frame.width < 8 || frame.height < 8) return null

        MainScanEdgeQuadFinder.find(frame, previous = previous)?.let { edgeQuad ->
            if (MainScanDocumentScore.isViable(edgeQuad)) return edgeQuad
        }
        return findByRegion(frame, config)
    }

    /** Brightness-based fallback: largest connected region, convex hull, maximum-area quad. */
    internal fun findByRegion(
        frame: LumaFrame,
        config: MainScanFinderConfig = MainScanFinderConfig()
    ): PerspectiveQuad? {
        if (frame.width < 8 || frame.height < 8) return null

        val background = estimateBackground(frame)
        val mask = buildMask(frame, background, config.contrastThreshold)

        val region = largestRegion(mask, frame.width, frame.height) ?: return null
        val minPixels = (frame.width * frame.height * config.minRegionFraction).toInt()
        if (region.size < minPixels) return null

        val hull = convexHull(region.points(frame.width))
        if (hull.size < 4) return null

        val simplified = simplifyHull(hull, config.maxHullPoints)
        val quad = maxAreaQuad(simplified) ?: return null

        val normalized = normalize(quad, frame.width, frame.height)
        val ordered = orderCornersRadially(normalized) ?: return null
        if (!PerspectiveGeometry.isConvex(ordered)) return null
        return ordered
    }

    /**
     * Assigns TL/TR/BR/BL by walking the corners around their centroid.
     *
     * [PerspectiveGeometry.orderCorners] uses the classic sum/difference heuristic — smallest x+y is
     * top-left, largest is bottom-right, and so on. That is fine for a roughly axis-aligned quad but
     * it TIES on a quad rotated near 45 degrees: a diamond's top and left tips share the same x+y,
     * so the same point can be selected for two roles, producing a degenerate shape that fails
     * convexity and drops the detection entirely. A page photographed at 45 degrees is completely
     * ordinary, so the finder cannot rely on that heuristic.
     *
     * Sorting radially is unambiguous for any rotation: the cyclic order around the centroid is
     * well-defined, and only the starting corner needs choosing. Ties there are broken
     * deterministically, so identical input always yields identical roles.
     *
     * The shared helper is deliberately left alone — it is used by the crop editor and the identity
     * flows, where quads are produced by 90-degree rotations that cannot hit this degeneracy.
     */
    internal fun orderCornersRadially(points: List<CropPoint>): PerspectiveQuad? {
        if (points.size != 4) return null
        val centerX = points.sumOf { it.x.toDouble() }.toFloat() / 4f
        val centerY = points.sumOf { it.y.toDouble() }.toFloat() / 4f

        // Screen coordinates have y pointing down, so increasing atan2 walks clockwise on screen —
        // which is the TL -> TR -> BR -> BL direction the quad contract expects.
        val clockwise = points.sortedBy {
            kotlin.math.atan2((it.y - centerY).toDouble(), (it.x - centerX).toDouble())
        }

        // Start at the most "top-left" corner: smallest x+y, ties broken by the higher point, then
        // the more leftward one, so the choice is total and reproducible.
        var startIndex = 0
        var bestKey = Triple(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE)
        for (i in clockwise.indices) {
            val p = clockwise[i]
            val key = Triple(p.x + p.y, p.y, p.x)
            if (key.first < bestKey.first ||
                (key.first == bestKey.first && key.second < bestKey.second) ||
                (key.first == bestKey.first && key.second == bestKey.second && key.third < bestKey.third)
            ) {
                bestKey = key
                startIndex = i
            }
        }

        val rotated = (0 until 4).map { clockwise[(startIndex + it) % 4] }
        // Reject a fit whose corners collapsed onto each other — that is not a quadrilateral.
        for (i in 0 until 4) {
            for (j in i + 1 until 4) {
                if (rotated[i] == rotated[j]) return null
            }
        }
        return PerspectiveQuad(rotated[0], rotated[1], rotated[2], rotated[3])
    }

    // --- stage 1: background and mask ------------------------------------------------------------

    /** Median luma of the border ring — robust against a large object dominating the centre. */
    private fun estimateBackground(frame: LumaFrame): Int {
        val border = ArrayList<Int>(2 * (frame.width + frame.height))
        for (x in 0 until frame.width) {
            border.add(frame.at(x, 0))
            border.add(frame.at(x, frame.height - 1))
        }
        for (y in 1 until frame.height - 1) {
            border.add(frame.at(0, y))
            border.add(frame.at(frame.width - 1, y))
        }
        border.sort()
        return border[border.size / 2]
    }

    private fun buildMask(frame: LumaFrame, background: Int, threshold: Int): BooleanArray {
        val mask = BooleanArray(frame.width * frame.height)
        for (i in mask.indices) {
            mask[i] = abs(frame.luma[i] - background) >= threshold
        }
        return mask
    }

    // --- stage 2: largest connected region --------------------------------------------------------

    /** A connected foreground region: the flat pixel indices belonging to it. */
    private class Region(val indices: IntArray, val size: Int) {
        fun points(width: Int): List<CropPoint> =
            (0 until size).map { i ->
                val index = indices[i]
                CropPoint((index % width).toFloat(), (index / width).toFloat())
            }
    }

    /**
     * Four-connectivity flood fill retaining only the largest component. Uses an explicit index
     * stack rather than recursion — a full-frame region on a 240px frame is tens of thousands of
     * pixels deep and would overflow the call stack.
     */
    private fun largestRegion(mask: BooleanArray, width: Int, height: Int): Region? {
        val visited = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        val current = IntArray(mask.size)
        var best: Region? = null

        for (start in mask.indices) {
            if (!mask[start] || visited[start]) continue
            var stackSize = 0
            var count = 0
            stack[stackSize++] = start
            visited[start] = true

            while (stackSize > 0) {
                val index = stack[--stackSize]
                current[count++] = index
                val x = index % width
                val y = index / width

                // Marking on PUSH (not on pop) is what keeps `stack` bounded by the pixel count:
                // a pixel can never be queued twice, so the array sized to the frame always fits.
                if (x > 0) {
                    val neighbour = index - 1
                    if (mask[neighbour] && !visited[neighbour]) {
                        visited[neighbour] = true
                        stack[stackSize++] = neighbour
                    }
                }
                if (x < width - 1) {
                    val neighbour = index + 1
                    if (mask[neighbour] && !visited[neighbour]) {
                        visited[neighbour] = true
                        stack[stackSize++] = neighbour
                    }
                }
                if (y > 0) {
                    val neighbour = index - width
                    if (mask[neighbour] && !visited[neighbour]) {
                        visited[neighbour] = true
                        stack[stackSize++] = neighbour
                    }
                }
                if (y < height - 1) {
                    val neighbour = index + width
                    if (mask[neighbour] && !visited[neighbour]) {
                        visited[neighbour] = true
                        stack[stackSize++] = neighbour
                    }
                }
            }

            if (best == null || count > best.size) {
                best = Region(current.copyOf(count), count)
            }
        }
        return best
    }

    // --- stage 3: convex hull ----------------------------------------------------------------------

    /** Andrew's monotone chain. Returns the hull in counter-clockwise order. */
    internal fun convexHull(points: List<CropPoint>): List<CropPoint> {
        if (points.size < 3) return points
        val sorted = points.sortedWith(compareBy({ it.x }, { it.y }))
        val lower = ArrayList<CropPoint>()
        for (point in sorted) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], point) <= 0f) {
                lower.removeAt(lower.size - 1)
            }
            lower.add(point)
        }
        val upper = ArrayList<CropPoint>()
        for (point in sorted.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], point) <= 0f) {
                upper.removeAt(upper.size - 1)
            }
            upper.add(point)
        }
        lower.removeAt(lower.size - 1)
        upper.removeAt(upper.size - 1)
        return lower + upper
    }

    private fun cross(o: CropPoint, a: CropPoint, b: CropPoint): Float =
        (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    /**
     * Reduces the hull to at most [limit] points by repeatedly dropping the vertex whose removal
     * costs the least area. Keeps the corners that define the shape while making the quad search
     * cheap, and is deterministic (ties resolve by index).
     */
    internal fun simplifyHull(hull: List<CropPoint>, limit: Int): List<CropPoint> {
        if (hull.size <= limit) return hull
        val working = ArrayList(hull)
        while (working.size > limit) {
            var bestIndex = 0
            var bestCost = Float.MAX_VALUE
            for (i in working.indices) {
                val previous = working[(i + working.size - 1) % working.size]
                val next = working[(i + 1) % working.size]
                val cost = abs(cross(previous, working[i], next))
                if (cost < bestCost) {
                    bestCost = cost
                    bestIndex = i
                }
            }
            working.removeAt(bestIndex)
        }
        return working
    }

    // --- stage 4: maximum-area inscribed quadrilateral ---------------------------------------------

    /**
     * The four hull vertices, in hull order, enclosing the greatest area.
     *
     * Fitting the maximum-area quad — rather than taking a bounding box or the extreme points — is
     * what makes the outline follow a tilted object's true edges. The hull is simplified to at most
     * ~24 points first, so this is a few thousand cheap area computations.
     */
    internal fun maxAreaQuad(hull: List<CropPoint>): List<CropPoint>? {
        val n = hull.size
        if (n < 4) return null
        var best: List<CropPoint>? = null
        var bestArea = 0f
        for (a in 0 until n - 3) {
            for (b in a + 1 until n - 2) {
                for (c in b + 1 until n - 1) {
                    for (d in c + 1 until n) {
                        val area = quadArea(hull[a], hull[b], hull[c], hull[d])
                        if (area > bestArea) {
                            bestArea = area
                            best = listOf(hull[a], hull[b], hull[c], hull[d])
                        }
                    }
                }
            }
        }
        return best
    }

    /** Shoelace area of four points already in hull order. */
    private fun quadArea(p0: CropPoint, p1: CropPoint, p2: CropPoint, p3: CropPoint): Float {
        val points = listOf(p0, p1, p2, p3)
        var sum = 0f
        for (i in points.indices) {
            val a = points[i]
            val b = points[(i + 1) % points.size]
            sum += a.x * b.y - b.x * a.y
        }
        return abs(sum) / 2f
    }

    private fun normalize(quad: List<CropPoint>, width: Int, height: Int): List<CropPoint> {
        val wScale = (width - 1).coerceAtLeast(1).toFloat()
        val hScale = (height - 1).coerceAtLeast(1).toFloat()
        return quad.map { CropPoint(it.x / wScale, it.y / hScale) }
    }
}
