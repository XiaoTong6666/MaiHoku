package io.github.xiaotong6666.maihoku.features.googleone.billing

import com.android.billingclient.api.Purchase
import java.security.MessageDigest

/** Compares Billing snapshots using in-memory token hashes. */
internal class GoogleOneSubscriptionSnapshot private constructor(
    private val items: Map<String, Entry>,
) {
    internal data class Entry(
        val products: Set<String>,
        val state: Int,
        val acknowledged: Boolean,
        val autoRenewing: Boolean,
    )

    data class Delta(val added: Int, val missing: Int, val changed: Int, val same: Int)

    fun compare(previous: GoogleOneSubscriptionSnapshot): Delta {
        val old = previous.items
        val added = items.keys.count { it !in old }
        val missing = old.keys.count { it !in items }
        val changed = items.keys.count { key -> key in old && items[key] != old[key] }
        val same = items.keys.count { key -> key in old && items[key] == old[key] }
        return Delta(added, missing, changed, same)
    }

    companion object {
        private const val GOOGLE_ONE_PACKAGE = "com.google.android.apps.subscriptions.red"

        fun from(purchases: List<Purchase>): GoogleOneSubscriptionSnapshot {
            val entries = linkedMapOf<String, Entry>()
            for (purchase in purchases) {
                if (purchase.packageName != GOOGLE_ONE_PACKAGE || purchase.purchaseToken.isBlank()) continue
                val hash = MessageDigest.getInstance("SHA-256")
                    .digest(purchase.purchaseToken.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                entries[hash] = Entry(
                    products = purchase.products.toSet(),
                    state = purchase.purchaseState,
                    acknowledged = purchase.isAcknowledged,
                    autoRenewing = purchase.isAutoRenewing,
                )
            }
            return GoogleOneSubscriptionSnapshot(entries)
        }
    }
}
