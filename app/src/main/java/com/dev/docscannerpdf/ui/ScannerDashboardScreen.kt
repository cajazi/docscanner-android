package com.dev.docscannerpdf.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.dev.docscannerpdf.data.local.DocumentEntity
import com.dev.docscannerpdf.presentation.FolderUiModel
import com.dev.docscannerpdf.presentation.ScannerViewModel
import com.dev.docscannerpdf.presentation.TagUiModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class BottomTab {
    HOME,
    FILES,
    TOOLS,
    PROFILE
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ScannerDashboardScreen(
    viewModel: ScannerViewModel,
    onStartScan: () -> Unit,
    onPdfTools: () -> Unit,
    onImportImages: () -> Unit,
    onImportFiles: () -> Unit,
    onCloud: () -> Unit,
    onPremium: () -> Unit,
    isPremium: Boolean,
    onIdCards: () -> Unit,
    onExtractText: () -> Unit,
    onAiTools: () -> Unit,
    onAllTools: () -> Unit,
    onViewAll: () -> Unit,
    onOpenSettings: () -> Unit,
    onToWord: (DocumentEntity) -> Unit,
    onOpenDocument: (DocumentEntity) -> Unit,
    onShareDocument: (DocumentEntity) -> Unit,
    onShareDocuments: (List<DocumentEntity>) -> Unit,
    onEditPdfDocument: (DocumentEntity) -> Unit,
    onSendDocumentToPc: (DocumentEntity) -> Unit,
    onSaveDocumentExport: (DocumentEntity) -> Unit,
    onPrintDocument: (DocumentEntity) -> Unit,
    onConvertImageToPdf: (DocumentEntity) -> Unit,
    onShareExtractedText: (DocumentEntity) -> Unit,
    onShareCleanedText: (String, String) -> Unit,
    onExportCleanedText: (String, String, String) -> Unit,
    onSaveOcrText: (DocumentEntity, String) -> Unit,
    onRenameDocument: (DocumentEntity, String) -> Unit,
    onDeleteDocument: (DocumentEntity) -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    var documentPendingDelete by remember { mutableStateOf<DocumentEntity?>(null) }
    var documentPreview by remember { mutableStateOf<DocumentEntity?>(null) }
    var documentPendingRename by remember { mutableStateOf<DocumentEntity?>(null) }
    var documentPendingExtractText by remember { mutableStateOf<DocumentEntity?>(null) }
    var documentPendingMove by remember { mutableStateOf<DocumentEntity?>(null) }
    var documentPendingActions by remember { mutableStateOf<DocumentEntity?>(null) }
    var documentPendingTags by remember { mutableStateOf<DocumentEntity?>(null) }
    var showCreateFolderSheet by remember { mutableStateOf(false) }
    var showCreateTagDialog by remember { mutableStateOf(false) }
    var folderPendingActions by remember { mutableStateOf<FolderUiModel?>(null) }
    var folderPendingRename by remember { mutableStateOf<FolderUiModel?>(null) }
    var folderPendingDelete by remember { mutableStateOf<FolderUiModel?>(null) }
    var tagPendingActions by remember { mutableStateOf<TagUiModel?>(null) }
    var tagPendingRename by remember { mutableStateOf<TagUiModel?>(null) }
    var tagPendingDelete by remember { mutableStateOf<TagUiModel?>(null) }
    var showExtractTextPicker by remember { mutableStateOf(false) }
    var showProfileSheet by remember { mutableStateOf(false) }
    var currentTab by remember { mutableStateOf(BottomTab.HOME) }
    var showTrashScreen by remember { mutableStateOf(false) }
    var selectedDocumentIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showBatchMoveSheet by remember { mutableStateOf(false) }
    var showBatchTagsDialog by remember { mutableStateOf(false) }
    var batchTrashPending by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val selectedDocuments = remember(uiState.documents, selectedDocumentIds) {
        uiState.documents.filter { document -> document.id in selectedDocumentIds }
    }
    val isSelectionMode = selectedDocumentIds.isNotEmpty()

    fun clearSelection() {
        selectedDocumentIds = emptySet()
    }

    fun toggleSelection(document: DocumentEntity) {
        selectedDocumentIds = if (document.id in selectedDocumentIds) {
            selectedDocumentIds - document.id
        } else {
            selectedDocumentIds + document.id
        }
    }

    val hasDashboardBackTarget = showTrashScreen ||
        documentPendingDelete != null ||
        documentPreview != null ||
        documentPendingRename != null ||
        documentPendingExtractText != null ||
        documentPendingMove != null ||
        documentPendingActions != null ||
        documentPendingTags != null ||
        showCreateFolderSheet ||
        showCreateTagDialog ||
        folderPendingActions != null ||
        folderPendingRename != null ||
        folderPendingDelete != null ||
        tagPendingActions != null ||
        tagPendingRename != null ||
        tagPendingDelete != null ||
        showExtractTextPicker ||
        showProfileSheet ||
        showBatchMoveSheet ||
        showBatchTagsDialog ||
        batchTrashPending ||
        isSelectionMode ||
        uiState.searchQuery.isNotBlank()

    BackHandler(enabled = hasDashboardBackTarget) {
        when {
            showTrashScreen && uiState.trashSearchQuery.isNotBlank() -> viewModel.updateTrashSearchQuery("")
            showTrashScreen -> showTrashScreen = false
            documentPendingDelete != null -> documentPendingDelete = null
            documentPreview != null -> documentPreview = null
            documentPendingRename != null -> documentPendingRename = null
            documentPendingExtractText != null -> documentPendingExtractText = null
            documentPendingMove != null -> documentPendingMove = null
            documentPendingActions != null -> documentPendingActions = null
            documentPendingTags != null -> documentPendingTags = null
            showBatchMoveSheet -> showBatchMoveSheet = false
            showBatchTagsDialog -> showBatchTagsDialog = false
            showCreateFolderSheet -> showCreateFolderSheet = false
            showCreateTagDialog -> showCreateTagDialog = false
            folderPendingActions != null -> folderPendingActions = null
            folderPendingRename != null -> folderPendingRename = null
            folderPendingDelete != null -> folderPendingDelete = null
            tagPendingActions != null -> tagPendingActions = null
            tagPendingRename != null -> tagPendingRename = null
            tagPendingDelete != null -> tagPendingDelete = null
            showExtractTextPicker -> showExtractTextPicker = false
            showProfileSheet -> showProfileSheet = false
            batchTrashPending -> batchTrashPending = false
            isSelectionMode -> clearSelection()
            uiState.searchQuery.isNotBlank() -> viewModel.updateSearchQuery("")
        }
    }

    if (showTrashScreen) {
        TrashScreen(
            documents = uiState.trashDocuments,
            query = uiState.trashSearchQuery,
            onQueryChange = viewModel::updateTrashSearchQuery,
            onBack = { showTrashScreen = false },
            onRestore = viewModel::restoreDocument,
            onPermanentDelete = viewModel::permanentlyDeleteDocument,
            onEmptyTrash = viewModel::emptyTrash
        )
        return
    }

    LaunchedEffect(uiState.errorMessage) {
        val message = uiState.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        viewModel.clearError()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            CenterScanFab(onClick = onStartScan)
        },
        bottomBar = {
            if (isSelectionMode) {
                BatchActionBar(
                    selectedCount = selectedDocumentIds.size,
                    favoriteLabel = if (selectedDocuments.all { document -> document.isFavorite }) "Unstar" else "Star",
                    pinLabel = if (selectedDocuments.all { document -> document.isPinned }) "Unpin" else "Pin",
                    onShare = {
                        onShareDocuments(selectedDocuments)
                        clearSelection()
                    },
                    onMove = { showBatchMoveSheet = true },
                    onTags = { showBatchTagsDialog = true },
                    onFavorite = {
                        viewModel.setDocumentsFavorite(
                            selectedDocuments,
                            selectedDocuments.any { document -> !document.isFavorite }
                        )
                        clearSelection()
                    },
                    onPin = {
                        viewModel.setDocumentsPinned(
                            selectedDocuments,
                            selectedDocuments.any { document -> !document.isPinned }
                        )
                        clearSelection()
                    },
                    onTrash = { batchTrashPending = true }
                )
            } else {
                DashboardBottomBar(
                    selectedTab = currentTab,
                    onOpenHome = { currentTab = BottomTab.HOME },
                    onOpenFiles = { currentTab = BottomTab.FILES },
                    onOpenTools = { currentTab = BottomTab.TOOLS },
                    onOpenProfile = { currentTab = BottomTab.PROFILE }
                )
            }
        }
    ) { innerPadding ->
        when (currentTab) {
            BottomTab.HOME -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(innerPadding),
                    contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
            if (isSelectionMode) {
                item(key = "selection-toolbar", contentType = "selection-toolbar") {
                    SelectionTopBar(
                        selectedCount = selectedDocumentIds.size,
                        totalCount = uiState.documents.size,
                        onClose = ::clearSelection,
                        onSelectAll = {
                            selectedDocumentIds = uiState.documents.map { document -> document.id }.toSet()
                        },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            item(key = "top-actions", contentType = "top-actions") {
                if (!isSelectionMode) {
                    ScannerTopSection(
                        query = uiState.searchQuery,
                        onQueryChange = viewModel::updateSearchQuery,
                        documentCount = uiState.totalDocumentsCount,
                        pageCount = uiState.totalPagesCount,
                        isSaving = uiState.isSaving,
                        onSmartScan = onStartScan,
                        onPdfTools = onPdfTools,
                        onImportImages = onImportImages,
                        onImportFiles = onImportFiles,
                        onCloud = onCloud,
                            onPremium = onPremium,
                        onIdCards = onIdCards,
                        onExtractText = { showExtractTextPicker = true },
                        onAiTools = onAiTools,
                        onAllTools = onAllTools,
                        modifier = Modifier.animateItem()
                    )
                }
            }

            if (uiState.isSaving) {
                items(
                    items = listOf("shimmer-1", "shimmer-2"),
                    key = { it },
                    contentType = { "shimmer" }
                ) {
                    LoadingDocumentPlaceholder(
                        modifier = Modifier.animateItem()
                    )
                }
            }

            item(key = "folders", contentType = "folders") {
                FolderSection(
                    folders = uiState.folders,
                    selectedFolderId = uiState.selectedFolderId,
                    onFolderSelected = viewModel::selectFolder,
                    onCreateFolder = { showCreateFolderSheet = true },
                    onFolderLongPress = { folder ->
                        if (!folder.isAllDocuments) {
                            folderPendingActions = folder
                        }
                    },
                    modifier = Modifier.animateItem()
                )
            }

            item(key = "tags", contentType = "tags") {
                TagSection(
                    tags = uiState.tags,
                    selectedTagIds = uiState.selectedTagIds,
                    onTagSelected = viewModel::toggleTagFilter,
                    onClearTags = viewModel::clearTagFilters,
                    onCreateTag = { showCreateTagDialog = true },
                    onTagLongPress = { tag -> tagPendingActions = tag },
                    modifier = Modifier.animateItem()
                )
            }

            if (uiState.searchQuery.isBlank() && uiState.recentSearches.isNotEmpty()) {
                item(key = "recent-searches", contentType = "recent-searches") {
                    RecentSearchesSection(
                        searches = uiState.recentSearches,
                        onSearchSelected = viewModel::updateSearchQuery,
                        modifier = Modifier.animateItem()
                    )
                }
            }

            if (uiState.documents.isEmpty() && !uiState.isSaving) {
                item(key = "empty-state", contentType = "empty") {
                    EmptyDocumentsState(
                        hasSearchQuery = uiState.searchQuery.isNotBlank(),
                        modifier = Modifier.animateItem()
                    )
                }
            }

            if (uiState.pinnedDocuments.isNotEmpty()) {
                item(key = "pinned-section", contentType = "document-section") {
                    HighlightedDocumentsSection(
                        title = "Pinned",
                        documents = uiState.pinnedDocuments,
                        icon = Icons.Default.PushPin,
                        onOpenDocument = { document -> documentPreview = document },
                        onLongPressDocument = { document -> documentPendingActions = document },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            if (uiState.favoriteDocuments.isNotEmpty()) {
                item(key = "favorites-section", contentType = "document-section") {
                    HighlightedDocumentsSection(
                        title = "Favorites",
                        documents = uiState.favoriteDocuments,
                        icon = Icons.Default.Star,
                        onOpenDocument = { document -> documentPreview = document },
                        onLongPressDocument = { document -> documentPendingActions = document },
                        modifier = Modifier.animateItem()
                    )
                }
            }

            if (uiState.documents.isNotEmpty()) {
                item(key = "recents-header", contentType = "section-header") {
                    RecentsHeader(
                        onViewAll = onViewAll,
                        modifier = Modifier.animateItem()
                    )
                }
            }

            items(
                items = dashboardItems(uiState.documents, includeAds = !isPremium),
                key = { item ->
                    when (item) {
                        is DashboardItem.Document -> "document-${item.document.id}"
                        is DashboardItem.NativeAd -> "native-ad-${item.position}"
                    }
                },
                contentType = { item ->
                    when (item) {
                        is DashboardItem.Document -> "document"
                        is DashboardItem.NativeAd -> "native-ad"
                    }
                }
            ) { item ->
                when (item) {
                    is DashboardItem.Document -> {
                        val document = item.document
                        val isNewest = item.isNewest
                        DocumentItemCard(
                            document = document,
                            searchQuery = uiState.searchQuery,
                            isNewest = isNewest,
                            onPreviewDocument = { documentPreview = document },
                            onOpenDocument = { onOpenDocument(document) },
                            onShareDocument = { onShareDocument(document) },
                            onExtractText = { documentPendingExtractText = document },
                            onMoveToFolder = { documentPendingMove = document },
                            onToggleFavorite = {
                                viewModel.setDocumentFavorite(document, !document.isFavorite)
                            },
                            onTogglePinned = {
                                viewModel.setDocumentPinned(document, !document.isPinned)
                            },
                            onShowLongPressMenu = {
                                selectedDocumentIds = selectedDocumentIds + document.id
                            },
                            onRenameDocument = { documentPendingRename = document },
                            onDeleteDocument = { documentPendingDelete = document },
                            onToWord = { onToWord(document) },
                            selectionMode = isSelectionMode,
                            selected = document.id in selectedDocumentIds,
                            onToggleSelection = { toggleSelection(document) },
                            modifier = Modifier.animateItem()
                        )
                    }

                    is DashboardItem.NativeAd -> {
                        NativeAdViewContainer(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(140.dp)
                                .animateItem()
                        )
                    }
                }
            }
        }
    }
    BottomTab.FILES -> {
        FilesScreen(
            query = uiState.searchQuery,
            documents = uiState.documents,
            folders = uiState.folders,
            onQueryChange = viewModel::updateSearchQuery,
            onImportFiles = onImportFiles,
            onImportImages = onImportImages,
            onCreateFolder = { showCreateFolderSheet = true },
            onOpenDocument = onOpenDocument,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
    BottomTab.TOOLS -> {
        PDFToolsScreen(
            documents = uiState.documents,
            onBack = { currentTab = BottomTab.HOME },
            onMergePdf = { onPdfTools() },
            onSplitPdf = { onPdfTools() },
            onCompressPdf = { onPdfTools() },
            onPdfToImages = { onPdfTools() },
            onImagesToPdf = { onPdfTools() },
            onEditPdf = { onPdfTools() },
            onLockPdf = { onPdfTools() },
            onUnlockPdf = { onPdfTools() },
            onSignPdf = { onPdfTools() },
            onWatermarkPdf = { onPdfTools() },
            onPdfToWord = { onPdfTools() },
            onRenameDocument = onRenameDocument,
            onShareExtractedText = onShareExtractedText,
            onShareCleanedText = onShareCleanedText,
            onExportCleanedText = onExportCleanedText,
            onSaveOcrText = onSaveOcrText,
            onComingSoon = { viewModel.showError(it) }
        )
    }
    BottomTab.PROFILE -> {
        ProfileTabScreen(
            trashCount = uiState.trashDocuments.size,
            onOpenSettings = onOpenSettings,
            onOpenTrash = { showTrashScreen = true },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}

    documentPendingDelete?.let { document ->
        AlertDialog(
            onDismissRequest = { documentPendingDelete = null },
            title = { Text(text = "Move to Trash?") },
            text = { Text(text = "This document moves to Trash and can be restored within 30 days.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        documentPendingDelete = null
                        onDeleteDocument(document)
                    }
                ) {
                    Text(text = "Move to Trash")
                }
            },
            dismissButton = {
                TextButton(onClick = { documentPendingDelete = null }) {
                    Text(text = "Cancel")
                }
            }
        )
    }

    documentPreview?.let { document ->
        DocumentPreviewDialog(
            document = document,
            onDismiss = { documentPreview = null },
            onAddPage = onStartScan,
            onOpenDocument = { onOpenDocument(document) },
            onShareDocument = { onShareDocument(document) },
            onEditPdfDocument = { onEditPdfDocument(document) },
            onSendDocumentToPc = { onSendDocumentToPc(document) },
            onSaveDocumentExport = { onSaveDocumentExport(document) },
            onPrintDocument = { onPrintDocument(document) },
            onConvertImageToPdf = { onConvertImageToPdf(document) },
            onExportText = { onToWord(document) },
            onRenameDocument = { documentPendingRename = document },
            onDeleteDocument = {
                documentPreview = null
                documentPendingDelete = document
            },
            onUnavailableAction = viewModel::showError
        )
    }

    documentPendingRename?.let { document ->
        RenameDocumentDialog(
            documents = uiState.documents,
            initialDocument = document,
            onDismiss = { documentPendingRename = null },
            onRename = onRenameDocument,
            onValidationError = viewModel::showError
        )
    }

    documentPendingMove?.let { document ->
        MoveToFolderBottomSheet(
            document = document,
            folders = uiState.folders,
            onDismiss = { documentPendingMove = null },
            onCreateFolder = {
                documentPendingMove = null
                showCreateFolderSheet = true
            },
            onMove = { folderId ->
                documentPendingMove = null
                viewModel.moveDocumentToFolder(document, folderId)
            }
        )
    }

    if (showBatchMoveSheet) {
        BatchMoveToFolderBottomSheet(
            selectedCount = selectedDocumentIds.size,
            folders = uiState.folders,
            onDismiss = { showBatchMoveSheet = false },
            onCreateFolder = {
                showBatchMoveSheet = false
                showCreateFolderSheet = true
            },
            onMove = { folderId ->
                showBatchMoveSheet = false
                viewModel.moveDocumentsToFolder(selectedDocuments, folderId)
                clearSelection()
            }
        )
    }

    documentPendingActions?.let { document ->
        DocumentLongPressActionsSheet(
            document = document,
            onDismiss = { documentPendingActions = null },
            onToggleFavorite = {
                documentPendingActions = null
                viewModel.setDocumentFavorite(document, !document.isFavorite)
            },
            onTogglePinned = {
                documentPendingActions = null
                viewModel.setDocumentPinned(document, !document.isPinned)
            },
            onMoveToFolder = {
                documentPendingActions = null
                documentPendingMove = document
            },
            onEditTags = {
                documentPendingActions = null
                documentPendingTags = document
            }
        )
    }

    documentPendingTags?.let { document ->
        EditTagsDialog(
            document = document,
            suggestedTags = uiState.tags,
            onDismiss = { documentPendingTags = null },
            onSave = { tags ->
                documentPendingTags = null
                viewModel.updateDocumentTags(document, tags)
            }
        )
    }

    if (showBatchTagsDialog) {
        AddTagsDialog(
            title = "Add tags",
            subtitle = "${selectedDocumentIds.size} selected documents",
            suggestedTags = uiState.tags,
            onDismiss = { showBatchTagsDialog = false },
            onSave = { tags ->
                showBatchTagsDialog = false
                viewModel.addTagsToDocuments(selectedDocuments, tags)
                clearSelection()
            }
        )
    }

    if (showCreateFolderSheet) {
        CreateFolderBottomSheet(
            onDismiss = { showCreateFolderSheet = false },
            onCreateFolder = { name ->
                showCreateFolderSheet = false
                viewModel.createFolder(name)
            }
        )
    }

    if (showCreateTagDialog) {
        CreateTagDialog(
            onDismiss = { showCreateTagDialog = false },
            onCreateTag = { name ->
                showCreateTagDialog = false
                viewModel.createTag(name)
            }
        )
    }

    folderPendingActions?.let { folder ->
        FolderActionsBottomSheet(
            folder = folder,
            onDismiss = { folderPendingActions = null },
            onRename = {
                folderPendingActions = null
                folderPendingRename = folder
            },
            onDelete = {
                folderPendingActions = null
                folderPendingDelete = folder
            }
        )
    }

    folderPendingRename?.let { folder ->
        RenameFolderDialog(
            folder = folder,
            onDismiss = { folderPendingRename = null },
            onRename = { name ->
                folderPendingRename = null
                folder.id?.let { folderId -> viewModel.renameFolder(folderId, name) }
            }
        )
    }

    folderPendingDelete?.let { folder ->
        AlertDialog(
            onDismissRequest = { folderPendingDelete = null },
            title = { Text(text = "Delete folder?") },
            text = { Text(text = "Documents stay saved and move back to All Documents.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        folderPendingDelete = null
                        folder.id?.let(viewModel::deleteFolder)
                    }
                ) {
                    Text(text = "Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { folderPendingDelete = null }) {
                    Text(text = "Cancel")
                }
            }
        )
    }

    tagPendingActions?.let { tag ->
        TagActionsBottomSheet(
            tag = tag,
            onDismiss = { tagPendingActions = null },
            onRename = {
                tagPendingActions = null
                tagPendingRename = tag
            },
            onDelete = {
                tagPendingActions = null
                tagPendingDelete = tag
            }
        )
    }

    tagPendingRename?.let { tag ->
        RenameTagDialog(
            tag = tag,
            onDismiss = { tagPendingRename = null },
            onRename = { name ->
                tagPendingRename = null
                viewModel.renameTag(tag.id, name)
            }
        )
    }

    tagPendingDelete?.let { tag ->
        AlertDialog(
            onDismissRequest = { tagPendingDelete = null },
            title = { Text(text = "Delete tag?") },
            text = { Text(text = "Documents stay saved. This tag is removed from every document.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        tagPendingDelete = null
                        viewModel.deleteTag(tag.id)
                    }
                ) {
                    Text(text = "Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { tagPendingDelete = null }) {
                    Text(text = "Cancel")
                }
            }
        )
    }

    if (batchTrashPending) {
        AlertDialog(
            onDismissRequest = { batchTrashPending = false },
            title = { Text(text = "Move selected to Trash?") },
            text = { Text(text = "${selectedDocumentIds.size} documents will move to Trash and can be restored within 30 days.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        batchTrashPending = false
                        viewModel.moveDocumentsToTrash(selectedDocuments)
                        clearSelection()
                    }
                ) {
                    Text(text = "Move to Trash")
                }
            },
            dismissButton = {
                TextButton(onClick = { batchTrashPending = false }) {
                    Text(text = "Cancel")
                }
            }
        )
    }

    if (showExtractTextPicker) {
        ExtractTextDialog(
            documents = uiState.documents,
            initialDocument = null,
            onDismiss = { showExtractTextPicker = false },
            onShareText = onShareExtractedText,
            onShareCleanedText = onShareCleanedText,
            onExportCleanedText = onExportCleanedText,
            onSaveText = onSaveOcrText,
            onValidationError = viewModel::showError
        )
    }

    if (showProfileSheet) {
        ProfileSettingsBottomSheet(
            trashCount = uiState.trashDocuments.size,
            onDismiss = { showProfileSheet = false },
            onOpenSettings = {
                showProfileSheet = false
                onOpenSettings()
            },
            onOpenTrash = {
                showProfileSheet = false
                showTrashScreen = true
            }
        )
    }

    documentPendingExtractText?.let { document ->
        ExtractTextDialog(
            documents = uiState.documents,
            initialDocument = document,
            onDismiss = { documentPendingExtractText = null },
            onShareText = onShareExtractedText,
            onShareCleanedText = onShareCleanedText,
            onExportCleanedText = onExportCleanedText,
            onSaveText = onSaveOcrText,
            onValidationError = viewModel::showError
        )
    }
}
}

@Composable
private fun ScannerTopSection(
    query: String,
    onQueryChange: (String) -> Unit,
    documentCount: Int,
    pageCount: Int,
    isSaving: Boolean,
    onSmartScan: () -> Unit,
    onPdfTools: () -> Unit,
    onImportImages: () -> Unit,
    onImportFiles: () -> Unit,
    onCloud: () -> Unit,
    onPremium: () -> Unit,
    onIdCards: () -> Unit,
    onExtractText: () -> Unit,
    onAiTools: () -> Unit,
    onAllTools: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            PremiumSearchBar(
                query = query,
                onQueryChange = onQueryChange,
                modifier = Modifier.weight(1f)
            )
            TopIconButton(
                icon = Icons.Default.Cloud,
                tint = Color(0xFF4C8DFF),
                contentDescription = "Cloud sync",
                onClick = onCloud
            )
            TopIconButton(
                icon = Icons.Default.WorkspacePremium,
                tint = Color(0xFFFFB74D),
                contentDescription = "Premium",
                onClick = onPremium
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            QuickActionButton(
                icon = Icons.Default.DocumentScanner,
                label = "Smart Scan",
                iconTint = Color(0xFF20D6C7),
                onClick = onSmartScan,
                modifier = Modifier.weight(1f)
            )
            QuickActionButton(
                icon = Icons.Default.PictureAsPdf,
                label = "PDF Tools",
                iconTint = Color(0xFFFF5B5B),
                onClick = onPdfTools,
                modifier = Modifier.weight(1f)
            )
            QuickActionButton(
                icon = Icons.Default.ImageIcon,
                label = "Import Images",
                iconTint = Color(0xFF6B8DFF),
                onClick = onImportImages,
                modifier = Modifier.weight(1f)
            )
            QuickActionButton(
                icon = Icons.Default.FolderOpen,
                label = "Import Files",
                iconTint = Color(0xFF7AA7FF),
                onClick = onImportFiles,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            QuickActionButton(
                icon = Icons.Default.CreditCard,
                label = "ID Cards",
                iconTint = Color(0xFFFFC857),
                onClick = onIdCards,
                modifier = Modifier.weight(1f)
            )
            QuickActionButton(
                icon = Icons.Default.TextFields,
                label = "Extract Text",
                iconTint = Color(0xFFB388FF),
                onClick = onExtractText,
                modifier = Modifier.weight(1f)
            )
            QuickActionButton(
                icon = Icons.Default.AutoAwesome,
                label = "AI Tools",
                iconTint = Color(0xFF46D9FF),
                onClick = onAiTools,
                modifier = Modifier.weight(1f)
            )
            QuickActionButton(
                icon = Icons.Default.Apps,
                label = "All",
                iconTint = Color(0xFFFF8A65),
                onClick = onAllTools,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetadataPill(text = "$documentCount documents")
            MetadataPill(text = "$pageCount pages")
        }

        if (isSaving) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(100.dp))
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdCardFeatureScreen(
    selectedType: String,
    onSelectType: (String) -> Unit,
    validationMessage: String? = null,
    onBack: () -> Unit,
    onMakeItNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = listOf("General", "ID Card", "Driver License", "Passport", "Bank Card")

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        TopAppBar(
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back"
                    )
                }
            },
            title = {
                Text(
                    text = "ID Cards",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.background,
                titleContentColor = Color.White,
                navigationIconContentColor = Color.White
            )
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF1C1E22))
            ) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(14.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF2B3038)
                ) {
                    Text(
                        text = "A4 paper example",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFB0BEC5)
                    )
                }

                Surface(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .width(220.dp)
                        .height(150.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .border(1.dp, Color(0xFF2F3238), RoundedCornerShape(16.dp)),
                    color = Color(0xFF101214)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "ID Card Preview",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF8A99A8)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tabs.forEach { tab ->
                    FilterChip(
                        selected = selectedType == tab,
                        onClick = { onSelectType(tab) },
                        label = { Text(text = tab) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = if (selectedType == tab) Color(0xFF1E88E5) else Color(0xFF202124),
                            labelColor = if (selectedType == tab) Color.White else Color(0xFF9AA0AB)
                        )
                    )
                }
            }

            Text(
                text = "Create and share ID copies for various situations, including banking, administration, and more.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFB0BEC5)
            )
            TextButton(onClick = { /* Learn more action can be added later */ }) {
                Text(
                    text = "Learn more >",
                    color = Color(0xFF64B5F6)
                )
            }

            if (!validationMessage.isNullOrBlank()) {
                Text(
                    text = validationMessage,
                    color = Color(0xFFFF8A80),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Button(
                onClick = onMakeItNow,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
            ) {
                Text(text = "Make it now")
            }
        }
    }
}

@Composable
private fun RecentsHeader(
    onViewAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Recents",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        TextButton(onClick = onViewAll) {
            Text(text = "View All")
        }
    }
}

