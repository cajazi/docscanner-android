package com.dev.docscannerpdf.ui.idcard

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Compare
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterVintage
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dev.docscannerpdf.domain.filter.DocumentFilter
import com.dev.docscannerpdf.domain.idscan.IdCardReviewSide
import com.dev.docscannerpdf.domain.idscan.IdCardReviewSlotPlanner
import com.dev.docscannerpdf.domain.idscan.IdCardReviewState
import com.dev.docscannerpdf.domain.pdf.CardRect
import com.dev.docscannerpdf.ui.ImportedImageBitmap

private val ScreenBackground = Color(0xFF101114)
private val SelectedBorder = Color(0xFF9AA0AC)
private val UnselectedBorder = Color(0xFFE4E6EC)
private val SaveAccent = Color(0xFF16C89A)
private val TipBarBackground = Color(0xFF202124)
private val DarkChip = Color(0xFF2A2C31)
private val TileCornerRadius = 10.dp

/**
 * CamScanner-style ID-card post-capture review: a wide white page showing the COMPLETE captured
 * front (and back, when present) — each side contain-fit inside an equal slot, never cropped,
 * zoomed, or stretched — with CamScanner-matching title/tip/toolbar chrome. Purely a display
 * surface: the saved image, gallery export, and PDF pipelines are untouched by anything here.
 * Tapping either image ONLY selects it as the target for
 * Crop/Rotate/Filter — it never rotates or otherwise mutates the image; the user already
 * complained about cards rotating unexpectedly, so rotation happens solely through the explicit
 * Rotate toolbar button. The Filter button toggles a picker strip showing the shared
 * [DocumentFilter.CATALOG]; the tapped filter goes through [onSelectFilter] to the currently
 * selected side only. Nothing here touches the normal document scan/result flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdCardReviewScreen(
    state: IdCardReviewState,
    onBack: () -> Unit,
    onSelectSide: (IdCardReviewSide) -> Unit,
    onRenameTitle: (String) -> Unit,
    onHelp: () -> Unit,
    onCompare: () -> Unit,
    onCrop: () -> Unit,
    onRotate: () -> Unit,
    onSelectFilter: (DocumentFilter) -> Unit,
    onAddWatermark: () -> Unit,
    onSave: () -> Unit
) {
    DarkSystemBarsEffect()
    var showRenameDialog by remember { mutableStateOf(false) }
    var showFilterPicker by remember { mutableStateOf(false) }

    Scaffold(
        // Background BEFORE safeDrawingPadding: on edge-to-edge targets (35+),
        // window.statusBarColor is a no-op, so the area behind the status bar shows whatever is
        // painted under it — without this the review screen got a white status-bar strip. The
        // dark fill matches the dark app bar; DarkSystemBarsEffect keeps the icons light.
        modifier = Modifier
            .fillMaxSize()
            .background(TipBarBackground)
            .safeDrawingPadding(),
        topBar = {
            Column {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = state.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            IconButton(onClick = { showRenameDialog = true }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    Icons.Default.Edit,
                                    contentDescription = "Rename",
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onHelp) {
                            Icon(Icons.Default.HelpOutline, contentDescription = "Help")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color(0xFF202124),
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                        actionIconContentColor = Color.White
                    )
                )
                Surface(color = TipBarBackground) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = null,
                            tint = Color(0xFFB8BDC4),
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            // Truthful to actual behavior: tapping only selects; rotation is
                            // the explicit Rotate tool's job (tap-to-rotate must never return).
                            text = "Tap an image to select it. Use Rotate if needed.",
                            color = Color(0xFFB8BDC4),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        bottomBar = {
            Column {
                if (showFilterPicker) {
                    IdCardFilterPickerStrip(
                        selectedFilter = state.filter(state.selectedSide),
                        onSelectFilter = onSelectFilter
                    )
                }
                IdCardReviewToolbar(
                    onCrop = onCrop,
                    onRotate = onRotate,
                    onFilter = { showFilterPicker = !showFilterPicker },
                    filterActive = showFilterPicker,
                    onAddWatermark = onAddWatermark,
                    onSave = onSave
                )
            }
        },
        containerColor = ScreenBackground
    ) { innerPadding ->
        // CamScanner-matching canvas: a wide white page with ~14dp side margins that starts
        // just below the instruction row (no big dark gap) but ends visibly ABOVE the bottom
        // controls — the reference leaves a clear dark band there. The clearance adapts:
        // ~52dp normally, proportionally less on short screens (never negative canvas), and
        // recomputes automatically when the filter strip opens (innerPadding grows) without
        // touching ContentScale.Fit or the slot math.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val density = LocalDensity.current
            val bottomClearance = with(density) {
                IdCardReviewSlotPlanner.canvasBottomClearance(
                    availableHeightPx = maxHeight.toPx(),
                    preferredClearancePx = 52.dp.toPx()
                ).toDp()
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = bottomClearance)
            ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(0.dp),
                color = Color.White
            ) {
                BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                    val density = LocalDensity.current
                    val pageWidthPx = with(density) { maxWidth.toPx() }
                    val pageHeightPx = with(density) { maxHeight.toPx() }
                    val sideCount = if (state.backBaseImageUri != null) 2 else 1
                    // Equal OUTER slots only — the image inside each tile is contain-fit
                    // (never cropped/zoomed), so the complete capture is always visible.
                    val slotRects = IdCardReviewSlotPlanner.plan(sideCount, pageWidthPx, pageHeightPx)

                    IdCardReviewTile(
                        imageUri = Uri.parse(state.frontRenderedImageUri ?: state.frontBaseImageUri),
                        rotationDegrees = state.frontRotationDegrees,
                        selected = state.selectedSide == IdCardReviewSide.FRONT,
                        isRendering = state.isRenderPending(IdCardReviewSide.FRONT),
                        rect = slotRects[0],
                        density = density,
                        onClick = { onSelectSide(IdCardReviewSide.FRONT) }
                    )
                    val backDisplayUri = state.displayImageUri(IdCardReviewSide.BACK)
                    if (backDisplayUri != null) {
                        IdCardReviewTile(
                            imageUri = Uri.parse(backDisplayUri),
                            rotationDegrees = state.backRotationDegrees,
                            selected = state.selectedSide == IdCardReviewSide.BACK,
                            isRendering = state.isRenderPending(IdCardReviewSide.BACK),
                            rect = slotRects[1],
                            density = density,
                            onClick = { onSelectSide(IdCardReviewSide.BACK) }
                        )
                    }
                }
            }

            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
                shape = RoundedCornerShape(4.dp),
                color = Color(0xCC202124)
            ) {
                Text(
                    text = "01",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    color = Color.White,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            // The VISUAL chip stays compact (CamScanner's squared tab, ~5dp corners — not a
            // pill), while the clickable target is an outer transparent box guaranteed at
            // least 48dp tall/wide for accessibility. The invisible target never enlarges
            // the visible chip.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable(onClick = onCompare),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.padding(horizontal = 6.dp),
                    shape = RoundedCornerShape(5.dp),
                    color = DarkChip
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Compare,
                            contentDescription = "Compare",
                            tint = Color.White,
                            modifier = Modifier.size(14.dp)
                        )
                        Text("Compare", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        }
    }

    if (showRenameDialog) {
        IdCardRenameDialog(
            initialTitle = state.title,
            onDismiss = { showRenameDialog = false },
            onConfirm = { newTitle ->
                onRenameTitle(newTitle)
                showRenameDialog = false
            }
        )
    }
}

@Composable
private fun IdCardRenameDialog(
    initialTitle: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var draft by remember { mutableStateOf(initialTitle) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename ID card") },
        text = {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * A single captured side's preview tile. The slot ([rect]) is only an OUTER allocation — the
 * bitmap inside uses `ContentScale.Fit`, so the COMPLETE captured image is always visible at
 * its intrinsic aspect ratio (see [IdCardReviewSlotPlanner.containedImageSize] for the pure
 * specification). `Crop` is deliberately banned here: it zoomed into the picture and cut off
 * the left/right (and sometimes top/bottom) content whenever the capture wasn't exactly
 * card-ratio. Letterboxing against the white page is expected and correct. Selection (which
 * side Crop/Rotate/Filter target) shows as a subtle gray outline on the selected slot only.
 */
