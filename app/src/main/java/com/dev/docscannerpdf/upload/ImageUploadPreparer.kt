package com.dev.docscannerpdf.upload

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody

object ImageUploadPreparer {
    private const val DEFAULT_FIELD_NAME = "file"
    private const val DEFAULT_FILE_NAME = "scan.jpg"
    const val DEFAULT_MIME_TYPE = "image/jpeg"

    fun detectMimeType(
        fileName: String?,
        resolverMimeType: String? = null
    ): String {
        val cleanResolverType = resolverMimeType?.takeIf { it.startsWith("image/") }
        if (cleanResolverType != null) return cleanResolverType

        return when (fileName?.substringAfterLast('.', missingDelimiterValue = "")?.lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "heic" -> "image/heic"
            "heif" -> "image/heif"
            else -> DEFAULT_MIME_TYPE
        }
    }

    fun createMultipartPart(
        file: File,
        fieldName: String = DEFAULT_FIELD_NAME,
        mimeType: String = detectMimeType(file.name),
        progressListener: UploadProgressListener? = null
    ): MultipartBody.Part {
        val body = ProgressFileRequestBody(
            file = file,
            contentType = mimeType.toMediaType(),
            progressListener = progressListener
        )
        return MultipartBody.Part.createFormData(fieldName, file.name, body)
    }

    fun createMultipartPart(
        context: Context,
        uri: Uri,
        fieldName: String = DEFAULT_FIELD_NAME,
        progressListener: UploadProgressListener? = null
    ): MultipartBody.Part {
        val resolver = context.contentResolver
        val fileName = displayName(context, uri) ?: DEFAULT_FILE_NAME
        val mimeType = detectMimeType(
            fileName = fileName,
            resolverMimeType = resolver.getType(uri)
        )
        val body = ProgressUriRequestBody(
            contentResolver = resolver,
            uri = uri,
            contentType = mimeType.toMediaType(),
            progressListener = progressListener
        )
        return MultipartBody.Part.createFormData(fieldName, fileName, body)
    }

    private fun displayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                } else {
                    null
                }
            }
    }
}
