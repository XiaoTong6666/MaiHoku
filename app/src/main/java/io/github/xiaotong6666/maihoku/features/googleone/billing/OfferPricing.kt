package io.github.xiaotong6666.maihoku.features.googleone.billing

import kotlin.math.roundToInt

/** Billing phase values used for display. */
internal data class OfferPhase(
    val amountMicros: Long,
    val formattedPrice: String,
    val billingPeriod: String,
    val billingCycles: Int,
)

internal object OfferPricing {
    private val yearMonthPeriod = Regex("^P(?:(\\d+)Y)?(?:(\\d+)M)?$")
    private val weekDayPeriod = Regex("^P(?:(\\d+)W)?(?:(\\d+)D)?$")

    fun hasFreeFirstPhase(phases: List<OfferPhase>): Boolean = phases.firstOrNull()?.amountMicros == 0L

    fun freeMonths(phases: List<OfferPhase>): Int {
        if (!hasFreeFirstPhase(phases)) return 0
        val phase = phases.first()
        if (phase.billingCycles <= 0) return 0
        val match = yearMonthPeriod.matchEntire(phase.billingPeriod) ?: return 0
        val years = match.groupValues[1].ifEmpty { "0" }.toIntOrNull() ?: return 0
        val months = match.groupValues[2].ifEmpty { "0" }.toIntOrNull() ?: return 0
        val monthsPerCycle = years.toLong() * 12 + months
        if (monthsPerCycle <= 0) return 0
        return if (monthsPerCycle > Int.MAX_VALUE / phase.billingCycles) {
            Int.MAX_VALUE
        } else {
            (monthsPerCycle * phase.billingCycles).toInt()
        }
    }

    /** Exact day count for trial phases expressed in weeks or days. */
    fun freeDays(phases: List<OfferPhase>): Int {
        if (!hasFreeFirstPhase(phases)) return 0
        val phase = phases.first()
        if (phase.billingCycles <= 0) return 0
        val match = weekDayPeriod.matchEntire(phase.billingPeriod) ?: return 0
        val weeks = match.groupValues[1].ifEmpty { "0" }.toLongOrNull() ?: return 0
        val days = match.groupValues[2].ifEmpty { "0" }.toLongOrNull() ?: return 0
        if (weeks == 0L && days == 0L) return 0
        if (weeks > Int.MAX_VALUE / 7L || days > Int.MAX_VALUE) return Int.MAX_VALUE
        val daysPerCycle = weeks * 7L + days
        return if (daysPerCycle > Int.MAX_VALUE.toLong() / phase.billingCycles) {
            Int.MAX_VALUE
        } else {
            (daysPerCycle * phase.billingCycles).toInt()
        }
    }

    /** Sorting only: compares calendar-month trials with exact day-based trials. */
    fun freeDurationDays(phases: List<OfferPhase>): Int {
        val months = freeMonths(phases)
        return if (months > 0) (months * (365.2425 / 12)).roundToInt() else freeDays(phases)
    }
}
