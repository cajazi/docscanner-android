package com.dev.docscannerpdf.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.dev.docscannerpdf.MainActivity
import com.dev.docscannerpdf.data.local.APP_DATABASE_VERSION
import com.dev.docscannerpdf.domain.backup.BackupRepository
import com.dev.docscannerpdf.navigation.canHandleSystemBack
import com.dev.docscannerpdf.navigation.handleSystemBack
import com.dev.docscannerpdf.ui.debug.ApiHealthScreen
import com.dev.docscannerpdf.ui.library.DocumentLibraryScreen
import com.dev.docscannerpdf.ui.library.buildDocumentLibraryState
import com.dev.docscannerpdf.ui.result.DocumentResultScreen
import com.dev.docscannerpdf.ui.theme.DocScannerPDFTheme
import com.dev.docscannerpdf.util.AppConstants
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import kotlinx.coroutines.launch

@Composable
internal fun DocScannerApp(host: MainActivity) {
    DocScannerPDFTheme {            
                val uiState by host.viewModel.uiState.collectAsState()
                val premiumState by host.billingRepository.premiumState.collectAsState()
                val cloudSyncState by host.cloudSyncRepository.state.collectAsState()
                val observabilitySettings by host.analyticsRepository.settings.collectAsState()
                val reviewState = host.imageImportReview
                val editorState = host.pendingImageImport
                val previewState = host.importedImagePreview
                val viewerDocument = host.pdfViewerDocument?.let { selectedDocument ->
                    uiState.documents.firstOrNull { it.id == selectedDocument.id } ?: selectedDocument
                }
                BackHandler(enabled = host.canHandleSystemBack()) {
                    host.handleSystemBack()
                }
                if (host.appLockSettings.lockEnabled && !host.appUnlocked) {
                    AppLockScreen(
                        pinLength = APP_PIN_LENGTH,
                        biometricsAvailable = host.canUseBiometrics(),
                        biometricsEnabled = host.appLockSettings.biometricsEnabled,
                        errorMessage = host.appLockError,
                        onPinComplete = host::unlockWithPin,
                        onBiometricClick = host::showBiometricPrompt
                    )
                } else if (host.showOnboarding) {
                    OnboardingScreen(
                        onComplete = host::completeOnboarding
                    )
                } else if (host.showCloudSync) {
                    CloudSyncScreen(
                        state = cloudSyncState,
                        isPremium = premiumState.isPremium,
                        onBack = { host.showCloudSync = false },
                        onSignIn = host::startGoogleSignIn,
                        onSignOut = host::signOutFromGoogle,
                        onPremium = { host.openPremium() },
                        onSyncEnabledChange = host.cloudSyncRepository::setSyncEnabled,
                        onSyncNow = { host.cloudSyncRepository.enqueueSync() }
                    )
                } else if (host.showPremium) {
                    PremiumScreen(
                        state = premiumState,
                        onBack = { host.showPremium = false },
                        onChoosePlan = { plan -> host.billingRepository.launchPurchase(host, plan) },
                        onRestorePurchases = host.billingRepository::restorePurchases,
                        onManageSubscription = { host.billingRepository.manageSubscription(host) }
                    )
                } else if (host.showFeatureValidation) {
                    FeatureValidationScreen(
                        databaseVersion = APP_DATABASE_VERSION,
                        migrationStatus = AppConstants.ROOM_MIGRATION_STATUS,
                        backupSchemaVersion = BackupRepository.SCHEMA_VERSION,
                        biometricsAvailable = host.canUseBiometrics(),
                        dangerousPermissionsDeclared = host.hasDangerousPermissionsDeclared(),
                        onBack = { host.showFeatureValidation = false }
                    )
                } else if (host.showApiHealth) {
                    ApiHealthScreen(
                        onBack = { host.showApiHealth = false }
                    )
                } else if (host.showBackupRestore) {
                    BackupRestoreScreen(
                        lastBackupInfo = host.lastBackupInfo,
                        isProcessing = host.backupProcessing,
                        statusMessage = host.backupStatusMessage,
                        pendingRestore = host.pendingRestoreArchive,
                        onBack = { host.showBackupRestore = false },
                        onCreateBackup = {
                            host.backupStatusMessage = null
                            host.createBackupLauncher.launch(host.defaultBackupFileName())
                        },
                        onRestoreBackup = {
                            host.backupStatusMessage = null
                            host.restoreBackupLauncher.launch(
                                arrayOf("application/zip", "application/json", "application/octet-stream")
                            )
                        },
                        onConfirmRestore = host::restorePendingBackup,
                        onDismissRestore = { host.pendingRestoreArchive = null }
                    )
                } else if (host.showAppLockSettings) {
                    AppLockSettingsScreen(
                        settings = host.appLockSettings,
                        observabilitySettings = observabilitySettings,
                        biometricsAvailable = host.canUseBiometrics(),
                        onBack = { host.showAppLockSettings = false },
                        onCreatePin = { pin, enableBiometrics ->
                            host.appLockRepository.savePin(pin)
                            host.appLockRepository.setBiometricsEnabled(enableBiometrics && host.canUseBiometrics())
                            host.refreshAppLockSettings()
                            host.appUnlocked = true
                            host.appLockMessage = "App Lock enabled."
                        },
                        onChangePin = { pin ->
                            host.appLockRepository.savePin(pin)
                            host.refreshAppLockSettings()
                            host.appUnlocked = true
                            host.appLockMessage = "PIN updated."
                        },
                        onLockEnabledChange = { enabled ->
                            host.appLockRepository.setLockEnabled(enabled)
                            host.refreshAppLockSettings()
                            host.appUnlocked = !enabled || host.appUnlocked
                        },
                        onBiometricsEnabledChange = { enabled ->
                            if (enabled && !host.canUseBiometrics()) {
                                host.appLockMessage = "Biometrics are not available on this device."
                            } else {
                                host.appLockRepository.setBiometricsEnabled(enabled)
                                host.refreshAppLockSettings()
                            }
                        },
                        onDisableLock = {
                            host.appLockRepository.clearLock()
                            host.refreshAppLockSettings()
                            host.appUnlocked = true
                            host.appLockMessage = "App Lock disabled."
                        },
                        onAnalyticsEnabledChange = host.analyticsRepository::setAnalyticsEnabled,
                        onCrashReportingEnabledChange = host.analyticsRepository::setCrashReportingEnabled,
                        onViewOnboardingAgain = host::viewOnboardingAgain,
                        onOpenBackupRestore = { host.showBackupRestore = true },
                        onOpenCloudSync = { host.showCloudSync = true },
                        onOpenFeatureValidation = { host.showFeatureValidation = true },
                        onOpenApiHealth = { host.showApiHealth = true }
                    )
                } else if (viewerDocument != null) {
                    PdfViewerScreen(
                        document = viewerDocument,
                        onBack = { host.pdfViewerDocument = null },
                        onShare = { host.sharePdf(viewerDocument) },
                        onExportText = { host.exportTextDocument(viewerDocument) },
                        onRename = { host.viewerDocumentPendingRename = viewerDocument },
                        onDelete = { host.viewerDocumentPendingDelete = viewerDocument }
                    )
                    host.viewerDocumentPendingRename?.let { document ->
                        RenameDocumentDialog(
                            documents = uiState.documents,
                            initialDocument = document,
                            onDismiss = { host.viewerDocumentPendingRename = null },
                            onRename = host.viewModel::renameDocument,
                            onValidationError = host.viewModel::showError
                        )
                    }
                    host.viewerDocumentPendingDelete?.let { document ->
                        AlertDialog(
                            onDismissRequest = { host.viewerDocumentPendingDelete = null },
                            title = { Text(text = "Move to Trash?") },
                            text = { Text(text = "This document moves to Trash and can be restored within 30 days.") },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        host.viewerDocumentPendingDelete = null
                                        host.pdfViewerDocument = null
                                        host.deleteDocument(document)
                                    }
                                ) {
                                    Text(text = "Move to Trash")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { host.viewerDocumentPendingDelete = null }) {
                                    Text(text = "Cancel")
                                }
                            }
                        )
                    }
                } else if (host.showLockPdf) {
                    LockPdfScreen(
                        state = host.lockPdfState,
                        onBack = host::closeLockPdf,
                        onPickPdf = { host.lockPdfLauncher.launch(arrayOf(AppConstants.PDF_MIME_TYPE)) },
                        onPasswordChange = { password -> host.lockPdfState = host.lockPdfState.copy(password = password) },
                        onLockPdf = host::lockSelectedPdf
                    )
                } else if (host.showUnlockPdf) {
                    UnlockPdfScreen(
                        state = host.unlockPdfState,
                        onBack = host::closeUnlockPdf,
                        onPickPdf = { host.unlockPdfLauncher.launch(arrayOf(AppConstants.PDF_MIME_TYPE)) },
                        onPasswordChange = { password -> host.unlockPdfState = host.unlockPdfState.copy(password = password) },
                        onUnlockPdf = host::unlockSelectedPdf
                    )
                } else if (host.showSignPdf) {
                    SignPdfScreen(
                        state = host.signPdfState,
                        onBack = host::closeSignPdf,
                        onPickPdf = { host.signPdfLauncher.launch(arrayOf(AppConstants.PDF_MIME_TYPE)) },
                        onSelectPage = { pageIndex ->
                            host.signPdfState = host.signPdfState.copy(selectedPageIndex = pageIndex)
                        },
                        onSignatureSaved = { bitmap ->
                            host.signPdfState = host.signPdfState.copy(
                                signatureBitmap = bitmap,
                                message = "Signature ready. Drag it into position."
                            )
                        },
                        onClearSignature = {
                            host.signPdfState.signatureBitmap?.recycle()
                            host.signPdfState = host.signPdfState.copy(
                                signatureBitmap = null,
                                message = "Signature cleared."
                            )
                        },
                        onMoveSignature = { offsetX, offsetY ->
                            host.signPdfState = host.signPdfState.copy(
                                signatureOffsetX = offsetX,
                                signatureOffsetY = offsetY
                            )
                        },
                        onResizeSignature = { scale ->
                            host.signPdfState = host.signPdfState.copy(signatureScale = scale)
                        },
                        onExport = host::exportSignedPdf
                    )
                } else if (host.showWatermarkPdf) {
                    WatermarkPdfScreen(
                        state = host.watermarkPdfState,
                        onBack = host::closeWatermarkPdf,
                        onPickPdf = { host.watermarkPdfLauncher.launch(arrayOf(AppConstants.PDF_MIME_TYPE)) },
                        onTextChange = { text -> host.watermarkPdfState = host.watermarkPdfState.copy(watermarkText = text) },
                        onOpacityChange = { opacity -> host.watermarkPdfState = host.watermarkPdfState.copy(opacity = opacity) },
                        onRotationChange = { rotation -> host.watermarkPdfState = host.watermarkPdfState.copy(rotation = rotation) },
                        onPositionChange = { position -> host.watermarkPdfState = host.watermarkPdfState.copy(position = position) },
                        onApply = host::applyWatermarkPdf
                    )
                } else if (host.showPdfToWord) {
                    PdfToWordScreen(
                        state = host.pdfToWordState,
                        onBack = host::closePdfToWord,
                        onPickPdf = { host.pdfToWordLauncher.launch(arrayOf(AppConstants.PDF_MIME_TYPE)) },
                        onConvert = host::convertPdfToWord
                    )
                } else if (host.showPdfToImages) {
                    PdfToImagesScreen(
                        state = host.pdfToImagesState,
                        onBack = host::closePdfToImages,
                        onPickPdf = { host.pdfToImagesLauncher.launch(arrayOf(AppConstants.PDF_MIME_TYPE)) },
                        onShareImages = host::sharePdfImages,
                        onSaveToApp = host::savePdfImagesToApp
                    )
                } else if (host.showEditPdf) {
                    EditPdfScreen(
                        state = host.editPdfState,
                        onBack = { host.closeEditPdf(returnToTools = true) },
                        onPickPdf = { host.editPdfLauncher.launch(arrayOf(AppConstants.PDF_MIME_TYPE)) },
                        onTitleChange = { title -> host.editPdfState = host.editPdfState.copy(title = title) },
                        onTogglePage = { pageId -> host.toggleEditPdfPage(pageId) },
                        onMovePage = { index, direction -> host.moveEditPdfPage(index, direction) },
                        onDeleteSelected = host::deleteSelectedEditPdfPages,
                        onRotateSelected = host::rotateSelectedEditPdfPages,
                        onDuplicateSelected = host::duplicateSelectedEditPdfPages,
                        onSave = host::saveEditedPdf
                    )
                } else if (host.showMergePdf) {
                    MergePdfScreen(
                        state = host.mergePdfState,
                        onBack = host::closeMergePdf,
                        onPickPdfs = { host.mergePdfLauncher.launch(arrayOf(AppConstants.PDF_MIME_TYPE)) },
                        onMoveUp = { index -> host.moveMergeItem(index, -1) },
                        onMoveDown = { index -> host.moveMergeItem(index, 1) },
                        onRemove = { index ->
                            host.mergePdfState = host.mergePdfState.copy(
                                items = host.mergePdfState.items.toMutableList().also { it.removeAt(index) }
                            )
                        },
                        onMerge = host::mergeSelectedPdfs
                    )
                } else if (host.showSplitPdf) {
                    SplitPdfScreen(
                        state = host.splitPdfState,
                        onBack = host::closeSplitPdf,
                        onPickPdf = { host.splitPdfLauncher.launch(arrayOf(AppConstants.PDF_MIME_TYPE)) },
                        onModeChange = { mode -> host.splitPdfState = host.splitPdfState.copy(mode = mode) },
                        onRangeChange = { range -> host.splitPdfState = host.splitPdfState.copy(customRange = range) },
                        onTogglePage = { pageIndex ->
                            host.splitPdfState = host.splitPdfState.copy(
                                selectedPages = if (pageIndex in host.splitPdfState.selectedPages) {
                                    host.splitPdfState.selectedPages - pageIndex
                                } else {
                                    host.splitPdfState.selectedPages + pageIndex
                                }
                            )
                        },
                        onSplit = host::splitSelectedPdf
                    )
                } else if (host.showImagesToPdf) {
                    ImagesToPdfScreen(
                        selectedImageCount = host.imagesToPdfState.imageUris.size,
                        isConverting = host.imagesToPdfState.isConverting,
                        message = host.imagesToPdfState.message,
                        onBack = host::closeImagesToPdf,
                        onPickImages = { host.imagesToPdfLauncher.launch("image/*") },
                        onConvert = host::convertImagesToPdf
                    )
                } else if (host.showCompressPdf) {
                    CompressPdfScreen(
                        state = host.compressPdfState,
                        onBack = host::closeCompressPdf,
                        onPickPdf = { host.compressPdfLauncher.launch(arrayOf(AppConstants.PDF_MIME_TYPE)) },
                        onCompress = host::compressSelectedPdf,
                        onShareCompressedPdf = host::shareCompressedPdf
                    )
                } else if (host.showAiTools) {
                    AiToolsScreen(
                        onBack = { host.showAiTools = false },
                        onSmartScan = {
                            host.startDocumentScanner(pageLimit = 20)
                            host.showAiTools = false
                        },
                        onExtractText = {
                            host.showPdfTools = true
                            host.showAiTools = false
                        },
                        onPdfToWord = {
                            host.showPdfToWord = true
                            host.showAiTools = false
                        },
                        onOpenAllTools = {
                            host.showPdfTools = true
                            host.showAiTools = false
                        },
                        onComingSoon = host.viewModel::showError
                    )
                } else if (host.showPdfTools) {
                    PDFToolsScreen(
                        documents = uiState.documents,
                        onBack = { host.showPdfTools = false },
                        onMergePdf = {
                            host.showMergePdf = true
                            host.showPdfTools = false
                        },
                        onSplitPdf = {
                            host.showSplitPdf = true
                            host.showPdfTools = false
                        },
                        onCompressPdf = {
                            host.showCompressPdf = true
                            host.showPdfTools = false
                        },
                        onPdfToImages = {
                            host.showPdfToImages = true
                            host.showPdfTools = false
                        },
                        onImagesToPdf = { host.imagesToPdfLauncher.launch("image/*") },
                        onEditPdf = {
                            host.returnToPdfToolsAfterEdit = true
                            host.showEditPdf = true
                            host.showPdfTools = false
                        },
                        onLockPdf = {
                            host.showLockPdf = true
                            host.showPdfTools = false
                        },
                        onUnlockPdf = {
                            host.showUnlockPdf = true
                            host.showPdfTools = false
                        },
                        onSignPdf = {
                            host.showSignPdf = true
                            host.showPdfTools = false
                        },
                        onWatermarkPdf = {
                            host.showWatermarkPdf = true
                            host.showPdfTools = false
                        },
                        onPdfToWord = {
                            host.showPdfToWord = true
                            host.showPdfTools = false
                        },
                        onRenameDocument = host.viewModel::renameDocument,
                        onShareExtractedText = host::shareExtractedText,
                        onShareCleanedText = host::shareCleanedText,
                        onExportCleanedText = host::exportCleanedText,
                        onSaveOcrText = host.viewModel::updateDocumentOcrText,
                        onComingSoon = { message -> host.pdfToolsMessage = message }
                    )
                    host.pdfToolsMessage?.let { message ->
                        AlertDialog(
                            onDismissRequest = { host.pdfToolsMessage = null },
                            text = { Text(text = message) },
                            confirmButton = {
                                TextButton(onClick = { host.pdfToolsMessage = null }) {
                                    Text(text = "OK")
                                }
                            }
                        )
                    }
                } else if (reviewState != null) {
                    ImageImportReviewScreen(
                        imageUris = reviewState.imageUris,
                        currentIndex = reviewState.currentIndex,
                        selectedIndices = reviewState.selectedIndices,
                        onBack = { host.imageImportReview = null },
                        onCurrentIndexChange = { index ->
                            host.imageImportReview = host.imageImportReview?.copy(currentIndex = index)
                        },
                        onToggleSelected = { index ->
                            host.imageImportReview = host.imageImportReview?.let { state ->
                                val nextSelected = if (index in state.selectedIndices) {
                                    state.selectedIndices - index
                                } else {
                                    state.selectedIndices + index
                                }
                                state.copy(selectedIndices = nextSelected)
                            }
                        },
                        onImportSelected = { host.importSelectedReviewImage() }
                    )
                } else if (editorState != null) {
                    ImageImportEditor(
                        imageUri = editorState.imageUri,
                        title = editorState.title,
                        extractedText = editorState.extractedText,
                        isExtractingText = editorState.isExtractingText,
                        rotationDegrees = editorState.rotationDegrees,
                        onBack = { host.pendingImageImport = null },
                        onTitleChange = { title ->
                            host.pendingImageImport = host.pendingImageImport?.copy(title = title)
                        },
                        onImport = { host.imageImportLauncher.launch("image/*") },
                        onRotateLeft = {
                            host.pendingImageImport = host.pendingImageImport?.copy(
                                rotationDegrees = ((host.pendingImageImport?.rotationDegrees ?: 0f) - 90f) % 360f
                            )
                        },
                        onCrop = {
                            val uri = editorState.imageUri
                            if (uri == null) {
                                host.imageEditorMessage = "No image to crop"
                                return@ImageImportEditor
                            }
                            host.lifecycleScope.launch {
                                try {
                                    val cropped = host.cropImageCenter(uri)
                                    if (cropped != null) {
                                        host.pendingImageImport = host.pendingImageImport?.copy(imageUri = cropped)
                                        host.imageEditorMessage = "Image cropped successfully"
                                    } else {
                                        host.imageEditorMessage = "Failed to crop image"
                                    }
                                } catch (t: Throwable) {
                                    Log.w(AppConstants.TAG, "Crop failed: ${t.message}")
                                    host.imageEditorMessage = "Unable to crop image"
                                }
                            }
                        },
                        onExtractText = { host.runImportedImageOcr(editorState.imageUri, showResult = true) },
                        onEnhance = { host.imageEditorMessage = "Enhance coming soon" },
                        onSign = {
                            host.signatureTargetUri = editorState.imageUri
                            host.showSignaturePad = true
                        },
                        onConfirmSave = {
                            host.confirmImportedImageSave(editorState)
                        }
                    )
                    host.imageEditorMessage?.let { message ->
                        AlertDialog(
                            onDismissRequest = { host.imageEditorMessage = null },
                            text = { Text(text = message) },
                            confirmButton = {
                                TextButton(onClick = { host.imageEditorMessage = null }) {
                                    Text(text = "OK")
                                }
                            }
                        )
                    }
                    if (host.showSignaturePad && host.signatureTargetUri != null) {
                        SignaturePadDialog(onDismiss = {
                            host.showSignaturePad = false
                            host.signatureTargetUri = null
                        }, onConfirm = { strokes ->
                            val target = host.signatureTargetUri
                            if (target != null) {
                                host.lifecycleScope.launch {
                                    try {
                                        val merged = host.applySignatureToImage(target, strokes)
                                        // update pending/imported states so UI reflects change
                                        host.pendingImageImport = host.pendingImageImport?.copy(imageUri = merged)
                                        host.importedImagePreview = host.importedImagePreview?.copy(imageUri = merged)
                                        host.imageEditorMessage = "Signature applied"
                                    } catch (t: Throwable) {
                                        Log.w(AppConstants.TAG, "Unable to apply signature: ${t.message}")
                                        host.imageEditorMessage = "Unable to apply signature"
                                    } finally {
                                        host.showSignaturePad = false
                                        host.signatureTargetUri = null
                                    }
                                }
                            }
                        })
                    }
                } else if (host.documentResultState != null) {
                    val resultState = host.documentResultState!!
                    DocumentResultScreen(
                        state = resultState,
                        onBack = host::closeDocumentResult,
                        onSaveOcrText = host::saveResultOcrText,
                        onCopyTextConfirmed = { host.viewModel.showError("Text copied.") },
                        onShareText = host::shareResultText,
                        onExportTxt = { text -> host.exportResultText(text, "txt") },
                        onExportDoc = { text -> host.exportResultText(text, "doc") },
                        onExportPdf = host::exportSearchablePdf,
                        onRetry = host::runScannerFlowValidation
                    )
                } else if (previewState != null) {
                    ImportedImageDocumentPreview(
                        imageUri = previewState.imageUri,
                        title = previewState.title,
                        rotationDegrees = previewState.rotationDegrees,
                        backendProcessingState = host.scannerBackendProcessingState,
                        validationState = host.scannerFlowValidationState,
                        onProcessWithBackend = host::processImportedPreviewWithBackend,
                        onRetryBackendProcessing = host::processImportedPreviewWithBackend,
                        onRunValidation = host::runScannerFlowValidation,
                        onRetryValidation = host::runScannerFlowValidation,
                        onOpenResult = host::openDocumentResult,
                        onBack = { host.importedImagePreview = null },
                        onAdd = { host.imageImportLauncher.launch("image/*") },
                        onEdit = {
                            host.pendingImageImport = previewState
                            host.importedImagePreview = null
                        },
                        onShare = { host.imageEditorMessage = "Share from imported image preview coming soon" },
                        onToWord = {
                            if (previewState.extractedText.isNullOrBlank()) {
                                host.imageEditorMessage = "No OCR text is available to export."
                            } else {
                                host.exportText(
                                    title = previewState.title,
                                    text = previewState.extractedText
                                )
                            }
                        },
                        onSign = {
                            // open signature pad for this preview
                            host.signatureTargetUri = previewState.imageUri
                            host.showSignaturePad = true
                        },
                        onRotate = {
                            host.importedImagePreview = previewState.copy(
                                rotationDegrees = (previewState.rotationDegrees + 90f) % 360f
                            )
                        },
                        onMenu = { host.imageEditorMessage = "More actions coming soon" },
                        onSaveToGallery = {
                            host.lifecycleScope.launch {
                                try {
                                    host.saveImageToGallery(previewState.imageUri, previewState.title)
                                    host.imageEditorMessage = "Saved to gallery"
                                } catch (t: Throwable) {
                                    Log.w(AppConstants.TAG, "Unable to save to gallery: ${t.message}")
                                    host.imageEditorMessage = "Unable to save to gallery"
                                }
                            }
                        }
                    )
                    host.imageEditorMessage?.let { message ->
                        AlertDialog(
                            onDismissRequest = { host.imageEditorMessage = null },
                            text = { Text(text = message) },
                            confirmButton = {
                                TextButton(onClick = { host.imageEditorMessage = null }) {
                                    Text(text = "OK")
                                }
                            }
                        )
                    }
                } else if (host.showIdCardFlow) {
                    IdCardFeatureScreen(
                        selectedType = host.selectedIdCardCategory,
                        onSelectType = {
                            host.selectedIdCardCategory = it
                            host.idCardValidationMessage = null
                        },
                        validationMessage = host.idCardValidationMessage,
                        onBack = { host.showIdCardFlow = false },
                        onMakeItNow = {
                            if (host.selectedIdCardCategory.isBlank()) {
                                host.idCardValidationMessage = "Please select an ID card type before scanning."
                            } else {
                                host.idCardValidationMessage = null
                                host.showIdCardFlow = false
                                host.startDocumentScanner(
                                    pageLimit = 1,
                                    titlePrefix = "$host.selectedIdCardCategory Scan",
                                    galleryImportAllowed = true,
                                    scannerMode = GmsDocumentScannerOptions.SCANNER_MODE_BASE_WITH_FILTER,
                                    isIdCardScan = true
                                )
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (host.showDocumentLibrary) {
                    val libraryState = buildDocumentLibraryState(
                        documents = uiState.documents,
                        query = host.documentLibraryQuery,
                        sort = host.documentLibrarySort
                    )
                    DocumentLibraryScreen(
                        state = libraryState,
                        onBack = host::closeDocumentLibrary,
                        onQueryChange = { host.documentLibraryQuery = it },
                        onSortChange = { host.documentLibrarySort = it },
                        onOpenDocument = { item ->
                            uiState.documents.firstOrNull { it.id == item.id }
                                ?.let(host::openLibraryDocument)
                        },
                        onToggleFavorite = { item ->
                            uiState.documents.firstOrNull { it.id == item.id }?.let { document ->
                                host.viewModel.setDocumentFavorite(document, !document.isFavorite)
                            }
                        },
                        onRenameDocument = { item ->
                            host.libraryPendingRename =
                                uiState.documents.firstOrNull { it.id == item.id }
                        },
                        onDeleteDocument = { item ->
                            host.libraryPendingDelete =
                                uiState.documents.firstOrNull { it.id == item.id }
                        }
                    )
                    host.libraryPendingRename?.let { document ->
                        RenameDocumentDialog(
                            documents = uiState.documents,
                            initialDocument = document,
                            onDismiss = { host.libraryPendingRename = null },
                            onRename = host.viewModel::renameDocument,
                            onValidationError = host.viewModel::showError
                        )
                    }
                    host.libraryPendingDelete?.let { document ->
                        AlertDialog(
                            onDismissRequest = { host.libraryPendingDelete = null },
                            title = { Text(text = "Move to Trash?") },
                            text = { Text(text = "This document moves to Trash and can be restored within 30 days.") },
                            confirmButton = {
                                TextButton(
                                    onClick = {
                                        host.libraryPendingDelete = null
                                        host.deleteDocument(document)
                                    }
                                ) {
                                    Text(text = "Move to Trash")
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { host.libraryPendingDelete = null }) {
                                    Text(text = "Cancel")
                                }
                            }
                        )
                    }
                } else {
                    ScannerDashboardScreen(
                        viewModel = host.viewModel,
                        onStartScan = { host.startDocumentScanner(pageLimit = 20) },
                        onPdfTools = { host.showPdfTools = true },
                        onImportImages = { host.imageImportLauncher.launch("image/*") },
                        onImportFiles = { host.fileImportLauncher.launch(arrayOf(AppConstants.PDF_MIME_TYPE)) },
                        onCloud = { host.showCloudSync = true },
                        onPremium = { host.openPremium() },
                        isPremium = premiumState.isPremium,
                        onIdCards = { host.showIdCardFlow = true },
                        onExtractText = { host.showPdfTools = true },
                        onAiTools = { host.showAiTools = true },
                        onAllTools = { host.showPdfTools = true },
                        onViewAll = host::openDocumentLibrary,
                        onOpenSettings = { host.showAppLockSettings = true },
                        onToWord = host::exportTextDocument,
                        onOpenDocument = { document -> host.pdfViewerDocument = document },
                        onShareDocument = host::sharePdf,
                        onShareDocuments = host::sharePdfs,
                        onEditPdfDocument = host::editPdfDocument,
                        onSendDocumentToPc = host::sendDocumentToPc,
                        onSaveDocumentExport = host::saveDocumentExport,
                        onPrintDocument = host::printDocument,
                        onConvertImageToPdf = host::convertImageDocumentToPdf,
                        onShareExtractedText = host::shareExtractedText,
                        onShareCleanedText = host::shareCleanedText,
                        onExportCleanedText = host::exportCleanedText,
                        onSaveOcrText = host.viewModel::updateDocumentOcrText,
                        onRenameDocument = host.viewModel::renameDocument,
                        onDeleteDocument = host::deleteDocument
                    )
                }
                host.appLockMessage?.let { message ->
                    AlertDialog(
                        onDismissRequest = { host.appLockMessage = null },
                        text = { Text(text = message) },
                        confirmButton = {
                            TextButton(onClick = { host.appLockMessage = null }) {
                                Text(text = "OK")
                            }
                        }
                    )
                }
            }
}
