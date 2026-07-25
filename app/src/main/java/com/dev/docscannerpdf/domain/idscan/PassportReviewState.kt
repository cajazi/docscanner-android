package com.dev.docscannerpdf.domain.idscan

import com.dev.docscannerpdf.domain.filter.DocumentFilter

/**
 * Pure state for the dedicated single-page passport review. Deliberately NOT
 * [IdCardReviewState] — that model is two-sided (front/back) and shared use would let a passport
 * regress the working card workflow. The invariants mirror the card review's hard-won ones:
 *
 * - [baseUri] is the CANONICAL upright portrait crop from [PassportCaptureBaker]; filters always
 *   render from it, so re-applying a filter can never compound and Original is always
 *   recoverable.
 * - [displayedUri] is what the preview shows right now: the published render when one exists for
 *   the CURRENT selection, otherwise the base.
 * - [renderedFilter] records which filter produced [displayedUri], so a render belonging to a
 *   superseded selection is never reused at save time.
 * - Rotation is NEVER a display transform. [requestedRotationDegrees] is what the user has asked
 *   for (normalized 0/90/180/270); [settledRotationDegrees] is the angle whose PIXELS are actually
 *   baked into [rotationRenderedUri]. The preview always shows fully-baked, fully-contained pixels
 *   ([displayedUri]) — it keeps displaying the previously SETTLED image while the newly requested
 *   rotation bakes, so a rotated `ContentScale.Fit` can never clip at 90°/270° for even one frame.
 * - [watermarkText] is the user's text (null = none). [watermarkRenderedUri] is the ACTUAL
 *   [PassportWatermarkRenderer] output — the single authoritative preview image, so the review
 *   never draws a separate approximate overlay. It is invalidated (nulled) on any upstream
 *   change (filter/rotation/crop) and re-rendered from the latest settled+rotated image.
 * - [saveInProgress] makes Confirm single-flight.
 */
