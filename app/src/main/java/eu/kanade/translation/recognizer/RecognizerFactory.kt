package eu.kanade.translation.recognizer

import android.content.Context
import tachiyomi.domain.translation.TranslationPreferences
import android.util.Log
object RecognizerFactory {

    // تأكد من تمرير Context هنا لأن PaddleOCR يحتاجه لقراءة ملفات ONNX من الـ Assets
    fun createRecognizer(
        context: Context,
        language: TextRecognizerLanguage,
        enginePreference: Int // رقم المحرك المختار من الإعدادات
    ): TextRecognizer {
        return when (enginePreference) {
            1 -> {
                Log.e("OCR_ENGINE", "MLKIT")
                MlKitTextRecognizer(language)
            }

            2 -> {
                Log.e("OCR_ENGINE", "PADDLE")
                PaddleTextRecognizer(context, language)
            }

            3 -> {
                Log.e("OCR_ENGINE", "HYBRID")
                MlKitPaddleTextRecognizer(context, language)
            }

            else -> {
                Log.e("OCR_ENGINE", "DEFAULT_MLKIT")
                MlKitTextRecognizer(language)
            }
        }

    }
}
