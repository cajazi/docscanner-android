package com.dev.docscannerpdf.domain.backup

import android.content.Context
import androidx.room.withTransaction
import com.dev.docscannerpdf.data.local.AppDatabase
import com.dev.docscannerpdf.data.local.DocumentEntity
import com.dev.docscannerpdf.data.local.DocumentTagCrossRef
import com.dev.docscannerpdf.data.local.FolderEntity
import com.dev.docscannerpdf.data.local.TagEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class BackupSummary(
    val schemaVersion: Int,
    val createdAt: Long,
    val documentCount: Int,
    val folderCount: Int,
    val tagCount: Int,
    val favoriteCount: Int,
    val pinnedCount: Int
)

data class LastBackupInfo(
    val createdAt: Long,
    val documentCount: Int,
    val folderCount: Int,
    val tagCount: Int
)

data class BackupArchive(
    val summary: BackupSummary,
    val documents: List<DocumentEntity>,
    val folders: List<FolderEntity>,
    val tags: List<TagEntity>,
    val crossRefs: List<DocumentTagCrossRef>
)

class BackupRepository(
    context: Context,
    private val db: AppDatabase
) {
    private val preferences = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getLastBackupInfo(): LastBackupInfo? {
        val createdAt = preferences.getLong(KEY_LAST_BACKUP_AT, 0L)
        if (createdAt == 0L) return null
        return LastBackupInfo(
            createdAt = createdAt,
            documentCount = preferences.getInt(KEY_LAST_DOCUMENTS, 0),
            folderCount = preferences.getInt(KEY_LAST_FOLDERS, 0),
            tagCount = preferences.getInt(KEY_LAST_TAGS, 0)
        )
    }

    suspend fun createBackupZip(): Pair<ByteArray, BackupSummary> = withContext(Dispatchers.IO) {
        val archive = db.withTransaction {
            val documents = db.documentDao().getAllForBackup()
            val folders = db.folderDao().getAllForBackup()
            val tags = db.tagDao().getAllForBackup()
            val crossRefs = db.tagDao().getAllCrossRefsForBackup()
            val summary = BackupSummary(
                schemaVersion = SCHEMA_VERSION,
                createdAt = System.currentTimeMillis(),
                documentCount = documents.size,
                folderCount = folders.size,
                tagCount = tags.size,
                favoriteCount = documents.count { document -> document.isFavorite },
                pinnedCount = documents.count { document -> document.isPinned }
            )
            BackupArchive(summary, documents, folders, tags, crossRefs)
        }
        val json = archive.toJson().toString()
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip ->
                zip.putNextEntry(ZipEntry(BACKUP_JSON_NAME))
                zip.write(json.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            output.toByteArray()
        }
        bytes to archive.summary
    }

    fun markBackupCreated(summary: BackupSummary) {
        preferences.edit()
            .putLong(KEY_LAST_BACKUP_AT, summary.createdAt)
            .putInt(KEY_LAST_DOCUMENTS, summary.documentCount)
            .putInt(KEY_LAST_FOLDERS, summary.folderCount)
            .putInt(KEY_LAST_TAGS, summary.tagCount)
            .apply()
    }

    suspend fun parseBackup(bytes: ByteArray): BackupArchive = withContext(Dispatchers.IO) {
        val jsonText = extractJson(bytes)
        val archive = JSONObject(jsonText).toBackupArchive()
        validateArchive(archive)
        archive
    }

    suspend fun restoreBackup(archive: BackupArchive) = withContext(Dispatchers.IO) {
        validateArchive(archive)
        db.withTransaction {
            db.tagDao().deleteAllCrossRefs()
            db.documentDao().deleteAll()
            db.folderDao().deleteAll()
            db.tagDao().deleteAll()
            db.folderDao().insertAll(archive.folders)
            db.tagDao().insertAll(archive.tags)
            db.documentDao().insertAll(archive.documents)
            db.tagDao().insertCrossRefs(archive.crossRefs)
        }
    }

    private fun extractJson(bytes: ByteArray): String {
        val rawText = bytes.toString(Charsets.UTF_8).trimStart()
        if (rawText.startsWith("{")) return rawText

        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name == BACKUP_JSON_NAME) {
                    return zip.readBytes().toString(Charsets.UTF_8)
                }
                entry = zip.nextEntry
            }
        }
        throw IllegalArgumentException("Backup file is missing $BACKUP_JSON_NAME.")
    }

    private fun validateArchive(archive: BackupArchive) {
        require(archive.summary.schemaVersion == SCHEMA_VERSION) { "Unsupported backup version." }
        require(archive.documents.size == archive.summary.documentCount) { "Document count mismatch." }
        require(archive.folders.size == archive.summary.folderCount) { "Folder count mismatch." }
        require(archive.tags.size == archive.summary.tagCount) { "Tag count mismatch." }

        val documentIds = archive.documents.map { document -> document.id }.toSet()
        val folderIds = archive.folders.map { folder -> folder.id }.toSet()
        val tagIds = archive.tags.map { tag -> tag.id }.toSet()

        require(documentIds.size == archive.documents.size) { "Duplicate document IDs found." }
        require(folderIds.size == archive.folders.size) { "Duplicate folder IDs found." }
        require(tagIds.size == archive.tags.size) { "Duplicate tag IDs found." }

        archive.documents.forEach { document ->
            require(document.id > 0L) { "Invalid document ID." }
            require(document.title.isNotBlank()) { "A document title is missing." }
            require(document.localPdfUri.isNotBlank()) { "A document file reference is missing." }
            require(document.folderId == null || document.folderId in folderIds) { "Document references a missing folder." }
        }
        archive.crossRefs.forEach { ref ->
            require(ref.documentId in documentIds) { "Tag relationship references a missing document." }
            require(ref.tagId in tagIds) { "Tag relationship references a missing tag." }
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val BACKUP_JSON_NAME = "backup.json"

        private const val PREFS_NAME = "backup_restore"
        private const val KEY_LAST_BACKUP_AT = "last_backup_at"
        private const val KEY_LAST_DOCUMENTS = "last_documents"
        private const val KEY_LAST_FOLDERS = "last_folders"
        private const val KEY_LAST_TAGS = "last_tags"
    }
}

