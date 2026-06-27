package com.dev.docscannerpdf.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import coil.compose.AsyncImage
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateLeft
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BorderColor
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Image as ImageIcon
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.runtime.mutableStateListOf
import com.dev.docscannerpdf.process.ScannerBackendProcessingState
import com.dev.docscannerpdf.process.ScannerFlowStage
import com.dev.docscannerpdf.process.ScannerFlowValidationState
import com.dev.docscannerpdf.process.ScannerOcrStatus
import com.dev.docscannerpdf.process.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageImportReviewScreen(
    imageUris: List<Uri>,
    currentIndex: Int,
    selectedIndices: Set<Int>,
    onBack: () -> Unit,
    onCurrentIndexChange: (Int) -> Unit,
    onToggleSelected: (Int) -> Unit,
    onImportSelected: () -> Unit
) {
    val selectedCount = selectedIndices.size
    val currentUri = imageUris.getOrNull(currentIndex)
    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Text(
                        text = "${currentIndex + 1}/${imageUris.size}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    Button(
                        enabled = selectedCount > 0,
                        onClick = onImportSelected,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF12BFA0),
                            disabledContainerColor = Color(0xFF3B3D42)
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(text = "Import($selectedCount)")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF2A2B2F),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF2A2B2F))
                    .navigationBarsPadding()
                    .padding(top = 10.dp, bottom = 12.dp)
            ) {
                if (imageUris.size > 1 || selectedCount > 0) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        itemsIndexed(imageUris) { index, uri ->
                            Box {
                                Surface(
                                    modifier = Modifier
                                        .width(64.dp)
                                        .height(82.dp)
                                        .border(
                                            2.dp,
                                            if (index == currentIndex) Color(0xFF12BFA0) else Color.Transparent,
                                            RoundedCornerShape(2.dp)
                                        )
                                        .clickable { onCurrentIndexChange(index) },
                                    shape = RoundedCornerShape(2.dp),
                                    color = Color.White
                                ) {
                                    ImportedImageBitmap(
                                        uri = uri,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                if (index in selectedIndices) {
                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .size(20.dp),
                                        shape = CircleShape,
                                        color = Color(0xFFFF5A5F)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Selected",
                                            modifier = Modifier.size(14.dp),
                                            tint = Color.White
                                        )
                                    }
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = currentIndex in selectedIndices,
                        onCheckedChange = { onToggleSelected(currentIndex) },
                        colors = CheckboxDefaults.colors(
                            checkedColor = Color(0xFF12BFA0),
                            uncheckedColor = Color(0xFF5E6067)
                        )
                    )
                    Text(
                        text = "Select",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF35D5B4)
                    )
                }
            }
        },
        containerColor = Color(0xFF111215)
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF111215))
                .padding(horizontal = 28.dp, vertical = 28.dp),
            contentAlignment = Alignment.Center
        ) {
            if (currentUri != null) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f / 1.414f),
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White
                ) {
                    ImportedImageBitmap(
                        uri = currentUri,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImageImportEditor(
    imageUri: Uri,
    title: String,
    extractedText: String?,
    isExtractingText: Boolean,
    rotationDegrees: Float,
    onBack: () -> Unit,
    onTitleChange: (String) -> Unit,
    onImport: () -> Unit,
    onRotateLeft: () -> Unit,
    onCrop: () -> Unit,
    onExtractText: () -> Unit,
    onEnhance: () -> Unit,
    onSign: () -> Unit,
    onConfirmSave: () -> Unit
) {
    val context = LocalContext.current
    var bitmap by remember(imageUri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(imageUri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                when (imageUri.scheme) {
                    "content" -> context.contentResolver.openInputStream(imageUri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                    "file" -> BitmapFactory.decodeFile(requireNotNull(imageUri.path))
                    null, "" -> BitmapFactory.decodeFile(imageUri.toString())
                    else -> null
                }
            }.getOrNull()
        }
    }

    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    TextField(
                        value = title,
                        onValueChange = onTitleChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold
                        ),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color(0xFF31D6A7),
                            unfocusedIndicatorColor = Color(0xFF5A5D64),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        )
                    )
                },
                actions = {
                    IconButton(onClick = { onTitleChange(title) }) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "Edit title"
                        )
                    }
                    IconButton(onClick = onImport) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More import options"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF17181B),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            ImageEditorBottomToolbar(
                isExtractingText = isExtractingText,
                onImport = onImport,
                onRotateLeft = onRotateLeft,
                onCrop = onCrop,
                onExtractText = onExtractText,
                onSign = onSign,
                onConfirmSave = onConfirmSave
            )
        },
        containerColor = Color(0xFF17181B)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF17181B)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 18.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f / 1.414f)
                        .shadow(8.dp, RoundedCornerShape(2.dp)),
                    shape = RoundedCornerShape(2.dp),
                    color = Color.White
                ) {
                    val importedBitmap = bitmap
                    if (importedBitmap != null) {
                        Image(
                            bitmap = importedBitmap.asImageBitmap(),
                            contentDescription = "Imported image preview",
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { rotationZ = rotationDegrees },
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Loading image",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF5F6368)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.width(132.dp).height(40.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF2B2C31)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "1/1",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
                Surface(
                    modifier = Modifier.height(40.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF2B2C31)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Apps,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "Compare",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                    }
                }
            }

            FilterStrip(onEnhance = onEnhance)

            extractedText
                ?.takeIf { it.isNotBlank() }
                ?.let { text ->
                    Text(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFB8BDC4),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportedImageDocumentPreview(
    imageUri: Uri,
    title: String,
    rotationDegrees: Float,
    backendProcessingState: ScannerBackendProcessingState = ScannerBackendProcessingState.Idle,
    validationState: ScannerFlowValidationState = ScannerFlowValidationState(),
    onProcessWithBackend: () -> Unit = {},
    onRetryBackendProcessing: () -> Unit = {},
    onRunValidation: () -> Unit = {},
    onRetryValidation: () -> Unit = {},
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onToWord: () -> Unit,
    onSign: () -> Unit,
    onRotate: () -> Unit,
    onSaveToGallery: () -> Unit,
    onMenu: () -> Unit
) {
    var showMenuOptions by remember { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.safeDrawingPadding(),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                actions = {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Edit title")
                    }
                    Surface(
                        modifier = Modifier
                            .height(28.dp)
                            .clickable { },
                        shape = RoundedCornerShape(5.dp),
                        color = Color.Transparent,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF5E6067))
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "Tags+",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                        }
                    }
                    IconButton(onClick = { }) {
                        Icon(Icons.Default.Apps, contentDescription = "Pages grid")
                    }
                    Box {
                        IconButton(onClick = { showMenuOptions = !showMenuOptions }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More actions")
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = showMenuOptions,
                            onDismissRequest = { showMenuOptions = false },
                            modifier = Modifier.background(Color(0xFF2A2B2F))
                        ) {
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Save to gallery", color = Color.White) },
                                onClick = {
                                    onSaveToGallery()
                                    showMenuOptions = false
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Print", color = Color.White) },
                                onClick = {
                                    onMenu()
                                    showMenuOptions = false
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Delete", color = Color.White) },
                                onClick = {
                                    onMenu()
                                    showMenuOptions = false
                                }
                            )
                            androidx.compose.material3.DropdownMenuItem(
                                text = { Text("Rename", color = Color.White) },
                                onClick = {
                                    onMenu()
                                    showMenuOptions = false
                                }
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF202124),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        bottomBar = {
            FinalPreviewToolbar(
                onAdd = onAdd,
                onEdit = onEdit,
                onShare = onShare,
                onToWord = onToWord,
                onSign = onSign,
                onRotate = onRotate
            )
        },
        containerColor = Color(0xFF101114)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFF101114))
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f / 1.414f),
                    shape = RoundedCornerShape(0.dp),
                    color = Color.White
                ) {
                    Box {
                        ImportedImageBitmap(
                            uri = imageUri,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer { rotationZ = rotationDegrees },
                            contentScale = ContentScale.Fit
                        )
                        Surface(
                            modifier = Modifier.align(Alignment.TopStart),
                            color = Color.Black.copy(alpha = 0.62f),
                            shape = RoundedCornerShape(bottomEnd = 3.dp)
                        ) {
                            Text(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                                text = "1/1",
                                color = Color.White,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }

            PreviewFilterStrip()

            ScannerBackendProcessingPanel(
                state = backendProcessingState,
                onProcessWithBackend = onProcessWithBackend,
                onRetry = onRetryBackendProcessing
            )

            ScannerFlowValidationSection(
                state = validationState,
                onRunValidation = onRunValidation,
                onRetryValidation = onRetryValidation
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(60.dp),
                color = Color.Black
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "Document Ready",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun ScannerBackendProcessingPanel(
    state: ScannerBackendProcessingState,
    onProcessWithBackend: () -> Unit,
    onRetry: () -> Unit
) {
    val active = state.isActive
    val statusText = when (state) {
        ScannerBackendProcessingState.Idle -> "Ready for backend processing"
        is ScannerBackendProcessingState.Uploading -> "Uploading"
        ScannerBackendProcessingState.Processing -> "Processing"
        is ScannerBackendProcessingState.Polling -> "Polling ${state.attempt}/${state.maxAttempts}: ${state.latestStatus.status}"
        is ScannerBackendProcessingState.CompletedWithImage -> "Backend accepted request"
        is ScannerBackendProcessingState.CompletedWithoutImage -> state.reason
        is ScannerBackendProcessingState.Error -> state.message
    }
    val accentColor = when (state) {
        is ScannerBackendProcessingState.CompletedWithImage -> Color(0xFF16C89A)
        is ScannerBackendProcessingState.CompletedWithoutImage -> Color(0xFFF6C85F)
        is ScannerBackendProcessingState.Error -> Color(0xFFFF6B6B)
        else -> Color(0xFF35D5B4)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF202124),
        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "Backend Processing",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD6D9DE),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Button(
                    enabled = !active,
                    onClick = if (state is ScannerBackendProcessingState.Error) onRetry else onProcessWithBackend,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        disabledContainerColor = Color(0xFF4B4D53)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = if (state is ScannerBackendProcessingState.Error) {
                            Icons.Default.Refresh
                        } else {
                            Icons.Default.CloudUpload
                        },
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (state is ScannerBackendProcessingState.Error) "Retry" else "Process with Backend",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1
                    )
                }
            }

            when (state) {
                is ScannerBackendProcessingState.Uploading -> {
                    if (state.progressFraction != null) {
                        LinearProgressIndicator(
                            progress = { state.progressFraction.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = accentColor,
                            trackColor = Color(0xFF3A3C42)
                        )
                    } else {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = accentColor,
                            trackColor = Color(0xFF3A3C42)
                        )
                    }
                }
                ScannerBackendProcessingState.Processing,
                is ScannerBackendProcessingState.Polling -> {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = accentColor,
                        trackColor = Color(0xFF3A3C42)
                    )
                }
                is ScannerBackendProcessingState.CompletedWithImage -> {
                    BackendResultIds(
                        documentId = state.documentId,
                        pageId = state.pageId,
                        processJobId = state.processJobId
                    )
                    AsyncImage(
                        model = state.url,
                        contentDescription = "Processed backend image",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.Black),
                        contentScale = ContentScale.Fit
                    )
                }
                is ScannerBackendProcessingState.CompletedWithoutImage -> {
                    BackendResultIds(
                        documentId = state.documentId,
                        pageId = state.pageId,
                        processJobId = state.processJobId
                    )
                }
                ScannerBackendProcessingState.Idle,
                is ScannerBackendProcessingState.Error -> Unit
            }
        }
    }
}

