package com.dev.docscannerpdf.ui.mainscan

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dev.docscannerpdf.domain.filter.DocumentFilter
import com.dev.docscannerpdf.domain.mainscan.MainScanRenderFailure
import com.dev.docscannerpdf.ui.idcard.DarkSystemBarsEffect

/**
 * Chrome for this surface. Declared here rather than shared because `private` is file scope in
 * Kotlin and the value is a local styling detail, not a contract between files.
 */
private val ProcessingChrome = Color(0xFF101114)

/**
 * The five filters the review offers, in the order the reference presents them: the unmodified
 * page first, a lighter variant, the enhanced default, then the two high-contrast document looks.
 *
 * Our own filter set and our own names — the reference's behaviour is what is being matched, never
 * its wording. Every entry is a real, already-implemented recipe in [DocumentFilter]; nothing here
 * is a placeholder, and selecting one re-renders both the preview and the saveable page.
 */
private val MainScanReviewFilters = listOf(
    DocumentFilter.ORIGINAL,
    DocumentFilter.BRIGHTNESS,
    DocumentFilter.ENHANCE,
    DocumentFilter.BW,
    DocumentFilter.GRAY
)

internal data class MainScanReviewPresentation(
    val statusMessage: String?,
    val imageDescription: String,
    val imageStateDescription: String
)

internal fun mainScanReviewPresentation(
    filterRendering: Boolean,
    highQualityResultAvailable: Boolean,
    highQualityFailure: MainScanRenderFailure?,
    enhancementApplied: Boolean,
    comparing: Boolean
): MainScanReviewPresentation {
    val statusMessage = when {
        filterRendering -> null
        highQualityResultAvailable -> null

        highQualityFailure == MainScanRenderFailure.INSUFFICIENT_MEMORY ->
            "Preview only — there wasn't enough memory to produce the full-quality " +
                "page, so this can't be saved. Close other apps and try again."

        else ->
            "Preview only — the high-quality page couldn't be produced, " +
                "so this can't be saved. Go back and try the crop again."
    }

    val imageDescription = when {
        comparing -> "The unenhanced cropped page, shown for comparison."

        filterRendering && enhancementApplied ->
            "Preview of the filtered page while a new filter is being applied."

        filterRendering ->
            "Preview of the cropped page while a new filter is being applied."

        !highQualityResultAvailable && enhancementApplied ->
            "Preview of the filtered page. Preview only — this cannot be saved."

        !highQualityResultAvailable ->
            "Preview of the cropped page, shown without a filter. " +
                "Preview only — this cannot be saved."

        enhancementApplied ->
            "Preview of the cropped and filtered page. The full-quality page is ready."

        else ->
            "Preview of the cropped page, shown without a filter. " +
                "The full-quality page is ready."
    }

    val imageStateDescription = when {
        filterRendering -> "Applying a new filter"
        highQualityResultAvailable -> "Full-quality page ready"
        else -> "Preview only, not saveable"
    }

    return MainScanReviewPresentation(
        statusMessage = statusMessage,
        imageDescription = imageDescription,
        imageStateDescription = imageStateDescription
    )
}

/**
 * The enhancement review — the surface the reference reaches after enhancement completes.
 *
 * Behaviour matched from `docs/main-scanner-reference.md`, stage 6, and the recording it was
 * derived from:
 *
 * - The page fills the surface and is never removed from screen, including while a filter change
 *   re-renders it: a small centred `Processing…` card is drawn OVER the retained page instead.
 * - The auto-generated document title sits in the top chrome as an **editable** field with a dashed
 *   underline, and what the user types is what Confirm writes.
 * - A **compare** affordance overlays the image's top-trailing corner. Holding it shows the
 *   unenhanced crop and releasing returns to the filtered page, so the two can be judged against
 *   each other without leaving the surface.
 * - A **filter row** sits above the toolbar with the active entry marked. Switching filters is a
 *   real re-render of the saveable artifact, not a preview-only effect.
 * - The confirm action is a wide, accent-filled button carrying a check glyph — visually dominant
 *   and unambiguously the primary action, rather than an icon in the app bar.
 *
 * ## The image shown is NOT the image that would be saved
 *
 * [enhanced] and [cropped] are previews, rendered from a bitmap downsampled to fit an interactive
 * surface. The saveable page is the separate source-resolution artifact, and
 * [highQualityResultAvailable] reports whether that artifact actually exists.
 *
 * The two can disagree, and when they do this surface says so and disables Confirm. A preview
 * renders identically whether the full-resolution render succeeded or refused — so a review that
 * stayed silent would show a perfect page while nothing saveable existed behind it. That is the one
 * lie this screen must not tell. In the healthy state there is no caption, matching the reference;
 * the caption appears only when it is load-bearing.
 */
