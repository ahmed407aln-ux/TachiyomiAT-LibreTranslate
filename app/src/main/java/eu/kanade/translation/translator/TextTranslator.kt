package eu.kanade.translation.translator

import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.translation.TranslationPreferences
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.Closeable
import android.app.Application // تمت إضافة هذا الاستدعاء لنجاح جلب الـ Context

interface TextTranslator : Closeable {
    val fromLang: TextRecognizerLanguage
    val toLang: TextTranslatorLanguage
    suspend fun translate(pages: MutableMap<String, PageTranslation>)
}

enum class TextTranslators(val label: String) {
    MLKIT("MlKit (On Device)"),
    GOOGLE("Google Translate"),
    GEMINI("Gemini AI [API KEY]"),
    OPENROUTER("OpenRouter [API KEY]"),
    LIBRETRANSLATE("LibreTranslate [Local]"),

    EXTERNAL_GGUF("نموذج GGUF خارجي"),
    LMSTUDIO("LM Studio [Local]");

    fun build(pref : TranslationPreferences = Injekt.get(), fromLang: TextRecognizerLanguage = TextRecognizerLanguage.fromPref(pref.translateFromLanguage()), toLang: TextTranslatorLanguage = TextTranslatorLanguage.fromPref(pref.translateToLanguage())): TextTranslator{
        val maxOutputTokens = pref.translationEngineMaxOutputTokens().get().toIntOrNull() ?: 8914
        val temperature = pref.translationEngineTemperature().get().toFloatOrNull() ?: 1.0f
        val modelName = pref.translationEngineModel().get()
        val apiKey = pref.translationEngineApiKey().get()
        val serverUrl = pref.translationServerUrl().get().trimEnd('/')



        // استدعاء السياق والمسار الخاص بالنموذج الخارجي
        val context = Injekt.get<Application>()
        val ggufPath = pref.externalGgufModelPath().get()

        return when(this){
            MLKIT -> MLKitTranslator(fromLang, toLang)
            GOOGLE -> GoogleTranslator(fromLang, toLang)
            GEMINI -> GeminiTranslator(fromLang, toLang, apiKey, modelName, maxOutputTokens, temperature)
            OPENROUTER -> OpenRouterTranslator(fromLang, toLang, apiKey, modelName, maxOutputTokens, temperature)

            LIBRETRANSLATE ->
                LibreTranslateTranslator(
                    fromLang,
                    toLang,
                    "$serverUrl:5000/translate"
                )

            LMSTUDIO ->
                LMStudioTranslator(
                    fromLang,
                    toLang,
                    "$serverUrl:1234/v1/chat/completions"
                )

            // إضافة التوجيه الجديد هنا
            EXTERNAL_GGUF -> ExternalGgufTranslator(context, fromLang, toLang, ggufPath)
        }
    }

    companion object {
        fun fromPref(pref: Preference<Int>): TextTranslators {
            var translator = entries.getOrNull(pref.get())
            if (translator == null) {
                pref.set(0)
                return MLKIT
            }
            return translator
        }
    }
}
