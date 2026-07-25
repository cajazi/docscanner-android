package com.dev.docscannerpdf.domain.idscan

import com.dev.docscannerpdf.domain.filter.DocumentFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the filter panel's pure layout contract: a FOUR-column grid whose first two rows (eight
 * filters) are visible without horizontal scrolling, mapped onto the app's own truthful
 * [DocumentFilter] recipes — no filter is invented to copy a reference app's name.
 */
class PassportFilterGridTest {

    @Test
    fun panelUsesExactlyFourColumns() {
        assertEquals(4, PassportFilterGrid.COLUMN_COUNT)
    }

    @Test
    fun firstTwoRowsExposeExactlyEightFilters() {
        assertEquals(2, PassportFilterGrid.VISIBLE_ROWS)
        assertEquals(8, PassportFilterGrid.firstVisible().size)
    }

    @Test
    fun firstVisibleRowsMatchTheReferenceArrangement() {
        // Row 1: Original, Brightness (the truthful "Lighten"), Enhance, Contrast.
        // Row 2: Sharpen, B&W, Gray, Warm.
        assertEquals(
            listOf(
                DocumentFilter.ORIGINAL,
                DocumentFilter.BRIGHTNESS,
                DocumentFilter.ENHANCE,
                DocumentFilter.CONTRAST,
                DocumentFilter.SHARPEN,
                DocumentFilter.BW,
                DocumentFilter.GRAY,
                DocumentFilter.WARM
            ),
            PassportFilterGrid.firstVisible()
        )
    }

    @Test
    fun remainingTruthfulFiltersFollowInScrollableRows() {
        val afterVisible = PassportFilterGrid.ORDER.drop(PassportFilterGrid.firstVisible().size)
        assertEquals(listOf(DocumentFilter.SEPIA, DocumentFilter.COOL), afterVisible)
    }

    @Test
    fun everyGridEntryIsAnExistingCatalogFilterAndNoneRepeats() {
        // Only truthful, already-implemented recipes appear — nothing invented to copy a name.
        assertTrue(DocumentFilter.CATALOG.containsAll(PassportFilterGrid.ORDER))
        assertEquals(PassportFilterGrid.ORDER.size, PassportFilterGrid.ORDER.toSet().size)
    }

    @Test
    fun gridCoversTheWholeCatalogSoNoFilterIsLost() {
        // The redesign must not silently drop a previously available filter.
        assertEquals(DocumentFilter.CATALOG.toSet(), PassportFilterGrid.ORDER.toSet())
    }

    @Test
    fun originalLeadsTheGridSoResetIsAlwaysOneTapAway() {
        assertEquals(DocumentFilter.ORIGINAL, PassportFilterGrid.ORDER.first())
    }

    @Test
    fun headerMatchesTheRequiredPanelTitle() {
        assertEquals(
            "Filter (will apply to all pictures in this page)",
            PassportFilterGrid.HEADER
        )
    }
}