@Composable
private fun TopIconButton(
    icon: ImageVector,
    tint: Color,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFF202124)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp)
        )
    }
}
}

@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    iconTint: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Surface(
            modifier = Modifier.size(42.dp),
            shape = CircleShape,
            color = Color(0xFF202124)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun CenterScanFab(
    onClick: () -> Unit
) {
    val transition = rememberInfiniteTransition(label = "scan-fab-motion")
    val scale by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1300),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scan-fab-scale"
    )

    Surface(
        modifier = Modifier
            .size(64.dp)
            .scale(scale)
            .shadow(12.dp, CircleShape)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        shape = CircleShape,
        color = Color(0xFF18D18F)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Default.CameraAlt,
                contentDescription = "Scan document with auto edge detection",
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

@Composable
private fun DashboardBottomBar(
    selectedTab: BottomTab,
    onOpenHome: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenProfile: () -> Unit
) {
    NavigationBar(
        modifier = Modifier.navigationBarsPadding(),
        containerColor = Color(0xFF17181B),
        tonalElevation = 0.dp
    ) {
        NavigationBarItem(
            selected = selectedTab == BottomTab.HOME,
            onClick = onOpenHome,
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            label = { Text("Home") }
        )
        NavigationBarItem(
            selected = selectedTab == BottomTab.FILES,
            onClick = onOpenFiles,
            icon = { Icon(Icons.Default.FolderOpen, contentDescription = null) },
            label = { Text("Files") }
        )
        NavigationBarItem(
            selected = selectedTab == BottomTab.TOOLS,
            onClick = onOpenTools,
            icon = { Icon(Icons.Default.Build, contentDescription = null) },
            label = { Text("Tools") }
        )
        NavigationBarItem(
            selected = selectedTab == BottomTab.PROFILE,
            onClick = onOpenProfile,
            icon = { Icon(Icons.Default.Person, contentDescription = null) },
            label = { Text("Me") }
        )
    }
}

@Composable
private fun SelectionTopBar(
    selectedCount: Int,
    totalCount: Int,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1F2024)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close selection")
            }
            Text(
                modifier = Modifier.weight(1f),
                text = "$selectedCount selected",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE8EAED)
            )
            TextButton(
                enabled = selectedCount < totalCount,
                onClick = onSelectAll
            ) {
                Text(text = "Select all")
            }
        }
    }
}

