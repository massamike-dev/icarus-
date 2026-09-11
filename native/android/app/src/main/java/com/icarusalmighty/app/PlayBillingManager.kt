package com.icarusalmighty.app

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import org.json.JSONObject

/**
 * Thin Google Play Billing client. The Android app never grants Premium by
 * itself: it only returns Play purchase tokens to the web layer, which sends
 * them to the ICARUS backend for Google Developer API verification.
 *
 * New purchase requests use a compact internal product spec of
 * `productId|accountBinding`. The native shell strips the binding before
 * querying Play, then passes the 64-character pseudonymous account binding to
 * BillingFlowParams so the backend can verify that the purchase belongs to the
 * signed-in ICARUS account.
 */
class PlayBillingManager(
    private val activity: Activity,
    private val resultDispatcher: (String) -> Unit,
) {
    private var pendingSubscribeRequestId: String? = null
    private var pendingSubscribeProductId: String? = null

    private val billingClient = BillingClient.newBuilder(activity)
        .setListener { result, purchases ->
            val requestId = pendingSubscribeRequestId
            if (requestId == null) {
                clearPendingSubscribe()
                return@setListener
            }
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                dispatchError(requestId, billingError(result))
                clearPendingSubscribe()
                return@setListener
            }

            val purchase = purchases?.firstOrNull()
            if (purchase == null) {
                dispatchError(requestId, "purchase_missing")
                clearPendingSubscribe()
                return@setListener
            }

            when (purchase.purchaseState) {
                com.android.billingclient.api.Purchase.PurchaseState.PURCHASED -> {
                    acknowledgeIfNeeded(requestId, purchase) {
                        dispatchPurchase(requestId, purchase, "purchased")
                        clearPendingSubscribe()
                    }
                }
                com.android.billingclient.api.Purchase.PurchaseState.PENDING -> {
                    dispatchPurchase(requestId, purchase, "pending")
                    clearPendingSubscribe()
                }
                else -> {
                    dispatchError(requestId, "purchase_not_completed")
                    clearPendingSubscribe()
                }
            }
        }
        .enablePendingPurchases()
        .build()

    fun checkSubscription(requestId: String) {
        withConnected(requestId) {
            val params = QueryPurchasesParams.newBuilder()
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            billingClient.queryPurchasesAsync(params) { result, purchases ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    dispatchError(requestId, billingError(result))
                    return@queryPurchasesAsync
                }

                val purchase = purchases
                    .filter { it.purchaseState == com.android.billingclient.api.Purchase.PurchaseState.PURCHASED }
                    .maxByOrNull { it.purchaseTime }

                if (purchase == null) {
                    dispatch(
                        JSONObject()
                            .put("ok", true)
                            .put("requestId", requestId)
                            .put("data", JSONObject()
                                .put("active", false)
                                .put("purchaseToken", "")
                                .put("state", "none"))
                    )
                    return@queryPurchasesAsync
                }

                // Restore/query flows use their own request id. If acknowledgement
                // fails, always resolve that exact request instead of looking at
                // the unrelated new-purchase state.
                acknowledgeIfNeeded(requestId, purchase) {
                    dispatchPurchase(requestId, purchase, "purchased")
                }
            }
        }
    }

    fun subscribe(requestId: String, productSpec: String) {
        val parts = productSpec.split('|', limit = 2)
        val productId = parts.firstOrNull().orEmpty().trim()
        val accountBinding = parts.getOrNull(1).orEmpty().trim().lowercase()

        if (productId !in SUPPORTED_PRODUCTS) {
            dispatchError(requestId, "unknown_subscription_product")
            return
        }
        if (!ACCOUNT_BINDING.matches(accountBinding)) {
            dispatchError(requestId, "invalid_billing_account_binding")
            return
        }
        if (pendingSubscribeRequestId != null) {
            dispatchError(requestId, "billing_flow_in_progress")
            return
        }

        withConnected(requestId) {
            val product = QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
            val query = QueryProductDetailsParams.newBuilder()
                .setProductList(listOf(product))
                .build()

            billingClient.queryProductDetailsAsync(query) { result, details ->
                if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                    dispatchError(requestId, billingError(result))
                    return@queryProductDetailsAsync
                }

                val productDetails = details.firstOrNull()
                if (productDetails == null) {
                    dispatchError(requestId, "subscription_product_unavailable")
                    return@queryProductDetailsAsync
                }

                val offer = chooseOffer(productDetails)
                if (offer == null) {
                    dispatchError(requestId, "subscription_offer_unavailable")
                    return@queryProductDetailsAsync
                }

                val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(productDetails)
                    .setOfferToken(offer.offerToken)
                    .build()
                val flowParams = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(listOf(productParams))
                    .setObfuscatedAccountId(accountBinding)
                    .build()

                pendingSubscribeRequestId = requestId
                pendingSubscribeProductId = productId
                val launchResult = billingClient.launchBillingFlow(activity, flowParams)
                if (launchResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    clearPendingSubscribe()
                    dispatchError(requestId, billingError(launchResult))
                }
            }
        }
    }

    fun close() {
        clearPendingSubscribe()
        if (billingClient.isReady) billingClient.endConnection()
    }

    private fun chooseOffer(details: ProductDetails): ProductDetails.SubscriptionOfferDetails? {
        val offers = details.subscriptionOfferDetails.orEmpty()
        if (offers.isEmpty()) return null

        // Prefer a trial when Google Play says the user is eligible, otherwise
        // fall back to the first eligible paid offer. The UI does not promise a
        // trial until Play actually presents one.
        return offers.firstOrNull { offer ->
            offer.pricingPhases.pricingPhaseList.any { it.priceAmountMicros == 0L }
        } ?: offers.first()
    }

    private fun acknowledgeIfNeeded(
        requestId: String,
        purchase: com.android.billingclient.api.Purchase,
        after: () -> Unit,
    ) {
        if (purchase.isAcknowledged) {
            after()
            return
        }

        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        billingClient.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                after()
            } else {
                dispatchError(requestId, "purchase_acknowledgement_failed")
                if (pendingSubscribeRequestId == requestId) clearPendingSubscribe()
            }
        }
    }

    private fun withConnected(requestId: String, action: () -> Unit) {
        if (billingClient.isReady) {
            action()
            return
        }

        billingClient.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) action()
                else dispatchError(requestId, billingError(result))
            }

            override fun onBillingServiceDisconnected() {
                // A later user action will reconnect. Do not synthesize a
                // Premium state from a transient Play service disconnect.
            }
        })
    }

    private fun dispatchPurchase(
        requestId: String,
        purchase: com.android.billingclient.api.Purchase,
        state: String,
    ) {
        val products = purchase.products
        val productId = products.firstOrNull() ?: pendingSubscribeProductId.orEmpty()
        dispatch(
            JSONObject()
                .put("ok", true)
                .put("requestId", requestId)
                .put("data", JSONObject()
                    .put("active", purchase.purchaseState == com.android.billingclient.api.Purchase.PurchaseState.PURCHASED)
                    .put("purchaseToken", purchase.purchaseToken)
                    .put("productId", productId)
                    .put("state", state)
                    .put("acknowledged", purchase.isAcknowledged))
        )
    }

    private fun dispatchError(requestId: String, code: String) {
        dispatch(
            JSONObject()
                .put("ok", false)
                .put("requestId", requestId)
                .put("error", code)
        )
    }

    private fun dispatch(payload: JSONObject) = resultDispatcher(payload.toString())

    private fun clearPendingSubscribe() {
        pendingSubscribeRequestId = null
        pendingSubscribeProductId = null
    }

    private fun billingError(result: BillingResult): String = when (result.responseCode) {
        BillingClient.BillingResponseCode.USER_CANCELED -> "purchase_canceled"
        BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> "subscription_already_owned"
        BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> "subscription_product_unavailable"
        BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE -> "play_billing_unavailable"
        BillingClient.BillingResponseCode.NETWORK_ERROR -> "play_billing_network_error"
        else -> "play_billing_error_${result.responseCode}"
    }

    companion object {
        private val SUPPORTED_PRODUCTS = setOf(
            "icarus_pro_monthly",
            "icarus_pro_annual",
        )
        private val ACCOUNT_BINDING = Regex("^[0-9a-f]{64}$")
    }
}
