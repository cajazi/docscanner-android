package com.dev.docscannerpdf.ui.pages

/** Coarse render status of the multi-page editor. */
enum class EditorStatus {
    LOADING,
    EMPTY,
    READY,
    ERROR
}

/**
 * A single editable page. Every field is sourced from real document data — the local source
 * file/page index, the optional backend processed image, and whether OCR exists for the
 * document the page belongs to. Nothing is fabricated: [processedImageUrl] stays null when
 * the backend exposed none, and [ocrAvailable] reflects real OCR presence.
 *
 * [rotationDegrees] is editor-local state; the current storage layer has no per-page
 * persistence, so rotation is applied to the in-memory model only (see [MultiPageEditorReducer]).
 */
data class EditorPage(
    val pageId: String,
    val order: Int,
    val sourceUri: String?,
    val sourcePageIndex: Int = 0,
    val processedImageUrl: String? = null,
    val ocrAvailable: Boolean = false,
    val exportEligible: Boolean = false,
    val rotationDegrees: Int = 0
) {
    /** 1-based page number derived from [order]. */
    val pageNumber: Int get() = order + 1

    /** Preferred image to render: backend processed image, else the local source. */
    val thumbnailUri: String? get() = processedImageUrl ?: sourceUri
}

/**
 * Immutable snapshot the [MultiPageDocumentEditorScreen] renders. Reorder is expressed by the
 * ordered [pages] list; delete confirmation by [pendingDeletePageId]; duplicate/rotate by the
 * pages themselves. All transitions go through [MultiPageEditorReducer] so they stay pure and
 * deterministic.
 */
data class MultiPageEditorState(
    val documentId: String? = null,
    val title: String = "",
    val pages: List<EditorPage> = emptyList(),
    val selectedPageId: String? = null,
    val status: EditorStatus = EditorStatus.LOADING,
    val pendingDeletePageId: String? = null,
    val errorMessage: String? = null
) {
    val pageCount: Int get() = pages.size
    val selectedPage: EditorPage? get() = pages.firstOrNull { it.pageId == selectedPageId }
    val isDeleteConfirmVisible: Boolean get() = pendingDeletePageId != null
    val isEmpty: Boolean get() = status == EditorStatus.EMPTY
    val isError: Boolean get() = status == EditorStatus.ERROR
    val isReady: Boolean get() = status == EditorStatus.READY

    /** The page targeted by a pending delete confirmation, if any. */
    val pendingDeletePage: EditorPage?
        get() = pendingDeletePageId?.let { id -> pages.firstOrNull { it.pageId == id } }
}

/** Renumbers [pages] to match list position, leaving unchanged pages untouched. */
internal fun reindexPages(pages: List<EditorPage>): List<EditorPage> =
    pages.mapIndexed { index, page -> if (page.order == index) page else page.copy(order = index) }
