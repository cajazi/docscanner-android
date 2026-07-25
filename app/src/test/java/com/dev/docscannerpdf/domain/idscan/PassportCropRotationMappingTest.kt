package com.dev.docscannerpdf.domain.idscan

import com.dev.docscannerpdf.domain.filter.DocumentFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the crop editor's rotation awareness: the editor shows the image the review is showing
 * ([PassportReviewState.displayedUri], whose rotation is BAKED into its pixels), so a rectangle
 * dragged there is expressed in a rotated frame and must be converted back to canonical base
 * coordinates before the authoritative crop is extracted from [PassportReviewState.baseUri].
 */
class PassportCropRotationMappingTest {

    private val tolerance = 1e-5f

    private fun assertRect(expected: PassportCropRect, actual: PassportCropRect) {
        assertEquals(expected.left, actual.left, tolerance)
        assertEquals(expected.top, actual.top, tolerance)
        assertEquals(expected.right, actual.right, tolerance)
        assertEquals(expected.bottom, actual.bottom, tolerance)
    }

    // ---------------------------------------------------------------- the conversion itself

    @Test
    fun `an unrotated page needs no conversion`() {
        val rect = PassportCropRect(0.2f, 0.1f, 0.7f, 0.6f)

        assertTrue(PassportCropRotationMapping.toBaseRect(rect, 0) === rect)
    }

    @Test
    fun `the full frame maps to the full frame at every angle`() {
        for (turns in 0..3) {
            assertRect(
                PassportCropRect.FULL,
                PassportCropRotationMapping.toBaseRect(PassportCropRect.FULL, turns)
            )
        }
    }

    @Test
    fun `a rectangle drawn on a 90 degree page maps to the base frame`() {
        // Top-left quadrant as SEEN on a page rotated 90° CW comes from the base's bottom-left.
        val displayed = PassportCropRect(0f, 0f, 0.5f, 0.5f)

        val base = PassportCropRotationMapping.toBaseRect(displayed, 1)

        assertRect(PassportCropRect(0f, 0.5f, 0.5f, 1f), base)
    }

    @Test
    fun `a rectangle drawn on a 180 degree page mirrors both axes`() {
        val displayed = PassportCropRect(0.1f, 0.2f, 0.4f, 0.5f)

        val base = PassportCropRotationMapping.toBaseRect(displayed, 2)

        assertRect(PassportCropRect(0.6f, 0.5f, 0.9f, 0.8f), base)
    }

    @Test
    fun `a rectangle drawn on a 270 degree page maps to the base frame`() {
        // Top-left quadrant as SEEN on a page rotated 270° CW comes from the base's top-right.
        val displayed = PassportCropRect(0f, 0f, 0.5f, 0.5f)

        val base = PassportCropRotationMapping.toBaseRect(displayed, 3)

        assertRect(PassportCropRect(0.5f, 0f, 1f, 0.5f), base)
    }

    @Test
    fun `the conversion round trips at every angle`() {
        val rect = PassportCropRect(0.15f, 0.25f, 0.65f, 0.95f)
        for (turns in 0..3) {
            val base = PassportCropRotationMapping.toBaseRect(rect, turns)
            assertRect(rect, PassportCropRotationMapping.toDisplayedRect(base, turns))
        }
    }

    @Test
    fun `negative and wrapped quarter turns normalize`() {
        val rect = PassportCropRect(0.15f, 0.25f, 0.65f, 0.95f)

        assertRect(
            PassportCropRotationMapping.toBaseRect(rect, 1),
            PassportCropRotationMapping.toBaseRect(rect, -3)
        )
        assertRect(
            PassportCropRotationMapping.toBaseRect(rect, 3),
            PassportCropRotationMapping.toBaseRect(rect, 7)
        )
    }

    // ---------------------------------------------------------------- invariants are preserved

    @Test
    fun `converted rectangles stay in bounds ordered and above the minimum size`() {
        val rect = PassportCropRect(0.05f, 0.42f, 0.95f, 0.58f)
        for (turns in 0..3) {
            val base = PassportCropRotationMapping.toBaseRect(rect, turns)
            assertTrue("left < right at $turns turns", base.left < base.right)
            assertTrue("top < bottom at $turns turns", base.top < base.bottom)
            assertTrue("in bounds at $turns turns", base.left >= 0f && base.right <= 1f)
            assertTrue("in bounds at $turns turns", base.top >= 0f && base.bottom <= 1f)
            // An odd quarter turn simply SWAPS width and height — neither can fall below MIN_SIZE.
            assertTrue(
                "minimum size survives $turns turns",
                base.width >= PassportCropReducer.MIN_SIZE - tolerance &&
                    base.height >= PassportCropReducer.MIN_SIZE - tolerance
            )
        }
    }

