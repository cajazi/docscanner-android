package com.dev.docscannerpdf.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(folder: FolderEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(folders: List<FolderEntity>)

    @Query("SELECT * FROM folders ORDER BY sortOrder ASC, name COLLATE NOCASE ASC")
    fun observeFolders(): Flow<List<FolderEntity>>

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): FolderEntity?

    @Query("SELECT * FROM folders WHERE name = :name LIMIT 1")
    suspend fun getByName(name: String): FolderEntity?

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String)

    @Delete
    suspend fun delete(folder: FolderEntity)

    @Query("DELETE FROM folders")
    suspend fun deleteAll()

    @Query("SELECT * FROM folders ORDER BY id ASC")
    suspend fun getAllForBackup(): List<FolderEntity>
}
