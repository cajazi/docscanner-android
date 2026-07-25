package com.dev.docscannerpdf.ui.idcard

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dev.docscannerpdf.domain.filter.DocumentFilter
import com.dev.docscannerpdf.domain.idscan.PassportFilterGrid
import com.dev.docscannerpdf.domain.idscan.PassportPreviewDecoder
import com.dev.docscannerpdf.domain.idscan.PassportPreviewFrame
import com.dev.docscannerpdf.domain.idscan.PassportReviewState

private val ScreenBackground = Color(0xFF101114)
private val ChromeColor = Color(0xFF202124)
private val AccentColor = Color(0xFF16C89A)
private val DarkChip = Color(0xFF2A2C31)
private val PanelColor = Color(0xFF1A1B1F)

/**
 * The dedicated single-page PASSPORT review — deliberately NOT the generic Document Ready
 * screen. It shows the complete captured page upright and as large as the screen allows
 * (`ContentScale.Fit`, stable container) and exposes exactly the five approved passport tools:
 * Crop, Rotate, Filter, Add Watermark and the accent Confirm.
 *
 * Every editing operation gives INSTANT feedback: the host publishes an in-memory preview
 * bitmap on [previewFrame] the moment the user taps, and the authoritative full-resolution
 * pixels atomically replace it when they settle. There is NO circular loading indicator after
 * an editing tap — the only pending indication is the thin accent progress line under the
 * title bar ([PassportReviewState.workInFlight]); the image and toolbar are never covered, and
 * the preview container never changes size. Confirm stays disabled until every authoritative
 * render settles, so the saved pixels always equal the settled displayed image.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PassportReviewScreen(
    state: PassportReviewState,
    previewFrame: PassportPreviewFrame?,
    filterThumbnails: Map<DocumentFilter, Bitmap>,
    onBack: () -> Unit,
    onCrop: () -> Unit,
    onRotate: () -> Unit,
    onSelectFilter: (DocumentFilter) -> Unit,
    onSetWatermark: (String?) -> Unit,
    onConfirm: () -> Unit
) {
    DarkSystemBarsEffect()
    var showFilterPicker by remember { mutableStateOf(false) }
    var showWatermarkDialog by remember { mutableStateOf(false) }
    // While a save runs, the review is an immutable transaction: every editing action and Back
    // are locked so the saved pixels can never diverge from what was confirmed.
    val locked = state.saveInProgress
    // While a requested rotation is still resolving, or a crop is settling into its new base,
    // Crop/Filter/Watermark are disabled so they can't mutate the page mid-transition. Rotate
    // stays enabled during rotation resolution so rapid taps queue via generation tokens.
    val editLocked = locked || state.rotationUnresolved || state.cropRenderPending

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(ChromeColor)
            .safeDrawingPadding(),
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack, enabled = !locked) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        Text(
                            text = state.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = ChromeColor,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White
                    )
                )
                // The single, subtle pending indication: a thin 2dp progress line under the
                // title bar while authoritative full-resolution work settles behind the instant
                // preview. The 2dp slot is ALWAYS reserved so the preview container below never
                // changes size, and nothing ever covers the image or the toolbar.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                ) {
                    if (state.workInFlight) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxSize(),
                            color = AccentColor,
                            trackColor = ChromeColor
                        )
                    }
                }
            }
        },
        bottomBar = {
            Column {
                if (showFilterPicker) {
                    PassportFilterPanel(
                        selectedFilter = state.selectedFilter,
                        thumbnails = filterThumbnails,
                        onSelectFilter = onSelectFilter
                    )
                }
                PassportReviewToolbar(
                    onCrop = onCrop,
                    onRotate = onRotate,
                    onFilter = { showFilterPicker = !showFilterPicker },
                    filterActive = showFilterPicker,
                    onWatermark = { showWatermarkDialog = true },
                    watermarkActive = state.watermarkText != null,
                    rotateLocked = locked || state.cropRenderPending,
                    editLocked = editLocked,
                    // The Confirm spinner shows ONLY while a save is genuinely running — never
                    // after an editing tap (pending renders show the thin line instead).
                    saving = state.saveInProgress,
                    confirmEnabled = state.canConfirm,
                    onConfirm = onConfirm
                )
            }
        },
        containerColor = ScreenBackground
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 12.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            // The preview draws whatever the host last published: the INSTANT in-memory preview
            // right after a tap, atomically replaced by the settled authoritative pixels. All
            // frames carry their rotation baked into the PIXELS and are shown with NO
            // display-rotation transform, so ContentScale.Fit contains the whole page at every
            // angle. The last frame stays visible until the next swaps in — no blank, no white
            // flash, no jump, no spinner over the image.
            PassportReviewImage(
                frame = previewFrame,
                fallbackUriString = state.displayedUri,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (showWatermarkDialog) {
        PassportWatermarkDialog(
            initialText = state.watermarkText.orEmpty(),
            onDismiss = { showWatermarkDialog = false },
            onConfirm = { text ->
                // The dialog (and its keyboard) closes immediately; the watermark preview is
                // published by the host in the same tap.
                onSetWatermark(text)
                showWatermarkDialog = false
            },
            onRemove = {
                onSetWatermark(null)
                showWatermarkDialog = false
            }
        )
    }
}

@Composable
private fun PassportWatermarkDialog(
    initialText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
    onRemove: () -> Unit
) {
    var draft by remember { mutableStateOf(initialText) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add watermark") },
        text = {
            Column {
                Text(
                    text = "The watermark is applied on this device only and appears exactly as previewed.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    label = { Text("Watermark text") }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }, enabled = draft.isNotBlank()) { Text("Apply") }
        },
        dismissButton = {
            Row {
                if (initialText.isNotEmpty()) {
                    TextButton(onClick = onRemove) { Text("Remove") }
                }
                // Cancel leaves the unwatermarked (or previously watermarked) state untouched.
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

/**
 * The full-width dark filter panel above the bottom toolbar: header on top, then a FOUR-column
 * thumbnail grid whose first two rows (eight filters) are visible without any horizontal
 * scrolling — further filters are reachable by vertical scroll. Every thumbnail shows the
 * ACTUAL effect applied to the user's current passport page (generated in memory by the host
 * from one shared small thumbnail; no artwork or branding is copied from anywhere). The
 * selected cell gets a 2dp turquoise border and a turquoise label; unselected labels are white.
 */
