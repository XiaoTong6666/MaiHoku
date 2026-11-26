package io.github.xiaotong6666.maihoku.features.googleone.query

import android.content.Context
import org.json.JSONObject

/** Stores historical SKU matches, not checkout tokens or eligibility. */
internal class GoogleOneFoundSkuCache(context: Context) {
    data class Entry(
        val sku: String,
        val offerCount: Int,
        val seenAtMs: Long,
        val freeOfferCount: Int? = null,
        val paidOfferCount: Int? = null,
    ) {
        companion object {
            fun fromOffers(sku: String, offers: List<GoogleOneQueryReport.Offer>, seenAtMs: Long): Entry = Entry(
                sku = sku,
                offerCount = offers.size,
                seenAtMs = seenAtMs,
                freeOfferCount = offers.count { it.phases.firstOrNull()?.micros == 0L },
                paidOfferCount = offers.count { (it.phases.firstOrNull()?.micros ?: 0L) > 0L },
            )
        }

        fun matches(filter: Filter): Boolean = when (filter) {
            Filter.ALL -> true
            Filter.FREE -> (freeOfferCount ?: 0) > 0
            Filter.PAID -> (paidOfferCount ?: 0) > 0
            Filter.UNCLASSIFIED -> freeOfferCount == null || paidOfferCount == null
        }
    }

    enum class Filter { ALL, FREE, PAID, UNCLASSIFIED }

    private val preferences = context.applicationContext.getSharedPreferences("maihoku_googleone_found", Context.MODE_PRIVATE)

    @Synchronized fun entries(filter: Filter = Filter.ALL, keyword: String = ""): List<Entry> = preferences.all.mapNotNull { (key, value) ->
        if (!key.startsWith(KEY_PREFIX) || value !is String) return@mapNotNull null
        GoogleOneFoundSkuCacheCodec.decode(key.removePrefix(KEY_PREFIX), value)
    }.filter { it.matches(filter) && it.sku.contains(keyword, ignoreCase = true) }
        .sortedByDescending(Entry::seenAtMs)

    @Synchronized fun record(returned: List<String>, offers: List<GoogleOneQueryReport.Offer>) {
        if (returned.isEmpty()) return
        val now = System.currentTimeMillis()
        val bySku = offers.groupBy { it.sku }
        val editor = preferences.edit()
        val updated = returned.distinct().map { sku ->
            Entry.fromOffers(sku, bySku[sku].orEmpty(), now)
        }
        for (entry in updated) {
            editor.putString(
                KEY_PREFIX + entry.sku,
                GoogleOneFoundSkuCacheCodec.encode(entry),
            )
        }
        val known = entries()
        val all = (updated + known)
            .distinctBy(Entry::sku)
            .sortedByDescending(Entry::seenAtMs)
        for (entry in all.drop(MAX_ENTRIES)) editor.remove(KEY_PREFIX + entry.sku)
        editor.apply()
    }

    @Synchronized fun clear() {
        preferences.edit().clear().apply()
    }

    private companion object {
        const val KEY_PREFIX = "sku:"
        const val MAX_ENTRIES = 1_024
    }
}

internal object GoogleOneFoundSkuCacheCodec {
    fun encode(entry: GoogleOneFoundSkuCache.Entry): String = JSONObject()
        .put("offers", entry.offerCount)
        .put("seen", entry.seenAtMs)
        .put("free_offers", entry.freeOfferCount ?: JSONObject.NULL)
        .put("paid_offers", entry.paidOfferCount ?: JSONObject.NULL)
        .toString()

    fun encode(offers: Int, seenAtMs: Long): String = JSONObject().put("offers", offers).put("seen", seenAtMs).toString()

    fun decode(sku: String, value: String): GoogleOneFoundSkuCache.Entry? = runCatching {
        val data = JSONObject(value)
        GoogleOneFoundSkuCache.Entry(
            sku,
            data.getInt("offers"),
            data.getLong("seen"),
            if (data.has("free_offers") && !data.isNull("free_offers")) data.getInt("free_offers") else null,
            if (data.has("paid_offers") && !data.isNull("paid_offers")) data.getInt("paid_offers") else null,
        )
    }.getOrNull()
}
