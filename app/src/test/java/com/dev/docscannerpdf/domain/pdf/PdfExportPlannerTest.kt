package com.dev.docscannerpdf.domain.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfExportPlannerTest {

    // --- Single page export -------------------------------------------------

    @Test
    fun singlePageProducesOneOrderedRenderablePage() {
        val plan = PdfExportPlanner.plan(
            listOf(
                PdfExportPageInput(
                    pageNumber = 1,
                    enhancedImageUrl = "https://api.test/enhanced-1.jpg",
                    ocrText = "Invoice total: 42.00"
                )
            )
        )

        val ready = plan as PdfExportPlan.Ready
        assertEquals(1, ready.pages.size)
        val page = ready.pages.single()
        assertEquals(1, page.pageNumber)
        assertEquals("https://api.test/enhanced-1.jpg", page.imageUrl)
        assertEquals(PdfExportImageSource.ENHANCED, page.imageSource)
    }

    // --- Multi-page export --------------------------------------------------

    @Test
    fun multiPageExportKeepsEveryPageInPageNumberOrder() {
        val plan = PdfExportPlanner.plan(
            listOf(
                PdfExportPageInput(pageNumber = 3, processedImageUrl = "https://api.test/p3.jpg"),
                PdfExportPageInput(pageNumber = 1, enhancedImageUrl = "https://api.test/p1.jpg"),
                PdfExportPageInput(pageNumber = 2, enhancedImageUrl = "https://api.test/p2.jpg")
            )
        )

        val ready = plan as PdfExportPlan.Ready
        assertEquals(listOf(1, 2, 3), ready.pages.map { it.pageNumber })
        assertEquals(
            listOf(
                "https://api.test/p1.jpg",
                "https://api.test/p2.jpg",
                "https://api.test/p3.jpg"
            ),
            ready.pages.map { it.imageUrl }
        )
    }

    // --- OCR embedding present ----------------------------------------------

    @Test
    fun ocrTextIsCarriedAsSearchableLayerWhenPresent() {
        val plan = PdfExportPlanner.plan(
            listOf(
                PdfExportPageInput(
                    pageNumber = 1,
                    enhancedImageUrl = "https://api.test/p1.jpg",
                    ocrText = "Searchable contract text"
                ),
                PdfExportPageInput(
                    pageNumber = 2,
                    enhancedImageUrl = "https://api.test/p2.jpg",
                    // Blank OCR must not become an (empty) searchable layer.
                    ocrText = "   "
                )
            )
        )

        val ready = plan as PdfExportPlan.Ready
        val first = ready.pages[0]
        assertTrue(first.hasSearchableText)
        assertEquals("Searchable contract text", first.ocrText)

        val second = ready.pages[1]
        assertFalse(second.hasSearchableText)
        assertNull(second.ocrText)
    }

    @Test
    fun searchableLayerWrapsTextDeterministicallyWithoutLosingContent() {
        val lines = PdfExportPlanner.layoutSearchableLines(
            text = "alpha beta gamma\n\ndelta",
            maxCharsPerLine = 10
        )

        // Existing blank lines are dropped; long lines wrap on word boundaries.
        assertEquals(listOf("alpha beta", "gamma", "delta"), lines)
    }

    @Test
    fun searchableLayerHardSplitsWordsLongerThanALine() {
        val lines = PdfExportPlanner.layoutSearchableLines(
            text = "supercalifragilistic",
            maxCharsPerLine = 6
        )

        assertEquals(listOf("superc", "alifra", "gilist", "ic"), lines)
        assertEquals("supercalifragilistic", lines.joinToString(""))
    }

    // --- Fallback image selection -------------------------------------------

    @Test
    fun enhancedImageIsPreferredOverProcessed() {
        val selection = PdfExportPlanner.selectImage(
            PdfExportPageInput(
                pageNumber = 1,
                enhancedImageUrl = "https://api.test/enhanced.jpg",
                processedImageUrl = "https://api.test/processed.jpg"
            )
        )

        assertEquals("https://api.test/enhanced.jpg" to PdfExportImageSource.ENHANCED, selection)
    }

    @Test
    fun processedImageIsUsedWhenEnhancedIsMissingOrBlank() {
        val blankEnhanced = PdfExportPlanner.selectImage(
            PdfExportPageInput(
                pageNumber = 1,
                enhancedImageUrl = "   ",
                processedImageUrl = "https://api.test/processed.jpg"
            )
        )
        assertEquals(
            "https://api.test/processed.jpg" to PdfExportImageSource.PROCESSED,
            blankEnhanced
        )

        val missingEnhanced = PdfExportPlanner.selectImage(
            PdfExportPageInput(pageNumber = 1, processedImageUrl = "https://api.test/processed.jpg")
        )
        assertEquals(
            "https://api.test/processed.jpg" to PdfExportImageSource.PROCESSED,
            missingEnhanced
        )
    }

    @Test
    fun selectImageReturnsNullWhenNoUsableImageExists() {
        assertNull(
            PdfExportPlanner.selectImage(
                PdfExportPageInput(pageNumber = 1, enhancedImageUrl = "", processedImageUrl = "  ")
            )
        )
    }

    // --- Error handling -----------------------------------------------------

    @Test
    fun emptyRequestIsInvalid() {
        val plan = PdfExportPlanner.plan(emptyList())
        assertTrue(plan is PdfExportPlan.Invalid)
    }

    @Test
    fun pageWithoutAnyImageMakesTheWholeExportInvalid() {
        val plan = PdfExportPlanner.plan(
            listOf(
                PdfExportPageInput(pageNumber = 1, enhancedImageUrl = "https://api.test/p1.jpg"),
                PdfExportPageInput(pageNumber = 2, ocrText = "text but no image")
            )
        )

        val invalid = plan as PdfExportPlan.Invalid
        assertTrue(invalid.reason.contains("Page 2"))
    }
}
