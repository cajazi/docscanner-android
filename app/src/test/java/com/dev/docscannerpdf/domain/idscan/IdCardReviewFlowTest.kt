package com.dev.docscannerpdf.domain.idscan

import com.dev.docscannerpdf.domain.filter.DocumentFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class IdCardReviewFlowTest {

    private fun frontOnly() = IdCardReviewState(frontBaseImageUri = "file://base/front.jpg")

    private fun frontAndBack() = IdCardReviewState(
        frontBaseImageUri = "file://base/front.jpg",
        backBaseImageUri = "file://base/back.jpg"
    )

    // --- selection & rotation invariants (regression: tap selects, only Rotate rotates) ---

    @Test
    fun startsSelectedOnFrontWithNoRotation() {
        val state = frontAndBack()

        assertEquals(IdCardReviewSide.FRONT, state.selectedSide)
        assertEquals(0, state.frontRotationDegrees)
        assertEquals(0, state.backRotationDegrees)
    }

    @Test
    fun selectingBackChangesSelectedSideAndNothingElse() {
        val state = frontAndBack()

        val selected = IdCardReviewFlow.selectSide(state, IdCardReviewSide.BACK)

        assertEquals(state.copy(selectedSide = IdCardReviewSide.BACK), selected)
        assertEquals(0, selected.frontRotationDegrees)
        assertEquals(0, selected.backRotationDegrees)
    }

    @Test
    fun selectingBackWhenNoneCapturedIsNoOp() {
        val state = frontOnly()

        assertEquals(state, IdCardReviewFlow.selectSide(state, IdCardReviewSide.BACK))
    }

    @Test
    fun rotateSelectedOnlyChangesSelectedSide() {
        val rotated = IdCardReviewFlow.rotateSelected(frontAndBack())

        assertEquals(90, rotated.frontRotationDegrees)
        assertEquals(0, rotated.backRotationDegrees)

        val backSelected = IdCardReviewFlow.selectSide(frontAndBack(), IdCardReviewSide.BACK)
        val backRotated = IdCardReviewFlow.rotateSelected(backSelected)
        assertEquals(0, backRotated.frontRotationDegrees)
        assertEquals(90, backRotated.backRotationDegrees)
    }

    @Test
    fun rotationWrapsAt360() {
        var state = frontAndBack()
        repeat(4) { state = IdCardReviewFlow.rotateSelected(state) }

        assertEquals(0, state.frontRotationDegrees)
    }

    // --- filter defaults ---

    @Test
    fun bothSidesDefaultToEnhanceWithNoRenderedOutputYet() {
        val state = frontAndBack()

        assertEquals(DocumentFilter.ENHANCE, state.frontFilter)
        assertEquals(DocumentFilter.ENHANCE, state.backFilter)
        assertNull(state.frontRenderedImageUri)
        assertNull(state.backRenderedImageUri)
        // Until the render publishes, the display falls back to the base.
        assertEquals("file://base/front.jpg", state.displayImageUri(IdCardReviewSide.FRONT))
        assertEquals("file://base/back.jpg", state.displayImageUri(IdCardReviewSide.BACK))
    }

    // --- applyFilter ---

    @Test
    fun applyFilterAffectsOnlySelectedSide() {
        val state = frontAndBack()

        val filtered = IdCardReviewFlow.applyFilter(state, DocumentFilter.SEPIA)

        assertEquals(DocumentFilter.SEPIA, filtered.frontFilter)
        assertEquals(DocumentFilter.ENHANCE, filtered.backFilter)
        assertEquals(state.backBaseImageUri, filtered.backBaseImageUri)
        assertEquals(state.backRenderedImageUri, filtered.backRenderedImageUri)
    }

    @Test
    fun frontAndBackSupportDifferentFilters() {
        var state = IdCardReviewFlow.applyFilter(frontAndBack(), DocumentFilter.BW)
        state = IdCardReviewFlow.selectSide(state, IdCardReviewSide.BACK)
        state = IdCardReviewFlow.applyFilter(state, DocumentFilter.WARM)

        assertEquals(DocumentFilter.BW, state.frontFilter)
        assertEquals(DocumentFilter.WARM, state.backFilter)
    }

    @Test
    fun applyFilterPreservesRotationCropAndTitle() {
        val state = frontAndBack().copy(
            frontRotationDegrees = 90,
            backRotationDegrees = 180,
            title = "My ID"
        )

        val filtered = IdCardReviewFlow.applyFilter(state, DocumentFilter.COOL)

        assertEquals(90, filtered.frontRotationDegrees)
        assertEquals(180, filtered.backRotationDegrees)
        assertEquals("My ID", filtered.title)
        assertEquals(state.frontBaseImageUri, filtered.frontBaseImageUri)
        assertEquals(state.backBaseImageUri, filtered.backBaseImageUri)
    }

    @Test
    fun originalRestoresOnlySelectedSideToBase() {
        var state = frontAndBack().copy(
            frontRenderedImageUri = "file://rendered/front-enhance.jpg",
            backRenderedImageUri = "file://rendered/back-enhance.jpg"
        )
        state = IdCardReviewFlow.selectSide(state, IdCardReviewSide.BACK)

        val reset = IdCardReviewFlow.applyFilter(state, DocumentFilter.ORIGINAL)

        assertEquals(DocumentFilter.ORIGINAL, reset.backFilter)
        assertNull(reset.backRenderedImageUri)
        assertEquals("file://base/back.jpg", reset.displayImageUri(IdCardReviewSide.BACK))
        // Front untouched: keeps its filter and rendered output.
        assertEquals(DocumentFilter.ENHANCE, reset.frontFilter)
        assertEquals("file://rendered/front-enhance.jpg", reset.frontRenderedImageUri)
    }

    @Test
    fun originalPreservesCropAndRotation() {
        val state = frontAndBack().copy(
            frontBaseImageUri = "file://base/front-cropped.jpg",
            frontRotationDegrees = 270,
            frontRenderedImageUri = "file://rendered/front.jpg"
        )

        val reset = IdCardReviewFlow.applyFilter(state, DocumentFilter.ORIGINAL)

        assertEquals("file://base/front-cropped.jpg", reset.frontBaseImageUri)
        assertEquals(270, reset.frontRotationDegrees)
        assertNull(reset.frontRenderedImageUri)
    }

    @Test
    fun reapplyingSettledFilterIsNoOp() {
        val settled = frontAndBack().copy(frontRenderedImageUri = "file://rendered/front-enhance.jpg")

        // Same instance back means no re-render is triggered and nothing can compound.
        assertSame(settled, IdCardReviewFlow.applyFilter(settled, DocumentFilter.ENHANCE))

        val original = IdCardReviewFlow.applyFilter(settled, DocumentFilter.ORIGINAL)
        assertSame(original, IdCardReviewFlow.applyFilter(original, DocumentFilter.ORIGINAL))
    }

    // --- withRenderedFilter (pure half of stale-result rejection) ---

    @Test
    fun matchingRenderPublicationIsAccepted() {
        val state = frontAndBack()

        val published = IdCardReviewFlow.withRenderedFilter(
            state = state,
            side = IdCardReviewSide.FRONT,
            filter = DocumentFilter.ENHANCE,
            baseImageUri = "file://base/front.jpg",
            renderedImageUri = "file://rendered/front-enhance.jpg"
        )

        assertEquals("file://rendered/front-enhance.jpg", published.frontRenderedImageUri)
        assertEquals("file://rendered/front-enhance.jpg", published.displayImageUri(IdCardReviewSide.FRONT))
    }

    @Test
    fun staleFilterPublicationIsRejected() {
        // The render was for SEPIA, but the user has since switched the front to BW.
        val state = IdCardReviewFlow.applyFilter(frontAndBack(), DocumentFilter.BW)

        val published = IdCardReviewFlow.withRenderedFilter(
            state = state,
            side = IdCardReviewSide.FRONT,
            filter = DocumentFilter.SEPIA,
            baseImageUri = "file://base/front.jpg",
            renderedImageUri = "file://rendered/front-sepia.jpg"
        )

        assertEquals(state, published)
        assertNull(published.frontRenderedImageUri)
    }

    @Test
    fun staleBasePublicationIsRejectedAfterCrop() {
        // The render started from the pre-crop base; the crop landed first.
        val cropped = IdCardReviewFlow.withCroppedBase(
            frontAndBack(), IdCardReviewSide.FRONT, "file://base/front-cropped.jpg"
        )

        val published = IdCardReviewFlow.withRenderedFilter(
            state = cropped,
            side = IdCardReviewSide.FRONT,
            filter = DocumentFilter.ENHANCE,
            baseImageUri = "file://base/front.jpg",
            renderedImageUri = "file://rendered/front-old-base.jpg"
        )

        assertEquals(cropped, published)
        assertNull(published.frontRenderedImageUri)
    }

    @Test
    fun originalPublicationIsAlwaysRejected() {
        val state = IdCardReviewFlow.applyFilter(frontAndBack(), DocumentFilter.ORIGINAL)

        val published = IdCardReviewFlow.withRenderedFilter(
            state = state,
            side = IdCardReviewSide.FRONT,
            filter = DocumentFilter.ORIGINAL,
            baseImageUri = "file://base/front.jpg",
            renderedImageUri = "file://rendered/should-not-exist.jpg"
        )

        assertEquals(state, published)
    }

    // --- withCroppedBase ---

    @Test
    fun croppedBasePreservesFilterAndRotationAndClearsStaleRender() {
        val state = frontAndBack().copy(
            frontFilter = DocumentFilter.GRAY,
            frontRenderedImageUri = "file://rendered/front-gray.jpg",
            frontRotationDegrees = 90
        )

        val cropped = IdCardReviewFlow.withCroppedBase(
            state, IdCardReviewSide.FRONT, "file://base/front-cropped.jpg"
        )

        assertEquals("file://base/front-cropped.jpg", cropped.frontBaseImageUri)
        assertEquals(DocumentFilter.GRAY, cropped.frontFilter)
        assertNull(cropped.frontRenderedImageUri)
        assertEquals(90, cropped.frontRotationDegrees)
        // Back untouched.
        assertEquals(state.backBaseImageUri, cropped.backBaseImageUri)
        assertEquals(state.backFilter, cropped.backFilter)
        assertEquals(state.backRenderedImageUri, cropped.backRenderedImageUri)
    }

    @Test
    fun croppingMissingBackIsNoOp() {
        val state = frontOnly()

        assertEquals(
            state,
            IdCardReviewFlow.withCroppedBase(state, IdCardReviewSide.BACK, "file://base/back.jpg")
        )
    }

    // --- title ---

    @Test
    fun renameTitleTrimsAndRejectsBlank() {
        val state = frontOnly()

        assertEquals("My Card", IdCardReviewFlow.renameTitle(state, "  My Card  ").title)
        assertEquals(state.title, IdCardReviewFlow.renameTitle(state, "   ").title)
    }

    @Test
    fun defaultTitleIsIdCard() {
        assertEquals("ID Card", frontOnly().title)
    }

    @Test
    fun fullEditingSessionPreservesBasesRotationsFiltersAndTitle() {
        // Select, rotate, filter, rename — the bases the save flow reads must never change.
        var state = frontAndBack()
        state = IdCardReviewFlow.selectSide(state, IdCardReviewSide.BACK)
        state = IdCardReviewFlow.rotateSelected(state)
        state = IdCardReviewFlow.applyFilter(state, DocumentFilter.SEPIA)
        state = IdCardReviewFlow.selectSide(state, IdCardReviewSide.FRONT)
        state = IdCardReviewFlow.rotateSelected(state)
        state = IdCardReviewFlow.applyFilter(state, DocumentFilter.BW)
        state = IdCardReviewFlow.renameTitle(state, "Renamed Card")

        assertEquals("file://base/front.jpg", state.frontBaseImageUri)
        assertEquals("file://base/back.jpg", state.backBaseImageUri)
        assertEquals(90, state.frontRotationDegrees)
        assertEquals(90, state.backRotationDegrees)
        assertEquals(DocumentFilter.BW, state.frontFilter)
        assertEquals(DocumentFilter.SEPIA, state.backFilter)
        assertEquals("Renamed Card", state.title)
    }
}
