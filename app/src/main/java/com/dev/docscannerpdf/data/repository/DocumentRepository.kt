package com.dev.docscannerpdf.data.repository

import com.dev.docscannerpdf.data.local.DocumentDao
import com.dev.docscannerpdf.data.local.DocumentEntity
import com.dev.docscannerpdf.ui.cleanOcrText
import kotlinx.coroutines.flow.Flow

class DocumentRepository(private val dao: DocumentDao) {
    fun observeDocuments(): Flow<List<DocumentEntity>> = dao.observeDocuments()

    fun observeTrashDocuments(rawQuery: String): Flow<List<DocumentEntity>> {
        val query = rawQuery.trim()
        return dao.observeTrashDocuments(
            query = "%${escapeLikeQuery(query)}%",
            hasQuery = query.isNotBlank()
        )
    }

    fun observeSearchDocuments(
        folderId: Long?,
        selectedTagIds: List<Long>,
        rawQuery: String
    ): Flow<List<DocumentEntity>> {
        val query = rawQuery.trim()
        val tagIds = selectedTagIds.ifEmpty { listOf(NO_TAG_FILTER_ID) }
        return dao.observeSearchDocuments(
            folderId = folderId,
            selectedTagIds = tagIds,
            selectedTagCount = selectedTagIds.size,
            query = "%${escapeLikeQuery(query)}%",
            hasQuery = query.isNotBlank(),
            favoriteSearch = query.isFavoriteQuery(),
            pinnedSearch = query.isPinnedQuery()
        )
    }

    suspend fun saveDocument(document: DocumentEntity): Long {
        return dao.insert(document.withSearchableText())
    }

    suspend fun updateDocument(document: DocumentEntity) = dao.update(document.withSearchableText())

    suspend fun updateTitle(id: Long, title: String) {
        val document = dao.getById(id) ?: return
        dao.update(document.copy(title = title).withSearchableText())
    }

    suspend fun updateFolder(id: Long, folderId: Long?) {
        val document = dao.getById(id) ?: return
        dao.update(document.copy(folderId = folderId).withSearchableText())
    }

    suspend fun updateTags(id: Long, tags: String) {
        val document = dao.getById(id) ?: return
        dao.update(document.copy(tags = tags).withSearchableText())
    }

    suspend fun updateFavorite(id: Long, isFavorite: Boolean) {
        val document = dao.getById(id) ?: return
        dao.update(document.copy(isFavorite = isFavorite).withSearchableText())
    }

    suspend fun updatePinned(id: Long, isPinned: Boolean) {
        val document = dao.getById(id) ?: return
        dao.update(document.copy(isPinned = isPinned).withSearchableText())
    }

    suspend fun clearFolder(folderId: Long) = dao.clearFolder(folderId)

    suspend fun moveToTrash(id: Long) = dao.moveToTrash(id, System.currentTimeMillis())

    suspend fun restoreFromTrash(id: Long) = dao.restoreFromTrash(id)

    suspend fun permanentlyDeleteDocument(document: DocumentEntity) = dao.delete(document)

    suspend fun emptyTrash() = dao.emptyTrash()

    suspend fun cleanupExpiredTrash() {
        dao.deleteTrashOlderThan(System.currentTimeMillis() - TRASH_RETENTION_MS)
    }

    suspend fun getDocumentById(id: Long): DocumentEntity? = dao.getById(id)

    suspend fun rebuildSearchableText() {
        observeDocuments()
    }
}

private fun DocumentEntity.withSearchableText(): DocumentEntity {
    val favoriteToken = if (isFavorite) "favorite starred" else ""
    val pinnedToken = if (isPinned) "pinned important" else ""
    val ocrText = extractedText.orEmpty()
    return copy(
        searchableText = listOf(
            title,
            ocrText,
            cleanOcrText(ocrText),
            tags,
            favoriteToken,
            pinnedToken
        )
            .joinToString(separator = " ")
            .lowercase()
            .replace(Regex("\\s+"), " ")
            .trim()
    )
}

private fun escapeLikeQuery(query: String): String {
    return buildString {
        query.forEach { char ->
            when (char) {
                '\\', '%', '_' -> append('\\').append(char)
                else -> append(char)
            }
        }
    }
}

private fun String.isFavoriteQuery(): Boolean {
    val query = lowercase()
    return query == "favorite" || query == "favorites" || query == "starred"
}

private fun String.isPinnedQuery(): Boolean {
    val query = lowercase()
    return query == "pinned" || query == "pin" || query == "important"
}

private const val NO_TAG_FILTER_ID = -1L
private const val TRASH_RETENTION_MS = 30L * 24L * 60L * 60L * 1000L
