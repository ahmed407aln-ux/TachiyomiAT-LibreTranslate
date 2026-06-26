package eu.kanade.translation.recognizer

import com.google.mlkit.vision.common.InputImage
import java.io.Closeable

interface TextRecognizer : Closeable {
    val language: TextRecognizerLanguage
     fun recognize(image: InputImage): String
}
