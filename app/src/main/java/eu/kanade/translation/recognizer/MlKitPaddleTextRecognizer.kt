package eu.kanade.translation.recognizer

import android.content.Context
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import kotlinx.coroutines.CancellationException
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority

class MlKitPaddleTextRecognizer(
    private val context: Context,
    override val language: TextRecognizerLanguage
) : TextRecognizer {

    private val TAG = "MlKitPaddleRecognizer"

    // تهيئة المحركين معاً
    private val mlKitRecognizer = MlKitTextRecognizer(language)
    private val paddleRecognizer = PaddleTextRecognizer(context, language)

    init {
        Log.d(TAG, "Initializing Hybrid TextRecognizer (ML Kit + PaddleOCR) for language: $language")
    }

    override fun recognize(image: InputImage): String {
        Log.d(TAG, "Starting hybrid recognize(image) method")
        return try {
            // 1. المحاولة الأولى: استخدام ML Kit لأنه خفيف جداً على المعالج وسريع
            Log.d(TAG, "Attempting text extraction using ML Kit...")
            var extractedText = mlKitRecognizer.recognize(image)

            // 2. المحاولة الثانية: إذا فشل ML Kit (النص فارغ)، نلجأ إلى دقة PaddleOCR
            if (extractedText.trim().isEmpty()) {
                Log.i(TAG, "ML Kit returned empty text, switching to PaddleOCR...")
                logcat(LogPriority.INFO) { "ML Kit فشل في التقاط النص، التبديل إلى PaddleOCR..." }

                extractedText = paddleRecognizer.recognize(image)
                Log.d(TAG, "PaddleOCR extraction completed")
            } else {
                Log.d(TAG, "ML Kit successfully extracted text")
            }

            // السطر المطلوب لطباعة النص النهائي المستخرج (سواء من ML Kit أو PaddleOCR)
            if (extractedText.isNotBlank()) {
                Log.e("PADDLE_TEXT", "TEXT = $extractedText")
            } else {
                Log.w("PADDLE_TEXT", "TEXT is completely empty after trying both engines")
            }

            extractedText
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Error in Hybrid Recognizer: ${e.message}", e)
            logcat(LogPriority.ERROR, e) { "خطأ في المحرك الهجين (MlKit+Paddle)" }
            ""
        }
    }

    override fun close() {
        Log.d(TAG, "Closing both ML Kit and PaddleOCR recognizers to release memory")
        // يجب إغلاق المحركين لتنظيف الذاكرة (RAM) ومسح موترات ONNX
        try {
            mlKitRecognizer.close()
            paddleRecognizer.close()
            Log.d(TAG, "Recognizers closed successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error while closing recognizers: ${e.message}", e)
        }
    }
}
