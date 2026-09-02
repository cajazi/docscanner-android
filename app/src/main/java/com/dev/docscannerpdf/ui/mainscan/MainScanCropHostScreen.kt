package com.dev.docscannerpdf.ui.mainscan

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.dev.docscannerpdf.domain.mainscan.MainScanTrace

private val CropChrome = Color(0xFF101114)

/**
 * The dedicated Main Scanner crop surface — the destination a successful capture routes to. This is
 * deliberately NOT the generic document-result screen: no backend processing, no OCR workspace, no
 * "document ready" state, and no saved document exists at this point.
 *
 * Slice 1 establishes the surface's hard visual contract, which the locked reference makes
 * non-negotiable (`docs/main-scanner-reference.md`, stage 3):
 *
 * - The captured page is rendered **immediately on entry**, at full frame, letterboxed on dark
 *   chrome. There is no state in which this screen is empty, white, tiny, or showing a previous
 *   page — [pageUri] is required, not nullable, so the surface cannot be reached without pixels.
 * - A small **centred** card with an indeterminate indicator and a `Processing…` label sits *over*
 *   the retained image while the decode runs. It never replaces the image.
 * - Back raises the discard confirmation rather than silently dropping the page.
 *
 * **Loop-free rendering.** The previous implementation built an `ImageRequest` inline on every
 * recomposition and drove a `decoding` flag from Coil's `onLoading`/`onSuccess` callbacks, while the
 * same composable read that flag — state written from the render path and read by it. That is a
 * recomposition-feedback shape worth removing on principle, though it is NOT confirmed as the cause
 * of the reported capture→Crop hang: Coil's `ImageRequest` implements structural equality and its
 * state callbacks fire only on transitions, so the loop may never have closed in practice.
 *
 * The model is now the stable [pageUri] string and progress is READ from the painter's own state
 * rather than written back into composition, so there is no state mutation in the render path at all.
 * The decode stays on Coil's background dispatcher — no full-resolution work touches the UI thread.
 *
 * The auto-detected quadrilateral, the eight drag handles, the dimmed exterior mask, and the
 * rotate/apply-to-all/advance action bar are Slice 4. They are not stubbed here: this project does
 * not render controls that do nothing.
 */
@Composable
fun MainScanCropHostScreen(
    pageUri: String,
    onBack: () -> Unit
) {
    // The model is the URI STRING — a stable, equals-comparable value. Coil resolves it to a file
    // request internally, so no request object is rebuilt per recomposition.
    val painter = rememberAsyncImagePainter(model = pageUri)
    val imageState = painter.state
    val fullResolutionReady = imageState is AsyncImagePainter.State.Success
    val decoding = imageState is AsyncImagePainter.State.Loading ||
        imageState is AsyncImagePainter.State.Empty

    // The reference's hard requirement for stage 3: the captured frame is on screen BEFORE
    // detection completes, and there is no blank, white, tiny or stale frame at any point. A
    // full-resolution Coil decode draws NOTHING while it is loading, so the surface was black
    // under the Processing card for as long as that decode took. This small EXIF-upright decode
    // lands in a few milliseconds and is drawn underneath, at the same geometry and the same
    // ContentScale, so the full-resolution image replaces it in place with no flash and no reflow.
    val context = LocalContext.current
    val instantPreview by produceState<android.graphics.Bitmap?>(initialValue = null, pageUri) {
        value = MainScanCaptureImageLoader.loadPreview(context, pageUri.toUri())
    }

    LaunchedEffect(pageUri) { MainScanTrace.cropMounted(hasPageUri = pageUri.isNotBlank()) }
    LaunchedEffect(imageState::class) {
        MainScanTrace.cropImageState(
            when (imageState) {
                is AsyncImagePainter.State.Loading -> "loading"
                is AsyncImagePainter.State.Success -> "success"
                is AsyncImagePainter.State.Error -> "error"
                is AsyncImagePainter.State.Empty -> "empty"
            }
        )
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
                    .background(CropChrome)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Text(
                    text = "Crop",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                // Drawn only until the full-resolution image is genuinely ready, so the two are
                // never composited on top of each other and the user never sees a low-detail page
                // once real pixels exist.
                val preview = instantPreview
                if (!fullResolutionReady && preview != null) {
                    val previewBitmap = remember(preview) { preview.asImageBitmap() }
                    Image(
                        bitmap = previewBitmap,
                        contentDescription = "Captured page",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Image(
                    painter = painter,
                    contentDescription = if (fullResolutionReady) "Captured page" else null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                if (decoding) {
                    MainScanProcessingCard()
                }
            }
        }
    }
}

/**
 * The centred processing card: a small rounded surface holding an indeterminate indicator and the
 * `Processing…` label. Sized to its content so the retained page stays visible around it — the
 * reference never dims or replaces the image while processing.
 */
@Composable
private fun MainScanProcessingCard() {
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
