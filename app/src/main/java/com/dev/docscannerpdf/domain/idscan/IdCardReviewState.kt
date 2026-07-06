package com.dev.docscannerpdf.domain.idscan

/** Which captured side the ID-card review screen's crop/rotate controls currently target. */
enum class IdCardReviewSide { FRONT, BACK }

/**
 * Pure state for the CamScanner-style ID-card review step shown after guided capture and before
 * the final Document Ready preview. Each side tracks its own rotation independently — rotating
 * one side must never rotate the other, and neither rotates unless the user taps the rotate
 * control. Uris are plain strings (not [android.net.Uri]) so this model stays framework-free and
 * unit-testable, matching [IdCardCaptureState]'s convention; the review screen converts to/from
 * [android.net.Uri] at its edges.
 */
data class IdCardReviewState(
    val frontImageUri: String,
    val backImageUri: String? = null,
    val frontRotationDegrees: Int = 0,
    val backRotationDegrees: Int = 0,
    val selectedSide: IdCardReviewSide = IdCardReviewSide.FRONT
) {
    fun imageUri(side: IdCardReviewSide): String? = when (side) {
        IdCardReviewSide.FRONT -> frontImageUri
        IdCardReviewSide.BACK -> backImageUri
    }

    fun rotationDegrees(side: IdCardReviewSide): Int = when (side) {
        IdCardReviewSide.FRONT -> frontRotationDegrees
        IdCardReviewSide.BACK -> backRotationDegrees
    }
}

/**
 * Pure reducer for [IdCardReviewState] transitions: selecting which side crop/rotate applies to,
 * rotating only the selected side, and swapping in a side's image after a crop or re-enhance.
 * Kept free of Android/CameraX types so these transitions are unit testable directly.
 */
object IdCardReviewFlow {

    /** Selects [side] as the crop/rotate target. Selecting BACK when there is no back is a no-op. */
    fun selectSide(state: IdCardReviewState, side: IdCardReviewSide): IdCardReviewState =
        if (side == IdCardReviewSide.BACK && state.backImageUri == null) {
            state
        } else {
            state.copy(selectedSide = side)
        }

    /** Rotates only [IdCardReviewState.selectedSide] by 90°, wrapping at 360; the other side is untouched. */
    fun rotateSelected(state: IdCardReviewState): IdCardReviewState = when (state.selectedSide) {
        IdCardReviewSide.FRONT -> state.copy(frontRotationDegrees = (state.frontRotationDegrees + 90) % 360)
        IdCardReviewSide.BACK -> state.copy(backRotationDegrees = (state.backRotationDegrees + 90) % 360)
    }

    /** Replaces [side]'s image (e.g. after a crop or a manual re-enhance), leaving the other side and both rotations untouched. */
    fun withUpdatedImage(state: IdCardReviewState, side: IdCardReviewSide, imageUri: String): IdCardReviewState =
        when (side) {
            IdCardReviewSide.FRONT -> state.copy(frontImageUri = imageUri)
            IdCardReviewSide.BACK -> state.copy(backImageUri = imageUri)
        }
}
