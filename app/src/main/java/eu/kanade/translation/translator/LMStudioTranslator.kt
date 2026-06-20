package eu.kanade.translation.translator

import eu.kanade.tachiyomi.network.await
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import logcat.logcat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class LMStudioTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val apiUrl: String, // هنا نضع رابط LM Studio: http://10.0.0.10:1234/v1/chat/completions
) : TextTranslator {

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        try {
            val mediaType = "application/json; charset=utf-8".toMediaType()

            for ((_, page) in pages) {
                page.blocks.forEachIndexed { _, block ->
                    // توجيه صارم للنموذج لتقديم ترجمة صياغية غير حرفية
                    val systemInstruction = "You are an expert manga translator. Translate the following text from ${fromLang.name} to ${toLang.label}. Provide only the natural, fluid translated text, without any explanations, thinking process, or additional formatting."

                    // بناء هيكل الطلب المتوافق مع معمارية OpenAI / v1
                    val root = JSONObject()
                    root.put("model", "qwen2.5-7b-instruct")
                    
                    val messagesArray = JSONArray()

                    // رسالة التوجيه (System)
                    val systemMsg = JSONObject()
                    systemMsg.put("role", "system")
                    systemMsg.put("content", systemInstruction)
                    messagesArray.put(systemMsg)

                    // رسالة النص المراد ترجمته (User)
                    val userMsg = JSONObject()
                    userMsg.put("role", "user")
                    userMsg.put("content", block.text)
                    messagesArray.put(userMsg)

                    root.put("messages", messagesArray)
                    root.put("stream", false)

                    val body = root.toString().toRequestBody(mediaType)
                    val build: Request = Request.Builder()
                        .url(apiUrl)
                        .header("Content-Type", "application/json")
                        .post(body)
                        .build()

                    val response = okHttpClient.newCall(build).await()
                    if (response.isSuccessful) {
                        val rBody = response.body ?: return@forEachIndexed
                        val jsonResponse = JSONObject(rBody.string())
                        
                        // استخراج النص المترجم من هيكل الاختيارات (choices)
                        val choices = jsonResponse.optJSONArray("choices")
                        if (choices != null && choices.length() > 0) {
                            val message = choices.getJSONObject(0).optJSONObject("message")
                            val translatedText = message?.optString("content", "")?.trim('"', '\n', ' ')
                            
                            if (!translatedText.isNullOrEmpty()) {
                                block.translation = translatedText
                            }
                        }
                    } else {
                        logcat { "LM Studio Error: HTTP ${response.code}" }
                    }
                }
            }
        } catch (e: Exception) {
            logcat { "LM Studio Translator Error : ${e.stackTraceToString()}" }
            throw e
        }
    }

    override fun close() {}
}