package com.dev.docscannerpdf.upload

data class UploadProgress(
    val bytesWritten: Long,
    val totalBytes: Long
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else bytesWritten.toFloat() / totalBytes.toFloat()
}

fun interface UploadProgressListener {
    fun onProgress(progress: UploadProgress)
}
