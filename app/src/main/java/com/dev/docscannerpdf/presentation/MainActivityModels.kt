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
    val rotationDegrees: Float = 0f
)

data class PendingImageReview(
    val imageUris: List<Uri>,
    val currentIndex: Int = 0,
    val selectedIndices: Set<Int> = emptySet()
)