    @Test
    fun `a meaningful crop stays meaningful and a no-op stays a no-op after conversion`() {
        val meaningful = PassportCropRect(0.2f, 0.2f, 0.8f, 0.8f)
        for (turns in 0..3) {
            assertTrue(
                PassportCropReducer.isMeaningfulCrop(
                    PassportCropRotationMapping.toBaseRect(meaningful, turns)
                )
            )
            assertTrue(
                !PassportCropReducer.isMeaningfulCrop(
                    PassportCropRotationMapping.toBaseRect(PassportCropRect.FULL, turns)
                )
            )
        }
    }

    // -------------------------------------------------- the angle the editor must convert by

    @Test
    fun `an unrotated review displays zero degrees`() {
        val state = PassportReviewState(baseUri = "file:///files/base.jpg")

        assertEquals(state.filteredUri, state.displayedUri)
        assertEquals(0, state.displayedRotationDegrees)
    }

    @Test
    fun `a filtered page is still displayed upright`() {
        val state = PassportReviewState(baseUri = "file:///files/base.jpg").copy(
            filteredUri = "file:///files/filtered.jpg",
            selectedFilter = DocumentFilter.ENHANCE,
            renderedFilter = DocumentFilter.ENHANCE
        )

        // Filters change tone, never geometry — the crop editor shows it, and converts by 0.
        assertEquals("file:///files/filtered.jpg", state.displayedUri)
        assertEquals(0, state.displayedRotationDegrees)
    }

    @Test
    fun `a settled rotation reports the angle actually baked into the displayed pixels`() {
        val state = PassportReviewState(baseUri = "file:///files/base.jpg").copy(
            requestedRotationDegrees = 90,
            settledRotationDegrees = 90,
            rotationRenderedUri = "file:///files/r90.jpg"
        )

        assertEquals("file:///files/r90.jpg", state.displayedUri)
        assertEquals(90, state.displayedRotationDegrees)
    }

    @Test
    fun `a rotation still baking reports the previously settled angle`() {
        val settled = PassportReviewState(baseUri = "file:///files/base.jpg").copy(
            requestedRotationDegrees = 90,
            settledRotationDegrees = 90,
            rotationRenderedUri = "file:///files/r90.jpg"
        )

        // 90° -> 180°: the 90° bake is still on screen, so the crop must convert by 90, not 180.
        val baking = PassportReviewFlow.rotate(settled)

        assertEquals(180, baking.requestedRotationDegrees)
        assertEquals("file:///files/r90.jpg", baking.displayedUri)
        assertEquals(90, baking.displayedRotationDegrees)
    }

    @Test
    fun `a watermark render reports the rotation it was baked at`() {
        val state = PassportReviewState(baseUri = "file:///files/base.jpg").copy(
            requestedRotationDegrees = 270,
            watermarkText = "COPY",
            watermarkRenderedUri = "file:///files/wm.jpg"
        )

        assertEquals("file:///files/wm.jpg", state.displayedUri)
        assertEquals(270, state.displayedRotationDegrees)
    }

    @Test
    fun `the displayed angle always matches the displayed image after a full rotation cycle`() {
        var state = PassportReviewState(baseUri = "file:///files/base.jpg")
        // Four taps, each settling its bake before the next — the editor's snapshot must track it.
        for (expected in listOf(90, 180, 270, 0)) {
            state = PassportReviewFlow.rotate(state)
            if (expected == 0) break
            state = PassportReviewFlow.withRotationRendered(
                state = state,
                fromFilteredUri = state.filteredUri,
                atRotation = expected,
                renderedUri = "file:///files/r$expected.jpg"
            )
            assertEquals(expected, state.displayedRotationDegrees)
            assertEquals("file:///files/r$expected.jpg", state.displayedUri)
        }
        // Back to upright: the bake is dropped and the filtered page is displayed at 0°.
        assertEquals(0, state.displayedRotationDegrees)
        assertEquals(state.filteredUri, state.displayedUri)
    }
}
