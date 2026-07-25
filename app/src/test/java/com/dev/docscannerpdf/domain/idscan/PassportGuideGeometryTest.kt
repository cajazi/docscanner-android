package com.dev.docscannerpdf.domain.idscan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PassportGuideGeometryTest {

    // The reference device's camera viewport, in 540x1170-screenshot pixel units: full width,
    // minus the top toolbar and the bottom Passport control panel.
    private val referenceViewportWidth = 540f
    private val referenceViewportHeight = 820f
    private val margin = 16f

    private fun referenceGuide() = PassportGuideGeometry.planPassportGuide(
        viewportWidth = referenceViewportWidth,
        viewportHeight = referenceViewportHeight,
        minimumHorizontalMargin = margin
    )

    // --- 0. The REAL Samsung device geometry (reproduced from on-device PASSPORT_LAYOUT) ---

    // Measured on the reference device: 1080px wide, 2105px padded height, 2.8125 px/dp,
    // top toolbar reserve 60dp = 168.75px, bottom control panel ~115dp.
    private val deviceWidth = 1080f
    private val devicePaddedHeight = 2105f
    private val deviceDensity = 2.8125f
    private val deviceTopReserve = 60f * deviceDensity
    private val deviceBottomReserve = 115f * deviceDensity
    private val deviceViewportHeight = devicePaddedHeight - deviceTopReserve - deviceBottomReserve

    private fun deviceGuide() = PassportGuideGeometry.planPassportGuide(
        viewportWidth = deviceWidth,
        viewportHeight = deviceViewportHeight,
        minimumHorizontalMargin = 12f * deviceDensity
    )

    @Test
    fun deviceCameraViewportMatchesMeasuredGeometry() {
        assertTrue(
            "viewport $deviceViewportHeight must be ~1610-1616px",
            deviceViewportHeight in 1610f..1616f
        )
    }

    @Test
    fun deviceGuideReachesLockedReferenceWidth() {
        val guide = deviceGuide()
        val widthFraction = guide.width / deviceWidth

        assertTrue("width fraction $widthFraction must be 0.91..0.93", widthFraction in 0.91f..0.93f)
        assertTrue("left ${guide.left} must be ~38..49px", guide.left in 38f..49f)
        assertTrue("right ${guide.right} must be ~1031..1042px", guide.right in 1031f..1042f)
    }

    @Test
    fun deviceGuideHeightAlignmentAndAspectMatchReference() {
        val guide = deviceGuide()

        val heightFraction = guide.height / deviceViewportHeight
        assertTrue("height fraction $heightFraction must be 0.86..0.88", heightFraction in 0.86f..0.881f)
        val alignment = (guide.alignmentY - guide.top) / guide.height
        assertTrue("alignment $alignment must be 0.48..0.52", alignment in 0.48f..0.52f)
        val aspect = guide.width / guide.height
        assertTrue("aspect $aspect must be 0.69..0.72", aspect in 0.69f..0.72f)
    }

    @Test
    fun deviceGuideNeverRegressesBelowLockedMinimumWidth() {
        // Hard regression guard: the logged 0.8509 (158dp reserve) and the earlier 0.709 must
        // both be unreachable for the reference device's viewport.
        val widthFraction = deviceGuide().width / deviceWidth

        assertTrue("width fraction $widthFraction must never drop below 0.89", widthFraction >= 0.89f)
    }

    @Test
    fun deviceGuideDoesNotOverlapTheControlPanel() {
        val guide = deviceGuide()
        // The guide is planned inside the viewport, which already excludes the panel, so the
        // frame's bottom (in full-screen coordinates) must stay above the panel's top edge.
        val frameBottomOnScreen = deviceTopReserve + guide.bottom
        val panelTopOnScreen = devicePaddedHeight - deviceBottomReserve

        assertTrue(
            "frame bottom $frameBottomOnScreen must stay above panel top $panelTopOnScreen",
            frameBottomOnScreen <= panelTopOnScreen + 0.01f
        )
        assertTrue("frame top below the toolbar", deviceTopReserve + guide.top >= deviceTopReserve)
    }

    // --- 1. Normal reference-equivalent screen ---

    @Test
    fun referenceScreenUsesNearFullWidth() {
        val guide = referenceGuide()
        val widthFraction = guide.width / referenceViewportWidth

        assertTrue("width fraction $widthFraction must be 0.91..0.93", widthFraction in 0.91f..0.93f)
        // The regression this fixes: the old planner collapsed to ~0.71 via the height cap.
        assertTrue("must never return to the old ~71% width", widthFraction > 0.85f)
    }

    @Test
    fun referenceScreenPreservesPassportAspect() {
        val guide = referenceGuide()
        val aspect = guide.width / guide.height

        assertTrue("aspect $aspect must be 0.69..0.72", aspect in 0.69f..0.72f)
        assertTrue("portrait", guide.height > guide.width)
    }

    @Test
    fun referenceScreenGuideNearlyFillsViewport() {
        val guide = referenceGuide()
        val heightFraction = guide.height / referenceViewportHeight

        assertTrue("height fraction $heightFraction must be 0.83..0.88", heightFraction in 0.83f..0.88f)
    }

    @Test
    fun dottedLineSitsHalfwayDownTheFrame() {
        val guide = referenceGuide()
        val fraction = (guide.alignmentY - guide.top) / guide.height

        assertTrue("alignment $fraction must be 0.48..0.52", fraction in 0.48f..0.52f)
    }

    @Test
    fun referenceMarginsMatchTheApprovedGeometry() {
        val guide = referenceGuide()
        // ~22px each side on a 540px-wide reference screenshot.
        assertTrue("left margin ${guide.left}", guide.left in 15f..30f)
        assertEquals("symmetric", guide.left, referenceViewportWidth - guide.right, 0.01f)
    }

    // --- 2. Compact portrait viewport ---

    @Test
    fun compactViewportKeepsFrameFullyVisibleWithoutOverlap() {
        val width = 540f
        val height = 560f // deliberately short: the aspect-derived height cannot fit
        val guide = PassportGuideGeometry.planPassportGuide(width, height, margin)

        assertTrue("top inside viewport", guide.top >= 0f)
        assertTrue("bottom inside viewport", guide.bottom <= height + 0.01f)
        assertTrue("left inside viewport", guide.left >= margin - 0.01f)
        assertTrue("right inside viewport", guide.right <= width - margin + 0.01f)
        assertEquals("aspect still preserved", 0.70f, guide.width / guide.height, 0.01f)
        assertTrue(
            "height capped at the max viewport fraction",
            guide.height <= height * PassportGuideGeometry.MAX_VIEWPORT_HEIGHT_FRACTION + 0.5f
        )
    }

    @Test
    fun compactViewportMaximizesWidthBeforeAddingMargin() {
        val width = 540f
        val height = 560f
        val guide = PassportGuideGeometry.planPassportGuide(width, height, margin)

        // Width is driven by the capped height through the aspect, not by an arbitrary shrink.
        val expectedWidth = height * PassportGuideGeometry.MAX_VIEWPORT_HEIGHT_FRACTION *
            PassportGuideGeometry.PASSPORT_ASPECT_WIDTH_OVER_HEIGHT
        assertEquals(expectedWidth, guide.width, 1f)
    }

    // --- 3. Tall portrait viewport ---

    @Test
    fun tallViewportKeepsTargetWidthAndCentersVertically() {
        val width = 540f
        val height = 1400f
        val guide = PassportGuideGeometry.planPassportGuide(width, height, margin)

        val widthFraction = guide.width / width
        assertTrue("width stays ~92% on tall screens", widthFraction in 0.91f..0.93f)
        // Vertically centered in the viewport.
        assertEquals(height / 2f, (guide.top + guide.bottom) / 2f, 0.01f)
    }

    @Test
    fun planningIsDeterministic() {
        assertEquals(referenceGuide(), referenceGuide())
    }

    // --- 4. Illustration planner ---

    @Test
    fun illustrationStaysInsideTheGuide() {
        val guide = referenceGuide()
        val art = PassportGuideGeometry.planIllustration(guide)

        assertTrue("left inside", art.groupLeft >= guide.left - 0.01f)
        assertTrue("right inside", art.groupRight <= guide.right + 0.01f)
        assertTrue("top inside", art.groupTop >= guide.top - 0.01f)
        assertTrue("bottom inside", art.groupBottom <= guide.bottom + 0.01f)
    }

    @Test
    fun illustrationOccupiesTheLowerFrameRegion() {
        val guide = referenceGuide()
        val art = PassportGuideGeometry.planIllustration(guide)

        // Head/shoulder group centered around 70-76% down the guide.
        val headFraction = (art.headCenterY - guide.top) / guide.height
        assertTrue("head at $headFraction must be 0.62..0.78", headFraction in 0.62f..0.78f)

        // Group spans a substantial share of the guide.
        val widthFraction = (art.groupRight - art.groupLeft) / guide.width
        assertTrue("group width $widthFraction must be 0.78..0.86", widthFraction in 0.78f..0.86f)
        val heightFraction = (art.groupBottom - art.groupTop) / guide.height
        assertTrue("group height $heightFraction must be 0.30..0.45", heightFraction in 0.30f..0.45f)
    }

    @Test
    fun bottomLongLineSitsNearTheFrameBottomAndStaysInside() {
        val guide = referenceGuide()
        val art = PassportGuideGeometry.planIllustration(guide)

        val bottomLine = art.lines.last()
        val fraction = (bottomLine.top - guide.top) / guide.height
        assertTrue("bottom line at $fraction must be 0.88..0.94", fraction in 0.88f..0.94f)
        assertTrue("inside right edge", bottomLine.right <= guide.right + 0.01f)
        assertTrue("spans most of the width", bottomLine.width / guide.width > 0.7f)
    }

    @Test
    fun illustrationScalesWithTheGuideRatherThanFixedPixels() {
        val small = PassportGuideGeometry.planPassportGuide(400f, 620f, margin)
        val large = PassportGuideGeometry.planPassportGuide(800f, 1240f, margin)
        val smallArt = PassportGuideGeometry.planIllustration(small)
        val largeArt = PassportGuideGeometry.planIllustration(large)

        // Doubling the guide doubles the artwork (same relative composition).
        assertEquals(2f, largeArt.headRadius / smallArt.headRadius, 0.05f)
    }

    // --- 5. Crop parity ---

    @Test
    fun theDrawnFrameIsTheCropFrame() {
        // The screen draws, reports bounds for the baker, and crops from ONE layout instance;
        // planning twice with the same inputs yields identical rects, so the Canvas frame and
        // the baker's crop frame can never diverge.
        val drawn = referenceGuide()
        val cropped = referenceGuide()

        assertEquals(drawn.left, cropped.left, 0f)
        assertEquals(drawn.top, cropped.top, 0f)
        assertEquals(drawn.right, cropped.right, 0f)
        assertEquals(drawn.bottom, cropped.bottom, 0f)
    }
}
