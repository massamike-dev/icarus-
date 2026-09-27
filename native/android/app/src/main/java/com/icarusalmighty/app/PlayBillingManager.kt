package com.icarusalmighty.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import org.json.JSONArray
import org.json.JSONObject

/** Play supplies prices and purchase tokens. Only the server grants/acknowledges Premium. */
class PlayBillingManager(private val activity: Activity, private val resultDispatcher: (String) -> Unit) {
    private data class Pending(val requestId: String, val productId: String, val binding: String)
    private var pending: Pending? = null
    private var loadingPurchase = false
    private var closed = false
    private var connecting = false
    private val handler = Handler(Looper.getMainLooper())
    private val waiting = mutableListOf<Pair<String, () -> Unit>>()
    private val connectionTimeout = Runnable { failWaiting("play_billing_timeout") }
    private val client = BillingClient.newBuilder(activity)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .setListener { result, purchases ->
            val request = pending ?: return@setListener // Restored on the next foreground query.
            pending = null
            loadingPurchase = false
            if (closed) return@setListener
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                error(request.requestId, errorCode(result)); return@setListener
            }
            val purchase = purchases?.firstOrNull {
                it.products.contains(request.productId) && it.accountIdentifiers?.obfuscatedAccountId == request.binding
            }
            if (purchase == null) error(request.requestId, "purchase_account_or_product_mismatch")
            else send(request.requestId, JSONObject().put("billingProtocolVersion", 2).put("purchase", purchaseJson(purchase)))
        }.build()

    // Reuses the existing native action. Older Android builds omit protocol v2,
    // so the web membership screen cannot accidentally initiate legacy checkout.
    fun checkSubscription(requestId: String) = connected(requestId) {
        queryProducts(requestId) { details ->
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { result, purchases ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) error(requestId, errorCode(result))
                else {
                    val catalog = JSONArray()
                    details.forEach { detail ->
                        baseOffers(detail).forEach { offer ->
                            val phase = offer.pricingPhases.pricingPhaseList.single()
                            catalog.put(JSONObject().put("productId", detail.productId)
                                .put("basePlanId", offer.basePlanId).put("offerToken", offer.offerToken)
                                .put("billingPeriod", phase.billingPeriod).put("formattedPrice", phase.formattedPrice)
                                .put("priceAmountMicros", phase.priceAmountMicros).put("currencyCode", phase.priceCurrencyCode))
                        }
                    }
                    send(requestId, JSONObject().put("billingProtocolVersion", 2).put("products", catalog)
                        .put("purchases", JSONArray(purchases.filter { it.products.any(SUPPORTED_PRODUCTS::contains) }.map(::purchaseJson))))
                }
            }
        }
    }

    // productId|server-issued account binding|basePlanId|Google offer token
    fun subscribe(requestId: String, productSpec: String) {
        val parts = productSpec.split('|')
        if (parts.size != 4 || parts[0] !in SUPPORTED_PRODUCTS || !BINDING.matches(parts[1]) ||
            !BASE_PLAN.matches(parts[2]) || parts[3].isBlank() || parts[3].length > 4096) {
            error(requestId, "invalid_subscription_selection"); return
        }
        if (pending != null || loadingPurchase) { error(requestId, "billing_flow_in_progress"); return }
        loadingPurchase = true
        val (productId, binding, basePlanId, offerToken) = parts
        connected(requestId) {
            queryProducts(requestId) { products ->
                val detail = products.firstOrNull { it.productId == productId }
                val offer = detail?.let(::baseOffers)?.firstOrNull { it.basePlanId == basePlanId && it.offerToken == offerToken }
                if (detail == null || offer == null) {
                    loadingPurchase = false; error(requestId, "subscription_offer_changed_refresh_prices"); return@queryProducts
                }
                // All plans confer the same membership. Do not sell overlapping plans.
                client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()) { result, purchases ->
                    if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                        loadingPurchase = false; error(requestId, errorCode(result)); return@queryPurchasesAsync
                    }
                    if (purchases.any { p -> p.products.any(SUPPORTED_PRODUCTS::contains) && p.purchaseState in setOf(Purchase.PurchaseState.PURCHASED, Purchase.PurchaseState.PENDING) }) {
                        loadingPurchase = false; error(requestId, "existing_subscription_restore_or_manage"); return@queryPurchasesAsync
                    }
                    activity.runOnUiThread {
                        if (closed || activity.isFinishing || activity.isDestroyed) { loadingPurchase = false; return@runOnUiThread }
                        pending = Pending(requestId, productId, binding)
                        val selected = BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(detail).setOfferToken(offer.offerToken).build()
                        val flow = BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(selected)).setObfuscatedAccountId(binding).build()
                        val launched = client.launchBillingFlow(activity, flow)
                        if (launched.responseCode != BillingClient.BillingResponseCode.OK) {
                            pending = null; loadingPurchase = false; error(requestId, errorCode(launched))
                        }
                    }
                }
            }
        }
    }

    fun close() {
        closed = true; pending = null; loadingPurchase = false
        waiting.clear(); handler.removeCallbacksAndMessages(null); client.endConnection()
    }

    private fun baseOffers(detail: ProductDetails): List<ProductDetails.SubscriptionOfferDetails> =
        detail.subscriptionOfferDetails.orEmpty().filter { offer ->
            val phases = offer.pricingPhases.pricingPhaseList
            offer.offerId == null && phases.size == 1 && phases[0].recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING &&
                phases[0].billingPeriod == PERIODS[detail.productId] && phases[0].priceAmountMicros > 0
        }

    private fun queryProducts(requestId: String, done: (List<ProductDetails>) -> Unit) {
        val products = SUPPORTED_PRODUCTS.map { QueryProductDetailsParams.Product.newBuilder().setProductId(it).setProductType(BillingClient.ProductType.SUBS).build() }
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder().setProductList(products).build()) { result, response ->
            if (closed) return@queryProductDetailsAsync
            if (result.responseCode != BillingClient.BillingResponseCode.OK) { loadingPurchase = false; error(requestId, errorCode(result)) }
            else done(response.productDetailsList)
        }
    }
    private fun connected(id: String, action: () -> Unit) {
        if (closed) return
        if (client.isReady) { action(); return }
        waiting.add(id to action)
        if (connecting) return
        connecting = true; handler.postDelayed(connectionTimeout, 15000)
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (closed) return
                handler.removeCallbacks(connectionTimeout)
                if (result.responseCode != BillingClient.BillingResponseCode.OK) { failWaiting(errorCode(result)); return }
                connecting = false
                val queue = waiting.toList(); waiting.clear(); queue.forEach { it.second() }
            }
            override fun onBillingServiceDisconnected() { failWaiting("play_billing_unavailable") }
        })
    }
    private fun failWaiting(code: String) {
        connecting = false; loadingPurchase = false; handler.removeCallbacks(connectionTimeout)
        val queue = waiting.toList(); waiting.clear(); queue.forEach { error(it.first, code) }
    }
    private fun purchaseJson(p: Purchase) = JSONObject().put("productId", p.products.firstOrNull { it in SUPPORTED_PRODUCTS })
        .put("purchaseToken", p.purchaseToken).put("state", when (p.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> "purchased"
            Purchase.PurchaseState.PENDING -> "pending"
            else -> "unknown"
        }).put("acknowledged", p.isAcknowledged).put("verified", false)
    private fun send(id: String, data: JSONObject) { if (!closed) resultDispatcher(JSONObject().put("ok", true).put("requestId", id).put("data", data).toString()) }
    private fun error(id: String, code: String) { if (!closed) resultDispatcher(JSONObject().put("ok", false).put("requestId", id).put("error", code).toString()) }
    private fun errorCode(r: BillingResult) = when (r.responseCode) {
        BillingClient.BillingResponseCode.USER_CANCELED -> "purchase_canceled"
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "existing_subscription_restore_or_manage"
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "subscription_product_unavailable"
        BillingClient.BillingResponseCode.NETWORK_ERROR -> "play_billing_network_error"
        else -> "play_billing_unavailable"
    }
    companion object {
        private val SUPPORTED_PRODUCTS = setOf("icarus_pro_monthly", "icarus_pro_quarterly", "icarus_pro_annual")
        private val PERIODS = mapOf("icarus_pro_monthly" to "P1M", "icarus_pro_quarterly" to "P3M", "icarus_pro_annual" to "P1Y")
        private val BINDING = Regex("^[0-9a-f]{64}$")
        private val BASE_PLAN = Regex("^[a-z0-9][a-z0-9-]{0,62}$")
    }
}
