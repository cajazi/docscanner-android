package com.dev.docscannerpdf.domain.idscan

import com.dev.docscannerpdf.domain.filter.DocumentFilter

/**
 * The pure layout contract for the passport review's filter panel: a dark, full-width panel with
 * a four-column thumbnail grid whose first two rows (eight filters) are always visible without
 * horizontal scrolling. Framework-free (no Compose/Bitmap) so the grid order, column count and
 * header are unit-testable on the JVM, and the screen cannot drift from the tested contract.
 *
 * The order maps the reference layout onto the app's OWN truthful [DocumentFilter] recipes —
 * no filter is invented to copy a name, and no artwork/branding is copied; every thumbnail is
 * generated at runtime from the user's current passport page.
 */
object PassportFilterGrid {

    /** Four equal-width columns, per the reference layout. */
    const val COLUMN_COUNT = 4

    /** The first two rows (eight filters) must be visible with no horizontal scrolling. */
    const val VISIBLE_ROWS = 2

    /** The panel header shown above the grid. */
    const val HEADER = "Filter (will apply to all pictures in this page)"

    /**
     * Grid order, row-major. Row 1: Original, Brightness, Enhance, Contrast. Row 2: Sharpen,
     * B&W, Gray, Warm. Remaining truthful filters (Sepia, Cool) follow in further rows the user
     * can scroll to vertically. Every entry is an existing [DocumentFilter] recipe.
     */
    val ORDER: List<DocumentFilter> = listOf(
        DocumentFilter.ORIGINAL,
        DocumentFilter.BRIGHTNESS,
        DocumentFilter.ENHANCE,
        DocumentFilter.CONTRAST,
        DocumentFilter.SHARPEN,
        DocumentFilter.BW,
        DocumentFilter.GRAY,
        DocumentFilter.WARM,
        DocumentFilter.SEPIA,
        DocumentFilter.COOL
    )

    /** The filters that must be visible without scrolling: the first two full rows. */
    fun firstVisible(): List<DocumentFilter> = ORDER.take(COLUMN_COUNT * VISIBLE_ROWS)
}
