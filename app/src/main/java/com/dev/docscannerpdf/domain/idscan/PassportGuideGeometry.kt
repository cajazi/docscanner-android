package com.dev.docscannerpdf.domain.idscan

/**
 * Resolved on-screen placement of the portrait passport guide frame, in pixels within the
 * capture container's coordinate space. [alignmentY] is the absolute y of the dotted horizontal
 * alignment line. Framework-free so the layout math is directly JVM-testable; the capture
 * screen converts to/from Compose units at its edges and feeds [left]/[top]/[width]/[height]
 * straight into the shared [IdCardFrameCropper] for the crop mapping — the drawn frame and the
 * baker's crop rect are the SAME rectangle.
 */
data class PassportGuideLayout(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val alignmentY: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/** One placeholder text line inside the passport illustration, in container pixels. */
data class PassportTextLine(
    val left: Float,
    val top: Float,
    val right: Float,
    val height: Float
) {
    val width: Float get() = right - left
}

/**
 * The original DocScanner passport illustration laid out relative to the guide frame: a
 * head-and-shoulders silhouette on the lower-left with placeholder text lines beside and below
 * it. Purely generic artwork — no real passport template, emblem, flag, seal or personal data.
 */
data class PassportIllustrationLayout(
    val headCenterX: Float,
    val headCenterY: Float,
    val headRadius: Float,
    val shoulderLeft: Float,
    val shoulderTop: Float,
    val shoulderRight: Float,
    val shoulderBottom: Float,
    val lines: List<PassportTextLine>
) {
    val groupLeft: Float get() = minOf(shoulderLeft, lines.minOf { it.left })
    val groupTop: Float get() = minOf(headCenterY - headRadius, lines.minOf { it.top })
    val groupRight: Float get() = maxOf(shoulderRight, lines.maxOf { it.right })
    val groupBottom: Float get() = maxOf(shoulderBottom, lines.maxOf { it.top + it.height })
}

/**
 * Pure, responsive geometry for the passport guide frame, calibrated to the approved capture
 * reference: a dominant, near-full-width portrait frame centered in the CAMERA VIEWPORT (the
 * region between the top toolbar and the bottom controls — the caller passes that already-inset
 * region, so this never assumes system-bar or cutout sizes itself).
 *
 * The previous conservative constants (85% width, 82% height cap) made the height cap the
 * binding constraint on normal phones, and capping recomputes width DOWN from the capped height
 * — collapsing the frame to ~71% width. The calibrated constants below keep the aspect-derived
 * height inside the viewport on normal portrait phones so the cap never triggers and the frame
 * stays at its target ~92% width.
 */
object PassportGuideGeometry {

    /** Passport information-page aspect ratio (width / height), matching the capture reference. */
    const val PASSPORT_ASPECT_WIDTH_OVER_HEIGHT = 0.70f

    /** Target frame width as a fraction of the camera viewport width. */
    const val TARGET_WIDTH_FRACTION = 0.92f

    /** The frame must never render narrower than this on a normal portrait phone. */
    const val MIN_WIDTH_FRACTION = 0.89f

    /** Upper bound so the frame always keeps a visible side margin. */
    const val MAX_WIDTH_FRACTION = 0.93f

    /** Height the frame aims to occupy within the camera viewport. */
    const val TARGET_VIEWPORT_HEIGHT_FRACTION = 0.84f

    /** Hard cap: only applied when the aspect-derived height genuinely cannot fit. */
    const val MAX_VIEWPORT_HEIGHT_FRACTION = 0.88f

    /** Dotted alignment line, as a fraction of the frame height from its top (reference: halfway). */
    const val ALIGNMENT_LINE_FRACTION = 0.50f

    // --- illustration composition, all relative to the guide frame ---
    private const val ILLUSTRATION_GROUP_TOP_FRACTION = 0.63f
    private const val ILLUSTRATION_SIDE_INSET_FRACTION = 0.09f
    private const val HEAD_CENTER_Y_FRACTION = 0.72f
    private const val BOTTOM_LINE_Y_FRACTION = 0.905f

    /**
     * Lays out the guide frame within the camera viewport [viewportWidth] x [viewportHeight]
     * (already inset for the toolbar, bottom controls, system bars and cutouts by the caller),
     * keeping at least [minimumHorizontalMargin] of horizontal margin. The viewport's top-left
     * is the origin; the call site adds the viewport's own offset back.
     */
    fun planPassportGuide(
        viewportWidth: Float,
        viewportHeight: Float,
        minimumHorizontalMargin: Float
    ): PassportGuideLayout {
        require(viewportWidth > 0f && viewportHeight > 0f) { "Viewport dimensions must be positive." }
        require(minimumHorizontalMargin >= 0f) { "Margin cannot be negative." }

        val maxWidthByMargin = (viewportWidth - 2f * minimumHorizontalMargin).coerceAtLeast(1f)
        val maxWidthByFraction = viewportWidth * MAX_WIDTH_FRACTION
        var width = (viewportWidth * TARGET_WIDTH_FRACTION)
            .coerceAtMost(maxWidthByMargin)
            .coerceAtMost(maxWidthByFraction)
        var height = width / PASSPORT_ASPECT_WIDTH_OVER_HEIGHT

        // Cap the height ONLY when the aspect-derived height genuinely overflows the viewport
        // (short/compact screens); the width is then recomputed from the capped height so the
        // aspect is always preserved rather than the frame being stretched.
        val maxHeight = viewportHeight * MAX_VIEWPORT_HEIGHT_FRACTION
        if (height > maxHeight) {
            height = maxHeight
            width = (height * PASSPORT_ASPECT_WIDTH_OVER_HEIGHT).coerceAtMost(maxWidthByMargin)
            height = width / PASSPORT_ASPECT_WIDTH_OVER_HEIGHT
        }

        val left = (viewportWidth - width) / 2f
        val top = (viewportHeight - height) / 2f
        return PassportGuideLayout(
            left = left,
            top = top,
            right = left + width,
            bottom = top + height,
            alignmentY = top + height * ALIGNMENT_LINE_FRACTION
        )
    }

    /**
     * Lays out the illustration inside [guide]: a head-and-shoulders silhouette on the lower
     * left, a short line beside the head, two lines beside/below the portrait, and one long line
     * spanning most of the lower frame width. Every value scales from the guide's own
     * dimensions — nothing is a fixed pixel size — so it grows with the enlarged frame.
     */
    fun planIllustration(guide: PassportGuideLayout): PassportIllustrationLayout {
        val inset = guide.width * ILLUSTRATION_SIDE_INSET_FRACTION
        val contentLeft = guide.left + inset
        val contentRight = guide.right - inset
        val contentWidth = contentRight - contentLeft

        val portraitWidth = contentWidth * 0.30f
        val headRadius = portraitWidth * 0.30f
        val headCenterX = contentLeft + portraitWidth / 2f
        val headCenterY = guide.top + guide.height * HEAD_CENTER_Y_FRACTION - headRadius * 1.2f

        val shoulderTop = headCenterY + headRadius * 1.15f
        val shoulderHeight = guide.height * 0.115f
        val shoulder = listOf(
            headCenterX - portraitWidth / 2f,
            shoulderTop,
            headCenterX + portraitWidth / 2f,
            shoulderTop + shoulderHeight
        )

        val lineHeight = guide.height * 0.018f
        val linesLeft = contentLeft + portraitWidth + contentWidth * 0.06f
        val groupTop = guide.top + guide.height * ILLUSTRATION_GROUP_TOP_FRACTION
        val lineGap = guide.height * 0.075f

        val lines = listOf(
            // Short line beside the head.
            PassportTextLine(
                left = linesLeft,
                top = groupTop,
                right = linesLeft + (contentRight - linesLeft) * 0.45f,
                height = lineHeight
            ),
            // Two medium/long lines beside and below the portrait.
            PassportTextLine(
                left = linesLeft,
                top = groupTop + lineGap,
                right = contentRight,
                height = lineHeight
            ),
            PassportTextLine(
                left = linesLeft,
                top = groupTop + lineGap * 2f,
                right = contentRight,
                height = lineHeight
            ),
            PassportTextLine(
                left = linesLeft,
                top = groupTop + lineGap * 3f,
                right = linesLeft + (contentRight - linesLeft) * 0.72f,
                height = lineHeight
            ),
            // One long line spanning most of the lower guide width.
            PassportTextLine(
                left = contentLeft,
                top = guide.top + guide.height * BOTTOM_LINE_Y_FRACTION,
                right = contentRight,
                height = lineHeight
            )
        )

        return PassportIllustrationLayout(
            headCenterX = headCenterX,
            headCenterY = headCenterY,
            headRadius = headRadius,
            shoulderLeft = shoulder[0],
            shoulderTop = shoulder[1],
            shoulderRight = shoulder[2],
            shoulderBottom = shoulder[3],
            lines = lines
        )
    }
}
