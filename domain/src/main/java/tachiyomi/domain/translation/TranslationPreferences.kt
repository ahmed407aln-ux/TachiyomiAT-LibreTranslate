package tachiyomi.domain.translation

import tachiyomi.core.common.preference.PreferenceStore

class TranslationPreferences(
    private val preferenceStore: PreferenceStore,
) {

    fun autoTranslateAfterDownload() = preferenceStore.getBoolean("auto_translate_after_download", false)
    fun translateFromLanguage() = preferenceStore.getString("translate_language_from", "ENGLISH")
    fun translateToLanguage() = preferenceStore.getString("translate_language_to", "ARABIC")
    fun translationFont() = preferenceStore.getInt("translation_font", 0)

    fun translationEngine() = preferenceStore.getInt("translation_engine", 0)
    fun translationEngineModel() = preferenceStore.getString("translation_engine_model", "gemini-1.5-pro")
    fun translationEngineApiKey() = preferenceStore.getString("translation_engine_api_key", "")
    fun translationEngineTemperature() = preferenceStore.getString("translation_engine_temperature", "0.1")
    fun translationEngineMaxOutputTokens() = preferenceStore.getString("translation_engine_output_tokens", "2048")

    // أضف هذه الدالة
    fun translationServerUrl() =
        preferenceStore.getString("translation_server_url", "http://10.0.0.10")

    fun externalGgufModelPath() = preferenceStore.getString(key = "external_gguf_model_path", defaultValue = "")

    fun textRecognizerType() = preferenceStore.getInt(key = "text_recognizer_type", defaultValue = 0)

}