data class PassportReviewState(
    val baseUri: String,
    val filteredUri: String = baseUri,
    val selectedFilter: DocumentFilter = DocumentFilter.ORIGINAL,
    val renderedFilter: DocumentFilter? = null,
    /** The rotation the user has requested (0/90/180/270). Not shown directly — only its bake is. */
    val requestedRotationDegrees: Int = 0,
    /** The rotation whose pixels are baked into [rotationRenderedUri] (0 when none). */
    val settledRotationDegrees: Int = 0,
    val rotationRenderedUri: String? = null,
    val rotationRenderPending: Boolean = false,
    val rotationRenderError: Boolean = false,
    val renderPending: Boolean = false,
    val watermarkText: String? = null,
    val watermarkRenderedUri: String? = null,
    val watermarkRenderPending: Boolean = false,
    val watermarkError: Boolean = false,
    /**
     * True from crop Apply until the authoritative full-resolution crop settles into a new
     * [baseUri] (or fails). The review shows the instant in-memory cropped preview meanwhile;
     * Confirm stays disabled so the low-resolution preview crop can never be what gets saved.
     */
    val cropRenderPending: Boolean = false,
    val saveInProgress: Boolean = false,
    val title: String = "Passport"
) {
    /**
     * The filter render that may be reused: only one produced by the CURRENTLY selected filter.
     * Null while a newer selection is still rendering, so the save path re-renders rather than
     * persisting the wrong filter's pixels.
     */
    val settledFilterUri: String?
        // [selectedFilter] is non-null, so equality already implies a non-null [renderedFilter] —
        // no separate null check (it would be always-true and is flagged as such by the compiler).
        get() = filteredUri.takeIf { renderedFilter == selectedFilter }

    /**
     * The ONE image the review displays and the save persists — ALWAYS fully-baked, fully-contained
     * SETTLED pixels, never a display-rotation transform:
     *
     * - the watermark render (filter + rotation + watermark baked in) when one is active+rendered;
     * - otherwise the settled rotation bake when the page is rotated (its 90°/270° pixels are baked,
     *   so `ContentScale.Fit` contains the whole page);
     * - otherwise the settled filtered page (rotation 0).
     *
     * While a newly requested rotation is still baking, [settledRotationDegrees]/[rotationRenderedUri]
     * keep pointing at the PREVIOUS settled image, so the preview shows that fully-contained page —
     * never the un-rotated page under a rotation transform. "What you see is what is saved" therefore
     * holds for every frame.
     */
    val displayedUri: String
        get() = when {
            watermarkText != null && watermarkRenderedUri != null -> watermarkRenderedUri
            settledRotationDegrees != 0 && rotationRenderedUri != null -> rotationRenderedUri
            else -> filteredUri
        }

    /**
     * The clockwise rotation ACTUALLY baked into [displayedUri]'s pixels — by construction it is
     * read from the same branch that chose [displayedUri], so the two can never disagree:
     *
     * - the watermark render is only ever published for the CURRENT [requestedRotationDegrees]
     *   ([PassportReviewFlow.withWatermarkRendered] rejects any other), and every rotation change
     *   invalidates it;
     * - the rotation bake carries [settledRotationDegrees], which is the angle whose pixels landed;
     * - the filtered page is upright (0°).
     *
     * The crop editor needs exactly this: it shows [displayedUri], so a rectangle drawn on it is
     * expressed in a frame rotated by this much, and must be un-rotated by
     * [PassportCropRotationMapping] before it can address the canonical [baseUri].
     */
    val displayedRotationDegrees: Int
        get() = when {
            watermarkText != null && watermarkRenderedUri != null -> requestedRotationDegrees
            settledRotationDegrees != 0 && rotationRenderedUri != null -> settledRotationDegrees
            else -> 0
        }

    /** True while ANY of the filter/rotation/watermark renders is in flight — for the spinner. */
    val renderInFlight: Boolean get() = renderPending || rotationRenderPending || watermarkRenderPending

    /**
     * True while any authoritative full-resolution work (filter/rotation/watermark render, or a
     * crop settling into a new base) is pending behind the instant in-memory preview. Drives the
     * review's single subtle progress indication (the thin line under the title bar) — never a
     * circular spinner over the image or toolbar.
     */
    val workInFlight: Boolean get() = renderInFlight || cropRenderPending

    /**
     * A requested rotation that is not yet backed by a matching settled bake — because one is
     * baking ([rotationRenderPending]) or the bake failed ([rotationRenderError]). Save must not
     * proceed: the displayed pixels are the PREVIOUS settled angle, not the requested one. Only
     * meaningful without an active watermark (the watermark render bakes rotation itself).
     */
    val rotationUnresolved: Boolean
        get() = watermarkText == null &&
            requestedRotationDegrees != 0 &&
            (rotationRenderPending || rotationRenderError)

    /**
     * A standalone rotation bake should be (re)attempted: a non-zero rotation is requested, no
     * watermark is handling rotation, a bake is pending, and the previous attempt did not error
     * (so a persistent failure can't spin). The screen owns the actual async bake.
     */
    val rotationNeedsRender: Boolean
        get() = watermarkText == null &&
            requestedRotationDegrees != 0 &&
            rotationRenderPending &&
            !rotationRenderError

    /** True when a watermark is requested but its authoritative render hasn't landed yet. */
    val watermarkNeedsRender: Boolean get() = watermarkText != null && watermarkRenderedUri == null

    /**
     * A watermark the user REQUESTED that is not yet backed by an authoritative render — whether
     * because it is still rendering ([watermarkRenderPending]) or because its render FAILED
     * ([watermarkError]). Save must never proceed in this state: the toolbar shows the watermark
     * as active, so saving the un-watermarked page would be a silent lie. Confirm stays disabled
     * until the watermark either renders successfully or is removed.
     */
    val watermarkUnresolved: Boolean get() = watermarkText != null && watermarkRenderedUri == null

    /**
     * Whether Confirm/save may be initiated right now: no save running, the filter render settled,
     * the requested ROTATION settled to matching baked pixels, and no unresolved (pending or
     * failed) watermark. The single truthful gate the UI disables Confirm on and
     * [PassportReviewFlow.beginSave] enforces — so save always reuses exactly [displayedUri].
     */
    val canConfirm: Boolean
        get() = !saveInProgress && !renderPending && !cropRenderPending &&
            !rotationUnresolved && !watermarkUnresolved
}

