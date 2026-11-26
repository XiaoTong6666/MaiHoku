package io.github.xiaotong6666.maihoku.features.googleone.billing

import com.android.billingclient.api.Purchase

/** Selects candidate purchases; Play decides replacement eligibility. */
internal object GoogleOneReplacementPolicy {
    private const val GOOGLE_ONE_PACKAGE = "com.google.android.apps.subscriptions.red"

    fun isReplaceable(purchase: Purchase): Boolean = purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
        purchase.isAcknowledged &&
        purchase.packageName == GOOGLE_ONE_PACKAGE &&
        purchase.purchaseToken.isNotBlank() &&
        purchase.products.any { it.isNotBlank() }

    fun isSameActiveSubscription(previous: Purchase, current: Purchase): Boolean = isReplaceable(previous) &&
        isReplaceable(current) &&
        previous.purchaseToken == current.purchaseToken &&
        previous.products.toSet() == current.products.toSet() &&
        previous.isAutoRenewing == current.isAutoRenewing

    fun isCanceledMatchingProduct(purchase: Purchase, sku: String): Boolean = isReplaceable(purchase) && !purchase.isAutoRenewing && sku in purchase.products
}
