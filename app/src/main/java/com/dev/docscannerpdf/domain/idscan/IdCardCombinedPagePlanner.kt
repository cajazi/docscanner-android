package com.dev.docscannerpdf.domain.idscan

import com.dev.docscannerpdf.domain.pdf.CardRect
import com.dev.docscannerpdf.domain.pdf.IdCardLayoutPlanner
import com.dev.docscannerpdf.util.AppConstants
import kotlin.math.roundToInt

/**
 * Where one ID-card side lands on the combined result page. [cardRect] is the fixed, card-shaped
 * slot from [IdCardLayoutPlanner] and is the side's COMPLETE destination — every side is drawn
 * filling its whole slot, so front and back always render at exactly the same visible size.
 * [sourceCropRect] is the centered region of the source photo (in that photo's own pixel space)
 * whose aspect ratio matches the slot: drawing that region into [cardRect] preserves proportions
 * (never stretches) and crops only the excess dimension. Aspect-FITTING into a smaller
 * destination was deliberately abandoned: with slightly different front/back source ratios it
 * shrank one side more than the other, and the white letterbox blended into the white page,
 * making the cards visibly different sizes.
 */
data class IdCardCombinedSideDraw(val cardRect: CardRect, val sourceCropRect: CardRect)

/**
 * Full layout for the combined CamScanner-style ID-card result page: front (and back, when
 * captured) on one white A4-proportioned page. [pageWidth]/[pageHeight] and every [CardRect] in
 * [front]/[back] are pixels in the output bitmap's space, except each side's
 * [IdCardCombinedSideDraw.sourceCropRect], which is in that side's source-image pixel space.
 */
data class IdCardCombinedPagePlan(
    val pageWidth: Int,
    val pageHeight: Int,
    val front: IdCardCombinedSideDraw,
    val back: IdCardCombinedSideDraw?
)

/**
 * Pure layout planning for the single combined front+back ID-card image shown on the Document
 * Ready preview and saved to the gallery. Card slots come from [IdCardLayoutPlanner] — the same
 * planner [com.dev.docscannerpdf.domain.pdf.PdfExportService.exportIdCard] uses for the A4 PDF
 * page — and the page keeps A4 proportions ([AppConstants.A4_WIDTH_POINTS] x
 * [AppConstants.A4_HEIGHT_POINTS]), so preview, gallery image, and PDF all agree on where each
 * side sits. Kept free of Android types so it is unit testable directly on the JVM;
 * [IdCardCombinedPageRenderer] is the thin bitmap-drawing shell around it.
 */
object IdCardCombinedPagePlanner {

    /**
     * Plans a combined page [pageWidth] pixels wide (height follows from the A4 ratio) for a
     * front image of [frontImageWidth] x [frontImageHeight] and, when both are non-null, a back
     * image of [backImageWidth] x [backImageHeight]. Passing dimensions for only one of the back
     * axes is a caller bug and throws, as would silently dropping the back side.
     */
    fun plan(
        pageWidth: Int,
        frontImageWidth: Int,
        frontImageHeight: Int,
        backImageWidth: Int? = null,
        backImageHeight: Int? = null
    ): IdCardCombinedPagePlan {
        require(pageWidth > 0) { "Page width must be positive." }
        require(frontImageWidth > 0 && frontImageHeight > 0) { "Front image dimensions must be positive." }
        require((backImageWidth == null) == (backImageHeight == null)) {
            "Back image dimensions must be provided together."
        }
        if (backImageWidth != null && backImageHeight != null) {
            require(backImageWidth > 0 && backImageHeight > 0) { "Back image dimensions must be positive." }
        }

        val pageHeight = (pageWidth.toFloat() * AppConstants.A4_HEIGHT_POINTS / AppConstants.A4_WIDTH_POINTS)
            .roundToInt()
        val sideCount = if (backImageWidth != null) 2 else 1
        val cardRects = IdCardLayoutPlanner.plan(
            sideCount = sideCount,
            pageWidth = pageWidth.toFloat(),
            pageHeight = pageHeight.toFloat()
        )

        return IdCardCombinedPagePlan(
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            front = IdCardCombinedSideDraw(
                cardRect = cardRects[0],
                sourceCropRect = sourceCoverCrop(cardRects[0], frontImageWidth, frontImageHeight)
            ),
            back = if (backImageWidth != null && backImageHeight != null) {
                IdCardCombinedSideDraw(
                    cardRect = cardRects[1],
                    sourceCropRect = sourceCoverCrop(cardRects[1], backImageWidth, backImageHeight)
                )
            } else {
                null
            }
        )
    }

    /**
     * The centered region of an [imageWidth] x [imageHeight] photo whose aspect ratio matches
     * [slot]'s — the source rect of a center-crop-to-fill draw into the complete slot. The image
     * is never stretched: only the dimension that overflows the slot's ratio is trimmed, equally
     * from both edges. The result is always inside the image bounds and at least 1x1. Shared by
     * the combined-page renderer and the ID-card PDF export so every surface fills its equal-size
     * card slot the same way.
     */
    fun sourceCoverCrop(slot: CardRect, imageWidth: Int, imageHeight: Int): CardRect {
        require(imageWidth > 0 && imageHeight > 0) { "Image dimensions must be positive." }
        require(slot.width > 0f && slot.height > 0f) { "Slot dimensions must be positive." }

        val slotRatio = slot.width / slot.height
        val imageRatio = imageWidth.toFloat() / imageHeight
        return if (imageRatio > slotRatio) {
            // Image is wider than the slot: full height, trim the sides.
            val cropWidth = (imageHeight * slotRatio).coerceIn(1f, imageWidth.toFloat())
            CardRect(
                left = (imageWidth - cropWidth) / 2f,
                top = 0f,
                width = cropWidth,
                height = imageHeight.toFloat()
            )
        } else {
            // Image is taller than the slot: full width, trim top and bottom.
            val cropHeight = (imageWidth / slotRatio).coerceIn(1f, imageHeight.toFloat())
            CardRect(
                left = 0f,
                top = (imageHeight - cropHeight) / 2f,
                width = imageWidth.toFloat(),
                height = cropHeight
            )
        }
    }
}
