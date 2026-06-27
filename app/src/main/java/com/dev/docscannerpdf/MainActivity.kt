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
import androidx.activity.compose.BackHandler
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
import com.dev.docscannerpdf.domain.pdf.PdfExportPageInput
import com.dev.docscannerpdf.domain.pdf.PdfExportService
import com.dev.docscannerpdf.domain.pdf.PdfRenderHelper
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : FragmentActivity() {

    internal val viewModel: ScannerViewModel by viewModels {
        val app = application as DocScannerPdfApplication
        ScannerViewModelFactory(app.repository, app.folderRepository, app.tagRepository, app.analyticsRepository)
    }
    private var pendingScanTitlePrefix = DEFAULT_SCAN_TITLE_PREFIX
    private var pendingScanIsIdCardScan = false
    private val processDocumentUseCase = ProcessDocumentUseCase()
    private val scannerFlowValidationUseCase = ScannerFlowValidationUseCase()
    private val pdfExportService by lazy { PdfExportService(applicationContext) }
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
    internal var showDocumentLibrary by mutableStateOf(false)
    internal var documentLibraryQuery by mutableStateOf("")
    internal var documentLibrarySort by mutableStateOf(DocumentLibrarySort.NEWEST)
    internal var libraryPendingRename by mutableStateOf<DocumentEntity?>(null)
    internal var libraryPendingDelete by mutableStateOf<DocumentEntity?>(null)
    internal var pdfToolsMessage by mutableStateOf<String?>(null)
    internal var pdfViewerDocument by mutableStateOf<DocumentEntity?>(null)
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
        if (result.resultCode == Activity.RESULT_OK) {
            val scanResult = GmsDocumentScanningResult.fromActivityResultIntent(result.data)
            val previewPageUri = scanResult?.pages?.firstOrNull()?.imageUri
            if (previewPageUri != null) {
                imageImportReview = null
                pendingImageImport = null
                imageEditorMessage = null
                scannerBackendProcessingState = ScannerBackendProcessingState.Idle
                scannerFlowValidationState = ScannerFlowValidationState()
                documentResultState = null
                importedImagePreview = PendingImageImport(
                    imageUri = previewPageUri,
                    title = "$pendingScanTitlePrefix ${SimpleDateFormat("dd-MM-yyyy HH.mm", Locale.getDefault()).format(Date())}"
                )
            }
            viewModel.handleScanResult(this, scanResult, pendingScanTitlePrefix, pendingScanIsIdCardScan)
        } else {
            viewModel.showError("Scan canceled.")
        }
        pendingScanTitlePrefix = DEFAULT_SCAN_TITLE_PREFIX
        pendingScanIsIdCardScan = false
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

    override fun onBackPressed() {
        if (canHandleSystemBack()) {
            handleSystemBack()
        } else {
            super.onBackPressed()
        }
    }

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
        lifecycleScope.launch {
            scannerFlowValidationUseCase.validate(
                context = this@MainActivity,
                imageUri = preview.imageUri,
                title = preview.title,
                onState = { state ->
                    scannerFlowValidationState = state
                    // Keep the unified result screen live while it is open (e.g. on retry).
                    if (documentResultState != null) {
                        documentResultState = state.toDocumentResultState(localPreviewUri)
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
            localPreviewUri = preview?.imageUri?.toString()
        )
    }

    internal fun closeDocumentResult() {
        documentResultState = null
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
        if (isResultScreenEligible(document)) {
            documentResultState = document.toLibraryResultState()
        } else {
            pdfViewerDocument = document
        }
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
        val pages = listOf(
            PdfExportPageInput(
                pageNumber = 1,
                enhancedImageUrl = state.enhancedImageUrl,
                processedImageUrl = state.processedImageUrl,
                ocrText = resolvedText
            )
        )
        val title = importedImagePreview?.title ?: "Searchable PDF"

        lifecycleScope.launch {
            viewModel.showError("Exporting searchable PDF…")
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
