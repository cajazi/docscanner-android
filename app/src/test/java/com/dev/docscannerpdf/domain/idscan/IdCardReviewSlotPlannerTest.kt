package com.dev.docscannerpdf.domain.idscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdCardReviewSlotPlannerTest {

    private val pageWidth = 1000f
    private val pageHeight = 1600f

    @Test
    fun frontAndBackReceiveExactlyEqualOuterAllocation() {
        val slots = IdCardReviewSlotPlanner.plan(2, pageWidth, pageHeight)

        assertEquals(2, slots.size)
        val (front, back) = slots
        assertEquals(front.width, back.width, 0f)
        assertEquals(front.height, back.height, 0f)
        assertEquals(front.left, back.left, 0f)
    }

    @Test
    fun slotsDependOnlyOnCanvasNeverOnImageDimensions() {
        // The planner takes no image dimensions at all — a portrait, panoramic, or square
        // capture can never change either side's allocation. (Compile-time guarantee made
        // explicit: plan() has no image parameters.)
        val a = IdCardReviewSlotPlanner.plan(2, pageWidth, pageHeight)
        val b = IdCardReviewSlotPlanner.plan(2, pageWidth, pageHeight)

        assertEquals(a, b)
    }

    @Test
    fun slotsAreCenteredHorizontallyAndInsideTheCanvas() {
        IdCardReviewSlotPlanner.plan(2, pageWidth, pageHeight).forEach { slot ->
            val center = slot.left + slot.width / 2f
            assertEquals(pageWidth / 2f, center, 0.01f)
            assertTrue(slot.left >= 0f)
            assertTrue(slot.top >= 0f)
            assertTrue(slot.right <= pageWidth)
            assertTrue(slot.bottom <= pageHeight)
        }
    }

    @Test
    fun frontAndBackHaveCamScannerStyleGap() {
        val (front, back) = IdCardReviewSlotPlanner.plan(2, pageWidth, pageHeight)

        val gap = back.top - front.bottom
        assertEquals(front.height * IdCardReviewSlotPlanner.GAP_TO_SLOT_HEIGHT_FRACTION, gap, 0.01f)
        // The device-QA complaint: images too large and too close. Guard both bounds.
        assertTrue("gap must be substantial", gap >= front.height * 0.5f)
        assertTrue(
            "slots must not dominate the canvas width",
            front.width <= pageWidth * IdCardReviewSlotPlanner.SLOT_WIDTH_FRACTION + 0.01f
        )
    }

    @Test
    fun frontOnlyRemainsVerticallyBalancedSlightlyAboveCenter() {
        val slot = IdCardReviewSlotPlanner.plan(1, pageWidth, pageHeight).single()

        // CamScanner bias: the leftover space splits GROUP_VERTICAL_BIAS above / rest below.
        val expectedTop = (pageHeight - slot.height) * IdCardReviewSlotPlanner.GROUP_VERTICAL_BIAS
        assertEquals(expectedTop, slot.top, 0.01f)
        val verticalCenter = slot.top + slot.height / 2f
        assertTrue("sits above dead center", verticalCenter < pageHeight / 2f)
        assertTrue("but comfortably inside the canvas", slot.top > pageHeight * 0.1f)
    }

    @Test
    fun twoSideGroupSitsAboveCenterLikeTheReference() {
        val (front, back) = IdCardReviewSlotPlanner.plan(2, pageWidth, pageHeight)

        val groupHeight = back.bottom - front.top
        val expectedTop = (pageHeight - groupHeight) * IdCardReviewSlotPlanner.GROUP_VERTICAL_BIAS
        assertEquals(expectedTop, front.top, 0.01f)
        assertTrue(front.top > 0f)
        assertTrue(back.bottom < pageHeight)
    }

    @Test
    fun shortCanvasCapsTheGroupHeight() {
        val shortHeight = 500f
        val slots = IdCardReviewSlotPlanner.plan(2, pageWidth, shortHeight)

        val groupHeight = slots.last().bottom - slots.first().top
        assertTrue(groupHeight <= shortHeight * IdCardReviewSlotPlanner.MAX_GROUP_HEIGHT_FRACTION + 0.01f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidSideCountThrows() {
        IdCardReviewSlotPlanner.plan(3, pageWidth, pageHeight)
    }

    // --- contain-fit specification (the pure equivalent of the tile's ContentScale.Fit) ---

    @Test
    fun containedImagePreservesIntrinsicAspectRatio() {
        val slot = IdCardReviewSlotPlanner.plan(1, pageWidth, pageHeight).single()

        listOf(1600 to 1000, 1000 to 1600, 900 to 900, 3000 to 500).forEach { (w, h) ->
            val fitted = IdCardReviewSlotPlanner.containedImageSize(slot.width, slot.height, w, h)
            assertEquals(
                "aspect must be preserved for ${w}x$h",
                w.toFloat() / h,
                fitted.width / fitted.height,
                0.001f
            )
        }
    }

    @Test
    fun containedImageAlwaysFitsEntirelyInsideTheSlot() {
        val slot = IdCardReviewSlotPlanner.plan(1, pageWidth, pageHeight).single()

        listOf(1600 to 1000, 1000 to 1600, 10 to 10, 8000 to 100).forEach { (w, h) ->
            val fitted = IdCardReviewSlotPlanner.containedImageSize(slot.width, slot.height, w, h)
            assertTrue("width fits for ${w}x$h", fitted.width <= slot.width + 0.01f)
            assertTrue("height fits for ${w}x$h", fitted.height <= slot.height + 0.01f)
        }
    }

    @Test
    fun containFitLetterboxesInsteadOfCropping() {
        val slot = IdCardReviewSlotPlanner.plan(1, pageWidth, pageHeight).single()

        // A portrait image in the landscape slot: full height used, width letterboxed —
        // never the Crop behavior of filling width and cutting top/bottom.
        val portrait = IdCardReviewSlotPlanner.containedImageSize(slot.width, slot.height, 1000, 1600)
        assertEquals(slot.height, portrait.height, 0.01f)
        assertTrue(portrait.width < slot.width)

        // A wider-than-slot image: full width used, height letterboxed.
        val panoramic = IdCardReviewSlotPlanner.containedImageSize(slot.width, slot.height, 4000, 1000)
        assertEquals(slot.width, panoramic.width, 0.01f)
        assertTrue(panoramic.height < slot.height)
    }

    // --- canvas bottom clearance (dark band between canvas and bottom controls) ---

    @Test
    fun normalScreenGetsThePreferredCollapsedClearance() {
        val preferred = 130f // ~52dp at 2.5x density
        val clearance = IdCardReviewSlotPlanner.canvasBottomClearance(1600f, preferred)

        assertEquals(preferred, clearance, 0f)
        assertTrue("clearance is positive", clearance > 0f)
    }

    @Test
    fun shortScreenClearanceShrinksButStaysPositiveAndCanvasStaysPositive() {
        val shortHeights = listOf(400f, 200f, 80f, 10f)
        shortHeights.forEach { available ->
            val clearance = IdCardReviewSlotPlanner.canvasBottomClearance(available, 130f)
            assertTrue("clearance positive for $available", clearance > 0f)
            assertTrue(
                "clearance capped for $available",
                clearance <= available * IdCardReviewSlotPlanner.MAX_BOTTOM_CLEARANCE_FRACTION + 0.001f
            )
            assertTrue("canvas height stays positive for $available", available - clearance > 0f)
        }
    }

    @Test
    fun slotsRemainInsideTheClearanceShortenedCanvas() {
        val available = 900f
        val clearance = IdCardReviewSlotPlanner.canvasBottomClearance(available, 130f)
        val canvasHeight = available - clearance

        IdCardReviewSlotPlanner.plan(2, pageWidth, canvasHeight).forEach { slot ->
            assertTrue(slot.top >= 0f)
            assertTrue(slot.bottom <= canvasHeight + 0.01f)
        }
    }

    @Test
    fun twoSideGroupRemainsContainedWithTheAcceptedGeometryConstants() {
        // Locks the accepted device-tested values together: 0.40 / 0.66 / 0.38.
        assertEquals(0.40f, IdCardReviewSlotPlanner.SLOT_WIDTH_FRACTION, 0f)
        assertEquals(0.66f, IdCardReviewSlotPlanner.GAP_TO_SLOT_HEIGHT_FRACTION, 0f)
        assertEquals(0.38f, IdCardReviewSlotPlanner.GROUP_VERTICAL_BIAS, 0f)

        val slots = IdCardReviewSlotPlanner.plan(2, pageWidth, pageHeight)
        assertTrue(slots.first().top >= 0f)
        assertTrue(slots.last().bottom <= pageHeight)
    }

    // --- rotation-aware inner container (keeps 90°/270° rotations inside the slot) ---

    @Test
    fun zeroAnd180DegreesUseSlotDimensionsUnchanged() {
        listOf(0, 180).forEach { degrees ->
            val inner = IdCardReviewSlotPlanner.rotationAwareContainerSize(800f, 500f, degrees)
            assertEquals("width at $degrees°", 800f, inner.width, 0f)
            assertEquals("height at $degrees°", 500f, inner.height, 0f)
        }
    }

    @Test
    fun ninetyAnd270DegreesSwapSlotDimensions() {
        listOf(90, 270).forEach { degrees ->
            val inner = IdCardReviewSlotPlanner.rotationAwareContainerSize(800f, 500f, degrees)
            assertEquals("width at $degrees°", 500f, inner.width, 0f)
            assertEquals("height at $degrees°", 800f, inner.height, 0f)
        }
    }

    @Test
    fun negativeNinetyBehavesLike270() {
        val negative = IdCardReviewSlotPlanner.rotationAwareContainerSize(800f, 500f, -90)
        val canonical = IdCardReviewSlotPlanner.rotationAwareContainerSize(800f, 500f, 270)

        assertEquals(canonical, negative)
    }

    @Test
    fun fourHundredFiftyBehavesLike90() {
        val wrapped = IdCardReviewSlotPlanner.rotationAwareContainerSize(800f, 500f, 450)
        val canonical = IdCardReviewSlotPlanner.rotationAwareContainerSize(800f, 500f, 90)

        assertEquals(canonical, wrapped)
    }

    @Test
    fun rotatedContainerBoundsStayInsideTheOuterSlot() {
        val (front, back) = IdCardReviewSlotPlanner.plan(2, pageWidth, pageHeight)

        listOf(0, 90, 180, 270, -90, 450, 360, -270).forEach { degrees ->
            val inner = IdCardReviewSlotPlanner.rotationAwareContainerSize(front.width, front.height, degrees)
            // Center-rotating the inner container by a quarter turn swaps its on-screen
            // footprint back to (height x width); for 0/180 the footprint is (width x height).
            val normalized = ((degrees % 360) + 360) % 360
            val footprintWidth = if (normalized == 90 || normalized == 270) inner.height else inner.width
            val footprintHeight = if (normalized == 90 || normalized == 270) inner.width else inner.height
            assertTrue("footprint width inside slot at $degrees°", footprintWidth <= front.width + 0.01f)
            assertTrue("footprint height inside slot at $degrees°", footprintHeight <= front.height + 0.01f)
        }

        // Rotation never affects the equal outer allocation.
        assertEquals(front.width, back.width, 0f)
        assertEquals(front.height, back.height, 0f)
    }
}