@Composable
private fun BatchActionBar(
    selectedCount: Int,
    favoriteLabel: String,
    pinLabel: String,
    onShare: () -> Unit,
    onMove: () -> Unit,
    onTags: () -> Unit,
    onFavorite: () -> Unit,
    onPin: () -> Unit,
    onTrash: () -> Unit
) {
    Surface(
        color = Color(0xFF17181B),
        tonalElevation = 0.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            BatchActionButton("Share", Icons.Default.Share, onShare)
            BatchActionButton("Move", Icons.Default.FolderOpen, onMove)
            BatchActionButton("Tags", Icons.Default.Tag, onTags)
            BatchActionButton(favoriteLabel, Icons.Default.Star, onFavorite)
            BatchActionButton(pinLabel, Icons.Default.PushPin, onPin)
            BatchActionButton("Trash", Icons.Default.Delete, onTrash)
        }
    }
}

@Composable
private fun BatchActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            modifier = Modifier.size(21.dp),
            tint = if (label == "Trash") Color(0xFFFF8A80) else Color(0xFF49D9A8)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFFE8EAED),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ProfileTabScreen(
    trashCount: Int,
    onOpenSettings: () -> Unit,
    onOpenTrash: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Profile",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = Color.White
        )

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSettings),
            colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF202124))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Settings", fontWeight = FontWeight.SemiBold)
                    Text("Manage app preferences", style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.Settings, contentDescription = null)
            }
        }

        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenTrash),
            colors = CardDefaults.elevatedCardColors(containerColor = Color(0xFF202124))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Trash", fontWeight = FontWeight.SemiBold)
                    Text("$trashCount documents in trash", style = MaterialTheme.typography.bodySmall)
                }
                Icon(Icons.Default.RestoreFromTrash, contentDescription = null)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileSettingsBottomSheet(
    trashCount: Int,
    onDismiss: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenTrash: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Profile",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            FolderActionRow("Settings", Icons.Default.Settings, onClick = onOpenSettings)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpenTrash)
                    .padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = Color(0xFFFF8A80)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Trash", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        text = "$trashCount deleted documents",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashScreen(
    documents: List<DocumentEntity>,
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    onRestore: (DocumentEntity) -> Unit,
    onPermanentDelete: (DocumentEntity) -> Unit,
    onEmptyTrash: () -> Unit
) {
    var documentPendingDelete by remember { mutableStateOf<DocumentEntity?>(null) }
    var showEmptyTrashConfirm by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Trash") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(
                        enabled = documents.isNotEmpty(),
                        onClick = { showEmptyTrashConfirm = true }
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteForever,
                            contentDescription = "Empty Trash"
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "trash-search") {
                PremiumSearchBar(
                    query = query,
                    onQueryChange = onQueryChange
                )
            }

            if (documents.isEmpty()) {
                item(key = "trash-empty") {
                    EmptyTrashState(hasSearchQuery = query.isNotBlank())
                }
            }

            items(documents, key = { document -> "trash-${document.id}" }) { document ->
                TrashDocumentRow(
                    document = document,
                    onRestore = { onRestore(document) },
                    onPermanentDelete = { documentPendingDelete = document }
                )
            }
        }
    }

    documentPendingDelete?.let { document ->
        AlertDialog(
            onDismissRequest = { documentPendingDelete = null },
            title = { Text(text = "Delete forever?") },
            text = { Text(text = "This permanently removes the document from Trash.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        documentPendingDelete = null
                        onPermanentDelete(document)
                    }
                ) {
                    Text(text = "Delete forever")
                }
            },
            dismissButton = {
                TextButton(onClick = { documentPendingDelete = null }) {
                    Text(text = "Cancel")
                }
            }
        )
    }

    if (showEmptyTrashConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyTrashConfirm = false },
            title = { Text(text = "Empty Trash?") },
            text = { Text(text = "All deleted documents in Trash will be permanently removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showEmptyTrashConfirm = false
                        onEmptyTrash()
                    }
                ) {
                    Text(text = "Empty Trash")
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashConfirm = false }) {
                    Text(text = "Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashDocumentRow(
    document: DocumentEntity,
    onRestore: () -> Unit,
    onPermanentDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                onRestore()
                true
            } else {
                false
            }
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF173B2D))
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.RestoreFromTrash,
                        contentDescription = null,
                        tint = Color(0xFF49D9A8)
                    )
                    Text(
                        text = "Restore",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE6F7EF)
                    )
                }
            }
        }
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF1F2024),
            tonalElevation = 0.dp
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFF2B2C31)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = Color(0xFF49D9A8)
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = document.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE8EAED),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "Deleted ${formatTrashDate(document.deletedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF9AA0A6),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onRestore) {
                    Icon(
                        imageVector = Icons.Default.RestoreFromTrash,
                        contentDescription = "Restore document",
                        tint = Color(0xFF49D9A8)
                    )
                }
                IconButton(onClick = onPermanentDelete) {
                    Icon(
                        imageVector = Icons.Default.DeleteForever,
                        contentDescription = "Delete forever",
                        tint = Color(0xFFFF8A80)
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyTrashState(hasSearchQuery: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF1F2024)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = Color(0xFF9AA0A6)
            )
            Text(
                text = if (hasSearchQuery) "No deleted documents found." else "Trash is empty",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE8EAED)
            )
            Text(
                text = if (hasSearchQuery) {
                    "Try another search inside Trash."
                } else {
                    "Deleted documents stay here for 30 days."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DashboardSummary(
    documentCount: Int,
    pageCount: Int,
    isSaving: Boolean,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SummaryChip(
                    label = "Documents",
                    value = documentCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                SummaryChip(
                    label = "Pages",
                    value = pageCount.toString(),
                    modifier = Modifier.weight(1f)
                )
            }

            if (isSaving) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Finishing scan",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(100.dp))
                    )
                }
            }
        }
    }
}

