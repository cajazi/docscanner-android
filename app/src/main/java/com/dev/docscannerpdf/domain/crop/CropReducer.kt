package com.dev.docscannerpdf.domain.crop

/**
 * Pure transitions for the crop editor. No Android dependencies, so corner movement, reset,
 * and apply/cancel lifecycle are fully unit-testable. Geometry validation/auto-fix is delegated
 * to [PerspectiveGeometry].
 */
object CropReducer {

    /** Enters edit mode without changing the quad. */
    fun beginEditing(state: CropState): CropState =
        if (state.mode == CropMode.EDITING) state else state.copy(mode = CropMode.EDITING)

    /** Moves a corner to ([x], [y]) (clamped to the image), entering edit mode if needed. */
    fun moveCorner(state: CropState, corner: CropCorner, x: Float, y: Float): CropState {
        val point = CropPoint(x, y).clampedToUnit()
        return state.copy(
            quad = state.quad.withCorner(corner, point),
            mode = CropMode.EDITING
        )
    }

    /** Resets the editable quad back to the baseline, staying in edit mode. */
    fun resetQuad(state: CropState): CropState =
        state.copy(quad = state.originalQuad, mode = CropMode.EDITING)

    /**
     * Commits the crop: auto-fixes corner order and, when the quad is valid, moves to
     * [CropMode.APPLYING]. An invalid (non-convex/degenerate) quad is rejected — the state
     * stays in [CropMode.EDITING] so the user can correct it.
     */
    fun applyCrop(state: CropState): CropState {
        val fixed = PerspectiveGeometry.normalize(state.quad)
        return if (PerspectiveGeometry.isValid(fixed)) {
            state.copy(quad = fixed, mode = CropMode.APPLYING)
        } else {
            state.copy(mode = CropMode.EDITING)
        }
    }

    /** True when the current quad can be applied. */
    fun canApply(state: CropState): Boolean =
        PerspectiveGeometry.isValid(PerspectiveGeometry.normalize(state.quad))

    /**
     * Completes an apply after the image has been warped and replaced: the new image is now the
     * cropped region, so the quad resets to full and the baseline is updated.
     */
    fun finishApply(state: CropState, newSourceUri: String?): CropState =
        state.copy(
            sourceImageUri = newSourceUri ?: state.sourceImageUri,
            quad = PerspectiveQuad.full(),
            originalQuad = PerspectiveQuad.full(),
            mode = CropMode.IDLE
        )

    /** Cancels editing, restoring the baseline quad and returning to idle. */
    fun cancelCrop(state: CropState): CropState =
        state.copy(quad = state.originalQuad, mode = CropMode.IDLE)
}
