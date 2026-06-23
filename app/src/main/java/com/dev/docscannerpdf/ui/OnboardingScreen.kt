package com.dev.docscannerpdf.ui

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(
    onComplete: () -> Unit
) {
    val pages = onboardingPages
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val isLastPage = pagerState.currentPage == pages.lastIndex

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1014))
            .padding(20.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            TextButton(
                onClick = onComplete,
                modifier = Modifier.semantics { contentDescription = "Skip onboarding" }
            ) {
                Text(text = "Skip")
            }
        }

        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) { pageIndex ->
            OnboardingPageContent(
                page = pages[pageIndex],
                pageIndex = pageIndex,
                pageCount = pages.size
            )
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            PageIndicator(
                pageCount = pages.size,
                selectedPage = pagerState.currentPage
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (pagerState.currentPage > 0) {
                    OutlinedButton(
                        modifier = Modifier.weight(1f),
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage - 1)
                            }
                        }
                    ) {
                        Text(text = "Back")
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                Button(
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = if (isLastPage) "Get started" else "Next onboarding page"
                        },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF49D9A8)),
                    onClick = {
                        if (isLastPage) {
                            onComplete()
                        } else {
                            scope.launch {
                                pagerState.animateScrollToPage(pagerState.currentPage + 1)
                            }
                        }
                    }
                ) {
                    Text(
                        text = if (isLastPage) "Get Started" else "Next",
                        color = Color(0xFF07120E),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun OnboardingPageContent(
    page: OnboardingPage,
    pageIndex: Int,
    pageCount: Int
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 4.dp, vertical = 12.dp)
            .semantics {
                contentDescription = "${page.title}. ${page.description}. Page ${pageIndex + 1} of $pageCount."
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            modifier = Modifier.size(104.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF1B1F25)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = page.icon,
                    contentDescription = null,
                    tint = page.tint,
                    modifier = Modifier.size(48.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(34.dp))
        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFF2F5F7),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            text = page.description,
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFFB8C0CC),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 10.dp)
        )
    }
}

@Composable
private fun PageIndicator(
    pageCount: Int,
    selectedPage: Int
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "Onboarding page ${selectedPage + 1} of $pageCount" },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(pageCount) { index ->
            Surface(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(width = if (index == selectedPage) 24.dp else 8.dp, height = 8.dp),
                shape = CircleShape,
                color = if (index == selectedPage) Color(0xFF49D9A8) else Color(0xFF3A4048)
            ) {}
        }
    }
}

private data class OnboardingPage(
    val title: String,
    val description: String,
    val icon: ImageVector,
    val tint: Color
)

private val onboardingPages = listOf(
    OnboardingPage(
        title = "Welcome to DocScanner & PDF",
        description = "Scan, organize, protect, and work with documents from one private workspace.",
        icon = Icons.Default.DocumentScanner,
        tint = Color(0xFF49D9A8)
    ),
    OnboardingPage(
        title = "Smart Scan",
        description = "Capture documents quickly with clean edges, readable pages, and saved PDFs.",
        icon = Icons.Default.AutoAwesome,
        tint = Color(0xFFFFD166)
    ),
    OnboardingPage(
        title = "OCR & Text Extraction",
        description = "Extract searchable text from scans and images without sending document content anywhere.",
        icon = Icons.Default.DocumentScanner,
        tint = Color(0xFF8AB4F8)
    ),
    OnboardingPage(
        title = "PDF Tools",
        description = "Merge, split, compress, edit, sign, watermark, and convert PDFs when your workflow needs it.",
        icon = Icons.Default.PictureAsPdf,
        tint = Color(0xFFFF8A80)
    ),
    OnboardingPage(
        title = "Security & Backup",
        description = "Use App Lock, Trash, backups, and restore tools to keep important files safer.",
        icon = Icons.Default.Lock,
        tint = Color(0xFFB39DDB)
    ),
    OnboardingPage(
        title = "Premium Features",
        description = "Unlock unlimited cleanup, conversions, advanced editing, cloud sync, and premium templates.",
        icon = Icons.Default.WorkspacePremium,
        tint = Color(0xFFFFC857)
    )
)
