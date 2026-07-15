package com.dev.docscannerpdf.domain.idscan

import com.dev.docscannerpdf.domain.filter.DocumentFilter

/** Which captured side the ID-card review screen's crop/rotate/filter controls currently target. */
enum class IdCardReviewSide { FRONT, BACK }

/**
 * Pure state for the CamScanner-style ID-card review step shown after guided capture and before
 * the final Document Ready preview. Each side independently tracks:
 *
 * - its BASE image (`*BaseImageUri`): the authoritative capture-baked image with the current
 *   crop applied and NO user-selected filter and NO rotation baked. Filters always render from
 *   this file and never overwrite it, so re-applying a filter can never compound and
 *   [DocumentFilter.ORIGINAL] is always recoverable;
 * - its selected [DocumentFilter] (`*Filter`), defaulting to [DocumentFilter.ENHANCE] to keep
 *   the familiar enhanced first appearance — now rendered non-destructively from the base;
 * - its rendered filter output (`*RenderedImageUri`): the filter applied to the current base,
 *   null while a render is pending or when the filter is ORIGINAL (display falls back to base);
 * - its rotation in degrees, display-only until the green-check save bakes it.
 *
 * Rotating, filtering, or cropping one side must never touch the other side. Uris are plain
 * strings (not [android.net.Uri]) so this model stays framework-free and unit-testable; the
 * review screen converts to/from [android.net.Uri] at its edges.
 */
data class IdCardReviewState(
    val frontBaseImageUri: String,
    val backBaseImageUri: String? = null,
    val frontFilter: DocumentFilter = DocumentFilter.ENHANCE,
    val backFilter: DocumentFilter = DocumentFilter.ENHANCE,
    val frontRenderedImageUri: String? = null,
    val backRenderedImageUri: String? = null,
    val frontRotationDegrees: Int = 0,
    val backRotationDegrees: Int = 0,
    val selectedSide: IdCardReviewSide = IdCardReviewSide.FRONT,
    val title: String = "ID Card"
) {
    fun baseImageUri(side: IdCardReviewSide): String? = when (side) {
        IdCardReviewSide.FRONT -> frontBaseImageUri
        IdCardReviewSide.BACK -> backBaseImageUri
    }

    fun filter(side: IdCardReviewSide): DocumentFilter = when (side) {
        IdCardReviewSide.FRONT -> frontFilter
        IdCardReviewSide.BACK -> backFilter
    }

    fun renderedImageUri(side: IdCardReviewSide): String? = when (side) {
        IdCardReviewSide.FRONT -> frontRenderedImageUri
        IdCardReviewSide.BACK -> backRenderedImageUri
    }

    /**
     * What the review tile for [side] should show right now: the rendered filter output when
     * one is published, otherwise the base image (which is exactly what ORIGINAL means, and the
     * least-wrong thing to show while a non-ORIGINAL render is still in flight).
     */
    fun displayImageUri(side: IdCardReviewSide): String? = when (side) {
        IdCardReviewSide.FRONT -> frontRenderedImageUri ?: frontBaseImageUri
        IdCardReviewSide.BACK -> backBaseImageUri?.let { backRenderedImageUri ?: it }
    }

    fun rotationDegrees(side: IdCardReviewSide): Int = when (side) {
        IdCardReviewSide.FRONT -> frontRotationDegrees
        IdCardReviewSide.BACK -> backRotationDegrees
    }
}

/**
 * Pure reducer for [IdCardReviewState] transitions. Selecting a side is the ONLY thing tapping
 * an image may do (rotation happens solely via [rotateSelected], wired to the explicit Rotate
 * button); filters apply solely to the selected side and only through [applyFilter] +
 * [withRenderedFilter]. Kept free of Android/Bitmap/coroutine types so every transition is unit
 * testable directly — the asynchronous render orchestration (jobs, generations) lives in the
 * activity, which consults these reducers for every state change.
 */
object IdCardReviewFlow {

