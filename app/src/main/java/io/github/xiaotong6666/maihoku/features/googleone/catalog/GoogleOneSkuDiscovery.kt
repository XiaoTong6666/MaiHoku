package io.github.xiaotong6666.maihoku.features.googleone.catalog

/** Generates candidates from Google One's observed and historical SKU formats. */
internal object GoogleOneSkuDiscovery {
    enum class Family { OBSERVED, FREE_TRIAL, INTRO_PRICE, MIXED }

    val paidPresets = listOf(
        "g1.2tb.ai.12months_ip_90p",
        "g1.2tb.ai.3months_ip_90p.acq",
        "g1.2tb.ai.1month_ip_95p.acq",
    )

    private val tiers = listOf(
        "2tb.ai", "play.2tb.ai", "2tb.ai.subs_variant", "5tb.ai", "ai.pro",
        "20tb", "20tb.ai", "20tb.ai.subs_variant", "20tb.v2",
        "30tb", "30tb.ai", "30tb.ai.subs_variant", "30tb.v2",
        "ai.ultra", "ai.ultra.subs_variant", "ultra", "ultra.subs_variant",
        "play.20tb", "play.30tb", "play.ai.ultra", "play.ai.pro",
        "30gb", "100gb", "200gb", "helium", "play.helium", "2tb", "5tb", "10tb",
        "30gb.annual", "100gb.annual", "200gb.annual", "2tb.annual", "helium.annual",
    )
    private val periods = listOf(
        "1month", "2months", "3months", "4months", "6months", "9months",
        "12months", "15months", "18months", "24months",
        "1mo", "3mo", "6mo", "12mo", "1year",
        "30days", "90days", "180days", "365days", "540days", "730days",
    )
    private val discountPeriods = listOf(
        "1month",
        "2months",
        "3months",
        "6months",
        "9months",
        "12months",
        "18months",
        "24months",
    )
    private val discounts = listOf(40, 50, 55, 60, 65, 70, 75, 80, 83, 85, 88, 90, 92, 95, 97, 98, 99)
    private val paidSuffixes = listOf("", ".acq", ".wb", ".sep")

    private val fixedPriceTiers = listOf("100gb", "200gb", "2tb", "30gb")
    private val fixedPrices = listOf("0.49", "0.99", "1.49", "2.49", "4.99")

    private fun freeCandidates() = sequence {
        for (tier in tiers) {
            for (period in periods) {
                val root = "g1.$tier."
                yield(root + period + "_eft")
                yield(root + period + "_eft.acq")
                yield(root + period + "_trial")
                yield(root + period + "_free")
                yield(root + period + "_intro")
                yield(root + period + "_promo")
                yield(root + period)
                yield(root + period + ".acq")
                yield(root + period + ".wb")
                yield(root + "eft_" + period)
                yield(root + "trial_" + period)
            }
        }
    }

    private fun paidCandidates() = sequence {
        for (tier in tiers) {
            for (period in discountPeriods) {
                for (discount in discounts) {
                    val root = "g1.$tier.${period}_ip_${discount}p"
                    for (suffix in paidSuffixes) yield(root + suffix)
                }
            }
        }
        for (tier in fixedPriceTiers) {
            for (period in listOf("1month", "3months", "6months", "12months")) {
                for (price in fixedPrices) yield("g1.$tier.${period}_ip.$price")
            }
        }
    }

    fun candidates(family: Family): List<String> {
        val known = GoogleOneSkuParser.defaultProducts.asSequence()
        val presets = paidPresets.asSequence()
        val observed = GoogleOneObservedSkus.productIds.asSequence()
        return when (family) {
            Family.OBSERVED -> known + observed + presets
            Family.FREE_TRIAL -> known + observed.filter { "_ip" !in it } + freeCandidates()
            Family.INTRO_PRICE -> known + observed.filter { "_ip" in it || "_eft" !in it } + presets + paidCandidates()
            Family.MIXED -> known + observed + presets + freeCandidates() + paidCandidates()
        }.distinct().toList()
    }
}
