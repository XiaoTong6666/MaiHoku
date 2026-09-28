package io.github.xiaotong6666.maihoku.translation

import app.nekogram.translator.GoogleAppTranslator

internal object GoogleTranslationBackend : TranslationBackend {
    private val translator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        GoogleAppTranslator.getInstance()
    }

    override fun translate(
        text: String,
        targetLanguage: String,
    ): TranslationResult {
        if (text.isBlank()) {
            return TranslationResult(text = text, sourceLanguage = null)
        }

        val result = translator.translate(text, null, targetLanguage)
        return TranslationResult(
            text = result.translation,
            sourceLanguage = result.sourceLanguage,
        )
    }
}
