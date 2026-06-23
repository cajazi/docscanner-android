package com.dev.docscannerpdf.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.dev.docscannerpdf.data.repository.DocumentRepository
import com.dev.docscannerpdf.data.repository.FolderRepository
import com.dev.docscannerpdf.data.repository.TagRepository
import com.dev.docscannerpdf.domain.analytics.AnalyticsRepository

class ScannerViewModelFactory(
    private val repository: DocumentRepository,
    private val folderRepository: FolderRepository,
    private val tagRepository: TagRepository,
    private val analyticsRepository: AnalyticsRepository
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ScannerViewModel::class.java)) {
            return ScannerViewModel(repository, folderRepository, tagRepository, analyticsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