@Composable
private fun PassportFilterPanel(
    selectedFilter: DocumentFilter,
    thumbnails: Map<DocumentFilter, Bitmap>,
    onSelectFilter: (DocumentFilter) -> Unit
) {
    Surface(color = PanelColor) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .height(292.dp)
        ) {
            Text(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                text = PassportFilterGrid.HEADER,
                color = Color.White,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(PassportFilterGrid.COLUMN_COUNT),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(PassportFilterGrid.ORDER) { filter ->
                    PassportFilterCell(
                        filter = filter,
                        thumbnail = thumbnails[filter],
                        selected = filter == selectedFilter,
                        onClick = { onSelectFilter(filter) }
                    )
                }
            }
        }
    }
}

/** One grid cell: a square thumbnail with the filter's effect and the name centred below it. */
@Composable
private fun PassportFilterCell(
    filter: DocumentFilter,
    thumbnail: Bitmap?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(2.dp)
            .semantics { contentDescription = "Filter ${filter.displayName}" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = DarkChip,
            border = if (selected) BorderStroke(2.dp, AccentColor) else null
        ) {
            Box(modifier = Modifier.size(64.dp), contentAlignment = Alignment.Center) {
                if (thumbnail != null) {
                    Image(
                        bitmap = thumbnail.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        }
        Text(
            text = filter.displayName,
            color = if (selected) AccentColor else Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
    }
}

/**
 * The passport preview image, driven by the host's frame stream: the moment an editing tap
 * publishes an in-memory preview it swaps in, and the settled authoritative pixels atomically
 * replace it later. The previous frame stays visible until the next one is ready — no blank
 * state, no white flash, no shrink — and the container size is fixed by the caller. Compose
 * itself guarantees the old bitmap is no longer drawn once `shown` moves on (bitmaps are
 * GC-released by the host, never recycled while displayable). The [fallbackUriString] covers
 * only the very first paint of a fresh review, decoded downscaled off the main thread; before
 * it lands the dark review background shows (never white).
 */
@Composable
private fun PassportReviewImage(
    frame: PassportPreviewFrame?,
    fallbackUriString: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(frame) {
        if (frame != null) shown = frame.bitmap
    }
    LaunchedEffect(fallbackUriString) {
        if (frame == null && shown == null) {
            val decoded = PassportPreviewDecoder.decodeDownscaled(context, Uri.parse(fallbackUriString))
            // Adopt only if no published frame beat the fallback decode.
            if (decoded != null && shown == null) shown = decoded
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        val current = shown
        if (current != null) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit
            )
        } else {
            // First load only: the dark review background (never a white page, never a spinner).
            Box(modifier = Modifier.fillMaxSize().background(ScreenBackground))
        }
    }
}

/**
 * The five approved passport actions. Deliberately excludes every generic document control
 * (Backend Processing, E2E Flow Validation, To Word, Add) — those must never appear in a
 * release passport review. All targets are >= 48dp with content descriptions.
 */
@Composable
private fun PassportReviewToolbar(
    onCrop: () -> Unit,
    onRotate: () -> Unit,
    onFilter: () -> Unit,
    filterActive: Boolean,
    onWatermark: () -> Unit,
    watermarkActive: Boolean,
    rotateLocked: Boolean,
    editLocked: Boolean,
    saving: Boolean,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit
) {
    Surface(color = ChromeColor) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            PassportToolButton("Crop", Icons.Default.Crop, enabled = !editLocked, onClick = onCrop)
            PassportToolButton(
                label = "Rotate",
                icon = Icons.AutoMirrored.Filled.RotateRight,
                enabled = !rotateLocked,
                onClick = onRotate
            )
            PassportToolButton(
                label = "Filter",
                icon = Icons.Default.FilterVintage,
                tint = if (filterActive) AccentColor else Color.White,
                enabled = !editLocked,
                onClick = onFilter
            )
            PassportToolButton(
                label = "Add Watermark",
                icon = Icons.Default.WaterDrop,
                tint = if (watermarkActive) AccentColor else Color.White,
                enabled = !editLocked,
                onClick = onWatermark
            )
            Surface(
                modifier = Modifier
                    .sizeIn(minWidth = 56.dp, minHeight = 48.dp)
                    .clickable(enabled = confirmEnabled, onClick = onConfirm)
                    .semantics { contentDescription = "Confirm and save passport" },
                shape = RoundedCornerShape(10.dp),
                color = if (confirmEnabled) AccentColor else Color(0xFF5E6067)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
                    }
                }
            }
        }
    }
}

@Composable
private fun PassportToolButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color = Color.White,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val effectiveTint = if (enabled) tint else tint.copy(alpha = 0.4f)
    Column(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = effectiveTint)
        Text(text = label, color = effectiveTint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
