package io.github.xiaotong6666.maihoku.translation

internal data class TranslationResult(
    val text: String,
    val sourceLanguage: String?,
)

internal fun interface TranslationBackend {
    fun translate(
        text: String,
        targetLanguage: String,
    ): TranslationResult
}
