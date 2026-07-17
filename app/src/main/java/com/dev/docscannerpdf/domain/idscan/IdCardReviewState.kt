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
 * - its selected [DocumentFilter] (`*Filter`), defaulting to [DocumentFilter.ENHANCE];
 * - its rendered filter output (`*RenderedImageUri`) plus WHICH filter produced it
 *   (`*RenderedFilter`) — while a new filter renders, the last valid rendered image keeps
 *   displaying instead of flashing back to the base, so the pairing is needed to know whether
 *   the rendered file matches the current selection (the save path must never reuse a stale
 *   render from a different filter);
 * - a render-pending flag (`*RenderPending`): true from filter selection (or initial capture,
 *   since ENHANCE is the default) until the render publishes or fails, driving the tile's
 *   small processing indicator so the UI never silently presents the base as "enhanced";
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
    val frontRenderedFilter: DocumentFilter? = null,
    val backRenderedFilter: DocumentFilter? = null,
    val frontRenderPending: Boolean = true,
    val backRenderPending: Boolean = false,
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

    fun renderedFilter(side: IdCardReviewSide): DocumentFilter? = when (side) {
        IdCardReviewSide.FRONT -> frontRenderedFilter
        IdCardReviewSide.BACK -> backRenderedFilter
    }

    /** True while [side]'s selected filter render is in flight — drives the tile's spinner. */
    fun isRenderPending(side: IdCardReviewSide): Boolean = when (side) {
        IdCardReviewSide.FRONT -> frontRenderPending
        IdCardReviewSide.BACK -> backRenderPending
    }

    /**
     * The rendered output ONLY if it was produced by [side]'s currently selected filter —
     * what the save path may reuse. Null while a different filter's output is on screen.
     */
    fun settledRenderedImageUri(side: IdCardReviewSide): String? =
        renderedImageUri(side)?.takeIf { renderedFilter(side) == filter(side) }

    /**
     * What the review tile for [side] should show right now: the last valid rendered output
     * when one exists (even while a newer filter render is still in flight — no flashing back
     * to the base), otherwise the base image (which is exactly what ORIGINAL means, and the
     * only thing available before the very first render publishes).
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
     * Applies [filter] to the currently selected side: sets the side's filter and marks its
     * render pending. The last valid rendered image is KEPT on display while the new render is
     * produced (no flash back to the base); [DocumentFilter.ORIGINAL] needs no render — the
     * base IS the final display, so the rendered output clears immediately. The other side's
     * filter/image/rotation, both crops (bases), both rotations, and the title are all
     * untouched. Re-selecting a filter that is already rendered or already in flight is a
     * no-op, so repeated taps can never trigger pointless re-renders or compounding.
     */
    fun applyFilter(state: IdCardReviewState, filter: DocumentFilter): IdCardReviewState {
        val side = state.selectedSide
        if (side == IdCardReviewSide.BACK && state.backBaseImageUri == null) return state
        val alreadySettled = state.filter(side) == filter && (
            filter == DocumentFilter.ORIGINAL ||
                state.settledRenderedImageUri(side) != null ||
                state.isRenderPending(side)
            )
        if (alreadySettled) return state
        return when (side) {
            IdCardReviewSide.FRONT -> if (filter == DocumentFilter.ORIGINAL) {
                state.copy(
                    frontFilter = filter,
                    frontRenderedImageUri = null,
                    frontRenderedFilter = null,
                    frontRenderPending = false
                )
            } else {
                state.copy(frontFilter = filter, frontRenderPending = true)
            }
            IdCardReviewSide.BACK -> if (filter == DocumentFilter.ORIGINAL) {
                state.copy(
                    backFilter = filter,
                    backRenderedImageUri = null,
                    backRenderedFilter = null,
                    backRenderPending = false
                )
            } else {
                state.copy(backFilter = filter, backRenderPending = true)
            }
        }
    }

    /**
     * Publishes a finished filter render for [side] — but ONLY if the state still wants it:
     * the side's current filter must equal [filter] and its base must equal [baseImageUri]
     * (the render's inputs). A publish for ORIGINAL is always rejected (ORIGINAL never renders).
     * This is the pure half of stale-result rejection; the activity's per-side generation token
     * is the other half — a cancelled stale render never reaches this call at all.
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
            IdCardReviewSide.FRONT -> state.copy(
                frontRenderedImageUri = renderedImageUri,
                frontRenderedFilter = filter,
                frontRenderPending = false
            )
            IdCardReviewSide.BACK -> state.copy(
                backRenderedImageUri = renderedImageUri,
                backRenderedFilter = filter,
                backRenderPending = false
            )
        }
    }

    /**
     * Truthful recovery after a FAILED render for [side]: once the spinner stops, the selected
     * filter must describe the pixels actually on screen. If a previous valid render exists,
     * the selection reverts to THAT filter (its pixels stayed on display); otherwise the
     * selection reverts to [DocumentFilter.ORIGINAL] and the base displays. Either way the
     * failed filter remains available for a retry tap, and the caller surfaces the error
     * message. Guarded by the render's inputs — the failure is ignored if the side's filter or
     * base has already moved on (the activity's generation token is the other half of that
     * staleness protection). The other side is never touched.
     */
    fun withRenderFailed(
        state: IdCardReviewState,
        side: IdCardReviewSide,
        filter: DocumentFilter,
        baseImageUri: String
    ): IdCardReviewState {
        if (state.filter(side) != filter) return state
        if (state.baseImageUri(side) != baseImageUri) return state
        val fallbackFilter = state.renderedFilter(side)
        val fallbackUri = state.renderedImageUri(side)
        return if (fallbackFilter != null && fallbackUri != null) {
            when (side) {
                IdCardReviewSide.FRONT -> state.copy(frontFilter = fallbackFilter, frontRenderPending = false)
                IdCardReviewSide.BACK -> state.copy(backFilter = fallbackFilter, backRenderPending = false)
            }
        } else {
            when (side) {
                IdCardReviewSide.FRONT -> state.copy(
                    frontFilter = DocumentFilter.ORIGINAL,
                    frontRenderedImageUri = null,
                    frontRenderedFilter = null,
                    frontRenderPending = false
                )
                IdCardReviewSide.BACK -> state.copy(
                    backFilter = DocumentFilter.ORIGINAL,
                    backRenderedImageUri = null,
                    backRenderedFilter = null,
                    backRenderPending = false
                )
            }
        }
    }

    /**
     * Installs a freshly cropped file as [side]'s new base image: the side keeps its selected
     * filter (which the caller must re-render from the new base) and its rotation, but its
     * rendered output — produced from the OLD base — is cleared, and the render goes pending
     * again for a non-ORIGINAL filter. Cropping a back side that doesn't exist is a no-op.
     * The other side is untouched.
     */
    fun withCroppedBase(
        state: IdCardReviewState,
        side: IdCardReviewSide,
        newBaseImageUri: String
    ): IdCardReviewState = when (side) {
        IdCardReviewSide.FRONT -> state.copy(
            frontBaseImageUri = newBaseImageUri,
            frontRenderedImageUri = null,
            frontRenderedFilter = null,
            frontRenderPending = state.frontFilter != DocumentFilter.ORIGINAL
        )
        IdCardReviewSide.BACK -> if (state.backBaseImageUri == null) {
            state
        } else {
            state.copy(
                backBaseImageUri = newBaseImageUri,
                backRenderedImageUri = null,
                backRenderedFilter = null,
                backRenderPending = state.backFilter != DocumentFilter.ORIGINAL
            )
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
