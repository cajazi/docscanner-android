package com.dev.docscannerpdf.ui.pages

import com.dev.docscannerpdf.data.local.DocumentEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiPageEditorReducerTest {

    private fun page(
        id: String,
        order: Int,
        ocr: Boolean = false,
        export: Boolean = true,
        rotation: Int = 0
    ) = EditorPage(
        pageId = id,
        order = order,
        sourceUri = "file:///docs/doc.pdf",
        sourcePageIndex = order,
        ocrAvailable = ocr,
        exportEligible = export,
        rotationDegrees = rotation
    )

    private fun state(vararg pages: EditorPage, selected: String? = pages.firstOrNull()?.pageId) =
        MultiPageEditorState(
            documentId = "1",
            title = "Doc",
            pages = pages.toList(),
            selectedPageId = selected,
            status = if (pages.isEmpty()) EditorStatus.EMPTY else EditorStatus.READY
        )

    // --- Page order sorting -------------------------------------------------

    @Test
    fun sortEditorPagesOrdersAndRenumbers() {
        val shuffled = listOf(page("c", 2), page("a", 0), page("b", 1))
        val sorted = sortEditorPages(shuffled)
        assertEquals(listOf("a", "b", "c"), sorted.map { it.pageId })
        assertEquals(listOf(0, 1, 2), sorted.map { it.order })
    }

    @Test
    fun mapperDerivesPagesFromDocumentPageCount() {
        val document = DocumentEntity(
            id = 7,
            title = "Contract",
            timestamp = 1,
            pageCount = 3,
            localPdfUri = "file:///docs/7.pdf",
            extractedText = "signed agreement"
        )
        val mapped = document.toMultiPageEditorState()

        assertEquals(EditorStatus.READY, mapped.status)
        assertEquals(3, mapped.pageCount)
        assertEquals(listOf(0, 1, 2), mapped.pages.map { it.sourcePageIndex })
        assertEquals("7-p1", mapped.selectedPageId)
        // Document-level OCR is reflected on every derived page (no per-page OCR is invented).
        assertTrue(mapped.pages.all { it.ocrAvailable })
        assertTrue(mapped.pages.all { it.exportEligible })
    }

    @Test
    fun routingPrefersEditorOnlyForMultiPageDocuments() {
        val multi = DocumentEntity(id = 1, title = "A", timestamp = 1, pageCount = 4, localPdfUri = "a.pdf")
        val single = DocumentEntity(id = 2, title = "B", timestamp = 1, pageCount = 1, localPdfUri = "b.pdf")
        assertTrue(shouldOpenMultiPageEditor(multi))
        assertFalse(shouldOpenMultiPageEditor(single))
    }

    // --- Move page up/down --------------------------------------------------

    @Test
    fun movePageUpAndDownReordersAndRenumbers() {
        val initial = state(page("a", 0), page("b", 1), page("c", 2))

        val movedDown = MultiPageEditorReducer.movePageDown(initial, "a")
        assertEquals(listOf("b", "a", "c"), movedDown.pages.map { it.pageId })
        assertEquals(listOf(0, 1, 2), movedDown.pages.map { it.order })

        val movedUp = MultiPageEditorReducer.movePageUp(movedDown, "a")
        assertEquals(listOf("a", "b", "c"), movedUp.pages.map { it.pageId })
    }

    @Test
    fun moveAtEdgesIsNoOp() {
        val initial = state(page("a", 0), page("b", 1))
        assertEquals(initial, MultiPageEditorReducer.movePageUp(initial, "a"))
        assertEquals(initial, MultiPageEditorReducer.movePageDown(initial, "b"))
    }

    // --- Export eligibility & OCR preserved after reorder -------------------

    @Test
    fun reorderPreservesExportAndOcrMetadata() {
        val initial = state(
            page("a", 0, ocr = true, export = true, rotation = 90),
            page("b", 1, ocr = false, export = false)
        )
        val moved = MultiPageEditorReducer.movePageDown(initial, "a")

        val a = moved.pages.first { it.pageId == "a" }
        assertTrue(a.ocrAvailable)
        assertTrue(a.exportEligible)
        assertEquals(90, a.rotationDegrees)
        val b = moved.pages.first { it.pageId == "b" }
        assertFalse(b.ocrAvailable)
        assertFalse(b.exportEligible)
    }

    // --- Delete confirmation ------------------------------------------------

    @Test
    fun deleteConfirmationFlowIsDeterministic() {
        val initial = state(page("a", 0), page("b", 1))

        val requested = MultiPageEditorReducer.requestDelete(initial, "b")
        assertTrue(requested.isDeleteConfirmVisible)
        assertEquals("b", requested.pendingDeletePage?.pageId)

        val cancelled = MultiPageEditorReducer.cancelDelete(requested)
        assertFalse(cancelled.isDeleteConfirmVisible)
        assertEquals(2, cancelled.pageCount)

        val confirmed = MultiPageEditorReducer.confirmDelete(requested)
        assertFalse(confirmed.isDeleteConfirmVisible)
        assertEquals(listOf("a"), confirmed.pages.map { it.pageId })
    }

    // --- Selected page fallback after delete --------------------------------

    @Test
    fun deletingSelectedSelectsNextThenPrevious() {
        val initial = state(page("a", 0), page("b", 1), page("c", 2), selected = "b")
        val afterDeleteB = MultiPageEditorReducer.confirmDelete(
            MultiPageEditorReducer.requestDelete(initial, "b")
        )
        // Falls back to the page that takes b's index (the former "c").
        assertEquals("c", afterDeleteB.selectedPageId)

        val afterDeleteLast = MultiPageEditorReducer.confirmDelete(
            MultiPageEditorReducer.requestDelete(afterDeleteB, "c")
        )
        // No next page, so selection falls back to the previous page.
        assertEquals("a", afterDeleteLast.selectedPageId)
    }

    @Test
    fun deletingUnselectedKeepsSelection() {
        val initial = state(page("a", 0), page("b", 1), selected = "a")
        val result = MultiPageEditorReducer.confirmDelete(
            MultiPageEditorReducer.requestDelete(initial, "b")
        )
        assertEquals("a", result.selectedPageId)
    }

    // --- Empty document state -----------------------------------------------

    @Test
    fun deletingLastPageYieldsEmptyState() {
        val initial = state(page("a", 0), selected = "a")
        val result = MultiPageEditorReducer.confirmDelete(
            MultiPageEditorReducer.requestDelete(initial, "a")
        )
        assertEquals(EditorStatus.EMPTY, result.status)
        assertTrue(result.pages.isEmpty())
        assertNull(result.selectedPageId)
    }

    // --- Duplicate page behavior --------------------------------------------

    @Test
    fun duplicateInsertsAdjacentCopyPreservingMetadata() {
        val initial = state(
            page("a", 0, ocr = true, export = true, rotation = 180),
            page("b", 1)
        )
        val result = MultiPageEditorReducer.duplicatePage(initial, "a")

        assertEquals(3, result.pageCount)
        assertEquals("a-copy", result.pages[1].pageId)
        assertEquals(listOf("a", "a-copy", "b"), result.pages.map { it.pageId })
        assertEquals(listOf(0, 1, 2), result.pages.map { it.order })

        val copy = result.pages[1]
        assertTrue(copy.ocrAvailable)
        assertTrue(copy.exportEligible)
        assertEquals(180, copy.rotationDegrees)
        // The new copy becomes the selection.
        assertEquals("a-copy", result.selectedPageId)
    }

    @Test
    fun duplicateGeneratesUniqueIdsWhenRepeated() {
        val initial = state(page("a", 0))
        val once = MultiPageEditorReducer.duplicatePage(initial, "a")
        val twice = MultiPageEditorReducer.duplicatePage(once, "a")

        val ids = twice.pages.map { it.pageId }
        assertEquals(ids.toSet().size, ids.size) // all unique
        assertTrue(ids.contains("a-copy"))
        assertTrue(ids.contains("a-copy-2"))
    }

    // --- Rotate (in-memory placeholder) -------------------------------------

    @Test
    fun rotateNormalizesAndPreservesOtherPages() {
        val initial = state(page("a", 0, ocr = true, rotation = 270), page("b", 1))
        val rotated = MultiPageEditorReducer.rotatePage(initial, "a")

        val a = rotated.pages.first { it.pageId == "a" }
        assertEquals(0, a.rotationDegrees) // 270 + 90 normalized to 0
        assertTrue(a.ocrAvailable)
        // Other pages untouched.
        assertEquals(initial.pages[1], rotated.pages[1])
    }

    @Test
    fun selectIgnoresUnknownPage() {
        val initial = state(page("a", 0), selected = "a")
        assertEquals(initial, MultiPageEditorReducer.select(initial, "missing"))
        assertEquals("a", MultiPageEditorReducer.select(initial, "a").selectedPageId)
    }
}
