package com.dev.docscannerpdf.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

const val APP_DATABASE_VERSION = 6

@Database(
    entities = [
        DocumentEntity::class,
        FolderEntity::class,
        TagEntity::class,
        DocumentTagCrossRef::class
    ],
    version = APP_DATABASE_VERSION,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
    abstract fun folderDao(): FolderDao
    abstract fun tagDao(): TagDao
}
