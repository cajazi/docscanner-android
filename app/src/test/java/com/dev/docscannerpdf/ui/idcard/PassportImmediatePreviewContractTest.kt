package com.dev.docscannerpdf.ui.idcard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level guards for the immediate-preview contracts that cannot be expressed as pure
 * reducer tests: the review screen must have NO horizontal filter-chip strip and NO
 * display-rotation transform, the filter panel must be the four-column thumbnail grid, the
 * only circular spinner left is the Confirm/save one (never after an editing tap), and the
 * debug timing log is structurally unable to carry paths, URIs, watermark text or image data.
 */
class PassportImmediatePreviewContractTest {

    private fun source(relative: String): String {
        val candidates = listOf(File(relative), File("app", relative))
        val file = candidates.firstOrNull { it.exists() }
            ?: error("Source file not found from test working dir: $relative")
        return file.readText()
    }

    private val reviewScreen get() = source("src/main/java/com/dev/docscannerpdf/ui/idcard/PassportReviewScreen.kt")

    @Test
    fun noHorizontalFilterChipStripRemains() {
        assertFalse(
            "the one-line horizontally scrolling chip strip must be gone",
            reviewScreen.contains("LazyRow")
        )
    }

    @Test
    fun filterPanelIsTheFourColumnThumbnailGrid() {
        val screen = reviewScreen
        assertTrue(screen.contains("LazyVerticalGrid"))
        assertTrue(
            "the column count must come from the tested PassportFilterGrid contract",
            screen.contains("GridCells.Fixed(PassportFilterGrid.COLUMN_COUNT)")
        )
        assertTrue(
            "the panel header must be the tested PassportFilterGrid.HEADER",
            screen.contains("PassportFilterGrid.HEADER")
        )
    }

    @Test
    fun noDisplayRotationTransformExists() {
        // Rotation is ALWAYS baked into pixels (preview bitmap or authoritative file) — a
        // graphicsLayer/rotationZ display transform would clip the page at 90°/270°.
        val screen = reviewScreen
        assertFalse(screen.contains("graphicsLayer"))
        assertFalse(screen.contains("rotationZ"))
    }

    @Test
    fun theOnlyCircularSpinnerLeftIsTheConfirmSaveOne() {
        // Editing taps show the thin LinearProgressIndicator line under the title bar; the
        // single remaining CircularProgressIndicator call is inside the Confirm button and is
        // driven by saveInProgress only.
        val screen = reviewScreen
        val spinnerCalls = Regex("CircularProgressIndicator\\(").findAll(screen).count()
        assertEquals("only the Confirm/save spinner may remain", 1, spinnerCalls)
        assertTrue("the thin pending line must exist", screen.contains("LinearProgressIndicator"))
        assertTrue(
            "the Confirm spinner must be gated on saveInProgress alone",
            screen.contains("saving = state.saveInProgress")
        )
    }

    @Test
    fun timingLogIsStructurallyContentFree() {
        val log = source("src/main/java/com/dev/docscannerpdf/domain/idscan/PassportTimingLog.kt")
        val allowedKeys = setOf("operation", "generation", "elapsedMs", "width", "height", "stage")
        val emittedKeys = Regex("([A-Za-z]+)=").findAll(log).map { it.groupValues[1] }.toSet()
        assertTrue(
            "timing logs may only carry $allowedKeys but found $emittedKeys",
            allowedKeys.containsAll(emittedKeys)
        )
    }

    @Test
    fun previewAndAuthoritativeWatermarkShareTheSamePlacementGeometry() {
        // Both renderers must derive text size, rotation angle and opacity from the SAME
        // fraction-based PassportWatermarkGeometry constants — "what you see is what is saved"
        // by construction, not by coincidence.
        val renderers = listOf(
            source("src/main/java/com/dev/docscannerpdf/domain/idscan/PassportPreviewRenderer.kt"),
            source("src/main/java/com/dev/docscannerpdf/domain/idscan/PassportWatermarkRenderer.kt")
        )
        for (renderer in renderers) {
            assertTrue(renderer.contains("PassportWatermarkGeometry.TEXT_SIZE_FRACTION"))
            assertTrue(renderer.contains("PassportWatermarkGeometry.ROTATION_DEGREES"))
            assertTrue(renderer.contains("PassportWatermarkGeometry.ALPHA"))
        }
    }

    @Test
    fun watermarkTextIsNeverInterpolatedIntoAnyLogLine() {
        val files = listOf(
            "src/main/java/com/dev/docscannerpdf/ui/idcard/PassportReviewScreen.kt",
            "src/main/java/com/dev/docscannerpdf/domain/idscan/PassportWatermarkRenderer.kt",
            "src/main/java/com/dev/docscannerpdf/domain/idscan/PassportPreviewRenderer.kt",
            "src/main/java/com/dev/docscannerpdf/MainActivity.kt"
        )
        for (path in files) {
            val logLines = source(path).lines().filter { it.contains("Log.") }
            for (line in logLines) {
                assertFalse(
                    "watermark text leaked into a log in $path: $line",
                    line.contains("\$text") || line.contains("\${text")
                )
            }
        }
    }
}
