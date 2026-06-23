package com.dev.docscannerpdf.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tags: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRefs(crossRefs: List<DocumentTagCrossRef>)

    @Query(
        """
        SELECT tags.id, tags.name, tags.createdAt, tags.isDefault, tags.sortOrder,
            COUNT(documents.id) AS documentCount
        FROM tags
        LEFT JOIN document_tag_cross_ref ON document_tag_cross_ref.tagId = tags.id
        LEFT JOIN documents ON documents.id = document_tag_cross_ref.documentId AND documents.isDeleted = 0
        GROUP BY tags.id
        ORDER BY tags.sortOrder ASC, tags.name COLLATE NOCASE ASC
        """
    )
    fun observeTagsWithCounts(): Flow<List<TagWithCount>>

    @Query("SELECT * FROM tags WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): TagEntity?

    @Query("SELECT * FROM tags WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun getByName(name: String): TagEntity?

    @Query("SELECT name FROM tags WHERE id IN (:ids)")
    suspend fun getNamesByIds(ids: List<Long>): List<String>

    @Query(
        """
        SELECT tags.name FROM tags
        INNER JOIN document_tag_cross_ref ON document_tag_cross_ref.tagId = tags.id
        WHERE document_tag_cross_ref.documentId = :documentId
        ORDER BY tags.name COLLATE NOCASE ASC
        """
    )
    suspend fun getTagNamesForDocument(documentId: Long): List<String>

    @Query("SELECT documentId FROM document_tag_cross_ref WHERE tagId = :tagId")
    suspend fun getDocumentIdsForTag(tagId: Long): List<Long>

    @Query("DELETE FROM document_tag_cross_ref WHERE documentId = :documentId")
    suspend fun deleteCrossRefsForDocument(documentId: Long)

    @Query("UPDATE tags SET name = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String)

    @Delete
    suspend fun delete(tag: TagEntity)

    @Query("DELETE FROM document_tag_cross_ref")
    suspend fun deleteAllCrossRefs()

    @Query("DELETE FROM tags")
    suspend fun deleteAll()

    @Query("SELECT * FROM tags ORDER BY id ASC")
    suspend fun getAllForBackup(): List<TagEntity>

    @Query("SELECT * FROM document_tag_cross_ref ORDER BY documentId ASC, tagId ASC")
    suspend fun getAllCrossRefsForBackup(): List<DocumentTagCrossRef>
}
