package com.dev.docscannerpdf.domain.idscan

import com.dev.docscannerpdf.domain.filter.DocumentFilter

/**
 * Everything the green-check save flow needs to produce one side's FINAL image, captured from
 * the review state at confirm time so later state changes can't affect an in-flight save.
 * The final image is `rotationBake(filter(baseImageUri))`: [renderedImageUri] is used when the
 * side's filter render already completed; otherwise the save flow must render [filter] from
 * [baseImageUri] itself ([requiresFilterRender]) — it must NEVER silently fall back to the
 * unfiltered base for a non-ORIGINAL filter.
 */
data class IdCardSideSavePlan(
    val side: IdCardReviewSide,
    val baseImageUri: String,
    val filter: DocumentFilter,
    val renderedImageUri: String?,
    val rotationDegrees: Int
) {
    /** True when a filter output must still be produced before this side can be finalized. */
    val requiresFilterRender: Boolean
        get() = filter != DocumentFilter.ORIGINAL && renderedImageUri == null

    /** True when a non-zero rotation must be baked into the pixels (failure aborts the save). */
    val requiresRotationBake: Boolean
        get() = rotationDegrees != 0
}

/** Outcome of planning a green-check ID-card save. */
sealed interface IdCardSavePlan {
    /**
     * A valid save: [title] becomes the document title, [front] (and [back] when captured)
     * produce the final side images, and the resulting document always has [pageCount] = 1 —
     * the single combined front/back page is the document's one visible page.
     */
    data class Ready(
        val title: String,
        val front: IdCardSideSavePlan,
        val back: IdCardSideSavePlan?
    ) : IdCardSavePlan {
        val pageCount: Int get() = 1
    }

    /** The review state cannot be saved; [reason] is user-presentable. */
    data class Invalid(val reason: String) : IdCardSavePlan
}

/**
 * Pure planning for the green-check save: validates the review state and captures an immutable
 * snapshot of everything the save needs. Kept free of Android types so the save contract
 * (front required, back optional, pageCount always 1, title from the review) is unit-testable
 * on the JVM independent of the activity's coroutine orchestration.
 */
object IdCardSavePlanner {

    fun plan(state: IdCardReviewState): IdCardSavePlan {
        if (state.frontBaseImageUri.isBlank()) {
            return IdCardSavePlan.Invalid("No front image is available to save.")
        }
        // settledRenderedImageUri: only a render produced by the side's CURRENT filter may be
        // reused at save time — while a newer filter is still rendering, the state keeps the
        // previous filter's output on display, and saving that would apply the wrong filter.
        val front = IdCardSideSavePlan(
            side = IdCardReviewSide.FRONT,
            baseImageUri = state.frontBaseImageUri,
            filter = state.frontFilter,
            renderedImageUri = state.settledRenderedImageUri(IdCardReviewSide.FRONT),
            rotationDegrees = state.frontRotationDegrees
        )
        val back = state.backBaseImageUri?.takeIf { it.isNotBlank() }?.let { backBase ->
            IdCardSideSavePlan(
                side = IdCardReviewSide.BACK,
                baseImageUri = backBase,
                filter = state.backFilter,
                renderedImageUri = state.settledRenderedImageUri(IdCardReviewSide.BACK),
                rotationDegrees = state.backRotationDegrees
            )
        }
        return IdCardSavePlan.Ready(title = state.title, front = front, back = back)
    }
}