@Composable
private fun SummaryChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun PremiumSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val borderColor = if (isFocused) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)
    }

    TextField(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .border(1.dp, borderColor, RoundedCornerShape(22.dp))
            .focusable(interactionSource = interactionSource),
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        shape = RoundedCornerShape(22.dp),
        interactionSource = interactionSource,
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null
            )
        },
        placeholder = { Text(text = "Search") },
        colors = TextFieldDefaults.colors(
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            focusedContainerColor = Color(0xFF1D1E22),
            unfocusedContainerColor = Color(0xFF1D1E22),
            cursorColor = MaterialTheme.colorScheme.primary
        )
    )
}

@Composable
private fun FolderSection(
    folders: List<FolderUiModel>,
    selectedFolderId: Long?,
    onFolderSelected: (Long?) -> Unit,
    onCreateFolder: () -> Unit,
    onFolderLongPress: (FolderUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Folders",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE8EAED)
            )
            TextButton(onClick = onCreateFolder) {
                Text(text = "Create folder")
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(end = 4.dp)
        ) {
            items(folders, key = { folder -> folder.id ?: -1L }) { folder ->
                FolderCard(
                    folder = folder,
                    selected = folder.id == selectedFolderId,
                    onClick = { onFolderSelected(folder.id) },
                    onLongClick = { onFolderLongPress(folder) }
                )
            }
        }
    }
}

