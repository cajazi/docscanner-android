package com.dev.docscannerpdf.domain.idscan

import com.dev.docscannerpdf.domain.filter.DocumentFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the passport review's temp-file ownership rules: which app-private JPEGs a reducer
 * transition SUPERSEDES (and may therefore be deleted), which are protected, and what a finished
 * or cancelled session must sweep. Each case mirrors a real leak the review would otherwise have,
 * because [PassportReviewFlow] is framework-free and simply DROPS a URI on these transitions.
 */
class PassportFileOwnershipTest {

    private val filesDir = "/data/user/0/com.dev.docscannerpdf/files"

    private fun uri(name: String) = "file://$filesDir/$name"

    private val base = uri("passport_guided_capture/base.jpg")

    private fun openReview() = PassportReviewState(baseUri = base)

    // ---------------------------------------------------------------- superseded: watermark

    @Test
    fun `rotating supersedes the watermark render it invalidates`() {
        val watermarked = uri("passport_watermarked/wm.jpg")
        val before = openReview().copy(
            watermarkText = "COPY",
            watermarkRenderedUri = watermarked
        )

        val after = PassportReviewFlow.rotate(before)

        // The reducer drops the render (it was drawn at the old angle) — it must be deletable.
        assertEquals(null, after.watermarkRenderedUri)
        assertEquals(setOf(watermarked), PassportFileOwnership.supersededUris(before, after))
    }

    @Test
    fun `clearing the watermark supersedes its render`() {
        val watermarked = uri("passport_watermarked/wm.jpg")
        val before = openReview().copy(watermarkText = "COPY", watermarkRenderedUri = watermarked)

        val after = PassportReviewFlow.withWatermark(before, null)

        assertEquals(setOf(watermarked), PassportFileOwnership.supersededUris(before, after))
    }

    @Test
    fun `a replacing watermark render supersedes only the previous one`() {
        val old = uri("passport_watermarked/old.jpg")
        val new = uri("passport_watermarked/new.jpg")
        val before = openReview().copy(watermarkText = "COPY", watermarkRenderedUri = old)

        val after = PassportReviewFlow.withWatermarkRendered(
            state = before,
            forText = "COPY",
            fromFilteredUri = base,
            atRotation = 0,
            renderedUri = new
        )

        assertEquals(new, after.watermarkRenderedUri)
        val superseded = PassportFileOwnership.supersededUris(before, after)
        assertEquals(setOf(old), superseded)
        assertFalse("the freshly published render is still active", new in superseded)
    }

    // ---------------------------------------------------------------- superseded: rotation

    @Test
    fun `rotating back to zero supersedes the rotation bake`() {
        val baked = uri("passport_rotated/r270.jpg")
        val before = openReview().copy(
            requestedRotationDegrees = 270,
            settledRotationDegrees = 270,
            rotationRenderedUri = baked
        )

        val after = PassportReviewFlow.rotate(before)

        assertEquals(0, after.requestedRotationDegrees)
        assertEquals(null, after.rotationRenderedUri)
        assertEquals(setOf(baked), PassportFileOwnership.supersededUris(before, after))
    }

    @Test
    fun `a rotation still baking keeps the previously settled file alive`() {
        val baked = uri("passport_rotated/r90.jpg")
        val before = openReview().copy(
            requestedRotationDegrees = 90,
            settledRotationDegrees = 90,
            rotationRenderedUri = baked
        )

        // 90° -> 180°: the settled bake stays on screen while the new angle renders.
        val after = PassportReviewFlow.rotate(before)

        assertEquals(baked, after.rotationRenderedUri)
        assertTrue(
            "the displayed image must never be deleted",
            PassportFileOwnership.supersededUris(before, after).isEmpty()
        )
    }

    // ---------------------------------------------------------------- superseded: crop / filter

    @Test
    fun `a settled crop supersedes the old base filter and watermark but keeps the rotation bake`() {
        val filtered = uri("passport_filtered/f.jpg")
        val watermarked = uri("passport_watermarked/wm.jpg")
        val baked = uri("passport_rotated/r90.jpg")
        val cropped = uri("passport_cropped/c.jpg")
        val before = openReview().copy(
            filteredUri = filtered,
            selectedFilter = DocumentFilter.ENHANCE,
            renderedFilter = DocumentFilter.ENHANCE,
            requestedRotationDegrees = 90,
            settledRotationDegrees = 90,
            rotationRenderedUri = baked,
            watermarkText = "COPY",
            watermarkRenderedUri = watermarked,
            cropRenderPending = true
        )

        val after = PassportReviewFlow.withCroppedBase(before, cropped)

        assertEquals(cropped, after.baseUri)
        assertEquals(
            setOf(base, filtered, watermarked),
            PassportFileOwnership.supersededUris(before, after)
        )
        // Retained for keep-last display until its re-bake publishes.
        assertEquals(baked, after.rotationRenderedUri)
    }