/**
 * Where a SUCCESSFUL passport save navigates. Deliberately typed (not booleans) so the passport
 * success path can never resolve to the generic Document Ready / backend-processing / E2E
 * preview — those are simply not values here.
 */
enum class PassportCompletionDestination {
    /** Open the newly saved document in the clean document viewer, over the Documents list. */
    SAVED_DOCUMENT,

    /** Open the Documents list (used when no valid saved id is available). */
    DOCUMENTS
}

/** Pure routing decision for a completed passport save — never the generic preview. */
object PassportCompletion {
    fun destinationFor(savedDocumentId: Long?): PassportCompletionDestination =
        if (savedDocumentId != null && savedDocumentId > 0L) {
            PassportCompletionDestination.SAVED_DOCUMENT
        } else {
            PassportCompletionDestination.DOCUMENTS
        }
}

/**
 * Pure reducer for [PassportReviewState]. Framework-free (no Uri/Bitmap/coroutines) so every
 * transition is JVM-testable; the screen owns the asynchronous rendering and consults these
 * functions for all state changes.
 */
object PassportReviewFlow {

    /**
     * Invalidates the current watermark render because an upstream transform (filter, rotation,
     * crop) changed. The rendered file is dropped; if watermark text is still set, the render
     * goes pending again so the caller re-renders it from the new settled+rotated image.
     */
    private fun invalidateWatermark(state: PassportReviewState): PassportReviewState =
        state.copy(
            watermarkRenderedUri = null,
            watermarkRenderPending = state.watermarkText != null,
            // A fresh render attempt is being armed, so any prior failure no longer applies.
            watermarkError = false
        )

    /**
     * Re-arms the rotation bake because an upstream input (the filtered page, or the watermark
     * toggle) changed. Crucially it KEEPS the previously settled [rotationRenderedUri] on screen
     * (so the preview never flashes an un-baked page) and only marks a re-bake pending — the new
     * bake replaces the settled image atomically via [withRotationRendered]. Reads the
     * ALREADY-updated state, so callers apply their change first.
     */
    private fun invalidateRotationRender(state: PassportReviewState): PassportReviewState =
        state.copy(
            rotationRenderPending = state.requestedRotationDegrees != 0 && state.watermarkText == null,
            rotationRenderError = false
        )

    /**
     * Rotates the page 90° clockwise, normalized to 0/90/180/270 (four taps return to 0). The
     * previously SETTLED image stays on screen while the new angle bakes; only a return to 0°
     * settles instantly (upright = the filtered page, no bake). Any watermark render is
     * invalidated — rotation is baked before the watermark, so it must re-render at the new angle.
     */
    fun rotate(state: PassportReviewState): PassportReviewState {
        val newRequested = (state.requestedRotationDegrees + 90) % 360
        val rotated = state.copy(requestedRotationDegrees = newRequested)
        val withRotation = if (newRequested == 0) {
            // Upright: the settled image is simply the filtered page — settle instantly, no bake,
            // no transform. The previously baked rotation file is simply no longer referenced by
            // this state (this reducer is framework-free and owns no file lifecycle).
            rotated.copy(
                settledRotationDegrees = 0,
                rotationRenderedUri = null,
                rotationRenderPending = false,
                rotationRenderError = false
            )
        } else {
            // Keep the PREVIOUS settled image displayed; bake the new angle asynchronously.
            rotated.copy(
                rotationRenderPending = state.watermarkText == null,
                rotationRenderError = false
            )
        }
        return invalidateWatermark(withRotation)
    }