@Composable
private fun TagSection(
    tags: List<TagUiModel>,
    selectedTagIds: Set<Long>,
    onTagSelected: (Long) -> Unit,
    onClearTags: () -> Unit,
    onCreateTag: () -> Unit,
    onTagLongPress: (TagUiModel) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Tag,
                    contentDescription = null,
                    tint = Color(0xFF49D9A8),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "Tags",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE8EAED)
                )
            }
            TextButton(onClick = onCreateTag) {
                Text(text = "Add tag")
            }
        }

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(end = 4.dp)
        ) {
            if (selectedTagIds.isNotEmpty()) {
                item(key = "all-tags") {
                    FilterChip(
                        selected = false,
                        onClick = onClearTags,
                        label = { Text(text = "All tags") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Tag,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
            }
            items(tags, key = { tag -> tag.id }) { tag ->
                TagFilterChip(
                    tag = tag,
                    selected = tag.id in selectedTagIds,
                    onClick = { onTagSelected(tag.id) },
                    onLongClick = { onTagLongPress(tag) }
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagFilterChip(
    tag: TagUiModel,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = if (tag.documentCount > 0) "${tag.name} ${tag.documentCount}" else tag.name,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        },
        leadingIcon = {
            Icon(
                imageVector = Icons.Default.Tag,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
        },
        modifier = Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onLongClick
        )
    )
}

@Composable
private fun RecentSearchesSection(
    searches: List<String>,
    onSearchSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Recent searches",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFFE8EAED)
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(searches, key = { search -> search.lowercase() }) { search ->
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSearchSelected(search) },
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF1F2024)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = Color(0xFF49D9A8)
                        )
                        Text(
                            text = search,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFE8EAED),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HighlightedDocumentsSection(
    title: String,
    documents: List<DocumentEntity>,
    icon: ImageVector,
    onOpenDocument: (DocumentEntity) -> Unit,
    onLongPressDocument: (DocumentEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color(0xFF49D9A8),
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE8EAED)
            )
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(end = 4.dp)
        ) {
            items(documents, key = { document -> "$title-${document.id}" }) { document ->
                HighlightedDocumentCard(
                    document = document,
                    onClick = { onOpenDocument(document) },
                    onLongClick = { onLongPressDocument(document) }
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun HighlightedDocumentCard(
    document: DocumentEntity,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .width(190.dp)
            .height(86.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1F2024)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(42.dp),
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF2B2C31)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Description,
                        contentDescription = null,
                        tint = Color(0xFF49D9A8)
                    )
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        modifier = Modifier.weight(1f),
                        text = document.title,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFE8EAED),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    DocumentBadges(document = document)
                }
                Text(
                    text = formatPageCount(document.pageCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF9AA0A6),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun FolderCard(
    folder: FolderUiModel,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val borderColor = if (selected) Color(0xFF49D9A8) else Color.White.copy(alpha = 0.10f)
    Surface(
        modifier = Modifier
            .width(154.dp)
            .height(86.dp)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            ),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) Color(0xFF233B35) else Color(0xFF1F2024)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = if (selected) Color(0xFF49D9A8) else Color(0xFFB8BDC4)
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFFE8EAED),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${folder.documentCount} documents",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF9AA0A6),
                    maxLines = 1
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoveToFolderBottomSheet(
    document: DocumentEntity,
    folders: List<FolderUiModel>,
    onDismiss: () -> Unit,
    onCreateFolder: () -> Unit,
    onMove: (Long?) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Move to folder",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = document.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            folders.forEach { folder ->
                FolderSheetRow(
                    folder = folder,
                    onClick = { onMove(folder.id) }
                )
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onCreateFolder
            ) {
                Text(text = "Create folder")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BatchMoveToFolderBottomSheet(
    selectedCount: Int,
    folders: List<FolderUiModel>,
    onDismiss: () -> Unit,
    onCreateFolder: () -> Unit,
    onMove: (Long?) -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Move selected",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "$selectedCount documents",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            folders.forEach { folder ->
                FolderSheetRow(
                    folder = folder,
                    onClick = { onMove(folder.id) }
                )
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                onClick = onCreateFolder
            ) {
                Text(text = "Create folder")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentLongPressActionsSheet(
    document: DocumentEntity,
    onDismiss: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePinned: () -> Unit,
    onMoveToFolder: () -> Unit,
    onEditTags: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = document.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            FolderActionRow(
                label = if (document.isFavorite) "Unfavorite" else "Favorite",
                icon = Icons.Default.Star,
                onClick = onToggleFavorite
            )
            FolderActionRow(
                label = if (document.isPinned) "Unpin" else "Pin",
                icon = Icons.Default.PushPin,
                onClick = onTogglePinned
            )
            FolderActionRow(
                label = "Move to folder",
                icon = Icons.Default.FolderOpen,
                onClick = onMoveToFolder
            )
            FolderActionRow(
                label = "Edit tags",
                icon = Icons.Default.Tag,
                onClick = onEditTags
            )
        }
    }
}

@Composable
private fun FolderSheetRow(
    folder: FolderUiModel,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1F2024)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                tint = Color(0xFF49D9A8)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = folder.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${folder.documentCount} documents",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateFolderBottomSheet(
    onDismiss: () -> Unit,
    onCreateFolder: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = "Create folder",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            TextField(
                modifier = Modifier.fillMaxWidth(),
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(text = "Folder name") }
            )
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = name.isNotBlank(),
                onClick = { onCreateFolder(name) }
            ) {
                Text(text = "Create folder")
            }
        }
    }
}

