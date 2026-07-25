package com.dev.docscannerpdf.domain.idscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure tests for the rectangular passport crop model and its ContentScale.Fit coordinate pipeline.
 * These cover the crop invariants that are genuinely unit-testable without a device: initial
 * frame, corner/edge/whole-frame moves, clamping, minimum size, letterbox-aware mapping, and the
 * normalized→source-pixel conversion Apply uses.
 */
class PassportCropGeometryTest {

    // --- reducer: initial frame, moves, clamping, minimum size ---

    @Test
    fun initialCropIsTheFullImageRectangle() {
        assertEquals(PassportCropRect.FULL, PassportCropReducer.reset())
        val full = PassportCropRect.FULL
        assertTrue(full.left == 0f && full.top == 0f && full.right == 1f && full.bottom == 1f)
    }

    @Test
    fun cornerDragChangesNormalizedBounds() {
        val moved = PassportCropReducer.moveHandle(PassportCropRect.FULL, PassportCropHandle.TOP_LEFT, 0.2f, 0.3f)
        assertEquals(0.2f, moved.left, 1e-4f)
        assertEquals(0.3f, moved.top, 1e-4f)
        assertEquals(1f, moved.right, 1e-4f)
        assertEquals(1f, moved.bottom, 1e-4f)
    }

    @Test
    fun edgeDragChangesOnlyItsOwnAxis() {
        val moved = PassportCropReducer.moveHandle(PassportCropRect.FULL, PassportCropHandle.LEFT, 0.25f, 0.9f)
        assertEquals("left edge moves", 0.25f, moved.left, 1e-4f)
        assertEquals("top unaffected by a LEFT edge drag", 0f, moved.top, 1e-4f)
        assertEquals(1f, moved.bottom, 1e-4f)
    }

    @Test
    fun wholeFrameDragPreservesSize() {
        val start = PassportCropRect(0.1f, 0.1f, 0.5f, 0.5f)
        val moved = PassportCropReducer.moveBy(start, 0.2f, 0.1f)
        assertEquals(start.width, moved.width, 1e-4f)
        assertEquals(start.height, moved.height, 1e-4f)
        assertEquals(0.3f, moved.left, 1e-4f)
        assertEquals(0.2f, moved.top, 1e-4f)
    }

    @Test
    fun theCropRectangleCanNeverLeaveTheImage() {
        // Corner dragged far past the edges is clamped into 0..1.
        val corner = PassportCropReducer.moveHandle(PassportCropRect.FULL, PassportCropHandle.TOP_LEFT, -5f, -5f)
        assertTrue(corner.left >= 0f && corner.top >= 0f)

        // Whole-frame dragged past the edge is clamped so it stays fully inside.
        val start = PassportCropRect(0.6f, 0.6f, 0.9f, 0.9f)
        val shoved = PassportCropReducer.moveBy(start, 0.9f, 0.9f)
        assertTrue(shoved.right <= 1f && shoved.bottom <= 1f)
        assertEquals("size preserved while clamped", start.width, shoved.width, 1e-4f)
    }

    @Test
    fun minimumCropSizeIsEnforced() {
        // Dragging the right edge past the left cannot collapse the crop below MIN_SIZE.
        val squeezed = PassportCropReducer.moveHandle(PassportCropRect.FULL, PassportCropHandle.RIGHT, 0f, 0.5f)
        assertTrue("width stays at least MIN_SIZE", squeezed.width >= PassportCropReducer.MIN_SIZE - 1e-4f)

        val squeezedBottom = PassportCropReducer.moveHandle(PassportCropRect.FULL, PassportCropHandle.BOTTOM, 0.5f, 0f)
        assertTrue(squeezedBottom.height >= PassportCropReducer.MIN_SIZE - 1e-4f)
    }

    @Test
    fun aFullFrameCropIsNotMeaningfulButAnInsetOneIs() {
        assertFalse(PassportCropReducer.isMeaningfulCrop(PassportCropRect.FULL))
        assertTrue(PassportCropReducer.isMeaningfulCrop(PassportCropRect(0.1f, 0.1f, 0.9f, 0.9f)))
    }

    @Test
    fun resetRestoresTheFullImageRectangle() {
        assertEquals(PassportCropRect.FULL, PassportCropReducer.reset())
    }

    // --- mapping: ContentScale.Fit letterbox + normalized <-> pixels ---

    @Test
    fun fitRectLetterboxesAWideImageInATallContainerVertically() {
        // 2000x1000 image (2:1) in a 1000x1000 container → full width, half height, centred.
        val disp = PassportCropMapping.fitRect(1000f, 1000f, 2000, 1000)
        assertEquals(0f, disp.left, 1e-3f)
        assertEquals(1000f, disp.width, 1e-3f)
        assertEquals(500f, disp.height, 1e-3f)
        assertEquals("vertically centred", 250f, disp.top, 1e-3f)
    }

    @Test
    fun aTouchInTheLetterboxClampsToTheImageEdgeNotOutside() {
        val disp = PassportCropMapping.fitRect(1000f, 1000f, 2000, 1000) // image spans y=250..750
        // A touch at y=0 (inside the top letterbox) must clamp to the image's top edge (ny=0).
        val (_, ny) = PassportCropMapping.toNormalized(500f, 0f, disp)
        assertEquals(0f, ny, 1e-4f)
        // A touch below the image clamps to the bottom edge (ny=1).
        val (_, nyBottom) = PassportCropMapping.toNormalized(500f, 1000f, disp)
        assertEquals(1f, nyBottom, 1e-4f)
    }

    @Test
    fun normalizedMapsToTheCorrectSourcePixels() {
        val crop = PassportCropRect(0.25f, 0.5f, 0.75f, 1.0f)
        val px = PassportCropMapping.toSourcePixels(crop, 2000, 1000)
        assertEquals(500, px.left)
        assertEquals(500, px.top)
        assertEquals(1000, px.width)  // (0.75-0.25)*2000
        assertEquals(500, px.height)  // (1.0-0.5)*1000
    }

    @Test
    fun aMeaningfulCropProducesSmallerNonEmptySourceDimensions() {
        val crop = PassportCropRect(0.2f, 0.2f, 0.6f, 0.6f)
        val px = PassportCropMapping.toSourcePixels(crop, 1000, 1000)
        assertTrue("smaller than the source", px.width < 1000 && px.height < 1000)
        assertTrue("non-empty", px.width >= 1 && px.height >= 1)
    }

    @Test
    fun sourcePixelsAreAlwaysAtLeastOnePixelAndClampedInside() {
        // A degenerate near-zero crop still yields a valid, in-bounds, >=1px region.
        val crop = PassportCropRect(0.999f, 0.999f, 1.0f, 1.0f)
        val px = PassportCropMapping.toSourcePixels(crop, 100, 100)
        assertTrue(px.width >= 1 && px.height >= 1)
        assertTrue(px.left + px.width <= 100 && px.top + px.height <= 100)
    }
}