@Composable
fun MainScanEnhancementReviewScreen(
    enhanced: Bitmap?,
    cropped: Bitmap?,
    highQualityResultAvailable: Boolean,
    highQualityFailure: MainScanRenderFailure?,
    title: String,
    onTitleChange: (String) -> Unit,
    selectedFilter: DocumentFilter,
    onFilterSelected: (DocumentFilter) -> Unit,
    filterRendering: Boolean,
    onBack: () -> Unit,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit
) {
    DarkSystemBarsEffect()
    // Enhancement is an improvement, not a requirement: when it failed, the true cropped page is
    // shown rather than nothing, and the caption says which one this is.
    val filtered = enhanced ?: cropped
    val enhancementApplied = enhanced != null

    // Held while the compare affordance is pressed. The unfiltered crop is what it reveals, so the
    // comparison is against the real base image rather than a second rendering of the same look.
    var comparing by remember { mutableStateOf(false) }
    val displayed = if (comparing && cropped != null) cropped else filtered
    val compareAvailable = cropped != null && enhanced != null && enhanced !== cropped

    // Keep the visible caption and accessibility copy on one pure state derivation so render-in-
    // progress, completed failure, and healthy states cannot drift apart.
    val presentation = mainScanReviewPresentation(
        filterRendering = filterRendering,
        highQualityResultAvailable = highQualityResultAvailable,
        highQualityFailure = highQualityFailure,
        enhancementApplied = enhancementApplied,
        comparing = comparing
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ProcessingChrome)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to crop",
                        tint = Color.White
                    )
                }
                MainScanTitleField(
                    title = title,
                    onTitleChange = onTitleChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp)
                )
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (displayed != null) {
                    val bitmap = remember(displayed) { displayed.asImageBitmap() }
                    Image(
                        bitmap = bitmap,
                        // Set through `semantics` rather than the parameter so the STATE travels
                        // with the image too: whether a saveable page exists is not a property of
                        // the picture, and a screen reader that reached the image without reading
                        // the caption below would otherwise never hear it.
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics {
                                contentDescription = presentation.imageDescription
                                stateDescription = presentation.imageStateDescription
                            }
                    )
                }

                if (compareAvailable) {
                    MainScanCompareChip(
                        pressed = comparing,
                        onPressChange = { comparing = it },
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(10.dp)
                    )
                }

                // Over the retained page, never instead of it — the reference keeps the image and
                // the chrome on screen for the whole of a filter change.
                if (filterRendering) {
                    MainScanReviewProcessingCard()
                }
            }

            if (presentation.statusMessage != null) {
                Text(
                    text = presentation.statusMessage,
                    color = Color.White,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(ProcessingChrome)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }

            MainScanFilterRow(
                base = cropped,
                selected = selectedFilter,
                enabled = !filterRendering,
                onFilterSelected = onFilterSelected
            )

            // The primary action: wide, accent-filled, carrying a check glyph. Disabled whenever
            // there is nothing saveable behind the page — while a filter is still rendering, or
            // when the full-quality render refused.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ProcessingChrome)
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Button(
                    onClick = onConfirm,
                    enabled = confirmEnabled,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MainScanAccent,
                        disabledContainerColor = Color(0xFF2A2C31)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Save document",
                        tint = if (confirmEnabled) Color.White else Color(0xFF6B6D72)
                    )
                }
            }
        }
    }
}