@Composable
private fun BackendResultIds(
    documentId: String?,
    pageId: String?,
    processJobId: String?
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        documentId?.let { BackendResultLine(label = "Document ID", value = it) }
        pageId?.let { BackendResultLine(label = "Page ID", value = it) }
        processJobId?.let { BackendResultLine(label = "Process Job ID", value = it) }
    }
}

@Composable
private fun BackendResultLine(
    label: String,
    value: String
) {
    Text(
        text = "$label: $value",
        style = MaterialTheme.typography.labelSmall,
        color = Color(0xFFB8BDC4),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun ScannerFlowValidationSection(
    state: ScannerFlowValidationState,
    onRunValidation: () -> Unit,
    onRetryValidation: () -> Unit
) {
    val active = state.isActive
    val accentColor = when (state.stage) {
        ScannerFlowStage.COMPLETED -> Color(0xFF16C89A)
        ScannerFlowStage.ERROR -> Color(0xFFFF6B6B)
        else -> Color(0xFF6C8CFF)
    }
    val ocrStatusLabel = when (state.ocrStatus) {
        ScannerOcrStatus.PENDING -> "Pending"
        ScannerOcrStatus.FETCHING -> "Fetching…"
        ScannerOcrStatus.AVAILABLE -> "Available"
        ScannerOcrStatus.EMPTY -> "Empty (backend returned no text)"
        ScannerOcrStatus.FAILED -> "Failed"
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF1B1C20),
        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = "E2E Flow Validation",
                        style = MaterialTheme.typography.titleSmall,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${state.stage.name.replace('_', ' ')} — ${state.statusMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD6D9DE),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Button(
                    enabled = !active,
                    onClick = if (state.isError) onRetryValidation else onRunValidation,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accentColor,
                        disabledContainerColor = Color(0xFF4B4D53)
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = if (state.isError) Icons.Default.Refresh else Icons.Default.CloudUpload,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (state.isError) "Retry" else "Run Validation",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1
                    )
                }
            }

            if (active && state.stage != ScannerFlowStage.IMAGE_SELECTED) {
                val progress = state.uploadProgress
                if (progress != null) {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth(),
                        color = accentColor,
                        trackColor = Color(0xFF3A3C42)
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = accentColor,
                        trackColor = Color(0xFF3A3C42)
                    )
                }
            }

            if (state.stage != ScannerFlowStage.IDLE) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    state.documentId?.let { BackendResultLine(label = "Document ID", value = it) }
                    state.pageId?.let { BackendResultLine(label = "Page ID", value = it) }
                    state.processJobId?.let { BackendResultLine(label = "Process Job ID", value = it) }
                    state.processedImageUrl?.let { BackendResultLine(label = "processedImageUrl", value = it) }
                    BackendResultLine(label = "OCR status", value = ocrStatusLabel)
                }
            }

            state.processedImageUrl?.let { url ->
                AsyncImage(
                    model = url,
                    contentDescription = "Processed backend image",
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black),
                    contentScale = ContentScale.Fit
                )
            }

            state.ocrTextPreview?.takeIf { it.isNotBlank() }?.let { preview ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFF101114)
                ) {
                    Text(
                        modifier = Modifier.padding(10.dp),
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFD6D9DE),
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            if (state.ocrStatus == ScannerOcrStatus.EMPTY) {
                Text(
                    text = "Backend OCR returned no text for this page.",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFF6C85F)
                )
            }

            state.failureReason?.let { reason ->
                Text(
                    text = "Failure: $reason",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFFF6B6B),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ImageEditorBottomToolbar(
    isExtractingText: Boolean,
    onImport: () -> Unit,
    onRotateLeft: () -> Unit,
    onCrop: () -> Unit,
    onExtractText: () -> Unit,
    onSign: () -> Unit,
    onConfirmSave: () -> Unit
) {
    val scrollState = rememberScrollState()
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF202124))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scrollState)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            EditorToolButton(
                label = "Import",
                icon = Icons.Default.ImageIcon,
                onClick = onImport,
                enabled = true
            )
            EditorToolButton(
                label = "Left",
                icon = Icons.AutoMirrored.Filled.RotateLeft,
                onClick = onRotateLeft,
                enabled = true
            )
            EditorToolButton(
                label = "Crop",
                icon = Icons.Default.Crop,
                onClick = onCrop,
                enabled = true
            )
            EditorToolButton(
                label = if (isExtractingText) "Reading" else "Extract Text",
                icon = Icons.Default.TextFields,
                onClick = onExtractText,
                enabled = !isExtractingText
            )
            EditorToolButton(
                label = "Sign",
                icon = Icons.Default.BorderColor,
                onClick = onSign,
                enabled = true
            )
            Spacer(modifier = Modifier.width(4.dp))
            Surface(
                modifier = Modifier
                    .height(48.dp)
                    .widthIn(min = 72.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = true, onClick = onConfirmSave),
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF16C89A)
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Confirm and save",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterStrip(
    onEnhance: () -> Unit
) {
    val filters = listOf(
        "Enhance",
        "Magic Pro",
        "No Watermark",
        "No Shadow",
        "No Handwriting"
    )
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF202124))
            .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        itemsIndexed(filters) { index, label ->
            Surface(
                modifier = Modifier
                    .width(92.dp)
                    .height(64.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { if (index == 0) onEnhance() },
                shape = RoundedCornerShape(6.dp),
                color = if (index == 0) Color(0xFF12BFA0) else Color(0xFF3A3B40)
            ) {
                Box(
                    modifier = Modifier.padding(6.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorToolButton(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Column(
        modifier = Modifier
            .width(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(PaddingValues(vertical = 4.dp))
            .alpha(if (enabled) 1f else 0.5f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            shape = CircleShape,
            color = Color.Transparent
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun FinalPreviewToolbar(
    onAdd: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onToWord: () -> Unit,
    onSign: () -> Unit,
    onRotate: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF2A2B2F))
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        EditorToolButton(label = "Add", icon = Icons.Default.Add, onClick = onAdd)
        EditorToolButton(label = "Edit", icon = Icons.Default.Edit, onClick = onEdit)
        EditorToolButton(label = "Rotate", icon = Icons.AutoMirrored.Filled.RotateLeft, onClick = onRotate)
        EditorToolButton(label = "Share", icon = Icons.Default.Share, onClick = onShare)
        EditorToolButton(label = "To Word", icon = Icons.Default.TextFields, onClick = onToWord)
        EditorToolButton(label = "Sign", icon = Icons.Default.BorderColor, onClick = onSign)
    }
}

@Composable
private fun ImportedImageBitmap(
    uri: Uri,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit
) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                when (uri.scheme) {
                    "content" -> context.contentResolver.openInputStream(uri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                    "file" -> BitmapFactory.decodeFile(requireNotNull(uri.path))
                    null, "" -> BitmapFactory.decodeFile(uri.toString())
                    else -> null
                }
            }.getOrNull()
        }
    }

    val loadedBitmap = bitmap
    if (loadedBitmap != null) {
        Image(
            bitmap = loadedBitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = contentScale
        )
    } else {
        Box(
            modifier = modifier.background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Loading image",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF5F6368)
            )
        }
    }
}

@Composable
fun SignaturePadDialog(
    onDismiss: () -> Unit,
    onConfirm: (List<List<Offset>>) -> Unit
) {
    val strokes = remember { mutableStateListOf<MutableList<Offset>>() }

    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sign document", color = Color.White) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(240.dp)
                    .background(Color(0xFF1A1B1F))
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                strokes.add(mutableListOf(offset))
                            },
                            onDrag = { change, dragAmount ->
                                val last = strokes.lastOrNull()
                                last?.add(change.position)
                            },
                            onDragEnd = {}
                        )
                    }
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    strokes.forEach { stroke ->
                        if (stroke.size > 1) {
                            for (i in 0 until stroke.size - 1) {
                                val a = stroke[i]
                                val b = stroke[i + 1]
                                drawLine(
                                    color = Color.White,
                                    start = a,
                                    end = b,
                                    strokeWidth = 6f
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(strokes.map { it.toList() }); onDismiss() }) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

@Composable
private fun PreviewFilterStrip() {
    val filters = listOf(
        FilterOption("Enhance", 0xFF12BFA0),
        FilterOption("Brightness", 0xFF3A3B40),
        FilterOption("Contrast", 0xFF3A3B40),
        FilterOption("Sharpen", 0xFF3A3B40),
        FilterOption("B&W", 0xFF3A3B40),
        FilterOption("Sepia", 0xFF3A3B40),
        FilterOption("Gray", 0xFF3A3B40),
        FilterOption("Warm", 0xFF3A3B40),
        FilterOption("Cool", 0xFF3A3B40)
    )
    
    LazyRow(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF1A1B1F))
            .padding(vertical = 8.dp),
        contentPadding = PaddingValues(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        itemsIndexed(filters) { index, filter ->
            Surface(
                modifier = Modifier
                    .width(85.dp)
                    .height(56.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { },
                shape = RoundedCornerShape(4.dp),
                color = Color(filter.colorLong)
            ) {
                Box(
                    modifier = Modifier.padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = filter.name,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = androidx.compose.ui.unit.TextUnit(10f, androidx.compose.ui.unit.TextUnitType.Sp)
                    )
                }
            }
        }
    }
}

private data class FilterOption(
    val name: String,
    val colorLong: Long
)
