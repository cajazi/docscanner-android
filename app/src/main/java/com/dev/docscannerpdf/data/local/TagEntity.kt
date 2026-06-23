package com.dev.docscannerpdf.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tags",
    indices = [Index(value = ["name"], unique = true)]
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val createdAt: Long,
    val isDefault: Boolean = false,
    val sortOrder: Int = 0
)

data class TagWithCount(
    val id: Long,
    val name: String,
    val createdAt: Long,
    val isDefault: Boolean,
    val sortOrder: Int,
    val documentCount: Int
)