private fun BackupArchive.toJson(): JSONObject {
    return JSONObject()
        .put("schemaVersion", summary.schemaVersion)
        .put("createdAt", summary.createdAt)
        .put("documents", JSONArray().also { array -> documents.forEach { array.put(it.toJson()) } })
        .put("folders", JSONArray().also { array -> folders.forEach { array.put(it.toJson()) } })
        .put("tags", JSONArray().also { array -> tags.forEach { array.put(it.toJson()) } })
        .put("documentTagCrossRefs", JSONArray().also { array -> crossRefs.forEach { array.put(it.toJson()) } })
}

private fun JSONObject.toBackupArchive(): BackupArchive {
    val documents = getJSONArray("documents").mapObjects { it.toDocumentEntity() }
    val folders = getJSONArray("folders").mapObjects { it.toFolderEntity() }
    val tags = getJSONArray("tags").mapObjects { it.toTagEntity() }
    val crossRefs = getJSONArray("documentTagCrossRefs").mapObjects { it.toDocumentTagCrossRef() }
    return BackupArchive(
        summary = BackupSummary(
            schemaVersion = getInt("schemaVersion"),
            createdAt = getLong("createdAt"),
            documentCount = documents.size,
            folderCount = folders.size,
            tagCount = tags.size,
            favoriteCount = documents.count { document -> document.isFavorite },
            pinnedCount = documents.count { document -> document.isPinned }
        ),
        documents = documents,
        folders = folders,
        tags = tags,
        crossRefs = crossRefs
    )
}

private fun DocumentEntity.toJson(): JSONObject {
    return JSONObject()
        .put("id", id)
        .put("title", title)
        .put("timestamp", timestamp)
        .put("pageCount", pageCount)
        .put("localPdfUri", localPdfUri)
        .put("extractedText", extractedText)
        .put("folderId", folderId)
        .put("isFavorite", isFavorite)
        .put("isPinned", isPinned)
        .put("tags", tags)
        .put("searchableText", searchableText)
        .put("isDeleted", isDeleted)
        .put("deletedAt", deletedAt)
}

private fun FolderEntity.toJson(): JSONObject {
    return JSONObject()
        .put("id", id)
        .put("name", name)
        .put("createdAt", createdAt)
        .put("isDefault", isDefault)
        .put("sortOrder", sortOrder)
}

private fun TagEntity.toJson(): JSONObject {
    return JSONObject()
        .put("id", id)
        .put("name", name)
        .put("createdAt", createdAt)
        .put("isDefault", isDefault)
        .put("sortOrder", sortOrder)
}

private fun DocumentTagCrossRef.toJson(): JSONObject {
    return JSONObject()
        .put("documentId", documentId)
        .put("tagId", tagId)
}

private fun JSONObject.toDocumentEntity(): DocumentEntity {
    return DocumentEntity(
        id = getLong("id"),
        title = getString("title"),
        timestamp = getLong("timestamp"),
        pageCount = getInt("pageCount"),
        localPdfUri = getString("localPdfUri"),
        extractedText = nullableString("extractedText"),
        folderId = nullableLong("folderId"),
        isFavorite = getBoolean("isFavorite"),
        isPinned = getBoolean("isPinned"),
        tags = getString("tags"),
        searchableText = getString("searchableText"),
        isDeleted = getBoolean("isDeleted"),
        deletedAt = nullableLong("deletedAt")
    )
}

private fun JSONObject.toFolderEntity(): FolderEntity {
    return FolderEntity(
        id = getLong("id"),
        name = getString("name"),
        createdAt = getLong("createdAt"),
        isDefault = getBoolean("isDefault"),
        sortOrder = getInt("sortOrder")
    )
}

private fun JSONObject.toTagEntity(): TagEntity {
    return TagEntity(
        id = getLong("id"),
        name = getString("name"),
        createdAt = getLong("createdAt"),
        isDefault = getBoolean("isDefault"),
        sortOrder = getInt("sortOrder")
    )
}

private fun JSONObject.toDocumentTagCrossRef(): DocumentTagCrossRef {
    return DocumentTagCrossRef(
        documentId = getLong("documentId"),
        tagId = getLong("tagId")
    )
}

private fun JSONObject.nullableString(name: String): String? {
    return if (isNull(name)) null else getString(name)
}

private fun JSONObject.nullableLong(name: String): Long? {
    return if (isNull(name)) null else getLong(name)
}

private fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> {
    return List(length()) { index -> transform(getJSONObject(index)) }
}
