package com.dev.docscannerpdf.data.repository

import com.dev.docscannerpdf.data.local.FolderDao
import com.dev.docscannerpdf.data.local.FolderEntity
import kotlinx.coroutines.flow.Flow

class FolderRepository(private val dao: FolderDao) {
    fun observeFolders(): Flow<List<FolderEntity>> = dao.observeFolders()

    suspend fun createFolder(name: String): Long {
        val now = System.currentTimeMillis()
        return dao.insert(
            FolderEntity(
                name = name,
                createdAt = now,
                isDefault = false,
                sortOrder = CUSTOM_FOLDER_SORT_ORDER
            )
        )
    }

    suspend fun ensureDefaultFolders() {
        DefaultFolders.forEachIndexed { index, name ->
            if (dao.getByName(name) == null) {
                dao.insert(
                    FolderEntity(
                        name = name,
                        createdAt = System.currentTimeMillis(),
                        isDefault = true,
                        sortOrder = index + 1
                    )
                )
            }
        }
    }

    suspend fun renameFolder(id: Long, name: String) = dao.updateName(id, name)

    suspend fun deleteFolder(folder: FolderEntity) = dao.delete(folder)

    suspend fun getFolderById(id: Long): FolderEntity? = dao.getById(id)

    companion object {
        val DefaultFolders = listOf("Receipts", "ID Cards", "Notes", "Business")
        private const val CUSTOM_FOLDER_SORT_ORDER = 100
    }
}