@Composable
private fun CreateTagDialog(
    onDismiss: () -> Unit,
    onCreateTag: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Add tag") },
        text = {
            TextField(
                modifier = Modifier.fillMaxWidth(),
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                placeholder = { Text(text = "Tag name") }
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onCreateTag(name) }
            ) {
                Text(text = "Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderActionsBottomSheet(
    folder: FolderUiModel,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = folder.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            FolderActionRow("Rename folder", Icons.Default.Edit, onRename)
            FolderActionRow("Delete folder", Icons.Default.Delete, onDelete)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagActionsBottomSheet(
    tag: TagUiModel,
    onDismiss: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = tag.name,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${tag.documentCount} documents",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FolderActionRow("Rename tag", Icons.Default.Edit, onRename)
            FolderActionRow("Delete tag", Icons.Default.Delete, onDelete)
        }
    }
}

@Composable
private fun FolderActionRow(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(imageVector = icon, contentDescription = null)
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun RenameFolderDialog(
    folder: FolderUiModel,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by remember(folder.id) { mutableStateOf(folder.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Rename folder") },
        text = {
            TextField(
                modifier = Modifier.fillMaxWidth(),
                value = name,
                onValueChange = { name = it },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onRename(name) }
            ) {
                Text(text = "Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel")
            }
        }
    )
}

@Composable
private fun RenameTagDialog(
    tag: TagUiModel,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit
) {
    var name by remember(tag.id) { mutableStateOf(tag.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Rename tag") },
        text = {
            TextField(
                modifier = Modifier.fillMaxWidth(),
                value = name,
                onValueChange = { name = it },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onRename(name) }
            ) {
                Text(text = "Rename")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddTagsDialog(
    title: String,
    subtitle: String,
    suggestedTags: List<TagUiModel>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    var selectedTags by remember { mutableStateOf<Set<String>>(emptySet()) }
    var customTags by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (suggestedTags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        suggestedTags.forEach { tag ->
                            val selected = selectedTags.any { name -> name.equals(tag.name, ignoreCase = true) }
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    selectedTags = if (selected) {
                                        selectedTags.filterNot { name ->
                                            name.equals(tag.name, ignoreCase = true)
                                        }.toSet()
                                    } else {
                                        selectedTags + tag.name
                                    }
                                },
                                label = { Text(text = tag.name) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Tag,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }
                TextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = customTags,
                    onValueChange = { customTags = it },
                    singleLine = true,
                    placeholder = { Text(text = "Add custom tags") }
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = selectedTags.isNotEmpty() || customTags.isNotBlank(),
                onClick = { onSave(selectedTags.toList() + parseTagText(customTags)) }
            ) {
                Text(text = "Add tags")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel")
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditTagsDialog(
    document: DocumentEntity,
    suggestedTags: List<TagUiModel>,
    onDismiss: () -> Unit,
    onSave: (List<String>) -> Unit
) {
    var selectedTags by remember(document.id) { mutableStateOf(parseTagText(document.tags).toSet()) }
    var customTags by remember(document.id) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = "Edit tags") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (suggestedTags.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        suggestedTags.forEach { tag ->
                            val selected = selectedTags.any { name -> name.equals(tag.name, ignoreCase = true) }
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    selectedTags = if (selected) {
                                        selectedTags.filterNot { name ->
                                            name.equals(tag.name, ignoreCase = true)
                                        }.toSet()
                                    } else {
                                        selectedTags + tag.name
                                    }
                                },
                                label = { Text(text = tag.name) },
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Tag,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                }
                TextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = customTags,
                    onValueChange = { customTags = it },
                    singleLine = true,
                    placeholder = { Text(text = "Add custom tags") }
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(selectedTags.toList() + parseTagText(customTags))
                }
            ) {
                Text(text = "Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "Cancel")
            }
        }
    )
}

@Composable
private fun EmptyDocumentsState(
    hasSearchQuery: Boolean,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            EmptyStateIllustration()
            Text(
                text = if (hasSearchQuery) "No matching documents found." else "No scans yet",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (hasSearchQuery) {
                    "Try a different title or OCR phrase."
                } else {
                    "Tap Scan to create your first searchable PDF."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyStateIllustration() {
    val primary = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .size(132.dp)
            .aspectRatio(1f),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = surfaceVariant,
                radius = size.minDimension * 0.46f,
                center = center
            )
            drawRoundRect(
                color = primary.copy(alpha = 0.16f),
                topLeft = Offset(size.width * 0.28f, size.height * 0.18f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.44f, size.height * 0.62f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx())
            )
            drawRoundRect(
                color = primary,
                topLeft = Offset(size.width * 0.34f, size.height * 0.32f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.32f, 6.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(100.dp.toPx())
            )
            drawRoundRect(
                color = primary.copy(alpha = 0.55f),
                topLeft = Offset(size.width * 0.34f, size.height * 0.46f),
                size = androidx.compose.ui.geometry.Size(size.width * 0.26f, 5.dp.toPx()),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(100.dp.toPx())
            )
            drawCircle(
                color = primary,
                radius = size.minDimension * 0.13f,
                center = Offset(size.width * 0.68f, size.height * 0.70f),
                style = Stroke(width = 6.dp.toPx())
            )
            drawLine(
                color = primary,
                start = Offset(size.width * 0.78f, size.height * 0.80f),
                end = Offset(size.width * 0.90f, size.height * 0.92f),
                strokeWidth = 6.dp.toPx()
            )
        }
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun DocumentItemCard(
    document: DocumentEntity,
    searchQuery: String,
    isNewest: Boolean,
    onPreviewDocument: () -> Unit,
    onOpenDocument: () -> Unit,
    onShareDocument: () -> Unit,
    onExtractText: () -> Unit,
    onMoveToFolder: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePinned: () -> Unit,
    onShowLongPressMenu: () -> Unit,
    onRenameDocument: () -> Unit,
    onDeleteDocument: () -> Unit,
    onToWord: () -> Unit,
    selectionMode: Boolean,
    selected: Boolean,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier
) {
    var menuExpanded by remember { mutableStateOf(false) }

    Box(modifier = modifier.fillMaxWidth()) {
        ElevatedCard(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = if (selected) 1.dp else 0.dp,
                    color = if (selected) Color(0xFF49D9A8) else Color.Transparent,
                    shape = RoundedCornerShape(16.dp)
                )
                .combinedClickable(
                    onClick = {
                        if (selectionMode) onToggleSelection() else onPreviewDocument()
                    },
                    onLongClick = onShowLongPressMenu
                ),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = if (selected) Color(0xFF20352E) else Color(0xFF1F2024)
            )
        ) {
            Column(
                modifier = Modifier.padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    PdfThumbnail(
                        pdfUri = document.localPdfUri,
                        modifier = Modifier.clickable {
                            if (selectionMode) onToggleSelection() else onPreviewDocument()
                        }
                    )

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    modifier = Modifier.weight(1f, fill = false),
                                    text = highlightedText(document.title, searchQuery),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFFE8EAED),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                DocumentBadges(document = document)
                            }
                            if (!selectionMode) {
                                Box {
                                    IconButton(
                                        modifier = Modifier.size(30.dp),
                                        onClick = { menuExpanded = true }
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.MoreVert,
                                            contentDescription = "Document actions",
                                            modifier = Modifier.size(18.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    DocumentActionsMenu(
                                        expanded = menuExpanded,
                                        document = document,
                                        onDismiss = { menuExpanded = false },
                                        onOpenDocument = {
                                            menuExpanded = false
                                            onOpenDocument()
                                        },
                                        onShareDocument = {
                                            menuExpanded = false
                                            onShareDocument()
                                        },
                                        onExtractText = {
                                            menuExpanded = false
                                            onExtractText()
                                        },
                                        onMoveToFolder = {
                                            menuExpanded = false
                                            onMoveToFolder()
                                        },
                                        onToggleFavorite = {
                                            menuExpanded = false
                                            onToggleFavorite()
                                        },
                                        onTogglePinned = {
                                            menuExpanded = false
                                            onTogglePinned()
                                        },
                                        onRenameDocument = {
                                            menuExpanded = false
                                            onRenameDocument()
                                        },
                                        onDeleteDocument = {
                                            menuExpanded = false
                                            onDeleteDocument()
                                        }
                                    )
                                }
                            }
                        }

                        Text(
                            text = "${formatRecentTimestamp(document.timestamp)}   |   ${formatPageCount(document.pageCount)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF9AA0A6),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        document.extractedText
                            ?.takeIf { it.isNotBlank() }
                            ?.let { extractedText ->
                                Text(
                                    modifier = Modifier.padding(top = 2.dp),
                                    text = highlightedText(extractedText, searchQuery),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFB8BDC4).copy(alpha = 0.78f),
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                    }
                }

                if (isNewest && !selectionMode) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        RecentActionButton(
                            label = "Share",
                            icon = Icons.Default.Share,
                            onClick = onShareDocument,
                            modifier = Modifier.weight(1f)
                        )
                        RecentActionButton(
                            label = "To Word",
                            icon = Icons.Default.TextFields,
                            onClick = onToWord,
                            modifier = Modifier.weight(1f)
                        )
                        RecentActionButton(
                            label = "View",
                            icon = Icons.AutoMirrored.Filled.OpenInNew,
                            onClick = onPreviewDocument,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
        if (selectionMode) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                shape = CircleShape,
                color = Color(0xFF101114).copy(alpha = 0.92f)
            ) {
                Checkbox(
                    checked = selected,
                    onCheckedChange = { onToggleSelection() }
                )
            }
        }
    }
}

@Composable
private fun RecentActionButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .height(32.dp)
            .clip(RoundedCornerShape(9.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(9.dp),
        color = Color(0xFF34363B)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = Color(0xFF49D9A8)
            )
            Spacer(modifier = Modifier.width(5.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE6F7EF),
                maxLines = 1
            )
        }
    }
}

@Composable
private fun DocumentBadges(document: DocumentEntity) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DocumentTypeBadge(isPdf = isPdfActionSupported(document.localPdfUri))
        if (document.isPinned) {
            Icon(
                imageVector = Icons.Default.PushPin,
                contentDescription = "Pinned",
                tint = Color(0xFF7AA7FF),
                modifier = Modifier.size(15.dp)
            )
        }
        if (document.isFavorite) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = "Favorite",
                tint = Color(0xFFFFD54F),
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun DocumentTypeBadge(isPdf: Boolean) {
    val container = if (isPdf) Color(0xFF243A31) else Color(0xFF34323A)
    val content = if (isPdf) Color(0xFF49D9A8) else Color(0xFFFFD166)
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = container
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
            text = if (isPdf) "PDF" else "IMAGE",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = content,
            maxLines = 1
        )
    }
}

@Composable
private fun PdfThumbnail(
    pdfUri: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var thumbnail by remember(pdfUri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    var loadFinished by remember(pdfUri) { mutableStateOf(false) }

    LaunchedEffect(pdfUri) {
        loadFinished = false
        thumbnail = PdfThumbnailLoader.loadThumbnail(context.applicationContext, pdfUri)
        loadFinished = true
    }

    Surface(
        modifier = modifier
            .width(64.dp)
            .height(82.dp)
            .border(
                width = 1.dp,
                color = Color.White.copy(alpha = 0.12f),
                shape = RoundedCornerShape(6.dp)
            ),
        shape = RoundedCornerShape(6.dp),
        color = Color(0xFF2A2C31)
    ) {
        Box(contentAlignment = Alignment.Center) {
            val renderedThumbnail = thumbnail
            if (renderedThumbnail != null) {
                Image(
                    bitmap = renderedThumbnail.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(6.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = if (loadFinished) {
                        "PDF thumbnail unavailable"
                    } else {
                        "Loading PDF thumbnail"
                    },
                    tint = Color(0xFF8F949C)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentPreviewDialog(
    document: DocumentEntity,
    onDismiss: () -> Unit,
    onAddPage: () -> Unit,
    onOpenDocument: () -> Unit,
    onShareDocument: () -> Unit,
    onEditPdfDocument: () -> Unit,
    onSendDocumentToPc: () -> Unit,
    onSaveDocumentExport: () -> Unit,
    onPrintDocument: () -> Unit,
    onConvertImageToPdf: () -> Unit,
    onExportText: () -> Unit,
    onRenameDocument: () -> Unit,
    onDeleteDocument: () -> Unit,
    onUnavailableAction: (String) -> Unit
) {
    val context = LocalContext.current
    var previewPages by remember(document.localPdfUri) { mutableStateOf<List<android.graphics.Bitmap>>(emptyList()) }
    var loadFinished by remember(document.localPdfUri) { mutableStateOf(false) }

    LaunchedEffect(document.localPdfUri) {
        loadFinished = false
        previewPages = PdfThumbnailLoader.loadPreviewPages(context.applicationContext, document.localPdfUri)
        loadFinished = true
    }

    var zoom by remember(document.localPdfUri) { mutableStateOf(1f) }
    var panX by remember(document.localPdfUri) { mutableStateOf(0f) }
    var panY by remember(document.localPdfUri) { mutableStateOf(0f) }
    var showActionsSheet by remember(document.localPdfUri) { mutableStateOf(false) }
    val pdfActionsAvailable = remember(document.localPdfUri) {
        isPdfActionSupported(document.localPdfUri)
    }
    BackHandler {
        if (showActionsSheet) {
            showActionsSheet = false
        } else {
            onDismiss()
        }
    }
    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextZoom = (zoom * zoomChange).coerceIn(1f, 4f)
        zoom = nextZoom
        if (nextZoom == 1f) {
            panX = 0f
            panY = 0f
        } else {
            panX += panChange.x
            panY += panChange.y
        }
    }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        navigationIcon = {
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Close preview"
                                )
                            }
                        },
                        title = {
                            Text(
                                text = document.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        actions = {
                            IconButton(onClick = onRenameDocument) {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = "Rename document"
                                )
                            }
                            IconButton(onClick = { showActionsSheet = true }) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "More actions"
                                )
                            }
                        }
                    )
                },
                bottomBar = {
                    DocumentPreviewToolbar(
                        onAdd = onAddPage,
                        onEdit = onRenameDocument,
                        onShare = onShareDocument,
                        onExportText = onExportText,
                        onSign = { onUnavailableAction("Sign coming soon") }
                    )
                }
            ) { innerPadding ->
                val pages = previewPages
                if (pages.isNotEmpty()) {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                            .background(Color(0xFF101114))
                            .graphicsLayer {
                                scaleX = zoom
                                scaleY = zoom
                                translationX = panX
                                translationY = panY
                            }
                            .transformable(transformableState),
                        contentPadding = PaddingValues(
                            start = 18.dp,
                            top = 16.dp,
                            end = 18.dp,
                            bottom = 22.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        itemsIndexed(
                            items = pages,
                            key = { index, _ -> "${document.id}-page-$index" }
                        ) { index, bitmap ->
                            PdfPreviewPage(
                                bitmap = bitmap,
                                pageNumber = index + 1,
                                pageCount = pages.size
                            )
                        }
                    }
                } else {
                    Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .background(Color(0xFF101114))
                        .padding(horizontal = 20.dp, vertical = 16.dp),
                    contentAlignment = Alignment.Center
                    ) {
                        PreviewPlaceholder(loadFinished = loadFinished)
                    }
                }
            }
        }
    }

    if (showActionsSheet) {
        PreviewActionsBottomSheet(
            document = document,
            pdfActionsAvailable = pdfActionsAvailable,
            onDismiss = { showActionsSheet = false },
            onEditPdf = {
                showActionsSheet = false
                if (pdfActionsAvailable) {
                    onEditPdfDocument()
                } else {
                    onUnavailableAction(PDF_ONLY_ACTION_MESSAGE)
                }
            },
            onSendToPc = {
                showActionsSheet = false
                onSendDocumentToPc()
            },
            onSaveToGallery = {
                showActionsSheet = false
                onSaveDocumentExport()
            },
            onPrint = {
                showActionsSheet = false
                if (pdfActionsAvailable) {
                    onPrintDocument()
                } else {
                    onUnavailableAction(PDF_ONLY_ACTION_MESSAGE)
                }
            },
            onShare = {
                showActionsSheet = false
                onShareDocument()
            },
            onDelete = {
                showActionsSheet = false
                onDeleteDocument()
            },
            onConvertToPdf = {
                showActionsSheet = false
                onConvertImageToPdf()
            }
        )
    }
}

