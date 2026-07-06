package com.dev.docscannerpdf.ui.idcard

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.dev.docscannerpdf.domain.idscan.IdCardReviewSide
import com.dev.docscannerpdf.domain.idscan.IdCardReviewState
import com.dev.docscannerpdf.domain.pdf.CardRect
import com.dev.docscannerpdf.domain.pdf.IdCardLayoutPlanner
import com.dev.docscannerpdf.ui.ImportedImageBitmap

private val ScreenBackground = Color(0xFF101114)
private val Accent = Color(0xFF6C8CFF)
private val SaveAccent = Color(0xFF16C89A)

/**
 * CamScanner-style ID-card post-capture review: a large white A4-like page showing the captured
 * front (and back, when present) in their captured orientation, with Crop/Rotate/Enhance controls
 * that target whichever side is tapped/selected, and a Save (check) action that hands off to the
 * existing Document Ready preview. Nothing here touches the normal document scan/result flow.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IdCardReviewScreen(
    state: IdCardReviewState,
    onBack: () -> Unit,
    onSelectSide: (IdCardReviewSide) -> Unit,
    onRotateSelected: () -> Unit,
    onCrop: () -> Unit,
    onEnhance: () -> Unit,
    onSave: () -> Unit
) {
    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                title = {
                    Text(
                        text = "Review ID Card",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF202124),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            IdCardReviewToolbar(
                onCrop = onCrop,
                onRotate = onRotateSelected,
                onEnhance = onEnhance,
                onSave = onSave
            )
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

                Surface(
                    modifier = Modifier.width(pageWidth).height(pageHeight),
                    shape = RoundedCornerShape(0.dp),
                    color = Color.White
                ) {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val density = LocalDensity.current
                        val pageWidthPx = with(density) { maxWidth.toPx() }
                        val pageHeightPx = with(density) { maxHeight.toPx() }
                        val sideCount = if (state.backImageUri != null) 2 else 1
                        val cardRects = IdCardLayoutPlanner.plan(sideCount, pageWidthPx, pageHeightPx)

                        IdCardReviewTile(
                            imageUri = Uri.parse(state.frontImageUri),
                            label = "Front",
                            rotationDegrees = state.frontRotationDegrees,
                            selected = state.selectedSide == IdCardReviewSide.FRONT,
                            rect = cardRects[0],
                            density = density,
                            onClick = { onSelectSide(IdCardReviewSide.FRONT) }
                        )
                        val backUri = state.backImageUri
                        if (backUri != null) {
                            IdCardReviewTile(
                                imageUri = Uri.parse(backUri),
                                label = "Back",
                                rotationDegrees = state.backRotationDegrees,
                                selected = state.selectedSide == IdCardReviewSide.BACK,
                                rect = cardRects[1],
                                density = density,
                                onClick = { onSelectSide(IdCardReviewSide.BACK) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IdCardReviewTile(
    imageUri: Uri,
    label: String,
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
    Column(
        modifier = Modifier
            .offset(x = offsetX, y = offsetY)
            .size(width = tileWidth, height = tileHeight)
            .border(if (selected) 2.dp else 1.dp, if (selected) Accent else Color(0xFFD9DBE0))
            .clickable(onClick = onClick)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = if (selected) Accent.copy(alpha = 0.15f) else Color(0xFFEFF1F5)
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                text = label,
                color = if (selected) Accent else Color(0xFF3A3D45),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // The captured orientation is preserved by default (rotationDegrees starts at 0);
            // rotation only changes when the user taps the Rotate control for this side.
            ImportedImageBitmap(
                uri = imageUri,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationZ = rotationDegrees.toFloat() },
                contentScale = androidx.compose.ui.layout.ContentScale.Fit
            )
        }
    }
}

@Composable
private fun IdCardReviewToolbar(
    onCrop: () -> Unit,
    onRotate: () -> Unit,
    onEnhance: () -> Unit,
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
            IdCardReviewToolbarButton(label = "Enhance", icon = Icons.Default.AutoFixHigh, onClick = onEnhance)
            IdCardReviewToolbarButton(
                label = "Save",
                icon = Icons.Default.Check,
                tint = SaveAccent,
                onClick = onSave
            )
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
