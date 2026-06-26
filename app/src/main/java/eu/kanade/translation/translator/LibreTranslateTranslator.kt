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

class LibreTranslateTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val apiUrl: String
) : TextTranslator {
    
    private val okHttpClient = OkHttpClient()

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        try {
            // 1. Data Flattening: استخراج النصوص ومؤشرات مسارها (Indices)
            val queries = JSONArray()
            val keys = mutableListOf<Pair<String, Int>>()

            for ((pageKey, page) in pages) {
                page.blocks.forEachIndexed { index, block ->
                    queries.put(block.text)
                    keys.add(Pair(pageKey, index))
                }
            }

            if (queries.length() == 0) return

            // 2. Payload Construction: بناء طلب LibreTranslate API
            val jsonObject = JSONObject()
            jsonObject.put("q", queries)
            jsonObject.put("source", "auto")
            // تم التعيين الثابت لرمز "ar" لأن LibreTranslate يتطلب معيار ISO-639-1
            jsonObject.put("target", "ar") 
            jsonObject.put("format", "text")

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val body = jsonObject.toString().toRequestBody(mediaType)
            val build: Request = Request.Builder()
                .url(apiUrl)
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            // 3. Execution & Parsing: تنفيذ الطلب وتحليل الرد
            val response = okHttpClient.newCall(build).await()
            if (!response.isSuccessful) {
                logcat { "LibreTranslate Network Error: HTTP ${response.code}" }
                return
            }

            val rBody = response.body ?: return
            val jsonResponse = JSONObject(rBody.string())
            val translatedArray = jsonResponse.optJSONArray("translatedText")

            // 4. Data Re-mapping: إعادة دمج النصوص المترجمة مع كتل التطبيق الأصلية
            if (translatedArray != null && translatedArray.length() == keys.size) {
                for (i in 0 until keys.size) {
                    val (pageKey, blockIndex) = keys[i]
                    val transText = translatedArray.getString(i)
                    pages[pageKey]?.blocks?.get(blockIndex)?.translation = transText
                }
            } else {
                logcat { "LibreTranslate Parse Error: Array size mismatch or missing 'translatedText'" }
            }

        } catch (e: Exception) {
            logcat { "LibreTranslate Error : ${e.stackTraceToString()}" }
            throw e
        }
    }

    override fun close() {
        // لا يوجد Stream مفتوح ليتطلب الإغلاق
    }
}