@Composable
private fun PdfPreviewPage(
    bitmap: android.graphics.Bitmap,
    pageNumber: Int,
    pageCount: Int
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f / 1.414f)
            .shadow(6.dp, RoundedCornerShape(2.dp)),
        shape = RoundedCornerShape(2.dp),
        color = Color.White
    ) {
        Box {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "PDF page $pageNumber of $pageCount",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp),
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.62f)
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                    text = "$pageNumber/$pageCount",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun DocumentPreviewToolbar(
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onExportText: () -> Unit,
    onSign: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF202124))
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        PreviewToolbarAction(
            label = "Add",
            icon = Icons.Default.CameraAlt,
            onClick = onAdd
        )
        PreviewToolbarAction(
            label = "Edit",
            icon = Icons.Default.Edit,
            onClick = onEdit
        )
        PreviewToolbarAction(
            label = "Share",
            icon = Icons.Default.Share,
            onClick = onShare
        )
        PreviewToolbarAction(
            label = "To Word",
            icon = Icons.Default.TextFields,
            onClick = onExportText
        )
        PreviewToolbarAction(
            label = "Sign",
            icon = Icons.Default.Edit,
            onClick = onSign
        )
    }
}

@Composable
private fun PreviewToolbarAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(68.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(23.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PreviewActionsBottomSheet(
    document: DocumentEntity,
    pdfActionsAvailable: Boolean,
    onDismiss: () -> Unit,
    onEditPdf: () -> Unit,
    onSendToPc: () -> Unit,
    onSaveToGallery: () -> Unit,
    onPrint: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onConvertToPdf: () -> Unit
) {
    val isImageDocument = !pdfActionsAvailable
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PdfThumbnail(pdfUri = document.localPdfUri)
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                modifier = Modifier.weight(1f, fill = false),
                                text = document.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            DocumentTypeBadge(isPdf = pdfActionsAvailable)
                        }
                        Text(
                            text = "${document.pageCount} pages",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                TextButton(onClick = onDismiss) {
                    Text(text = "Close")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PreviewSheetAction(
                    label = "Edit PDF",
                    icon = Icons.Default.Edit,
                    enabled = pdfActionsAvailable,
                    disabledMessage = PDF_ONLY_ACTION_MESSAGE,
                    onClick = onEditPdf,
                    modifier = Modifier.weight(1f)
                )
                PreviewSheetAction(
                    label = "Send to PC",
                    icon = Icons.AutoMirrored.Filled.Send,
                    onClick = onSendToPc,
                    modifier = Modifier.weight(1f)
                )
                PreviewSheetAction(
                    label = "Save",
                    icon = Icons.Default.SaveAlt,
                    onClick = onSaveToGallery,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PreviewSheetAction(
                    label = "Print",
                    icon = Icons.Default.Print,
                    enabled = pdfActionsAvailable,
                    disabledMessage = PDF_ONLY_ACTION_MESSAGE,
                    onClick = onPrint,
                    modifier = Modifier.weight(1f)
                )
                PreviewSheetAction(
                    label = "Share",
                    icon = Icons.Default.Share,
                    onClick = onShare,
                    modifier = Modifier.weight(1f)
                )
                PreviewSheetAction(
                    label = "Delete",
                    icon = Icons.Default.Delete,
                    onClick = onDelete,
                    modifier = Modifier.weight(1f)
                )
            }

            if (isImageDocument) {
                PreviewSheetAction(
                    label = "Convert To PDF",
                    icon = Icons.Default.PictureAsPdf,
                    onClick = onConvertToPdf,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun PreviewSheetAction(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    enabled: Boolean = true,
    disabledMessage: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    Column(
        modifier = modifier
            .height(88.dp)
            .clip(RoundedCornerShape(18.dp))
            .clickable(
                enabled = true,
                onClick = {
                    if (enabled) {
                        onClick()
                    } else {
                        android.widget.Toast
                            .makeText(
                                context,
                                disabledMessage ?: PDF_ONLY_ACTION_MESSAGE,
                                android.widget.Toast.LENGTH_SHORT
                            )
                            .show()
                    }
                }
            )
            .alpha(if (enabled) 1f else 0.48f)
            .padding(horizontal = 6.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        Surface(
            modifier = Modifier.size(48.dp),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = disabledMessage ?: label,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private const val PDF_ONLY_ACTION_MESSAGE = "Available for PDF documents only."

private fun isPdfActionSupported(uriValue: String): Boolean {
    val lower = uriValue.lowercase(Locale.US)
    return !lower.endsWith(".jpg") &&
        !lower.endsWith(".jpeg") &&
        !lower.endsWith(".png") &&
        !lower.contains("/imported_images/")
}

@Composable
private fun PreviewPlaceholder(
    loadFinished: Boolean
) {
    ElevatedCard(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Text(
                text = if (loadFinished) "Preview unavailable" else "Loading preview",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (loadFinished) {
                    "The PDF may be missing or unreadable."
                } else {
                    "Rendering the first page safely."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MetadataPill(
    text: String
) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
    }
}

@Composable
private fun DocumentActionsMenu(
    expanded: Boolean,
    document: DocumentEntity,
    onDismiss: () -> Unit,
    onOpenDocument: () -> Unit,
    onShareDocument: () -> Unit,
    onExtractText: () -> Unit,
    onMoveToFolder: () -> Unit,
    onToggleFavorite: () -> Unit,
    onTogglePinned: () -> Unit,
    onRenameDocument: () -> Unit,
    onDeleteDocument: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss
    ) {
        DropdownMenuItem(
            text = { Text(text = "Open PDF") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null
                )
            },
            onClick = onOpenDocument
        )
        DropdownMenuItem(
            text = { Text(text = "Share PDF") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = null
                )
            },
            onClick = onShareDocument
        )
        DropdownMenuItem(
            text = { Text(text = "Extract Text") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.TextFields,
                    contentDescription = null
                )
            },
            onClick = onExtractText
        )
        DropdownMenuItem(
            text = { Text(text = "Move to folder") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null
                )
            },
            onClick = onMoveToFolder
        )
        DropdownMenuItem(
            text = { Text(text = if (document.isFavorite) "Unfavorite" else "Favorite") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = null
                )
            },
            onClick = onToggleFavorite
        )
        DropdownMenuItem(
            text = { Text(text = if (document.isPinned) "Unpin" else "Pin") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.PushPin,
                    contentDescription = null
                )
            },
            onClick = onTogglePinned
        )
        DropdownMenuItem(
            text = { Text(text = "Rename PDF") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null
                )
            },
            onClick = onRenameDocument
        )
        DropdownMenuItem(
            text = { Text(text = "Delete document") },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null
                )
            },
            onClick = onDeleteDocument
        )
    }
}

@Composable
private fun LoadingDocumentPlaceholder(
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmer-offset"
    )
    val shimmerBrush = Brush.linearGradient(
        colors = listOf(
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        start = Offset(offset - 320f, 0f),
        end = Offset(offset, 260f)
    )

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(shimmerBrush)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.72f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(100.dp))
                        .background(shimmerBrush)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(shimmerBrush)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.46f)
                        .height(12.dp)
                        .clip(RoundedCornerShape(100.dp))
                        .background(shimmerBrush)
                )
            }
        }
    }
}

