package com.dev.docscannerpdf.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.dev.docscannerpdf.BuildConfig
import com.dev.docscannerpdf.MainActivity
import com.dev.docscannerpdf.data.local.APP_DATABASE_VERSION
import com.dev.docscannerpdf.domain.backup.BackupRepository
import com.dev.docscannerpdf.navigation.AppSurface
import com.dev.docscannerpdf.navigation.canHandleSystemBack
import com.dev.docscannerpdf.navigation.handleSystemBack
import com.dev.docscannerpdf.navigation.resolveAppSurface
import com.dev.docscannerpdf.ui.debug.ApiHealthScreen
import com.dev.docscannerpdf.ui.crop.CropEditorScreen
import com.dev.docscannerpdf.ui.detection.LiveScannerScreen
import com.dev.docscannerpdf.domain.idscan.IdentityDocumentMode
import com.dev.docscannerpdf.ui.idcard.CameraOwnershipLog
import com.dev.docscannerpdf.ui.idcard.IdCardGuidedCaptureScreen
import com.dev.docscannerpdf.ui.idcard.IdCardReviewScreen
import com.dev.docscannerpdf.domain.mainscan.MainScanCropEditor
import com.dev.docscannerpdf.domain.mainscan.MainScanPolygonSource
import com.dev.docscannerpdf.domain.mainscan.MainScanRouting
import com.dev.docscannerpdf.domain.mainscan.MainScanStage
import com.dev.docscannerpdf.domain.mainscan.MainScanWorkflow
import com.dev.docscannerpdf.domain.mainscan.PrimaryScanTarget
import com.dev.docscannerpdf.ui.mainscan.MainScanCropEditorScreen
import com.dev.docscannerpdf.ui.mainscan.MainScanCropHostScreen
import com.dev.docscannerpdf.ui.mainscan.MainScanDiscardDialog
import com.dev.docscannerpdf.ui.mainscan.MainScanEnhancementReviewScreen
import com.dev.docscannerpdf.ui.mainscan.MainScanFailureScreen
import com.dev.docscannerpdf.ui.mainscan.MainScanProcessingScreen
import com.dev.docscannerpdf.ui.mainscan.MainScannerCaptureScreen
import com.dev.docscannerpdf.ui.idcard.PassportGuidedCaptureScreen
import com.dev.docscannerpdf.ui.idcard.PassportCropEditorScreen
import com.dev.docscannerpdf.ui.idcard.PassportReviewScreen
import com.dev.docscannerpdf.ui.library.DocumentLibraryScreen
import com.dev.docscannerpdf.ui.library.buildDocumentLibraryState
import com.dev.docscannerpdf.ui.pages.MultiPageDocumentEditorScreen
import com.dev.docscannerpdf.ui.result.DocumentResultScreen
import com.dev.docscannerpdf.ui.theme.DocScannerPDFTheme
import com.dev.docscannerpdf.util.AppConstants
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
                val retainedMainScanCompletion = host.mainScanCompletedDocument
                LaunchedEffect(retainedMainScanCompletion?.id) {
                    retainedMainScanCompletion?.let(host::presentRetainedMainScanCompletion)
                }
                val viewerDocument = host.pdfViewerDocument?.let { selectedDocument ->
                    uiState.documents.firstOrNull { it.id == selectedDocument.id } ?: selectedDocument
                }
                BackHandler(enabled = host.canHandleSystemBack()) {
                    host.handleSystemBack()
                }
                // ONE authoritative decision for the top-priority surfaces (pure, unit-tested):
                // no state outside its four inputs can deselect an active capture session.
                val topSurface = resolveAppSurface(
                    appLockActive = host.appLockSettings.lockEnabled && !host.appUnlocked,
                    showOnboarding = host.showOnboarding,
                    showIdCardGuidedCapture = host.showIdCardGuidedCapture,
                    showPassportCapture = host.showPassportCapture,
                    passportReviewOpen = host.passportReview != null,
                    mainScanCaptureOpen = host.showMainScanCapture,
                    mainScanPageUri = host.mainScanState.pendingPage?.uri,
                    idCardReviewOpen = host.idCardReview != null
                )
                if (BuildConfig.DEBUG) {
                    // Diagnostic: every top-surface transition is logged, so a replaced capture
                    // screen can be traced to the exact route change that caused it.
                    LaunchedEffect(topSurface) {
                        Log.d(
                            "IdCardCapture",
                            CameraOwnershipLog.host(
                                surface = topSurface.toString(),
                                idCardCaptureVisible = host.showIdCardGuidedCapture,
                                passportCaptureVisible = host.showPassportCapture,
                                mainScanCaptureVisible = host.showMainScanCapture
                            )
                        )
                    }
                }
                if (topSurface == AppSurface.APP_LOCK) {
                    AppLockScreen(
                        pinLength = APP_PIN_LENGTH,
                        biometricsAvailable = host.canUseBiometrics(),
                        biometricsEnabled = host.appLockSettings.biometricsEnabled,
                        errorMessage = host.appLockError,
                        onPinComplete = host::unlockWithPin,
                        onBiometricClick = host::showBiometricPrompt
                    )
                } else if (topSurface == AppSurface.ONBOARDING) {
                    OnboardingScreen(
                        onComplete = host::completeOnboarding
                    )
                } else if (topSurface == AppSurface.ID_CARD_CAPTURE) {
                    // Near-modal priority (below only app-lock/onboarding): an active CameraX
                    // capture session must never be torn down by a lower branch briefly
                    // flickering non-null — that replaced this screen's composition node,
                    // reconstructing the camera controller mid-visit (two controllers, double
                    // bind, detach churn). Only showIdCardGuidedCapture itself mounts/unmounts
                    // this screen now.
                    IdCardGuidedCaptureScreen(
                        outputDirectory = host.idCardCaptureDirectory,
                        onBack = { host.showIdCardGuidedCapture = false },
                        onCaptureComplete = { front, back ->
                            host.beginIdCardReview(front, back)
                        }
                    )
                } else if (topSurface == AppSurface.MAIN_SCAN_CROP) {
                    // The DEDICATED Main Scanner crop surface — never the generic Document Ready
                    // screen. It outranks its own camera so the captured pixels it is already
                    // showing can never be replaced by a re-mounted preview. A page URI is
                    // guaranteed non-null here by resolveAppSurface, so this surface can never be
                    // reached without pixels to display.
                    // Which surface composes is decided ONLY by the workflow stage, so a progress
                    // overlay can never appear without its image and the editor can never appear
                    // before the polygon is resolved.
                    val pendingUri = host.mainScanState.pendingPage!!.uri
                    val seed = host.mainScanState.frozenCropSeed
                    LaunchedEffect(pendingUri) {
                        host.prepareMainScanCrop(pendingUri.toUri(), seed)
                    }
                    Box {
                        when (host.mainScanStage) {
                            // Decode and polygon resolution: the captured JPEG is shown straight
                            // from disk with a small centred indicator over it, so the surface has
                            // real pixels from the first frame rather than a spinner on black.
                            // Every Back affordance on this surface — and system/predictive Back —
                            // is the SAME call. onMainScanPageBack asks MainScanWorkflow.backTarget
                            // what Back means at the current stage, so the visible arrow can never
                            // resolve to a different transition than the gesture does.
                            MainScanStage.CropPreparing, MainScanStage.CaptureAccepted ->
                                MainScanCropHostScreen(
                                    pageUri = pendingUri,
                                    onBack = host::onMainScanPageBack
                                )
                            // The processing stages get a REAL Back, not a decoration: the governed
                            // rule has no in-workflow predecessor for them, so it resolves to the
                            // ordinary discard decision — the same one the crop editor's arrow
                            // raises. Leaving the arrow off was the divergence: system Back was
                            // answerable here while the surface showed no way to do it.
                            MainScanStage.Cropping -> MainScanProcessingScreen(
                                image = host.mainScanWorkingImage?.bitmap,
                                label = "Cropping image…",
                                onBack = host::onMainScanPageBack
                            )
                            MainScanStage.EnhancementPreparing -> MainScanProcessingScreen(
                                image = host.mainScanCroppedImage
                                    ?: host.mainScanWorkingImage?.bitmap,
                                label = "Enhancing image…",
                                onBack = host::onMainScanPageBack
                            )
                            // The two bitmaps are previews; the third argument is the only thing
                            // that says whether a saveable, source-resolution page exists behind
                            // them. Passing the artifact's presence rather than a bitmap is what
                            // keeps a preview from ever standing in for one.
                            // The review surface stays mounted for the WHOLE of Confirm.
                            //
                            // The reference has no "Confirming", no "Saving document" and no
                            // "Opening document" screen: the check is tapped and the next thing on
                            // screen is the saved document's viewer. Swapping in a labelled
                            // progress surface for each of those stages is a step the reference
                            // does not have, and it was long enough to read. Confirm is instead
                            // gated by `confirmEnabled` below, which is false for every stage past
                            // EnhancementReview — so the page and its chrome simply stay put,
                            // inert, until the viewer replaces them.
                            MainScanStage.EnhancementReview,
                            MainScanStage.Confirming,
                            MainScanStage.Persisting,
                            MainScanStage.Completed,
                            MainScanStage.Discarded -> MainScanEnhancementReviewScreen(
                                enhanced = host.mainScanEnhancedImage,
                                cropped = host.mainScanCroppedImage,
                                highQualityResultAvailable = host.mainScanAuthoritative != null,
                                highQualityFailure = host.mainScanAuthoritativeFailure,
                                title = host.mainScanTitle.orEmpty(),
                                onTitleChange = host::onMainScanTitleChange,
                                selectedFilter = host.mainScanFilter,
                                onFilterSelected = host::selectMainScanFilter,
                                filterRendering = host.mainScanFilterRendering,
                                onBack = host::onMainScanPageBack,
                                // A saveable artifact must exist AND not be mid-re-render: while a
                                // filter change is running the published artifact has deliberately
                                // been dropped, so there is nothing Confirm could correctly write.
                                confirmEnabled =
                                    MainScanWorkflow.allowsConfirm(host.mainScanStage) &&
                                        !host.mainScanFilterRendering &&
                                        host.mainScanAuthoritative != null,
                                onConfirm = host::confirmMainScan
                            )
                            // The capture could not be decoded, so there is no image and no polygon.
                            // This must NOT fall through to the crop editor: its empty-image branch
                            // is an indeterminate spinner with no work behind it, which never
                            // resolves. State the failure and offer the one recovery that exists.
                            MainScanStage.Failed -> MainScanFailureScreen(
                                message = "Couldn't open that capture. Please take the shot again.",
                                onBackToCamera = host::confirmMainScanDiscard
                            )
                            else -> MainScanCropEditorScreen(
                                image = host.mainScanWorkingImage,
                                cropState = host.mainScanCropState
                                    ?: MainScanCropEditor.initial(
                                        MainScanCropEditor.fullFrame(),
                                        MainScanPolygonSource.FULL_FRAME
                                    ),
                                onCropStateChange = host::onMainScanCropStateChange,
                                onRotate = host::rotateMainScanCrop,
                                onResetAll = host::resetMainScanCropToFullFrame,
                                onNext = host::advanceMainScanCrop,
                                onBack = host::onMainScanPageBack
                            )
                        }
                        // Back on THIS surface raises the same discard decision the capture surface
                        // raises, so it must be answered here too. Without this the flag was set and
                        // nothing appeared, leaving Back inert and the crop screen impossible to
                        // exit: the following press cancelled the invisible dialog, so presses
                        // alternated between two unseen states forever.
                        if (host.mainScanState.discardConfirmVisible) {
                            MainScanDiscardDialog(
                                onCancel = host::cancelMainScanDiscard,
                                onDiscard = host::confirmMainScanDiscard
                            )
                        }
                    }
                } else if (topSurface == AppSurface.MAIN_SCAN_CAPTURE) {
                    // Near-modal priority, same rationale as ID-card/passport capture: the
                    // app-owned Main Scanner CameraX session must never be replaced by a lower
                    // branch flickering non-null. Clean preview, always-armed manual shutter, and
                    // no Room write anywhere in this surface.
                    MainScannerCaptureScreen(
                        outputDirectory = host.mainScanCaptureDirectory,
                        state = host.mainScanState,
                        onCaptureStarted = host::onMainScanCaptureStarted,
                        onCaptureSucceeded = host::onMainScanCaptureSucceeded,
                        onCaptureFailed = host::onMainScanCaptureFailed,
                        onCaptureTimedOut = host::onMainScanCaptureTimedOut,
                        onCameraUnavailable = host::onMainScanCameraUnavailable,
                        // Same single decision system/predictive Back uses — see onMainScanBack.
                        onBack = host::onMainScanBack,
                        onCancelDiscard = host::cancelMainScanDiscard,
                        onConfirmDiscard = host::confirmMainScanDiscard
                    )
                } else if (topSurface == AppSurface.PASSPORT_CAPTURE) {
                    // Near-modal priority, same rationale as ID-card capture: the passport
                    // CameraX session must never be replaced by a lower branch flickering
                    // non-null. Single-page portrait flow; on completion it routes into the
                    // DEDICATED passport review below — never the generic Document Ready preview.
                    PassportGuidedCaptureScreen(
                        outputDirectory = host.passportCaptureDirectory,
                        onBack = { host.showPassportCapture = false },
                        onCaptureComplete = { page -> host.beginPassportReview(page) }
                    )
                } else if (topSurface == AppSurface.PASSPORT_REVIEW) {
                    // The DEDICATED passport review — never the generic Document Ready screen,
                    // so no backend-processing / E2E-validation / To Word surface can appear in
                    // the passport path.
                    val passportState = host.passportReview!!
                    val passportCrop = host.passportCropRect
                    if (passportCrop != null) {
                        // The interactive rectangular crop editor sits on top of the passport
                        // review; cancel returns without changing the review state.
                        PassportCropEditorScreen(
                            sourceBitmap = host.passportCropSourceBitmap,
                            crop = passportCrop,
                            applying = host.passportCropApplying,
                            onMoveHandle = host::passportCropMoveHandle,
                            onMoveBy = host::passportCropMoveBy,
                            onReset = host::passportCropReset,
                            onApply = host::passportCropApply,
                            onCancel = host::cancelPassportCropEditor
                        )
                    } else {
                        // The instant-preview frame stream: in-memory previews the moment the
                        // user taps, atomically replaced by settled authoritative pixels.
                        val passportPreviewFrame by host.passportPreviewFrames.collectAsState()
                        PassportReviewScreen(
                            state = passportState,
                            previewFrame = passportPreviewFrame,
                            filterThumbnails = host.passportFilterThumbnails,
                            onBack = host::cancelPassportReview,
                            onCrop = host::openPassportCropEditor,
                            onRotate = host::rotatePassportReview,
                            onSelectFilter = host::applyPassportFilter,
                            onSetWatermark = host::setPassportWatermark,
                            onConfirm = host::confirmPassportReview
                        )
                    }
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
                } else if (host.showLiveScanner) {
                    LiveScannerScreen(
                        onBack = { host.showLiveScanner = false },
                        onReadyToCapture = host::onLiveCaptureReady
                    )
                } else if (host.showAiTools) {
                    AiToolsScreen(
                        onBack = { host.showAiTools = false },
                        onSmartScan = {
                            host.startDocumentScanner(pageLimit = 20)
                            host.showAiTools = false
                        },
                        onLiveScan = host::openLiveScanner,
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
                                        // A signed front invalidates an ID-card scan's combined
                                        // result page; re-render it. No-op for normal documents.
                                        host.refreshIdCardCombinedPreviewImage()
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
                } else if (host.cropState != null) {
                    CropEditorScreen(
                        state = host.cropState!!,
                        sourceBitmap = host.cropSourceBitmap,
                        onMoveCorner = host::cropMoveCorner,
                        onReset = host::cropResetQuad,
                        onApply = host::cropApply,
                        onCancel = host::cancelCropEditor
                    )
                } else if (host.documentResultState != null) {
                    val resultState = host.documentResultState!!
                    LaunchedEffect(resultState.documentId, resultState.pageId) {
                        host.beginAnnotationSession(resultState)
                    }
                    DocumentResultScreen(
                        state = resultState,
                        onBack = host::closeDocumentResult,
                        onSaveOcrText = host::saveResultOcrText,
                        onCopyTextConfirmed = { host.viewModel.showError("Text copied.") },
                        onShareText = host::shareResultText,
                        onExportTxt = { text -> host.exportResultText(text, "txt") },
                        onExportDoc = { text -> host.exportResultText(text, "doc") },
                        onExportPdf = host::exportSearchablePdf,
                        onRetry = host::runScannerFlowValidation,
                        annotationState = host.annotationEditor,
                        onToggleAnnotateMode = host::toggleAnnotationMode,
                        onSelectAnnotationTool = host::selectAnnotationTool,
                        onAddAnnotationStroke = host::addAnnotationStroke,
                        onUndoAnnotation = host::undoAnnotation,
                        onRedoAnnotation = host::redoAnnotation,
                        onEditCrop = host::openCropEditor,
                        overlayAnnotations = host.displayAnnotations()
                    )
                } else if (previewState != null) {
                    ImportedImageDocumentPreview(
                        imageUri = previewState.imageUri,
                        title = previewState.title,
                        rotationDegrees = previewState.rotationDegrees,
                        backImageUri = previewState.backImageUri,
                        isIdCardScan = previewState.isIdCardScan,
                        combinedImageUri = previewState.combinedImageUri,
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
                                    // ID-card scans save the combined front+back result page —
                                    // never just the front side. Normal documents have no
                                    // combined image and keep saving their single page.
                                    val galleryUri = previewState.combinedImageUri ?: previewState.imageUri
                                    host.saveImageToGallery(galleryUri, previewState.title)
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
                                // Passport is a single portrait page; every other type keeps the
                                // existing Front/Back card guided capture.
                                if (IdentityDocumentMode.fromEntryLabel(host.selectedIdCardCategory) ==
                                    IdentityDocumentMode.PASSPORT
                                ) {
                                    host.startPassportCapture()
                                } else {
                                    host.showIdCardFlow = false
                                    host.startIdCardGuidedCapture(
                                        titlePrefix = host.selectedIdCardCategory
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (host.idCardCropState != null) {
                    CropEditorScreen(
                        state = host.idCardCropState!!,
                        sourceBitmap = host.idCardCropSourceBitmap,
                        onMoveCorner = host::idCardCropMoveCorner,
                        onReset = host::idCardCropResetQuad,
                        onApply = host::idCardCropApply,
                        onCancel = host::cancelIdCardCropEditor
                    )
                } else if (host.idCardReview != null) {
                    val reviewState = host.idCardReview!!
                    IdCardReviewScreen(
                        state = reviewState,
                        onBack = host::cancelIdCardReview,
                        onSelectSide = host::selectIdCardReviewSide,
                        onRenameTitle = host::renameIdCardReviewTitle,
                        onHelp = host::idCardReviewHelpTapped,
                        onCompare = host::idCardReviewCompareTapped,
                        onCrop = host::openIdCardCropEditor,
                        onRotate = host::rotateSelectedIdCardReviewSide,
                        onSelectFilter = host::applyIdCardReviewFilter,
                        onAddWatermark = host::idCardReviewAddWatermarkTapped,
                        onSave = host::confirmIdCardReview
                    )
                } else if (host.multiPageEditorState != null) {
                    val editorState = host.multiPageEditorState!!
                    MultiPageDocumentEditorScreen(
                        state = editorState,
                        onBack = host::closeMultiPageEditor,
                        onSelectPage = host::editorSelectPage,
                        onMovePageUp = host::editorMovePageUp,
                        onMovePageDown = host::editorMovePageDown,
                        onAddPage = host::editorAddPagePlaceholder,
                        onDuplicatePage = host::editorDuplicatePage,
                        onRotatePage = host::editorRotatePage,
                        onRequestDeletePage = host::editorRequestDeletePage,
                        onConfirmDeletePage = host::editorConfirmDeletePage,
                        onCancelDeletePage = host::editorCancelDeletePage
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
                        // Build-type routing: debug opens the app-owned Main Scanner, release opens
                        // ML Kit. BuildConfig.DEBUG is the only input, so a release binary can
                        // never reach the incomplete flow. See MainScanRouting.
                        onStartScan = {
                            when (MainScanRouting.primaryScanTarget(BuildConfig.DEBUG)) {
                                PrimaryScanTarget.MAIN_SCANNER -> host.startMainScanCapture()
                                PrimaryScanTarget.ML_KIT -> host.startDocumentScanner(pageLimit = 20)
                            }
                        },
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
