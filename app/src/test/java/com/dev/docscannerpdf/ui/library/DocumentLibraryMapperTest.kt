package com.dev.docscannerpdf.ui.library

import com.dev.docscannerpdf.data.local.DocumentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentLibraryMapperTest {

    private fun document(
        id: Long,
        title: String,
        timestamp: Long = id,
        pageCount: Int = 1,
        localPdfUri: String = "file:///docs/$id.pdf",
        extractedText: String? = null,
        searchableText: String = "",
        tags: String = "",
        isFavorite: Boolean = false
    ) = DocumentEntity(
        id = id,
        title = title,
        timestamp = timestamp,
        pageCount = pageCount,
        localPdfUri = localPdfUri,
        extractedText = extractedText,
        searchableText = searchableText,
        tags = tags,
        isFavorite = isFavorite
    )

    // --- Search filters by title / OCR text ---------------------------------

    @Test
    fun searchFiltersByTitle() {
        val docs = listOf(
            document(1, "Invoice March"),
            document(2, "Receipt April"),
            document(3, "Invoice May")
        )

        val state = buildDocumentLibraryState(docs, query = "invoice", sort = DocumentLibrarySort.NEWEST)

        assertEquals(DocumentLibraryStatus.READY, state.status)
        assertEquals(listOf(3L, 1L), state.items.map { it.id })
        assertEquals(3, state.totalCount)
    }

    @Test
    fun searchFiltersByOcrText() {
        val docs = listOf(
            document(1, "Scan A", extractedText = "Total amount due 42.00 USD"),
            document(2, "Scan B", extractedText = "Meeting notes about roadmap"),
            // searchableText is also matched (it carries cleaned OCR + metadata).
            document(3, "Scan C", searchableText = "quarterly roadmap planning")
        )

        val byOcr = buildDocumentLibraryState(docs, query = "roadmap", sort = DocumentLibrarySort.OLDEST)
        assertEquals(listOf(2L, 3L), byOcr.items.map { it.id })

        val byAmount = buildDocumentLibraryState(docs, query = "42.00", sort = DocumentLibrarySort.NEWEST)
        assertEquals(listOf(1L), byAmount.items.map { it.id })
    }

    @Test
    fun blankQueryReturnsEveryDocument() {
        val docs = listOf(document(1, "A"), document(2, "B"))
        val state = buildDocumentLibraryState(docs, query = "   ", sort = DocumentLibrarySort.NEWEST)
        assertEquals(2, state.items.size)
    }

    // --- Sort newest / oldest / name ----------------------------------------

    @Test
    fun sortNewestOldestAndName() {
        val docs = listOf(
            document(1, "Banana", timestamp = 100),
            document(2, "apple", timestamp = 300),
            document(3, "Cherry", timestamp = 200)
        )

        val newest = buildDocumentLibraryState(docs, query = "", sort = DocumentLibrarySort.NEWEST)
        assertEquals(listOf(2L, 3L, 1L), newest.items.map { it.id })

        val oldest = buildDocumentLibraryState(docs, query = "", sort = DocumentLibrarySort.OLDEST)
        assertEquals(listOf(1L, 3L, 2L), oldest.items.map { it.id })

        // Name sort is case-insensitive: apple, Banana, Cherry.
        val byName = buildDocumentLibraryState(docs, query = "", sort = DocumentLibrarySort.NAME)
        assertEquals(listOf(2L, 1L, 3L), byName.items.map { it.id })
    }

    // --- Empty state mapping ------------------------------------------------

    @Test
    fun emptyStoreMapsToEmptyStatus() {
        val state = buildDocumentLibraryState(emptyList(), query = "", sort = DocumentLibrarySort.NEWEST)
        assertEquals(DocumentLibraryStatus.EMPTY, state.status)
        assertTrue(state.isEmpty)
        assertEquals(0, state.totalCount)
    }

    @Test
    fun populatedStoreWithNoMatchesIsReadyButFilteredEmpty() {
        val docs = listOf(document(1, "Invoice"))
        val state = buildDocumentLibraryState(docs, query = "nonexistent", sort = DocumentLibrarySort.NEWEST)
        assertEquals(DocumentLibraryStatus.READY, state.status)
        assertTrue(state.isFilteredEmpty)
        assertFalse(state.isEmpty)
        assertEquals(1, state.totalCount)
    }

    @Test
    fun loadingAndErrorTakePrecedenceOverContent() {
        val docs = listOf(document(1, "Invoice"))
        val loading = buildDocumentLibraryState(docs, query = "", sort = DocumentLibrarySort.NEWEST, isLoading = true)
        assertEquals(DocumentLibraryStatus.LOADING, loading.status)

        val error = buildDocumentLibraryState(
            docs, query = "", sort = DocumentLibrarySort.NEWEST, errorMessage = "boom"
        )
        assertEquals(DocumentLibraryStatus.ERROR, error.status)
        assertEquals("boom", error.errorMessage)
    }

    // --- Delete confirmation state ------------------------------------------

    @Test
    fun deleteConfirmationStateIsDeterministic() {
        val item = document(1, "Invoice").toLibraryItem()
        var dialogs = DocumentLibraryDialogs()
        assertFalse(dialogs.isDeleteConfirmVisible)

        dialogs = DocumentLibraryReducer.requestDelete(dialogs, item)
        assertTrue(dialogs.isDeleteConfirmVisible)
        assertEquals(item, dialogs.deleteTarget)
        assertNull(dialogs.renameTarget)

        // Requesting rename clears any pending delete, and vice-versa.
        dialogs = DocumentLibraryReducer.requestRename(dialogs, item)
        assertFalse(dialogs.isDeleteConfirmVisible)
        assertTrue(dialogs.isRenameVisible)

        dialogs = DocumentLibraryReducer.dismiss(dialogs)
        assertFalse(dialogs.isDeleteConfirmVisible)
        assertFalse(dialogs.isRenameVisible)
    }

    // --- Rename state validation --------------------------------------------

    @Test
    fun renameValidationMirrorsTitleConstraints() {
        assertTrue(validateLibraryTitle("   ") is LibraryTitleValidation.Invalid)
        assertTrue(validateLibraryTitle("a".repeat(MAX_LIBRARY_TITLE_LENGTH + 1)) is LibraryTitleValidation.Invalid)

        val valid = validateLibraryTitle("  Tax Return 2025  ")
        assertTrue(valid is LibraryTitleValidation.Valid)
        assertEquals("Tax Return 2025", (valid as LibraryTitleValidation.Valid).normalizedTitle)
    }

    // --- Favorite behavior is deterministic ---------------------------------

    @Test
    fun favoriteFlagMapsDeterministicallyFromEntity() {
        val favored = document(1, "Starred", isFavorite = true).toLibraryItem()
        val plain = document(2, "Plain", isFavorite = false).toLibraryItem()

        assertTrue(favored.isFavorite)
        assertFalse(plain.isFavorite)
        // Same input yields the same mapping every time.
        assertEquals(favored, document(1, "Starred", isFavorite = true).toLibraryItem())
    }

    // --- Item presentation: thumbnail / PDF indicator / routing -------------

    @Test
    fun imageDocumentsExposeThumbnailWhilePdfsShowPdfIndicator() {
        val pdf = document(1, "Report", localPdfUri = "file:///docs/report.pdf").toLibraryItem()
        assertTrue(pdf.hasGeneratedPdf)
        assertNull(pdf.thumbnailUri)

        val image = document(2, "Photo", localPdfUri = "file:///imported_images/photo.jpg").toLibraryItem()
        assertFalse(image.hasGeneratedPdf)
        assertEquals("file:///imported_images/photo.jpg", image.thumbnailUri)
    }

    @Test
    fun resultScreenEligibilityPrefersOcrOrImageOverPlainPdf() {
        assertTrue(isResultScreenEligible(document(1, "OCR pdf", extractedText = "hello")))
        assertTrue(isResultScreenEligible(document(2, "Image", localPdfUri = "file:///x/photo.png")))
        assertFalse(isResultScreenEligible(document(3, "Plain pdf", localPdfUri = "file:///x/plain.pdf")))
    }
}