@Composable
private fun IdCardReviewTile(
    imageUri: Uri,
    rotationDegrees: Int,
    selected: Boolean,
    isRendering: Boolean,
    rect: CardRect,
    density: androidx.compose.ui.unit.Density,
    onClick: () -> Unit
) {
    val offsetX = with(density) { rect.left.toDp() }
    val offsetY = with(density) { rect.top.toDp() }
    val tileWidth = with(density) { rect.width.toDp() }
    val tileHeight = with(density) { rect.height.toDp() }
    // Rotation-aware inner container: for 90°/270° the container takes the slot's dimensions
    // SWAPPED, so after rotating around its center its on-screen footprint is exactly the
    // slot — the complete image stays visible and can never overflow into the other side.
    val innerSize = IdCardReviewSlotPlanner.rotationAwareContainerSize(
        slotWidth = rect.width,
        slotHeight = rect.height,
        rotationDegrees = rotationDegrees
    )
    val innerWidth = with(density) { innerSize.width.toDp() }
    val innerHeight = with(density) { innerSize.height.toDp() }
    Box(
        modifier = Modifier
            .offset(x = offsetX, y = offsetY)
            .size(width = tileWidth, height = tileHeight)
            .then(
                if (selected) {
                    Modifier.border(1.5.dp, SelectedBorder, RoundedCornerShape(TileCornerRadius))
                } else {
                    Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        ImportedImageBitmap(
            uri = imageUri,
            modifier = Modifier
                .size(width = innerWidth, height = innerHeight)
                .graphicsLayer { rotationZ = rotationDegrees.toFloat() },
            contentScale = androidx.compose.ui.layout.ContentScale.Fit
        )
        // Truthful processing state: while the selected filter's render is in flight the tile
        // shows the last valid image plus this small indicator — never silently presenting the
        // base as if the filter were already applied.
        if (isRendering) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(28.dp)
                    .background(Color(0x99202124), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

/**
 * The Filter button's picker: the complete shared [DocumentFilter.CATALOG] in exact catalog
 * order as a horizontally scrollable chip strip (so all ten entries stay reachable on small
 * screens), with the selected side's current filter highlighted in the app's accent color.
 * Tapping a chip applies that filter to the currently selected Front/Back side only.
 */
@Composable
private fun IdCardFilterPickerStrip(
    selectedFilter: DocumentFilter,
    onSelectFilter: (DocumentFilter) -> Unit
) {
    Surface(color = Color(0xFF1A1B1F)) {
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(DocumentFilter.CATALOG) { filter ->
                val selected = filter == selectedFilter
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onSelectFilter(filter) },
                    shape = RoundedCornerShape(6.dp),
                    color = if (selected) SaveAccent else DarkChip,
                    border = if (selected) {
                        androidx.compose.foundation.BorderStroke(1.dp, Color.White)
                    } else {
                        null
                    }
                ) {
                    Text(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        text = filter.displayName,
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * Bottom action row: Crop / Rotate / Filter / Watermark, plus the accent-colored Save check.
 * Rotate is deliberately an explicit button here — the only way to rotate a side — because
 * rotating on image tap made cards turn when the user just meant to select one. Filter toggles
 * the picker strip and tints while it is open.
 */
@Composable
private fun IdCardReviewToolbar(
    onCrop: () -> Unit,
    onRotate: () -> Unit,
    onFilter: () -> Unit,
    filterActive: Boolean,
    onAddWatermark: () -> Unit,
    onSave: () -> Unit
) {
    Surface(color = Color(0xFF202124)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IdCardReviewToolbarButton(label = "Crop", icon = Icons.Default.Crop, onClick = onCrop)
            IdCardReviewToolbarButton(label = "Rotate", icon = Icons.AutoMirrored.Filled.RotateRight, onClick = onRotate)
            IdCardReviewToolbarButton(
                label = "Filter",
                icon = Icons.Default.FilterVintage,
                tint = if (filterActive) SaveAccent else Color.White,
                onClick = onFilter
            )
            IdCardReviewToolbarButton(label = "Add Watermark", icon = Icons.Default.WaterDrop, onClick = onAddWatermark)
            Surface(
                modifier = Modifier
                    .clickable(onClick = onSave)
                    .size(width = 56.dp, height = 40.dp),
                shape = RoundedCornerShape(10.dp),
                color = SaveAccent
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Check, contentDescription = "Save", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun IdCardReviewToolbarButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint)
        Text(text = label, color = tint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
    }
}
