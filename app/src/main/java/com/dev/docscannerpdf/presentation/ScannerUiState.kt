package com.dev.docscannerpdf.presentation

import com.dev.docscannerpdf.data.local.DocumentEntity

data class ScannerUiState(
    val documents: List<DocumentEntity> = emptyList(),
    val trashDocuments: List<DocumentEntity> = emptyList(),
    val favoriteDocuments: List<DocumentEntity> = emptyList(),
    val pinnedDocuments: List<DocumentEntity> = emptyList(),
    val folders: List<FolderUiModel> = emptyList(),
    val tags: List<TagUiModel> = emptyList(),
    val selectedFolderId: Long? = null,
    val selectedTagIds: Set<Long> = emptySet(),
    val totalDocumentsCount: Int = 0,
    val totalPagesCount: Int = 0,
    val searchQuery: String = "",
    val trashSearchQuery: String = "",
    val recentSearches: List<String> = emptyList(),
    val isSaving: Boolean = false,
    val shouldShowInterstitial: Boolean = false,
    val errorMessage: String? = null
)

data class FolderUiModel(
    val id: Long?,
    val name: String,
    val documentCount: Int,
    val isDefault: Boolean = false,
    val isAllDocuments: Boolean = false
)

data class TagUiModel(
    val id: Long,
    val name: String,
    val documentCount: Int,
    val isDefault: Boolean = false
)