private sealed interface DashboardItem {
    data class Document(
        val document: DocumentEntity,
        val isNewest: Boolean
    ) : DashboardItem
    data class NativeAd(val position: Int) : DashboardItem
}

private fun dashboardItems(
    documents: List<DocumentEntity>,
    includeAds: Boolean
): List<DashboardItem> {
    return buildList {
        documents.forEachIndexed { index, document ->
            add(DashboardItem.Document(document = document, isNewest = index == 0))
            if (includeAds && (index + 1) % 4 == 0) {
                add(DashboardItem.NativeAd(position = index + 1))
            }
        }
    }
}

private fun parseTagText(tags: String): List<String> {
    return tags.split(',', '#')
        .map { tag -> tag.trim().trimStart('#').replace(Regex("\\s+"), " ") }
        .filter { tag -> tag.isNotBlank() }
        .distinctBy { tag -> tag.lowercase() }
}

private fun highlightedText(
    text: String,
    query: String
) = buildAnnotatedString {
    val terms = query.trim()
        .split(Regex("\\s+"))
        .map { term -> term.trim() }
        .filter { term -> term.length >= 2 }
        .distinctBy { term -> term.lowercase() }

    if (terms.isEmpty()) {
        append(text)
        return@buildAnnotatedString
    }

    var index = 0
    while (index < text.length) {
        val nextMatch = terms
            .mapNotNull { term ->
                val matchIndex = text.indexOf(term, startIndex = index, ignoreCase = true)
                if (matchIndex >= 0) matchIndex to term.length else null
            }
            .minByOrNull { it.first }

        if (nextMatch == null) {
            append(text.substring(index))
            break
        }

        val (matchIndex, matchLength) = nextMatch
        if (matchIndex > index) {
            append(text.substring(index, matchIndex))
        }
        withStyle(
            SpanStyle(
                color = Color(0xFF101114),
                background = Color(0xFF49D9A8)
            )
        ) {
            append(text.substring(matchIndex, (matchIndex + matchLength).coerceAtMost(text.length)))
        }
        index = (matchIndex + matchLength).coerceAtLeast(index + 1)
    }
}

private fun formatRecentTimestamp(timestamp: Long): String {
    return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
}

private fun formatTrashDate(timestamp: Long?): String {
    return timestamp?.let { formatRecentTimestamp(it) } ?: "recently"
}

private fun formatPageCount(pageCount: Int): String {
    return if (pageCount == 1) "1 page" else "$pageCount pages"
}





