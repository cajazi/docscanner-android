package com.dev.docscannerpdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WorkspacePremium
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.dev.docscannerpdf.domain.billing.PremiumPlan
import com.dev.docscannerpdf.domain.billing.PremiumState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PremiumScreen(
    state: PremiumState,
    onBack: () -> Unit,
    onChoosePlan: (PremiumPlan) -> Unit,
    onRestorePurchases: () -> Unit,
    onManageSubscription: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Premium") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = onRestorePurchases) {
                        Text(text = "Restore")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                PremiumHero(isPremium = state.isPremium)
            }
            item {
                ComparisonTable()
            }
            items(state.plans, key = { plan -> plan.productId + plan.offerToken }) { plan ->
                PremiumPlanCard(
                    plan = plan,
                    isActive = state.activeProductId == plan.productId,
                    onChoosePlan = { onChoosePlan(plan) }
                )
            }
            if (state.plans.isEmpty()) {
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF1F2024)
                    ) {
                        Text(
                            modifier = Modifier.padding(16.dp),
                            text = if (state.isBillingReady) {
                                "Subscription plans are not available yet. Check your Play Console product IDs."
                            } else {
                                "Connecting to Google Play Billing..."
                            },
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            item {
                OutlinedButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onManageSubscription
                ) {
                    Text(text = "Manage subscription")
                }
            }
            state.statusMessage?.let { message ->
                item {
                    Text(
                        modifier = Modifier.fillMaxWidth(),
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun PremiumHero(isPremium: Boolean) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF1F2024)
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.WorkspacePremium,
                contentDescription = null,
                tint = Color(0xFFFFC857),
                modifier = Modifier.padding(top = 4.dp)
            )
            Text(
                text = if (isPremium) "Premium active" else "Unlock Doc Scanner Premium",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE8EAED),
                textAlign = TextAlign.Center
            )
            Text(
                text = "No ads, unlimited cleanup and conversions, premium watermark templates, and advanced PDF editing.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun ComparisonTable() {
    val rows = listOf(
        "No ads",
        "Unlimited OCR cleanup",
        "Unlimited PDF conversions",
        "Premium watermark templates",
        "Advanced PDF editing"
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFF1F2024)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = "Premium unlocks",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFE8EAED)
            )
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF49D9A8)
                    )
                    Text(
                        modifier = Modifier.weight(1f),
                        text = row,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFE8EAED)
                    )
                }
            }
        }
    }
}

@Composable
private fun PremiumPlanCard(
    plan: PremiumPlan,
    isActive: Boolean,
    onChoosePlan: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = if (isActive) Color(0xFF243A31) else Color(0xFF1F2024)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = plan.billingPeriod,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFFE8EAED)
                )
                Text(
                    text = plan.price,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Button(
                enabled = !isActive,
                onClick = onChoosePlan
            ) {
                Text(text = if (isActive) "Active" else "Choose")
            }
        }
    }
}
