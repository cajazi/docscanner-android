package com.dev.docscannerpdf.data.local

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "documents",
    indices = [
        Index(value = ["title"]),
        Index(value = ["folderId"]),
        Index(value = ["tags"]),
        Index(value = ["isDeleted", "deletedAt"]),
        Index(value = ["searchableText"]),
        Index(value = ["isFavorite", "isPinned", "timestamp"])
    ]
)
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val title: String,
    val timestamp: Long,
    val pageCount: Int,
    val localPdfUri: String,
    val extractedText: String? = null,
    val folderId: Long? = null,
    val isFavorite: Boolean = false,
    val isPinned: Boolean = false,
    val tags: String = "",
    val searchableText: String = "",
    val isDeleted: Boolean = false,
    val deletedAt: Long? = null
)
