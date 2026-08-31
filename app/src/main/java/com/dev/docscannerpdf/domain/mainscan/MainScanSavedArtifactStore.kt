package com.dev.docscannerpdf.domain.mainscan

import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** The durable app-private JPEG owned by a successfully saved Main Scanner document. */
data class MainScanSavedArtifact(val uri: String)

/**
 * Promotes an authoritative transient JPEG into the durable Main Scanner document directory.
 *
 * The input type is deliberately [MainScanAuthoritativeArtifact], not a bitmap or an arbitrary
 * preview URI. The enhanced source-resolution sibling is the only source copied, and the source is
 * never moved or deleted here: it remains owned by the visit until persistence succeeds and the
 * visit sweep retires its transient files.
 */
class MainScanSavedArtifactStore(
    private val directory: File,
    private val nonce: () -> String = { UUID.randomUUID().toString() }
) {

    /** Copies [artifact]'s enhanced sibling to a new collision-safe durable file. */
    suspend fun promote(artifact: MainScanAuthoritativeArtifact): MainScanSavedArtifact {
        var target: File? = null
        try {
            return withContext(Dispatchers.IO) {
                val source = fileFromUri(artifact.enhancedUri)
                require(source.isFile && source.length() > 0L) {
                    "The authoritative enhanced page is unavailable."
                }
                ensureDirectory()
                val allocated = allocateTarget()
                target = allocated
                source.inputStream().buffered().use { input ->
                    allocated.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                if (!allocated.isFile ||
                    allocated.length() != source.length() ||
                    allocated.length() <= 0L
                ) {
                    throw IOException("The durable Main Scanner copy could not be verified.")
                }
                MainScanSavedArtifact(fileUri(allocated))
            }
        } catch (throwable: Throwable) {
            // The try surrounds withContext itself. That matters when cancellation wins the return
            // dispatch after the copy was verified: the result URI is lost to the caller, but the
            // allocated file is still known here and is removed before cancellation propagates.
            val cleanupFailure = withContext(NonCancellable + Dispatchers.IO) {
                target?.takeIf { file -> file.exists() && !file.delete() }?.let {
                    IOException("The incomplete durable Main Scanner copy could not be removed.")
                }
            }
            cleanupFailure?.let(throwable::addSuppressed)
            throw throwable
        }
    }

    /**
     * Removes a promoted file after a handled pre-insertion failure.
     *
     * Containment is rechecked from canonical paths. A malformed or substituted URI can therefore
     * never turn failure cleanup into authority to delete another app-private file.
     */
    suspend fun delete(artifact: MainScanSavedArtifact) = withContext(Dispatchers.IO) {
        val root = directory.canonicalFile
        val target = fileFromUri(artifact.uri).canonicalFile
        if (target.parentFile != root) {
            throw IOException("Refusing to delete a file outside the durable Main Scanner store.")
        }
        if (target.exists() && !target.delete()) {
            throw IOException("The failed durable Main Scanner copy could not be removed.")
        }
    }

    private fun ensureDirectory() {
        if (directory.isDirectory) return
        if (directory.exists() || !directory.mkdirs()) {
            throw IOException("The durable Main Scanner directory is unavailable.")
        }
    }

    private fun allocateTarget(): File {
        repeat(MAX_FILE_NAME_ATTEMPTS) {
            val token = nonce().filter { character ->
                character.isLetterOrDigit() || character == '-' || character == '_'
            }
            if (token.isBlank()) throw IOException("A durable file name could not be created.")
            val candidate = File(directory, "main-scan-saved-$token.jpg")
            if (candidate.createNewFile()) return candidate
        }
        throw IOException("A unique durable Main Scanner file could not be allocated.")
    }

    private fun fileFromUri(uriValue: String): File {
        val parsed = runCatching { URI(uriValue) }
            .getOrElse { throw IOException("The Main Scanner file URI is invalid.", it) }
        if (!parsed.scheme.equals("file", ignoreCase = true)) {
            throw IOException("Only app-private file URIs can be promoted.")
        }
        return runCatching { File(parsed) }
            .getOrElse { throw IOException("The Main Scanner file URI has no local path.", it) }
    }

    private fun fileUri(file: File): String = "file://${file.canonicalFile.toURI().rawPath}"

    companion object {
        const val DIRECTORY_NAME = "main_scan_saved"
        private const val MAX_FILE_NAME_ATTEMPTS = 16
    }
}
