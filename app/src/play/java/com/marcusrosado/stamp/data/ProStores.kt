package com.marcusrosado.stamp.data

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The Play build: Pro is a one-time purchase of the product [PRODUCT], set up in Play Console. */
fun proStore(context: Context, flags: Flags): ProStore = PlayPro(context.applicationContext, flags)

private const val PRODUCT = "stamp_pro"

private class PlayPro(context: Context, private val flags: Flags) : ProStore, PurchasesUpdatedListener {
    override val sold = true

    private val _unlocked = MutableStateFlow(flags.get(ProStore.FLAG))
    override val unlocked: StateFlow<Boolean> = _unlocked.asStateFlow()

    private val _price = MutableStateFlow<String?>(null)
    override val price: StateFlow<String?> = _price.asStateFlow()

    @Volatile private var details: ProductDetails? = null

    // When a purchase last came in, so an older list of purchases arriving after it never locks
    // Pro again. Both callbacks run on the main thread.
    private var boughtAt = 0L

    private val client = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .build()

    init {
        connect()
    }

    private fun connect() {
        if (client.isReady) { load(); return }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) load()
            }

            // Asked again on the next refresh, resume or buy; the cached flag holds meanwhile.
            override fun onBillingServiceDisconnected() = Unit
        })
    }

    private fun load() {
        val asked = System.nanoTime()
        client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        ) { result, purchases ->
            // Play's own list is the truth, so a refund takes Pro away again; unless a purchase
            // came in after the list was asked for.
            if (result.responseCode == BillingClient.BillingResponseCode.OK) take(purchases, complete = asked > boughtAt)
        }
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT).setProductType(BillingClient.ProductType.INAPP).build()
        client.queryProductDetailsAsync(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build(),
        ) { result, found ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) return@queryProductDetailsAsync
            details = found.productDetailsList.firstOrNull()
            _price.value = details?.oneTimePurchaseOfferDetails?.formattedPrice
        }
    }

    /** Unlocks for a paid Pro and confirms it to Play; with [complete], no Pro in the list locks. */
    private fun take(purchases: List<Purchase>, complete: Boolean) {
        val paid = purchases.filter { PRODUCT in it.products && it.purchaseState == Purchase.PurchaseState.PURCHASED }
        // Play refunds a purchase that is not confirmed within three days.
        for (p in paid.filter { !it.isAcknowledged }) {
            client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(p.purchaseToken).build()) { }
        }
        val on = paid.isNotEmpty() || (!complete && _unlocked.value)
        _unlocked.value = on
        flags.set(ProStore.FLAG, on)
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: MutableList<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> { boughtAt = System.nanoTime(); take(purchases.orEmpty(), complete = false) }
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> load()
            else -> Unit
        }
    }

    override fun buy(activity: Activity): Boolean {
        val d = details
        if (d == null || !client.isReady) { connect(); return false }
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(d).build()))
            .build()
        return client.launchBillingFlow(activity, params).responseCode == BillingClient.BillingResponseCode.OK
    }

    override fun refresh() = connect()
}
