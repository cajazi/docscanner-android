package com.dev.docscannerpdf.presentation

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.dev.docscannerpdf.data.local.DocumentEntity
import com.dev.docscannerpdf.data.local.FolderEntity
import com.dev.docscannerpdf.data.repository.DocumentRepository
import com.dev.docscannerpdf.data.repository.FolderRepository
import com.dev.docscannerpdf.data.repository.TagRepository
import com.dev.docscannerpdf.domain.analytics.AnalyticsRepository
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ScannerViewModel(
    private val repository: DocumentRepository,
    private val folderRepository: FolderRepository,
    private val tagRepository: TagRepository,
    private val analyticsRepository: AnalyticsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ScannerUiState())
    val uiState: StateFlow<ScannerUiState> = _uiState.asStateFlow()
    private val allDocuments = MutableStateFlow<List<DocumentEntity>>(emptyList())
    private val allFolders = MutableStateFlow<List<FolderEntity>>(emptyList())
    private val allTags = MutableStateFlow<List<TagUiModel>>(emptyList())
    private val searchDocuments = MutableStateFlow<List<DocumentEntity>>(emptyList())
    private val trashDocuments = MutableStateFlow<List<DocumentEntity>>(emptyList())
    private val searchQuery = MutableStateFlow("")
    private val trashSearchQuery = MutableStateFlow("")
    private val selectedFolderId = MutableStateFlow<Long?>(null)
    private val selectedTagIds = MutableStateFlow<Set<Long>>(emptySet())
    private val recentSearches = MutableStateFlow<List<String>>(emptyList())

    init {
        viewModelScope.launch {
            runCatching { repository.cleanupExpiredTrash() }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(errorMessage = throwable.message ?: "Unable to clean Trash.")
                    }
                }
        }

        viewModelScope.launch {
            runCatching { folderRepository.ensureDefaultFolders() }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(errorMessage = throwable.message ?: "Unable to prepare folders.")
                    }
                }
        }

        viewModelScope.launch {
            runCatching { tagRepository.ensureDefaultTags() }
                .onFailure { throwable ->
                    _uiState.update {
                        it.copy(errorMessage = throwable.message ?: "Unable to prepare tags.")
                    }
                }
        }

        viewModelScope.launch {
            repository.observeDocuments()
                .catch { throwable ->
                    _uiState.update {
                        it.copy(errorMessage = throwable.message ?: "Unable to load documents.")
                    }
                }
                .collect { documents ->
                    allDocuments.value = documents
                }
        }

        viewModelScope.launch {
            folderRepository.observeFolders()
                .catch { throwable ->
                    _uiState.update {
                        it.copy(errorMessage = throwable.message ?: "Unable to load folders.")
                    }
                }
                .collect { folders ->
                    allFolders.value = folders
                }
        }

        viewModelScope.launch {
            tagRepository.observeTagsWithCounts()
                .catch { throwable ->
                    _uiState.update {
                        it.copy(errorMessage = throwable.message ?: "Unable to load tags.")
                    }
                }
                .collect { tags ->
                    allTags.value = tags.map { tag ->
                        TagUiModel(
                            id = tag.id,
                            name = tag.name,
                            documentCount = tag.documentCount,
                            isDefault = tag.isDefault
                        )
                    }
                }
        }

        viewModelScope.launch {
            searchResultsFlow()
                .catch { throwable ->
                    _uiState.update {
                        it.copy(errorMessage = throwable.message ?: "Unable to search documents.")
                    }
                }
                .collect { documents ->
                    searchDocuments.value = documents
                }
        }

        viewModelScope.launch {
            trashResultsFlow()
                .catch { throwable ->
                    _uiState.update {
                        it.copy(errorMessage = throwable.message ?: "Unable to load Trash.")
                    }
                }
                .collect { documents ->
                    trashDocuments.value = documents
                }
        }

        viewModelScope.launch {
            val documentSources = combine(searchDocuments, allDocuments, trashDocuments, allFolders, allTags) {
                    searchedDocuments,
                    allDocumentsValue,
                    trashDocumentsValue,
                    folders,
                    tags ->
                DashboardSources(
                    searchedDocuments = searchedDocuments,
                    allDocuments = allDocumentsValue,
                    trashDocuments = trashDocumentsValue,
                    folders = folders,
                    tags = tags
                )
            }

            combine(documentSources, searchQuery, trashSearchQuery, selectedFolderId, selectedTagIds) {
                    sources,
                    query,
                    trashQuery,
                    folderId,
                    tagIds ->
                val sortedDocuments = sources.searchedDocuments.sortedWith(documentPriorityComparator)
                FolderDashboardState(
                    query = query.trim(),
                    trashQuery = trashQuery.trim(),
                    documents = sortedDocuments,
                    trashDocuments = sources.trashDocuments,
                    favoriteDocuments = sortedDocuments.filter { document -> document.isFavorite },
                    pinnedDocuments = sortedDocuments.filter { document -> document.isPinned },
                    folders = buildFolderUiModels(sources.folders, sources.allDocuments),
                    tags = sources.tags,
                    selectedFolderId = folderId,
                    selectedTagIds = tagIds,
                    totalDocumentsCount = sources.allDocuments.size,
                    totalPagesCount = sources.allDocuments.sumOf { document -> document.pageCount },
                    recentSearches = recentSearches.value
                )
            }.collect { dashboardState ->
                _uiState.update {
                    it.copy(
                        documents = dashboardState.documents,
                        trashDocuments = dashboardState.trashDocuments,
                        favoriteDocuments = dashboardState.favoriteDocuments,
                        pinnedDocuments = dashboardState.pinnedDocuments,
                        folders = dashboardState.folders,
                        tags = dashboardState.tags,
                        selectedFolderId = dashboardState.selectedFolderId,
                        selectedTagIds = dashboardState.selectedTagIds,
                        totalDocumentsCount = dashboardState.totalDocumentsCount,
                        totalPagesCount = dashboardState.totalPagesCount,
                        searchQuery = dashboardState.query,
                        trashSearchQuery = dashboardState.trashQuery,
                        recentSearches = dashboardState.recentSearches
                    )
                }
            }
        }
    }

    fun handleScanResult(
        activity: Activity,
        result: GmsDocumentScanningResult?,
        titlePrefix: String = "Scan",
        isIdCardScan: Boolean = false
    ) {
        val pdfUri = result?.pdf?.uri
        if (pdfUri == null) {
            _uiState.update { it.copy(errorMessage = "No PDF was created.") }
            return
        }

        if (isIdCardScan) {
            val pageCount = result.pages?.size ?: 0
            if (pageCount != 1) {
                _uiState.update {
                    it.copy(errorMessage = "ID card scans must capture exactly one card page.")
                }
                return
            }
        }

        val now = System.currentTimeMillis()
        val document = DocumentEntity(
            title = "$titlePrefix $now",
            timestamp = now,
            pageCount = result.pages?.size ?: 0,
            localPdfUri = pdfUri.toString(),
            extractedText = null
        )

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                val documentId = repository.saveDocument(document)
                analyticsRepository.trackEvent(
                    AnalyticsRepository.EVENT_SCAN_CREATED,
                    mapOf("page_count" to document.pageCount)
                )
                saveExtractedTextIfAvailable(
                    context = activity.applicationContext,
                    document = document.copy(id = documentId),
                    imageUri = result.pages?.firstOrNull()?.imageUri
                )
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        shouldShowInterstitial = true
                    )
                }
            } catch (throwable: Throwable) {
                analyticsRepository.recordNonFatal(
                    throwable = throwable,
                    area = "scan_save",
                    metadata = mapOf("page_count" to document.pageCount.toString())
                )
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = throwable.message ?: "Unable to save document."
                    )
                }
            }
        }
    }

    fun importImage(
        context: Context,
        imageUri: Uri?
    ) {
        if (imageUri == null) {
            _uiState.update { it.copy(errorMessage = "Image import canceled.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                val localUri = copyImportedImageForEditor(
                    context = context.applicationContext,
                    imageUri = imageUri
                )
                repository.saveDocument(
                    DocumentEntity(
                        title = "Imported Image",
                        timestamp = System.currentTimeMillis(),
                        pageCount = 1,
                        localPdfUri = localUri.toString(),
                        extractedText = null
                    )
                )
                _uiState.update { it.copy(isSaving = false) }
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = throwable.message ?: "Unable to import image."
                    )
                }
            }
        }
    }

    suspend fun copyImportedImageForEditor(
        context: Context,
        imageUri: Uri
    ): Uri {
        return copyUriToPrivateStorage(
            context = context.applicationContext,
            sourceUri = imageUri,
            directoryName = "imported_images",
            filePrefix = "imported_image",
            fileExtension = ".jpg"
        )
    }

    fun saveImportedImageDocument(
        title: String,
        imageUri: Uri,
        extractedText: String?
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                repository.saveDocument(
                    DocumentEntity(
                        title = title.ifBlank { "Imported Image" },
                        timestamp = System.currentTimeMillis(),
                        pageCount = 1,
                        localPdfUri = imageUri.toString(),
                        extractedText = extractedText?.takeIf { text -> text.isNotBlank() }
                    )
                )
                _uiState.update { it.copy(isSaving = false) }
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = throwable.message ?: "Unable to save imported image."
                    )
                }
            }
        }
    }

    fun saveGeneratedPdfDocument(
        title: String,
        pageCount: Int,
        pdfUri: Uri,
        extractedText: String?
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                repository.saveDocument(
                    DocumentEntity(
                        title = title.ifBlank { "Images to PDF" },
                        timestamp = System.currentTimeMillis(),
                        pageCount = pageCount,
                        localPdfUri = pdfUri.toString(),
                        extractedText = extractedText?.takeIf { text -> text.isNotBlank() }
                    )
                )
                _uiState.update { it.copy(isSaving = false) }
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = throwable.message ?: "Unable to save generated PDF."
                    )
                }
            }
        }
    }

    fun saveGeneratedPdfDocument(
        document: DocumentEntity,
        onSaved: (DocumentEntity) -> Unit = {}
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                val documentId = repository.saveDocument(document)
                val savedDocument = document.copy(id = documentId)
                _uiState.update { it.copy(isSaving = false) }
                onSaved(savedDocument)
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = throwable.message ?: "Unable to save generated PDF."
                    )
                }
            }
        }
    }

    fun importPdf(
        context: Context,
        pdfUri: Uri?
    ) {
        if (pdfUri == null) {
            _uiState.update { it.copy(errorMessage = "File import canceled.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, errorMessage = null) }
            try {
                repository.saveDocument(
                    DocumentEntity(
                        title = "Imported PDF",
                        timestamp = System.currentTimeMillis(),
                        pageCount = 0,
                        localPdfUri = pdfUri.toString(),
                        extractedText = null
                    )
                )
                _uiState.update { it.copy(isSaving = false) }
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        errorMessage = throwable.message ?: "Unable to import PDF."
                    )
                }
            }
        }
    }

    private suspend fun saveExtractedTextIfAvailable(
        context: Context,
        document: DocumentEntity,
        imageUri: Uri?
    ) {
        if (imageUri == null) return

        val extractedText = runCatching {
            recognizeText(context, imageUri)
        }.onFailure { throwable ->
            analyticsRepository.recordNonFatal(
                throwable = throwable,
                area = "ocr",
                metadata = mapOf("source" to "scan_page")
            )
        }.getOrNull()

        if (extractedText != null) {
            runCatching {
                repository.updateDocument(document.copy(extractedText = extractedText))
                analyticsRepository.trackEvent(
                    AnalyticsRepository.EVENT_OCR_EXTRACTED,
                    mapOf("source" to "scan_page", "has_text" to extractedText.isNotBlank())
                )
            }.onFailure { throwable ->
                analyticsRepository.recordNonFatal(
                    throwable = throwable,
                    area = "ocr",
                    metadata = mapOf("source" to "save_extracted_text")
                )
            }
        }
    }

    suspend fun recognizeText(
        context: Context,
        imageUri: Uri
    ): String {
        val image = withContext(Dispatchers.IO) {
            InputImage.fromFilePath(context.applicationContext, imageUri)
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            recognizer.process(image).await().text
        } finally {
            recognizer.close()
        }
    }

    suspend fun recognizeText(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            recognizer.process(image).await().text
        } finally {
            recognizer.close()
        }
    }

    private suspend fun copyUriToPrivateStorage(
        context: Context,
        sourceUri: Uri,
        directoryName: String,
        filePrefix: String,
        fileExtension: String
    ): Uri = withContext(Dispatchers.IO) {
        val directory = File(context.filesDir, directoryName).apply {
            if (!exists()) mkdirs()
        }
        val destination = File(directory, "$filePrefix-${System.currentTimeMillis()}$fileExtension")

        context.contentResolver.openInputStream(sourceUri).use { input ->
            requireNotNull(input) { "Unable to read selected file." }
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        Uri.fromFile(destination)
    }

    fun deleteDocument(document: DocumentEntity) {
        viewModelScope.launch {
            try {
                repository.moveToTrash(document.id)
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to move document to Trash.")
                }
            }
        }
    }

    fun moveDocumentsToTrash(documents: List<DocumentEntity>) {
        viewModelScope.launch {
            try {
                documents.forEach { document -> repository.moveToTrash(document.id) }
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to move documents to Trash.")
                }
            }
        }
    }

    fun restoreDocument(document: DocumentEntity) {
        viewModelScope.launch {
            try {
                repository.restoreFromTrash(document.id)
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to restore document.")
                }
            }
        }
    }

    fun permanentlyDeleteDocument(document: DocumentEntity) {
        viewModelScope.launch {
            try {
                repository.permanentlyDeleteDocument(document)
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to permanently delete document.")
                }
            }
        }
    }

    fun emptyTrash() {
        viewModelScope.launch {
            try {
                repository.emptyTrash()
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to empty Trash.")
                }
            }
        }
    }

    fun renameDocument(
        document: DocumentEntity,
        title: String
    ) {
        val normalizedTitle = title.trim()
        when {
            normalizedTitle.isBlank() -> {
                _uiState.update { it.copy(errorMessage = "Title cannot be blank.") }
                return
            }
            normalizedTitle.length > MAX_TITLE_LENGTH -> {
                _uiState.update { it.copy(errorMessage = "Title must be 80 characters or fewer.") }
                return
            }
        }

        viewModelScope.launch {
            try {
                repository.updateTitle(document.id, normalizedTitle)
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to rename document.")
                }
            }
        }
    }

    fun createFolder(name: String) {
        val normalizedName = normalizeFolderName(name) ?: return
        viewModelScope.launch {
            try {
                folderRepository.createFolder(normalizedName)
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to create folder.")
                }
            }
        }
    }

    fun renameFolder(folderId: Long, name: String) {
        val normalizedName = normalizeFolderName(name) ?: return
        viewModelScope.launch {
            try {
                folderRepository.renameFolder(folderId, normalizedName)
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to rename folder.")
                }
            }
        }
    }

    fun deleteFolder(folderId: Long) {
        viewModelScope.launch {
            try {
                val folder = folderRepository.getFolderById(folderId)
                if (folder == null) {
                    _uiState.update { it.copy(errorMessage = "Folder not found.") }
                    return@launch
                }
                repository.clearFolder(folderId)
                folderRepository.deleteFolder(folder)
                if (selectedFolderId.value == folderId) {
                    selectedFolderId.value = null
                }
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to delete folder.")
                }
            }
        }
    }

    fun moveDocumentToFolder(document: DocumentEntity, folderId: Long?) {
        viewModelScope.launch {
            try {
                repository.updateFolder(document.id, folderId)
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to move document.")
                }
            }
        }
    }

    fun moveDocumentsToFolder(documents: List<DocumentEntity>, folderId: Long?) {
        viewModelScope.launch {
            try {
                documents.forEach { document -> repository.updateFolder(document.id, folderId) }
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to move documents.")
                }
            }
        }
    }

    fun setDocumentFavorite(document: DocumentEntity, isFavorite: Boolean) {
        viewModelScope.launch {
            try {
                repository.updateFavorite(document.id, isFavorite)
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to update favorite.")
                }
            }
        }
    }

    fun setDocumentsFavorite(documents: List<DocumentEntity>, isFavorite: Boolean) {
        viewModelScope.launch {
            try {
                documents.forEach { document -> repository.updateFavorite(document.id, isFavorite) }
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to update favorites.")
                }
            }
        }
    }

    fun setDocumentPinned(document: DocumentEntity, isPinned: Boolean) {
        viewModelScope.launch {
            try {
                repository.updatePinned(document.id, isPinned)
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to update pin.")
                }
            }
        }
    }

    fun setDocumentsPinned(documents: List<DocumentEntity>, isPinned: Boolean) {
        viewModelScope.launch {
            try {
                documents.forEach { document -> repository.updatePinned(document.id, isPinned) }
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to update pins.")
                }
            }
        }
    }

    fun updateDocumentTags(document: DocumentEntity, tags: String) {
        updateDocumentTags(
            document = document,
            tagNames = tags.split(',', '#')
        )
    }

    fun updateDocumentTags(document: DocumentEntity, tagNames: List<String>) {
        viewModelScope.launch {
            try {
                tagRepository.setDocumentTags(document.id, tagNames)
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to update tags.")
                }
            }
        }
    }

    fun addTagsToDocuments(documents: List<DocumentEntity>, tagNames: List<String>) {
        viewModelScope.launch {
            try {
                documents.forEach { document ->
                    tagRepository.setDocumentTags(
                        documentId = document.id,
                        tagNames = parseTagText(document.tags) + tagNames
                    )
                }
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to add tags.")
                }
            }
        }
    }

    fun createTag(name: String) {
        val normalizedName = normalizeTagName(name) ?: return
        viewModelScope.launch {
            try {
                tagRepository.createTag(normalizedName)
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to create tag.")
                }
            }
        }
    }

    fun renameTag(tagId: Long, name: String) {
        val normalizedName = normalizeTagName(name) ?: return
        viewModelScope.launch {
            try {
                tagRepository.renameTag(tagId, normalizedName)
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to rename tag.")
                }
            }
        }
    }

    fun deleteTag(tagId: Long) {
        viewModelScope.launch {
            try {
                tagRepository.deleteTag(tagId)
                selectedTagIds.update { ids -> ids - tagId }
            } catch (throwable: Throwable) {
                _uiState.update {
                    it.copy(errorMessage = throwable.message ?: "Unable to delete tag.")
                }
            }
        }
    }

    fun selectFolder(folderId: Long?) {
        selectedFolderId.value = folderId
    }

    fun toggleTagFilter(tagId: Long) {
        selectedTagIds.update { ids ->
            if (tagId in ids) ids - tagId else ids + tagId
        }
    }

    fun clearTagFilters() {
        selectedTagIds.value = emptySet()
    }

    fun updateSearchQuery(query: String) {
        val normalizedQuery = query.trimStart()
        searchQuery.value = normalizedQuery
        val searchLabel = normalizedQuery.trim()
        if (searchLabel.length >= MIN_RECENT_SEARCH_LENGTH) {
            recentSearches.update { searches ->
                listOf(searchLabel) + searches.filterNot { it.equals(searchLabel, ignoreCase = true) }
            }.also {
                recentSearches.value = recentSearches.value.take(MAX_RECENT_SEARCHES)
            }
        }
    }

    fun updateTrashSearchQuery(query: String) {
        trashSearchQuery.value = query.trimStart()
    }

    fun showError(message: String) {
        _uiState.update { it.copy(errorMessage = message) }
    }

    fun markInterstitialConsumed() {
        _uiState.update { it.copy(shouldShowInterstitial = false) }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private companion object {
        const val MAX_TITLE_LENGTH = 80
        const val MAX_FOLDER_NAME_LENGTH = 48
        const val MAX_TAG_NAME_LENGTH = 40
        const val MAX_RECENT_SEARCHES = 6
        const val MIN_RECENT_SEARCH_LENGTH = 2
    }

    private fun normalizeFolderName(name: String): String? {
        val normalizedName = name.trim()
        when {
            normalizedName.isBlank() -> {
                _uiState.update { it.copy(errorMessage = "Folder name cannot be blank.") }
                return null
            }
            normalizedName.length > MAX_FOLDER_NAME_LENGTH -> {
                _uiState.update { it.copy(errorMessage = "Folder name must be 48 characters or fewer.") }
                return null
            }
        }
        return normalizedName
    }

    private fun normalizeTagName(name: String): String? {
        val normalizedName = name.trim().trimStart('#').replace(Regex("\\s+"), " ")
        when {
            normalizedName.isBlank() -> {
                _uiState.update { it.copy(errorMessage = "Tag name cannot be blank.") }
                return null
            }
            normalizedName.length > MAX_TAG_NAME_LENGTH -> {
                _uiState.update { it.copy(errorMessage = "Tag name must be 40 characters or fewer.") }
                return null
            }
        }
        return normalizedName
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun searchResultsFlow() =
        combine(searchQuery.debounce(180), selectedFolderId, selectedTagIds) { query, folderId, tagIds ->
            SearchFilter(query = query, folderId = folderId, tagIds = tagIds)
        }
            .flatMapLatest { filter ->
                repository.observeSearchDocuments(
                    folderId = filter.folderId,
                    selectedTagIds = filter.tagIds.toList(),
                    rawQuery = filter.query
                )
            }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    private fun trashResultsFlow() =
        trashSearchQuery.debounce(180).flatMapLatest { query ->
            repository.observeTrashDocuments(query)
        }
}

private data class SearchFilter(
    val query: String,
    val folderId: Long?,
    val tagIds: Set<Long>
)

private data class DashboardSources(
    val searchedDocuments: List<DocumentEntity>,
    val allDocuments: List<DocumentEntity>,
    val trashDocuments: List<DocumentEntity>,
    val folders: List<FolderEntity>,
    val tags: List<TagUiModel>
)

private data class FolderDashboardState(
    val query: String,
    val trashQuery: String,
    val documents: List<DocumentEntity>,
    val trashDocuments: List<DocumentEntity>,
    val favoriteDocuments: List<DocumentEntity>,
    val pinnedDocuments: List<DocumentEntity>,
    val folders: List<FolderUiModel>,
    val tags: List<TagUiModel>,
    val selectedFolderId: Long?,
    val selectedTagIds: Set<Long>,
    val totalDocumentsCount: Int,
    val totalPagesCount: Int,
    val recentSearches: List<String>
)

private val documentPriorityComparator = compareByDescending<DocumentEntity> { document -> document.isPinned }
    .thenByDescending { document -> document.isFavorite }
    .thenByDescending { document -> document.timestamp }

private fun buildFolderUiModels(
    folders: List<FolderEntity>,
    documents: List<DocumentEntity>
): List<FolderUiModel> {
    val counts = documents.groupingBy { document -> document.folderId }.eachCount()
    return listOf(
        FolderUiModel(
            id = null,
            name = "All Documents",
            documentCount = documents.size,
            isDefault = true,
            isAllDocuments = true
        )
    ) + folders.map { folder ->
        FolderUiModel(
            id = folder.id,
            name = folder.name,
            documentCount = counts[folder.id] ?: 0,
            isDefault = folder.isDefault
        )
    }
}

private fun parseTagText(tags: String): List<String> {
    return tags.split(',', '#')
        .map { tag -> tag.trim().trimStart('#').replace(Regex("\\s+"), " ") }
        .filter { tag -> tag.isNotBlank() }
        .distinctBy { tag -> tag.lowercase() }
}

private suspend fun <T> Task<T>.await(): T {
    return suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            continuation.resume(result)
        }
        addOnFailureListener { exception ->
            continuation.resumeWithException(exception)
        }
        addOnCanceledListener {
            continuation.cancel()
        }
    }
}
