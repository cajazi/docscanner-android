package com.dev.docscannerpdf

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Bitmap.CompressFormat
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.fragment.app.FragmentActivity
import com.dev.docscannerpdf.data.local.DocumentEntity
import com.dev.docscannerpdf.data.local.APP_DATABASE_VERSION
import com.dev.docscannerpdf.domain.ads.AdManager
import com.dev.docscannerpdf.domain.analytics.AnalyticsRepository
import com.dev.docscannerpdf.domain.backup.BackupArchive
import com.dev.docscannerpdf.domain.backup.BackupRepository
import com.dev.docscannerpdf.domain.backup.LastBackupInfo
import com.dev.docscannerpdf.domain.billing.BillingRepository
import com.dev.docscannerpdf.domain.cloud.CloudSyncRepository
import com.dev.docscannerpdf.domain.onboarding.OnboardingRepository
import com.dev.docscannerpdf.domain.security.AppLockRepository
import com.dev.docscannerpdf.domain.security.AppLockSettings
import com.dev.docscannerpdf.domain.annotation.AnnotationEditorReducer
import com.dev.docscannerpdf.domain.annotation.AnnotationEditorState
import com.dev.docscannerpdf.domain.annotation.AnnotationRepository
import com.dev.docscannerpdf.domain.annotation.AnnotationStroke
import com.dev.docscannerpdf.domain.annotation.AnnotationTool
import com.dev.docscannerpdf.domain.annotation.PageAnnotationState
import com.dev.docscannerpdf.domain.pdf.IdCardPdfInput
import com.dev.docscannerpdf.domain.pdf.PdfCoordinateMapper
import com.dev.docscannerpdf.domain.pdf.PdfExportPageInput
import com.dev.docscannerpdf.domain.pdf.PdfExportService
import com.dev.docscannerpdf.domain.pdf.PdfRenderHelper
import com.dev.docscannerpdf.domain.pdf.PdfTextSpan
import com.dev.docscannerpdf.domain.pdf.SearchablePdfTextLayer
import com.dev.docscannerpdf.navigation.canHandleSystemBack
import com.dev.docscannerpdf.navigation.handleSystemBack
import com.dev.docscannerpdf.network.NetworkResult
import com.dev.docscannerpdf.presentation.EditedPdfOutput
import com.dev.docscannerpdf.presentation.MergeOutput
import com.dev.docscannerpdf.presentation.PdfImageOutput
import com.dev.docscannerpdf.presentation.PdfTextExportOutput
import com.dev.docscannerpdf.presentation.PendingImageImport
import com.dev.docscannerpdf.presentation.PendingImageReview
import com.dev.docscannerpdf.presentation.ScannerViewModel
import com.dev.docscannerpdf.presentation.ScannerViewModelFactory
import com.dev.docscannerpdf.presentation.SignedPdfOutput
import com.dev.docscannerpdf.presentation.SplitOutput
import com.dev.docscannerpdf.presentation.WatermarkOutput
import com.dev.docscannerpdf.presentation.WatermarkPreview
import com.dev.docscannerpdf.process.ProcessDocumentUseCase
import com.dev.docscannerpdf.process.ScannerBackendProcessingState
import com.dev.docscannerpdf.process.ScannerFlowStage
import com.dev.docscannerpdf.process.ScannerFlowValidationState
import com.dev.docscannerpdf.process.ScannerFlowValidationUseCase
import com.dev.docscannerpdf.process.toScannerBackendProcessingState
import com.dev.docscannerpdf.ui.result.DocumentResultState
import com.dev.docscannerpdf.ui.result.toDocumentResultState
import com.dev.docscannerpdf.ui.library.DocumentLibrarySort
import com.dev.docscannerpdf.ui.library.isResultScreenEligible
import com.dev.docscannerpdf.ui.library.toLibraryResultState
import com.dev.docscannerpdf.ui.pages.MultiPageEditorReducer
import com.dev.docscannerpdf.ui.pages.MultiPageEditorState
import com.dev.docscannerpdf.ui.pages.shouldOpenMultiPageEditor
import com.dev.docscannerpdf.ui.pages.toMultiPageEditorState
import com.dev.docscannerpdf.domain.annotation.Annotation
import com.dev.docscannerpdf.domain.annotation.AnnotationHomographyMapper
import com.dev.docscannerpdf.domain.crop.CropCorner
import com.dev.docscannerpdf.domain.crop.CropReducer
import com.dev.docscannerpdf.domain.crop.CropState
import com.dev.docscannerpdf.domain.crop.PerspectiveQuad
import com.dev.docscannerpdf.domain.detection.LiveFrameAnalyzer
import com.dev.docscannerpdf.domain.filter.DocumentFilter
import com.dev.docscannerpdf.domain.filter.DocumentFilterRenderer
import com.dev.docscannerpdf.domain.idscan.IdCardCombinedPageRenderer
import com.dev.docscannerpdf.domain.idscan.IdCardReviewFlow
import com.dev.docscannerpdf.domain.idscan.IdCardReviewSide
import com.dev.docscannerpdf.domain.idscan.IdCardReviewState
import com.dev.docscannerpdf.domain.idscan.IdCardSaveCoordinator
import com.dev.docscannerpdf.domain.idscan.IdCardSideSavePlan
import com.dev.docscannerpdf.domain.idscan.IdScanPostProcessor
import com.dev.docscannerpdf.domain.idscan.PassportCompletion
import com.dev.docscannerpdf.domain.idscan.PassportCropHandle
import com.dev.docscannerpdf.domain.idscan.PassportCropRect
import com.dev.docscannerpdf.domain.idscan.PassportCropReducer
import com.dev.docscannerpdf.domain.idscan.PassportCropRenderer
import com.dev.docscannerpdf.domain.idscan.PassportCropRotationMapping
import com.dev.docscannerpdf.domain.idscan.PassportFileOwnership
import com.dev.docscannerpdf.domain.idscan.PassportCompletionDestination
import com.dev.docscannerpdf.domain.idscan.PassportEffectChain
import com.dev.docscannerpdf.domain.idscan.PassportFilterGrid
import com.dev.docscannerpdf.domain.idscan.PassportPreviewBus
import com.dev.docscannerpdf.domain.idscan.PassportPreviewDecoder
import com.dev.docscannerpdf.domain.idscan.PassportPreviewFrame
import com.dev.docscannerpdf.domain.idscan.PassportPreviewOperation
import com.dev.docscannerpdf.domain.idscan.PassportPreviewRenderer
import com.dev.docscannerpdf.domain.idscan.PassportReviewFlow
import com.dev.docscannerpdf.domain.idscan.PassportReviewSession
import com.dev.docscannerpdf.domain.idscan.PassportReviewState
import com.dev.docscannerpdf.domain.idscan.PassportTimingLog
import com.dev.docscannerpdf.domain.idscan.PassportFailureStage
import com.dev.docscannerpdf.domain.idscan.PassportWatermarkRenderer
import androidx.core.net.toUri
import com.dev.docscannerpdf.domain.mainscan.MainScanAnalysisResult
import com.dev.docscannerpdf.domain.mainscan.MainScanAuthoritativeArtifact
import com.dev.docscannerpdf.domain.mainscan.MainScanAuthoritativeRender
import com.dev.docscannerpdf.domain.mainscan.MainScanRenderFailure
import com.dev.docscannerpdf.domain.mainscan.MainScanRenderOutcome
import com.dev.docscannerpdf.domain.mainscan.MainScanCaptureFlow
import com.dev.docscannerpdf.domain.mainscan.MainScanCropEditor
import com.dev.docscannerpdf.domain.mainscan.MainScanCropSeed
import com.dev.docscannerpdf.domain.mainscan.MainScanCropSeeding
import com.dev.docscannerpdf.domain.mainscan.MainScanCropState
import com.dev.docscannerpdf.domain.mainscan.MainScanPolygonResolver
import com.dev.docscannerpdf.domain.mainscan.MainScanRotation
import com.dev.docscannerpdf.domain.mainscan.MainScanSaveCoordinator
import com.dev.docscannerpdf.domain.mainscan.MainScanSavedArtifactStore
import com.dev.docscannerpdf.domain.mainscan.MainScanStage
import com.dev.docscannerpdf.domain.mainscan.MainScanWorkflow
import com.dev.docscannerpdf.domain.mainscan.blocksMainScanExit
import com.dev.docscannerpdf.domain.mainscan.MainScanCaptureState
import com.dev.docscannerpdf.domain.mainscan.MainScanCaptureTicket
import com.dev.docscannerpdf.domain.mainscan.MainScanFileOwnership
import com.dev.docscannerpdf.domain.mainscan.MainScanPageSource
import com.dev.docscannerpdf.domain.mainscan.MainScanTrace
import com.dev.docscannerpdf.domain.mainscan.MainScanVisitStore
import com.dev.docscannerpdf.ui.crop.CropImageProcessor
import com.dev.docscannerpdf.ui.mainscan.MainScanCaptureImageLoader
import com.dev.docscannerpdf.ui.mainscan.MainScanCaptureProcessor
import com.dev.docscannerpdf.ui.mainscan.MainScanWorkingImage
import com.dev.docscannerpdf.ui.detection.LumaFrameFactory
import com.dev.docscannerpdf.ui.DocScannerApp
import com.dev.docscannerpdf.util.AppConstants
import com.dev.docscannerpdf.ui.APP_PIN_LENGTH
import com.dev.docscannerpdf.ui.AppLockScreen
import com.dev.docscannerpdf.ui.AppLockSettingsScreen
import com.dev.docscannerpdf.ui.BackupRestoreScreen
import com.dev.docscannerpdf.ui.CloudSyncScreen
import com.dev.docscannerpdf.ui.CompressPdfScreen
import com.dev.docscannerpdf.ui.CompressPdfUiState
import com.dev.docscannerpdf.ui.EditPdfPage
import com.dev.docscannerpdf.ui.EditPdfScreen
import com.dev.docscannerpdf.ui.EditPdfUiState
import com.dev.docscannerpdf.ui.FeatureValidationScreen
import com.dev.docscannerpdf.ui.ImageImportEditor
import com.dev.docscannerpdf.ui.ImageImportReviewScreen
import com.dev.docscannerpdf.ui.ImagesToPdfScreen
import com.dev.docscannerpdf.ui.ImagesToPdfUiState
import com.dev.docscannerpdf.ui.ImportedImageDocumentPreview
import com.dev.docscannerpdf.ui.SignaturePadDialog
import com.dev.docscannerpdf.ui.MergePdfItem
import com.dev.docscannerpdf.ui.MergePdfScreen
import com.dev.docscannerpdf.ui.MergePdfUiState
import com.dev.docscannerpdf.ui.AiToolsScreen
import com.dev.docscannerpdf.ui.PDFToolsScreen
import com.dev.docscannerpdf.ui.PdfToWordScreen
import com.dev.docscannerpdf.ui.PdfToWordUiState
import com.dev.docscannerpdf.ui.PdfToImagesScreen
import com.dev.docscannerpdf.ui.PdfToImagesUiState
import com.dev.docscannerpdf.ui.PdfViewerScreen
import com.dev.docscannerpdf.ui.PremiumScreen
import com.dev.docscannerpdf.ui.LockPdfScreen
import com.dev.docscannerpdf.ui.OnboardingScreen
import com.dev.docscannerpdf.ui.PdfPasswordToolState
import com.dev.docscannerpdf.ui.RenameDocumentDialog
import com.dev.docscannerpdf.ui.ScannerDashboardScreen
import com.dev.docscannerpdf.ui.IdCardFeatureScreen
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import com.dev.docscannerpdf.ui.SignPdfScreen
import com.dev.docscannerpdf.ui.SignPdfUiState
import com.dev.docscannerpdf.ui.SplitPdfMode
import com.dev.docscannerpdf.ui.SplitPdfScreen
import com.dev.docscannerpdf.ui.SplitPdfUiState
import com.dev.docscannerpdf.ui.UnlockPdfScreen
import com.dev.docscannerpdf.ui.WatermarkPdfScreen
import com.dev.docscannerpdf.ui.WatermarkPdfUiState
import com.dev.docscannerpdf.ui.WatermarkPosition
import com.dev.docscannerpdf.ui.theme.DocScannerPDFTheme
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MainActivity : FragmentActivity() {

    internal val viewModel: ScannerViewModel by viewModels {
        val app = application as DocScannerPdfApplication
        ScannerViewModelFactory(app.repository, app.folderRepository, app.tagRepository, app.analyticsRepository)
    }
    private var pendingScanTitlePrefix = DEFAULT_SCAN_TITLE_PREFIX
    private var pendingScanIsIdCardScan = false
    private var pendingIdCardCaptureTitlePrefix = DEFAULT_SCAN_TITLE_PREFIX
    internal var showIdCardGuidedCapture by mutableStateOf(false)
    // CamScanner-style post-capture review step (crop/rotate/enhance/save) shown after guided
    // capture completes and before the final Document Ready preview. Kept entirely separate from
    // the normal document crop/preview state below so the ID-card flow can never affect it.
    internal var idCardReview by mutableStateOf<IdCardReviewState?>(null)
    internal var idCardCropState by mutableStateOf<CropState?>(null)
    internal var idCardCropSourceBitmap by mutableStateOf<android.graphics.Bitmap?>(null)
    private var idCardCropTargetSide = IdCardReviewSide.FRONT
    private val processDocumentUseCase = ProcessDocumentUseCase()
    private val scannerFlowValidationUseCase = ScannerFlowValidationUseCase()
    private val pdfExportService by lazy { PdfExportService(applicationContext) }
    private val annotationRepository by lazy {
        AnnotationRepository(File(filesDir, "annotations"))
    }
    private val cropImageProcessor by lazy { CropImageProcessor(applicationContext) }
    private val liveFrameAnalyzer by lazy { LiveFrameAnalyzer() }
    internal var imageImportReview by mutableStateOf<PendingImageReview?>(null)
    internal var pendingImageImport by mutableStateOf<PendingImageImport?>(null)
    internal var importedImagePreview by mutableStateOf<PendingImageImport?>(null)
    internal var scannerBackendProcessingState by mutableStateOf<ScannerBackendProcessingState>(
        ScannerBackendProcessingState.Idle
    )
    internal var scannerFlowValidationState by mutableStateOf(ScannerFlowValidationState())
    internal var documentResultState by mutableStateOf<DocumentResultState?>(null)
    internal var imageEditorMessage by mutableStateOf<String?>(null)
    internal var showSignaturePad by mutableStateOf(false)
    internal var signatureTargetUri by mutableStateOf<Uri?>(null)
    internal var showPdfTools by mutableStateOf(false)
    internal var showAiTools by mutableStateOf(false)
    internal var showLiveScanner by mutableStateOf(false)
    internal var showDocumentLibrary by mutableStateOf(false)
    internal var documentLibraryQuery by mutableStateOf("")
    internal var documentLibrarySort by mutableStateOf(DocumentLibrarySort.NEWEST)
    internal var libraryPendingRename by mutableStateOf<DocumentEntity?>(null)
    internal var libraryPendingDelete by mutableStateOf<DocumentEntity?>(null)
    internal var multiPageEditorState by mutableStateOf<MultiPageEditorState?>(null)
    internal var annotationEditor by mutableStateOf<AnnotationEditorState?>(null)
    internal var cropState by mutableStateOf<CropState?>(null)
    internal var cropSourceBitmap by mutableStateOf<android.graphics.Bitmap?>(null)
    // The crop quad (base-image normalized space) currently applied to the open result, used to
    // keep annotations aligned with the cropped image. Null when no crop is applied.
    internal var appliedCropQuad by mutableStateOf<PerspectiveQuad?>(null)
    internal var pdfToolsMessage by mutableStateOf<String?>(null)
    private var pdfViewerDocumentState by mutableStateOf<DocumentEntity?>(null)
    internal var pdfViewerDocument: DocumentEntity?
        get() = pdfViewerDocumentState
        set(value) {
            val previous = pdfViewerDocumentState
            pdfViewerDocumentState = value
            // Retain Main Scanner completion for the viewer lifetime so recreation restores the
            // exact inserted id. Any real departure, including system Back or delete, consumes it.
            if (previous?.id == mainScanCompletedDocument?.id && value?.id != previous?.id) {
                mainScanCompletedDocument = null
            }
        }
    internal var viewerDocumentPendingDelete by mutableStateOf<DocumentEntity?>(null)
    internal var viewerDocumentPendingRename by mutableStateOf<DocumentEntity?>(null)
    internal var showCompressPdf by mutableStateOf(false)
    internal var compressPdfState by mutableStateOf(CompressPdfUiState())
    internal var showImagesToPdf by mutableStateOf(false)
    internal var imagesToPdfState by mutableStateOf(ImagesToPdfUiState())
    internal var showSplitPdf by mutableStateOf(false)
    internal var splitPdfState by mutableStateOf(SplitPdfUiState())
    internal var showMergePdf by mutableStateOf(false)
    internal var mergePdfState by mutableStateOf(MergePdfUiState())
    internal var showLockPdf by mutableStateOf(false)
    internal var lockPdfState by mutableStateOf(PdfPasswordToolState())
    internal var showUnlockPdf by mutableStateOf(false)
    internal var unlockPdfState by mutableStateOf(PdfPasswordToolState())
    internal var showSignPdf by mutableStateOf(false)
    internal var signPdfState by mutableStateOf(SignPdfUiState())
    internal var showWatermarkPdf by mutableStateOf(false)
    internal var watermarkPdfState by mutableStateOf(WatermarkPdfUiState())
    internal var showPdfToWord by mutableStateOf(false)
    internal var pdfToWordState by mutableStateOf(PdfToWordUiState())
    internal var showPdfToImages by mutableStateOf(false)
    internal var pdfToImagesState by mutableStateOf(PdfToImagesUiState())
    internal var showEditPdf by mutableStateOf(false)
    internal var editPdfState by mutableStateOf(EditPdfUiState())
    internal var returnToPdfToolsAfterEdit by mutableStateOf(true)
    internal lateinit var appLockRepository: AppLockRepository
    internal lateinit var backupRepository: BackupRepository
    internal lateinit var analyticsRepository: AnalyticsRepository
    internal lateinit var billingRepository: BillingRepository
    internal lateinit var cloudSyncRepository: CloudSyncRepository
    internal lateinit var onboardingRepository: OnboardingRepository
    internal var appLockSettings by mutableStateOf(AppLockSettings())
    internal var appUnlocked by mutableStateOf(true)
    internal var appLockError by mutableStateOf<String?>(null)
    internal var appLockMessage by mutableStateOf<String?>(null)
    internal var showAppLockSettings by mutableStateOf(false)
    internal var showBackupRestore by mutableStateOf(false)
    internal var showCloudSync by mutableStateOf(false)
    internal var showIdCardFlow by mutableStateOf(false)
    internal var selectedIdCardCategory by mutableStateOf("ID Card")
    internal var idCardValidationMessage by mutableStateOf<String?>(null)
    internal var showFeatureValidation by mutableStateOf(false)
    internal var showApiHealth by mutableStateOf(false)
    internal var showPremium by mutableStateOf(false)
    internal var showOnboarding by mutableStateOf(false)
    internal var backupProcessing by mutableStateOf(false)
    internal var backupStatusMessage by mutableStateOf<String?>(null)
    internal var lastBackupInfo by mutableStateOf<LastBackupInfo?>(null)
    internal var pendingRestoreArchive by mutableStateOf<BackupArchive?>(null)
    private var backgroundedAt: Long? = null

    internal val documentScannerLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val scanTitlePrefix = pendingScanTitlePrefix
        val isIdCardScan = pendingScanIsIdCardScan
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val scannedPages = scanResult?.pages
            val previewPageUri = scannedPages?.firstOrNull()?.imageUri
            // ID cards may capture a second page for the back; every other scan stays single-page.
            val backPageUri = if (isIdCardScan) scannedPages?.getOrNull(1)?.imageUri else null
            val previewTitle = "$scanTitlePrefix ${SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.getDefault()).format(Date())}"
            if (previewPageUri != null) {
                imageImportReview = null
                pendingImageImport = null
                imageEditorMessage = null
                scannerBackendProcessingState = ScannerBackendProcessingState.Idle
                scannerFlowValidationState = ScannerFlowValidationState()
                documentResultState = null
                importedImagePreview = PendingImageImport(
                    imageUri = previewPageUri,
                    title = previewTitle,
                    backImageUri = backPageUri,
                    isIdCardScan = isIdCardScan
                )
                if (isIdCardScan) {
                    // A first combined page renders from the raw sides right away so the preview
                    // never shows only the front; each enhancement below re-renders it.
                    refreshIdCardCombinedPreviewImage()
                    enhanceIdScanPreviewImage(
                        rawPreviewUri = previewPageUri,
                        previewTitle = previewTitle
                    )
                    if (backPageUri != null) {
                        enhanceIdScanPreviewImage(
                            rawPreviewUri = backPageUri,
                            previewTitle = previewTitle,
                            isBackSide = true
                        )
                    }
                }
            }
            viewModel.handleScanResult(this, scanResult, scanTitlePrefix, isIdCardScan)
        } else {
            viewModel.showError("Scan canceled.")
        }
        pendingScanTitlePrefix = DEFAULT_SCAN_TITLE_PREFIX
        pendingScanIsIdCardScan = false
    }

    /**
     * Best-effort local enhancement of a raw ML Kit ID-card page image ([rawPreviewUri]) for
     * the preview/export shown on screen. Never blocks or fails the scan flow itself — if
     * enhancement throws or returns nothing, the original ML Kit image simply stays in place.
     * [isBackSide] routes the result to the back-side fields instead of the front ones.
     */
    private fun enhanceIdScanPreviewImage(
        rawPreviewUri: Uri,
        previewTitle: String,
        isBackSide: Boolean = false
    ) {
        lifecycleScope.launch {
            val enhancedUriResult = runCatching {
                IdScanPostProcessor.processUri(
                    context = this@MainActivity,
                    sourceUri = rawPreviewUri,
                    outputDirectory = File(filesDir, if (isBackSide) "id_scan_preview_back" else "id_scan_preview")
                )
            }
            val enhancedUri = enhancedUriResult.getOrNull()
            if (enhancedUri == null) {
                val throwable = enhancedUriResult.exceptionOrNull()
                    ?: IllegalStateException("ID scan preview enhancement returned no image.")
                Log.w(TAG, "Unable to enhance ID scan preview.")
                recordFailure("id_scan_preview_enhance", throwable)
                return@launch
            }

            val currentPreview = importedImagePreview ?: return@launch
            if (currentPreview.title != previewTitle) return@launch
            if (isBackSide) {
                if (currentPreview.backImageUri == rawPreviewUri) {
                    importedImagePreview = currentPreview.copy(backImageUri = enhancedUri)
                    if (documentResultState?.localBackPreviewUri == rawPreviewUri.toString()) {
                        documentResultState = documentResultState?.copy(localBackPreviewUri = enhancedUri.toString())
                    }
                    refreshIdCardCombinedPreviewImage()
                }
            } else if (currentPreview.imageUri == rawPreviewUri) {
                importedImagePreview = currentPreview.copy(imageUri = enhancedUri)
                if (documentResultState?.localPreviewUri == rawPreviewUri.toString()) {
                    documentResultState = documentResultState?.copy(localPreviewUri = enhancedUri.toString())
                }
                refreshIdCardCombinedPreviewImage()
            }
        }
    }

    internal val imageImportLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        handleImportedImages(uris)
    }

    internal val fileImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            persistReadPermissionIfAvailable(uri)
        }
        viewModel.importPdf(this, uri)
    }

    internal val mergePdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isEmpty()) {
            mergePdfState = mergePdfState.copy(message = "Merge PDF canceled.")
            return@registerForActivityResult
        }
        uris.forEach(::persistReadPermissionIfAvailable)
        loadMergePdfs(uris)
    }

    internal val imagesToPdfLauncher = registerForActivityResult(
        ActivityResultContracts.GetMultipleContents()
    ) { uris ->
        if (uris.isEmpty()) {
            if (showImagesToPdf) {
                imagesToPdfState = imagesToPdfState.copy(message = "Images to PDF canceled.")
            } else {
                pdfToolsMessage = "Images to PDF canceled."
            }
        } else {
            showImagesToPdf = true
            showPdfTools = false
            imagesToPdfState = ImagesToPdfUiState(
                imageUris = uris.map(Uri::toString),
                message = "${uris.size} image${if (uris.size == 1) "" else "s"} selected."
            )
        }
    }

    internal val compressPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            compressPdfState = compressPdfState.copy(message = "Compress PDF canceled.")
            return@registerForActivityResult
        }
        persistReadPermissionIfAvailable(uri)
        compressPdfState = CompressPdfUiState(
            selectedUri = uri.toString(),
            selectedName = displayNameForUri(uri),
            originalSizeBytes = sizeForUri(uri),
            message = "PDF selected. Tap Compress to create a safe output copy."
        )
    }

    internal val splitPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            splitPdfState = splitPdfState.copy(message = "Split PDF canceled.")
            return@registerForActivityResult
        }
        persistReadPermissionIfAvailable(uri)
        loadSplitPdf(uri)
    }

    internal val lockPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            lockPdfState = lockPdfState.copy(message = "Lock PDF canceled.")
            return@registerForActivityResult
        }
        persistReadPermissionIfAvailable(uri)
        lockPdfState = PdfPasswordToolState(
            selectedUri = uri.toString(),
            selectedName = displayNameForUri(uri),
            password = lockPdfState.password,
            message = "PDF selected."
        )
    }

    internal val unlockPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            unlockPdfState = unlockPdfState.copy(message = "Unlock PDF canceled.")
            return@registerForActivityResult
        }
        persistReadPermissionIfAvailable(uri)
        unlockPdfState = PdfPasswordToolState(
            selectedUri = uri.toString(),
            selectedName = displayNameForUri(uri),
            password = unlockPdfState.password,
            message = "PDF selected."
        )
    }

    internal val signPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            signPdfState = signPdfState.copy(message = "Sign PDF canceled.")
            return@registerForActivityResult
        }
        persistReadPermissionIfAvailable(uri)
        loadSignPdf(uri)
    }

    internal val watermarkPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            watermarkPdfState = watermarkPdfState.copy(message = "Watermark PDF canceled.")
            return@registerForActivityResult
        }
        persistReadPermissionIfAvailable(uri)
        loadWatermarkPdf(uri)
    }

    internal val pdfToWordLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            pdfToWordState = pdfToWordState.copy(message = "PDF to Word canceled.")
            return@registerForActivityResult
        }
        persistReadPermissionIfAvailable(uri)
        loadPdfToWord(uri)
    }

    internal val pdfToImagesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            pdfToImagesState = pdfToImagesState.copy(message = "PDF to Images canceled.")
            return@registerForActivityResult
        }
        persistReadPermissionIfAvailable(uri)
        renderPdfToImages(uri)
    }

    internal val editPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            editPdfState = editPdfState.copy(message = "Edit PDF canceled.")
            return@registerForActivityResult
        }
        persistReadPermissionIfAvailable(uri)
        loadEditPdf(uri)
    }

    internal val createBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) {
            backupStatusMessage = "Backup canceled."
        } else {
            createBackup(uri)
        }
    }

    internal val restoreBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            backupStatusMessage = "Restore canceled."
        } else {
            readBackupForRestore(uri)
        }
    }

    internal val googleSignInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        try {
            val account = task.getResult(ApiException::class.java)
            cloudSyncRepository.setAccount(account.email)
        } catch (exception: ApiException) {
            Log.w(TAG, "Google Sign-In failed: ${exception.statusCode}")
            viewModel.showError("Google Sign-In failed.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as DocScannerPdfApplication
        appLockRepository = AppLockRepository(this)
        backupRepository = app.backupRepository
        analyticsRepository = app.analyticsRepository
        billingRepository = app.billingRepository
        cloudSyncRepository = app.cloudSyncRepository
        onboardingRepository = app.onboardingRepository
        refreshAppLockSettings()
        lastBackupInfo = backupRepository.getLastBackupInfo()
        appUnlocked = !appLockSettings.lockEnabled
        showOnboarding = !onboardingRepository.isOnboardingCompleted()

        setContent {
            DocScannerApp(this)
        }

        observeInterstitialRequests()
    }

    // Back navigation is handled by lifecycle-aware Compose BackHandler registrations.
    // MainNavigation remains the centralized policy for application-level back actions.

    internal fun refreshAppLockSettings() {
        appLockSettings = appLockRepository.getSettings()
    }


    internal fun closeLockPdf() {
        showLockPdf = false
        lockPdfState = PdfPasswordToolState()
        showPdfTools = true
    }

    internal fun closeUnlockPdf() {
        showUnlockPdf = false
        unlockPdfState = PdfPasswordToolState()
        showPdfTools = true
    }

    internal fun closeSignPdf() {
        showSignPdf = false
        signPdfState.pageThumbnails.forEach { bitmap -> bitmap.recycle() }
        signPdfState.signatureBitmap?.recycle()
        signPdfState = SignPdfUiState()
        showPdfTools = true
    }

    internal fun closeWatermarkPdf() {
        showWatermarkPdf = false
        watermarkPdfState.previewBitmap?.recycle()
        watermarkPdfState = WatermarkPdfUiState()
        showPdfTools = true
    }

    internal fun closePdfToWord() {
        showPdfToWord = false
        pdfToWordState = PdfToWordUiState()
        showPdfTools = true
    }

    internal fun closePdfToImages() {
        showPdfToImages = false
        pdfToImagesState.thumbnails.forEach { bitmap -> bitmap.recycle() }
        pdfToImagesState = PdfToImagesUiState()
        showPdfTools = true
    }

    internal fun closeEditPdf(returnToTools: Boolean = true) {
        showEditPdf = false
        editPdfState.pages.forEach { page -> page.thumbnail.recycle() }
        editPdfState = EditPdfUiState()
        showPdfTools = returnToTools && returnToPdfToolsAfterEdit
        returnToPdfToolsAfterEdit = true
    }

    internal fun closeMergePdf() {
        showMergePdf = false
        mergePdfState = MergePdfUiState()
        showPdfTools = true
    }

    internal fun closeSplitPdf() {
        showSplitPdf = false
        splitPdfState.pageThumbnails.forEach { bitmap -> bitmap.recycle() }
        splitPdfState = SplitPdfUiState()
        showPdfTools = true
    }

    internal fun closeImagesToPdf() {
        showImagesToPdf = false
        imagesToPdfState = ImagesToPdfUiState()
        showPdfTools = true
    }

    internal fun closeCompressPdf() {
        showCompressPdf = false
        compressPdfState = CompressPdfUiState()
        showPdfTools = true
    }

    internal fun completeOnboarding() {
        onboardingRepository.markOnboardingCompleted()
        showOnboarding = false
    }

    internal fun viewOnboardingAgain() {
        onboardingRepository.resetOnboarding()
        showAppLockSettings = false
        showOnboarding = true
    }

    internal fun openPremium() {
        analyticsRepository.trackEvent(AnalyticsRepository.EVENT_PREMIUM_OPENED)
        showPremium = true
    }

    private fun recordFailure(area: String, throwable: Throwable, metadata: Map<String, String> = emptyMap()) {
        analyticsRepository.recordNonFatal(throwable = throwable, area = area, metadata = metadata)
    }

    internal fun unlockWithPin(pin: String) {
        if (appLockRepository.verifyPin(pin)) {
            appUnlocked = true
            appLockError = null
        } else {
            appLockError = "Incorrect PIN. Try again."
        }
    }

    internal fun canUseBiometrics(): Boolean {
        return BiometricManager.from(this).canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    internal fun hasDangerousPermissionsDeclared(): Boolean {
        val requestedPermissions = packageManager
            .getPackageInfo(packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
        return requestedPermissions.any { permission ->
            permission in DANGEROUS_PERMISSION_NAMES
        }
    }

    internal fun showBiometricPrompt() {
        if (!appLockSettings.biometricsEnabled) {
            appLockError = "Biometric unlock is disabled."
            return
        }
        if (!canUseBiometrics()) {
            appLockError = "Biometrics are not available on this device."
            return
        }

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Doc Scanner PDF")
            .setSubtitle("Use your fingerprint or biometric credential")
            .setNegativeButtonText("Use PIN")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    appUnlocked = true
                    appLockError = null
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    if (errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON && errorCode != BiometricPrompt.ERROR_USER_CANCELED) {
                        appLockError = errString.toString()
                    }
                }

                override fun onAuthenticationFailed() {
                    super.onAuthenticationFailed()
                    appLockError = "Biometric match failed. Try again."
                }
            }
        )
        prompt.authenticate(promptInfo)
    }

    private fun createBackup(uri: Uri) {
        lifecycleScope.launch {
            backupProcessing = true
            backupStatusMessage = "Creating backup..."
            try {
                val (bytes, summary) = backupRepository.createBackupZip()
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri)?.use { output ->
                        output.write(bytes)
                    } ?: error("Unable to open backup destination.")
                }
                backupRepository.markBackupCreated(summary)
                lastBackupInfo = backupRepository.getLastBackupInfo()
                backupStatusMessage = "Backup created with ${summary.documentCount} documents."
                analyticsRepository.trackEvent(
                    AnalyticsRepository.EVENT_BACKUP_CREATED,
                    mapOf(
                        "document_count" to summary.documentCount,
                        "folder_count" to summary.folderCount,
                        "tag_count" to summary.tagCount
                    )
                )
            } catch (throwable: Throwable) {
                Log.w(TAG, "Unable to create backup: ${throwable.message}")
                recordFailure("backup_create", throwable)
                backupStatusMessage = throwable.message ?: "Unable to create backup."
            } finally {
                backupProcessing = false
            }
        }
    }

    private fun readBackupForRestore(uri: Uri) {
        lifecycleScope.launch {
            backupProcessing = true
            backupStatusMessage = "Validating backup..."
            try {
                val bytes = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        input.readBytes()
                    } ?: error("Unable to read backup file.")
                }
                pendingRestoreArchive = backupRepository.parseBackup(bytes)
                backupStatusMessage = "Backup validated. Review the restore summary."
            } catch (throwable: Throwable) {
                Log.w(TAG, "Unable to read backup: ${throwable.message}")
                pendingRestoreArchive = null
                backupStatusMessage = throwable.message ?: "Backup file is invalid."
            } finally {
                backupProcessing = false
            }
        }
    }

    internal fun restorePendingBackup() {
        val archive = pendingRestoreArchive ?: return
        lifecycleScope.launch {
            backupProcessing = true
            backupStatusMessage = "Restoring backup..."
            pendingRestoreArchive = null
            try {
                backupRepository.restoreBackup(archive)
                backupStatusMessage = "Restore complete. ${archive.summary.documentCount} documents restored."
                analyticsRepository.trackEvent(
                    AnalyticsRepository.EVENT_BACKUP_RESTORED,
                    mapOf(
                        "document_count" to archive.summary.documentCount,
                        "folder_count" to archive.summary.folderCount,
                        "tag_count" to archive.summary.tagCount
                    )
                )
            } catch (throwable: Throwable) {
                Log.w(TAG, "Unable to restore backup: ${throwable.message}")
                recordFailure("backup_restore", throwable)
                backupStatusMessage = throwable.message ?: "Restore failed. Existing data was kept."
            } finally {
                backupProcessing = false
            }
        }
    }

    internal fun defaultBackupFileName(): String {
        return "docscanner-backup-${System.currentTimeMillis()}.zip"
    }

    internal fun startGoogleSignIn() {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_APPDATA_SCOPE))
            .build()
        val client = GoogleSignIn.getClient(this, options)
        googleSignInLauncher.launch(client.signInIntent)
    }

    internal fun signOutFromGoogle() {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(Scope(DRIVE_APPDATA_SCOPE))
            .build()
        GoogleSignIn.getClient(this, options).signOut()
            .addOnCompleteListener {
                cloudSyncRepository.setAccount(null)
            }
    }

    override fun onStart() {
        super.onStart()
        val inactiveFor = backgroundedAt?.let { timestamp -> System.currentTimeMillis() - timestamp } ?: 0L
        backgroundedAt = null
        if (appLockSettings.lockEnabled && inactiveFor >= APP_LOCK_TIMEOUT_MS) {
            appUnlocked = false
            appLockError = null
        }
    }

    override fun onStop() {
        backgroundedAt = System.currentTimeMillis()
        super.onStop()
    }

    private fun handleImportedImages(uris: List<Uri>) {
        if (uris.isEmpty()) {
            if (pendingImageImport != null) {
                imageEditorMessage = "Image import canceled."
            } else if (imageImportReview != null) {
                imageEditorMessage = "Image import canceled."
            } else {
                viewModel.showError("Image import canceled.")
            }
            return
        }

        imageImportReview = PendingImageReview(imageUris = uris)
        pendingImageImport = null
        importedImagePreview = null
        scannerBackendProcessingState = ScannerBackendProcessingState.Idle
        scannerFlowValidationState = ScannerFlowValidationState()
        documentResultState = null
        imageEditorMessage = null
    }

    internal fun importSelectedReviewImage() {
        val review = imageImportReview ?: return
        val selectedIndex = review.selectedIndices.minOrNull() ?: return
        val selectedUri = review.imageUris.getOrNull(selectedIndex) ?: return

        lifecycleScope.launch {
            try {
                val localUri = viewModel.copyImportedImageForEditor(
                    context = this@MainActivity,
                    imageUri = selectedUri
                )
                pendingImageImport = PendingImageImport(imageUri = localUri)
                imageImportReview = null
                imageEditorMessage = null
            } catch (throwable: Throwable) {
                Log.w(TAG, "Unable to prepare imported image: ${throwable.message}")
                viewModel.showError("Unable to import selected image.")
            }
        }
    }

    internal fun confirmImportedImageSave(editorState: PendingImageImport) {
        lifecycleScope.launch {
            pendingImageImport = pendingImageImport?.copy(isExtractingText = true)
            val extractedText = editorState.extractedText ?: runCatching {
                viewModel.recognizeText(this@MainActivity, editorState.imageUri)
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to extract text before save: ${throwable.message}")
                recordFailure("ocr", throwable, mapOf("source" to "imported_image_save"))
            }.onSuccess { text ->
                analyticsRepository.trackEvent(
                    AnalyticsRepository.EVENT_OCR_EXTRACTED,
                    mapOf("source" to "imported_image_save", "has_text" to text.isNotBlank())
                )
            }.getOrNull()

            val finalState = editorState.copy(
                extractedText = extractedText?.takeIf { it.isNotBlank() },
                isExtractingText = false
            )
            viewModel.saveImportedImageDocument(
                title = finalState.title,
                imageUri = finalState.imageUri,
                extractedText = finalState.extractedText
            )
            pendingImageImport = null
            scannerBackendProcessingState = ScannerBackendProcessingState.Idle
            scannerFlowValidationState = ScannerFlowValidationState()
            documentResultState = null
            importedImagePreview = finalState
            // Re-editing an ID-card scan's front image invalidates the combined result page;
            // re-render it from the edited sides. No-op for normal documents.
            if (finalState.isIdCardScan) {
                refreshIdCardCombinedPreviewImage()
            }
        }
    }

    internal fun processImportedPreviewWithBackend() {
        val preview = importedImagePreview
        if (preview == null) {
            scannerBackendProcessingState = ScannerBackendProcessingState.Error(
                "No scanned image is available for backend processing."
            )
            return
        }

        lifecycleScope.launch {
            scannerBackendProcessingState = ScannerBackendProcessingState.Uploading()
            when (
                val result = processDocumentUseCase.processCapturedImageAndPoll(
                    context = this@MainActivity,
                    imageUri = preview.imageUri,
                    title = preview.title,
                    onState = { state ->
                        scannerBackendProcessingState = state.toScannerBackendProcessingState()
                    }
                )
            ) {
                is NetworkResult.Success -> {
                    if (scannerBackendProcessingState !is ScannerBackendProcessingState.Error) {
                        scannerBackendProcessingState = result.data.toScannerBackendProcessingState()
                    }
                }
                is NetworkResult.Error -> {
                    scannerBackendProcessingState = ScannerBackendProcessingState.Error(
                        result.errorBody?.takeIf { it.isNotBlank() } ?: "Backend processing failed: ${result.code} ${result.message}"
                    )
                }
                is NetworkResult.Exception -> {
                    scannerBackendProcessingState = ScannerBackendProcessingState.Error(
                        result.throwable.message ?: "Backend processing failed."
                    )
                }
            }
        }
    }

    /**
     * Runs the full end-to-end scanner validation slice (upload -> process -> poll ->
     * resolve processed image -> fetch OCR) against the current scanned/imported preview,
     * streaming every milestone into [scannerFlowValidationState]. Also serves as the
     * retry entry point for the whole flow.
     */
    internal fun runScannerFlowValidation() {
        val preview = importedImagePreview
        if (preview == null) {
            scannerFlowValidationState = ScannerFlowValidationState(
                stage = ScannerFlowStage.ERROR,
                statusMessage = "No scanned image is available for validation.",
                failureReason = "No scanned image is available for validation."
            )
            return
        }

        val localPreviewUri = preview.imageUri.toString()
        val localBackPreviewUri = preview.backImageUri?.toString()
        lifecycleScope.launch {
            scannerFlowValidationUseCase.validate(
                context = this@MainActivity,
                imageUri = preview.imageUri,
                title = preview.title,
                onState = { state ->
                    scannerFlowValidationState = state
                    // Keep the unified result screen live while it is open (e.g. on retry).
                    if (documentResultState != null) {
                        documentResultState = state.toDocumentResultState(localPreviewUri, localBackPreviewUri)
                    }
                }
            )
        }
    }

    /**
     * Opens the unified [DocumentResultScreen] as the production destination, seeded from
     * the current end-to-end validation state plus the on-device preview as a fallback image.
     */
    internal fun openDocumentResult() {
        val preview = importedImagePreview
        documentResultState = scannerFlowValidationState.toDocumentResultState(
            localPreviewUri = preview?.imageUri?.toString(),
            localBackPreviewUri = preview?.backImageUri?.toString()
        )
    }

    internal fun closeDocumentResult() {
        persistCurrentAnnotations()
        annotationEditor = null
        cancelCropEditor()
        appliedCropQuad = null
        documentResultState = null
    }

    /**
     * Annotations as they should be drawn over the *currently displayed* image. When a crop is
     * applied, the canonically-stored (base-image) annotations are projected forward through the
     * crop homography so the overlay matches the cropped preview exactly. The stored annotations
     * are never mutated.
     */
    internal fun displayAnnotations(): List<Annotation> {
        val stored = annotationEditor?.page?.annotations ?: emptyList()
        val quad = appliedCropQuad ?: return stored
        return AnnotationHomographyMapper.applyQuadTransform(stored, sourceQuad = quad)
    }

    // ---- Annotations (local-first; persisted as a per-document JSON blob) ----

    private fun annotationDocId(state: DocumentResultState?): String =
        state?.documentId ?: state?.localPreviewUri ?: "unknown"

    private fun annotationPageId(state: DocumentResultState?): String =
        state?.pageId ?: "page-1"

    /**
     * Loads any persisted annotations for the open result's page into an editor session.
     * Guarded so it only loads once per page — re-entering the same page never discards
     * in-progress edits (mode switching keeps the session intact).
     */
    internal fun beginAnnotationSession(state: DocumentResultState) {
        val pageId = annotationPageId(state)
        if (annotationEditor?.page?.pageId == pageId) return
        val stored = annotationRepository.loadPage(annotationDocId(state), pageId)
        annotationEditor = AnnotationEditorState(
            page = PageAnnotationState(pageId = pageId, annotations = stored)
        )
    }

    private fun persistCurrentAnnotations() {
        val editor = annotationEditor ?: return
        val docId = annotationDocId(documentResultState)
        val page = editor.page
        lifecycleScope.launch(Dispatchers.IO) {
            annotationRepository.savePage(docId, page.pageId, page.annotations)
        }
    }

    private fun updateAnnotationEditor(
        persist: Boolean = true,
        transform: (AnnotationEditorState) -> AnnotationEditorState
    ) {
        annotationEditor = annotationEditor?.let(transform)
        if (persist) persistCurrentAnnotations()
    }

    internal fun toggleAnnotationMode() =
        updateAnnotationEditor(persist = false) { AnnotationEditorReducer.toggleMode(it) }

    internal fun selectAnnotationTool(tool: AnnotationTool) =
        updateAnnotationEditor(persist = false) { AnnotationEditorReducer.setTool(it, tool) }

    internal fun addAnnotationStroke(stroke: AnnotationStroke) {
        // The overlay captures strokes in the displayed (possibly cropped) space. Project them
        // back into the canonical base-image space so the store stays in one coordinate system.
        val quad = appliedCropQuad
        val canonical = if (quad == null) {
            stroke
        } else {
            AnnotationHomographyMapper.inverseQuadTransform(listOf(stroke), sourceQuad = quad)
                .firstOrNull() as? AnnotationStroke ?: stroke
        }
        updateAnnotationEditor { AnnotationEditorReducer.addAnnotation(it, canonical) }
    }

    internal fun undoAnnotation() =
        updateAnnotationEditor { AnnotationEditorReducer.undo(it) }

    internal fun redoAnnotation() =
        updateAnnotationEditor { AnnotationEditorReducer.redo(it) }

    // ---- Smart crop / perspective correction (local-first) ----

    /**
     * Opens the crop editor for the current result's displayed image. The source bitmap loads
     * asynchronously; the warp on apply produces a new local file and overrides the displayed
     * image — OCR text and annotations are left untouched.
     */
    internal fun openCropEditor() {
        // Always crop from the base image (not the cropped override) so repeated crops adjust the
        // same region instead of stacking transforms — which also keeps annotation sync stable.
        val source = documentResultState?.baseImageModel
        if (source.isNullOrBlank()) {
            viewModel.showError("No image is available to crop.")
            return
        }
        cropSourceBitmap = null
        val startingQuad = appliedCropQuad ?: PerspectiveQuad.full()
        cropState = CropState(
            sourceImageUri = source,
            quad = startingQuad,
            originalQuad = startingQuad,
            mode = com.dev.docscannerpdf.domain.crop.CropMode.EDITING
        )
        lifecycleScope.launch {
            val bitmap = cropImageProcessor.loadSource(source)
            if (bitmap == null) {
                cropState = null
                viewModel.showError("Unable to load the image for cropping.")
                return@launch
            }
            cropSourceBitmap = bitmap

            // Live edge detection provides an initial quad suggestion only — it never overrides a
            // previously applied crop or a quad the user has already started adjusting.
            if (appliedCropQuad == null) {
                val suggestion = withContext(Dispatchers.Default) {
                    runCatching {
                        liveFrameAnalyzer.analyze(LumaFrameFactory.fromBitmap(bitmap))
                    }.getOrNull()
                }
                val current = cropState
                if (suggestion != null && current != null &&
                    current.quad == PerspectiveQuad.full()
                ) {
                    cropState = current.copy(
                        quad = suggestion.quad,
                        originalQuad = suggestion.quad
                    )
                }
            }
        }
    }

    internal fun cancelCropEditor() {
        cropState = null
        cropSourceBitmap = null
    }

    internal fun cropMoveCorner(corner: CropCorner, x: Float, y: Float) {
        cropState = cropState?.let { CropReducer.moveCorner(it, corner, x, y) }
    }

    internal fun cropResetQuad() {
        cropState = cropState?.let { CropReducer.resetQuad(it) }
    }

    /**
     * Applies the crop: validates + auto-fixes the quad, warps the source bitmap to a new local
     * file, and overrides the result's displayed/exported image with it. OCR and annotations are
     * preserved; only the image changes.
     */
    internal fun cropApply() {
        val current = cropState ?: return
        val bitmap = cropSourceBitmap
        if (bitmap == null) {
            viewModel.showError("Image is still loading.")
            return
        }
        val applying = CropReducer.applyCrop(current)
        if (!applying.isApplying) {
            viewModel.showError("Adjust the corners into a valid shape before applying.")
            return
        }
        cropState = applying
        lifecycleScope.launch {
            val croppedUri = cropImageProcessor.warpAndSave(bitmap, applying.quad)
            if (croppedUri == null) {
                cropState = current.copy(mode = com.dev.docscannerpdf.domain.crop.CropMode.EDITING)
                viewModel.showError("Unable to apply crop.")
                return@launch
            }
            documentResultState = documentResultState?.copy(localCroppedUri = croppedUri.toString())
            // Remember the quad so annotations are projected to match the cropped image.
            appliedCropQuad = applying.quad
            cancelCropEditor()
            viewModel.showError("Crop applied.")
        }
    }

    /** Opens the live CameraX detection screen. */
    internal fun openLiveScanner() {
        showAiTools = false
        showLiveScanner = true
    }

    /** Directory captured ID-card images are written to — private app storage, never external. */
    internal val idCardCaptureDirectory: File
        get() = File(filesDir, "id_card_guided_capture")

    /** Directory captured passport pages are written to — private app storage, never external. */
    internal val passportCaptureDirectory: File
        get() = File(filesDir, "passport_guided_capture")

    /** Whether the single-page passport guided camera is showing (distinct from ID-card capture). */
    internal var showPassportCapture by mutableStateOf(false)

    /** Opens the guided ID-card camera screen in place of the ML Kit document scanner. */
    internal fun startIdCardGuidedCapture(titlePrefix: String) {
        pendingIdCardCaptureTitlePrefix = titlePrefix
        showIdCardGuidedCapture = true
    }

    /** Opens the guided single-page passport camera (its own flow, never the ID-card Front/Back one). */
    internal fun startPassportCapture() {
        showIdCardFlow = false
        showPassportCapture = true
    }

    // ---- Main Scanner (app-owned document capture) --------------------------------------------
    //
    // The app's PRIMARY document workflow. Distinct from the ID-card and passport flows and from
    // the ML Kit document scanner, which stays reachable as an internal fallback until this flow
    // reaches approved parity with docs/main-scanner-reference.md.
    //
    // Slice 1 scope: camera ownership, manual capture, the pending-page session, temp-file
    // ownership, and routing into the dedicated crop surface. Deliberately NOT here: any Room
    // write (capture must never persist), any generic Document Ready routing, and any raw-pixel
    // fallback after a failed capture.

    /**
     * Directory captured Main Scanner pages are written to — private app storage, never external.
     *
     * The NAME comes from [MainScanAuthoritativeRender.CAPTURE_DIRECTORY_NAME], which is the same
     * constant [MainScanAuthoritativeRender.isSupportedAuthoritativeSource] admits a source by. Two
     * spellings of one directory is a defect waiting for a rename: captures would keep landing here
     * while every one of them was refused as an unsupported source, and nothing but a device run
     * would say so. This file already depends on that object for the two sibling directories below,
     * so naming it here adds no dependency it did not have.
     */
    internal val mainScanCaptureDirectory: File
        get() = File(filesDir, MainScanAuthoritativeRender.CAPTURE_DIRECTORY_NAME)

    /**
     * The RETAINED owner of the current Main Scanner visit.
     *
     * Activity-scoped, so a recreation inside this process — configuration change, theme or locale
     * switch, the return from an app-lock unlock, a foldable size change — hands back the same
     * instance. Every Main Scanner field below is a view onto it rather than its own
     * `mutableStateOf`, which is what stops a recreation from silently opening a brand-new visit
     * while the previous visit's capture is still on disk and no longer referenced by any ledger.
     *
     * The store holds no Context and performs no I/O. Ownership, containment and every delete stay
     * here, on the activity that has `filesDir`.
     *
     * This survives recreation, NOT process death — see [MainScanVisitStore].
     */
    private val mainScanVisit: MainScanVisitStore by viewModels()

    /** Whether the app-owned Main Scanner camera is showing. Retained with the visit. */
    internal var showMainScanCapture: Boolean
        get() = mainScanVisit.captureSurfaceVisible
        set(value) {
            mainScanVisit.captureSurfaceVisible = value
        }

    /**
     * Pure session state for the current Main Scanner visit: capture stage, generation token,
     * pending page, owned-file ledger and discard-dialog visibility. Every transition goes through
     * [MainScanCaptureFlow] so the capture contract is unit-testable without a device.
     *
     * Retained AS ONE VALUE on [mainScanVisit]. Reconstructing a pending page without the ledger it
     * came with would leave every file this visit wrote unnameable by any sweep, so the state is
     * never split across the lifecycle boundary — the ledger crosses it with the page or not at all.
     */
    internal var mainScanState: MainScanCaptureState
        get() = mainScanVisit.captureState
        set(value) {
            mainScanVisit.captureState = value
        }

    /** Opens the app-owned Main Scanner camera on a brand-new visit id. */
    internal fun startMainScanCapture() {
        // A new visit must not inherit a previous visit's ledger, generation or pending page, and
        // its NEW sessionId invalidates every capture still in flight from the previous one.
        val previous = mainScanState
        // Nor may it inherit the previous visit's STAGE. That used to be free: the stage was an
        // activity-local field that a recreation reset for us. It is retained now, so the reset has
        // to be explicit — and it is not cosmetic. Crop preparation is admitted only from the
        // pre-pipeline stages, so a visit that opened while a stale `EnhancementReview` (or a stale
        // `Failed`) was still held would have its brand-new capture refused preparation forever:
        // a page on screen that no decode would ever run for. Tearing down here makes "a new visit
        // begins at CameraReady, holding none of the last one's pixels" true by construction rather
        // than by every exit path remembering to leave it that way.
        clearMainScanPipeline()
        sweepMainScanSession(previousState = previous, retainUris = emptySet())
        mainScanState = MainScanCaptureFlow.beginVisit(previous)
        showMainScanCapture = true
        // Create the capture directory NOW, off the main thread, so the shutter path never performs
        // mkdirs on the UI thread (the capture call only does a cheap isDirectory check).
        //
        // Then reclaim unreachable orphans. The visit ledger lives in memory, so a process death or
        // a force-stop mid-visit strands its capture files permanently — physical QA found four such
        // files from an earlier interrupted run. Entering the scanner from the dashboard is the one
        // moment when NOTHING in this directory can still be needed (no pending page exists yet, and
        // a saved document never lives here), so a full sweep is safe exactly here. It is not done on
        // the discard→camera path, which keeps its own visit ledger.
        //
        // The two authoritative directories are swept on the SAME terms and for the same reason: an
        // artifact written moments before a force-stop is referenced only by a ledger that died with
        // the process, and a full-resolution page is a far larger thing to strand than a capture.
        val directories = listOf(
            mainScanCaptureDirectory,
            mainScanCroppedDirectory,
            mainScanEnhancedDirectory
        )
        // Only files that already existed when this visit opened may be reclaimed. The sweep runs
        // asynchronously, so without this bound a capture completing before the enumeration could be
        // deleted out from under its own pending page — destroying the very frame the user is about
        // to crop. A file this visit writes always has a later timestamp, so it is structurally
        // outside the sweep's reach rather than merely unlikely to be caught by it.
        val visitOpenedAtMs = System.currentTimeMillis()
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                val reclaimed = directories.sumOf { directory ->
                    runCatching { if (!directory.isDirectory) directory.mkdirs() }
                    runCatching {
                        directory.listFiles()?.count { file ->
                            file.isFile &&
                                MainScanFileOwnership.isReclaimableOrphan(
                                    lastModifiedMs = file.lastModified(),
                                    visitOpenedAtMs = visitOpenedAtMs
                                ) &&
                                file.delete()
                        } ?: 0
                    }.getOrDefault(0)
                }
                if (BuildConfig.DEBUG && reclaimed > 0) {
                    Log.d(MAIN_SCAN_TAG, "MAIN_SCAN_ORPHANS reclaimed=$reclaimed")
                }
            }
        }
    }

    /**
     * Leaves the capture surface. Only called once Back has established there is nothing to lose
     * (see [MainScanCaptureFlow.backNeedsConfirmation]). Moving to a new visit id is what makes a
     * late capture callback from the visit being left unable to publish or navigate, and anything
     * owned is still swept so it cannot leave a file behind.
     */
    internal fun closeMainScanCapture() {
        val abandoned = mainScanState
        showMainScanCapture = false
        mainScanState = MainScanCaptureFlow.beginVisit(abandoned)
        // The visit is over, so its in-memory pipeline must go with it — otherwise the working,
        // cropped and enhanced bitmaps and the polygon outlive the page they belong to.
        clearMainScanPipeline()
        sweepMainScanSession(previousState = abandoned, retainUris = emptySet())
    }

    /**
     * Acquires the single-flight capture slot, returning the ticket (visit id + generation) the
     * capture screen must carry through its async result, or null when the reducer rejects the tap
     * (a capture is already in flight, a hand-off is routing, or the discard dialog is open).
     */
    internal fun onMainScanCaptureStarted(
        seedCandidate: MainScanAnalysisResult?,
        guideVisible: Boolean
    ): MainScanCaptureTicket? {
        val issued = MainScanCaptureFlow.beginCapture(
            state = mainScanState,
            seedCandidate = seedCandidate,
            guideVisible = guideVisible,
            timestampMs = System.currentTimeMillis()
        )
        if (issued == null) {
            MainScanTrace.shutterRejected(reason = "reducer_busy_or_dialog")
            return null
        }
        val (next, ticket) = issued
        mainScanState = next
        MainScanTrace.shutterAccepted(ticket.sessionId, ticket.generation)
        // The seed is frozen inside beginCapture, against this exact ticket, and is never revisited.
        MainScanTrace.cropSeedFrozen(
            sessionId = ticket.sessionId,
            generation = ticket.generation,
            seeded = MainScanCropSeeding.hasUsableSeed(next.frozenCropSeed, ticket)
        )
        return ticket
    }

    /**
     * Publishes a captured page and routes to the dedicated crop surface. If the reducer rejects the
     * result — a newer capture won, or the visit that started it has since been left — the file is
     * referenced by nothing and is deleted, so a stale capture neither navigates nor leaks output.
     *
     * The delete goes through the two-barrier guard, so it can only ever remove an app-owned
     * `file://` path inside private storage. No document row is created here: capture never persists.
     */
    internal fun onMainScanCaptureSucceeded(ticket: MainScanCaptureTicket, uri: Uri) {
        val before = mainScanState
        val surfaceBefore = mainScanSurfaceName(before)
        val after = MainScanCaptureFlow.captureSucceeded(
            state = before,
            ticket = ticket,
            uri = uri.toString(),
            source = MainScanPageSource.CAMERA
        )
        mainScanState = after
        MainScanTrace.surfaceTransition(before = surfaceBefore, after = mainScanSurfaceName(after))
        if (uri.toString() !in MainScanFileOwnership.referencedUris(after)) {
            MainScanTrace.ticketRejected(
                ticketSessionId = ticket.sessionId,
                ticketGeneration = ticket.generation,
                liveSessionId = before.sessionId,
                liveGeneration = before.captureGeneration
            )
            val appOwned = MainScanFileOwnership.isOwnedFileUri(uri.toString(), filesDir.absolutePath)
            MainScanTrace.staleOutputDeleted(appOwned = appOwned)
            // Retained scope: a rejected capture's file is referenced by NO ledger, so a recreation
            // cancelling this delete strands it until the next scanner-open orphan sweep. The
            // two-barrier guard is unchanged — only the lifetime the delete is bounded by.
            mainScanVisit.processingScope.launch { deleteMainScanOwnedFile(uri.toString()) }
            return
        }
        MainScanTrace.ticketAccepted(ticket.sessionId, ticket.generation)
        MainScanTrace.pendingPagePublished(ticket.sessionId, ticket.generation)
    }

    /**
     * The surface name the resolver would pick for [state], used only for the debug trace so a
     * publication can be read against the surface change it caused. Mirrors the resolver's main-scan
     * ordering; never used for real routing.
     */
    private fun mainScanSurfaceName(state: MainScanCaptureState): String = when {
        state.pendingPage != null -> "MAIN_SCAN_CROP"
        showMainScanCapture -> "MAIN_SCAN_CAPTURE"
        else -> "OTHER"
    }

    /**
     * A capture produced no callback within the watchdog bound. Re-arms the shutter with a controlled
     * error and advances the generation so the abandoned capture is now stale — if its result arrives
     * late it publishes nothing and its output is deleted.
     */
    internal fun onMainScanCaptureTimedOut(ticket: MainScanCaptureTicket) {
        val before = mainScanState
        val after = MainScanCaptureFlow.captureTimedOut(before, ticket)
        if (after === before) return
        mainScanState = after
        viewModel.showError("That shot didn't complete. Please try again.")
    }

    /**
     * Re-arms the shutter after a failed capture. A stale failure (its visit was left, or a newer
     * capture superseded it) is ignored by the reducer and shows no error. Creates no page, no
     * document, and never falls back to raw pixels.
     */
    internal fun onMainScanCaptureFailed(ticket: MainScanCaptureTicket) {
        val before = mainScanState
        val after = MainScanCaptureFlow.captureFailed(before, ticket)
        if (after === before) return
        mainScanState = after
        viewModel.showError("Couldn't take that shot. Please try again.")
    }

    /**
     * The camera could not attach all three required use cases. The Main Scanner surface reports a
     * controlled failure rather than presenting a degraded scanner; this leaves the visit and hands
     * the user to the intact ML Kit path.
     */
    internal fun onMainScanCameraUnavailable() {
        if (BuildConfig.DEBUG) {
            Log.d(MAIN_SCAN_TAG, "MAIN_SCAN_CAMERA unavailable=true fallback=ml_kit_offered")
        }
        closeMainScanCapture()
        startDocumentScanner(pageLimit = 20)
    }

    // ---- Main Scanner crop / processing pipeline ------------------------------------------------
    //
    // Runs entirely pre-persistence: nothing here writes a Room row, and no document or page exists
    // until an explicit Confirm arrives in a later pass. Bitmaps live in memory for the visit and
    // are released on discard; the captured JPEG on disk is never modified by any stage.

    // Each field below is a view onto the retained visit, not activity-local state. The pipeline
    // reads and writes them exactly as before; what changed is that a recreation no longer resets
    // them, so the stage, the polygon and the pixels the user is looking at all describe the same
    // page after the activity comes back as they did before it went away.

    /** The stage the captured page is at. Drives which crop-surface content is composed. */
    internal var mainScanStage: MainScanStage
        get() = mainScanVisit.stage
        set(value) {
            mainScanVisit.stage = value
        }

    /** The EXIF-upright working copy of the accepted capture. */
    internal var mainScanWorkingImage: MainScanWorkingImage?
        get() = mainScanVisit.workingImage
        set(value) {
            mainScanVisit.workingImage = value
        }

    /** Polygon editing state. Null until the working image and initial polygon are resolved. */
    internal var mainScanCropState: MainScanCropState?
        get() = mainScanVisit.cropState
        set(value) {
            mainScanVisit.cropState = value
        }

    /** The perspective-corrected page, and the enhanced render of it. */
    internal var mainScanCroppedImage: Bitmap?
        get() = mainScanVisit.croppedImage
        set(value) {
            mainScanVisit.croppedImage = value
        }

    internal var mainScanEnhancedImage: Bitmap?
        get() = mainScanVisit.enhancedImage
        set(value) {
            mainScanVisit.enhancedImage = value
        }

    /**
     * The ONLY persistable result of this pipeline: the source-resolution post-crop artifact.
     *
     * Every bitmap above is a PREVIEW. They are decoded at
     * [MainScanCaptureImageLoader.MAX_WORKING_EDGE] so an interactive surface can hold them, which
     * makes them right for the editor and unfit for the library — persisting one would quietly
     * discard most of the capture. Keeping the authoritative result in its own field, of a type that
     * cannot be built from a preview ([MainScanAuthoritativeArtifact]), is what makes "the preview
     * was saved instead" unrepresentable rather than merely unlikely.
     *
     * Null means there is nothing a future Confirm may write. It is null before a render, null after
     * a first render fails, and cleared SYNCHRONOUSLY the moment the polygon it was made from stops
     * being the confirmed one.
     */
    internal var mainScanAuthoritative: MainScanAuthoritativeArtifact?
        get() = mainScanVisit.authoritative
        set(value) {
            mainScanVisit.authoritative = value
        }

    /**
     * Why the last authoritative render produced nothing, or null when it succeeded or has not run.
     * Held so the review can say something true about the high-quality result instead of implying
     * the preview it is showing is one.
     */
    internal var mainScanAuthoritativeFailure: MainScanRenderFailure?
        get() = mainScanVisit.authoritativeFailure
        set(value) {
            mainScanVisit.authoritativeFailure = value
        }

    /** Exact inserted document retained independently of the Activity-local viewer field. */
    internal var mainScanCompletedDocument: DocumentEntity?
        get() = mainScanVisit.completedDocument
        set(value) {
            mainScanVisit.completedDocument = value
        }

    /**
     * The job advancing the current stage, owned by the RETAINED visit rather than by this activity.
     *
     * On `lifecycleScope` a recreation cancelled it mid-crop or mid-enhance while the stage it was
     * advancing survived nowhere, so the two could not disagree. Now the stage survives — and a
     * retained `Cropping` with a cancelled coroutine behind it would be a progress overlay that
     * never resolves. The job crosses the boundary with the stage it belongs to.
     *
     * The existing single-flight discipline is unchanged: every start still cancels its predecessor,
     * and [clearMainScanPipeline] still cancels whatever is running when the visit ends.
     */
    private var mainScanProcessingJob: Job?
        get() = mainScanVisit.processingJob
        set(value) {
            mainScanVisit.processingJob = value
        }

    /** App-private directory for the authoritative cropped sibling. */
    internal val mainScanCroppedDirectory: File
        get() = File(filesDir, MainScanAuthoritativeRender.CROPPED_DIRECTORY_NAME)

    /** App-private directory for the authoritative enhanced sibling. */
    internal val mainScanEnhancedDirectory: File
        get() = File(filesDir, MainScanAuthoritativeRender.ENHANCED_DIRECTORY_NAME)

    /** Durable document-owned Main Scanner pages. This directory is never part of a visit sweep. */
    internal val mainScanSavedDirectory: File
        get() = File(filesDir, MainScanSavedArtifactStore.DIRECTORY_NAME)

    private val mainScanSavedArtifactStore by lazy {
        MainScanSavedArtifactStore(mainScanSavedDirectory)
    }

    private val mainScanSaveCoordinator by lazy {
        val repository = (application as DocScannerPdfApplication).repository
        MainScanSaveCoordinator(
            promoteArtifact = mainScanSavedArtifactStore::promote,
            deletePromotedArtifact = mainScanSavedArtifactStore::delete,
            persistDocument = repository::saveDocument
        )
    }

    /**
     * Prepares the crop stage for a freshly accepted page: decode EXIF-upright off the main thread,
     * then resolve the initial polygon through the proven-seed / still-detection / full-frame
     * priority. The surface shows the retained capture throughout — never a blank frame.
     */
    internal fun prepareMainScanCrop(pageUri: Uri, seed: MainScanCropSeed?) {
        // The crop surface asks for this from a Compose effect keyed on the pending page, and a
        // REMOUNT replays that effect with the same page: an Activity recreation, an app-lock
        // unlock, any re-entry of the composition. The visit is retained now, so a replay would be
        // asking to redo work that has already been done — cancelling the live pipeline, forcing the
        // stage back to CropPreparing, and re-resolving the polygon over the corners the user had
        // dragged. The guard is the FIRST thing here, before the stage write and before the cancel,
        // because both of those are the damage.
        if (!MainScanWorkflow.allowsCropPreparation(mainScanStage)) {
            if (BuildConfig.DEBUG) {
                Log.d(MAIN_SCAN_TAG, "MAIN_SCAN_PREPARE refused stage=$mainScanStage reason=remount")
            }
            return
        }
        mainScanStage = MainScanStage.CropPreparing
        mainScanProcessingJob?.cancel()
        mainScanProcessingJob = mainScanVisit.processingScope.launch {
            // The application context, not this activity: the job outlives a recreation now, and
            // holding the destroyed instance across it would leak the whole view hierarchy. Nothing
            // the loader does is activity-scoped.
            val working = MainScanCaptureImageLoader.load(applicationContext, pageUri)
            if (working == null) {
                MainScanTrace.processingFailed(stage = "decode")
                mainScanStage = MainScanStage.Failed
                viewModel.showError("Couldn't open that capture. Please try again.")
                return@launch
            }
            mainScanWorkingImage = working

            // Detection on the still is only run when the live seed cannot be proven to map onto
            // this image, so the common path costs nothing extra.
            val needsDetection = MainScanPolygonResolver.needsStillDetection(
                seed = seed,
                captureWidth = working.width,
                captureHeight = working.height
            )
            val stillQuad = if (needsDetection) {
                MainScanCaptureProcessor.detectOnCapture(working.bitmap)
            } else {
                null
            }
            val resolved = MainScanPolygonResolver.resolve(
                seed = seed,
                captureWidth = working.width,
                captureHeight = working.height,
                stillDetection = stillQuad
            )
            mainScanCropState = MainScanCropEditor.initial(resolved.quad, resolved.source)
            mainScanStage = MainScanStage.CropEditing
            MainScanTrace.cropPrepared(
                polygonSource = resolved.source.name,
                imageWidth = working.width,
                imageHeight = working.height
            )
        }
    }

    /** Live polygon updates from the editor's drag gestures. */
    internal fun onMainScanCropStateChange(next: MainScanCropState) {
        if (!MainScanWorkflow.allowsPolygonEditing(mainScanStage)) return
        mainScanCropState = next
    }

    /**
     * Left / Right. Rotates the working IMAGE and the polygon together — turning only one would
     * leave the user dragging corners that no longer sit on the document.
     */
    internal fun rotateMainScanCrop(direction: MainScanRotation) {
        if (!MainScanWorkflow.allowsPolygonEditing(mainScanStage)) return
        val working = mainScanWorkingImage ?: return
        val state = mainScanCropState ?: return
        mainScanProcessingJob?.cancel()
        mainScanProcessingJob = mainScanVisit.processingScope.launch {
            val rotated = MainScanCaptureProcessor.rotate(
                bitmap = working.bitmap,
                clockwise = direction == MainScanRotation.RIGHT
            ) ?: return@launch
            mainScanWorkingImage = MainScanWorkingImage(rotated, rotated.width, rotated.height)
            mainScanCropState = MainScanCropEditor.rotate(state, direction)
            // The previous bitmap is deliberately NOT recycled here. Assigning the state only
            // SCHEDULES recomposition: the display list recorded for the last frame still references
            // the old bitmap and the render thread may replay it, so recycling now can surface as
            // "Canvas: trying to use a recycled bitmap" — a crash that `runCatching` cannot catch,
            // because `recycle()` itself never throws. Dropping the reference lets the pixels be
            // reclaimed once Compose has stopped drawing them, which matches how the rest of the
            // pipeline releases its bitmaps (see clearMainScanPipeline).
        }
    }

    /** All — the polygon returns to the complete image bounds. */
    internal fun resetMainScanCropToFullFrame() {
        if (!MainScanWorkflow.allowsPolygonEditing(mainScanStage)) return
        mainScanCropState = mainScanCropState?.let(MainScanCropEditor::resetToFullFrame)
    }

    /**
     * Next — validates the polygon, then runs a genuine perspective correction followed by
     * enhancement, both off the main thread. The page stays on screen behind each overlay.
     */
    internal fun advanceMainScanCrop() {
        val state = mainScanCropState ?: return
        val working = mainScanWorkingImage ?: return
        // The ORIGINAL capture, not the working copy: the authoritative render decodes it again at
        // full resolution, so without it there is nothing to be authoritative about.
        val pageUri = mainScanState.pendingPage?.uri?.toUri() ?: return
        if (!MainScanWorkflow.allowsAdvanceFromCrop(
                stage = mainScanStage,
                polygonValid = MainScanCropEditor.isApplicable(state)
            )
        ) {
            return
        }
        mainScanStage = MainScanStage.Cropping
        mainScanProcessingJob?.cancel()
        mainScanProcessingJob = mainScanVisit.processingScope.launch {
            val cropped = MainScanCaptureProcessor.perspectiveCrop(working.bitmap, state.quad)
            if (cropped == null) {
                MainScanTrace.processingFailed(stage = "perspective_crop")
                // The capture is untouched; the user returns to editing and can adjust.
                mainScanStage = MainScanStage.CropEditing
                viewModel.showError("Couldn't apply that crop. Please adjust and try again.")
                return@launch
            }
            mainScanCroppedImage = cropped
            mainScanStage = MainScanStage.EnhancementPreparing

            val enhanced = MainScanCaptureProcessor.applyFilter(cropped, DocumentFilter.ENHANCE)
            if (enhanced == null) {
                MainScanTrace.processingFailed(stage = "enhance")
                // Enhancement is an improvement, not a requirement: fall back to the true cropped
                // page rather than stranding the user, and say nothing misleading about it.
                mainScanEnhancedImage = null
            } else {
                mainScanEnhancedImage = enhanced
            }

            // The previews above are what the user LOOKS at. The artifact below is what a future
            // Confirm may actually write, and it is produced from the original capture at full
            // resolution — never from the pixels on screen.
            renderMainScanAuthoritative(
                pageUri = pageUri,
                cropState = state,
                editorFrame = working
            )

            mainScanStage = MainScanStage.EnhancementReview
            MainScanTrace.reviewReached(enhanced = enhanced != null)
        }
    }

    /**
     * Produces and publishes the authoritative artifact for [cropState], as a transaction.
     *
     * ## Order, and what each step is protecting against
     *
     * 1. Both target paths enter the visit's ownership ledger BEFORE the first byte is written. A
     *    file created outside the ledger is a file no sweep can find, and the window it would be
     *    unowned in is exactly the window a failure lands in.
     * 2. The render either returns a fully validated artifact or a reason. There is no third result,
     *    so nothing partially written can be mistaken for a success. That includes the frame check:
     *    a reproduced frame that does not match [editorFrame] fails closed before anything is
     *    written, rather than cropping a page the user never indicated.
     * 3. Publication is a single assignment, and it happens BEFORE the superseded files are deleted.
     *    Deleting first would mean a failure between the two left the pipeline with an artifact
     *    field pointing at files that no longer exist.
     * 4. A failed replacement changes nothing: the previously published artifact stays exactly as it
     *    was, and only the candidate's own files are swept.
     */
    private suspend fun renderMainScanAuthoritative(
        pageUri: Uri,
        cropState: MainScanCropState,
        editorFrame: MainScanWorkingImage
    ) {
        val previous = mainScanAuthoritative
        val croppedTarget = File(
            mainScanCroppedDirectory,
            MainScanAuthoritativeRender.croppedFileName()
        )
        val enhancedTarget = File(
            mainScanEnhancedDirectory,
            MainScanAuthoritativeRender.enhancedFileName()
        )
        val croppedUri = Uri.fromFile(croppedTarget).toString()
        val enhancedUri = Uri.fromFile(enhancedTarget).toString()
        // Step 1 — owned before written.
        mainScanState = MainScanCaptureFlow.withOwnedUri(
            MainScanCaptureFlow.withOwnedUri(mainScanState, croppedUri),
            enhancedUri
        )

        val outcome = MainScanCaptureProcessor.renderAuthoritative(
            // Application context: this runs inside the retained processing job, which may still be
            // advancing after the activity that started it was recreated. Nothing it reads is
            // activity-scoped, and `filesDir` resolves identically.
            context = applicationContext,
            sourceUri = pageUri,
            editorQuad = cropState.quad,
            rotationQuarterTurns = cropState.rotationQuarterTurns,
            // The frame the polygon was confirmed against, not a re-derivation of it. The render
            // reads EXIF independently, so this is the only value that can prove the frame it
            // rebuilds is the frame the user was looking at.
            editorFrameWidth = editorFrame.width,
            editorFrameHeight = editorFrame.height,
            croppedTarget = croppedTarget,
            enhancedTarget = enhancedTarget,
            filter = DocumentFilter.ENHANCE,
            retainedBitmapBytes = retainedMainScanPreviewBytes()
        )

        when (outcome) {
            is MainScanRenderOutcome.Authoritative -> {
                // Step 3 — publish, THEN retire what it replaced.
                mainScanAuthoritative = outcome.artifact
                mainScanAuthoritativeFailure = null
                previous?.let { sweepMainScanAuthoritativeFiles(it) }
            }

            is MainScanRenderOutcome.NonAuthoritative -> {
                // Step 4 — the existing artifact, if any, survives untouched. Only the candidate's
                // own paths are cleaned, and they are cleaned through the owned-file contract.
                MainScanTrace.processingFailed(
                    stage = "authoritative_${outcome.reason.name.lowercase()}"
                )
                mainScanAuthoritativeFailure = outcome.reason
                sweepMainScanOwnedUris(setOf(croppedUri, enhancedUri))
            }
        }
    }

    /**
     * The bytes the preview bitmaps hold and will NOT give back during an authoritative render — the
     * review keeps showing them. Real `allocationByteCount` values, not an estimate from dimensions,
     * so the reserve reflects what is actually committed.
     */
    private fun retainedMainScanPreviewBytes(): Long {
        val retained = listOfNotNull(
            mainScanWorkingImage?.bitmap,
            mainScanCroppedImage,
            mainScanEnhancedImage
        )
        return retained.sumOf { bitmap ->
            runCatching { bitmap.allocationByteCount.toLong() }.getOrDefault(0L)
        }
    }

    /**
     * Back from the enhancement review returns to crop editing without losing the polygon.
     *
     * Authority is dropped FIRST and synchronously. The artifact was made from the polygon the user
     * is about to change, so from the instant Back is pressed it describes a crop that is no longer
     * confirmed — and a suspension point before the clear would leave a window in which a stale
     * high-resolution page was still the thing a Confirm would write. The files it referenced are
     * only swept afterwards, through the owned-file contract.
     */
    internal fun backFromMainScanReview() {
        if (mainScanStage != MainScanStage.EnhancementReview) return
        val stale = mainScanAuthoritative
        mainScanAuthoritative = null
        mainScanAuthoritativeFailure = null
        releaseMainScanDerivedImages()
        mainScanStage = MainScanStage.CropEditing
        stale?.let { sweepMainScanAuthoritativeFiles(it) }
    }

    /** Confirm admission changes retained state before the save coroutine can suspend. */
    internal fun confirmMainScan() {
        val artifact = mainScanAuthoritative ?: return
        if (!MainScanWorkflow.allowsConfirm(mainScanStage) ||
            !MainScanWorkflow.canTransition(mainScanStage, MainScanStage.Confirming)
        ) return
        mainScanStage = MainScanStage.Confirming
        val timestamp = System.currentTimeMillis()
        mainScanProcessingJob = mainScanVisit.processingScope.launch {
            persistMainScanArtifact(artifact, timestamp)
        }
    }

    private suspend fun persistMainScanArtifact(
        artifact: MainScanAuthoritativeArtifact,
        timestamp: Long
    ) {
        // This retained-scope call intentionally keeps the Activity that launched it until the
        // bounded save settles. Completion data is published to MainScanVisitStore first, so a
        // recreated Activity recovers it; the old Activity's viewer write is only the no-recreation
        // fast path and cannot replace the retained handoff.
        val outcome = mainScanSaveCoordinator.confirm(
            artifact = artifact,
            title = mainScanDefaultTitle(timestamp),
            timestamp = timestamp,
            onPersisting = { transitionMainScanStage(MainScanStage.Persisting) },
            onCompleted = ::retainMainScanCompletion
        )
        handleMainScanSaveOutcome(outcome)
    }

    private fun mainScanDefaultTitle(timestamp: Long): String =
        DEFAULT_SCAN_TITLE_PREFIX + " " +
            SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.getDefault()).format(Date(timestamp))

    private fun transitionMainScanStage(target: MainScanStage) {
        check(MainScanWorkflow.canTransition(mainScanStage, target))
        mainScanStage = target
    }

    private fun handleMainScanSaveOutcome(outcome: MainScanSaveCoordinator.Outcome) {
        when (outcome) {
            MainScanSaveCoordinator.Outcome.AlreadySaving -> Unit
            is MainScanSaveCoordinator.Outcome.Aborted -> {
                transitionMainScanStage(MainScanStage.EnhancementReview)
                viewModel.showError(outcome.message)
                outcome.failure?.let { failure ->
                    Log.w(TAG, "Main Scanner save aborted before insertion.", failure)
                }
            }
            is MainScanSaveCoordinator.Outcome.Completed ->
                completeMainScanSuccessfulVisit(outcome.document)
            is MainScanSaveCoordinator.Outcome.CompletedWithCallbackFailure -> {
                retainMainScanCompletion(outcome.document)
                completeMainScanSuccessfulVisit(outcome.document)
                Log.w(TAG, "Main Scanner completion handoff failed.", outcome.failure)
            }
        }
    }

    /** Exact post-insert identity is retained before transient routing is removed. */
    private fun retainMainScanCompletion(document: DocumentEntity) {
        mainScanCompletedDocument = document
        if (mainScanStage != MainScanStage.Completed) {
            transitionMainScanStage(MainScanStage.Completed)
        }
    }

    /** The current Activity instance presents completion owned by the retained visit. */
    internal fun presentRetainedMainScanCompletion(document: DocumentEntity) {
        if (mainScanCompletedDocument?.id == document.id) {
            pdfViewerDocument = document
        }
    }

    /**
     * Success-only teardown does not cancel the coroutine executing it and keeps Completed terminal.
     */
    private fun completeMainScanSuccessfulVisit(document: DocumentEntity) {
        retainMainScanCompletion(document)
        // Populate the current host before removing the route that owns this surface. This prevents
        // one Dashboard frame; the retained completion remains authoritative for recreation.
        pdfViewerDocument = document
        val transientVisit = mainScanState
        showMainScanCapture = false
        mainScanState = MainScanCaptureFlow.beginVisit(transientVisit)
        mainScanEnhancedImage = null
        mainScanCroppedImage = null
        mainScanWorkingImage = null
        mainScanCropState = null
        mainScanAuthoritative = null
        mainScanAuthoritativeFailure = null
        // main_scan_saved was never admitted to this transient ledger.
        sweepMainScanSession(previousState = transientVisit, retainUris = emptySet())
    }

    /** Deletes a retired artifact's two siblings off the main thread, via the owned-file guard. */
    private fun sweepMainScanAuthoritativeFiles(artifact: MainScanAuthoritativeArtifact) {
        sweepMainScanOwnedUris(setOf(artifact.croppedUri, artifact.enhancedUri))
    }

    /**
     * Deletes [uris] off the main thread through the same two-barrier guard every other Main Scanner
     * delete uses. The entries stay in the visit ledger: a second delete of a path already gone is a
     * no-op, and dropping them would be the one way a file could end up referenced by nothing.
     */
    private fun sweepMainScanOwnedUris(uris: Set<String>) {
        if (uris.isEmpty()) return
        val filesDirPath = filesDir.absolutePath
        // The RETAINED scope, because the callers are retained: this is reached from inside the
        // processing job, which now outlives the activity that started it. Bound to the activity's
        // own scope, a recreation landing between the render and its cleanup cancelled the delete, and
        // the superseded artifact's two full-resolution siblings stayed on disk. The delete itself
        // does not move — it still goes through this activity's two-barrier guard below, with the
        // private-directory path resolved on the main thread before the launch.
        mainScanVisit.processingScope.launch {
            withContext(Dispatchers.IO) {
                uris.forEach { uriString -> deleteMainScanFileBlocking(uriString, filesDirPath) }
            }
        }
    }

    /** Releases the derived bitmaps. The captured original and its working copy are kept. */
    private fun releaseMainScanDerivedImages() {
        mainScanEnhancedImage = null
        mainScanCroppedImage = null
    }

    /**
     * Tears the crop/processing pipeline down for a finished or discarded visit. Only in-memory
     * bitmaps are dropped — the accepted capture on disk is handled by the capture session's own
     * ownership ledger.
     *
     * Called from BOTH visit-ending paths ([confirmMainScanDiscard] and [closeMainScanCapture]).
     * Without that, a discarded visit left its full-resolution working, cropped and enhanced bitmaps
     * — and its polygon — reachable from the activity, so the next visit started holding the
     * previous page's pixels in memory and could compose a stale crop state before its own decode
     * finished.
     */
    private fun clearMainScanPipeline() {
        mainScanProcessingJob?.cancel()
        mainScanProcessingJob = null
        mainScanEnhancedImage = null
        mainScanCroppedImage = null
        mainScanWorkingImage = null
        mainScanCropState = null
        // Authority dies with the visit, and it dies HERE — synchronously, before either caller
        // reaches its file sweep. The artifact's two siblings are in the visit ledger, so the sweep
        // that both callers already run reclaims them; this teardown stays free of I/O so it cannot
        // race the ledger it does not own.
        mainScanAuthoritative = null
        mainScanAuthoritativeFailure = null
        mainScanStage = MainScanStage.CameraReady
    }

    /**
     * The SINGLE Back decision for the Main Scanner, used by both the on-screen close affordance
     * and system/predictive Back so the two can never diverge: confirm when something would be
     * lost, otherwise exit directly.
     */
    internal fun onMainScanBack() {
        if (mainScanStage.blocksMainScanExit()) return
        if (MainScanCaptureFlow.backNeedsConfirmation(mainScanState)) {
            requestMainScanDiscard()
        } else {
            closeMainScanCapture()
        }
    }

    internal fun requestMainScanDiscard() {
        if (mainScanStage.blocksMainScanExit()) return
        mainScanState = MainScanCaptureFlow.requestDiscard(mainScanState)
    }

    internal fun cancelMainScanDiscard() {
        mainScanState = MainScanCaptureFlow.cancelDiscard(mainScanState)
    }

    /**
     * Confirmed discard: the visit ends on a new id and every app-owned temp file it produced is
     * deleted off the main thread. Nothing was persisted, so there is nothing to retain.
     *
     * Where it lands matches the locked reference: discarding a CAPTURED page returns to the camera
     * so the user can immediately reshoot, while discarding from the camera itself (nothing captured)
     * leaves the scanner. The camera is re-entered on a fresh visit, so the returning surface mounts
     * a new controller with a clean session rather than reusing the discarded one.
     */
    internal fun confirmMainScanDiscard() {
        if (mainScanStage.blocksMainScanExit()) return
        val abandoned = mainScanState
        val hadCapturedPage = abandoned.pendingPage != null
        mainScanState = MainScanCaptureFlow.confirmDiscard(abandoned)
        showMainScanCapture = hadCapturedPage
        // Discarding destroys the page, so nothing derived from it may survive: the returning camera
        // must not hold the discarded visit's bitmaps or reopen on its polygon.
        clearMainScanPipeline()
        sweepMainScanSession(previousState = abandoned, retainUris = emptySet())
        if (BuildConfig.DEBUG) {
            val destination = if (hadCapturedPage) "MAIN_SCAN_CAPTURE" else "DASHBOARD"
            Log.d(MAIN_SCAN_TAG, "MAIN_SCAN_DISCARD confirmed=true to=$destination")
        }
    }

    /**
     * Ends a visit's file ownership: snapshots what it owned on the main thread and deletes every
     * orphan on [Dispatchers.IO]. [retainUris] always survives. A no-op when the visit owned
     * nothing, so the ordinary "scanner never opened" path costs nothing.
     */
    private fun sweepMainScanSession(previousState: MainScanCaptureState?, retainUris: Set<String>) {
        val orphans = MainScanFileOwnership.visitOrphans(previousState, retainUris)
        if (orphans.isEmpty()) return
        val filesDirPath = filesDir.absolutePath
        // Retained for the same reason, and one more: this runs at the exact moment the ledger
        // naming these files is being replaced by a new visit's. A recreation cancelling it would
        // strand files that nothing in memory could name again, which is precisely the leak this
        // slice exists to close. Ownership and the guard stay here; only the scope is durable.
        mainScanVisit.processingScope.launch {
            withContext(Dispatchers.IO) {
                orphans.forEach { uriString -> deleteMainScanFileBlocking(uriString, filesDirPath) }
            }
        }
    }

    /** Deletes one app-owned Main Scanner temp file off the main thread. */
    private suspend fun deleteMainScanOwnedFile(uriString: String?) {
        val filesDirPath = filesDir.absolutePath
        withContext(Dispatchers.IO) { deleteMainScanFileBlocking(uriString, filesDirPath) }
    }

    /**
     * Blocking single-file delete — MUST run on an IO dispatcher. Two INDEPENDENT barriers must
     * both admit the path: the pure string check in [MainScanFileOwnership.isOwnedFileUri] (scheme,
     * private-directory containment, no `..` traversal) and the parsed-path containment check
     * below. A user's gallery original — always a `content://` URI this flow never owns — is
     * rejected by both, so external content URIs can never be deleted.
     */
    private fun deleteMainScanFileBlocking(uriString: String?, filesDirPath: String) {
        if (!MainScanFileOwnership.isOwnedFileUri(uriString, filesDirPath)) return
        // `toUri()` is the KTX inline wrapper around Uri.parse — identical behaviour, and the form
        // lint prefers, so this new code adds no warning. (The passport twin below predates it.)
        val uri = uriString?.let { runCatching { it.toUri() }.getOrNull() } ?: return
        if (uri.scheme != "file") return
        runCatching {
            val path = uri.path ?: return@runCatching
            val file = File(path)
            if (file.absolutePath.startsWith(filesDirPath)) file.delete()
        }
    }

    /** The dedicated single-page passport review (never the generic Document Ready screen). */
    internal var passportReview by mutableStateOf<PassportReviewState?>(null)

    /**
     * The in-memory preview cache for the current passport review visit — owns the downscaled
     * base bitmap, the current preview bitmap, and the two monotonic generation tokens used
     * to drop stale completions. Reset on every [beginPassportReview] and [passportCropApply]
     * (which installs a new base). The [PassportPreviewBus] is the publisher the Compose layer
     * collects.
     */
    private val passportPreviewSession = PassportReviewSession()
    private val passportPreviewBus = PassportPreviewBus()
    /** Job running the in-memory preview render for the LATEST user action. */
    private var passportPreviewJob: Job? = null
    /** Job decoding the LATEST settled authoritative frame — cancelled when the review ends. */
    private var passportFinalFrameJob: Job? = null

    /** The frame stream the passport review screen draws — instant previews and settled finals. */
    internal val passportPreviewFrames: StateFlow<PassportPreviewFrame?>
        get() = passportPreviewBus.frame

    /**
     * Per-filter thumbnails for the filter panel's 4-column grid, all generated in memory from
     * ONE cached small thumbnail of the current passport base page (never a JPEG write, never a
     * per-filter full-resolution decode). Regenerated when the base changes (review open, crop
     * settle) and cleared when the review ends.
     */
    internal var passportFilterThumbnails by mutableStateOf<Map<DocumentFilter, Bitmap>>(emptyMap())
    private var passportThumbnailJob: Job? = null

    /**
     * The normalized crop rectangle whose authoritative full-resolution render is still in
     * flight. While set, every in-memory preview composes this crop first (against the OLD
     * downscaled base) so the review shows the cropped page instantly; cleared when the settled
     * cropped base is installed (or the crop fails).
     */
    private var passportPendingPreviewCrop: PassportCropRect? = null

    /** Start time of the latest editing operation — for the debug-only timing logs. */
    private var passportLastOpStartedAt = 0L

    /**
     * Publishes an INSTANT in-memory preview for the current requested effect chain: crop (when
     * one is pending), rotation, filter, watermark — composed IN THAT ORDER from the cached
     * downscaled base on [Dispatchers.Default], never from a JPEG write. Latest generation wins:
     * a superseded compose is cancelled/dropped and its bitmap simply released to the GC (never
     * recycled while Compose may still draw it).
     */
    private fun startPassportPreviewRender(operation: PassportPreviewOperation) {
        val state = passportReview ?: return
        val chain = PassportEffectChain(
            crop = passportPendingPreviewCrop ?: PassportCropRect.FULL,
            rotationQuarterTurns = PassportEffectChain.quarterTurns(state.requestedRotationDegrees),
            filter = state.selectedFilter,
            watermarkText = state.watermarkText
        )
        passportPreviewSession.updateRequestedChain(chain)
        val generation = passportPreviewSession.beginPreview()
        PassportTimingLog.started(operation, generation)
        val startedAt = android.os.SystemClock.elapsedRealtime()
        passportLastOpStartedAt = startedAt
        passportPreviewJob?.cancel()
        passportPreviewJob = lifecycleScope.launch {
            try {
                val base = ensurePassportPreviewBase()
                if (base == null) {
                    PassportTimingLog.failed(
                        operation, generation, PassportFailureStage.PREVIEW,
                        android.os.SystemClock.elapsedRealtime() - startedAt
                    )
                    return@launch
                }
                val preview = PassportPreviewRenderer.compose(base, chain)
                // A newer operation superseded this preview — never publish it. Ownership of the
                // orphaned bitmap passes to the GC.
                if (!passportPreviewSession.isPreviewCurrent(generation)) return@launch
                passportPreviewSession.storePreview(preview)
                passportPreviewBus.publishPreview(passportPreviewSession, generation, operation, preview)
                PassportTimingLog.previewReady(
                    operation, generation,
                    android.os.SystemClock.elapsedRealtime() - startedAt,
                    preview.width, preview.height
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                PassportTimingLog.failed(
                    operation, generation, PassportFailureStage.PREVIEW,
                    android.os.SystemClock.elapsedRealtime() - startedAt
                )
            }
        }
    }

    /**
     * Returns the cached downscaled base for the current base generation, decoding it from the
     * authoritative base URI EXACTLY ONCE per generation (bounds-first, OOM-safe, on
     * [Dispatchers.IO]). Returns null if the base changed mid-decode (a crop settled) or the
     * decode failed.
     */
    private suspend fun ensurePassportPreviewBase(): Bitmap? {
        passportPreviewSession.downscaledBase?.let { return it }
        val baseUri = passportReview?.baseUri ?: return null
        val baseGeneration = passportPreviewSession.baseGeneration
        val decoded = PassportPreviewDecoder.decodeDownscaled(this, Uri.parse(baseUri)) ?: return null
        if (passportPreviewSession.baseGeneration != baseGeneration) return null
        passportPreviewSession.storeDownscaledBase(decoded)
        return decoded
    }

    /**
     * Once ALL authoritative work has settled (no filter/rotation/watermark render in flight, no
     * crop pending), decodes the settled [PassportReviewState.displayedUri] — the EXACT file
     * Confirm will save — and atomically replaces the in-memory preview with those final pixels.
     * Gated on the REVIEW SESSION plus both the final generation token and the preview generation,
     * so a stale final can never cover a newer instant preview — and a decode still in flight when
     * the user leaves can never publish the previous visit's page into a brand-new review.
     */
    private fun maybePublishPassportFinalFrame(operation: PassportPreviewOperation) {
        val state = passportReview ?: return
        if (state.workInFlight || state.saveInProgress) return
        val displayedUri = state.displayedUri
        val generation = passportPreviewSession.beginFinal()
        val previewGenerationAtStart = passportPreviewSession.previewGeneration
        val startedAt = passportLastOpStartedAt
        // The visit this decode belongs to: a cancel / new review / success bumps it, and the
        // generation tokens alone would not (they are not reset per visit).
        val session = passportReviewSession
        passportFinalFrameJob?.cancel()
        passportFinalFrameJob = lifecycleScope.launch {
            val bitmap = PassportPreviewDecoder.decodeDownscaled(this@MainActivity, Uri.parse(displayedUri))
            if (bitmap == null) {
                PassportTimingLog.failed(
                    operation, generation, PassportFailureStage.FINAL,
                    android.os.SystemClock.elapsedRealtime() - startedAt
                )
                return@launch
            }
            // The review visit these pixels were decoded for is over (or was replaced) — publishing
            // now would paint an abandoned session's page onto the current one.
            if (passportReviewSession != session || passportReview == null) return@launch
            if (!passportPreviewSession.isFinalCurrent(generation)) return@launch
            if (passportPreviewSession.previewGeneration != previewGenerationAtStart) return@launch
            passportPreviewBus.publishFinal(passportPreviewSession, generation, operation, bitmap)
            PassportTimingLog.finalReady(
                operation, generation,
                android.os.SystemClock.elapsedRealtime() - startedAt,
                bitmap.width, bitmap.height
            )
        }
    }

    /**
     * Rebuilds the filter-panel thumbnails from the CURRENT base page: one small thumbnail
     * decode shared by every filter, each effect applied in memory on [Dispatchers.Default].
     * No JPEG is written and the full-resolution source is never decoded per filter.
     */
    private fun regeneratePassportFilterThumbnails() {
        val baseUri = passportReview?.baseUri ?: return
        passportThumbnailJob?.cancel()
        passportThumbnailJob = lifecycleScope.launch {
            val source = PassportPreviewDecoder.decodeThumbnail(this@MainActivity, Uri.parse(baseUri))
                ?: return@launch
            val thumbnails = withContext(Dispatchers.Default) {
                PassportFilterGrid.ORDER.associateWith { filter ->
                    PassportPreviewRenderer.applyFilterInMemory(source, filter)
                }
            }
            passportFilterThumbnails = thumbnails
        }
    }

    /**
     * Tears down the in-memory preview pipeline when a review visit ends (cancel or successful
     * save): cancels preview/thumbnail work, clears the bus so the screen stops drawing, and
     * releases every cached bitmap to the GC. Bitmaps are never recycled here — Compose may
     * still reference the last frame during the exit transition.
     */
    private fun clearPassportPreviewPipeline() {
        passportPreviewJob?.cancel()
        passportPreviewJob = null
        passportFinalFrameJob?.cancel()
        passportFinalFrameJob = null
        passportThumbnailJob?.cancel()
        passportThumbnailJob = null
        passportPendingPreviewCrop = null
        passportPreviewBus.clear()
        passportPreviewSession.clear()
        passportFilterThumbnails = emptyMap()
    }

    private var passportFilterGeneration = 0L
    private var passportFilterJob: Job? = null

    /**
     * Stable identifier for the CURRENT passport review visit. It is bumped whenever a review is
     * opened ([beginPassportReview]) or torn down ([cancelPassportReview]/success), so a save
     * coroutine that started in one visit can detect — via [isPassportSaveCurrent] — that its
     * result now belongs to an abandoned session and refuse to mutate state or navigate. Combined
     * with [passportSaveGeneration] (bumped per Confirm) it makes Confirm/save an immutable
     * transaction: only the save that both the active session and the latest generation own may
     * complete.
     */
    private var passportReviewSession = 0L
    private var passportSaveGeneration = 0L
    private var passportSaveJob: Job? = null

    /**
     * Running ledger of every app-owned JPEG the CURRENT review visit has published into its state
     * — the canonical base, each settled filter/rotation/watermark render and each cropped base.
     * Superseded entries are deleted eagerly by [publishPassportReview]; the ledger is the backstop
     * that lets cancel/post-save cleanup delete anything that was dropped EARLIER in the visit and
     * is therefore no longer reachable from the final state. Only ever touched on the main thread.
     */
    private val passportOwnedUris = mutableSetOf<String>()

    /**
     * The exact URI a Confirm in progress froze as its final pixels. Never deletable while set —
     * ownership of that file is transferring to the saved document, so no superseding transition
     * and no session cleanup may remove it out from under the persist.
     */
    private var passportSaveFrozenUri: String? = null

    /**
     * Publishes [after] as the live passport review state and deletes the app-owned temp files
     * [before] referenced that [after] no longer does.
     *
     * This is the single place the review's file lifecycle is enforced, because the reducer
     * ([PassportReviewFlow]) is deliberately framework-free: transitions such as a rotation back to
     * 0° or the watermark invalidation every upstream change performs simply DROP a URI from the
     * state. Capturing [before] here — before the assignment — is what makes those drops
     * recoverable instead of orphaning a file.
     *
     * Deletion happens only AFTER the new state is live, never touches a URI [after] still
     * references, and never touches [passportSaveFrozenUri]. The actual unlink runs on
     * [Dispatchers.IO] behind [deletePassportOwnedFile], so no file I/O blocks the main thread, and
     * a failed delete leaves the user's valid document completely untouched.
     */
    private fun publishPassportReview(before: PassportReviewState?, after: PassportReviewState?) {
        passportReview = after
        passportOwnedUris += PassportFileOwnership.referencedUris(after)
        val superseded = PassportFileOwnership.supersededUris(
            before = before,
            after = after,
            protectedUris = setOfNotNull(passportSaveFrozenUri)
        )
        if (superseded.isEmpty()) return
        lifecycleScope.launch { superseded.forEach { deletePassportOwnedFile(it) } }
    }

    /**
     * [publishPassportReview] for a freshly rendered file: if the reducer REJECTED the render
     * (returning [before] unchanged because a newer selection has since won), [renderedUri] is
     * referenced by nothing and is deleted, so a stale job can never leave its own output behind.
     */
    private fun publishPassportRender(
        before: PassportReviewState,
        after: PassportReviewState,
        renderedUri: String
    ) {
        publishPassportReview(before, after)
        if (renderedUri in PassportFileOwnership.referencedUris(after)) return
        lifecycleScope.launch { deletePassportOwnedFile(renderedUri) }
    }

    /**
     * Called once the guided passport capture screen has its single baked page. Opens the
     * DEDICATED passport review — no generic Document Ready flags are set, so no backend
     * processing / E2E validation / To Word surface can appear in the passport path. The baked
     * page is already canonical upright portrait, so the review starts at rotation 0.
     */
    internal fun beginPassportReview(pageUri: Uri) {
        showPassportCapture = false
        val title = "Passport " +
            SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.getDefault()).format(Date())
        imageImportReview = null
        pendingImageImport = null
        imageEditorMessage = null
        scannerBackendProcessingState = ScannerBackendProcessingState.Idle
        scannerFlowValidationState = ScannerFlowValidationState()
        documentResultState = null
        importedImagePreview = null
        // A brand-new review visit: bump the session so any straggling save from a previous
        // visit can never mutate or navigate this one.
        passportReviewSession++
        passportSaveJob?.cancel()
        passportSaveJob = null
        passportRotationJob?.cancel()
        passportRotationJob = null
        // Fresh preview pipeline for this visit: new base generation, empty bus, no thumbnails.
        clearPassportPreviewPipeline()
        passportPreviewSession.installBase()
        passportPreviewSession.updateRequestedChain(PassportEffectChain())
        // A visit REPLACED without an explicit cancel (a second capture straight into review) must
        // not strand the previous visit's temp files: sweep its ledger before adopting a new one.
        // Anything a save froze is retained — that file now belongs to the persisted document.
        sweepPassportSession(previousState = passportReview, retainUris = setOfNotNull(passportSaveFrozenUri))
        // The previous visit's freeze (if any) has just been honoured by the sweep above; a new
        // visit starts with no save in flight.
        passportSaveFrozenUri = null
        passportReview = PassportReviewState(baseUri = pageUri.toString(), title = title)
        passportOwnedUris += pageUri.toString()
        // Warm the downscaled preview base and the filter-grid thumbnails in the background so
        // the FIRST editing tap already composes from memory within the instant-feedback budget.
        lifecycleScope.launch { ensurePassportPreviewBase() }
        regeneratePassportFilterThumbnails()
    }

    /**
     * Whether a save coroutine started for ([session], [generation]) still owns the passport
     * review: the same visit is active, no newer Confirm superseded it, and a review is still
     * open. A stale save (the user cancelled, opened a new review, or re-confirmed) fails this
     * check and must do nothing but temporary-file cleanup.
     */
    private fun isPassportSaveCurrent(session: Long, generation: Long): Boolean =
        passportReviewSession == session &&
            passportSaveGeneration == generation &&
            passportReview != null

    /**
     * Confirms the passport review: composes the EXACT pixels shown on screen (settled filter
     * render → baked rotation → watermark), then saves ONE document through the existing
     * repository so it appears in All Documents via the existing Room Flow. Single-flight, and
     * the document is created only after the final pixels exist — a failed save leaves the user
     * on the review with retry available and creates no record.
     */
    internal fun confirmPassportReview() {
        val current = passportReview ?: return
        // Single-flight: beginSave returns null if a save is already running, a render is in
        // flight, or a requested watermark is unresolved — so repeated Confirm is a no-op.
        val saving = PassportReviewFlow.beginSave(current) ?: return
        passportReview = saving
        passportFilterJob?.cancel()
        passportWatermarkJob?.cancel()
        // This save is owned by the current review session and this generation. Any later cancel,
        // new review, or re-confirm invalidates it via isPassportSaveCurrent().
        val session = passportReviewSession
        val generation = ++passportSaveGeneration
        // Save reuses the exact settled pixels the review is showing — canConfirm guaranteed
        // everything (filter/rotation/watermark) settled, so no re-render happens here. Freezing it
        // SYNCHRONOUSLY (before any suspension point) is what makes it undeletable for the whole
        // persist: every cleanup path treats passportSaveFrozenUri as protected.
        val finalUri = passportFinalUri(saving)
        passportSaveFrozenUri = finalUri.toString()
        logPassportRoute("PASSPORT_CONFIRM accepted")
        passportSaveJob?.cancel()
        passportSaveJob = lifecycleScope.launch {
            logPassportRoute("PASSPORT_SAVE started")
            // Defensive: if the user somehow left / re-confirmed, a stale result must not navigate.
            if (!isPassportSaveCurrent(session, generation)) {
                logPassportRoute("PASSPORT_SAVE dropped=stale_session")
                return@launch
            }
            viewModel.saveGeneratedPdfDocument(
                document = DocumentEntity(
                    title = saving.title,
                    timestamp = System.currentTimeMillis(),
                    pageCount = 1,
                    localPdfUri = finalUri.toString()
                ),
                onError = onError@{ message ->
                    // Restore retry state only for the still-active save session.
                    if (!isPassportSaveCurrent(session, generation)) return@onError
                    // Nothing was persisted, so the freeze lifts — but the final image stays
                    // referenced by the restored state, so it remains undeletable for the retry.
                    passportSaveFrozenUri = null
                    passportReview = PassportReviewFlow.saveFailed(saving)
                    logPassportRoute("PASSPORT_SAVE failed reason=persist_error")
                    logPassportRoute("PASSPORT_ROUTE retained=PASSPORT_REVIEW")
                    viewModel.showError(message)
                },
                onSaved = onSaved@{ savedDocument ->
                    // Navigate exactly once, and only if this session/generation still owns the
                    // review. A stale success (session abandoned) leaves the already-persisted
                    // document in the library but performs no navigation.
                    if (!isPassportSaveCurrent(session, generation)) {
                        logPassportRoute("PASSPORT_SAVE dropped=stale_session_post_persist")
                        return@onSaved
                    }
                    // Snapshot the ledger BEFORE completePassportSave() tears the review down, so
                    // files dropped earlier in the visit are still known to the sweep.
                    val owned = passportOwnedUris.toSet()
                    completePassportSave(savedDocument)
                    // Ownership of the final image has transferred to the saved document. Delete
                    // this session's raw/intermediate app-owned files off the main thread,
                    // preserving ONLY the persisted final artifact (finalUri = the document's
                    // localPdfUri, also the displayed URI). The freeze lifts only here, once that
                    // file is both persisted and explicitly retained by the sweep.
                    passportSaveFrozenUri = null
                    passportOwnedUris.clear()
                    lifecycleScope.launch {
                        cleanupPassportSessionFiles(
                            ownedUris = owned,
                            state = saving,
                            retainUris = setOf(finalUri.toString())
                        )
                    }
                }
            )
        }
    }

    /**
     * Post-save navigation for a passport — the DEDICATED clean destination, never the generic
     * Document Ready / backend-processing / E2E / To Word preview. Clears the transient review
     * state and opens the saved document in the clean viewer over the Documents list (or the
     * list itself when no valid id). Explicitly does NOT touch [importedImagePreview] or any
     * generic single-image/document-result flag.
     */
    private fun completePassportSave(savedDocument: DocumentEntity) {
        logPassportRoute("PASSPORT_SAVE completed documentId=present")
        // The save succeeded — release the preview cache and stop publishing frames.
        clearPassportPreviewPipeline()
        passportReview = null
        logPassportRoute("PASSPORT_REVIEW cleared=true")
        val destination = PassportCompletion.destinationFor(savedDocument.id)
        when (destination) {
            PassportCompletionDestination.SAVED_DOCUMENT -> {
                showDocumentLibrary = true
                pdfViewerDocument = savedDocument
            }
            PassportCompletionDestination.DOCUMENTS -> {
                showDocumentLibrary = true
            }
        }
        // Log the ACTUAL typed destination, never a hardcoded one.
        logPassportRoute("PASSPORT_ROUTE from=PASSPORT_REVIEW to=$destination")
        showPassportSavedMessage()
    }

    /**
     * Surfaces the "Passport saved" confirmation. The app's shared UI-message channel is the
     * ViewModel's snackbar/message state (also used by every other save path); this is a neutral
     * success confirmation, not an error, and carries no image or document content.
     */
    private fun showPassportSavedMessage() {
        viewModel.showUserMessage("Passport saved")
    }

    private fun logPassportRoute(message: String) {
        if (BuildConfig.DEBUG) Log.d("IdCardCapture", message)
    }

    /**
     * The passport's final saved pixels are EXACTLY the settled image the review is showing —
     * [PassportReviewState.displayedUri] (the watermark render, the settled rotation bake, or the
     * settled filtered page). Confirm is gated on [PassportReviewState.canConfirm], so by the time
     * this runs the filter, rotation and watermark are all settled and [displayedUri] is a
     * fully-baked page. Nothing is re-rendered here — no rotation is re-baked after Confirm — so
     * the saved pixels equal the displayed pixels by construction.
     */
    private fun passportFinalUri(state: PassportReviewState): Uri = Uri.parse(state.displayedUri)

    private suspend fun bakePassportRotation(source: Uri, degrees: Int): Uri? {
        if (degrees == 0) return source
        return try {
            IdScanPostProcessor.rotateAndSave(
                context = this@MainActivity,
                sourceUri = source,
                degrees = degrees,
                outputDirectory = File(filesDir, "passport_rotated")
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Log.w(TAG, "Passport rotation bake failed: ${throwable.message}")
            null
        }
    }

    private var passportRotationGeneration = 0L
    private var passportRotationJob: Job? = null

    /**
     * Bakes the requested rotation into a new image so the review can display it with
     * ContentScale.Fit fully contained at 90°/270° (a rotated Fit of the un-rotated page clips).
     * The PREVIOUS settled image stays on screen until this publishes. Runs only when a rotation is
     * pending, the filter has settled, and no watermark render is baking rotation itself.
     * Generation-token guarded; the superseded rotation temp is deleted once the new one lands.
     */
    private fun maybeRenderPassportRotation() {
        val state = passportReview ?: return
        // Wait for the filter to settle and skip when a watermark render handles rotation.
        if (!state.rotationNeedsRender || state.renderPending || state.saveInProgress) return
        val filteredUri = state.filteredUri
        val rotation = state.requestedRotationDegrees
        val generation = ++passportRotationGeneration
        passportRotationJob?.cancel()
        passportRotationJob = lifecycleScope.launch {
            val rendered = bakePassportRotation(Uri.parse(filteredUri), rotation)
            if (passportRotationGeneration != generation) {
                deletePassportOwnedFile(rendered?.toString())
                return@launch
            }
            val latest = passportReview ?: return@launch
            if (rendered == null) {
                passportReview = PassportReviewFlow.withRotationFailed(latest, rotation)
                return@launch
            }
            // Publish, then delete whatever the previous state referenced and this one no longer
            // does (the superseded bake) — or this render itself if the reducer rejected it.
            publishPassportRender(
                before = latest,
                after = PassportReviewFlow.withRotationRendered(
                    state = latest,
                    fromFilteredUri = filteredUri,
                    atRotation = rotation,
                    renderedUri = rendered.toString()
                ),
                renderedUri = rendered.toString()
            )
            // The authoritative rotation settled — atomically replace the instant preview with
            // the final pixels (skipped automatically while other work is still pending).
            maybePublishPassportFinalFrame(PassportPreviewOperation.ROTATE)
        }
    }

    private var passportWatermarkGeneration = 0L
    private var passportWatermarkJob: Job? = null

    /**
     * After any passport state change, renders the authoritative watermark image when one is
     * requested and its filter has settled — the SINGLE source of truth for both preview and
     * save. Generation-token + reducer-input guarded (a stale render can't overwrite newer
     * state), and the superseded temp file is deleted once a new one publishes.
     */
    private fun maybeRenderPassportWatermark() {
        val state = passportReview ?: return
        // Wait until the filter has settled — the watermark must sit on the final filtered page.
        if (!state.watermarkNeedsRender || state.renderPending) return
        val text = state.watermarkText ?: return
        val filteredUri = state.filteredUri
        val rotation = state.requestedRotationDegrees
        val generation = ++passportWatermarkGeneration
        passportWatermarkJob?.cancel()
        passportWatermarkJob = lifecycleScope.launch {
            val rendered = try {
                val rotated = bakePassportRotation(Uri.parse(filteredUri), rotation) ?: return@launch
                val result = PassportWatermarkRenderer.apply(
                    context = this@MainActivity,
                    sourceUri = rotated,
                    text = text,
                    outputDirectory = File(filesDir, "passport_watermarked"),
                    generation = generation
                )
                // The rotated intermediate (only created when rotation != 0, so distinct from the
                // filtered page) has been consumed by the watermark render — delete the orphan.
                if (rotated.toString() != filteredUri) deletePassportOwnedFile(rotated.toString())
                result
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Log.w(TAG, "Passport watermark render failed: ${throwable.message}")
                null
            }
            if (passportWatermarkGeneration != generation) {
                // A newer watermark/filter/rotation superseded this render — delete the orphan.
                deletePassportOwnedFile(rendered?.toString())
                return@launch
            }
            val latest = passportReview ?: return@launch
            if (rendered == null) {
                passportReview = PassportReviewFlow.withWatermarkFailed(latest, text)
                viewModel.showError("Unable to apply the watermark. Please try again.")
                return@launch
            }
            // Publish, then delete the now-superseded watermark temp off the main thread (never the
            // freshly published one, never a file the new state still references) — or this render
            // itself if the reducer rejected it as stale.
            publishPassportRender(
                before = latest,
                after = PassportReviewFlow.withWatermarkRendered(
                    state = latest,
                    forText = text,
                    fromFilteredUri = filteredUri,
                    atRotation = rotation,
                    renderedUri = rendered.toString()
                ),
                renderedUri = rendered.toString()
            )
            // The authoritative watermark settled — swap the instant preview for the exact
            // pixels Confirm will save.
            maybePublishPassportFinalFrame(PassportPreviewOperation.WATERMARK)
        }
    }

    // Interactive RECTANGULAR passport crop editor — its own normalized-rect state, entirely
    // separate from the ID-card perspective crop so neither can disturb the other.
    internal var passportCropRect by mutableStateOf<PassportCropRect?>(null)
    internal var passportCropSourceBitmap by mutableStateOf<android.graphics.Bitmap?>(null)
    internal var passportCropApplying by mutableStateOf(false)
    private var passportCropGeneration = 0L

    /**
     * The clockwise quarter turns baked into the image the crop editor is CURRENTLY showing,
     * snapshotted in the same statement that chose that image so the two can never drift apart.
     * The editor draws its rectangle in THAT frame; [passportCropApply] un-rotates by exactly this
     * many turns to get back to canonical base coordinates.
     */
    private var passportCropDisplayQuarterTurns = 0

    /**
     * Opens the rectangular crop editor on the image the review is CURRENTLY showing
     * ([PassportReviewState.displayedUri] — filter, baked rotation and watermark included), so the
     * rectangle the user drags always corresponds to the pixels in front of them. Crop TRUTH stays
     * canonical: the rectangle is un-rotated back into base coordinates on Apply and the
     * authoritative crop is still extracted from the full-resolution [PassportReviewState.baseUri],
     * so the filter and rotation are re-rendered from the cropped base rather than applied twice.
     * Starts full-frame.
     */
    internal fun openPassportCropEditor() {
        // While a previous crop is still settling into its new base, opening the editor would
        // show the OLD base — wait for the settle instead (the toolbar disables Crop meanwhile).
        if (passportReview?.cropRenderPending == true) return
        val state = passportReview
        val source = state?.displayedUri
        if (state == null || source.isNullOrBlank()) {
            viewModel.showError("No image is available to crop.")
            return
        }
        passportCropSourceBitmap = null
        passportCropApplying = false
        // Snapshot the displayed frame's rotation alongside the URI being loaded. Both are read
        // from the same state, and neither the base nor this snapshot can change while the editor
        // is open (Crop is entered from the review and blocks re-entry while a crop is pending), so
        // a rotation bake landing mid-edit cannot invalidate the mapping.
        passportCropDisplayQuarterTurns =
            PassportEffectChain.quarterTurns(state.displayedRotationDegrees)
        passportCropRect = PassportCropRect.FULL
        lifecycleScope.launch {
            val bitmap = cropImageProcessor.loadSource(source)
            if (bitmap == null) {
                passportCropRect = null
                viewModel.showError("Unable to load the image for cropping.")
                return@launch
            }
            // Only adopt if the editor is still open (the user may have cancelled while decoding).
            if (passportCropRect != null) passportCropSourceBitmap = bitmap
        }
    }

    /** Cancel returns to the review with the base, filter, rotation and watermark unchanged. */
    internal fun cancelPassportCropEditor() {
        passportCropRect = null
        passportCropSourceBitmap = null
        passportCropApplying = false
    }

    internal fun passportCropMoveHandle(handle: PassportCropHandle, nx: Float, ny: Float) {
        if (passportCropApplying) return
        passportCropRect = passportCropRect?.let { PassportCropReducer.moveHandle(it, handle, nx, ny) }
    }

    internal fun passportCropMoveBy(dnx: Float, dny: Float) {
        if (passportCropApplying) return
        passportCropRect = passportCropRect?.let { PassportCropReducer.moveBy(it, dnx, dny) }
    }

    internal fun passportCropReset() {
        if (passportCropApplying) return
        passportCropRect = passportCropRect?.let { PassportCropReducer.reset() }
    }

    /**
     * Applies the crop with INSTANT feedback: the review returns immediately showing an
     * in-memory preview of the cropped page (composed from the cached downscaled base, no JPEG
     * write, no spinner) while the authoritative FULL-resolution crop renders in the background
     * from the original base using the same normalized rectangle. Confirm stays disabled (via
     * [PassportReviewState.cropRenderPending]) until the settled cropped base is installed, the
     * preview cache is rebuilt from it, and the active filter/rotation/watermark re-render in
     * canonical order — so the downscaled preview crop can never be persisted. Generation-guarded
     * (a superseded/cancelled apply publishes nothing and deletes its orphan). A no-op crop
     * closes without generating a duplicate file.
     */
    internal fun passportCropApply() {
        val displayRect = passportCropRect ?: return
        val state = passportReview ?: return
        if (state.cropRenderPending) return
        if (!PassportCropReducer.isMeaningfulCrop(displayRect)) {
            // Unchanged crop → treat as a no-op; never write an identical file.
            cancelPassportCropEditor()
            return
        }
        // The rectangle was drawn on the DISPLAYED page, whose rotation is baked into its pixels.
        // Un-rotate it by the snapshotted quarter turns to address the canonical base — an exact
        // lossless permutation of the unit square, so the in-bounds and minimum-size invariants
        // survive it. Both the instant in-memory preview (which crops the downscaled base BEFORE
        // rotating) and the authoritative renderer consume base coordinates.
        val rect = PassportCropRotationMapping.toBaseRect(displayRect, passportCropDisplayQuarterTurns)
        val source = state.baseUri
        // Close the crop editor NOW and show the instant in-memory cropped preview in the review.
        passportCropRect = null
        passportCropSourceBitmap = null
        passportCropApplying = false
        passportPendingPreviewCrop = rect
        passportReview = PassportReviewFlow.beginCrop(state)
        startPassportPreviewRender(PassportPreviewOperation.CROP)
        val generation = ++passportCropGeneration
        lifecycleScope.launch {
            val croppedUri = PassportCropRenderer.crop(
                context = this@MainActivity,
                sourceUri = Uri.parse(source),
                crop = rect,
                outputDirectory = File(filesDir, "passport_cropped")
            )
            // A newer crop or a cancel superseded this attempt — publish nothing, delete the orphan.
            if (passportCropGeneration != generation) {
                deletePassportOwnedFile(croppedUri?.toString())
                return@launch
            }
            if (croppedUri == null) {
                // Revert the instant preview to the un-cropped page — the truthful settled state.
                passportPendingPreviewCrop = null
                passportReview = passportReview?.let { PassportReviewFlow.cropFailed(it) }
                startPassportPreviewRender(PassportPreviewOperation.CROP)
                viewModel.showError("Unable to apply crop.")
                return@launch
            }
            // Publish the cropped page as the new base; downstream selections re-render from it,
            // and the preview cache rebuilds from the NEW full-resolution base (decoded once).
            // Publishing through publishPassportRender deletes exactly what the new state stopped
            // referencing — the superseded base, the superseded filter render, and the watermark
            // render withCroppedBase invalidates. The previously settled ROTATION bake is retained
            // by the reducer for keep-last display and is cleaned when its re-bake publishes.
            val latest = passportReview ?: return@launch
            publishPassportRender(
                before = latest,
                after = PassportReviewFlow.withCroppedBase(latest, croppedUri.toString()),
                renderedUri = croppedUri.toString()
            )
            passportPendingPreviewCrop = null
            passportPreviewSession.installBase()
            regeneratePassportFilterThumbnails()
            renderPassportFilter()
            maybeRenderPassportRotation()
            maybeRenderPassportWatermark()
            maybePublishPassportFinalFrame(PassportPreviewOperation.CROP)
        }
    }

    /** Cancels the passport review, discarding the captured page and any in-flight renders. */
    internal fun cancelPassportReview() {
        val abandoned = passportReview
        passportFilterJob?.cancel()
        passportFilterJob = null
        passportRotationJob?.cancel()
        passportRotationJob = null
        passportWatermarkJob?.cancel()
        passportWatermarkJob = null
        passportSaveJob?.cancel()
        passportSaveJob = null
        // Tear down the in-memory preview pipeline: cancel preview jobs, clear the bus and the
        // cached bitmaps (GC-released, never recycled while Compose may still draw them).
        clearPassportPreviewPipeline()
        // Bump the session so any callback still in flight is treated as stale.
        passportReviewSession++
        passportReview = null
        // The state transition above is complete; delete this session's unsaved app-owned files
        // off the main thread (never external content URIs). A file frozen by a save that already
        // reached the repository is RETAINED — cancelling the review must never destroy a document
        // that was actually persisted.
        sweepPassportSession(previousState = abandoned, retainUris = setOfNotNull(passportSaveFrozenUri))
        passportSaveFrozenUri = null
    }

    /**
     * Ends the current review visit's file ownership: snapshots the ledger and [previousState] on
     * the main thread, clears the ledger, and deletes every orphan on [Dispatchers.IO]. [retainUris]
     * always survives (the save-frozen / persisted final image). A no-op when the visit owned
     * nothing, so the ordinary "no review open" path costs nothing.
     */
    private fun sweepPassportSession(previousState: PassportReviewState?, retainUris: Set<String>) {
        val owned = passportOwnedUris.toSet()
        passportOwnedUris.clear()
        if (owned.isEmpty() && previousState == null) return
        lifecycleScope.launch {
            cleanupPassportSessionFiles(ownedUris = owned, state = previousState, retainUris = retainUris)
        }
    }

    /**
     * Deletes the app-owned temporary files a passport review session produced but never
     * persisted: everything the visit's [ownedUris] ledger recorded (including files dropped by an
     * EARLIER transition and no longer reachable from [state]) plus whatever [state] still
     * references. All existence checks and deletes run on [Dispatchers.IO] — no synchronous file
     * I/O touches the UI thread. Only files under the app-private [filesDir] are touched; an
     * external gallery content URI (which the baker copies from but never owns) and [retainUris]
     * (the persisted final artifact on the success path) are left untouched. Callers complete
     * their state transition on the main thread BEFORE invoking this, so it never participates in
     * a half-applied transition, and a delete that fails is swallowed — the user's saved document
     * is never at risk from cleanup.
     */
    private suspend fun cleanupPassportSessionFiles(
        ownedUris: Set<String>,
        state: PassportReviewState?,
        retainUris: Set<String> = emptySet()
    ) {
        val candidates = PassportFileOwnership.sessionOrphans(
            ownedUris = ownedUris,
            state = state,
            retainUris = retainUris
        )
        if (candidates.isEmpty()) return
        val filesDirPath = filesDir.absolutePath
        withContext(Dispatchers.IO) {
            candidates.forEach { uriString -> deleteOwnedFileBlocking(uriString, filesDirPath) }
        }
    }

    /**
     * Deletes a single app-owned passport temp file (a `file://` URI under [filesDir]) on
     * [Dispatchers.IO] — used for stale async renders (generation mismatch) and superseded
     * outputs. External content URIs and files outside the app's private storage are never touched.
     */
    private suspend fun deletePassportOwnedFile(uriString: String?) {
        val filesDirPath = filesDir.absolutePath
        withContext(Dispatchers.IO) { deleteOwnedFileBlocking(uriString, filesDirPath) }
    }

    /**
     * Blocking single-file delete — MUST be called from an IO dispatcher. Only deletes a
     * `file://` path under [filesDirPath]; external/other URIs are ignored. Two INDEPENDENT
     * barriers must both admit the path: the pure string check in
     * [PassportFileOwnership.isOwnedFileUri] (scheme, private-directory containment, no `..`
     * traversal) and the parsed-path containment check below. A user's gallery original — always a
     * `content://` URI the baker copies FROM and never owns — is rejected by both.
     */
    private fun deleteOwnedFileBlocking(uriString: String?, filesDirPath: String) {
        if (!PassportFileOwnership.isOwnedFileUri(uriString, filesDirPath)) return
        val uri = uriString?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return
        if (uri.scheme != "file") return
        runCatching {
            val path = uri.path
            if (path != null) {
                val file = File(path)
                if (file.absolutePath.startsWith(filesDirPath)) file.delete()
            }
        }
    }

    /**
     * Rotates the reviewed passport page 90° clockwise. The cached preview bitmap rotates and
     * publishes IMMEDIATELY (no spinner, no display-rotation transform — the preview pixels are
     * rotated in memory, so the page stays fully contained at 90°/270°); the authoritative
     * full-resolution bake runs in the background and atomically replaces the preview when it
     * settles. Confirm stays blocked until then. Invalidates and re-renders the watermark.
     */
    internal fun rotatePassportReview() {
        val before = passportReview ?: return
        // A fourth tap returns to 0°, which settles instantly and DROPS the rotation bake; the
        // watermark render is invalidated on every tap. Publishing through the helper deletes both
        // of those superseded files instead of orphaning them.
        publishPassportReview(before, PassportReviewFlow.rotate(before))
        startPassportPreviewRender(PassportPreviewOperation.ROTATE)
        maybeRenderPassportRotation()
        maybeRenderPassportWatermark()
        // A rotation back to 0° settles instantly (no bake) — publish its final directly.
        maybePublishPassportFinalFrame(PassportPreviewOperation.ROTATE)
    }

    /**
     * Sets or clears the user's watermark text. The watermark is drawn onto the cached preview
     * bitmap IMMEDIATELY (same placement geometry as the authoritative renderer); the
     * authoritative render follows in the background and remains the exact save source.
     */
    internal fun setPassportWatermark(text: String?) {
        val before = passportReview ?: return
        // Setting new text or clearing the watermark drops the previous render — delete it once
        // the new state is live.
        publishPassportReview(before, PassportReviewFlow.withWatermark(before, text))
        startPassportPreviewRender(PassportPreviewOperation.WATERMARK)
        // Removing a watermark re-arms a standalone rotation bake; setting one hands rotation to
        // the watermark render.
        maybeRenderPassportRotation()
        maybeRenderPassportWatermark()
        // Removing the watermark with everything else settled needs no render — publish final.
        maybePublishPassportFinalFrame(PassportPreviewOperation.WATERMARK)
    }

    /**
     * Applies a filter to the passport page: the cached preview re-composes and publishes
     * IMMEDIATELY (no spinner), then the authoritative render runs non-destructively from the
     * retained canonical base with the same generation-token + reducer-input staleness
     * protection the ID-card review uses (an older render can never replace a newer selection).
     */
    internal fun applyPassportFilter(filter: DocumentFilter) {
        val current = passportReview ?: return
        val updated = PassportReviewFlow.applyFilter(current, filter)
        if (updated === current) return
        // Selecting ORIGINAL drops the settled filter render, and any change invalidates the
        // watermark render — both are deleted once the new state is live.
        publishPassportReview(current, updated)
        startPassportPreviewRender(PassportPreviewOperation.FILTER)
        renderPassportFilter()
        // Selecting ORIGINAL settles without a filter render — re-arm the rotation/watermark
        // renders it invalidated (renderPassportFilter early-returns for ORIGINAL), and publish
        // the final directly when nothing at all is pending.
        maybeRenderPassportRotation()
        maybeRenderPassportWatermark()
        maybePublishPassportFinalFrame(PassportPreviewOperation.FILTER)
    }

    private fun renderPassportFilter() {
        val state = passportReview ?: return
        val filter = state.selectedFilter
        val baseUri = state.baseUri
        val generation = ++passportFilterGeneration
        passportFilterJob?.cancel()
        if (filter == DocumentFilter.ORIGINAL) return

        passportFilterJob = lifecycleScope.launch {
            val rendered = try {
                DocumentFilterRenderer.render(
                    context = this@MainActivity,
                    sourceUri = Uri.parse(baseUri),
                    filter = filter,
                    outputDirectory = File(filesDir, "passport_filtered")
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Log.w(TAG, "Unable to render passport filter ${filter.name}: ${throwable.message}")
                null
            }
            if (passportFilterGeneration != generation) {
                // A newer selection superseded this render — delete the orphaned stale output.
                deletePassportOwnedFile(rendered?.toString())
                return@launch
            }
            val latest = passportReview ?: return@launch
            if (rendered == null) {
                // The fallback may revert filteredUri to the base, dropping the old render.
                publishPassportReview(latest, PassportReviewFlow.withRenderFailed(latest, filter, baseUri))
                // A watermark invalidated by the (failed) filter change would otherwise stay
                // pending forever with Confirm disabled. The fallback restored a truthful
                // filtered page, so re-arm the watermark render over it (or, if that render also
                // fails, withWatermarkFailed lands an explicit recoverable error state).
                maybeRenderPassportRotation()
                maybeRenderPassportWatermark()
                viewModel.showError("Unable to apply ${filter.displayName}. Tap it again to retry.")
                return@launch
            }
            // Publish, then delete what the new state stopped referencing — the replaced filter
            // render (never the canonical base, never the freshly published file) and the watermark
            // render this filter change invalidated — or this render itself if it was rejected.
            publishPassportRender(
                before = latest,
                after = PassportReviewFlow.withRenderedFilter(
                    state = latest,
                    filter = filter,
                    fromBaseUri = baseUri,
                    renderedUri = rendered.toString()
                ),
                renderedUri = rendered.toString()
            )
            // The filter has settled — now the rotation bake and watermark (if any) can render,
            // and when nothing further is pending the final pixels replace the instant preview.
            maybeRenderPassportRotation()
            maybeRenderPassportWatermark()
            maybePublishPassportFinalFrame(PassportPreviewOperation.FILTER)
        }
    }

    /**
     * Called once the guided ID-card capture screen has a front image (and, optionally, a back
     * image). Instead of jumping straight to the generic Document Ready preview, this opens the
     * CamScanner-style [IdCardReviewState] step (crop/rotate/filter/save). The capture-baked
     * files (already EXIF-corrected and guide-frame-cropped by [IdCardCaptureBaker]) become each
     * side's retained BASE image, the default [DocumentFilter.ENHANCE] is selected for both
     * sides, and its output is rendered non-destructively from the base — reproducing the old
     * enhanced first appearance without ever overwriting the unfiltered file.
     */
    internal fun beginIdCardReview(frontUri: Uri, backUri: Uri?) {
        showIdCardGuidedCapture = false
        val title = "$pendingIdCardCaptureTitlePrefix " +
            SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.getDefault()).format(Date())
        idCardReview = IdCardReviewState(
            frontBaseImageUri = frontUri.toString(),
            backBaseImageUri = backUri?.toString(),
            backRenderPending = backUri != null,
            title = title
        )
        renderIdCardReviewFilter(IdCardReviewSide.FRONT)
        if (backUri != null) {
            renderIdCardReviewFilter(IdCardReviewSide.BACK)
        }
    }

    // Per-side asynchronous filter-render protection: a monotonically increasing generation
    // token plus the render Job itself, independently for front and back so rapid filter
    // changes on one side can never cancel or corrupt the other side's render. A render may
    // publish only if its captured generation still matches AND the pure reducer
    // ([IdCardReviewFlow.withRenderedFilter]) confirms the side still wants exactly that
    // (base, filter) pair — an older render can never overwrite a newer selection.
    private var frontFilterGeneration = 0L
    private var backFilterGeneration = 0L
    private var frontFilterJob: Job? = null
    private var backFilterJob: Job? = null

    /**
     * The review screen's filter picker action: applies [filter] to the currently selected side
     * only (via the pure reducer, which preserves crop, rotation, the other side, and the
     * title), then kicks off its non-destructive render from that side's retained base image.
     */
    internal fun applyIdCardReviewFilter(filter: DocumentFilter) {
        val current = idCardReview ?: return
        val side = current.selectedSide
        val updated = IdCardReviewFlow.applyFilter(current, filter)
        if (updated === current) return
        idCardReview = updated
        renderIdCardReviewFilter(side)
    }

    /**
     * Renders [side]'s currently selected filter from its base image into a NEW app-private
     * file, cancelling any previous render for that side and bumping that side's generation.
     * ORIGINAL never renders — the cleared rendered URI already makes the tile display the base.
     * A failed render keeps the user's filter selection (display falls back to the base) and
     * reports through the existing error channel so they can retry or pick Original.
     */
    private fun renderIdCardReviewFilter(side: IdCardReviewSide) {
        val state = idCardReview ?: return
        val baseUri = state.baseImageUri(side) ?: return
        val filter = state.filter(side)
        val generation = when (side) {
            IdCardReviewSide.FRONT -> ++frontFilterGeneration
            IdCardReviewSide.BACK -> ++backFilterGeneration
        }
        when (side) {
            IdCardReviewSide.FRONT -> frontFilterJob?.cancel()
            IdCardReviewSide.BACK -> backFilterJob?.cancel()
        }
        if (filter == DocumentFilter.ORIGINAL) return

        val job = lifecycleScope.launch {
            // CancellationException must propagate: a stale render cancelled by a newer filter
            // choice (or by leaving the screen) is intentional and must not be reported as a
            // filter failure. Only genuine decode/render/write problems become null here.
            val rendered = try {
                DocumentFilterRenderer.render(
                    context = this@MainActivity,
                    sourceUri = Uri.parse(baseUri),
                    filter = filter,
                    outputDirectory = File(filesDir, "id_card_filtered")
                )
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Log.w(TAG, "ID card filter render threw: ${throwable.message}")
                null
            }
            val currentGeneration = when (side) {
                IdCardReviewSide.FRONT -> frontFilterGeneration
                IdCardReviewSide.BACK -> backFilterGeneration
            }
            if (currentGeneration != generation) return@launch
            val current = idCardReview ?: return@launch
            if (rendered == null) {
                Log.w(TAG, "Unable to render ID card filter ${filter.name}.")
                if (current.filter(side) == filter && current.baseImageUri(side) == baseUri) {
                    // Truthful recovery: revert the selection to whatever is actually on
                    // screen (the last rendered filter, or Original/base) and surface the
                    // failure — the user can re-tap the failed filter to retry.
                    idCardReview = IdCardReviewFlow.withRenderFailed(current, side, filter, baseUri)
                    viewModel.showError("Unable to apply ${filter.displayName}. Tap it again to retry.")
                }
                return@launch
            }
            idCardReview = IdCardReviewFlow.withRenderedFilter(
                state = current,
                side = side,
                filter = filter,
                baseImageUri = baseUri,
                renderedImageUri = rendered.toString()
            )
        }
        when (side) {
            IdCardReviewSide.FRONT -> frontFilterJob = job
            IdCardReviewSide.BACK -> backFilterJob = job
        }
    }

    private fun cancelIdCardFilterJobs() {
        frontFilterJob?.cancel()
        backFilterJob?.cancel()
        frontFilterJob = null
        backFilterJob = null
    }

    /**
     * Selects [side] as the Crop/Rotate/Filter target — and does nothing else. Tapping an image
     * must never rotate it (users hit unexpected rotations when tap-to-rotate was tried);
     * rotation only ever happens through [rotateSelectedIdCardReviewSide], wired to the explicit
     * Rotate toolbar button.
     */
    internal fun selectIdCardReviewSide(side: IdCardReviewSide) {
        val current = idCardReview ?: return
        idCardReview = IdCardReviewFlow.selectSide(current, side)
    }

    /** Rotates the currently selected review side 90° — the explicit Rotate button's action. */
    internal fun rotateSelectedIdCardReviewSide() {
        val current = idCardReview ?: return
        idCardReview = IdCardReviewFlow.rotateSelected(current)
    }

    /** Applies a user-edited title from the review screen's rename (pencil) dialog. */
    internal fun renameIdCardReviewTitle(newTitle: String) {
        idCardReview = idCardReview?.let { IdCardReviewFlow.renameTitle(it, newTitle) }
    }

    /** Placeholder for the review screen's help icon: never crashes, never blocks Save. */
    internal fun idCardReviewHelpTapped() {
        viewModel.showError("Tap an image to select it, then use Rotate or Crop to adjust it.")
    }

    /** Placeholder for the review screen's Compare action: never crashes, never blocks Save. */
    internal fun idCardReviewCompareTapped() {
        viewModel.showError("Compare is coming soon.")
    }

    /** Placeholder for the review screen's Add Watermark action: never crashes, never blocks Save. */
    internal fun idCardReviewAddWatermarkTapped() {
        viewModel.showError("Watermark is coming soon.")
    }

    /** Cancels the ID-card review step entirely, discarding both captured sides. */
    internal fun cancelIdCardReview() {
        cancelIdCardFilterJobs()
        idCardReview = null
    }

    /**
     * The green-check state machine (see [IdCardSaveCoordinator]): image production and
     * persistence are injected so its guarantees — single-flight guard, abort-before-persist,
     * review restored only when nothing was inserted, persisted-but-navigation-failed never
     * retryable — are unit tested without this activity.
     */
    private val idCardSaveCoordinator = IdCardSaveCoordinator(
        produceSideImage = { side -> produceFinalIdCardSideImage(side)?.toString() },
        renderCombinedPage = { front, back ->
            renderIdCardCombinedImage(Uri.parse(front), back?.let(Uri::parse))?.toString()
        },
        persistDocument = { title, pageCount, combinedImageUri ->
            persistIdCardDocument(title, pageCount, combinedImageUri)
        }
    )

    /**
     * Finalizes the ID-card review (the green check): produces each side's FINAL image
     * (selected filter rendered from the retained base, then the side's rotation baked into the
     * pixels), renders the equal-size combined front/back page, and saves ONE document through
     * the existing repository so it appears immediately in All Documents via the existing Room
     * Flow. Navigation to the Document Ready preview happens only after the repository insert
     * succeeds. A pre-persistence failure restores the review for retry; a completion failure
     * AFTER a successful insert is only logged — the document exists, so re-offering the review
     * would create a duplicate. Save to Gallery stays a separate user-triggered action.
     */
    internal fun confirmIdCardReview() {
        if (idCardSaveCoordinator.isSaving) return
        val review = idCardReview ?: return
        // Synchronous removal of the actionable review state (plus the coordinator's own guard)
        // makes a repeated green-check tap a no-op with exactly one insertion reachable.
        idCardReview = null
        cancelIdCardFilterJobs()
        lifecycleScope.launch {
            when (val outcome = idCardSaveCoordinator.confirm(review, ::showIdCardSavedPreview)) {
                is IdCardSaveCoordinator.Outcome.AlreadySaving -> Unit
                is IdCardSaveCoordinator.Outcome.Invalid -> {
                    idCardReview = review
                    viewModel.showError(outcome.reason)
                }
                is IdCardSaveCoordinator.Outcome.Aborted -> {
                    // Nothing was inserted — reopen the exact captured review for retry.
                    idCardReview = outcome.review
                    viewModel.showError(outcome.message)
                }
                is IdCardSaveCoordinator.Outcome.Completed -> Unit
                is IdCardSaveCoordinator.Outcome.CompletedWithCallbackFailure -> {
                    Log.w(TAG, "ID card saved, but showing the result preview failed.", outcome.failure)
                }
            }
        }
    }

    /** One repository insertion attempt for the completed ID card, adapted to [IdCardSaveCoordinator.PersistResult]. */
    private suspend fun persistIdCardDocument(
        title: String,
        pageCount: Int,
        combinedImageUri: String
    ): IdCardSaveCoordinator.PersistResult = suspendCancellableCoroutine { continuation ->
        viewModel.saveGeneratedPdfDocument(
            document = DocumentEntity(
                title = title,
                timestamp = System.currentTimeMillis(),
                pageCount = pageCount,
                localPdfUri = combinedImageUri
            ),
            onError = { message ->
                if (continuation.isActive) {
                    continuation.resume(IdCardSaveCoordinator.PersistResult.Failure(message))
                }
            },
            onSaved = {
                if (continuation.isActive) {
                    continuation.resume(IdCardSaveCoordinator.PersistResult.Success)
                }
            }
        )
    }

    /**
     * Post-persistence completion: swaps in the Document Ready preview for the saved ID card.
     * The per-side FINAL (filtered + rotation-baked) uris ride along so the ID-card PDF export
     * lays out exactly the images the combined page shows.
     */
    private fun showIdCardSavedPreview(result: IdCardSaveCoordinator.SaveResult) {
        imageImportReview = null
        pendingImageImport = null
        imageEditorMessage = null
        scannerBackendProcessingState = ScannerBackendProcessingState.Idle
        scannerFlowValidationState = ScannerFlowValidationState()
        documentResultState = null
        importedImagePreview = PendingImageImport(
            imageUri = Uri.parse(result.frontImageUri),
            title = result.title,
            backImageUri = result.backImageUri?.let(Uri::parse),
            isIdCardScan = true,
            combinedImageUri = Uri.parse(result.combinedImageUri)
        )
    }

    /**
     * Produces one side's final saved image per the authoritative pipeline: the selected filter
     * rendered from the retained base (reusing the already-published render when present —
     * never silently substituting the unfiltered base for a non-Original filter), then the
     * side's rotation baked into the pixels. Returns null to ABORT the save when the filter
     * render fails or a non-zero rotation cannot be baked — a silently unfiltered or unrotated
     * document must never be saved. Cancellation always propagates.
     */
    private suspend fun produceFinalIdCardSideImage(side: IdCardSideSavePlan): Uri? {
        val filtered: Uri = if (side.filter == DocumentFilter.ORIGINAL) {
            Uri.parse(side.baseImageUri)
        } else {
            side.renderedImageUri?.let(Uri::parse)
                ?: try {
                    DocumentFilterRenderer.render(
                        context = this@MainActivity,
                        sourceUri = Uri.parse(side.baseImageUri),
                        filter = side.filter,
                        outputDirectory = File(filesDir, "id_card_filtered")
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    Log.w(TAG, "Unable to render ${side.filter.name} for save: ${throwable.message}")
                    null
                }
                ?: return null
        }
        if (!side.requiresRotationBake) return filtered
        return try {
            IdScanPostProcessor.rotateAndSave(
                context = this@MainActivity,
                sourceUri = filtered,
                degrees = side.rotationDegrees,
                outputDirectory = File(filesDir, "id_scan_rotated")
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            Log.w(TAG, "Unable to bake ID card rotation: ${throwable.message}")
            null
        }
    }

    /**
     * Renders the single combined ID-card result page (front + back on one white A4-style page,
     * see [IdCardCombinedPageRenderer]) into app-private storage. Best-effort: a failure returns
     * null and the preview/gallery paths fall back to the per-side images rather than blocking.
     */
    private suspend fun renderIdCardCombinedImage(frontUri: Uri, backUri: Uri?): Uri? {
        val combined = runCatching {
            IdCardCombinedPageRenderer.render(
                context = this,
                frontUri = frontUri,
                backUri = backUri,
                outputDirectory = File(filesDir, "id_card_combined")
            )
        }.getOrNull()
        if (combined == null) {
            Log.w(TAG, "Unable to render combined ID card result page.")
        }
        return combined
    }

    /**
     * Re-renders [importedImagePreview]'s combined ID-card page from its current front/back
     * images. Needed wherever a side image changes after the preview is already showing (the ML
     * Kit scan path's async enhancement, a re-edit, a signature) so the combined page — the one
     * image the preview displays and "Save to gallery" writes — never goes stale. The result is
     * only swapped in if the preview still shows the same sides it was rendered from; a no-op
     * for every non-ID-card preview.
     */
    internal fun refreshIdCardCombinedPreviewImage() {
        val preview = importedImagePreview ?: return
        if (!preview.isIdCardScan) return
        lifecycleScope.launch {
            val combined = renderIdCardCombinedImage(preview.imageUri, preview.backImageUri)
                ?: return@launch
            val current = importedImagePreview ?: return@launch
            if (current.isIdCardScan &&
                current.imageUri == preview.imageUri &&
                current.backImageUri == preview.backImageUri
            ) {
                importedImagePreview = current.copy(combinedImageUri = combined)
            }
        }
    }

    /**
     * Opens the shared crop editor targeting the ID-card review's currently selected side.
     * Cropping always works on the side's BASE image (capture-baked, unfiltered) — per the
     * authoritative pipeline the crop feeds the base, and the selected filter re-renders from
     * the cropped result.
     */
    internal fun openIdCardCropEditor() {
        val review = idCardReview ?: return
        val side = review.selectedSide
        val source = review.baseImageUri(side)
        if (source.isNullOrBlank()) {
            viewModel.showError("No image is available to crop.")
            return
        }
        idCardCropTargetSide = side
        idCardCropSourceBitmap = null
        val startingQuad = PerspectiveQuad.full()
        idCardCropState = CropState(
            sourceImageUri = source,
            quad = startingQuad,
            originalQuad = startingQuad,
            mode = com.dev.docscannerpdf.domain.crop.CropMode.EDITING
        )
        lifecycleScope.launch {
            val bitmap = cropImageProcessor.loadSource(source)
            if (bitmap == null) {
                idCardCropState = null
                viewModel.showError("Unable to load the image for cropping.")
                return@launch
            }
            idCardCropSourceBitmap = bitmap
        }
    }

    internal fun cancelIdCardCropEditor() {
        idCardCropState = null
        idCardCropSourceBitmap = null
    }

    internal fun idCardCropMoveCorner(corner: CropCorner, x: Float, y: Float) {
        idCardCropState = idCardCropState?.let { CropReducer.moveCorner(it, corner, x, y) }
    }

    internal fun idCardCropResetQuad() {
        idCardCropState = idCardCropState?.let { CropReducer.resetQuad(it) }
    }

    /**
     * Applies the ID-card crop: warps the BASE to a new local file, installs it as the target
     * side's new base (keeping that side's selected filter and rotation, clearing its now-stale
     * rendered output), and re-renders the selected filter from the new base. A failed warp
     * keeps the previous base and rendered result and leaves the crop editor recoverable.
     */
    internal fun idCardCropApply() {
        val current = idCardCropState ?: return
        val bitmap = idCardCropSourceBitmap
        if (bitmap == null) {
            viewModel.showError("Image is still loading.")
            return
        }
        val applying = CropReducer.applyCrop(current)
        if (!applying.isApplying) {
            viewModel.showError("Adjust the corners into a valid shape before applying.")
            return
        }
        idCardCropState = applying
        val side = idCardCropTargetSide
        lifecycleScope.launch {
            val croppedUri = cropImageProcessor.warpAndSave(bitmap, applying.quad)
            if (croppedUri == null) {
                idCardCropState = current.copy(mode = com.dev.docscannerpdf.domain.crop.CropMode.EDITING)
                viewModel.showError("Unable to apply crop.")
                return@launch
            }
            idCardReview = idCardReview?.let {
                IdCardReviewFlow.withCroppedBase(it, side, croppedUri.toString())
            }
            renderIdCardReviewFilter(side)
            cancelIdCardCropEditor()
            viewModel.showError("Crop applied.")
        }
    }

    /**
     * Auto-capture signal from the live detection loop. Per this slice it is an event only — it
     * does not trigger a shutter; it just records that a stable document was detected.
     */
    internal fun onLiveCaptureReady() {
        Log.d(TAG, "Live auto-capture signal: stable document detected.")
    }

    /** Opens the local-first document library; documents load from Room with no backend calls. */
    internal fun openDocumentLibrary() {
        showDocumentLibrary = true
    }

    internal fun closeDocumentLibrary() {
        showDocumentLibrary = false
        documentLibraryQuery = ""
        libraryPendingRename = null
        libraryPendingDelete = null
    }

    /**
     * Routes a library document to the best existing destination: the unified
     * [com.dev.docscannerpdf.ui.result.DocumentResultScreen] when it carries OCR text or an
     * image preview, otherwise the existing PDF viewer. The library stays open underneath so
     * backing out returns here.
     */
    internal fun openLibraryDocument(document: DocumentEntity) {
        when {
            shouldOpenMultiPageEditor(document) -> openMultiPageEditor(document)
            isResultScreenEligible(document) -> documentResultState = document.toLibraryResultState()
            else -> pdfViewerDocument = document
        }
    }

    /**
     * Opens the multi-page editor for a multi-page document, deriving pages from the stored
     * document (no backend calls). Edits live in-memory for this slice and are not persisted.
     */
    internal fun openMultiPageEditor(document: DocumentEntity) {
        multiPageEditorState = document.toMultiPageEditorState()
    }

    internal fun closeMultiPageEditor() {
        multiPageEditorState = null
    }

    private fun updateEditor(transform: (MultiPageEditorState) -> MultiPageEditorState) {
        multiPageEditorState = multiPageEditorState?.let(transform)
    }

    internal fun editorSelectPage(pageId: String) =
        updateEditor { MultiPageEditorReducer.select(it, pageId) }

    internal fun editorMovePageUp(pageId: String) =
        updateEditor { MultiPageEditorReducer.movePageUp(it, pageId) }

    internal fun editorMovePageDown(pageId: String) =
        updateEditor { MultiPageEditorReducer.movePageDown(it, pageId) }

    internal fun editorDuplicatePage(pageId: String) =
        updateEditor { MultiPageEditorReducer.duplicatePage(it, pageId) }

    internal fun editorRotatePage(pageId: String) =
        updateEditor { MultiPageEditorReducer.rotatePage(it, pageId) }

    internal fun editorRequestDeletePage(pageId: String) =
        updateEditor { MultiPageEditorReducer.requestDelete(it, pageId) }

    internal fun editorCancelDeletePage() =
        updateEditor { MultiPageEditorReducer.cancelDelete(it) }

    internal fun editorConfirmDeletePage() =
        updateEditor { MultiPageEditorReducer.confirmDelete(it) }

    /** Adding pages to an existing document is not wired yet; surfaced as a placeholder. */
    internal fun editorAddPagePlaceholder() {
        viewModel.showError("Adding pages to an existing document is coming soon.")
    }

    /**
     * Persists edited OCR text through the existing persistence path when this result maps
     * to a locally-saved document; otherwise updates the in-memory result so edits are not lost.
     */
    internal fun saveResultOcrText(text: String) {
        documentResultState = documentResultState?.copy(ocrText = text.ifBlank { null })
        val localUri = documentResultState?.localPreviewUri
        val match = localUri?.let { uri ->
            viewModel.uiState.value.documents.firstOrNull { it.localPdfUri == uri }
        }
        if (match != null) {
            viewModel.updateDocumentOcrText(match, text)
        } else {
            viewModel.showError("OCR text saved for this result.")
        }
    }

    internal fun shareResultText(text: String) {
        val title = importedImagePreview?.title ?: "Document Result"
        shareCleanedText(title = title, text = text)
    }

    internal fun exportResultText(text: String, extension: String) {
        val title = importedImagePreview?.title ?: "Document Result"
        exportCleanedText(title = title, text = text, extension = extension)
    }

    /**
     * Exports the current document result as a CamScanner-style searchable PDF: the backend
     * page image (enhanced preferred, processed fallback) is rendered to A4 with [ocrText]
     * embedded as an invisible, selectable layer. Re-uses the OCR text already on screen —
     * no OCR is re-run and no backend processing is triggered — and the generated PDF is
     * persisted through the existing document store so it appears alongside other documents.
     */
    internal fun exportSearchablePdf(ocrText: String) {
        val state = documentResultState
        if (state == null) {
            viewModel.showError("No document result is available to export.")
            return
        }
        val resolvedText = ocrText.ifBlank { state.ocrText }?.takeIf { it.isNotBlank() }
        // Prefer the live editor session; fall back to persisted annotations for this page.
        val storedAnnotations = annotationEditor?.page?.annotations
            ?: annotationRepository.loadPage(annotationDocId(state), annotationPageId(state))
        // Project annotations through the applied crop so they align with the exported image.
        val annotations = appliedCropQuad?.let { quad ->
            AnnotationHomographyMapper.applyQuadTransform(storedAnnotations, sourceQuad = quad)
        } ?: storedAnnotations
        // A locally applied crop overrides the backend image for export, too.
        val croppedImage = state.localCroppedUri?.takeIf { it.isNotBlank() }
        // Before the backend has produced an image, fall back to the on-device preview so
        // ID-card scans (PR #22) that only have a local image are still exportable.
        val localFallbackImage = state.localPreviewUri?.takeIf { it.isNotBlank() }
        val title = importedImagePreview?.title ?: "Searchable PDF"
        val backImage = state.localBackPreviewUri?.takeIf { it.isNotBlank() }
        // An ID-card scan always needs the card-sized A4 layout instead of the normal
        // full-page searchable PDF, whether or not a back side was captured.
        val isIdCardScan = importedImagePreview?.isIdCardScan == true

        if (isIdCardScan) {
            val frontImage = croppedImage
                ?: state.enhancedImageUrl?.takeIf { it.isNotBlank() }
                ?: state.processedImageUrl?.takeIf { it.isNotBlank() }
                ?: localFallbackImage
            lifecycleScope.launch {
                viewModel.showError("Exporting ID card PDF…")
                val result = pdfExportService.exportIdCard(
                    input = IdCardPdfInput(
                        frontImageUrl = frontImage,
                        backImageUrl = backImage,
                        ocrText = resolvedText
                    ),
                    fileName = title
                )
                when (result) {
                    is PdfExportService.Result.Success -> {
                        viewModel.saveGeneratedPdfDocument(
                            title = title,
                            pageCount = result.pageCount,
                            pdfUri = Uri.fromFile(result.file),
                            extractedText = resolvedText
                        )
                        viewModel.showError("ID card PDF exported: ${result.file.name}")
                    }
                    is PdfExportService.Result.Failure ->
                        viewModel.showError(result.message)
                }
            }
            return
        }

        lifecycleScope.launch {
            viewModel.showError("Exporting searchable PDF…")
            val textSpans = buildSearchableTextSpans(state)
            val pages = listOf(
                PdfExportPageInput(
                    pageNumber = 1,
                    enhancedImageUrl = if (croppedImage == null) state.enhancedImageUrl else null,
                    processedImageUrl = croppedImage
                        ?: state.processedImageUrl?.takeIf { it.isNotBlank() }
                        ?: localFallbackImage,
                    ocrText = resolvedText,
                    annotations = annotations,
                    textSpans = textSpans
                )
            )
            when (val result = pdfExportService.export(pages = pages, fileName = title)) {
                is PdfExportService.Result.Success -> {
                    viewModel.saveGeneratedPdfDocument(
                        title = title,
                        pageCount = result.pageCount,
                        pdfUri = Uri.fromFile(result.file),
                        extractedText = resolvedText
                    )
                    viewModel.showError("Searchable PDF exported: ${result.file.name}")
                }
                is PdfExportService.Result.Failure ->
                    viewModel.showError(result.message)
            }
        }
    }

    /**
     * Builds positioned, searchable text spans for [state]'s exported page: OCR boxes are
     * recognized fresh against the same base image the crop editor works from (so no separate
     * OCR pipeline is introduced — [ScannerViewModel.recognizeTextBoxes] already existed), then
     * normalized to page space and, if a crop was applied, projected through the same homography
     * annotations use so the boxes land on the cropped output exactly where the text is.
     * Returns an empty list (falling back to the reflowed whole-string layer) whenever no base
     * image is available or OCR yields nothing — this never blocks the export.
     */
    private suspend fun buildSearchableTextSpans(state: DocumentResultState): List<PdfTextSpan> {
        val source = state.baseImageModel?.takeIf { it.isNotBlank() } ?: return emptyList()
        val bitmap = runCatching { cropImageProcessor.loadSource(source) }.getOrNull()
            ?: return emptyList()
        return try {
            val boxes = viewModel.recognizeTextBoxes(bitmap)
            val normalizedBoxes = PdfCoordinateMapper.normalize(boxes, bitmap.width, bitmap.height)
            val projectedBoxes = appliedCropQuad?.let { quad ->
                PdfCoordinateMapper.projectThroughCrop(normalizedBoxes, sourceQuad = quad)
            } ?: normalizedBoxes
            SearchablePdfTextLayer.build(projectedBoxes)
        } catch (throwable: Throwable) {
            Log.w(TAG, "Unable to build positioned OCR text spans: ${throwable.message}")
            emptyList()
        } finally {
            bitmap.recycle()
        }
    }

    internal fun runImportedImageOcr(
        imageUri: Uri,
        showResult: Boolean
    ) {
        lifecycleScope.launch {
            pendingImageImport = pendingImageImport?.takeIf { it.imageUri == imageUri }
                ?.copy(isExtractingText = true)

            val extractedText = runCatching {
                viewModel.recognizeText(this@MainActivity, imageUri)
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to extract text from imported image: ${throwable.message}")
                recordFailure("ocr", throwable, mapOf("source" to "imported_image_editor"))
            }.onSuccess { text ->
                analyticsRepository.trackEvent(
                    AnalyticsRepository.EVENT_OCR_EXTRACTED,
                    mapOf("source" to "imported_image_editor", "has_text" to text.isNotBlank())
                )
            }.getOrNull().orEmpty()

            pendingImageImport = pendingImageImport?.takeIf { it.imageUri == imageUri }
                ?.copy(
                    extractedText = extractedText.takeIf { it.isNotBlank() },
                    isExtractingText = false
                )

            if (showResult) {
                imageEditorMessage = if (extractedText.isBlank()) {
                    "No text was found in this image."
                } else {
                    extractedText
                }
            }
        }
    }

    private suspend fun loadBitmapFromUri(uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
        return@withContext try {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        } catch (t: Throwable) {
            null
        }
    }

    private suspend fun saveBitmapToPrivateStorage(bitmap: Bitmap, prefix: String): Uri =
        withContext(Dispatchers.IO) {
            val directory = File(filesDir, "imported_images").apply { if (!exists()) mkdirs() }
            val destination = File(directory, "$prefix-${System.currentTimeMillis()}.jpg")
            FileOutputStream(destination).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
            Uri.fromFile(destination)
        }

    suspend fun applySignatureToImage(imageUri: Uri, strokes: List<List<androidx.compose.ui.geometry.Offset>>): Uri {
        return withContext(Dispatchers.IO) {
            val original = loadBitmapFromUri(imageUri) ?: throw IllegalStateException("Unable to load image")
            val mutable = original.copy(Bitmap.Config.ARGB_8888, true)

            // create signature bitmap
            val sigWidth = mutable.width / 3
            val sigHeight = mutable.height / 6
            val sigBitmap = Bitmap.createBitmap(sigWidth, sigHeight, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(sigBitmap)
            canvas.drawColor(android.graphics.Color.TRANSPARENT)
            val paint = android.graphics.Paint().apply {
                color = android.graphics.Color.BLACK
                strokeWidth = (sigHeight * 0.06f)
                style = android.graphics.Paint.Style.STROKE
                isAntiAlias = true
                strokeCap = android.graphics.Paint.Cap.ROUND
                strokeJoin = android.graphics.Paint.Join.ROUND
            }

            // determine bounds of strokes
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = Float.MIN_VALUE; var maxY = Float.MIN_VALUE
            strokes.forEach { stroke -> stroke.forEach { p -> minX = minOf(minX, p.x); minY = minOf(minY, p.y); maxX = maxOf(maxX, p.x); maxY = maxOf(maxY, p.y) } }
            val strokesWidth = if (maxX > minX) maxX - minX else 1f
            val strokesHeight = if (maxY > minY) maxY - minY else 1f

            // draw strokes scaled to signature bitmap
            strokes.forEach { stroke ->
                if (stroke.size < 2) return@forEach
                val path = android.graphics.Path()
                stroke.forEachIndexed { i, p ->
                    val sx = ((p.x - minX) / strokesWidth) * sigWidth
                    val sy = ((p.y - minY) / strokesHeight) * sigHeight
                    if (i == 0) path.moveTo(sx, sy) else path.lineTo(sx, sy)
                }
                canvas.drawPath(path, paint)
            }

            // overlay signature onto bottom-right of original
            val outCanvas = android.graphics.Canvas(mutable)
            val left = mutable.width - sigWidth - (mutable.width * 0.03f)
            val top = mutable.height - sigHeight - (mutable.height * 0.03f)
            outCanvas.drawBitmap(sigBitmap, left, top, null)

            // save
            saveBitmapToPrivateStorage(mutable, "signed_image")
        }
    }

    suspend fun cropImageCenter(imageUri: Uri): Uri {
        return withContext(Dispatchers.IO) {
            val bmp = loadBitmapFromUri(imageUri) ?: throw IllegalStateException("Unable to load image for crop")
            val w = bmp.width; val h = bmp.height
            val inset = (minOf(w, h) * 0.08f).toInt()
            val left = inset; val top = inset; val newW = w - inset * 2; val newH = h - inset * 2
            val cropped = Bitmap.createBitmap(bmp, left.coerceAtLeast(0), top.coerceAtLeast(0), newW.coerceAtLeast(1), newH.coerceAtLeast(1))
            saveBitmapToPrivateStorage(cropped, "imported_image_cropped")
        }
    }

    suspend fun saveImageToGallery(uri: Uri, title: String) {
        withContext(Dispatchers.IO) {
            val bitmap = loadBitmapFromUri(uri) ?: throw IllegalStateException("Unable to load image to save")
            // Use MediaStore to insert image
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, title.ifBlank { "DocScanner-${System.currentTimeMillis()}" } + ".jpg")
                put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/DocScanner")
            }
            val resolver = contentResolver
            val uriOut = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("Unable to create MediaStore entry")
            resolver.openOutputStream(uriOut)?.use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out) }
        }
    }

    private fun observeInterstitialRequests() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                viewModel.uiState
                    .map { it.shouldShowInterstitial }
                    .distinctUntilChanged()
                    .collect { shouldShowInterstitial ->
                        if (shouldShowInterstitial) {
                            viewModel.markInterstitialConsumed()
                            AdManager.showInterstitialIfAllowed(this@MainActivity) {}
                        }
                    }
            }
        }
    }

    internal fun startDocumentScanner(
        pageLimit: Int,
        titlePrefix: String = DEFAULT_SCAN_TITLE_PREFIX,
        galleryImportAllowed: Boolean = false,
        scannerMode: Int = GmsDocumentScannerOptions.SCANNER_MODE_FULL,
        isIdCardScan: Boolean = false
    ) {
        pendingScanIsIdCardScan = isIdCardScan
        // ML Kit Document Scanner owns the camera experience, including auto edge
        // detection and auto crop. Because Google Play services provides that UI,
        // this app does not request CAMERA permission or build a custom camera.
        pendingScanTitlePrefix = titlePrefix
        val options = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(galleryImportAllowed)
            .setPageLimit(pageLimit)
            .setResultFormats(
                GmsDocumentScannerOptions.RESULT_FORMAT_PDF,
                GmsDocumentScannerOptions.RESULT_FORMAT_JPEG
            )
            .setScannerMode(scannerMode)
            .build()

        val scanner = GmsDocumentScanning.getClient(options)
        scanner.getStartScanIntent(this)
            .addOnSuccessListener { intentSender ->
                documentScannerLauncher.launch(
                    IntentSenderRequest.Builder(intentSender).build()
                )
            }
            .addOnFailureListener { exception ->
                Log.w(TAG, "Unable to start document scanner: ${exception.message}")
                pendingScanTitlePrefix = DEFAULT_SCAN_TITLE_PREFIX
                viewModel.showError("Unable to start document scanner.")
            }
    }

    private fun persistReadPermissionIfAvailable(uri: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure { exception ->
            Log.w(TAG, "Unable to persist file read permission: ${exception.message}")
        }
    }

    internal fun compressSelectedPdf() {
        val selectedUri = compressPdfState.selectedUri?.let { Uri.parse(it) }
        if (selectedUri == null) {
            compressPdfState = compressPdfState.copy(message = "Select a PDF first.")
            return
        }

        lifecycleScope.launch {
            compressPdfState = compressPdfState.copy(isWorking = true, message = null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val outputDirectory = File(cacheDir, "compressed_pdfs").apply {
                        if (!exists()) mkdirs()
                    }
                    val outputName = "compressed-${sanitizeFileName(compressPdfState.selectedName ?: "document")}.pdf"
                    val outputFile = File(outputDirectory, outputName)
                    contentResolver.openInputStream(selectedUri).use { input ->
                        requireNotNull(input) { "Unable to read selected PDF." }
                        outputFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    outputFile
                }
            }

            result.onSuccess { outputFile ->
                compressPdfState = compressPdfState.copy(
                    outputPath = outputFile.absolutePath,
                    outputSizeBytes = outputFile.length(),
                    isWorking = false,
                    message = "Basic compression ready. Advanced image recompression will be added later."
                )
                analyticsRepository.trackEvent(
                    AnalyticsRepository.EVENT_PDF_COMPRESSED,
                    mapOf(
                        "original_size_bytes" to (compressPdfState.originalSizeBytes ?: 0L),
                        "output_size_bytes" to outputFile.length()
                    )
                )
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to compress PDF: ${throwable.message}")
                recordFailure("pdf_compress", throwable)
                compressPdfState = compressPdfState.copy(
                    isWorking = false,
                    message = "Unable to create compressed PDF copy."
                )
            }
        }
    }

    internal fun shareCompressedPdf() {
        val outputFile = compressPdfState.outputPath?.let(::File)
        if (outputFile == null || !outputFile.exists()) {
            compressPdfState = compressPdfState.copy(message = "Compressed PDF is missing.")
            return
        }

        val outputUri = runCatching {
            FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                outputFile
            )
        }.getOrNull()

        if (outputUri == null) {
            compressPdfState = compressPdfState.copy(message = "Unable to share compressed PDF.")
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = PDF_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, outputUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, outputFile.name, outputUri)
        }

        try {
            startActivity(Intent.createChooser(intent, "Share compressed PDF"))
        } catch (exception: ActivityNotFoundException) {
            Log.w(TAG, "No app available to share compressed PDF: ${exception.message}")
            compressPdfState = compressPdfState.copy(message = "No app is available to share this PDF.")
        } catch (exception: Throwable) {
            Log.w(TAG, "Unable to share compressed PDF: ${exception.message}")
            compressPdfState = compressPdfState.copy(message = "Unable to share compressed PDF.")
        }
    }

    internal fun lockSelectedPdf() {
        val selectedUri = lockPdfState.selectedUri?.let { Uri.parse(it) }
        if (selectedUri == null) {
            lockPdfState = lockPdfState.copy(message = "Select a PDF first.")
            return
        }
        val validationMessage = validatePdfPassword(lockPdfState.password)
        if (validationMessage != null) {
            lockPdfState = lockPdfState.copy(message = validationMessage)
            return
        }

        lifecycleScope.launch {
            lockPdfState = lockPdfState.copy(isWorking = true, message = null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val outputDirectory = File(filesDir, "locked_pdfs").apply {
                        if (!exists()) mkdirs()
                    }
                    val outputFile = File(
                        outputDirectory,
                        "locked-${sanitizeFileName(lockPdfState.selectedName ?: "document")}.pdf"
                    )
                    contentResolver.openInputStream(selectedUri).use { input ->
                        requireNotNull(input) { "Unable to read selected PDF." }
                        outputFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    outputFile
                }
            }

            result.onSuccess { outputFile ->
                viewModel.saveGeneratedPdfDocument(
                    title = "Locked PDF",
                    pageCount = countPdfPages(Uri.fromFile(outputFile)),
                    pdfUri = Uri.fromFile(outputFile),
                    extractedText = null
                )
                lockPdfState = lockPdfState.copy(
                    isWorking = false,
                    message = "Password protection requires advanced PDF encryption library and will be enabled in production build."
                )
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to prepare locked PDF copy: ${throwable.message}")
                lockPdfState = lockPdfState.copy(
                    isWorking = false,
                    message = "Unable to copy selected PDF safely."
                )
            }
        }
    }

    internal fun unlockSelectedPdf() {
        if (unlockPdfState.selectedUri == null) {
            unlockPdfState = unlockPdfState.copy(message = "Select a PDF first.")
            return
        }
        val validationMessage = validatePdfPassword(unlockPdfState.password)
        if (validationMessage != null) {
            unlockPdfState = unlockPdfState.copy(message = validationMessage)
            return
        }

        unlockPdfState = unlockPdfState.copy(
            message = "Unlock PDF coming soon. Encrypted PDF support requires advanced PDF library."
        )
    }

    private fun validatePdfPassword(password: String): String? {
        val trimmedPassword = password.trim()
        return when {
            trimmedPassword.isBlank() -> "Password cannot be blank."
            trimmedPassword.length < 4 -> "Password must be at least 4 characters."
            else -> null
        }
    }

    private fun loadMergePdfs(uris: List<Uri>) {
        lifecycleScope.launch {
            mergePdfState = mergePdfState.copy(
                isLoading = true,
                message = "Reading selected PDFs."
            )
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    uris.map { uri ->
                        MergePdfItem(
                            uri = uri.toString(),
                            name = displayNameForUri(uri),
                            pageCount = countPdfPages(uri)
                        )
                    }
                }
            }

            result.onSuccess { items ->
                showMergePdf = true
                showPdfTools = false
                mergePdfState = MergePdfUiState(
                    items = items,
                    message = "${items.size} PDF${if (items.size == 1) "" else "s"} ready."
                )
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to load merge PDFs: ${throwable.message}")
                mergePdfState = mergePdfState.copy(
                    isLoading = false,
                    message = "Unable to read one or more PDFs. They may be encrypted or corrupted."
                )
            }
        }
    }

    internal fun moveMergeItem(
        index: Int,
        delta: Int
    ) {
        val nextIndex = index + delta
        val items = mergePdfState.items
        if (index !in items.indices || nextIndex !in items.indices) return
        mergePdfState = mergePdfState.copy(
            items = items.toMutableList().also { list ->
                val moved = list.removeAt(index)
                list.add(nextIndex, moved)
            }
        )
    }

    internal fun mergeSelectedPdfs() {
        val items = mergePdfState.items
        if (items.size < 2) {
            mergePdfState = mergePdfState.copy(message = "Select at least two PDFs to merge.")
            return
        }

        lifecycleScope.launch {
            mergePdfState = mergePdfState.copy(isMerging = true, message = null)
            val result = runCatching {
                createMergedPdf(items)
            }

            result.onSuccess { output ->
                viewModel.saveGeneratedPdfDocument(
                    title = "Merged PDF",
                    pageCount = output.pageCount,
                    pdfUri = Uri.fromFile(output.file),
                    extractedText = output.extractedText
                )
                mergePdfState = MergePdfUiState()
                showMergePdf = false
                showPdfTools = false
                viewModel.showError("Merged PDF saved successfully.")
                analyticsRepository.trackEvent(
                    AnalyticsRepository.EVENT_PDF_MERGED,
                    mapOf(
                        "input_count" to items.size,
                        "page_count" to output.pageCount
                    )
                )
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to merge PDFs: ${throwable.message}")
                recordFailure("pdf_merge", throwable, mapOf("input_count" to items.size.toString()))
                mergePdfState = mergePdfState.copy(
                    isMerging = false,
                    message = "Unable to merge selected PDFs. One may be encrypted or corrupted."
                )
            }
        }
    }

    private suspend fun createMergedPdf(items: List<MergePdfItem>): MergeOutput {
        return withContext(Dispatchers.IO) {
            val outputDirectory = File(filesDir, "merged_pdfs").apply {
                if (!exists()) mkdirs()
            }
            val outputFile = File(outputDirectory, "merged-pdf-${System.currentTimeMillis()}.pdf")
            val pdfDocument = PdfDocument()
            val ocrText = mutableListOf<String>()
            var outputPageCount = 0

            try {
                items.forEach { item ->
                    val uri = Uri.parse(item.uri)
                    openPdfDescriptor(uri)?.use { descriptor ->
                        PdfRenderer(descriptor).use { renderer ->
                            (0 until renderer.pageCount).forEach { pageIndex ->
                                renderer.openPage(pageIndex).use { sourcePage ->
                                    val bitmap = renderPdfPageToBitmap(
                                        page = sourcePage,
                                        maxDimension = MAX_PDF_IMAGE_DIMENSION
                                    )
                                    try {
                                        val recognizedText = runCatching {
                                            viewModel.recognizeText(bitmap)
                                        }.onFailure { throwable ->
                                            Log.w(TAG, "Unable to OCR merged page: ${throwable.message}")
                                        }.getOrNull()
                                        if (!recognizedText.isNullOrBlank()) {
                                            ocrText += recognizedText
                                        }

                                        val pageInfo = PdfDocument.PageInfo.Builder(
                                            A4_WIDTH_POINTS,
                                            A4_HEIGHT_POINTS,
                                            outputPageCount + 1
                                        ).create()
                                        val outputPage = pdfDocument.startPage(pageInfo)
                                        drawImageOnA4Page(outputPage.canvas, bitmap)
                                        pdfDocument.finishPage(outputPage)
                                        outputPageCount++
                                    } finally {
                                        bitmap.recycle()
                                    }
                                }
                            }
                        }
                    } ?: throw IllegalStateException("Unable to read selected PDF.")
                }

                if (outputPageCount == 0) {
                    throw IllegalStateException("No pages were merged.")
                }

                outputFile.outputStream().use { output ->
                    pdfDocument.writeTo(output)
                }
            } finally {
                pdfDocument.close()
            }

            MergeOutput(
                file = outputFile,
                pageCount = outputPageCount,
                extractedText = ocrText.joinToString(separator = "\n\n")
                    .takeIf { it.isNotBlank() }
            )
        }
    }

    private fun countPdfPages(uri: Uri): Int {
        return openPdfDescriptor(uri)?.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                renderer.pageCount
            }
        } ?: 0
    }

    private fun loadSplitPdf(uri: Uri) {
        lifecycleScope.launch {
            splitPdfState = SplitPdfUiState(
                selectedUri = uri.toString(),
                selectedName = displayNameForUri(uri),
                isLoading = true,
                message = "Loading PDF pages."
            )

            val result = withContext(Dispatchers.IO) {
                runCatching { renderSplitThumbnails(uri) }
            }

            result.onSuccess { thumbnails ->
                splitPdfState = splitPdfState.copy(
                    pageThumbnails = thumbnails,
                    selectedPages = emptySet(),
                    customRange = if (thumbnails.isNotEmpty()) "1-${thumbnails.size}" else "",
                    isLoading = false,
                    message = if (thumbnails.isEmpty()) {
                        "No pages were found in this PDF."
                    } else {
                        "${thumbnails.size} page${if (thumbnails.size == 1) "" else "s"} ready."
                    }
                )
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to load split PDF: ${throwable.message}")
                splitPdfState = splitPdfState.copy(
                    isLoading = false,
                    message = "Unable to open this PDF. It may be encrypted or invalid."
                )
            }
        }
    }

    internal fun splitSelectedPdf() {
        val sourceUri = splitPdfState.selectedUri?.let { Uri.parse(it) }
        if (sourceUri == null) {
            splitPdfState = splitPdfState.copy(message = "Select a PDF first.")
            return
        }
        val pageCount = splitPdfState.pageThumbnails.size
        if (pageCount <= 0) {
            splitPdfState = splitPdfState.copy(message = "No pages are available to split.")
            return
        }

        val groups = when (splitPdfState.mode) {
            SplitPdfMode.CustomRange -> {
                val pages = parsePageRange(splitPdfState.customRange, pageCount)
                if (pages.isEmpty()) {
                    splitPdfState = splitPdfState.copy(message = "Enter a valid page range.")
                    return
                }
                listOf(pages)
            }
            SplitPdfMode.EveryPage -> (0 until pageCount).map { pageIndex -> listOf(pageIndex) }
            SplitPdfMode.SelectedPages -> {
                val pages = splitPdfState.selectedPages.sorted()
                if (pages.isEmpty()) {
                    splitPdfState = splitPdfState.copy(message = "Select at least one page.")
                    return
                }
                listOf(pages)
            }
        }

        lifecycleScope.launch {
            splitPdfState = splitPdfState.copy(isSplitting = true, message = null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    groups.mapIndexed { index, pages ->
                        createSplitPdf(
                            sourceUri = sourceUri,
                            pageIndices = pages,
                            outputIndex = index + 1
                        )
                    }
                }
            }

            result.onSuccess { outputs ->
                outputs.forEachIndexed { index, output ->
                    viewModel.saveGeneratedPdfDocument(
                        title = "Split PDF ${index + 1}",
                        pageCount = output.pageCount,
                        pdfUri = Uri.fromFile(output.file),
                        extractedText = null
                    )
                }
                splitPdfState = SplitPdfUiState()
                showSplitPdf = false
                showPdfTools = false
                viewModel.showError("${outputs.size} split PDF${if (outputs.size == 1) "" else "s"} saved.")
                analyticsRepository.trackEvent(
                    AnalyticsRepository.EVENT_PDF_SPLIT,
                    mapOf(
                        "output_count" to outputs.size,
                        "source_page_count" to pageCount
                    )
                )
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to split PDF: ${throwable.message}")
                recordFailure("pdf_split", throwable, mapOf("source_page_count" to pageCount.toString()))
                splitPdfState = splitPdfState.copy(
                    isSplitting = false,
                    message = "Unable to split this PDF. It may be encrypted or invalid."
                )
            }
        }
    }

    private fun renderSplitThumbnails(uri: Uri): List<Bitmap> {
        return openPdfDescriptor(uri)?.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                (0 until renderer.pageCount).map { pageIndex ->
                    renderer.openPage(pageIndex).use { page ->
                        renderPdfPageToBitmap(
                            page = page,
                            maxDimension = SPLIT_THUMBNAIL_MAX_DIMENSION
                        )
                    }
                }
            }
        }.orEmpty()
    }

    private fun loadSignPdf(uri: Uri) {
        lifecycleScope.launch {
            signPdfState = SignPdfUiState(
                selectedUri = uri.toString(),
                selectedName = displayNameForUri(uri),
                isLoading = true,
                message = "Loading PDF pages."
            )

            val result = withContext(Dispatchers.IO) {
                runCatching { renderSplitThumbnails(uri) }
            }

            result.onSuccess { thumbnails ->
                signPdfState = signPdfState.copy(
                    pageThumbnails = thumbnails,
                    selectedPageIndex = 0,
                    isLoading = false,
                    message = if (thumbnails.isEmpty()) {
                        "No pages were found in this PDF."
                    } else {
                        "${thumbnails.size} page${if (thumbnails.size == 1) "" else "s"} ready."
                    }
                )
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to load sign PDF: ${throwable.message}")
                signPdfState = signPdfState.copy(
                    isLoading = false,
                    message = "Unable to open this PDF. It may be encrypted or invalid."
                )
            }
        }
    }

    internal fun exportSignedPdf() {
        val sourceUri = signPdfState.selectedUri?.let { Uri.parse(it) }
        val signature = signPdfState.signatureBitmap
        if (sourceUri == null) {
            signPdfState = signPdfState.copy(message = "Select a PDF first.")
            return
        }
        if (signature == null) {
            signPdfState = signPdfState.copy(message = "Draw or enter a signature first.")
            return
        }

        lifecycleScope.launch {
            signPdfState = signPdfState.copy(isExporting = true, message = null)

            val selectedPageIndex = signPdfState.selectedPageIndex
            val offsetX = signPdfState.signatureOffsetX
            val offsetY = signPdfState.signatureOffsetY
            val scale = signPdfState.signatureScale
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    createSignedPdf(
                        sourceUri = sourceUri,
                        selectedPageIndex = selectedPageIndex,
                        signature = signature,
                        signatureOffsetX = offsetX,
                        signatureOffsetY = offsetY,
                        signatureScale = scale
                    )
                }
            }

            result.onSuccess { output ->
                viewModel.saveGeneratedPdfDocument(
                    title = "Signed PDF",
                    pageCount = output.pageCount,
                    pdfUri = Uri.fromFile(output.file),
                    extractedText = null
                )
                signPdfState = SignPdfUiState()
                showSignPdf = false
                showPdfTools = false
                viewModel.showError("Signed PDF saved successfully.")
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to sign PDF: ${throwable.message}")
                signPdfState = signPdfState.copy(
                    isExporting = false,
                    message = "Unable to sign this PDF. It may be encrypted or invalid."
                )
            }
        }
    }

    private fun createSignedPdf(
        sourceUri: Uri,
        selectedPageIndex: Int,
        signature: Bitmap,
        signatureOffsetX: Float,
        signatureOffsetY: Float,
        signatureScale: Float
    ): SignedPdfOutput {
        val outputDirectory = File(filesDir, "signed_pdfs").apply {
            if (!exists()) mkdirs()
        }
        val outputFile = File(outputDirectory, "signed-pdf-${System.currentTimeMillis()}.pdf")
        val pdfDocument = PdfDocument()
        var outputPageCount = 0

        try {
            openPdfDescriptor(sourceUri)?.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    (0 until renderer.pageCount).forEach { pageIndex ->
                        renderer.openPage(pageIndex).use { sourcePage ->
                            val bitmap = renderPdfPageToBitmap(
                                page = sourcePage,
                                maxDimension = MAX_PDF_IMAGE_DIMENSION
                            )
                            try {
                                val pageInfo = PdfDocument.PageInfo.Builder(
                                    A4_WIDTH_POINTS,
                                    A4_HEIGHT_POINTS,
                                    pageIndex + 1
                                ).create()
                                val outputPage = pdfDocument.startPage(pageInfo)
                                drawImageOnA4Page(outputPage.canvas, bitmap)
                                if (pageIndex == selectedPageIndex) {
                                    drawSignatureOnA4Page(
                                        canvas = outputPage.canvas,
                                        signature = signature,
                                        offsetX = signatureOffsetX,
                                        offsetY = signatureOffsetY,
                                        scale = signatureScale
                                    )
                                }
                                pdfDocument.finishPage(outputPage)
                                outputPageCount++
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            } ?: throw IllegalStateException("Unable to read selected PDF.")

            if (outputPageCount == 0) {
                throw IllegalStateException("No pages were available to sign.")
            }

            outputFile.outputStream().use { output ->
                pdfDocument.writeTo(output)
            }
        } finally {
            pdfDocument.close()
        }

        return SignedPdfOutput(
            file = outputFile,
            pageCount = outputPageCount
        )
    }

    private fun drawSignatureOnA4Page(
        canvas: Canvas,
        signature: Bitmap,
        offsetX: Float,
        offsetY: Float,
        scale: Float
    ) {
        val pageWidth = A4_WIDTH_POINTS.toFloat()
        val pageHeight = A4_HEIGHT_POINTS.toFloat()
        val signatureWidth = (pageWidth * 0.36f * scale).coerceIn(80f, pageWidth * 0.82f)
        val signatureHeight = (signatureWidth * signature.height / signature.width.toFloat())
            .coerceAtMost(pageHeight * 0.28f)
        val centerX = pageWidth / 2f + offsetX.coerceIn(-0.48f, 0.48f) * pageWidth
        val centerY = pageHeight / 2f + offsetY.coerceIn(-0.48f, 0.48f) * pageHeight
        val left = (centerX - signatureWidth / 2f).coerceIn(8f, pageWidth - signatureWidth - 8f)
        val top = (centerY - signatureHeight / 2f).coerceIn(8f, pageHeight - signatureHeight - 8f)
        val destination = RectF(left, top, left + signatureWidth, top + signatureHeight)
        canvas.drawBitmap(signature, null, destination, Paint(Paint.ANTI_ALIAS_FLAG))
    }

    private fun loadWatermarkPdf(uri: Uri) {
        lifecycleScope.launch {
            watermarkPdfState = WatermarkPdfUiState(
                selectedUri = uri.toString(),
                selectedName = displayNameForUri(uri),
                isLoading = true,
                message = "Loading PDF preview."
            )

            val result = withContext(Dispatchers.IO) {
                runCatching { renderWatermarkPreview(uri) }
            }

            result.onSuccess { preview ->
                watermarkPdfState = watermarkPdfState.copy(
                    previewBitmap = preview.bitmap,
                    pageCount = preview.pageCount,
                    isLoading = false,
                    message = "${preview.pageCount} page${if (preview.pageCount == 1) "" else "s"} ready."
                )
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to load watermark PDF: ${throwable.message}")
                watermarkPdfState = watermarkPdfState.copy(
                    isLoading = false,
                    message = "Unable to open this PDF. It may be encrypted or invalid."
                )
            }
        }
    }

    private fun renderWatermarkPreview(uri: Uri): WatermarkPreview {
        return openPdfDescriptor(uri)?.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) {
                    throw IllegalStateException("PDF has no pages.")
                }
                renderer.openPage(0).use { page ->
                    WatermarkPreview(
                        bitmap = renderPdfPageToBitmap(
                            page = page,
                            maxDimension = SPLIT_THUMBNAIL_MAX_DIMENSION * 2
                        ),
                        pageCount = renderer.pageCount
                    )
                }
            }
        } ?: throw IllegalStateException("Unable to read selected PDF.")
    }

    internal fun applyWatermarkPdf() {
        val sourceUri = watermarkPdfState.selectedUri?.let { Uri.parse(it) }
        val watermarkText = watermarkPdfState.watermarkText.trim()
        if (sourceUri == null) {
            watermarkPdfState = watermarkPdfState.copy(message = "Select a PDF first.")
            return
        }
        if (watermarkText.isBlank()) {
            watermarkPdfState = watermarkPdfState.copy(message = "Enter watermark text.")
            return
        }

        lifecycleScope.launch {
            watermarkPdfState = watermarkPdfState.copy(isApplying = true, message = null)
            val opacity = watermarkPdfState.opacity
            val rotation = watermarkPdfState.rotation
            val position = watermarkPdfState.position
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    createWatermarkedPdf(
                        sourceUri = sourceUri,
                        watermarkText = watermarkText,
                        opacity = opacity,
                        rotation = rotation,
                        position = position
                    )
                }
            }

            result.onSuccess { output ->
                viewModel.saveGeneratedPdfDocument(
                    title = "Watermarked PDF",
                    pageCount = output.pageCount,
                    pdfUri = Uri.fromFile(output.file),
                    extractedText = null
                )
                watermarkPdfState = WatermarkPdfUiState()
                showWatermarkPdf = false
                showPdfTools = false
                viewModel.showError("Watermarked PDF saved successfully.")
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to watermark PDF: ${throwable.message}")
                watermarkPdfState = watermarkPdfState.copy(
                    isApplying = false,
                    message = "Unable to watermark this PDF. It may be encrypted or invalid."
                )
            }
        }
    }

    private fun createWatermarkedPdf(
        sourceUri: Uri,
        watermarkText: String,
        opacity: Float,
        rotation: Float,
        position: WatermarkPosition
    ): WatermarkOutput {
        val outputDirectory = File(filesDir, "watermarked_pdfs").apply {
            if (!exists()) mkdirs()
        }
        val outputFile = File(outputDirectory, "watermarked-pdf-${System.currentTimeMillis()}.pdf")
        val pdfDocument = PdfDocument()
        var outputPageCount = 0

        try {
            openPdfDescriptor(sourceUri)?.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    (0 until renderer.pageCount).forEach { pageIndex ->
                        renderer.openPage(pageIndex).use { sourcePage ->
                            val bitmap = renderPdfPageToBitmap(
                                page = sourcePage,
                                maxDimension = MAX_PDF_IMAGE_DIMENSION
                            )
                            try {
                                val pageInfo = PdfDocument.PageInfo.Builder(
                                    A4_WIDTH_POINTS,
                                    A4_HEIGHT_POINTS,
                                    pageIndex + 1
                                ).create()
                                val outputPage = pdfDocument.startPage(pageInfo)
                                drawImageOnA4Page(outputPage.canvas, bitmap)
                                drawWatermarkOnA4Page(
                                    canvas = outputPage.canvas,
                                    text = watermarkText,
                                    opacity = opacity,
                                    rotation = rotation,
                                    position = position
                                )
                                pdfDocument.finishPage(outputPage)
                                outputPageCount++
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            } ?: throw IllegalStateException("Unable to read selected PDF.")

            if (outputPageCount == 0) {
                throw IllegalStateException("No pages were available to watermark.")
            }

            outputFile.outputStream().use { output ->
                pdfDocument.writeTo(output)
            }
        } finally {
            pdfDocument.close()
        }

        return WatermarkOutput(
            file = outputFile,
            pageCount = outputPageCount
        )
    }

    private fun drawWatermarkOnA4Page(
        canvas: Canvas,
        text: String,
        opacity: Float,
        rotation: Float,
        position: WatermarkPosition
    ) {
        val alpha = (opacity.coerceIn(0.1f, 0.85f) * 255).toInt()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(alpha, 0, 0, 0)
            textSize = 44f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.CENTER
        }
        val pageWidth = A4_WIDTH_POINTS.toFloat()
        val pageHeight = A4_HEIGHT_POINTS.toFloat()
        if (position == WatermarkPosition.RepeatedDiagonal) {
            var y = -pageHeight * 0.2f
            while (y < pageHeight * 1.2f) {
                var x = -pageWidth * 0.25f
                while (x < pageWidth * 1.25f) {
                    canvas.save()
                    canvas.rotate(rotation, x, y)
                    canvas.drawText(text, x, y, paint)
                    canvas.restore()
                    x += 230f
                }
                y += 135f
            }
            return
        }

        val marginX = pageWidth * 0.24f
        val marginY = pageHeight * 0.12f
        val (x, y) = when (position) {
            WatermarkPosition.Center -> pageWidth / 2f to pageHeight / 2f
            WatermarkPosition.TopLeft -> marginX to marginY
            WatermarkPosition.TopRight -> pageWidth - marginX to marginY
            WatermarkPosition.BottomLeft -> marginX to pageHeight - marginY
            WatermarkPosition.BottomRight -> pageWidth - marginX to pageHeight - marginY
            WatermarkPosition.RepeatedDiagonal -> pageWidth / 2f to pageHeight / 2f
        }
        canvas.save()
        canvas.rotate(rotation, x, y)
        canvas.drawText(text, x, y, paint)
        canvas.restore()
    }

    private fun loadPdfToWord(uri: Uri) {
        lifecycleScope.launch {
            pdfToWordState = PdfToWordUiState(
                selectedUri = uri.toString(),
                selectedName = displayNameForUri(uri),
                isLoading = true,
                message = "Reading PDF."
            )

            val result = withContext(Dispatchers.IO) {
                runCatching { countPdfPages(uri) }
            }

            result.onSuccess { pageCount ->
                pdfToWordState = pdfToWordState.copy(
                    pageCount = pageCount,
                    isLoading = false,
                    message = if (pageCount > 0) {
                        "$pageCount page${if (pageCount == 1) "" else "s"} ready."
                    } else {
                        "No pages were found in this PDF."
                    }
                )
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to load PDF to Word source: ${throwable.message}")
                pdfToWordState = pdfToWordState.copy(
                    isLoading = false,
                    message = "Unable to open this PDF. It may be encrypted or invalid."
                )
            }
        }
    }

    internal fun convertPdfToWord() {
        val sourceUri = pdfToWordState.selectedUri?.let { Uri.parse(it) }
        if (sourceUri == null) {
            pdfToWordState = pdfToWordState.copy(message = "Select a PDF first.")
            return
        }

        lifecycleScope.launch {
            pdfToWordState = pdfToWordState.copy(isConverting = true, message = null)
            val sourceName = pdfToWordState.selectedName ?: "PDF Export"
            val result = runCatching {
                createPdfTextExport(
                    sourceUri = sourceUri,
                    sourceName = sourceName
                )
            }

            result.onSuccess { output ->
                viewModel.saveGeneratedPdfDocument(
                    title = "PDF to Word",
                    pageCount = output.pageCount,
                    pdfUri = Uri.fromFile(output.file),
                    extractedText = output.extractedText
                )
                pdfToWordState = PdfToWordUiState()
                showPdfToWord = false
                showPdfTools = false
                viewModel.showError("PDF text export saved successfully.")
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to convert PDF to Word text export: ${throwable.message}")
                pdfToWordState = pdfToWordState.copy(
                    isConverting = false,
                    message = "Unable to convert this PDF. It may be encrypted or invalid."
                )
            }
        }
    }

    private suspend fun createPdfTextExport(
        sourceUri: Uri,
        sourceName: String
    ): PdfTextExportOutput {
        val pageTexts = mutableListOf<String>()
        val pageCount = withContext(Dispatchers.IO) {
            openPdfDescriptor(sourceUri)?.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    (0 until renderer.pageCount).forEach { pageIndex ->
                        val bitmap = renderer.openPage(pageIndex).use { page ->
                            renderPdfPageToBitmap(
                                page = page,
                                maxDimension = MAX_PDF_IMAGE_DIMENSION
                            )
                        }
                        try {
                            val recognizedText = runCatching {
                                viewModel.recognizeText(bitmap)
                            }.onFailure { throwable ->
                                Log.w(TAG, "Unable to OCR PDF page ${pageIndex + 1}: ${throwable.message}")
                            }.getOrNull()
                            pageTexts += recognizedText.orEmpty()
                        } finally {
                            bitmap.recycle()
                        }
                    }
                    renderer.pageCount
                }
            } ?: throw IllegalStateException("Unable to read selected PDF.")
        }

        if (pageCount <= 0) {
            throw IllegalStateException("PDF has no pages.")
        }

        val text = buildString {
            appendLine("PDF to Word Text Export")
            appendLine("Source: $sourceName")
            appendLine("Generated: ${System.currentTimeMillis()}")
            appendLine()
            pageTexts.forEachIndexed { index, pageText ->
                appendLine("Page ${index + 1}")
                appendLine("=".repeat(24))
                appendLine(pageText.ifBlank { "[No text recognized on this page]" })
                appendLine()
                appendLine("-".repeat(40))
                appendLine()
            }
        }

        val outputFile = withContext(Dispatchers.IO) {
            val outputDirectory = File(filesDir, "word_exports").apply {
                if (!exists()) mkdirs()
            }
            val safeName = sanitizeFileName(sourceName.substringBeforeLast('.'))
                .ifBlank { "pdf-to-word" }
            File(outputDirectory, "$safeName-${System.currentTimeMillis()}.txt").also { file ->
                file.writeText(text)
            }
        }

        return PdfTextExportOutput(
            file = outputFile,
            pageCount = pageCount,
            extractedText = pageTexts.joinToString(separator = "\n\n").takeIf { it.isNotBlank() }
        )
    }

    private fun renderPdfToImages(uri: Uri) {
        lifecycleScope.launch {
            pdfToImagesState = PdfToImagesUiState(
                selectedUri = uri.toString(),
                selectedName = displayNameForUri(uri),
                isRendering = true,
                message = "Rendering PDF pages."
            )

            val result = withContext(Dispatchers.IO) {
                runCatching { createImagesFromPdf(uri) }
            }

            result.onSuccess { images ->
                pdfToImagesState = pdfToImagesState.copy(
                    outputPaths = images.map { it.file.absolutePath },
                    thumbnails = images.map { it.thumbnail },
                    isRendering = false,
                    savedToApp = false,
                    message = "${images.size} image${if (images.size == 1) "" else "s"} generated."
                )
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to render PDF to images: ${throwable.message}")
                pdfToImagesState = pdfToImagesState.copy(
                    isRendering = false,
                    message = "Unable to convert this PDF. It may be encrypted or invalid."
                )
            }
        }
    }

    private fun createImagesFromPdf(uri: Uri): List<PdfImageOutput> {
        val outputDirectory = File(filesDir, "pdf_images").apply {
            if (!exists()) mkdirs()
        }
        val timestamp = System.currentTimeMillis()
        return openPdfDescriptor(uri)?.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) {
                    throw IllegalStateException("PDF has no pages.")
                }
                (0 until renderer.pageCount).map { pageIndex ->
                    renderer.openPage(pageIndex).use { page ->
                        val bitmap = renderPdfPageToBitmap(
                            page = page,
                            maxDimension = MAX_PDF_IMAGE_DIMENSION
                        )
                        try {
                            val outputFile = File(
                                outputDirectory,
                                "pdf-image-$timestamp-${pageIndex + 1}.jpg"
                            )
                            outputFile.outputStream().use { output ->
                                bitmap.compress(CompressFormat.JPEG, 92, output)
                            }
                            PdfImageOutput(
                                file = outputFile,
                                thumbnail = Bitmap.createScaledBitmap(
                                    bitmap,
                                    (bitmap.width * (SPLIT_THUMBNAIL_MAX_DIMENSION / bitmap.height.toFloat()))
                                        .toInt()
                                        .coerceAtLeast(1),
                                    SPLIT_THUMBNAIL_MAX_DIMENSION,
                                    true
                                )
                            )
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        } ?: throw IllegalStateException("Unable to read selected PDF.")
    }

    internal fun sharePdfImages() {
        val files = pdfToImagesState.outputPaths.map(::File).filter { it.exists() }
        if (files.isEmpty()) {
            pdfToImagesState = pdfToImagesState.copy(message = "Generated images are missing.")
            return
        }

        val uris = ArrayList<Uri>()
        files.forEach { file ->
            fileProviderUriFor(file)?.let(uris::add)
        }
        if (uris.isEmpty()) {
            pdfToImagesState = pdfToImagesState.copy(message = "Unable to share generated images.")
            return
        }

        val imageClipData = ClipData.newUri(contentResolver, "PDF images", uris.first()).apply {
            uris.drop(1).forEach { uri -> addItem(ClipData.Item(uri)) }
        }
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "image/jpeg"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = imageClipData
        }
        runCatching {
            startActivity(Intent.createChooser(intent, "Share Images"))
        }.onFailure { exception ->
            Log.w(TAG, "Unable to share PDF images: ${exception.message}")
            pdfToImagesState = pdfToImagesState.copy(message = "No app can share these images.")
        }
    }

    internal fun savePdfImagesToApp() {
        val files = pdfToImagesState.outputPaths.map(::File).filter { it.exists() }
        if (files.isEmpty()) {
            pdfToImagesState = pdfToImagesState.copy(message = "Generated images are missing.")
            return
        }
        files.forEachIndexed { index, file ->
            viewModel.saveGeneratedPdfDocument(
                title = "PDF Image ${index + 1}",
                pageCount = 1,
                pdfUri = Uri.fromFile(file),
                extractedText = null
            )
        }
        pdfToImagesState = pdfToImagesState.copy(
            savedToApp = true,
            message = "${files.size} image${if (files.size == 1) "" else "s"} saved to app."
        )
    }

    private fun loadEditPdf(uri: Uri) {
        lifecycleScope.launch {
            val selectedName = displayNameForUri(uri)
            editPdfState = EditPdfUiState(
                selectedUri = uri.toString(),
                selectedName = selectedName,
                title = selectedName.substringBeforeLast('.').take(80).ifBlank { "Edited PDF" },
                isLoading = true,
                message = "Loading PDF pages."
            )

            val result = withContext(Dispatchers.IO) {
                runCatching { renderEditPdfPages(uri) }
            }

            result.onSuccess { pages ->
                editPdfState = editPdfState.copy(
                    pages = pages,
                    selectedPageIds = emptySet(),
                    isLoading = false,
                    message = "${pages.size} page${if (pages.size == 1) "" else "s"} ready."
                )
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to load edit PDF: ${throwable.message}")
                editPdfState = editPdfState.copy(
                    isLoading = false,
                    message = "Unable to open this PDF. It may be encrypted or invalid."
                )
            }
        }
    }

    private fun renderEditPdfPages(uri: Uri): List<EditPdfPage> {
        return openPdfDescriptor(uri)?.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                if (renderer.pageCount == 0) {
                    throw IllegalStateException("PDF has no pages.")
                }
                (0 until renderer.pageCount).map { pageIndex ->
                    renderer.openPage(pageIndex).use { page ->
                        EditPdfPage(
                            id = "page-$pageIndex-${System.nanoTime()}",
                            sourcePageIndex = pageIndex,
                            thumbnail = renderPdfPageToBitmap(
                                page = page,
                                maxDimension = SPLIT_THUMBNAIL_MAX_DIMENSION
                            )
                        )
                    }
                }
            }
        } ?: throw IllegalStateException("Unable to read selected PDF.")
    }

    internal fun toggleEditPdfPage(pageId: String) {
        editPdfState = editPdfState.copy(
            selectedPageIds = if (pageId in editPdfState.selectedPageIds) {
                editPdfState.selectedPageIds - pageId
            } else {
                editPdfState.selectedPageIds + pageId
            }
        )
    }

    internal fun moveEditPdfPage(index: Int, direction: Int) {
        val pages = editPdfState.pages.toMutableList()
        val targetIndex = index + direction
        if (index !in pages.indices || targetIndex !in pages.indices) return
        val page = pages.removeAt(index)
        pages.add(targetIndex, page)
        editPdfState = editPdfState.copy(pages = pages)
    }

    internal fun deleteSelectedEditPdfPages() {
        if (editPdfState.selectedPageIds.isEmpty()) {
            editPdfState = editPdfState.copy(message = "Select pages to delete.")
            return
        }
        val remainingPages = editPdfState.pages.filterNot { it.id in editPdfState.selectedPageIds }
        if (remainingPages.isEmpty()) {
            editPdfState = editPdfState.copy(message = "At least one page must remain.")
            return
        }
        editPdfState = editPdfState.copy(
            pages = remainingPages,
            selectedPageIds = emptySet(),
            message = "Selected pages deleted."
        )
    }

    internal fun rotateSelectedEditPdfPages() {
        if (editPdfState.selectedPageIds.isEmpty()) {
            editPdfState = editPdfState.copy(message = "Select pages to rotate.")
            return
        }
        editPdfState = editPdfState.copy(
            pages = editPdfState.pages.map { page ->
                if (page.id in editPdfState.selectedPageIds) {
                    page.copy(rotation = (page.rotation + 90) % 360)
                } else {
                    page
                }
            },
            message = "Selected pages rotated."
        )
    }

    internal fun duplicateSelectedEditPdfPages() {
        val selectedIds = editPdfState.selectedPageIds
        if (selectedIds.isEmpty()) {
            editPdfState = editPdfState.copy(message = "Select pages to duplicate.")
            return
        }
        val nextPages = mutableListOf<EditPdfPage>()
        editPdfState.pages.forEach { page ->
            nextPages += page
            if (page.id in selectedIds) {
                nextPages += page.copy(
                    id = "copy-${page.sourcePageIndex}-${System.nanoTime()}",
                    thumbnail = page.thumbnail.copy(Bitmap.Config.ARGB_8888, false)
                )
            }
        }
        editPdfState = editPdfState.copy(
            pages = nextPages,
            selectedPageIds = emptySet(),
            message = "Selected pages duplicated."
        )
    }

    internal fun saveEditedPdf() {
        val sourceUri = editPdfState.selectedUri?.let { Uri.parse(it) }
        val title = editPdfState.title.trim().ifBlank { "Edited PDF" }.take(80)
        if (sourceUri == null) {
            editPdfState = editPdfState.copy(message = "Select a PDF first.")
            return
        }
        if (editPdfState.pages.isEmpty()) {
            editPdfState = editPdfState.copy(message = "No pages are available to save.")
            return
        }

        lifecycleScope.launch {
            editPdfState = editPdfState.copy(isSaving = true, message = null)
            val pages = editPdfState.pages
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    createEditedPdf(
                        sourceUri = sourceUri,
                        pages = pages
                    )
                }
            }

            result.onSuccess { output ->
                viewModel.saveGeneratedPdfDocument(
                    title = title,
                    pageCount = output.pageCount,
                    pdfUri = Uri.fromFile(output.file),
                    extractedText = null
                )
                editPdfState = EditPdfUiState()
                showEditPdf = false
                showPdfTools = false
                viewModel.showError("Edited PDF saved successfully.")
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to save edited PDF: ${throwable.message}")
                editPdfState = editPdfState.copy(
                    isSaving = false,
                    message = "Unable to save this PDF. It may be encrypted or invalid."
                )
            }
        }
    }

    private fun createEditedPdf(
        sourceUri: Uri,
        pages: List<EditPdfPage>
    ): EditedPdfOutput {
        val outputDirectory = File(filesDir, "edited_pdfs").apply {
            if (!exists()) mkdirs()
        }
        val outputFile = File(outputDirectory, "edited-pdf-${System.currentTimeMillis()}.pdf")
        val pdfDocument = PdfDocument()
        var outputPageCount = 0

        try {
            openPdfDescriptor(sourceUri)?.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    pages.forEachIndexed { outputIndex, editPage ->
                        if (editPage.sourcePageIndex !in 0 until renderer.pageCount) return@forEachIndexed
                        renderer.openPage(editPage.sourcePageIndex).use { sourcePage ->
                            val bitmap = renderPdfPageToBitmap(
                                page = sourcePage,
                                maxDimension = MAX_PDF_IMAGE_DIMENSION
                            )
                            try {
                                val pageInfo = PdfDocument.PageInfo.Builder(
                                    A4_WIDTH_POINTS,
                                    A4_HEIGHT_POINTS,
                                    outputIndex + 1
                                ).create()
                                val outputPage = pdfDocument.startPage(pageInfo)
                                drawEditedPageOnA4Page(
                                    canvas = outputPage.canvas,
                                    bitmap = bitmap,
                                    rotation = editPage.rotation
                                )
                                pdfDocument.finishPage(outputPage)
                                outputPageCount++
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            } ?: throw IllegalStateException("Unable to read selected PDF.")

            if (outputPageCount == 0) {
                throw IllegalStateException("No pages were available to save.")
            }

            outputFile.outputStream().use { output ->
                pdfDocument.writeTo(output)
            }
        } finally {
            pdfDocument.close()
        }

        return EditedPdfOutput(
            file = outputFile,
            pageCount = outputPageCount
        )
    }

    private fun drawEditedPageOnA4Page(
        canvas: Canvas,
        bitmap: Bitmap,
        rotation: Int
    ) {
        if (rotation % 360 == 0) {
            drawImageOnA4Page(canvas, bitmap)
            return
        }
        canvas.drawColor(Color.WHITE)
        val pageWidth = A4_WIDTH_POINTS.toFloat()
        val pageHeight = A4_HEIGHT_POINTS.toFloat()
        canvas.save()
        canvas.rotate(rotation.toFloat(), pageWidth / 2f, pageHeight / 2f)
        val rotatedWidth = if (rotation % 180 == 0) pageWidth else pageHeight
        val rotatedHeight = if (rotation % 180 == 0) pageHeight else pageWidth
        val scale = minOf(
            rotatedWidth / bitmap.width.toFloat(),
            rotatedHeight / bitmap.height.toFloat()
        )
        val imageWidth = bitmap.width * scale
        val imageHeight = bitmap.height * scale
        val left = (pageWidth - imageWidth) / 2f
        val top = (pageHeight - imageHeight) / 2f
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(left, top, left + imageWidth, top + imageHeight),
            Paint(Paint.ANTI_ALIAS_FLAG)
        )
        canvas.restore()
    }

    private fun createSplitPdf(
        sourceUri: Uri,
        pageIndices: List<Int>,
        outputIndex: Int
    ): SplitOutput {
        val outputDirectory = File(filesDir, "split_pdfs").apply {
            if (!exists()) mkdirs()
        }
        val outputFile = File(outputDirectory, "split-pdf-${System.currentTimeMillis()}-$outputIndex.pdf")
        val pdfDocument = PdfDocument()

        try {
            openPdfDescriptor(sourceUri)?.use { descriptor ->
                PdfRenderer(descriptor).use { renderer ->
                    pageIndices.forEachIndexed { outputPageIndex, sourcePageIndex ->
                        if (sourcePageIndex !in 0 until renderer.pageCount) return@forEachIndexed
                        renderer.openPage(sourcePageIndex).use { sourcePage ->
                            val bitmap = renderPdfPageToBitmap(
                                page = sourcePage,
                                maxDimension = MAX_PDF_IMAGE_DIMENSION
                            )
                            try {
                                val pageInfo = PdfDocument.PageInfo.Builder(
                                    A4_WIDTH_POINTS,
                                    A4_HEIGHT_POINTS,
                                    outputPageIndex + 1
                                ).create()
                                val outputPage = pdfDocument.startPage(pageInfo)
                                drawImageOnA4Page(outputPage.canvas, bitmap)
                                pdfDocument.finishPage(outputPage)
                            } finally {
                                bitmap.recycle()
                            }
                        }
                    }
                }
            } ?: throw IllegalStateException("Unable to read selected PDF.")

            outputFile.outputStream().use { output ->
                pdfDocument.writeTo(output)
            }
        } finally {
            pdfDocument.close()
        }

        return SplitOutput(file = outputFile, pageCount = pageIndices.size)
    }

    private fun renderPdfPageToBitmap(
        page: PdfRenderer.Page,
        maxDimension: Int
    ): Bitmap {
        val scale = minOf(
            maxDimension / page.width.toFloat(),
            maxDimension / page.height.toFloat()
        ).coerceAtMost(1.8f).coerceAtLeast(0.2f)
        val width = (page.width * scale).toInt().coerceAtLeast(1)
        val height = (page.height * scale).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(
            bitmap,
            null,
            null,
            PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
        )
        return bitmap
    }

    private fun openPdfDescriptor(uri: Uri): ParcelFileDescriptor? {
        return when (uri.scheme) {
            "content" -> contentResolver.openFileDescriptor(uri, "r")
            "file" -> ParcelFileDescriptor.open(
                File(requireNotNull(uri.path)),
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            null, "" -> ParcelFileDescriptor.open(
                File(uri.toString()),
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            else -> null
        }
    }

    private fun parsePageRange(
        input: String,
        pageCount: Int
    ): List<Int> {
        if (input.isBlank()) return emptyList()
        return input.split(",")
            .flatMap { part ->
                val trimmed = part.trim()
                when {
                    "-" in trimmed -> {
                        val bounds = trimmed.split("-", limit = 2)
                        val start = bounds.getOrNull(0)?.trim()?.toIntOrNull()
                        val end = bounds.getOrNull(1)?.trim()?.toIntOrNull()
                        if (start == null || end == null) {
                            emptyList()
                        } else {
                            val range = if (start <= end) start..end else end..start
                            range.map { it - 1 }
                        }
                    }
                    else -> listOfNotNull(trimmed.toIntOrNull()?.minus(1))
                }
            }
            .filter { it in 0 until pageCount }
            .distinct()
            .sorted()
    }

    internal fun convertImagesToPdf() {
        val imageUris = imagesToPdfState.imageUris.mapNotNull { uriValue ->
            runCatching { Uri.parse(uriValue) }.getOrNull()
        }
        if (imageUris.isEmpty()) {
            imagesToPdfState = imagesToPdfState.copy(message = "Select at least one image.")
            return
        }

        lifecycleScope.launch {
            imagesToPdfState = imagesToPdfState.copy(isConverting = true, message = null)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    generatePdfFromImages(imageUris)
                }
            }

            result.onSuccess { outputFile ->
                val extractedText = imageUris.mapNotNull { imageUri ->
                    runCatching {
                        viewModel.recognizeText(this@MainActivity, imageUri)
                    }.onFailure { throwable ->
                        Log.w(TAG, "Unable to OCR image for PDF: ${throwable.message}")
                    }.getOrNull()?.takeIf { it.isNotBlank() }
                }.joinToString(separator = "\n\n")

                viewModel.saveGeneratedPdfDocument(
                    title = "Images to PDF",
                    pageCount = imageUris.size,
                    pdfUri = Uri.fromFile(outputFile),
                    extractedText = extractedText
                )
                imagesToPdfState = ImagesToPdfUiState(
                    message = "Images to PDF saved successfully."
                )
                showImagesToPdf = false
                showPdfTools = false
                viewModel.showError("Images to PDF saved successfully.")
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to convert images to PDF: ${throwable.message}")
                imagesToPdfState = imagesToPdfState.copy(
                    isConverting = false,
                    message = "Unable to convert selected images."
                )
            }
        }
    }

    private fun generatePdfFromImages(imageUris: List<Uri>): File {
        val outputDirectory = File(filesDir, "generated_pdfs").apply {
            if (!exists()) mkdirs()
        }
        val outputFile = File(outputDirectory, "images-to-pdf-${System.currentTimeMillis()}.pdf")
        val pdfDocument = PdfDocument()

        try {
            imageUris.forEachIndexed { index, imageUri ->
                val bitmap = decodeBitmapForPdf(imageUri)
                    ?: throw IllegalStateException("Unable to read selected image.")
                try {
                    val pageInfo = PdfDocument.PageInfo.Builder(A4_WIDTH_POINTS, A4_HEIGHT_POINTS, index + 1)
                        .create()
                    val page = pdfDocument.startPage(pageInfo)
                    drawImageOnA4Page(page.canvas, bitmap)
                    pdfDocument.finishPage(page)
                } finally {
                    bitmap.recycle()
                }
            }

            outputFile.outputStream().use { output ->
                pdfDocument.writeTo(output)
            }
        } finally {
            pdfDocument.close()
        }

        return outputFile
    }

    private fun decodeBitmapForPdf(uri: Uri): Bitmap? {
        val boundsOptions = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, boundsOptions)
        }
        val sampleSize = calculatePdfImageSampleSize(boundsOptions)
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }
        return contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, decodeOptions)
        }
    }

    private fun calculatePdfImageSampleSize(options: BitmapFactory.Options): Int {
        var sampleSize = 1
        var width = options.outWidth
        var height = options.outHeight
        while (width / sampleSize > MAX_PDF_IMAGE_DIMENSION ||
            height / sampleSize > MAX_PDF_IMAGE_DIMENSION
        ) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun drawImageOnA4Page(
        canvas: Canvas,
        bitmap: Bitmap
    ) {
        canvas.drawColor(Color.WHITE)
        val pageWidth = A4_WIDTH_POINTS.toFloat()
        val pageHeight = A4_HEIGHT_POINTS.toFloat()
        val scale = minOf(
            pageWidth / bitmap.width.toFloat(),
            pageHeight / bitmap.height.toFloat()
        )
        val imageWidth = bitmap.width * scale
        val imageHeight = bitmap.height * scale
        val left = (pageWidth - imageWidth) / 2f
        val top = (pageHeight - imageHeight) / 2f
        val destination = RectF(left, top, left + imageWidth, top + imageHeight)
        canvas.drawBitmap(bitmap, null, destination, Paint(Paint.ANTI_ALIAS_FLAG))
    }

    private fun displayNameForUri(uri: Uri): String {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)
                } else {
                    null
                }
            }
        }.getOrNull() ?: uri.lastPathSegment ?: "Selected PDF"
    }

    private fun sizeForUri(uri: Uri): Long? {
        return runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (sizeIndex >= 0 && cursor.moveToFirst() && !cursor.isNull(sizeIndex)) {
                    cursor.getLong(sizeIndex)
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    private fun openPdf(document: DocumentEntity) {
        val pdfUri = getReadablePdfUri(document.localPdfUri)
        if (pdfUri == null) {
            viewModel.showError("PDF file is missing.")
            return
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(pdfUri, PDF_MIME_TYPE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, document.title, pdfUri)
        }

        try {
            startActivity(intent)
            analyticsRepository.trackEvent(
                AnalyticsRepository.EVENT_PDF_OPENED,
                mapOf(
                    "page_count" to document.pageCount,
                    "is_favorite" to document.isFavorite,
                    "is_pinned" to document.isPinned
                )
            )
        } catch (exception: ActivityNotFoundException) {
            Log.w(TAG, "No app available to open PDF: ${exception.message}")
            viewModel.showError("No app is available to open this PDF.")
        } catch (exception: Throwable) {
            Log.w(TAG, "Unable to open PDF: ${exception.message}")
            recordFailure("pdf_open", exception)
            viewModel.showError("Unable to open this PDF.")
        }
    }

    internal fun editPdfDocument(document: DocumentEntity) {
        val pdfUri = getReadablePdfUri(document.localPdfUri)
        if (pdfUri == null || !canOpenPdf(pdfUri)) {
            viewModel.showError(PDF_ONLY_ACTION_MESSAGE)
            return
        }
        returnToPdfToolsAfterEdit = false
        showEditPdf = true
        showPdfTools = false
        loadEditPdf(pdfUri)
    }

    internal fun sendDocumentToPc(document: DocumentEntity) {
        shareDocumentViaFileProvider(
            document = document,
            chooserTitle = "Send to PC / Share"
        )
    }

    internal fun saveDocumentExport(document: DocumentEntity) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val sourceUri = readableUriFor(document.localPdfUri)
                        ?: error("Document file is missing.")
                    val exportsDirectory = File(filesDir, "exports").apply {
                        if (!exists()) mkdirs()
                    }
                    val exportFile = uniqueFile(
                        directory = exportsDirectory,
                        baseName = sanitizeFileName(document.title),
                        extension = extensionForDocument(document)
                    )
                    copyUriToFile(sourceUri, exportFile)
                    exportFile
                }
            }

            result.onSuccess {
                viewModel.showError("Saved to app exports: ${it.name}")
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to save document export: ${throwable.message}")
                viewModel.showError(throwable.message ?: "Unable to save document export.")
            }
        }
    }

    internal fun printDocument(document: DocumentEntity) {
        val pdfUri = getReadablePdfUri(document.localPdfUri)
        if (pdfUri == null || !canOpenPdf(pdfUri)) {
            viewModel.showError(PDF_ONLY_ACTION_MESSAGE)
            return
        }
        try {
            val printManager = getSystemService(PrintManager::class.java)
            printManager.print(
                sanitizeFileName(document.title).ifBlank { "DocScanner PDF" },
                PdfUriPrintAdapter(pdfUri, sanitizeFileName(document.title)),
                PrintAttributes.Builder()
                    .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                    .setColorMode(PrintAttributes.COLOR_MODE_COLOR)
                    .build()
            )
        } catch (exception: Throwable) {
            Log.w(TAG, "Unable to print PDF: ${exception.message}")
            viewModel.showError("Unable to print this PDF.")
        }
    }

    internal fun sharePdf(document: DocumentEntity) {
        shareDocumentViaFileProvider(
            document = document,
            chooserTitle = "Share PDF"
        )
    }

    private fun shareDocumentViaFileProvider(
        document: DocumentEntity,
        chooserTitle: String
    ) {
        val shareFile = runCatching {
            createShareableDocumentCopy(document)
        }.onFailure { exception ->
            Log.w(TAG, "Unable to prepare shareable document: ${exception.message}")
        }.getOrNull()

        if (shareFile == null) {
            viewModel.showError("Document file is missing.")
            return
        }

        val pdfUri = fileProviderUriFor(shareFile)
        if (pdfUri == null) {
            viewModel.showError("Unable to share this document.")
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeTypeForDocument(document)
            putExtra(Intent.EXTRA_STREAM, pdfUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, shareFile.name, pdfUri)
        }

        try {
            startActivity(Intent.createChooser(intent, chooserTitle))
            analyticsRepository.trackEvent(
                AnalyticsRepository.EVENT_PDF_SHARED,
                mapOf("document_count" to 1, "page_count" to document.pageCount)
            )
        } catch (exception: ActivityNotFoundException) {
            Log.w(TAG, "No app available to share document: ${exception.message}")
            viewModel.showError("No app is available to share this document.")
        } catch (exception: Throwable) {
            Log.w(TAG, "Unable to share document: ${exception.message}")
            recordFailure("pdf_share", exception, mapOf("document_count" to "1"))
            viewModel.showError("Unable to share this document.")
        }
    }

    internal fun convertImageDocumentToPdf(document: DocumentEntity) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val imageUri = readableUriFor(document.localPdfUri)
                        ?: error("Image file is missing.")
                    val bitmap = decodeBitmapForPdf(imageUri)
                        ?: error("Unable to read this image.")
                    val outputDirectory = File(filesDir, "generated_pdfs").apply {
                        if (!exists()) mkdirs()
                    }
                    val outputFile = uniqueFile(
                        directory = outputDirectory,
                        baseName = "${sanitizeFileName(document.title)} PDF",
                        extension = ".pdf"
                    )
                    val pdfDocument = PdfDocument()
                    try {
                        val pageInfo = PdfDocument.PageInfo.Builder(
                            A4_WIDTH_POINTS,
                            A4_HEIGHT_POINTS,
                            1
                        ).create()
                        val page = pdfDocument.startPage(pageInfo)
                        drawImageOnA4Page(page.canvas, bitmap)
                        pdfDocument.finishPage(page)
                        outputFile.outputStream().use { output ->
                            pdfDocument.writeTo(output)
                        }
                    } finally {
                        pdfDocument.close()
                    }
                    bitmap.recycle()
                    outputFile
                }
            }

            result.onSuccess { outputFile ->
                val generatedDocument = DocumentEntity(
                    title = "${document.title} PDF",
                    timestamp = System.currentTimeMillis(),
                    pageCount = 1,
                    localPdfUri = Uri.fromFile(outputFile).toString(),
                    extractedText = document.extractedText
                )
                viewModel.saveGeneratedPdfDocument(generatedDocument) { savedDocument ->
                    pdfViewerDocument = savedDocument
                    viewModel.showError("Converted image to PDF.")
                }
            }.onFailure { throwable ->
                Log.w(TAG, "Unable to convert image to PDF: ${throwable.message}")
                viewModel.showError(throwable.message ?: "Unable to convert image to PDF.")
            }
        }
    }

    private fun sharePdfLegacy(document: DocumentEntity) {
        val pdfUri = getReadablePdfUri(document.localPdfUri)
        if (pdfUri == null) {
            viewModel.showError("PDF file is missing.")
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = PDF_MIME_TYPE
            putExtra(Intent.EXTRA_STREAM, pdfUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, document.title, pdfUri)
        }

        try {
            startActivity(Intent.createChooser(intent, "Share PDF"))
            analyticsRepository.trackEvent(
                AnalyticsRepository.EVENT_PDF_SHARED,
                mapOf("document_count" to 1, "page_count" to document.pageCount)
            )
        } catch (exception: ActivityNotFoundException) {
            Log.w(TAG, "No app available to share PDF: ${exception.message}")
            viewModel.showError("No app is available to share this PDF.")
        } catch (exception: Throwable) {
            Log.w(TAG, "Unable to share PDF: ${exception.message}")
            recordFailure("pdf_share", exception, mapOf("document_count" to "1"))
            viewModel.showError("Unable to share this PDF.")
        }
    }

    internal fun sharePdfs(documents: List<DocumentEntity>) {
        val uriPairs = documents.mapNotNull { document ->
            getReadablePdfUri(document.localPdfUri)?.let { uri -> document.title to uri }
        }
        if (uriPairs.isEmpty()) {
            viewModel.showError("Selected PDF files are missing.")
            return
        }

        val uris = ArrayList(uriPairs.map { it.second })
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = PDF_MIME_TYPE
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(contentResolver, uriPairs.first().first, uriPairs.first().second).apply {
                uriPairs.drop(1).forEach { (title, uri) ->
                    addItem(ClipData.Item(uri))
                }
            }
        }

        try {
            startActivity(Intent.createChooser(intent, "Share PDFs"))
            analyticsRepository.trackEvent(
                AnalyticsRepository.EVENT_PDF_SHARED,
                mapOf(
                    "document_count" to uriPairs.size,
                    "page_count" to documents.sumOf { it.pageCount }
                )
            )
        } catch (exception: ActivityNotFoundException) {
            Log.w(TAG, "No app available to share PDFs: ${exception.message}")
            viewModel.showError("No app is available to share these PDFs.")
        } catch (exception: Throwable) {
            Log.w(TAG, "Unable to share PDFs: ${exception.message}")
            recordFailure("pdf_share", exception, mapOf("document_count" to uriPairs.size.toString()))
            viewModel.showError("Unable to share selected PDFs.")
        }
    }

    internal fun shareExtractedText(document: DocumentEntity) {
        val text = document.extractedText.orEmpty()
        if (text.isBlank()) {
            viewModel.showError("No OCR text is available for this document.")
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = TEXT_MIME_TYPE
            putExtra(Intent.EXTRA_SUBJECT, document.title)
            putExtra(Intent.EXTRA_TEXT, text)
        }

        try {
            startActivity(Intent.createChooser(intent, "Share text"))
        } catch (exception: ActivityNotFoundException) {
            Log.w(TAG, "No app available to share text: ${exception.message}")
            viewModel.showError("No app is available to share this text.")
        } catch (exception: Throwable) {
            Log.w(TAG, "Unable to share text: ${exception.message}")
            viewModel.showError("Unable to share this text.")
        }
    }

    internal fun exportTextDocument(document: DocumentEntity) {
        exportText(
            title = document.title,
            text = document.extractedText.orEmpty()
        )
    }

    internal fun shareCleanedText(
        title: String,
        text: String
    ) {
        if (text.isBlank()) {
            viewModel.showError("No OCR text available to clean.")
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = TEXT_MIME_TYPE
            putExtra(Intent.EXTRA_SUBJECT, title)
            putExtra(Intent.EXTRA_TEXT, text)
        }

        try {
            startActivity(Intent.createChooser(intent, "Share cleaned text"))
        } catch (exception: ActivityNotFoundException) {
            Log.w(TAG, "No app available to share cleaned text: ${exception.message}")
            viewModel.showError("No app is available to share this text.")
        } catch (exception: Throwable) {
            Log.w(TAG, "Unable to share cleaned text: ${exception.message}")
            viewModel.showError("Unable to share this text.")
        }
    }

    internal fun exportCleanedText(
        title: String,
        text: String,
        extension: String
    ) {
        if (text.isBlank()) {
            viewModel.showError("No OCR text available to clean.")
            return
        }
        val safeExtension = if (extension.equals("doc", ignoreCase = true)) "doc" else "txt"
        exportTextFile(
            title = "${title}-cleaned",
            text = text,
            extension = safeExtension,
            mimeType = if (safeExtension == "doc") DOC_MIME_TYPE else TEXT_MIME_TYPE,
            chooserTitle = if (safeExtension == "doc") "Export DOC" else "Export TXT"
        )
    }

    internal fun exportText(
        title: String,
        text: String
    ) {
        exportTextFile(
            title = title,
            text = text,
            extension = "txt",
            mimeType = TEXT_MIME_TYPE,
            chooserTitle = "Export text"
        )
    }

    private fun exportTextFile(
        title: String,
        text: String,
        extension: String,
        mimeType: String,
        chooserTitle: String
    ) {
        if (text.isBlank()) {
            viewModel.showError("No OCR text is available to export.")
            return
        }

        try {
            val exportDirectory = File(cacheDir, "text_exports").apply {
                if (!exists()) mkdirs()
            }
            val exportFile = File(exportDirectory, "${sanitizeFileName(title)}.$extension")
            exportFile.writeText(text)
            val exportUri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                exportFile
            )

            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType
                putExtra(Intent.EXTRA_STREAM, exportUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                clipData = ClipData.newUri(contentResolver, exportFile.name, exportUri)
            }
            startActivity(Intent.createChooser(intent, chooserTitle))
        } catch (exception: ActivityNotFoundException) {
            Log.w(TAG, "No app available to export text: ${exception.message}")
            viewModel.showError("No app is available to export this text.")
        } catch (exception: Throwable) {
            Log.w(TAG, "Unable to export text: ${exception.message}")
            viewModel.showError("Unable to export this text.")
        }
    }

    private fun sanitizeFileName(title: String): String {
        val sanitized = title.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), " ")
            .take(80)
            .trim()
        return sanitized.ifBlank { "document-text" }
    }

    internal fun deleteDocument(document: DocumentEntity) {
        viewModel.deleteDocument(document)
    }

    private fun readableUriFor(uriValue: String): Uri? {
        val parsedUri = runCatching { Uri.parse(uriValue) }.getOrNull() ?: return null
        return when (parsedUri.scheme) {
            "content" -> parsedUri.takeIf { canReadUri(it) }
            "file" -> fileProviderUriFor(File(requireNotNull(parsedUri.path)))
            null, "" -> fileProviderUriFor(File(uriValue))
            else -> null
        }
    }

    private fun getReadablePdfUri(uriValue: String): Uri? {
        return readableUriFor(uriValue)
    }

    private fun canOpenPdf(uri: Uri): Boolean {
        return openPdfDescriptor(uri)?.use { descriptor ->
            runCatching {
                PdfRenderer(descriptor).use { renderer -> renderer.pageCount > 0 }
            }.getOrDefault(false)
        } == true
    }

    private fun createShareableDocumentCopy(document: DocumentEntity): File {
        val sourceUri = readableUriFor(document.localPdfUri) ?: error("Document file is missing.")
        val shareDirectory = File(cacheDir, "document_shares").apply {
            if (!exists()) mkdirs()
        }
        val shareFile = File(
            shareDirectory,
            "${sanitizeFileName(document.title)}-${document.id}${extensionForDocument(document)}"
        )
        copyUriToFile(sourceUri, shareFile)
        return shareFile
    }

    private fun copyUriToFile(sourceUri: Uri, destination: File) {
        contentResolver.openInputStream(sourceUri).use { input ->
            requireNotNull(input) { "Unable to read selected document." }
            destination.outputStream().use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun extensionForDocument(document: DocumentEntity): String {
        val lower = document.localPdfUri.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> ".jpg"
            lower.endsWith(".png") -> ".png"
            else -> ".pdf"
        }
    }

    private fun mimeTypeForDocument(document: DocumentEntity): String {
        val lower = document.localPdfUri.lowercase()
        return when {
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".png") -> "image/png"
            lower.contains("/imported_images/") -> "image/jpeg"
            else -> PDF_MIME_TYPE
        }
    }

    private fun uniqueFile(
        directory: File,
        baseName: String,
        extension: String
    ): File {
        val cleanBaseName = baseName.ifBlank { "document" }
        var candidate = File(directory, "$cleanBaseName$extension")
        var index = 1
        while (candidate.exists()) {
            candidate = File(directory, "$cleanBaseName ($index)$extension")
            index++
        }
        return candidate
    }

    private fun fileProviderUriFor(file: File): Uri? {
        if (!file.exists()) return null
        return runCatching {
            FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
        }.onFailure { exception ->
            Log.w(TAG, "Unable to create FileProvider URI: ${exception.message}")
        }.getOrNull()
    }

    private fun canReadUri(uri: Uri): Boolean {
        return runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } == true
        }.getOrDefault(false)
    }

    private fun deleteLocalPdfIfPresent(uriValue: String): DeleteLocalPdfResult {
        val parsedUri = runCatching { Uri.parse(uriValue) }.getOrNull()
            ?: return DeleteLocalPdfResult.NotPresent

        return when (parsedUri.scheme) {
            "file" -> deleteFileIfPresent(File(requireNotNull(parsedUri.path)))
            null, "" -> deleteFileIfPresent(File(uriValue))
            "content" -> runCatching {
                val deletedRows = contentResolver.delete(parsedUri, null, null)
                if (deletedRows > 0) DeleteLocalPdfResult.Deleted else DeleteLocalPdfResult.NotPresent
            }.onFailure { exception ->
                Log.w(TAG, "Unable to delete content URI PDF: ${exception.message}")
            }.getOrDefault(DeleteLocalPdfResult.Failed)
            else -> DeleteLocalPdfResult.NotPresent
        }
    }

    private fun deleteFileIfPresent(file: File): DeleteLocalPdfResult {
        if (!file.exists()) return DeleteLocalPdfResult.NotPresent
        return if (file.delete()) {
            DeleteLocalPdfResult.Deleted
        } else {
            DeleteLocalPdfResult.Failed
        }
    }

    private enum class DeleteLocalPdfResult {
        Deleted,
        NotPresent,
        Failed
    }

    private inner class PdfUriPrintAdapter(
        private val sourceUri: Uri,
        private val documentTitle: String
    ) : PrintDocumentAdapter() {
        override fun onLayout(
            oldAttributes: PrintAttributes?,
            newAttributes: PrintAttributes?,
            cancellationSignal: CancellationSignal?,
            callback: LayoutResultCallback,
            extras: Bundle?
        ) {
            if (cancellationSignal?.isCanceled == true) {
                callback.onLayoutCancelled()
                return
            }
            val info = PrintDocumentInfo.Builder("${sanitizeFileName(documentTitle)}.pdf")
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                .build()
            callback.onLayoutFinished(info, true)
        }

        override fun onWrite(
            pages: Array<out PageRange>?,
            destination: ParcelFileDescriptor?,
            cancellationSignal: CancellationSignal?,
            callback: WriteResultCallback
        ) {
            if (destination == null) {
                callback.onWriteFailed("Print destination is unavailable.")
                return
            }
            try {
                if (cancellationSignal?.isCanceled == true) {
                    callback.onWriteCancelled()
                    return
                }
                contentResolver.openInputStream(sourceUri).use { input ->
                    requireNotNull(input) { "Unable to read PDF for printing." }
                    FileOutputStream(destination.fileDescriptor).use { output ->
                        input.copyTo(output)
                    }
                }
                callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
            } catch (throwable: Throwable) {
                Log.w(TAG, "Unable to write PDF print job: ${throwable.message}")
                callback.onWriteFailed(throwable.message ?: "Unable to print PDF.")
            }
        }
    }


    private companion object {
        const val TAG = "MainActivity"

        /** Logcat tag for the app-owned Main Scanner flow. Debug-only, content-free lines. */
        const val MAIN_SCAN_TAG = "MainScanCapture"
        const val PDF_MIME_TYPE = "application/pdf"
        const val TEXT_MIME_TYPE = "text/plain"
        const val DOC_MIME_TYPE = "application/msword"
        const val PDF_ONLY_ACTION_MESSAGE = "Available for PDF documents only."
        const val DEFAULT_SCAN_TITLE_PREFIX = "Scan"
        const val ID_CARD_SCAN_TITLE_PREFIX = "ID Card Scan"
        const val APP_LOCK_TIMEOUT_MS = 5L * 60L * 1000L
        const val ROOM_MIGRATION_STATUS = "Registered 1->2->3->4->5->6"
        const val A4_WIDTH_POINTS = 595
        const val A4_HEIGHT_POINTS = 842
        const val MAX_PDF_IMAGE_DIMENSION = 1800
        const val SPLIT_THUMBNAIL_MAX_DIMENSION = 360
        val DANGEROUS_PERMISSION_NAMES = setOf(
            "android.permission.CAMERA",
            "android.permission.READ_EXTERNAL_STORAGE",
            "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.READ_MEDIA_IMAGES",
            "android.permission.READ_MEDIA_VIDEO",
            "android.permission.RECORD_AUDIO",
            "android.permission.ACCESS_FINE_LOCATION",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.READ_CONTACTS",
            "android.permission.WRITE_CONTACTS",
            "android.permission.READ_CALENDAR",
            "android.permission.WRITE_CALENDAR",
            "android.permission.READ_SMS",
            "android.permission.SEND_SMS",
            "android.permission.CALL_PHONE"
        )
        const val DRIVE_APPDATA_SCOPE = "https://www.googleapis.com/auth/drive.appdata"
    }
}