/**
 * The editable document title in the review's top chrome.
 *
 * A [BasicTextField] rather than a label plus a rename dialog: the reference edits the title in
 * place, and the dashed underline is what says the field is editable before it is touched.
 */
@Composable
private fun MainScanTitleField(
    title: String,
    onTitleChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val underline = Color(0x99FFFFFF)
    BasicTextField(
        value = title,
        onValueChange = onTitleChange,
        singleLine = true,
        textStyle = TextStyle(
            color = Color.White,
            fontSize = MaterialTheme.typography.titleMedium.fontSize,
            fontWeight = FontWeight.SemiBold
        ),
        cursorBrush = SolidColor(MainScanAccent),
        modifier = modifier
            .semantics { contentDescription = "Document title" }
            .drawBehind {
                val y = size.height - 1.dp.toPx()
                drawLine(
                    color = underline,
                    start = Offset(0f, y),
                    end = Offset(size.width, y),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(
                        floatArrayOf(4.dp.toPx(), 3.dp.toPx()),
                        0f
                    )
                )
            }
            .padding(bottom = 5.dp)
    )
}

/**
 * The compare affordance overlaid on the page's top-trailing corner.
 *
 * Press-and-hold, not a toggle: the reference reveals the original only while the control is held,
 * so releasing always returns to the page that would actually be saved and the user can never be
 * left looking at the base image believing it is the result.
 */
@Composable
private fun MainScanCompareChip(
    pressed: Boolean,
    onPressChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Text(
        text = "Compare",
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (pressed) MainScanAccent else Color(0xCC202226))
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics {
                contentDescription = "Hold to compare with the unenhanced page"
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onPressChange(true)
                        // Returns on release AND on cancellation, so a gesture interrupted by a
                        // scroll or a lifecycle event cannot strand the surface showing the base
                        // image.
                        tryAwaitRelease()
                        onPressChange(false)
                    }
                )
            }
    )
}

/**
 * The filter row: one swatch per offered recipe, the active one marked.
 *
 * Each swatch draws the real cropped page through that filter's colour matrix, so the row shows
 * this page rather than a generic sample. It is an APPROXIMATION on purpose: the tone curve and the
 * sharpen pass are omitted from the thumbnails because running the full recipe five times on every
 * recomposition would cost more than the row is worth. The selected entry is always rendered by the
 * real pipeline, so what the user judges full-size is the true result.
 */
@Composable
private fun MainScanFilterRow(
    base: Bitmap?,
    selected: DocumentFilter,
    enabled: Boolean,
    onFilterSelected: (DocumentFilter) -> Unit
) {
    if (base == null) return
    val bitmap = remember(base) { base.asImageBitmap() }
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(ProcessingChrome)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        items(MainScanReviewFilters) { filter ->
            val isSelected = filter == selected
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .width(62.dp)
                    .clickable(enabled = enabled && !isSelected) { onFilterSelected(filter) }
                    .semantics {
                        contentDescription = "${filter.displayName} filter"
                        stateDescription = if (isSelected) "Selected" else "Not selected"
                    }
            ) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    colorFilter = filter.colorMatrix?.let { matrix ->
                        ColorFilter.colorMatrix(ColorMatrix(matrix.copyOf()))
                    },
                    modifier = Modifier
                        .size(width = 62.dp, height = 46.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = if (isSelected) MainScanAccent else Color(0xFF3A3D43),
                            shape = RoundedCornerShape(6.dp)
                        )
                )
                Text(
                    text = filter.displayName,
                    color = if (isSelected) MainScanAccent else Color(0xFFB9BCC2),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    textAlign = TextAlign.Center,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * The small centred card drawn over the retained page while a filter change re-renders it. Sized to
 * its content so the page stays visible around it — the reference never dims or replaces the image.
 */
@Composable
private fun MainScanReviewProcessingCard() {
    Column(
        modifier = Modifier
            .background(Color(0xE6202226), RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = MainScanAccent,
            strokeWidth = 3.dp
        )
        Text(
            text = "Processing…",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium
        )
    }
}
