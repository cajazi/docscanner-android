package com.dev.docscannerpdf.domain.mainscan

import java.io.File
import java.io.IOException
import java.net.URI
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MainScanSavedArtifactStoreTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private fun artifact(cropped: File, enhanced: File) = MainScanAuthoritativeArtifact(
        croppedUri = cropped.toURI().toString(),
        enhancedUri = enhanced.toURI().toString(),
        pixelWidth = 2400,
        pixelHeight = 3200,
        sourceSampleSize = MainScanAuthoritativeRender.AUTHORITATIVE_SAMPLE_SIZE,
        rotationQuarterTurns = 0
    )

    private suspend fun expectIOException(block: suspend () -> Unit): IOException {
        try {
            block()
        } catch (failure: IOException) {
            return failure
        }
        throw AssertionError("expected IOException")
    }

    @Test
    fun promotionCopiesOnlyEnhancedAuthoritativePixelsIntoDurableDirectory() = runBlocking {
        val transient = temporaryFolder.newFolder("main_scan_enhanced")
        val cropped = File(transient, "cropped.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val enhancedBytes = byteArrayOf(9, 8, 7, 6, 5)
        val enhanced = File(transient, "enhanced.jpg").apply { writeBytes(enhancedBytes) }
        val durable = temporaryFolder.newFolder(MainScanSavedArtifactStore.DIRECTORY_NAME)
        val store = MainScanSavedArtifactStore(durable) { "first" }

        val saved = store.promote(artifact(cropped, enhanced))
        val savedFile = File(URI(saved.uri))

        assertEquals(durable.canonicalFile, requireNotNull(savedFile.parentFile).canonicalFile)
        assertArrayEquals(enhancedBytes, savedFile.readBytes())
        assertFalse("the cropped sibling is not a persistence fallback", savedFile.readBytes().contentEquals(cropped.readBytes()))
        assertTrue("promotion copies; it never moves the source", enhanced.isFile)
        assertNotEquals(enhanced.toURI().toString(), saved.uri)
    }

    @Test
    fun repeatedPromotionsUseDistinctCollisionSafeNames() = runBlocking {
        val transient = temporaryFolder.newFolder("source")
        val cropped = File(transient, "cropped.jpg").apply { writeBytes(byteArrayOf(1)) }
        val enhanced = File(transient, "enhanced.jpg").apply { writeBytes(byteArrayOf(2)) }
        val durable = temporaryFolder.newFolder(MainScanSavedArtifactStore.DIRECTORY_NAME)
        var nonce = 0
        val store = MainScanSavedArtifactStore(durable) { "nonce-${++nonce}" }

        val first = store.promote(artifact(cropped, enhanced))
        val second = store.promote(artifact(cropped, enhanced))

        assertNotEquals(first.uri, second.uri)
        assertEquals(2, durable.listFiles().orEmpty().size)
    }

    @Test
    fun missingEnhancedSourceCreatesNoDurableArtifact() = runBlocking {
        val transient = temporaryFolder.newFolder("missing-source")
        val cropped = File(transient, "cropped.jpg").apply { writeBytes(byteArrayOf(1)) }
        val missing = File(transient, "missing-enhanced.jpg")
        val durable = temporaryFolder.newFolder(MainScanSavedArtifactStore.DIRECTORY_NAME)
        val store = MainScanSavedArtifactStore(durable)

        var failed = false
        try {
            store.promote(artifact(cropped, missing))
        } catch (_: IllegalArgumentException) {
            failed = true
        }

        assertTrue(failed)
        assertTrue(durable.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun handledFailureCleanupDeletesOnlyDurableCopyAndKeepsAuthoritativeSource() = runBlocking {
        val transient = temporaryFolder.newFolder("cleanup-source")
        val cropped = File(transient, "cropped.jpg").apply { writeBytes(byteArrayOf(1)) }
        val enhanced = File(transient, "enhanced.jpg").apply { writeBytes(byteArrayOf(2, 3)) }
        val durable = temporaryFolder.newFolder(MainScanSavedArtifactStore.DIRECTORY_NAME)
        val store = MainScanSavedArtifactStore(durable) { "cleanup" }
        val saved = store.promote(artifact(cropped, enhanced))

        store.delete(saved)

        assertFalse(File(URI(saved.uri)).exists())
        assertTrue(enhanced.isFile)
        assertTrue(cropped.isFile)
    }

    @Test
    fun deleteRefusesAFileOutsideMainScanSaved() = runBlocking {
        val root = temporaryFolder.newFolder("outside-delete")
        val durable = File(root, MainScanSavedArtifactStore.DIRECTORY_NAME).apply {
            assertTrue(mkdir())
        }
        val outsideBytes = byteArrayOf(7, 7, 3)
        val outside = File(root, "library-owned.jpg").apply { writeBytes(outsideBytes) }
        val store = MainScanSavedArtifactStore(durable)

        val failure = expectIOException {
            store.delete(MainScanSavedArtifact(outside.toURI().toString()))
        }

        assertTrue(failure.message.orEmpty().contains("outside"))
        assertTrue(outside.isFile)
        assertArrayEquals(outsideBytes, outside.readBytes())
    }

    @Test
    fun deleteRejectsMalformedAndNonFileUris() = runBlocking {
        val durable = temporaryFolder.newFolder(MainScanSavedArtifactStore.DIRECTORY_NAME)
        val store = MainScanSavedArtifactStore(durable)

        for (uri in listOf("not a valid URI [", "content://documents/42")) {
            expectIOException { store.delete(MainScanSavedArtifact(uri)) }
        }

        assertTrue(durable.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun traversalShapedUriCannotEscapeDurableContainment() = runBlocking {
        val root = temporaryFolder.newFolder("traversal")
        val durable = File(root, MainScanSavedArtifactStore.DIRECTORY_NAME).apply {
            assertTrue(mkdir())
        }
        val outsideBytes = byteArrayOf(4, 2, 4, 2)
        val outside = File(root, "outside.jpg").apply { writeBytes(outsideBytes) }
        val traversalUri = durable.toURI().toString() + "../outside.jpg"
        val store = MainScanSavedArtifactStore(durable)

        assertTrue("the test input must retain its traversal shape", traversalUri.contains("../"))
        expectIOException { store.delete(MainScanSavedArtifact(traversalUri)) }

        assertEquals(outside.canonicalFile, File(URI(traversalUri)).canonicalFile)
        assertTrue(outside.isFile)
        assertArrayEquals(outsideBytes, outside.readBytes())
    }
}
