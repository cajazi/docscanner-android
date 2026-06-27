package com.dev.docscannerpdf.ui.library

import com.dev.docscannerpdf.data.local.DocumentEntity
import com.dev.docscannerpdf.ui.result.DocumentResultState
import com.dev.docscannerpdf.ui.result.ResultLoadingState
import com.dev.docscannerpdf.ui.result.ResultOcrStatus

/** Maximum document title length, kept in sync with the rename flow in ScannerViewModel. */
const val MAX_LIBRARY_TITLE_LENGTH = 80

private const val MAX_SNIPPET_LENGTH = 140

/**
 * Pure, framework-free logic for the local-first document library: filtering, sorting,
 * snippet extraction, rename validation, and the routing decision for opening a document.
 * Keeping all of this off the Android framework lets every requirement be unit tested
 * directly on the JVM, independent of the Compose [DocumentLibraryScreen].
 */

/**
 * Folds the locally-stored [documents] into a [DocumentLibraryState] for [query]/[sort].
 * [isLoading] and [errorMessage] take precedence so the UI can surface those states; with
 * neither set, an empty store maps to [DocumentLibraryStatus.EMPTY] and a populated store
 * (even when the query matches nothing) maps to [DocumentLibraryStatus.READY].
 */
fun buildDocumentLibraryState(
    documents: List<DocumentEntity>,
    query: String,
    sort: DocumentLibrarySort,
    isLoading: Boolean = false,
    errorMessage: String? = null
): DocumentLibraryState {
    if (errorMessage != null) {
        return DocumentLibraryState(
            status = DocumentLibraryStatus.ERROR,
            query = query,
            sort = sort,
            totalCount = documents.size,
            errorMessage = errorMessage
        )
    }
    if (isLoading) {
        return DocumentLibraryState(
            status = DocumentLibraryStatus.LOADING,
            query = query,
            sort = sort,
            totalCount = documents.size
        )
    }
    if (documents.isEmpty()) {
        return DocumentLibraryState(
            status = DocumentLibraryStatus.EMPTY,
            query = query,
            sort = sort,
            totalCount = 0
        )
    }

    val normalizedQuery = query.trim().lowercase()
    val filtered = documents.filter { matchesLibraryQuery(it, normalizedQuery) }
    val sorted = sortLibraryDocuments(filtered, sort)
    return DocumentLibraryState(
        status = DocumentLibraryStatus.READY,
        items = sorted.map { it.toLibraryItem(normalizedQuery) },
        query = query,
        sort = sort,
        totalCount = documents.size
    )
}

/** Matches a document against a normalized (trimmed, lowercased) query across title + OCR. */
fun matchesLibraryQuery(document: DocumentEntity, normalizedQuery: String): Boolean {
    if (normalizedQuery.isEmpty()) return true
    return document.title.lowercase().contains(normalizedQuery) ||
        document.extractedText?.lowercase()?.contains(normalizedQuery) == true ||
        document.searchableText.lowercase().contains(normalizedQuery) ||
        document.tags.lowercase().contains(normalizedQuery)
}

/** Orders documents by the requested [sort]; NAME is case-insensitive, ties break on id. */
fun sortLibraryDocuments(
    documents: List<DocumentEntity>,
    sort: DocumentLibrarySort
): List<DocumentEntity> = when (sort) {
    DocumentLibrarySort.NEWEST -> documents.sortedWith(
        compareByDescending<DocumentEntity> { it.timestamp }.thenByDescending { it.id }
    )
    DocumentLibrarySort.OLDEST -> documents.sortedWith(
        compareBy<DocumentEntity> { it.timestamp }.thenBy { it.id }
    )
    DocumentLibrarySort.NAME -> documents.sortedWith(
        compareBy(String.CASE_INSENSITIVE_ORDER, DocumentEntity::title).thenBy { it.id }
    )
}

/** Builds a single-line OCR snippet, centered on the query match when one exists. */
fun buildLibrarySnippet(extractedText: String?, normalizedQuery: String): String? {
    val collapsed = extractedText
        ?.replace(Regex("\\s+"), " ")
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: return null

    if (collapsed.length <= MAX_SNIPPET_LENGTH) return collapsed

    val matchIndex = if (normalizedQuery.isNotEmpty()) {
        collapsed.lowercase().indexOf(normalizedQuery)
    } else {
        -1
    }
    if (matchIndex <= 0) {
        return collapsed.take(MAX_SNIPPET_LENGTH).trimEnd() + "…"
    }

    val start = (matchIndex - MAX_SNIPPET_LENGTH / 3).coerceAtLeast(0)
    val end = (start + MAX_SNIPPET_LENGTH).coerceAtMost(collapsed.length)
    val prefix = if (start > 0) "…" else ""
    val suffix = if (end < collapsed.length) "…" else ""
    return prefix + collapsed.substring(start, end).trim() + suffix
}

/** Maps a stored document into its library presentation, extracting a query-aware snippet. */
fun DocumentEntity.toLibraryItem(normalizedQuery: String = ""): DocumentLibraryItem {
    return DocumentLibraryItem(
        id = id,
        title = title,
        pageCount = pageCount,
        createdAt = timestamp,
        snippet = buildLibrarySnippet(extractedText, normalizedQuery),
        hasGeneratedPdf = isPdfDocumentUri(localPdfUri),
        thumbnailUri = localPdfUri.takeIf { isImageDocumentUri(it) },
        isFavorite = isFavorite
    )
}

/** Validates a rename title, mirroring the existing ScannerViewModel rename constraints. */
fun validateLibraryTitle(rawTitle: String): LibraryTitleValidation {
    val normalized = rawTitle.trim()
    return when {
        normalized.isBlank() -> LibraryTitleValidation.Invalid("Title cannot be blank.")
        normalized.length > MAX_LIBRARY_TITLE_LENGTH ->
            LibraryTitleValidation.Invalid("Title must be $MAX_LIBRARY_TITLE_LENGTH characters or fewer.")
        else -> LibraryTitleValidation.Valid(normalized)
    }
}

/**
 * Routing decision for opening a document: the unified result screen is preferred when the
 * document carries OCR text or an image preview to show; otherwise the caller falls back to
 * the existing PDF viewer. Pure so the routing rule is unit-testable.
 */
fun isResultScreenEligible(document: DocumentEntity): Boolean =
    !document.extractedText.isNullOrBlank() || isImageDocumentUri(document.localPdfUri)

/**
 * Builds a [DocumentResultState] for a saved document so the existing unified result screen
 * can render it. Only real stored values are used — no backend identifiers are fabricated.
 */
fun DocumentEntity.toLibraryResultState(): DocumentResultState {
    val hasText = !extractedText.isNullOrBlank()
    return DocumentResultState(
        documentId = id.toString(),
        localPreviewUri = localPdfUri.takeIf { isImageDocumentUri(it) },
        ocrText = extractedText,
        ocrStatus = if (hasText) ResultOcrStatus.AVAILABLE else ResultOcrStatus.EMPTY,
        processingStatus = "Saved document",
        loadingState = ResultLoadingState.READY
    )
}

private fun isImageDocumentUri(uriValue: String): Boolean {
    val lower = uriValue.lowercase()
    return lower.endsWith(".jpg") ||
        lower.endsWith(".jpeg") ||
        lower.endsWith(".png") ||
        lower.endsWith(".webp") ||
        lower.contains("/imported_images/")
}

private fun isPdfDocumentUri(uriValue: String): Boolean =
    uriValue.lowercase().endsWith(".pdf")
