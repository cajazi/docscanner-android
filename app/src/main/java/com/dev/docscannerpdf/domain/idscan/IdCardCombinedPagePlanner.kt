package com.dev.docscannerpdf.domain.idscan

import com.dev.docscannerpdf.domain.pdf.CardRect
import com.dev.docscannerpdf.domain.pdf.IdCardLayoutPlanner
import com.dev.docscannerpdf.util.AppConstants
import kotlin.math.roundToInt

/**
 * Where one ID-card side lands on the combined result page: [cardRect] is the fixed,
 * card-shaped slot from [IdCardLayoutPlanner], and [imageRect] is the side's photo scaled to
 * fit (never cropped or stretched) and centered inside that slot — the same rule
 * [com.dev.docscannerpdf.domain.pdf.PdfCoordinateMapper.imageDestinationRect] applies for the
 * PDF export, so the rasterized page and the exported PDF place each photo identically.
 */
data class IdCardCombinedSideDraw(val cardRect: CardRect, val imageRect: CardRect)

/**
 * Full layout for the combined CamScanner-style ID-card result page: front (and back, when
 * captured) on one white A4-proportioned page. All values are pixels in the output bitmap's
 * space.
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
                imageRect = fitWithin(cardRects[0], frontImageWidth, frontImageHeight)
            ),
            back = if (backImageWidth != null && backImageHeight != null) {
                IdCardCombinedSideDraw(
                    cardRect = cardRects[1],
                    imageRect = fitWithin(cardRects[1], backImageWidth, backImageHeight)
                )
            } else {
                null
            }
        )
    }

    /**
     * Scales an [imageWidth] x [imageHeight] photo to fit inside [rect] preserving aspect ratio
     * (never cropped or stretched), centered — the same centered-fit rule
     * [com.dev.docscannerpdf.domain.pdf.PdfCoordinateMapper.imageDestinationRect] uses, restated
     * here on [CardRect] so this planner stays free of android.graphics types.
     */
    private fun fitWithin(rect: CardRect, imageWidth: Int, imageHeight: Int): CardRect {
        val scale = minOf(rect.width / imageWidth, rect.height / imageHeight)
        val width = imageWidth * scale
        val height = imageHeight * scale
        return CardRect(
            left = rect.left + (rect.width - width) / 2f,
            top = rect.top + (rect.height - height) / 2f,
            width = width,
            height = height
        )
    }
}
