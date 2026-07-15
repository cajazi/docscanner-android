package com.dev.docscannerpdf.ui.idcard

import android.net.Uri
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.AlertDialog
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
import com.dev.docscannerpdf.domain.idscan.IdCardReviewState
import com.dev.docscannerpdf.domain.pdf.CardRect
import com.dev.docscannerpdf.domain.pdf.IdCardLayoutPlanner
import com.dev.docscannerpdf.ui.ImportedImageBitmap

private val ScreenBackground = Color(0xFF101114)
private val SelectedBorder = Color(0xFF9AA0AC)
private val UnselectedBorder = Color(0xFFE4E6EC)
private val SaveAccent = Color(0xFF16C89A)
private val TipBarBackground = Color(0xFF202124)
private val DarkChip = Color(0xFF2A2C31)
private val TileCornerRadius = 10.dp

/**
 * CamScanner-style ID-card post-capture review: a large white A4-like page showing the captured
 * front (and back, when present) — already baked into landscape card crops by the guided capture
 * screen, so they fill their tiles with no large white side padding — with a CamScanner-matching
 * title/tip/toolbar chrome. Tapping either image ONLY selects it as the target for
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
        modifier = Modifier.safeDrawingPadding(),
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
                    Text(
                        text = "Tap an image to select it. Use Rotate if needed.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        color = Color(0xFFB8BDC4),
                        style = MaterialTheme.typography.bodySmall
                    )
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                // Same contain-fit rule as the final A4 preview: fit the page within both the
                // available width AND height so it renders as large as possible without being
                // squeezed by the bottom toolbar.
                val pageAspectRatio = 1f / 1.414f
                val pageWidth = minOf(maxWidth, maxHeight * pageAspectRatio)
                val pageHeight = pageWidth / pageAspectRatio

                Box(modifier = Modifier.width(pageWidth).height(pageHeight)) {
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
                            val cardRects = IdCardLayoutPlanner.plan(sideCount, pageWidthPx, pageHeightPx)

                            IdCardReviewTile(
                                imageUri = Uri.parse(state.frontRenderedImageUri ?: state.frontBaseImageUri),
                                rotationDegrees = state.frontRotationDegrees,
                                selected = state.selectedSide == IdCardReviewSide.FRONT,
                                rect = cardRects[0],
                                density = density,
                                onClick = { onSelectSide(IdCardReviewSide.FRONT) }
                            )
                            val backDisplayUri = state.displayImageUri(IdCardReviewSide.BACK)
                            if (backDisplayUri != null) {
                                IdCardReviewTile(
                                    imageUri = Uri.parse(backDisplayUri),
                                    rotationDegrees = state.backRotationDegrees,
                                    selected = state.selectedSide == IdCardReviewSide.BACK,
                                    rect = cardRects[1],
                                    density = density,
                                    onClick = { onSelectSide(IdCardReviewSide.BACK) }
                                )
                            }
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp),
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xCC202124)
                    ) {
                        Text(
                            text = "01",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .clickable(onClick = onCompare),
                        shape = RoundedCornerShape(100.dp),
                        color = DarkChip
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.Compare,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Text("Compare", color = Color.White, style = MaterialTheme.typography.labelMedium)
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
 * A single captured side's landscape card thumbnail. Deliberately has no visible "Front"/"Back"
 * label — CamScanner's review page keeps the white document page clean — and selection (which
 * side Crop/Filter currently target) is shown as a subtle neutral-gray border rather than a
 * strong blue editing outline.
 */
@Composable
private fun IdCardReviewTile(
    imageUri: Uri,
    rotationDegrees: Int,
    selected: Boolean,
    rect: CardRect,
    density: androidx.compose.ui.unit.Density,
    onClick: () -> Unit
) {
    val offsetX = with(density) { rect.left.toDp() }
    val offsetY = with(density) { rect.top.toDp() }
    val tileWidth = with(density) { rect.width.toDp() }
    val tileHeight = with(density) { rect.height.toDp() }
    Box(
        modifier = Modifier
            .offset(x = offsetX, y = offsetY)
            .size(width = tileWidth, height = tileHeight)
            .clip(RoundedCornerShape(TileCornerRadius))
            .border(
                width = if (selected) 1.5.dp else 1.dp,
                color = if (selected) SelectedBorder else UnselectedBorder,
                shape = RoundedCornerShape(TileCornerRadius)
            )
            .clickable(onClick = onClick)
    ) {
        // The guided capture screen already bakes each side into a tight landscape card
        // crop, so Crop (fill the tile, no letterboxing) is correct here — unlike a raw,
        // arbitrary-aspect photo, which would need Fit to avoid distorting/clipping content.
        ImportedImageBitmap(
            uri = imageUri,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = rotationDegrees.toFloat() },
            contentScale = androidx.compose.ui.layout.ContentScale.Crop
        )
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
            IdCardReviewToolbarButton(label = "Watermark", icon = Icons.Default.WaterDrop, onClick = onAddWatermark)
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
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(imageVector = icon, contentDescription = label, tint = tint)
        Text(text = label, color = tint, style = MaterialTheme.typography.labelSmall)
    }
}
