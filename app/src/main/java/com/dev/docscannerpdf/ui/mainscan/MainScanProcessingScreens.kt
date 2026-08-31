package com.dev.docscannerpdf.ui.mainscan

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
