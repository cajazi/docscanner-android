package com.dev.docscannerpdf.domain.pdf

/**
 * A single card-shaped rectangle within some page/canvas space (points, px, or dp — the caller
 * decides the unit by the [pageWidth]/[pageHeight] it passes into [IdCardLayoutPlanner.plan]).
 * Framework-free so it can be shared by both the Compose preview and the PDF export renderer,
 * guaranteeing they agree on where each card sits.
 */
data class CardRect(val left: Float, val top: Float, val width: Float, val height: Float) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height
}

/**
 * Pure layout logic for CamScanner-style ID-card placement: 1 or 2 same-size, ID-card-shaped
 * rectangles, stacked front-above-back and centered as a group on a page. Kept free of Android
 * types so it can be unit tested directly on the JVM and reused identically by
 * [PdfExportService] and the on-screen preview, instead of each hand-rolling its own sizing.
 */
object IdCardLayoutPlanner {

    /** ISO/IEC 7810 ID-1 card ratio (85.60mm x 53.98mm) — real ID/bank-card proportions. */
    const val CARD_ASPECT_RATIO = 1.586f

    /** A card never grows wider than this fraction of the page, even with only one side. */
    private const val MAX_CARD_WIDTH_FRACTION = 0.78f

    /** The whole card stack (1 or 2 cards + gap) never taller than this fraction of the page. */
    private const val MAX_GROUP_HEIGHT_FRACTION = 0.62f

    /** Vertical gap between front and back, as a fraction of the page height. */
    private const val GAP_FRACTION = 0.035f

    /**
     * Builds [sideCount] (1 or 2) same-size [CardRect]s sized to [CARD_ASPECT_RATIO], stacked
     * top-to-bottom in order (front first) and centered as one group within a [pageWidth] x
     * [pageHeight] page. Card size is capped by both the page width and the available stack
     * height so two cards never overflow the page and never get stretched into a document-sized
     * slot the way a naive "page height / side count" split would.
     */
    fun plan(sideCount: Int, pageWidth: Float, pageHeight: Float): List<CardRect> {
        require(sideCount in 1..2) { "An ID card export only ever has 1 or 2 sides." }
        require(pageWidth > 0f && pageHeight > 0f) { "Page dimensions must be positive." }

        val gap = if (sideCount > 1) pageHeight * GAP_FRACTION else 0f
        val maxWidthFromPageWidth = pageWidth * MAX_CARD_WIDTH_FRACTION
        val maxGroupHeight = pageHeight * MAX_GROUP_HEIGHT_FRACTION
        val maxCardHeightFromGroup = (maxGroupHeight - gap * (sideCount - 1)) / sideCount
        val maxWidthFromHeight = maxCardHeightFromGroup * CARD_ASPECT_RATIO
        val cardWidth = minOf(maxWidthFromPageWidth, maxWidthFromHeight)
        val cardHeight = cardWidth / CARD_ASPECT_RATIO

        val totalGroupHeight = cardHeight * sideCount + gap * (sideCount - 1)
        val groupTop = (pageHeight - totalGroupHeight) / 2f
        val left = (pageWidth - cardWidth) / 2f

        return (0 until sideCount).map { index ->
            CardRect(
                left = left,
                top = groupTop + index * (cardHeight + gap),
                width = cardWidth,
                height = cardHeight
            )
        }
    }
}
