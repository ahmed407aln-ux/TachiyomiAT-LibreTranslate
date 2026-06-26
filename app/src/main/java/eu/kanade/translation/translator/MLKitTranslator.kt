package eu.kanade.translation.translator

import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class MLKitTranslator(
    override val fromLang: TextRecognizerLanguage,
    override val toLang: TextTranslatorLanguage,
) : TextTranslator {

    private val translator = Translation.getClient(
        TranslatorOptions.Builder()
            .setSourceLanguage(fromLang.code)
            .setTargetLanguage(TranslateLanguage.fromLanguageTag(toLang.code) ?: TranslateLanguage.ENGLISH)
            .build()
    )

    private val conditions = DownloadConditions.Builder().build()

    // تقييد الطلبات المتزامنة (بما أننا نستخدم التجميع، يمكننا تقليل السيمفور لـ 3 مثلاً لأن كل طلب يعالج صفحة كاملة)
    private val translationSemaphore = Semaphore(3)

    // فاصل مميز لا يستخدم عادة في النصوص العادية لدمج وفصل الترجمات
    private val batchSeparator = " ||| "

    override suspend fun translate(pages: MutableMap<String, PageTranslation>) {
        try {
            // تحميل النموذج إذا لم يكن موجوداً
            translator.downloadModelIfNeeded(conditions).await()

            withContext(Dispatchers.IO) {
                supervisorScope {
                    pages.values.forEach { page ->
                        // نستخدم launch لكل صفحة ليتم معالجة الصفحات بالتوازي
                        launch {
                            try {
                                // 1. تنظيف وتجميع النصوص للصفحة بأكملها
                                val validBlocks = page.blocks.filter { it.text.trim().isNotBlank() }
                                if (validBlocks.isEmpty()) return@launch

                                val batchedText = validBlocks.joinToString(batchSeparator) { block ->
                                    block.text.replace(Regex("\\s+"), " ").trim()
                                }

                                // 2. ترجمة النص المجمع بطلب واحد فقط
                                val translatedBatchedText = translationSemaphore.withPermit {
                                    translator.translate(batchedText).await()
                                }

                                // 3. تقسيم النص المترجم وإعادته للكتل الأصلية
                                val translatedParts = translatedBatchedText.split(batchSeparator)

                                // التأكد من أن عدد الأجزاء المترجمة يطابق عدد الأجزاء الأصلية (أحياناً قد يغير المترجم الفواصل)
                                if (translatedParts.size == validBlocks.size) {
                                    validBlocks.forEachIndexed { index, block ->
                                        block.translation = translatedParts[index].trim()
                                    }
                                } else {
                                    // حالة احتياطية (Fallback): إذا فشل التقسيم، نترجم كل كتلة على حدة كحل أخير
                                    translateBlocksIndividually(validBlocks)
                                }

                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                e.printStackTrace()
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            e.printStackTrace()
        }
    }

    // دالة احتياطية في حال فشل التجميع بسبب مسح المترجم للفاصل (نادرة الحدوث)
    private suspend fun translateBlocksIndividually(blocks: List<eu.kanade.translation.model.TranslationBlock>) {
        supervisorScope {
            blocks.forEach { block ->
                launch {
                    try {
                        val cleanText = block.text.replace(Regex("\\s+"), " ").trim()
                        translationSemaphore.withPermit {
                            block.translation = translator.translate(cleanText).await()
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        block.translation = ""
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    override fun close() {
        translator.close()
    }
}
