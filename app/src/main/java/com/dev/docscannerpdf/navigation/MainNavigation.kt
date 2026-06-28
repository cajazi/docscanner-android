package com.dev.docscannerpdf.navigation

import com.dev.docscannerpdf.MainActivity
import com.dev.docscannerpdf.presentation.MainScreen

internal fun MainActivity.canHandleSystemBack(): Boolean {
    val screen = currentScreen()
    return appLockMessage != null ||
        imageEditorMessage != null ||
        pdfToolsMessage != null ||
        showSignaturePad ||
        pendingRestoreArchive != null ||
        viewerDocumentPendingDelete != null ||
        viewerDocumentPendingRename != null ||
        when (screen) {
            MainScreen.Dashboard, MainScreen.AppLock, MainScreen.Onboarding -> false
            else -> true
        }
}

internal fun MainActivity.currentScreen(): MainScreen {
    return when {
        appLockSettings.lockEnabled && !appUnlocked -> MainScreen.AppLock
        showOnboarding -> MainScreen.Onboarding
        showCloudSync -> MainScreen.CloudSync
        showPremium -> MainScreen.Premium
        showFeatureValidation -> MainScreen.FeatureValidation
        showApiHealth -> MainScreen.ApiHealth
        showBackupRestore -> MainScreen.BackupRestore
        showAppLockSettings -> MainScreen.Settings
        pdfViewerDocument != null -> MainScreen.PdfViewer
        showLockPdf -> MainScreen.LockPdf
        showUnlockPdf -> MainScreen.UnlockPdf
        showSignPdf -> MainScreen.SignPdf
        showWatermarkPdf -> MainScreen.WatermarkPdf
        showPdfToWord -> MainScreen.PdfToWord
        showPdfToImages -> MainScreen.PdfToImages
        showEditPdf -> MainScreen.EditPdf
        showMergePdf -> MainScreen.MergePdf
        showSplitPdf -> MainScreen.SplitPdf
        showImagesToPdf -> MainScreen.ImagesToPdf
        showCompressPdf -> MainScreen.CompressPdf
        showPdfTools -> MainScreen.PdfTools
        showLiveScanner -> MainScreen.LiveScanner
        cropState != null -> MainScreen.CropEditor
        multiPageEditorState != null -> MainScreen.MultiPageEditor
        showDocumentLibrary -> MainScreen.DocumentLibrary
        imageImportReview != null -> MainScreen.ImageImportReview
        pendingImageImport != null -> MainScreen.ImageEditor
        importedImagePreview != null -> MainScreen.ImportedImagePreview
        else -> MainScreen.Dashboard
    }
}

internal fun MainActivity.handleSystemBack() {
    when {
        appLockMessage != null -> appLockMessage = null
        imageEditorMessage != null -> imageEditorMessage = null
        pdfToolsMessage != null -> pdfToolsMessage = null
        showSignaturePad -> {
            showSignaturePad = false
            signatureTargetUri = null
        }
        pendingRestoreArchive != null -> pendingRestoreArchive = null
        viewerDocumentPendingDelete != null -> viewerDocumentPendingDelete = null
        viewerDocumentPendingRename != null -> viewerDocumentPendingRename = null
        pdfViewerDocument != null -> pdfViewerDocument = null
        showCloudSync -> showCloudSync = false
        showPremium -> showPremium = false
        showFeatureValidation -> showFeatureValidation = false
        showApiHealth -> showApiHealth = false
        showBackupRestore -> showBackupRestore = false
        showAppLockSettings -> showAppLockSettings = false
        showLockPdf -> closeLockPdf()
        showUnlockPdf -> closeUnlockPdf()
        showSignPdf -> closeSignPdf()
        showWatermarkPdf -> closeWatermarkPdf()
        showPdfToWord -> closePdfToWord()
        showPdfToImages -> closePdfToImages()
        showEditPdf -> closeEditPdf()
        showMergePdf -> closeMergePdf()
        showSplitPdf -> closeSplitPdf()
        showImagesToPdf -> closeImagesToPdf()
        showCompressPdf -> closeCompressPdf()
        showLiveScanner -> showLiveScanner = false
        showAiTools -> showAiTools = false
        showPdfTools -> showPdfTools = false
        // The crop editor sits on top of the result screen; back cancels it first.
        cropState != null -> cancelCropEditor()
        // The multi-page editor backs out to its launch point (e.g. the library) first.
        multiPageEditorState != null -> closeMultiPageEditor()
        // While the library is open, a document opened into the result screen backs out to
        // the library first; otherwise system back closes the library itself.
        showDocumentLibrary && documentResultState != null -> closeDocumentResult()
        showDocumentLibrary -> closeDocumentLibrary()
        importedImagePreview != null -> {
            pendingImageImport = importedImagePreview
            importedImagePreview = null
        }
        imageImportReview != null -> imageImportReview = null
        pendingImageImport != null -> {
            imageImportReview = com.dev.docscannerpdf.presentation.PendingImageReview(
                imageUris = listOf(pendingImageImport!!.imageUri)
            )
            pendingImageImport = null
        }
    }
}
