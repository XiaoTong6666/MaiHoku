package io.github.xiaotong6666.maihoku.features.googleone

/**
 * Display-only representation of a subscription phase. The displayed renewal
 * price is never inferred from the introductory phase alone.
 */
internal data class OfferPhase(
    val amountMicros: Long,
    val formattedPrice: String,
    val billingPeriod: String,
    val billingCycles: Int,
)

internal object OfferPricing {
    fun hasFreeFirstPhase(phases: List<OfferPhase>): Boolean = phases.firstOrNull()?.amountMicros == 0L

    fun freeMonths(phases: List<OfferPhase>): Int {
        if (!hasFreeFirstPhase(phases)) return 0
        val phase = phases.first()
        val count = phase.billingCycles.coerceAtLeast(1)
        return when (phase.billingPeriod) {
            "P1Y" -> 12 * count
            "P12M" -> 12 * count
            "P6M" -> 6 * count
            "P3M" -> 3 * count
            "P1M" -> count
            else -> 0
        }
    }
}
