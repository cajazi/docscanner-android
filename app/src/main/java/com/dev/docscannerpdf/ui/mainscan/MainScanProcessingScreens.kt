package com.dev.docscannerpdf.ui.mainscan

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dev.docscannerpdf.domain.mainscan.MainScanRenderFailure
import com.dev.docscannerpdf.ui.idcard.DarkSystemBarsEffect

private val ProcessingChrome = Color(0xFF101114)

/**
 * A processing stage: the page stays on screen with an indeterminate bar and a label beneath it.
 *
 * The retained image is the point. Replacing the page with a spinner during cropping or enhancement
 * is the specific defect the locked reference does not have — the user must keep seeing what is
 * being worked on, so progress is drawn around the image rather than instead of it.
 *
 * [label] is supplied by the caller so each stage names itself truthfully; the bar is indeterminate
 * because the underlying work reports no percentage, and a fake determinate bar that completes
 * before the work does would be a lie about progress.
 */
@Composable
fun MainScanProcessingScreen(
    image: Bitmap?,
    label: String,
    onBack: (() -> Unit)? = null
) {
    DarkSystemBarsEffect()
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
                if (onBack != null) {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                } else {
                    Box(modifier = Modifier.height(48.dp))
                }
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (image != null) {
                    val bitmap = remember(image) { image.asImageBitmap() }
                    Image(
                        bitmap = bitmap,
                        contentDescription = "Page being processed",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ProcessingChrome),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MainScanAccent,
                    trackColor = ProcessingChrome
                )
                Text(
                    text = label,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(bottom = 14.dp)
                )
            }
        }
    }
}

/**
 * The terminal failure surface for the crop/processing pipeline.
 *
 * [MainScanStage.Failed][com.dev.docscannerpdf.domain.mainscan.MainScanStage.Failed] is reached when
 * the captured JPEG could not be decoded, so there is no working image and no polygon. Composing the
 * crop editor for that stage rendered its empty-image branch — an indeterminate spinner over black
 * with no work behind it, which never resolves and says nothing true. This states the failure and
 * offers the one action that can recover: go back and shoot again.
 *
 * Deliberately NOT a progress surface: no spinner, no bar. Nothing is running.
 */
@Composable
fun MainScanFailureScreen(
    message: String,
    onBackToCamera: () -> Unit
) {
    DarkSystemBarsEffect()
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
                IconButton(onClick = onBackToCamera) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to camera",
                        tint = Color.White
                    )
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)
            ) {
                Text(
                    text = message,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center
                )
                Button(
                    onClick = onBackToCamera,
                    colors = ButtonDefaults.buttonColors(containerColor = MainScanAccent)
                ) { Text("Back to camera", color = Color.White) }
            }
        }
    }
}

/**
 * The enhancement-review handoff.
 *
 * This is deliberately the END of Pass A. It shows the real perspective-corrected, enhanced page —
 * the genuine output of the pipeline — and nothing else. The filter strip, the tool row and the
 * green confirm action belong to the persistence pass, and rendering them here as controls that do
 * not save would be exactly the fake-success the brief forbids. Back returns to crop editing with
 * the polygon intact.
 *
 * ## The image shown is NOT the image that would be saved
 *
 * [enhanced] and [cropped] are previews, rendered from a bitmap downsampled to fit an interactive
 * surface. The saveable page is the separate source-resolution artifact, and
 * [highQualityResultAvailable] reports whether that artifact actually exists.
 *
 * The two can disagree, and when they do this surface says so. A preview renders identically whether
 * the full-resolution render succeeded or refused — so a review that stayed silent would show a
 * perfect page while nothing saveable existed behind it. That is the one lie this screen must not
 * tell: the preview is never a fallback the user could keep.
 *
 * [highQualityFailure] refines the message where the reason is something the user can act on, and is
 * ignored entirely when a result does exist.
 */
@Composable
fun MainScanEnhancementReviewScreen(
    enhanced: Bitmap?,
    cropped: Bitmap?,
    highQualityResultAvailable: Boolean,
    highQualityFailure: MainScanRenderFailure?,
    onBack: () -> Unit
) {
    DarkSystemBarsEffect()
    // Enhancement is an improvement, not a requirement: when it failed, the true cropped page is
    // shown rather than nothing, and the caption says which one this is.
    val displayed = enhanced ?: cropped
    val enhancementApplied = enhanced != null

    // Computed once and used for BOTH the caption and the accessibility state, so what a sighted
    // user reads and what a screen reader announces cannot drift apart.
    //
    // No artifact exists, so there is nothing a later Confirm could write. Said plainly, and said
    // even though the page above looks entirely correct. Running out of memory is the one reason the
    // user can do something about, so it gets the advice; every other reason gets the same honest
    // statement without a suggestion that would not help.
    val statusMessage = when {
        !highQualityResultAvailable &&
            highQualityFailure == MainScanRenderFailure.INSUFFICIENT_MEMORY ->
            "Preview only — there wasn't enough memory to produce the full-quality " +
                "page, so this can't be saved. Close other apps and try again."

        !highQualityResultAvailable ->
            "Preview only — the high-quality page couldn't be produced, " +
                "so this can't be saved. Go back and try the crop again."

        enhancementApplied ->
            "Cropped and enhanced at full quality. Saving arrives in the next step."

        // The artifact is always enhanced when it exists — the render fails closed rather than
        // publishing an unenhanced one — so only the PREVIEW is missing its enhancement here, and
        // the caption says exactly that rather than describing the saveable page as something it
        // is not.
        else ->
            "Cropped and enhanced at full quality. The preview above shows the " +
                "unenhanced crop."
    }

    // What the image IS, for a user who cannot see it. "Enhanced page" was a lie in three of these
    // four states: this surface can be showing an unenhanced crop, and it can be showing a preview
    // that no saveable page stands behind. A screen reader must not be told the page is finished
    // when the only thing that exists is the picture of it.
    val imageDescription = when {
        !highQualityResultAvailable && enhancementApplied ->
            "Preview of the enhanced page. Preview only — this cannot be saved."

        !highQualityResultAvailable ->
            "Preview of the cropped page, shown without enhancement. " +
                "Preview only — this cannot be saved."

        // Even here the IMAGE is a preview — the artifact is a separate, larger file. Saying so
        // costs one clause and keeps the description true of the thing actually on screen.
        enhancementApplied ->
            "Preview of the cropped and enhanced page. The full-quality page is ready."

        else ->
            "Preview of the cropped page, shown without enhancement. " +
                "The full-quality enhanced page is ready."
    }

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
                Text(
                    text = "Review",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
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
                                contentDescription = imageDescription
                                stateDescription = if (highQualityResultAvailable) {
                                    "Full-quality page ready"
                                } else {
                                    "Preview only, not saveable"
                                }
                            }
                    )
                }
            }

            Text(
                text = statusMessage,
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(ProcessingChrome)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            )
        }
    }
}
