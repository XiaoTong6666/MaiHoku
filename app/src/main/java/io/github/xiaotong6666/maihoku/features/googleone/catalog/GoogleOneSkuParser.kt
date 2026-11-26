package io.github.xiaotong6666.maihoku.features.googleone.catalog

/** Parses user-supplied SKU lists. */
internal object GoogleOneSkuParser {
    enum class InputError {
        TOO_LONG,
        EMPTY,
        INVALID_ID,
        TOO_MANY,
    }

    class InvalidInput(val reason: InputError) : IllegalArgumentException(reason.name)

    const val BATCH_SIZE = 20
    const val MAX_SKUS = 120
    private const val MAX_INPUT_LENGTH = 16_384
    private val separator = Regex("[\\s,;，；]+")
    private val productIdPattern = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

    val defaultProducts = listOf(
        "g1.play.20tb",
        "g1.play.ai.pro",
        "g1.30tb",
        "g1.2tb.ai.subs_variant",
        "g1.2tb.ai.18months_eft",
        "g1.2tb.ai.15months_eft",
        "g1.2tb.ai.12months_eft",
        "g1.2tb.ai.6months_eft",
        "g1.2tb.ai.4months_eft",
        "g1.2tb.ai.3months_eft",
        "g1.2tb.ai.2months_eft",
        "g1.2tb.ai.1month_eft",
    )

    fun parse(input: String): List<String> {
        if (input.length > MAX_INPUT_LENGTH) throw InvalidInput(InputError.TOO_LONG)
        val tokens = input.trim().split(separator).filter(String::isNotBlank)
        if (tokens.isEmpty()) throw InvalidInput(InputError.EMPTY)
        if (tokens.any { !productIdPattern.matches(it) }) throw InvalidInput(InputError.INVALID_ID)
        val ids = tokens.distinct()
        if (ids.size > MAX_SKUS) throw InvalidInput(InputError.TOO_MANY)
        return ids
    }
}
