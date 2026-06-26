package eu.kanade.translation.translator

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import logcat.LogPriority
import logcat.logcat
import org.codeshipping.llamakotlin.LlamaModel
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ExternalGgufTranslator(
    private val context: Context,
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
    private val ggufUriString: String
) : TextTranslator {

    private var model: LlamaModel? = null

    init {
        logcat(LogPriority.INFO) { "تم تجهيز المترجم المحلي." }
    }

    // إجبار التحميل على العمل في مسار الخلفية (Dispatchers.IO)
    private suspend fun loadModelIfNeeded() = withContext(Dispatchers.IO) {
        if (model != null) return@withContext

        try {
            if (ggufUriString.isNotBlank()) {
                val uri = Uri.parse(ggufUriString)
                val realPath = getRealPathFromURI(context, uri)

                if (realPath != null && File(realPath).exists()) {
                    model = LlamaModel.load(realPath) {
                        contextSize = 2048
                        threads = 4
                        temperature = 0.1f // تقليل الحرارة لترجمة دقيقة وغير مهلوسة
                    }
                    logcat(LogPriority.INFO) { "تم تحميل المودل للترجمة بنجاح!" }
                }
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR) { "فشل التحميل: ${e.message}" }
        }
    }

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) = withContext(Dispatchers.IO) {
        loadModelIfNeeded()

        if (model == null) {
            pages.values.flatMap { it.blocks }.forEach { it.translation = "[المودل غير محمل]" }
            return@withContext
        }

        // 1. حساب إجمالي الكتل النصية المطلوبة ترجمتها
        val totalBlocks = pages.values.sumOf { it.blocks.size }
        var currentBlock = 0

        for ((pageName, page) in pages) {
            page.blocks.forEach { block ->
                val cleanText = block.text.trim().replace("\n", " ")
                if (cleanText.isBlank()) return@forEach

                // 2. حساب التقدم وطباعته في Logcat
                currentBlock++
                val progress = (currentBlock * 100) / totalBlocks
                logcat(LogPriority.INFO) { "التقدم: $progress% ($currentBlock/$totalBlocks) - جاري معالجة: $cleanText" }

                try {
                    val prompt = "<start_of_turn>user\nTranslate the following text to ${toLang.name}. Output ONLY the translation without any quotes or explanations:\n$cleanText<end_of_turn>\n<start_of_turn>model\n"

                    var translatedResult = ""
                    model?.generateStream(prompt)?.collect { token ->
                        translatedResult += token
                    }

                    val finalTranslation = translatedResult.replace("<eos>", "").trim()

                    if (finalTranslation.isBlank()) {
                        block.translation = "[فارغ]"
                    } else {
                        block.translation = finalTranslation
                    }

                } catch (e: Exception) {
                    block.translation = "[خطأ في التوليد]"
                }
            }
        }
    }

    override fun close() {
        model?.close()
        model = null
    }

    private fun getRealPathFromURI(context: Context, uri: Uri): String? {
        if (uri.scheme == "file") return uri.path
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        val nameIndex = cursor?.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        cursor?.moveToFirst()
        val name = cursor?.getString(nameIndex ?: 0) ?: "temp_model.gguf"
        cursor?.close()
        val file = File(context.cacheDir, name)
        if (!file.exists()) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val outputStream = FileOutputStream(file)
                inputStream?.copyTo(outputStream)
                inputStream?.close()
                outputStream.close()
            } catch (e: Exception) {
                return null
            }
        }
        return file.absolutePath
    }
}
