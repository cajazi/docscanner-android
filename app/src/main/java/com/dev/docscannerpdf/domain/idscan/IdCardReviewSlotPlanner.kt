package com.dev.docscannerpdf.domain.idscan

import com.dev.docscannerpdf.domain.pdf.CardRect
import com.dev.docscannerpdf.domain.pdf.IdCardLayoutPlanner

/** A width/height pair for a bitmap displayed inside a slot with contain-fit semantics. */
data class ContainedImageSize(val width: Float, val height: Float)

/**
 * Pure layout for the ID-card REVIEW screen's front/back preview slots — deliberately separate
 * from [IdCardLayoutPlanner], which keeps driving the combined page / PDF export unchanged.
 *
 * The review slots are OUTER allocation bounds only: both sides always get exactly equal slots,
 * but the captured bitmap is displayed inside its slot with contain/fit semantics (see
 * [containedImageSize] — the pure specification of what `ContentScale.Fit` does in the tile),
 * so the COMPLETE image is always visible at its intrinsic aspect ratio. Nothing here ever
 * crops, zooms, or stretches pixels; letterboxing inside a slot is expected and correct.
 * Compared to the old review layout this also sizes the images smaller and spreads front/back
 * further apart, per CamScanner-reference device QA.
 */
object IdCardReviewSlotPlanner {

    /**
     * A slot never grows wider than this fraction of the white canvas. Tuned against the
     * CamScanner reference screenshots: its review images span ~40% of the page width — the
     * previous 0.48 read visibly larger than the reference on device.
     */
    const val SLOT_WIDTH_FRACTION = 0.40f

    /** Outer slot shape only (ID-1 card ratio); the image inside letterboxes, never crops. */
    const val SLOT_ASPECT_RATIO = IdCardLayoutPlanner.CARD_ASPECT_RATIO

    /**
     * Vertical gap between front and back, as a fraction of one slot's height — ~2/3 of an
     * image height in the CamScanner reference.
     */
    const val GAP_TO_SLOT_HEIGHT_FRACTION = 0.66f

    /** The whole front/back group never taller than this fraction of the canvas. */
    const val MAX_GROUP_HEIGHT_FRACTION = 0.72f

    /**
     * Where the group sits vertically: the fraction of the LEFTOVER canvas height placed above
     * the group. 0.5 would dead-center it; CamScanner sits the cards noticeably above center
     * (its group top lands ~20% down the page), which 0.38 reproduces.
     */
    const val GROUP_VERTICAL_BIAS = 0.38f

    /** The bottom clearance never eats more than this fraction of a short screen's height. */
    const val MAX_BOTTOM_CLEARANCE_FRACTION = 0.12f

    /**
     * The dark breathing space between the white canvas's bottom edge and the bottom controls
     * — CamScanner shows a clearly visible dark band there instead of running the page into
     * the toolbar. Returns the preferred clearance (the screen passes ~52dp) capped at
     * [MAX_BOTTOM_CLEARANCE_FRACTION] of the available height, so short screens keep a
     * positive, proportionate band and the canvas height (available − clearance) can never go
     * negative. Pure px-in/px-out; the caller converts dp at the edge.
     */
    fun canvasBottomClearance(availableHeightPx: Float, preferredClearancePx: Float): Float {
        require(availableHeightPx > 0f) { "Available height must be positive." }
        require(preferredClearancePx >= 0f) { "Preferred clearance cannot be negative." }
        return minOf(preferredClearancePx, availableHeightPx * MAX_BOTTOM_CLEARANCE_FRACTION)
    }

    /**
     * Builds [sideCount] (1 or 2) exactly equal slots, stacked front-above-back with a
     * CamScanner-like gap, horizontally centered and vertically placed slightly ABOVE center
     * ([GROUP_VERTICAL_BIAS]) on a [pageWidth] x [pageHeight] canvas, matching the reference.
     * Slot geometry depends only on the canvas — never on the images' dimensions — so no image
     * shape can ever force the other side's allocation to change.
     */
    fun plan(sideCount: Int, pageWidth: Float, pageHeight: Float): List<CardRect> {
        require(sideCount in 1..2) { "An ID card review only ever shows 1 or 2 sides." }
        require(pageWidth > 0f && pageHeight > 0f) { "Canvas dimensions must be positive." }

        val heightFromWidth = (pageWidth * SLOT_WIDTH_FRACTION) / SLOT_ASPECT_RATIO
        // groupHeight = h * (sideCount + (sideCount - 1) * gapFraction); cap it to the canvas.
        val groupHeightUnits = sideCount + (sideCount - 1) * GAP_TO_SLOT_HEIGHT_FRACTION
        val heightFromCanvas = (pageHeight * MAX_GROUP_HEIGHT_FRACTION) / groupHeightUnits
        val slotHeight = minOf(heightFromWidth, heightFromCanvas)
        val slotWidth = slotHeight * SLOT_ASPECT_RATIO
        val gap = if (sideCount > 1) slotHeight * GAP_TO_SLOT_HEIGHT_FRACTION else 0f

        val groupHeight = slotHeight * sideCount + gap * (sideCount - 1)
        val groupTop = (pageHeight - groupHeight) * GROUP_VERTICAL_BIAS
        val left = (pageWidth - slotWidth) / 2f

        return (0 until sideCount).map { index ->
            CardRect(
                left = left,
                top = groupTop + index * (slotHeight + gap),
                width = slotWidth,
                height = slotHeight
            )
        }
    }

    /**
     * Contain-fit: the largest size at which an [imageWidth] x [imageHeight] bitmap fits
     * ENTIRELY inside a [slotWidth] x [slotHeight] slot with its intrinsic aspect ratio
     * preserved — the pure equivalent of `ContentScale.Fit`. The complete bitmap is always
     * visible: nothing is cropped and nothing is stretched; whichever dimension doesn't fill
     * the slot letterboxes.
     */
    fun containedImageSize(
        slotWidth: Float,
        slotHeight: Float,
        imageWidth: Int,
        imageHeight: Int
    ): ContainedImageSize {
        require(slotWidth > 0f && slotHeight > 0f) { "Slot dimensions must be positive." }
        require(imageWidth > 0 && imageHeight > 0) { "Image dimensions must be positive." }
        val scale = minOf(slotWidth / imageWidth, slotHeight / imageHeight)
        return ContainedImageSize(width = imageWidth * scale, height = imageHeight * scale)
    }

    /**
     * Size of the INNER image container that keeps a rotated image fully inside its outer
     * slot. Rotating a slot-sized layer by 90°/270° swings its corners outside the slot (and
     * potentially over the other side); instead the tile sizes an inner container to the slot's
     * dimensions SWAPPED for quarter rotations — after the center rotation its on-screen
     * footprint is exactly the slot again, so contain-fit content stays complete and contained
     * at 0, 90, 180, and 270 degrees. Rotation is normalized first, so negative values and
     * values beyond 360 (e.g. -90 ≡ 270, 450 ≡ 90) behave identically to their canonical angle.
     * The outer slot — and therefore the equal front/back allocation — is never changed.
     */
    fun rotationAwareContainerSize(
        slotWidth: Float,
        slotHeight: Float,
        rotationDegrees: Int
    ): ContainedImageSize {
        require(slotWidth > 0f && slotHeight > 0f) { "Slot dimensions must be positive." }
        val normalized = ((rotationDegrees % 360) + 360) % 360
        return if (normalized == 90 || normalized == 270) {
            ContainedImageSize(width = slotHeight, height = slotWidth)
        } else {
            ContainedImageSize(width = slotWidth, height = slotHeight)
        }
    }
}
