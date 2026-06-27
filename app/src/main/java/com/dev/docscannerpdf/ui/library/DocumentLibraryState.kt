package com.dev.docscannerpdf.ui.library

/** Sort order offered by the document library. */
enum class DocumentLibrarySort(val label: String) {
    NEWEST("Newest"),
    OLDEST("Oldest"),
    NAME("Name")
}

/** Coarse render status of the document library, derived from the local Room store. */
enum class DocumentLibraryStatus {
    LOADING,
    EMPTY,
    READY,
    ERROR
}

/**
 * A single saved document as presented in the library. Every value is derived from the
 * existing [com.dev.docscannerpdf.data.local.DocumentEntity] — nothing here is fetched
 * from the backend, so the library renders fully offline.
 */
data class DocumentLibraryItem(
    val id: Long,
    val title: String,
    val pageCount: Int,
    val createdAt: Long,
    val snippet: String?,
    val hasGeneratedPdf: Boolean,
    val thumbnailUri: String?,
    val isFavorite: Boolean
) {
    val hasSnippet: Boolean get() = !snippet.isNullOrBlank()
    val hasThumbnail: Boolean get() = !thumbnailUri.isNullOrBlank()
}

/**
 * Immutable snapshot the [DocumentLibraryScreen] renders. [totalCount] is the unfiltered
 * document count so the UI can distinguish "no documents yet" (EMPTY) from "no search
 * matches" (READY with empty [items]).
 */
data class DocumentLibraryState(
    val status: DocumentLibraryStatus = DocumentLibraryStatus.LOADING,
    val items: List<DocumentLibraryItem> = emptyList(),
    val query: String = "",
    val sort: DocumentLibrarySort = DocumentLibrarySort.NEWEST,
    val totalCount: Int = 0,
    val errorMessage: String? = null
) {
    val isEmpty: Boolean get() = status == DocumentLibraryStatus.EMPTY
    val isError: Boolean get() = status == DocumentLibraryStatus.ERROR
    val isLoading: Boolean get() = status == DocumentLibraryStatus.LOADING

    /** True when documents exist but the active query matched none of them. */
    val isFilteredEmpty: Boolean
        get() = status == DocumentLibraryStatus.READY && items.isEmpty()
}

/**
 * Transient dialog state for the library's destructive/edit actions. Kept separate from
 * [DocumentLibraryState] (which mirrors the store) so confirmation flows are pure and
 * deterministic to test.
 */
data class DocumentLibraryDialogs(
    val deleteTarget: DocumentLibraryItem? = null,
    val renameTarget: DocumentLibraryItem? = null
) {
    val isDeleteConfirmVisible: Boolean get() = deleteTarget != null
    val isRenameVisible: Boolean get() = renameTarget != null
}

/** Pure transitions for the library dialog state. */
object DocumentLibraryReducer {
    fun requestDelete(state: DocumentLibraryDialogs, item: DocumentLibraryItem): DocumentLibraryDialogs =
        state.copy(deleteTarget = item, renameTarget = null)

    fun requestRename(state: DocumentLibraryDialogs, item: DocumentLibraryItem): DocumentLibraryDialogs =
        state.copy(renameTarget = item, deleteTarget = null)

    fun dismiss(state: DocumentLibraryDialogs): DocumentLibraryDialogs =
        state.copy(deleteTarget = null, renameTarget = null)
}

/** Result of validating a rename title before it is committed to the store. */
sealed interface LibraryTitleValidation {
    data class Valid(val normalizedTitle: String) : LibraryTitleValidation
    data class Invalid(val reason: String) : LibraryTitleValidation
}
