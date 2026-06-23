package com.dev.docscannerpdf.data.repository

import androidx.room.withTransaction
import com.dev.docscannerpdf.data.local.AppDatabase
import com.dev.docscannerpdf.data.local.DocumentDao
import com.dev.docscannerpdf.data.local.DocumentEntity
import com.dev.docscannerpdf.data.local.DocumentTagCrossRef
import com.dev.docscannerpdf.data.local.TagDao
import com.dev.docscannerpdf.data.local.TagEntity
import com.dev.docscannerpdf.data.local.TagWithCount
import com.dev.docscannerpdf.ui.cleanOcrText
import kotlinx.coroutines.flow.Flow

class TagRepository(
    private val db: AppDatabase,
    private val tagDao: TagDao,
    private val documentDao: DocumentDao
) {
    fun observeTagsWithCounts(): Flow<List<TagWithCount>> = tagDao.observeTagsWithCounts()

    suspend fun ensureDefaultTags() {
        DefaultTags.forEachIndexed { index, name ->
            if (tagDao.getByName(name) == null) {
                tagDao.insert(
                    TagEntity(
                        name = name,
                        createdAt = System.currentTimeMillis(),
                        isDefault = true,
                        sortOrder = index + 1
                    )
                )
            }
        }
    }

    suspend fun createTag(name: String): Long {
        val normalizedName = name.normalizeTagName()
        val existing = tagDao.getByName(normalizedName)
        if (existing != null) return existing.id
        return tagDao.insert(
            TagEntity(
                name = normalizedName,
                createdAt = System.currentTimeMillis(),
                isDefault = false,
                sortOrder = CUSTOM_TAG_SORT_ORDER
            )
        )
    }

    suspend fun setDocumentTags(documentId: Long, tagNames: List<String>) {
        db.withTransaction {
            val tags = tagNames
                .mapNotNull { tag -> tag.normalizeTagNameOrNull() }
                .distinctBy { tag -> tag.lowercase() }
                .map { tagName -> resolveTag(tagName) }

            tagDao.deleteCrossRefsForDocument(documentId)
            tagDao.insertCrossRefs(tags.map { tag -> DocumentTagCrossRef(documentId, tag.id) })
            syncDocumentTagCache(documentId, tags.map { tag -> tag.name })
        }
    }

    suspend fun addTagToDocument(documentId: Long, tagName: String) {
        val currentNames = tagDao.getTagNamesForDocument(documentId)
        setDocumentTags(documentId, currentNames + tagName)
    }

    suspend fun removeTagFromDocument(documentId: Long, tagId: Long) {
        val tagName = tagDao.getById(tagId)?.name ?: return
        val currentNames = tagDao.getTagNamesForDocument(documentId)
        setDocumentTags(
            documentId = documentId,
            tagNames = currentNames.filterNot { name -> name.equals(tagName, ignoreCase = true) }
        )
    }

    suspend fun renameTag(tagId: Long, name: String) {
        val normalizedName = name.normalizeTagName()
        db.withTransaction {
            val tag = tagDao.getById(tagId) ?: return@withTransaction
            val existing = tagDao.getByName(normalizedName)
            if (existing != null && existing.id != tagId) {
                throw IllegalArgumentException("A tag named $normalizedName already exists.")
            }

            val documentIds = tagDao.getDocumentIdsForTag(tagId)
            tagDao.updateName(tag.id, normalizedName)
            documentIds.forEach { documentId -> syncDocumentTagCache(documentId) }
        }
    }

    suspend fun deleteTag(tagId: Long) {
        db.withTransaction {
            val tag = tagDao.getById(tagId) ?: return@withTransaction
            val documentIds = tagDao.getDocumentIdsForTag(tagId)
            tagDao.delete(tag)
            documentIds.forEach { documentId -> syncDocumentTagCache(documentId) }
        }
    }

    private suspend fun resolveTag(name: String): TagEntity {
        val existing = tagDao.getByName(name)
        if (existing != null) return existing

        val tagId = tagDao.insert(
            TagEntity(
                name = name,
                createdAt = System.currentTimeMillis(),
                isDefault = false,
                sortOrder = CUSTOM_TAG_SORT_ORDER
            )
        )
        return tagDao.getById(tagId) ?: TagEntity(
            id = tagId,
            name = name,
            createdAt = System.currentTimeMillis(),
            isDefault = false,
            sortOrder = CUSTOM_TAG_SORT_ORDER
        )
    }

    private suspend fun syncDocumentTagCache(documentId: Long, names: List<String>? = null) {
        val document = documentDao.getById(documentId) ?: return
        val tagText = (names ?: tagDao.getTagNamesForDocument(documentId))
            .joinToString(separator = ", ")
        documentDao.update(document.copy(tags = tagText).withSearchableText())
    }

    companion object {
        val DefaultTags = listOf("Work", "Receipt", "Invoice", "School", "Personal", "Medical")
        private const val CUSTOM_TAG_SORT_ORDER = 100
    }
}

private fun String.normalizeTagName(): String {
    return normalizeTagNameOrNull() ?: throw IllegalArgumentException("Tag name cannot be blank.")
}

private fun String.normalizeTagNameOrNull(): String? {
    return trim()
        .trimStart('#')
        .replace(Regex("\\s+"), " ")
        .takeIf { tag -> tag.isNotBlank() }
        ?.take(40)
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
