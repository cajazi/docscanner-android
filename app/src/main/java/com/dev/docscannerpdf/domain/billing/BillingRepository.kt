package com.dev.docscannerpdf.domain.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.dev.docscannerpdf.domain.analytics.AnalyticsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PremiumPlan(
    val productId: String,
    val title: String,
    val price: String,
    val billingPeriod: String,
    val offerToken: String,
    val productDetails: ProductDetails
)

data class PremiumState(
    val isPremium: Boolean = false,
    val isBillingReady: Boolean = false,
    val plans: List<PremiumPlan> = emptyList(),
    val activeProductId: String? = null,
    val statusMessage: String? = null,
    val lastValidatedAt: Long? = null
)

class BillingRepository(
    context: Context,
    private val analyticsRepository: AnalyticsRepository
) : PurchasesUpdatedListener {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _premiumState = MutableStateFlow(loadCachedState())
    val premiumState: StateFlow<PremiumState> = _premiumState.asStateFlow()

    private val billingClient = BillingClient.newBuilder(appContext)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder()
                .enableOneTimeProducts()
                .enablePrepaidPlans()
                .build()
        )
        .build()

    fun start() {
        if (billingClient.isReady) {
            refreshPurchases()
            queryPlans()
            return
        }
        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                val ready = billingResult.responseCode == BillingClient.BillingResponseCode.OK
                _premiumState.update {
                    it.copy(
                        isBillingReady = ready,
                        statusMessage = if (ready) null else billingResult.debugMessage.takeIf(String::isNotBlank)
                    )
                }
                if (ready) {
                    refreshPurchases()
                    queryPlans()
                }
            }

            override fun onBillingServiceDisconnected() {
                _premiumState.update {
                    it.copy(isBillingReady = false, statusMessage = "Billing service disconnected.")
                }
            }
        })
    }

    fun launchPurchase(activity: Activity, plan: PremiumPlan) {
        val productDetailsParams = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(plan.productDetails)
            .setOfferToken(plan.offerToken)
            .build()
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(productDetailsParams))
            .build()
        billingClient.launchBillingFlow(activity, params)
    }

    fun restorePurchases() {
        refreshPurchases(showRestoredMessage = true)
    }

    fun manageSubscription(activity: Activity) {
        val productId = _premiumState.value.activeProductId ?: PREMIUM_MONTHLY_PRODUCT_ID
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://play.google.com/store/account/subscriptions?sku=$productId&package=${activity.packageName}")
        )
        activity.startActivity(intent)
    }

    override fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        if (billingResult.responseCode == BillingClient.BillingResponseCode.OK && purchases != null) {
            handlePurchases(purchases, "Premium unlocked.")
        } else if (billingResult.responseCode != BillingClient.BillingResponseCode.USER_CANCELED) {
            _premiumState.update {
                it.copy(statusMessage = billingResult.debugMessage.ifBlank { "Purchase failed." })
            }
        }
    }

    private fun queryPlans() {
        if (!billingClient.isReady) return
        val products = listOf(PREMIUM_MONTHLY_PRODUCT_ID, PREMIUM_YEARLY_PRODUCT_ID).map { productId ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(products)
            .build()
        billingClient.queryProductDetailsAsync(params) { billingResult, productDetailsResult ->
            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                _premiumState.update { it.copy(statusMessage = billingResult.debugMessage) }
                return@queryProductDetailsAsync
            }
            val plans = productDetailsResult.productDetailsList.flatMap { details ->
                details.subscriptionOfferDetails.orEmpty().mapNotNull { offer ->
                    val phase = offer.pricingPhases.pricingPhaseList.firstOrNull()
                    val periodLabel = when (details.productId) {
                        PREMIUM_YEARLY_PRODUCT_ID -> "Yearly"
                        else -> "Monthly"
                    }
                    phase?.let {
                        PremiumPlan(
                            productId = details.productId,
                            title = details.title.ifBlank { periodLabel },
                            price = it.formattedPrice,
                            billingPeriod = periodLabel,
                            offerToken = offer.offerToken,
                            productDetails = details
                        )
                    }
                }
            }.sortedBy { plan -> if (plan.productId == PREMIUM_MONTHLY_PRODUCT_ID) 0 else 1 }
            _premiumState.update { it.copy(plans = plans, statusMessage = null) }
        }
    }

    private fun refreshPurchases(showRestoredMessage: Boolean = false) {
        if (!billingClient.isReady) {
            start()
            return
        }
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()
        billingClient.queryPurchasesAsync(params) { billingResult, purchases ->
            if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                handlePurchases(
                    purchases,
                    if (showRestoredMessage) "Purchases restored." else null
                )
            } else {
                _premiumState.update { it.copy(statusMessage = billingResult.debugMessage) }
            }
        }
    }

    private fun handlePurchases(purchases: List<Purchase>, message: String?) {
        val activePurchase = purchases.firstOrNull { purchase ->
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
                purchase.products.any { productId -> productId in PREMIUM_PRODUCT_IDS }
        }
        activePurchase?.takeIf { purchase -> !purchase.isAcknowledged }?.let { purchase ->
            val params = AcknowledgePurchaseParams.newBuilder()
                .setPurchaseToken(purchase.purchaseToken)
                .build()
            billingClient.acknowledgePurchase(params) {}
        }

        val activeProductId = activePurchase?.products?.firstOrNull { it in PREMIUM_PRODUCT_IDS }
        val isPremium = activePurchase != null
        val wasPremium = _premiumState.value.isPremium
        cachePremiumState(isPremium, activeProductId)
        _premiumState.update {
            it.copy(
                isPremium = isPremium,
                activeProductId = activeProductId,
                lastValidatedAt = System.currentTimeMillis(),
                statusMessage = message ?: if (isPremium) null else "No active subscription found."
            )
        }
        if (isPremium && !wasPremium) {
            analyticsRepository.trackEvent(
                AnalyticsRepository.EVENT_PREMIUM_PURCHASED,
                mapOf("plan" to activeProductId.orEmpty())
            )
        }
    }

    private fun loadCachedState(): PremiumState {
        return PremiumState(
            isPremium = preferences.getBoolean(KEY_IS_PREMIUM, false),
            activeProductId = preferences.getString(KEY_ACTIVE_PRODUCT_ID, null),
            lastValidatedAt = preferences.getLong(KEY_LAST_VALIDATED_AT, 0L).takeIf { it > 0L }
        )
    }

    private fun cachePremiumState(isPremium: Boolean, activeProductId: String?) {
        preferences.edit()
            .putBoolean(KEY_IS_PREMIUM, isPremium)
            .putString(KEY_ACTIVE_PRODUCT_ID, activeProductId)
            .putLong(KEY_LAST_VALIDATED_AT, System.currentTimeMillis())
            .apply()
    }

    companion object {
        const val PREMIUM_MONTHLY_PRODUCT_ID = "docscanner_premium_monthly"
        const val PREMIUM_YEARLY_PRODUCT_ID = "docscanner_premium_yearly"
        val PREMIUM_PRODUCT_IDS = setOf(PREMIUM_MONTHLY_PRODUCT_ID, PREMIUM_YEARLY_PRODUCT_ID)

        private const val PREFS_NAME = "premium_billing"
        private const val KEY_IS_PREMIUM = "is_premium"
        private const val KEY_ACTIVE_PRODUCT_ID = "active_product_id"
        private const val KEY_LAST_VALIDATED_AT = "last_validated_at"
    }
}