    /** Selects [side] as the crop/rotate/filter target. Selecting BACK when there is no back is a no-op. */
    fun selectSide(state: IdCardReviewState, side: IdCardReviewSide): IdCardReviewState =
        if (side == IdCardReviewSide.BACK && state.backBaseImageUri == null) {
            state
        } else {
            state.copy(selectedSide = side)
        }

    /** Rotates only [IdCardReviewState.selectedSide] by 90°, wrapping at 360; the other side is untouched. */
    fun rotateSelected(state: IdCardReviewState): IdCardReviewState = when (state.selectedSide) {
        IdCardReviewSide.FRONT -> state.copy(frontRotationDegrees = (state.frontRotationDegrees + 90) % 360)
        IdCardReviewSide.BACK -> state.copy(backRotationDegrees = (state.backRotationDegrees + 90) % 360)
    }

    /**
     * Applies [filter] to the currently selected side: sets the side's filter and clears its
     * rendered output (the display falls back to the base while the new render is produced; for
     * [DocumentFilter.ORIGINAL] the base IS the final display and no render ever runs). The
     * other side's filter/image/rotation, both crops (bases), both rotations, and the title are
     * all untouched. Re-selecting the side's current filter when its output is already settled
     * is a no-op, so repeated taps can never trigger pointless re-renders or compounding.
     */
    fun applyFilter(state: IdCardReviewState, filter: DocumentFilter): IdCardReviewState {
        val side = state.selectedSide
        if (side == IdCardReviewSide.BACK && state.backBaseImageUri == null) return state
        val alreadySettled = state.filter(side) == filter &&
            (filter == DocumentFilter.ORIGINAL || state.renderedImageUri(side) != null)
        if (alreadySettled) return state
        return when (side) {
            IdCardReviewSide.FRONT -> state.copy(frontFilter = filter, frontRenderedImageUri = null)
            IdCardReviewSide.BACK -> state.copy(backFilter = filter, backRenderedImageUri = null)
        }
    }

    /**
     * Publishes a finished filter render for [side] — but ONLY if the state still wants it:
     * the side's current filter must equal [filter] and its base must equal [baseImageUri]
     * (the render's inputs). A publish for ORIGINAL is always rejected (ORIGINAL never renders).
     * This is the pure half of stale-result rejection; the activity's per-side generation token
     * is the other half.
     */
    fun withRenderedFilter(
        state: IdCardReviewState,
        side: IdCardReviewSide,
        filter: DocumentFilter,
        baseImageUri: String,
        renderedImageUri: String
    ): IdCardReviewState {
        if (filter == DocumentFilter.ORIGINAL) return state
        if (state.filter(side) != filter) return state
        if (state.baseImageUri(side) != baseImageUri) return state
        return when (side) {
            IdCardReviewSide.FRONT -> state.copy(frontRenderedImageUri = renderedImageUri)
            IdCardReviewSide.BACK -> state.copy(backRenderedImageUri = renderedImageUri)
        }
    }

    /**
     * Installs a freshly cropped file as [side]'s new base image: the side keeps its selected
     * filter (which the caller must re-render from the new base) and its rotation, but its stale
     * rendered output — produced from the OLD base — is cleared. Cropping a back side that
     * doesn't exist is a no-op. The other side is untouched.
     */
    fun withCroppedBase(
        state: IdCardReviewState,
        side: IdCardReviewSide,
        newBaseImageUri: String
    ): IdCardReviewState = when (side) {
        IdCardReviewSide.FRONT -> state.copy(frontBaseImageUri = newBaseImageUri, frontRenderedImageUri = null)
        IdCardReviewSide.BACK -> if (state.backBaseImageUri == null) {
            state
        } else {
            state.copy(backBaseImageUri = newBaseImageUri, backRenderedImageUri = null)
        }
    }

    /**
     * Renames the review's title (the CamScanner-style pencil-edit action next to the title).
     * A blank/whitespace-only [newTitle] is a no-op — the title driving the eventually-saved
     * document must never end up empty.
     */
    fun renameTitle(state: IdCardReviewState, newTitle: String): IdCardReviewState {
        val trimmed = newTitle.trim()
        return if (trimmed.isBlank()) state else state.copy(title = trimmed)
    }
}
