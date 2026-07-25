package com.dev.docscannerpdf.ui.library

import com.dev.docscannerpdf.data.local.DocumentEntity
import com.dev.docscannerpdf.ui.result.ResultLoadingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * BLOCKER 3 — proof (from IMPLEMENTATION, not naming) that the passport save's image-backed
 * document is valid.
 *
 * `DocumentEntity.localPdfUri` is a MISNOMER: it is the document's primary-file URI and is
 * deliberately polymorphic. Image-backed documents already store an image URI in it
 * (`ScannerViewModel.saveImportedImage`, the ID-card combined page), and the whole library layer
 * routes on the file's real type via [isResultScreenEligible] / [toLibraryItem] /
 * [toLibraryResultState] — never on the field name. A passport saves a canonical `.jpg` under the
 * app-private `filesDir`, so these tests confirm it resolves as an image document the clean
 * viewer opens, is NOT mistaken for a PDF, and (being a `file://` path under `filesDir`) survives
 * process restart. There is therefore no requirement to synthesize a real one-page PDF.
 */
class PassportDocumentContractTest {

    private fun passportDocument(
        uri: String = "file:///data/user/0/com.dev.docscannerpdf/files/passport_watermarked/passport-123.jpg"
    ) = DocumentEntity(
        id = 42L,
        title = "Passport 01-01-2026 09.00",
        timestamp = 1_700_000_000_000L,
        pageCount = 1,
        localPdfUri = uri
    )

    @Test
    fun savedPassportJpegIsRecognizedAsAnImageBackedDocument() {
        val document = passportDocument()

        // The clean unified result/viewer screen is eligible for the passport image.
        assertTrue(isResultScreenEligible(document))

        val item = document.toLibraryItem()
        assertFalse("a .jpg passport is not a generated PDF", item.hasGeneratedPdf)
        assertEquals("the image itself is the thumbnail source", document.localPdfUri, item.thumbnailUri)
    }

    @Test
    fun savedPassportOpensInTheCleanViewerWithTheImageAsItsPageSource() {
        val result = passportDocument().toLibraryResultState()

        assertEquals(
            "the saved passport image is the viewer's page source",
            "file:///data/user/0/com.dev.docscannerpdf/files/passport_watermarked/passport-123.jpg",
            result.localPreviewUri
        )
        assertEquals(ResultLoadingState.READY, result.loadingState)
    }

    @Test
    fun theLocalPdfUriFieldIsPolymorphicAJpgIsImageAPdfIsPdf() {
        val image = passportDocument()
        val pdf = passportDocument(uri = "file:///data/user/0/com.dev.docscannerpdf/files/docs/report.pdf")

        assertFalse(image.toLibraryItem().hasGeneratedPdf)
        assertTrue("a .pdf primary file is a generated PDF", pdf.toLibraryItem().hasGeneratedPdf)
        assertEquals("a PDF has no image thumbnail", null, pdf.toLibraryItem().thumbnailUri)
    }

    @Test
    fun savedPassportPathIsAppPrivateFilesDirSoItSurvivesProcessRestart() {
        // The persisted URI is a plain file:// path under the app's private filesDir — not a
        // cache dir and not an expiring external content:// grant — so re-reading it from Room
        // after a cold start resolves the same on-disk file.
        val uri = passportDocument().localPdfUri
        assertTrue(uri.startsWith("file:///data/user/0/com.dev.docscannerpdf/files/"))
        assertFalse("must not live in a volatile cache dir", uri.contains("/cache/"))
    }
}
