package com.dev.docscannerpdf.domain.mainscan

import com.dev.docscannerpdf.domain.crop.CropPoint
import com.dev.docscannerpdf.domain.crop.PerspectiveGeometry
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Pure applicability predicate for a Main Scanner quadrilateral.
 *
 * This preserves the exact Main Scanner geometry rules without bringing the crop editor's
 * interaction state into the detector-domain foundation.
 *
 * This predicate is stricter than PerspectiveGeometry.isValid:
 * it requires a larger minimum area and meaningful separation between
 * both pairs of opposite-edge midpoints.
 *
 * Coordinate clamping, normalization and winding preservation are
 * intentionally outside this predicate.
 */
internal object MainScanQuadValidity {

    private const val MIN_AREA = 0.02f
    private const val MIN_EDGE_SEPARATION = 0.05f

    fun isApplicable(quad: PerspectiveQuad): Boolean {
        if (!PerspectiveGeometry.isConvex(quad)) return false
        if (abs(PerspectiveGeometry.signedArea(quad)) < MIN_AREA) return false

        val topBottomSeparation = midpointDistance(
            quad.topLeft,
            quad.topRight,
            quad.bottomLeft,
            quad.bottomRight
        )

        val leftRightSeparation = midpointDistance(
            quad.topLeft,
            quad.bottomLeft,
            quad.topRight,
            quad.bottomRight
        )

        return topBottomSeparation >= MIN_EDGE_SEPARATION &&
            leftRightSeparation >= MIN_EDGE_SEPARATION
    }

    private fun midpointDistance(
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
}
