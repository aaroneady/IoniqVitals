package com.ioniqvitals.billing

import android.app.Activity
import android.util.Log
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.ioniqvitals.R

/**
 * Wraps Google Play Billing for one-time "tip the developer" purchases.
 *
 * The tip tiers are **consumable** in-app products: each purchase is consumed as soon as it
 * completes, so the same user can tip again and again. The IDs in [TIP_PRODUCT_IDS] must match the
 * managed in-app products created **and activated** in Play Console.
 *
 * Billing only returns products / opens the purchase sheet when the app is installed from a Play
 * track (internal testing is enough) with those products active and the account added as a license
 * tester. On a plain sideloaded build [queryProducts] comes back empty and [hasProducts] stays
 * false — the UI treats that as "tips unavailable".
 */
class BillingManager(
    private val activity: Activity,
    private val onMessage: (String) -> Unit,
    private val onProductsReady: () -> Unit,
) : PurchasesUpdatedListener, BillingClientStateListener {

    /** Loaded products, keyed by product ID, preserving [TIP_PRODUCT_IDS] order. */
    private val productDetails = LinkedHashMap<String, ProductDetails>()

    private val billingClient: BillingClient = BillingClient.newBuilder(activity)
        .setListener(this)
        .enablePendingPurchases(
            PendingPurchasesParams.newBuilder().enableOneTimeProducts().build(),
        )
        .build()

    /** True once at least one tip product has loaded from Play. */
    val hasProducts: Boolean get() = productDetails.isNotEmpty()

    /** Loaded tip products in [TIP_PRODUCT_IDS] order, for building the chooser. */
    fun availableProducts(): List<ProductDetails> = TIP_PRODUCT_IDS.mapNotNull { productDetails[it] }

    fun start() {
        if (billingClient.connectionState == BillingClient.ConnectionState.CONNECTED) {
            queryProducts()
        } else {
            billingClient.startConnection(this)
        }
    }

    fun release() {
        billingClient.endConnection()
    }

    override fun onBillingSetupFinished(result: BillingResult) {
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            queryProducts()
        } else {
            Log.w(TAG, "Billing setup failed: ${result.debugMessage}")
        }
    }

    override fun onBillingServiceDisconnected() {
        // Leave reconnection to the next start()/launchTip(); no aggressive retry loop.
    }

    private fun queryProducts() {
        val products = TIP_PRODUCT_IDS.map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(BillingClient.ProductType.INAPP)
                .build()
        }
        val params = QueryProductDetailsParams.newBuilder().setProductList(products).build()
        billingClient.queryProductDetailsAsync(params) { result, list ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                productDetails.clear()
                list.forEach { productDetails[it.productId] = it }
                activity.runOnUiThread { onProductsReady() }
            } else {
                Log.w(TAG, "queryProductDetails failed: ${result.debugMessage}")
            }
        }
    }

    /** Launches the Play purchase sheet for [productId]. Returns false if it isn't loaded yet. */
    fun launchTip(productId: String): Boolean {
        val details = productDetails[productId] ?: return false
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(details)
                        .build(),
                ),
            )
            .build()
        val result = billingClient.launchBillingFlow(activity, params)
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> purchases?.forEach { handlePurchase(it) }
            BillingClient.BillingResponseCode.USER_CANCELED -> Unit // user backed out; stay silent
            else -> Log.w(TAG, "Purchase failed: ${result.debugMessage}")
        }
    }

    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) return
        // Consuming both grants a fresh entitlement (so the tier can be bought again) and
        // acknowledges the purchase; an unacknowledged purchase is auto-refunded after 3 days.
        val params = ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        billingClient.consumeAsync(params) { consumeResult, _ ->
            if (consumeResult.responseCode == BillingClient.BillingResponseCode.OK) {
                activity.runOnUiThread { onMessage(activity.getString(R.string.tip_thanks)) }
            }
        }
    }

    companion object {
        private const val TAG = "BillingManager"

        // Must match the managed in-app product IDs in Play Console, in display order.
        val TIP_PRODUCT_IDS = listOf("tip_small", "tip_medium", "tip_large")
    }
}
