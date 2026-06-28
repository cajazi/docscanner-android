package com.dev.docscannerpdf.domain.crop

/** A point in normalized image space: [x]/[y] are fractions in 0f..1f of the source image. */
data class CropPoint(val x: Float, val y: Float) {
    fun clampedToUnit(): CropPoint = CropPoint(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))
}

/** The four corners of a crop quad, in clockwise order from the top-left. */
enum class CropCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_RIGHT, BOTTOM_LEFT }

/**
 * A perspective crop region defined by its four corners (normalized). Corners are stored in
 * clockwise order; [PerspectiveGeometry] keeps them ordered and convex.
 */
data class PerspectiveQuad(
    val topLeft: CropPoint,
    val topRight: CropPoint,
    val bottomRight: CropPoint,
    val bottomLeft: CropPoint
) {
    /** Corners in clockwise order: TL, TR, BR, BL. */
    fun corners(): List<CropPoint> = listOf(topLeft, topRight, bottomRight, bottomLeft)

    fun corner(which: CropCorner): CropPoint = when (which) {
        CropCorner.TOP_LEFT -> topLeft
        CropCorner.TOP_RIGHT -> topRight
        CropCorner.BOTTOM_RIGHT -> bottomRight
        CropCorner.BOTTOM_LEFT -> bottomLeft
    }

    fun withCorner(which: CropCorner, point: CropPoint): PerspectiveQuad = when (which) {
        CropCorner.TOP_LEFT -> copy(topLeft = point)
        CropCorner.TOP_RIGHT -> copy(topRight = point)
        CropCorner.BOTTOM_RIGHT -> copy(bottomRight = point)
        CropCorner.BOTTOM_LEFT -> copy(bottomLeft = point)
    }

    val centroid: CropPoint
        get() = CropPoint(
            x = corners().map { it.x }.average().toFloat(),
            y = corners().map { it.y }.average().toFloat()
        )

    companion object {
        /** A quad covering the whole image. */
        fun full(): PerspectiveQuad = PerspectiveQuad(
            topLeft = CropPoint(0f, 0f),
            topRight = CropPoint(1f, 0f),
            bottomRight = CropPoint(1f, 1f),
            bottomLeft = CropPoint(0f, 1f)
        )

        /** A quad inset from the edges by [inset] (e.g. a CamScanner-style starting frame). */
        fun inset(inset: Float = 0.08f): PerspectiveQuad {
            val low = inset.coerceIn(0f, 0.45f)
            val high = 1f - low
            return PerspectiveQuad(
                topLeft = CropPoint(low, low),
                topRight = CropPoint(high, low),
                bottomRight = CropPoint(high, high),
                bottomLeft = CropPoint(low, high)
            )
        }
    }
}

/** Lifecycle of a crop edit. */
enum class CropMode { IDLE, EDITING, APPLYING }

/**
 * Immutable crop-editor state. [quad] is the live, editable region; [originalQuad] is the
 * baseline used by reset/cancel. Coordinates are normalized so the same state drives the
 * on-screen overlay and the pixel warp regardless of display size.
 */
data class CropState(
    val sourceImageUri: String? = null,
    val quad: PerspectiveQuad = PerspectiveQuad.full(),
    val originalQuad: PerspectiveQuad = PerspectiveQuad.full(),
    val mode: CropMode = CropMode.IDLE
) {
    val isEditing: Boolean get() = mode == CropMode.EDITING
    val isApplying: Boolean get() = mode == CropMode.APPLYING
    val isDirty: Boolean get() = quad != originalQuad
}
