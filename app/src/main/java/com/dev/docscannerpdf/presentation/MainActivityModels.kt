package com.dev.docscannerpdf.presentation

import android.graphics.Bitmap
import android.net.Uri
import java.io.File

data class SplitOutput(
    val file: File,
    val pageCount: Int
)

data class MergeOutput(
    val file: File,
    val pageCount: Int,
    val extractedText: String?
)

data class SignedPdfOutput(
    val file: File,
    val pageCount: Int
)

data class WatermarkPreview(
    val bitmap: Bitmap,
    val pageCount: Int
)

data class WatermarkOutput(
    val file: File,
    val pageCount: Int
)

data class PdfTextExportOutput(
    val file: File,
    val pageCount: Int,
    val extractedText: String?
)

data class PdfImageOutput(
    val file: File,
    val thumbnail: Bitmap
)

data class EditedPdfOutput(
    val file: File,
    val pageCount: Int
)

data class PendingImageImport(
    val imageUri: Uri,
    val title: String = "Imported Image",
    val extractedText: String? = null,
    val isExtractingText: Boolean = false,
    val rotationDegrees: Float = 0f,
    // The back side of an ID-card scan, when the user captured one. Null for every other
    // scan/import flow — normal documents only ever have [imageUri].
    val backImageUri: Uri? = null,
    // True for an ID-card scan even when only the front was captured, so preview/export can use
    // the ID-card-sized layout instead of the normal full-page document layout. Normal
    // document scans/imports always leave this false.
    val isIdCardScan: Boolean = false,
    // The rendered combined result page (front + back on one white A4-style page) for an
    // ID-card scan — the single image the Document Ready preview shows and "Save to gallery"
    // writes. Null while it is still rendering and for every non-ID-card flow. [imageUri]/
    // [backImageUri] stay the per-side sources the PDF export lays out itself.
    val combinedImageUri: Uri? = null
)

data class PendingImageReview(
    val imageUris: List<Uri>,
    val currentIndex: Int = 0,
    val selectedIndices: Set<Int> = emptySet()
)
