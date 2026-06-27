package com.dev.docscannerpdf.ui.pages

import com.dev.docscannerpdf.data.local.DocumentEntity

/**
 * Pure mapping between the stored [DocumentEntity] and the editor model. The current storage
 * layer keeps one file per document plus a page count (no per-page table), so pages are
 * derived from the document's real page count and source file — never invented.
 */

/**
 * Builds an editor state from a saved document. For a multi-page PDF the pages reference the
 * same source file at successive page indices; for an image document there is a single page.
 *
 * OCR is document-level in the current storage, so every derived page shares the document's
 * OCR availability rather than claiming distinct per-page OCR — an honest reflection of what
 * is actually stored. Export eligibility is true while the page has a renderable source.
 */
fun DocumentEntity.toMultiPageEditorState(): MultiPageEditorState {
    val isImage = isImageDocumentUri(localPdfUri)
    val count = pageCount.coerceAtLeast(1)
    val documentHasOcr = !extractedText.isNullOrBlank()
    val hasSource = localPdfUri.isNotBlank()

    val pages = (0 until count).map { index ->
        EditorPage(
            pageId = "${id}-p${index + 1}",
            order = index,
            sourceUri = localPdfUri.takeIf { it.isNotBlank() },
            sourcePageIndex = if (isImage) 0 else index,
            processedImageUrl = null,
            ocrAvailable = documentHasOcr,
            exportEligible = hasSource
        )
    }

    return MultiPageEditorState(
        documentId = id.toString(),
        title = title,
        pages = pages,
        selectedPageId = pages.firstOrNull()?.pageId,
        status = if (pages.isEmpty()) EditorStatus.EMPTY else EditorStatus.READY
    )
}

/**
 * Routing rule: a document opens in the multi-page editor when it has more than one page.
 * Single-page documents continue to route to the unified result screen / PDF viewer.
 */
fun shouldOpenMultiPageEditor(document: DocumentEntity): Boolean = document.pageCount > 1

/** Sorts pages by their declared order and renumbers them to match list position. */
fun sortEditorPages(pages: List<EditorPage>): List<EditorPage> =
    reindexPages(pages.sortedBy { it.order })

private fun isImageDocumentUri(uriValue: String): Boolean {
    val lower = uriValue.lowercase()
    return lower.endsWith(".jpg") ||
        lower.endsWith(".jpeg") ||
        lower.endsWith(".png") ||
        lower.endsWith(".webp") ||
        lower.contains("/imported_images/")
}
