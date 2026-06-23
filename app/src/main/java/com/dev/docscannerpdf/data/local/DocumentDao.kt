package com.dev.docscannerpdf.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: DocumentEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(documents: List<DocumentEntity>)

    @Delete
    suspend fun delete(document: DocumentEntity)

    @Update
    suspend fun update(document: DocumentEntity)

    @Query("UPDATE documents SET isDeleted = 1, deletedAt = :deletedAt WHERE id = :id")
    suspend fun moveToTrash(id: Long, deletedAt: Long)

    @Query("UPDATE documents SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restoreFromTrash(id: Long)

    @Query("DELETE FROM documents WHERE isDeleted = 1 AND deletedAt IS NOT NULL AND deletedAt < :cutoff")
    suspend fun deleteTrashOlderThan(cutoff: Long)

    @Query("DELETE FROM documents WHERE isDeleted = 1")
    suspend fun emptyTrash()

    @Query("DELETE FROM documents")
    suspend fun deleteAll()

    @Query("UPDATE documents SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String)

    @Query("UPDATE documents SET folderId = :folderId WHERE id = :id")
    suspend fun updateFolder(id: Long, folderId: Long?)

    @Query("UPDATE documents SET tags = :tags WHERE id = :id")
    suspend fun updateTags(id: Long, tags: String)

    @Query("UPDATE documents SET searchableText = :searchableText WHERE id = :id")
    suspend fun updateSearchableText(id: Long, searchableText: String)

    @Query("UPDATE documents SET folderId = NULL WHERE folderId = :folderId")
    suspend fun clearFolder(folderId: Long)

    @Query("SELECT * FROM documents WHERE isDeleted = 0 ORDER BY isPinned DESC, isFavorite DESC, timestamp DESC")
    fun observeDocuments(): Flow<List<DocumentEntity>>

    @Query(
        """
        SELECT documents.* FROM documents
        LEFT JOIN folders ON folders.id = documents.folderId
        LEFT JOIN document_tag_cross_ref AS doc_tags ON doc_tags.documentId = documents.id
        LEFT JOIN tags ON tags.id = doc_tags.tagId
        WHERE documents.isDeleted = 0
            AND (:folderId IS NULL OR documents.folderId = :folderId)
            AND (
                :selectedTagCount = 0
                OR documents.id IN (
                    SELECT documentId
                    FROM document_tag_cross_ref
                    WHERE tagId IN (:selectedTagIds)
                    GROUP BY documentId
                    HAVING COUNT(DISTINCT tagId) = :selectedTagCount
                )
            )
            AND (
                :hasQuery = 0
                OR documents.searchableText LIKE :query ESCAPE '\'
                OR documents.title LIKE :query ESCAPE '\'
                OR documents.extractedText LIKE :query ESCAPE '\'
                OR documents.tags LIKE :query ESCAPE '\'
                OR tags.name LIKE :query ESCAPE '\'
                OR folders.name LIKE :query ESCAPE '\'
                OR (:favoriteSearch = 1 AND documents.isFavorite = 1)
                OR (:pinnedSearch = 1 AND documents.isPinned = 1)
            )
        GROUP BY documents.id
        ORDER BY documents.isPinned DESC, documents.isFavorite DESC, documents.timestamp DESC
        """
    )
    fun observeSearchDocuments(
        folderId: Long?,
        selectedTagIds: List<Long>,
        selectedTagCount: Int,
        query: String,
        hasQuery: Boolean,
        favoriteSearch: Boolean,
        pinnedSearch: Boolean
    ): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE isDeleted = 0 AND isFavorite = 1 ORDER BY isPinned DESC, timestamp DESC")
    fun observeFavoriteDocuments(): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE isDeleted = 0 AND isPinned = 1 ORDER BY isFavorite DESC, timestamp DESC")
    fun observePinnedDocuments(): Flow<List<DocumentEntity>>

    @Query(
        """
        SELECT * FROM documents
        WHERE isDeleted = 1
            AND (
                :hasQuery = 0
                OR searchableText LIKE :query ESCAPE '\'
                OR title LIKE :query ESCAPE '\'
                OR extractedText LIKE :query ESCAPE '\'
                OR tags LIKE :query ESCAPE '\'
            )
        ORDER BY deletedAt DESC, timestamp DESC
        """
    )
    fun observeTrashDocuments(
        query: String,
        hasQuery: Boolean
    ): Flow<List<DocumentEntity>>

    @Query("SELECT * FROM documents WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): DocumentEntity?

    @Query("SELECT * FROM documents ORDER BY id ASC")
    suspend fun getAllForBackup(): List<DocumentEntity>
}