    /**
     * Selects [filter]. ORIGINAL settles immediately on the canonical base; any other filter
     * marks a pending render while KEEPING the last valid image on screen. Either way, a changed
     * filter invalidates the watermark render (it was drawn on the old filtered image).
     * Re-selecting an already-settled/pending filter is a no-op.
     */
    fun applyFilter(state: PassportReviewState, filter: DocumentFilter): PassportReviewState {
        val settled = state.selectedFilter == filter &&
            (filter == DocumentFilter.ORIGINAL || state.settledFilterUri != null || state.renderPending)
        if (settled) return state
        val next = if (filter == DocumentFilter.ORIGINAL) {
            state.copy(
                selectedFilter = filter,
                filteredUri = state.baseUri,
                renderedFilter = null,
                renderPending = false
            )
        } else {
            state.copy(selectedFilter = filter, renderPending = true)
        }
        return invalidateRotationRender(invalidateWatermark(next))
    }

    /**
     * Publishes a completed filter render — only if the state still wants exactly this
     * ([filter], [fromBaseUri]) pair. ORIGINAL never renders. The watermark stays invalidated
     * (pending) so it re-renders from this freshly filtered image.
     */
    fun withRenderedFilter(
        state: PassportReviewState,
        filter: DocumentFilter,
        fromBaseUri: String,
        renderedUri: String
    ): PassportReviewState {
        if (filter == DocumentFilter.ORIGINAL) return state
        if (state.selectedFilter != filter) return state
        if (state.baseUri != fromBaseUri) return state
        return state.copy(
            filteredUri = renderedUri,
            renderedFilter = filter,
            renderPending = false,
            // Keep the previously settled rotation image displayed; re-arm its bake from the new
            // filtered page (Confirm stays blocked via rotationUnresolved until it re-settles).
            rotationRenderPending = state.requestedRotationDegrees != 0 && state.watermarkText == null,
            rotationRenderError = false,
            watermarkRenderedUri = null,
            watermarkRenderPending = state.watermarkText != null,
            watermarkError = false
        )
    }

    /**
     * Truthful recovery after a FAILED filter render: reverts the selection to whatever filter's
     * pixels are actually on screen (or ORIGINAL/base). Ignored when the request is already
     * stale.
     */
    fun withRenderFailed(
        state: PassportReviewState,
        filter: DocumentFilter,
        fromBaseUri: String
    ): PassportReviewState {
        if (state.selectedFilter != filter) return state
        if (state.baseUri != fromBaseUri) return state
        val fallback = state.renderedFilter
        return if (fallback != null) {
            state.copy(selectedFilter = fallback, renderPending = false)
        } else {
            state.copy(
                selectedFilter = DocumentFilter.ORIGINAL,
                filteredUri = state.baseUri,
                renderedFilter = null,
                renderPending = false
            )
        }
    }

    /**
     * Marks the authoritative full-resolution crop as in flight the moment the user taps Apply.
     * The review returns immediately with the in-memory cropped PREVIEW; Confirm stays disabled
     * (via [PassportReviewState.canConfirm]) until [withCroppedBase] installs the settled
     * full-resolution base — so the downscaled preview crop can never become the saved pixels.
     */
    fun beginCrop(state: PassportReviewState): PassportReviewState =
        state.copy(cropRenderPending = true)

    /** Clears the crop-pending flag after a FAILED crop render so the review is editable again. */
    fun cropFailed(state: PassportReviewState): PassportReviewState =
        state.copy(cropRenderPending = false)

    /**
     * Installs a freshly cropped page as the new canonical base. The old filter render (from the
     * OLD base) and the old watermark render are both discarded; the selected filter re-renders
     * from the new base and the watermark re-renders after it. Rotation is preserved.
     */
    fun withCroppedBase(state: PassportReviewState, newBaseUri: String): PassportReviewState =
        invalidateRotationRender(
            invalidateWatermark(
                state.copy(
                    baseUri = newBaseUri,
                    filteredUri = newBaseUri,
                    renderedFilter = null,
                    renderPending = state.selectedFilter != DocumentFilter.ORIGINAL,
                    cropRenderPending = false
                )
            )
        )

