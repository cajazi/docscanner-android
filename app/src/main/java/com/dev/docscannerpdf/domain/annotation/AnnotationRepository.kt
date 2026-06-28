package com.dev.docscannerpdf.domain.annotation

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Local-first annotation persistence. Annotations are stored as a JSON blob per document
 * (`<baseDir>/<documentId>.json`) — no backend and no Room schema change. The polymorphic
 * [Annotation] hierarchy round-trips through kotlinx serialization.
 *
 * [baseDir] is injected so the layer is testable against a temp directory.
 */
class AnnotationRepository(
    private val baseDir: File,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // The annotation hierarchy already has a `type` property, so the polymorphic
        // discriminator must use a different key to avoid a conflict.
        classDiscriminator = "kind"
    }
) {
    private fun fileFor(documentId: String): File =
        File(baseDir, "${sanitize(documentId)}.json")

    /** Loads all annotations for a document, returning an empty container when absent/corrupt. */
    fun load(documentId: String): DocumentAnnotations {
        val file = fileFor(documentId)
        if (!file.exists()) return DocumentAnnotations()
        return runCatching { json.decodeFromString<DocumentAnnotations>(file.readText()) }
            .getOrDefault(DocumentAnnotations())
    }

    /** Persists the full document annotation set, creating [baseDir] if needed. */
    fun save(documentId: String, data: DocumentAnnotations) {
        runCatching {
            if (!baseDir.exists()) baseDir.mkdirs()
            fileFor(documentId).writeText(json.encodeToString(data))
        }
    }

    fun loadPage(documentId: String, pageId: String): List<Annotation> =
        load(documentId).pages[pageId].orEmpty()

    /** Merges [annotations] for [pageId] into the document's stored set. */
    fun savePage(documentId: String, pageId: String, annotations: List<Annotation>) {
        val current = load(documentId)
        val updatedPages = current.pages.toMutableMap().apply {
            if (annotations.isEmpty()) remove(pageId) else put(pageId, annotations)
        }
        save(documentId, current.copy(pages = updatedPages))
    }

    private fun sanitize(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "document" }
}
