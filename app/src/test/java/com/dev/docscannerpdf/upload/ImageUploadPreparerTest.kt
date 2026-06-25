package com.dev.docscannerpdf.upload

import java.io.File
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageUploadPreparerTest {
    @Test
    fun detectMimeType_usesResolverImageTypeFirst() {
        assertEquals(
            "image/png",
            ImageUploadPreparer.detectMimeType(
                fileName = "scan.jpg",
                resolverMimeType = "image/png"
            )
        )
    }

    @Test
    fun detectMimeType_mapsKnownImageExtensions() {
        assertEquals("image/jpeg", ImageUploadPreparer.detectMimeType("scan.jpeg"))
        assertEquals("image/png", ImageUploadPreparer.detectMimeType("scan.PNG"))
        assertEquals("image/webp", ImageUploadPreparer.detectMimeType("scan.webp"))
        assertEquals("image/heic", ImageUploadPreparer.detectMimeType("scan.heic"))
        assertEquals("image/heif", ImageUploadPreparer.detectMimeType("scan.heif"))
    }

    @Test
    fun detectMimeType_defaultsToJpegForUnknownInput() {
        assertEquals(ImageUploadPreparer.DEFAULT_MIME_TYPE, ImageUploadPreparer.detectMimeType("scan.bin"))
        assertEquals(ImageUploadPreparer.DEFAULT_MIME_TYPE, ImageUploadPreparer.detectMimeType(null))
    }

    @Test
    fun createMultipartPart_buildsImagePartFromFile() {
        val file = File.createTempFile("scan", ".jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
            deleteOnExit()
        }
        val progress = mutableListOf<UploadProgress>()

        val part = ImageUploadPreparer.createMultipartPart(
            file = file,
            progressListener = UploadProgressListener(progress::add)
        )
        val buffer = Buffer()
        part.body.writeTo(buffer)

        assertNotNull(part.headers)
        assertTrue(part.headers.toString().contains("name=\"image\""))
        assertTrue(part.headers.toString().contains("filename=\"${file.name}\""))
        assertEquals("image/jpeg", part.body.contentType().toString())
        assertEquals(4L, part.body.contentLength())
        assertEquals(listOf(1, 2, 3, 4), buffer.readByteArray().map { it.toInt() })
        assertEquals(4L, progress.last().bytesWritten)
        assertEquals(4L, progress.last().totalBytes)
    }
}