    /**
     * Publishes a completed rotation bake as the new SETTLED image — its pixels are rotated so the
     * preview shows it un-rotated and fully contained. Accepted only if it still matches the
     * current ([fromFilteredUri], [atRotation]) and no watermark has since taken over rotation, so
     * a stale bake can never overwrite newer state. Until this lands, the previous settled image
     * stayed on screen; here it swaps atomically to the requested angle.
     */
    fun withRotationRendered(
        state: PassportReviewState,
        fromFilteredUri: String,
        atRotation: Int,
        renderedUri: String
    ): PassportReviewState {
        if (state.filteredUri != fromFilteredUri) return state
        if (state.requestedRotationDegrees != atRotation) return state
        if (state.watermarkText != null) return state
        return state.copy(
            rotationRenderedUri = renderedUri,
            settledRotationDegrees = atRotation,
            rotationRenderPending = false,
            rotationRenderError = false
        )
    }

    /**
     * Records a FAILED rotation bake as an explicit, recoverable error. The previous settled image
     * stays on screen (never a clipped transform), [rotationUnresolved] stays true so Confirm is
     * disabled, and a further Rotate tap re-attempts. Orientation is never silently lost or shown
     * clipped. Stale failures (angle no longer requested) are ignored.
     */
    fun withRotationFailed(state: PassportReviewState, atRotation: Int): PassportReviewState {
        if (state.requestedRotationDegrees != atRotation) return state
        return state.copy(rotationRenderPending = false, rotationRenderError = true)
    }

    /**
     * Sets the user's watermark text (null/blank clears it). Setting text marks the watermark
     * render pending and drops any prior render; clearing it drops the render so the preview
     * returns to the settled non-watermarked image.
     */
    fun withWatermark(state: PassportReviewState, text: String?): PassportReviewState {
        val normalized = text?.trim()?.takeIf { it.isNotEmpty() }
        // Setting text hands rotation to the watermark render; removing it re-arms a standalone
        // rotation bake (via invalidateRotationRender, which reads the updated watermarkText).
        return invalidateRotationRender(
            state.copy(
                watermarkText = normalized,
                watermarkRenderedUri = null,
                watermarkRenderPending = normalized != null,
                // Setting new text (or removing it) starts a clean attempt / clears the error.
                watermarkError = false
            )
        )
    }

    /**
     * Publishes the authoritative watermark render — the exact image the review displays and the
     * save persists. Accepted only if the watermark text still matches [forText] and its input
     * still matches [fromFilteredUri]/[atRotation] (the settled+rotated image it was drawn on),
     * so a stale render from a superseded filter/rotation/text can never overwrite newer state.
     */
    fun withWatermarkRendered(
        state: PassportReviewState,
        forText: String,
        fromFilteredUri: String,
        atRotation: Int,
        renderedUri: String
    ): PassportReviewState {
        if (state.watermarkText != forText) return state
        if (state.filteredUri != fromFilteredUri) return state
        if (state.requestedRotationDegrees != atRotation) return state
        return state.copy(
            watermarkRenderedUri = renderedUri,
            watermarkRenderPending = false,
            watermarkError = false
        )
    }

    /**
     * Records a FAILED watermark render as an EXPLICIT, recoverable error: the pending flag
     * clears (nothing is in flight) but [watermarkText] is kept and [watermarkError] is set, so
     * the preview stays on the last truthful un-watermarked page while the UI can offer Retry /
     * Remove. Crucially [watermarkUnresolved] stays true, so Confirm is disabled and the page can
     * never be silently saved without the watermark the toolbar still advertises as active.
     */
    fun withWatermarkFailed(state: PassportReviewState, forText: String): PassportReviewState {
        if (state.watermarkText != forText) return state
        return state.copy(watermarkRenderPending = false, watermarkError = true)
    }

    /**
     * Acquires the single-flight save guard. Returns null unless [PassportReviewState.canConfirm]
     * holds — i.e. no save is already running, no filter/watermark render is in flight, and no
     * requested watermark is unresolved (pending or failed). Confirm therefore persists exactly
     * the settled visible pixels, never an un-watermarked page while a watermark is advertised.
     */
    fun beginSave(state: PassportReviewState): PassportReviewState? =
        if (state.canConfirm) state.copy(saveInProgress = true) else null

    /** Releases the save guard after a FAILED save so the user can retry from the review. */
    fun saveFailed(state: PassportReviewState): PassportReviewState =
        state.copy(saveInProgress = false)
}
