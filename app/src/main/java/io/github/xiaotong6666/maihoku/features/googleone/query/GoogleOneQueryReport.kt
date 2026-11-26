package io.github.xiaotong6666.maihoku.features.googleone.query

import org.json.JSONArray
import org.json.JSONObject

/** Per-SKU query outcomes; excludes purchase and offer tokens. */
internal class GoogleOneQueryReport(
    private val requestedSkus: List<String>,
    private val startedAtMs: Long = System.currentTimeMillis(),
) {
    enum class Outcome(val jsonValue: String) {
        NOT_QUERIED("not_queried"),
        QUERY_FAILED("query_failed"),
        NOT_RETURNED("not_returned"),
        NO_OFFERS("no_offers"),
        OFFERS("offers"),
    }

    data class Summary(
        val requested: Int,
        val queried: Int,
        val returned: Int,
        val offers: Int,
        val notReturned: Int,
        val notQueried: Int,
        val failed: Int,
        val batches: Int,
        val unexpected: Int,
    )

    data class Phase(
        val price: String,
        val micros: Long,
        val period: String,
        val cycles: Int,
    )

    data class Offer(
        val sku: String,
        val basePlanId: String,
        val offerId: String?,
        val phases: List<Phase>,
    )

    private val outcomes = requestedSkus.associateWith { Outcome.NOT_QUERIED }.toMutableMap()
    private val offersBySku = linkedMapOf<String, List<Offer>>()
    private val batchCodes = mutableListOf<Int>()
    private val billingEvents = mutableListOf<Pair<String, Int>>()
    private var unexpectedProducts = 0
    private var processedProducts = 0
    private var returnedProducts = 0
    private var unmatchedProducts = 0
    private var failedProducts = 0
    private var foundOffers = 0

    @Volatile var status: String = "running"
        private set
    var finishedAtMs: Long? = null
        private set
    var failedBatch: Int? = null
        private set

    @Synchronized fun recordBatch(
        requested: List<String>,
        responseCode: Int,
        returnedSkus: List<String>,
        returnedOffers: List<Offer>,
    ) {
        if (status != "running") return
        val batch = requested.toSet()
        require(batch.isNotEmpty() && batch.all { it in outcomes }) { "Unknown query batch" }
        require(requested.all { outcomes[it] == Outcome.NOT_QUERIED }) { "Batch already recorded" }
        batchCodes += responseCode
        processedProducts += requested.size
        if (responseCode != 0) {
            requested.forEach { outcomes[it] = Outcome.QUERY_FAILED }
            failedProducts += requested.size
            return
        }
        val returned = returnedSkus.toSet().intersect(batch)
        unexpectedProducts += returnedSkus.count { it !in batch }
        returnedProducts += returned.size
        unmatchedProducts += requested.size - returned.size
        for (sku in requested) {
            val offers = returnedOffers.filter { it.sku == sku }
            outcomes[sku] = when {
                sku !in returned -> Outcome.NOT_RETURNED
                offers.isEmpty() -> Outcome.NO_OFFERS
                else -> Outcome.OFFERS
            }
            if (offers.isNotEmpty()) offersBySku[sku] = offers
            foundOffers += offers.size
        }
    }

    @Synchronized fun recordBillingEvent(stage: String, responseCode: Int) {
        if (billingEvents.size < 40) billingEvents += stage to responseCode
    }

    @Synchronized fun complete() = finish("complete")

    @Synchronized fun fail(batch: Int?) {
        if (status != "running") return
        failedBatch = batch
        finish("failed")
    }

    @Synchronized fun cancel() = finish("canceled")

    private fun finish(value: String) {
        if (status != "running") return
        status = value
        finishedAtMs = System.currentTimeMillis()
    }

    @Synchronized fun summaryCounts(): Summary = Summary(
        requested = requestedSkus.size,
        queried = processedProducts,
        returned = returnedProducts,
        offers = foundOffers,
        notReturned = unmatchedProducts,
        notQueried = requestedSkus.size - processedProducts,
        failed = failedProducts,
        batches = batchCodes.size,
        unexpected = unexpectedProducts,
    )

    @Synchronized fun toJson(): String {
        val products = JSONArray()
        val compact = requestedSkus.size > 2_000
        var omitted = 0
        for (sku in requestedSkus) {
            val outcome = requireNotNull(outcomes[sku])
            if (compact && (outcome == Outcome.NOT_RETURNED || outcome == Outcome.NOT_QUERIED)) {
                omitted++
                continue
            }
            val item = JSONObject()
                .put("sku", sku)
                .put("result", outcome.jsonValue)
            val itemOffers = JSONArray()
            for (offer in offersBySku[sku].orEmpty()) {
                val phases = JSONArray()
                for (phase in offer.phases) {
                    phases.put(
                        JSONObject()
                            .put("formatted_price", phase.price)
                            .put("price_micros", phase.micros)
                            .put("billing_period", phase.period)
                            .put("billing_cycles", phase.cycles),
                    )
                }
                itemOffers.put(
                    JSONObject()
                        .put("base_plan_id", offer.basePlanId)
                        .put("offer_id", offer.offerId ?: JSONObject.NULL)
                        .put("pricing_phases", phases),
                )
            }
            products.put(item.put("offers", itemOffers))
        }
        return JSONObject()
            .put("schema", "maihoku-googleone-query-v2")
            .put("started_at_ms", startedAtMs)
            .put("finished_at_ms", finishedAtMs ?: JSONObject.NULL)
            .put("status", status)
            .put("failed_batch", failedBatch ?: JSONObject.NULL)
            .put("batch_response_codes", JSONArray(batchCodes))
            .put("compact_results", compact)
            .put("omitted_products", omitted)
            .put("unexpected_response_products", unexpectedProducts)
            .put(
                "billing_events",
                JSONArray().apply {
                    for ((stage, code) in billingEvents) {
                        put(JSONObject().put("stage", stage).put("response_code", code))
                    }
                },
            )
            .put("products", products)
            .toString(2)
    }
}