    @Test
    fun `selecting ORIGINAL supersedes the settled filter render`() {
        val filtered = uri("passport_filtered/f.jpg")
        val before = openReview().copy(
            filteredUri = filtered,
            selectedFilter = DocumentFilter.ENHANCE,
            renderedFilter = DocumentFilter.ENHANCE
        )

        val after = PassportReviewFlow.applyFilter(before, DocumentFilter.ORIGINAL)

        assertEquals(setOf(filtered), PassportFileOwnership.supersededUris(before, after))
    }

    @Test
    fun `the canonical base is never superseded by a filter change`() {
        val before = openReview()

        val after = PassportReviewFlow.applyFilter(before, DocumentFilter.ENHANCE)

        assertTrue(PassportFileOwnership.supersededUris(before, after).isEmpty())
    }

    // ---------------------------------------------------------------- protection

    @Test
    fun `a save-frozen uri is never superseded`() {
        val watermarked = uri("passport_watermarked/wm.jpg")
        val before = openReview().copy(watermarkText = "COPY", watermarkRenderedUri = watermarked)
        val after = PassportReviewFlow.rotate(before)

        val superseded = PassportFileOwnership.supersededUris(
            before = before,
            after = after,
            protectedUris = setOf(watermarked)
        )

        assertTrue("the in-progress save's pixels must survive", superseded.isEmpty())
    }

    @Test
    fun `every uri the new state still references is protected implicitly`() {
        val before = openReview()
        val after = PassportReviewFlow.rotate(before)

        assertTrue(base in PassportFileOwnership.referencedUris(after))
        assertFalse(base in PassportFileOwnership.supersededUris(before, after))
    }

    // ---------------------------------------------------------------- session sweeps

    @Test
    fun `cancelling sweeps the ledger and the final state together`() {
        val supersededFilter = uri("passport_filtered/old.jpg")
        val currentFilter = uri("passport_filtered/new.jpg")
        val ledger = setOf(base, supersededFilter, currentFilter)
        val state = openReview().copy(
            filteredUri = currentFilter,
            selectedFilter = DocumentFilter.ENHANCE,
            renderedFilter = DocumentFilter.ENHANCE
        )

        val orphans = PassportFileOwnership.sessionOrphans(ownedUris = ledger, state = state)

        assertEquals(setOf(base, supersededFilter, currentFilter), orphans)
    }

    @Test
    fun `a saved session retains only the persisted final image`() {
        val filtered = uri("passport_filtered/f.jpg")
        val watermarked = uri("passport_watermarked/wm.jpg")
        val ledger = setOf(base, filtered, watermarked)
        val state = openReview().copy(
            filteredUri = filtered,
            selectedFilter = DocumentFilter.ENHANCE,
            renderedFilter = DocumentFilter.ENHANCE,
            watermarkText = "COPY",
            watermarkRenderedUri = watermarked
        )

        val orphans = PassportFileOwnership.sessionOrphans(
            ownedUris = ledger,
            state = state,
            retainUris = setOf(state.displayedUri)
        )

        assertEquals(watermarked, state.displayedUri)
        assertEquals(setOf(base, filtered), orphans)
        assertFalse("the saved document's file must survive", watermarked in orphans)
    }

    @Test
    fun `a cleared session with an empty ledger sweeps nothing`() {
        assertTrue(
            PassportFileOwnership.sessionOrphans(ownedUris = emptySet(), state = null).isEmpty()
        )
    }

    // ---------------------------------------------------------------- deletable-path guard

    @Test
    fun `only app-private file uris are deletable`() {
        assertTrue(PassportFileOwnership.isOwnedFileUri(uri("passport_cropped/c.jpg"), filesDir))
        // The imported gallery original — copied FROM, never owned.
        assertFalse(
            PassportFileOwnership.isOwnedFileUri("content://media/external/images/media/42", filesDir)
        )
        assertFalse(PassportFileOwnership.isOwnedFileUri("file:///sdcard/DCIM/photo.jpg", filesDir))
        assertFalse(
            PassportFileOwnership.isOwnedFileUri("file://$filesDir/../../secrets.txt", filesDir)
        )
        // A sibling directory that merely shares the prefix must not match.
        assertFalse(PassportFileOwnership.isOwnedFileUri("file://${filesDir}_backup/x.jpg", filesDir))
        assertFalse(PassportFileOwnership.isOwnedFileUri(null, filesDir))
        assertFalse(PassportFileOwnership.isOwnedFileUri(uri("c.jpg"), ""))
    }
}
