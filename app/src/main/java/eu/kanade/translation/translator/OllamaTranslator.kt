package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import logcat.logcat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class OllamaTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val apiUrl: String,
    private val modelName: String
) : TextTranslator {

    private val okHttpClient = OkHttpClient()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        try {
            val mediaType = "application/json; charset=utf-8".toMediaType()

            for ((pageKey, page) in pages) {
                // إرسال كل كتلة نصية إلى Ollama للحصول على ترجمة دقيقة سياقياً
                page.blocks.forEachIndexed { index, block ->
                    val prompt = "Translate the following manga text from ${fromLang.name} to ${toLang.label}. Provide only the translated text, without any explanations or additional formatting:\n\"${block.text}\""
                    
                    val jsonObject = JSONObject()
                    jsonObject.put("model", modelName)
                    jsonObject.put("prompt", prompt)
                    jsonObject.put("stream", false) // نطلب الرد كدفعة واحدة وليس كتدفق

                    val body = jsonObject.toString().toRequestBody(mediaType)
                    val build: Request = Request.Builder()
                        .url(apiUrl)
                        .header("Content-Type", "application/json")
                        .post(body)
                        .build()

                    val response = okHttpClient.newCall(build).await()
                    if (response.isSuccessful) {
                        // تم إصلاح الخطأ هنا: استبدال continue بـ return@forEachIndexed
                        val rBody = response.body ?: return@forEachIndexed
                        val jsonResponse = JSONObject(rBody.string())
                        val translatedText = jsonResponse.optString("response", "").trim('"', '\n', ' ')
                        
                        if (translatedText.isNotEmpty()) {
                            block.translation = translatedText
                        }
                    } else {
                        logcat { "Ollama Error: HTTP ${response.code}" }
                    }
                }
            }
        } catch (e: Exception) {
            logcat { "Ollama Translator Error : ${e.stackTraceToString()}" }
            throw e
        }
    }

    override fun close() {}
}