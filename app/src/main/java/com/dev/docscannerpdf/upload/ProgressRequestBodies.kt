package com.dev.docscannerpdf.upload

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import okhttp3.MediaType
import okhttp3.RequestBody
import okio.BufferedSink

private const val SEGMENT_SIZE = 8 * 1024

class ProgressFileRequestBody(
    private val file: File,
    private val contentType: MediaType,
    private val progressListener: UploadProgressListener? = null
) : RequestBody() {
    override fun contentType(): MediaType = contentType

    override fun contentLength(): Long = file.length()

    override fun writeTo(sink: BufferedSink) {
        file.inputStream().use { input ->
            writeProgress(input::read, sink, contentLength(), progressListener)
        }
    }
}

class ProgressUriRequestBody(
    private val contentResolver: ContentResolver,
    private val uri: Uri,
    private val contentType: MediaType,
    private val progressListener: UploadProgressListener? = null
) : RequestBody() {
    override fun contentType(): MediaType = contentType

    override fun contentLength(): Long {
        return contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            descriptor.length
        } ?: -1L
    }

    override fun writeTo(sink: BufferedSink) {
        val totalBytes = contentLength()
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open image upload stream." }
            writeProgress(input::read, sink, totalBytes, progressListener)
        }
    }
}

private fun writeProgress(
    read: (ByteArray) -> Int,
    sink: BufferedSink,
    totalBytes: Long,
    progressListener: UploadProgressListener?
) {
    val buffer = ByteArray(SEGMENT_SIZE)
    var uploaded = 0L
    while (true) {
        val readCount = read(buffer)
        if (readCount == -1) break
        sink.write(buffer, 0, readCount)
        uploaded += readCount
        progressListener?.onProgress(UploadProgress(uploaded, totalBytes))
    }
}
