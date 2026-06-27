package com.dev.docscannerpdf.ui.pages

/**
 * Pure, framework-free transitions for the multi-page editor. Every function takes a state
 * and returns a new state with no side effects, so reorder/delete/duplicate/rotate behavior
 * — and especially the preservation of OCR/export metadata — is fully unit-testable on the
 * JVM, independent of the Compose [MultiPageDocumentEditorScreen].
 *
 * Edits here are applied to the in-memory model only; persisting page edits back to the
 * stored document is a later slice (the current storage layer has no per-page table).
 */
object MultiPageEditorReducer {

    /** Selects [pageId] if it exists; otherwise leaves the state unchanged. */
    fun select(state: MultiPageEditorState, pageId: String): MultiPageEditorState =
        if (state.pages.any { it.pageId == pageId }) state.copy(selectedPageId = pageId) else state

    fun movePageUp(state: MultiPageEditorState, pageId: String): MultiPageEditorState =
        movePage(state, pageId, -1)

    fun movePageDown(state: MultiPageEditorState, pageId: String): MultiPageEditorState =
        movePage(state, pageId, 1)

    /**
     * Swaps the page with its neighbor in [delta] direction and renumbers. A move past either
     * edge is a no-op. Page contents (OCR/export/rotation) are carried verbatim — only order
     * changes.
     */
    private fun movePage(
        state: MultiPageEditorState,
        pageId: String,
        delta: Int
    ): MultiPageEditorState {
        val index = state.pages.indexOfFirst { it.pageId == pageId }
        if (index < 0) return state
        val target = index + delta
        if (target !in state.pages.indices) return state
        val reordered = state.pages.toMutableList().apply {
            val moved = removeAt(index)
            add(target, moved)
        }
        return state.copy(pages = reindexPages(reordered))
    }

    /** Marks [pageId] for deletion, surfacing the confirmation dialog. */
    fun requestDelete(state: MultiPageEditorState, pageId: String): MultiPageEditorState =
        if (state.pages.any { it.pageId == pageId }) state.copy(pendingDeletePageId = pageId) else state

    fun cancelDelete(state: MultiPageEditorState): MultiPageEditorState =
        state.copy(pendingDeletePageId = null)

    /**
     * Removes the pending-delete page and renumbers. If the removed page was selected, the
     * selection falls back to the next page (or the previous one when the last page was
     * removed); removing the final page leaves an [EditorStatus.EMPTY] document.
     */
    fun confirmDelete(state: MultiPageEditorState): MultiPageEditorState {
        val pageId = state.pendingDeletePageId ?: return state
        val index = state.pages.indexOfFirst { it.pageId == pageId }
        if (index < 0) return state.copy(pendingDeletePageId = null)

        val remaining = reindexPages(state.pages.toMutableList().apply { removeAt(index) })
        val newSelection = when {
            state.selectedPageId != pageId -> state.selectedPageId
            remaining.isEmpty() -> null
            else -> remaining[index.coerceAtMost(remaining.lastIndex)].pageId
        }
        return state.copy(
            pages = remaining,
            selectedPageId = newSelection,
            pendingDeletePageId = null,
            status = if (remaining.isEmpty()) EditorStatus.EMPTY else EditorStatus.READY
        )
    }

    /**
     * Inserts a copy of [pageId] immediately after it, preserving its OCR availability, export
     * eligibility, rotation, and source — only the [EditorPage.pageId] differs. The new page
     * becomes the selection.
     */
    fun duplicatePage(state: MultiPageEditorState, pageId: String): MultiPageEditorState {
        val index = state.pages.indexOfFirst { it.pageId == pageId }
        if (index < 0) return state
        val original = state.pages[index]
        val newId = uniquePageId(state.pages.map { it.pageId }, "${original.pageId}-copy")
        val duplicate = original.copy(pageId = newId)
        val updated = state.pages.toMutableList().apply { add(index + 1, duplicate) }
        return state.copy(
            pages = reindexPages(updated),
            selectedPageId = newId,
            status = EditorStatus.READY
        )
    }

    /**
     * Rotates [pageId] by [delta] degrees (normalized to 0/90/180/270). This updates the
     * editor model only — the stored document is not rewritten in this slice.
     */
    fun rotatePage(
        state: MultiPageEditorState,
        pageId: String,
        delta: Int = 90
    ): MultiPageEditorState {
        if (state.pages.none { it.pageId == pageId }) return state
        val rotated = state.pages.map { page ->
            if (page.pageId == pageId) {
                page.copy(rotationDegrees = normalizeRotation(page.rotationDegrees + delta))
            } else {
                page
            }
        }
        return state.copy(pages = rotated)
    }

    private fun normalizeRotation(degrees: Int): Int = ((degrees % 360) + 360) % 360

    private fun uniquePageId(existing: List<String>, base: String): String {
        if (base !in existing) return base
        var suffix = 2
        while ("$base-$suffix" in existing) suffix++
        return "$base-$suffix"
    }
}
