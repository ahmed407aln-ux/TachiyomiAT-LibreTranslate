package eu.kanade.translation.translator

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.BlockThreshold
import com.google.ai.client.generativeai.type.HarmCategory
import com.google.ai.client.generativeai.type.SafetySetting
import com.google.ai.client.generativeai.type.content
import com.google.ai.client.generativeai.type.generationConfig
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import logcat.logcat
import org.json.JSONObject

@Suppress
class GeminiTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    apiKey: String,
    modelName: String,
    val maxOutputToken: Int,
    val temp: Float,
) : TextTranslator {

    private var model: GenerativeModel = GenerativeModel(
        modelName = modelName,
        apiKey = apiKey,
        generationConfig = generationConfig {
            topK = 30
            topP = 0.5f
            temperature = temp
            maxOutputTokens = maxOutputToken
            responseMimeType = "application/json"
        },
        safetySettings = listOf(
            SafetySetting(HarmCategory.HARASSMENT, BlockThreshold.NONE),
            SafetySetting(HarmCategory.HATE_SPEECH, BlockThreshold.NONE),
            SafetySetting(HarmCategory.SEXUALLY_EXPLICIT, BlockThreshold.NONE),
            SafetySetting(HarmCategory.DANGEROUS_CONTENT, BlockThreshold.NONE),
        ),
        systemInstruction = content {
            text(
                "You are a highly skilled AI tasked with translating text from scanned images of comics (manhwa, manga, manhua). " +
                "Translate the following JSON object values from ${fromLang.name} to ${toLang.label}.\n" +
                "CRITICAL RULES:\n" +
                "1. Return ONLY valid JSON matching the input structure exactly.\n" +
                "2. Remove watermarks (e.g. site links) by replacing them with 'RTMTH'.\n" +
                "3. DO NOT wrap the output in ```json ... ``` markdown blocks. Output the raw JSON object directly."
            )
        }
    )

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        try {
            // 1. تجميع النصوص في كائن JSON
            val data = pages.mapValues { (_, v) -> v.blocks.map { b -> b.text } }
            val json = JSONObject(data)
            
            // 2. إرسال الطلب إلى خوادم Gemini
            val response = model.generateContent(json.toString())
            val rawText = response.text ?: "{}"
            
            // 3. تنظيف الاستجابة جبرياً من أي علامات Markdown قد تسبب الانهيار (Crash)
            val cleanText = rawText.replace("```json", "").replace("```", "").trim()
            val resJson = JSONObject(cleanText)
            
            // 4. تعيين الترجمات إلى الكتل النصية
            for ((k, v) in pages) {
                v.blocks.forEachIndexed { i, b ->
                    val res = resJson.optJSONArray(k)?.optString(i, "NULL")
                    b.translation = if (res == null || res == "NULL") b.text else res
                }
                // 5. تصفية وإزالة الكتل التي تحتوي على علامات مائية
                v.blocks = v.blocks.filterNot { it.translation.contains("RTMTH") }.toMutableList()
            }
        } catch (e: Exception) {
            logcat { "Gemini Translation Error : ${e.stackTraceToString()}" }
            throw e
        }
    }

    override fun close() {}
}