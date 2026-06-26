package eu.kanade.translation.recognizer

import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// كلاس بيانات لحفظ إحداثيات نصوص ML Kit للترتيب الذكي
data class MlKitTextBlock(
    val text: String,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

class MlKitTextRecognizer(override val language: TextRecognizerLanguage) : TextRecognizer {

    private val TAG = "MlKitTextRecognizer"

    // تحديد ما إذا كانت اللغة تستخدم مسافات بين الكلمات أم لا
    private val usesWordSpaces: Boolean
        get() = language != TextRecognizerLanguage.JAPANESE &&
            language != TextRecognizerLanguage.CHINESE

    init {
        Log.d(TAG, "Initializing TextRecognizer for language: $language")
    }

    private val recognizer = TextRecognition.getClient(
        when (language) {
            TextRecognizerLanguage.ENGLISH -> TextRecognizerOptions.DEFAULT_OPTIONS
            TextRecognizerLanguage.CHINESE -> ChineseTextRecognizerOptions.Builder().build()
            TextRecognizerLanguage.JAPANESE -> JapaneseTextRecognizerOptions.Builder().build()
            TextRecognizerLanguage.KOREAN -> KoreanTextRecognizerOptions.Builder().build()
        },
    )

    override fun recognize(image: InputImage): String {
        Log.d(TAG, "Starting recognize(image) method")
        val mlKitResult = process(image)

        val rawBlocks = mutableListOf<MlKitTextBlock>()
        for (block in mlKitResult.textBlocks) {
            val boundingBox = block.boundingBox
            if (boundingBox != null && block.text.isNotBlank()) {
                rawBlocks.add(
                    MlKitTextBlock(
                        text = block.text.trim(),
                        x = boundingBox.left.toFloat(),
                        y = boundingBox.top.toFloat(),
                        width = boundingBox.width().toFloat(),
                        height = boundingBox.height().toFloat()
                    )
                )
            }
        }

        val finalBlocks = mergeBlocksIntelligently(rawBlocks)
        return finalBlocks.joinToString("\n") { it.text }
    }

    fun process(image: InputImage): Text {
        Log.d(TAG, "Starting process(image) method")
        return try {
            val result = Tasks.await<Text>(recognizer.process(image))
            Log.d(TAG, "Text processing completed successfully")

            // === استخراج وترتيب النصوص للطباعة في اللوج (PADDLE_TEXT) ===
            val rawBlocks = mutableListOf<MlKitTextBlock>()
            for (block in result.textBlocks) {
                val boundingBox = block.boundingBox
                if (boundingBox != null && block.text.isNotBlank()) {
                    rawBlocks.add(
                        MlKitTextBlock(
                            text = block.text.trim(),
                            x = boundingBox.left.toFloat(),
                            y = boundingBox.top.toFloat(),
                            width = boundingBox.width().toFloat(),
                            height = boundingBox.height().toFloat()
                        )
                    )
                }
            }

            val finalBlocks = mergeBlocksIntelligently(rawBlocks)
            val extractedText = finalBlocks.joinToString("\n") { it.text }

            if (extractedText.isNotBlank()) {
                Log.e("PADDLE_TEXT", "TEXT = \n$extractedText")
            } else {
                Log.e("PADDLE_TEXT", "TEXT = [لا يوجد نص]")
            }
            // =========================================================

            result
        } catch (e: Exception) {
            Log.e(TAG, "Error during text processing: ${e.message}", e)
            throw e
        }
    }

    // خوارزمية الترتيب والدمج الذكي للحفاظ على سياق فقاعات الحوار
    private fun mergeBlocksIntelligently(blocks: List<MlKitTextBlock>): List<MlKitTextBlock> {
        if (blocks.isEmpty()) return emptyList()

        // ترتيب مبدئي عمودي
        val sortedBlocks = blocks.sortedBy { it.y }
        val lines = mutableListOf<MutableList<MlKitTextBlock>>()

        // تجميع الكتل في أسطر بناءً على التداخل العمودي
        for (block in sortedBlocks) {
            var placed = false
            for (line in lines) {
                val leader = line.first()
                val currentLineCenter = line.map { it.y + it.height / 2f }.average().toFloat()
                val blockCenter = block.y + block.height / 2f
                val allowedOverlap = min(leader.height, block.height) * 0.40f

                if (abs(blockCenter - currentLineCenter) < allowedOverlap) {
                    line.add(block)
                    placed = true
                    break
                }
            }
            if (!placed) {
                lines.add(mutableListOf(block))
            }
        }

        val intermediateBlocks = mutableListOf<MlKitTextBlock>()

        // دمج الكتل المتقاربة أفقياً في نفس السطر
        for (line in lines) {
            line.sortBy { it.x }
            var current = line.first()

            for (i in 1 until line.size) {
                val next = line[i]
                val horizontalGap = next.x - (current.x + current.width)

                val estimatedCharWidth = if (current.text.isNotEmpty()) {
                    current.width / current.text.length
                } else {
                    current.height * 0.6f
                }

                val mergeThreshold = estimatedCharWidth * 1.5f
                val spaceThreshold = estimatedCharWidth * 3.0f

                if (horizontalGap < mergeThreshold) {
                    val separator = if (usesWordSpaces) "" else ""
                    current = mergeTwoBlocks(current, next, separator)
                } else if (usesWordSpaces && horizontalGap < spaceThreshold) {
                    current = mergeTwoBlocks(current, next, " ")
                } else if (horizontalGap < current.height * 1.2f && !usesWordSpaces) {
                    current = mergeTwoBlocks(current, next, "")
                } else {
                    intermediateBlocks.add(current)
                    current = next
                }
            }
            intermediateBlocks.add(current)
        }

        // تمريرة دمج نهائية للأسطر
        val finalLines = mutableListOf<MutableList<MlKitTextBlock>>()
        for (block in intermediateBlocks.sortedBy { it.y }) {
            var placed = false
            for (line in finalLines) {
                val currentLineCenter = line.map { it.y + it.height / 2f }.average().toFloat()
                val blockCenter = block.y + block.height / 2f
                if (abs(blockCenter - currentLineCenter) < block.height * 0.5f) {
                    line.add(block)
                    placed = true
                    break
                }
            }
            if (!placed) {
                finalLines.add(mutableListOf(block))
            }
        }

        val result = mutableListOf<MlKitTextBlock>()
        for (line in finalLines) {
            line.sortBy { it.x }
            result.addAll(line)
        }

        return result
    }

    private fun mergeTwoBlocks(a: MlKitTextBlock, b: MlKitTextBlock, separator: String): MlKitTextBlock {
        val newX = min(a.x, b.x)
        val newY = min(a.y, b.y)
        val newMaxX = max(a.x + a.width, b.x + b.width)
        val newMaxY = max(a.y + a.height, b.y + b.height)

        val combinedText = "${a.text}$separator${b.text}"

        return MlKitTextBlock(
            text = combinedText,
            x = newX,
            y = newY,
            width = newMaxX - newX,
            height = newMaxY - newY
        )
    }

    override fun close() {
        Log.d(TAG, "Closing TextRecognizer")
        recognizer.close()
    }
}
