package eu.kanade.translation.recognizer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import tachiyomi.core.common.util.system.logcat
import logcat.LogPriority

data class PaddleTextBlock(
    val text: String,
    val confidence: Float = 1f,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
)

data class TextBox(
    val minX: Int,
    val minY: Int,
    val maxX: Int,
    val maxY: Int
)

class PaddleTextRecognizer(
    private val context: Context,
    override val language: TextRecognizerLanguage
) : TextRecognizer {

    private val TAG = "PaddleTextRecognizer"
    private var ortEnvironment: OrtEnvironment? = null
    private var detSession: OrtSession? = null
    private var recSession: OrtSession? = null
    private val dictionary = mutableListOf<String>()

    private val usesWordSpaces: Boolean
        get() = language != TextRecognizerLanguage.JAPANESE &&
            language != TextRecognizerLanguage.CHINESE

    init {
        Log.d(TAG, "Initializing PaddleTextRecognizer for language: $language")
        try {
            ortEnvironment = OrtEnvironment.getEnvironment()

            Log.d(TAG, "Loading detection model...")
            val detModel = context.assets.open("paddleocr/det_model.onnx").readBytes()
            val detOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            detSession = ortEnvironment?.createSession(detModel, detOptions)
            Log.d(TAG, "Detection model loaded successfully.")

            val (recModelPath, dictPath) = when (language) {
                TextRecognizerLanguage.CHINESE -> Pair("paddleocr/rec_ch_model.onnx", "paddleocr/ch_dict.txt")
                TextRecognizerLanguage.JAPANESE -> Pair("paddleocr/rec_jp_model.onnx", "paddleocr/jp_dict.txt")
                TextRecognizerLanguage.KOREAN -> Pair("paddleocr/rec_kr_model.onnx", "paddleocr/kr_dict.txt")
                else -> Pair("paddleocr/rec_en_model.onnx", "paddleocr/en_dict.txt")
            }

            Log.d(TAG, "Loading recognition model from $recModelPath...")
            val recModel = context.assets.open(recModelPath).readBytes()
            val recOptions = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(4)
            }
            recSession = ortEnvironment?.createSession(recModel, recOptions)
            Log.d(TAG, "Recognition model loaded successfully.")

            Log.d(TAG, "Loading dictionary from $dictPath...")
            BufferedReader(InputStreamReader(context.assets.open(dictPath))).use { reader ->
                dictionary.add("blank")
                reader.forEachLine { line -> dictionary.add(line) }
            }
            Log.d(TAG, "Dictionary loaded with ${dictionary.size} entries.")

            Log.i(TAG, "تم تهيئة PaddleOCR بنجاح للغة: ${language.name}")
            logcat(LogPriority.INFO) { "تم تهيئة PaddleOCR بنجاح للغة: ${language.name}" }

        } catch (e: Exception) {
            Log.e(TAG, "فشل في تحميل نماذج ONNX", e)
            logcat(LogPriority.ERROR, e) { "فشل في تحميل نماذج ONNX لـ PaddleOCR" }
        }
    }

    override fun recognize(image: InputImage): String {
        Log.d(TAG, "Starting recognize(image) method")
        val result = process(image).joinToString("\n") { it.text }
        Log.d(TAG, "recognize(image) finished processing")
        return result
    }

    fun process(image: InputImage): List<PaddleTextBlock> {
        Log.d(TAG, "Starting process(image) method")
        val bitmap = image.bitmapInternal ?: run {
            Log.w(TAG, "Image bitmap is null, returning empty list")
            return emptyList()
        }

        Log.d(TAG, "Calling detectTextBoxes...")
        val boxes = detectTextBoxes(bitmap)
        Log.d(TAG, "detectTextBoxes returned ${boxes.size} boxes")

        if (boxes.isEmpty()) return emptyList()

        val resultBlocks = mutableListOf<PaddleTextBlock>()

        Log.d(TAG, "Processing each detected box for text recognition...")
        for ((index, box) in boxes.withIndex()) {
            val cropW = box.maxX - box.minX
            val cropH = box.maxY - box.minY
            if (cropW < 12 || cropH < 12) continue

            var crop: Bitmap? = null
            try {
                val padX = (cropW * 0.04f).toInt()
                val padY = (cropH * 0.04f).toInt()

                val startX = max(0, box.minX - padX)
                val startY = max(0, box.minY - padY)
                val endX = min(bitmap.width, box.maxX + padX)
                val endY = min(bitmap.height, box.maxY + padY)

                val newCropW = endX - startX
                val newCropH = endY - startY

                crop = Bitmap.createBitmap(bitmap, startX, startY, newCropW, newCropH)

                val deskewedCrop = applyBasicDeskewIfNecessary(crop)
                val preprocessedCrop = preprocessForRecognition(deskewedCrop)

                val (text, confidence) = recognizeCrop(preprocessedCrop)

                Log.d(TAG, "Raw Text [Box $index]: '$text' | Conf: $confidence")

                if (deskewedCrop != crop) deskewedCrop.recycle()
                if (preprocessedCrop != deskewedCrop) preprocessedCrop.recycle()

                if (isValidText(text, confidence)) {
                    resultBlocks.add(
                        PaddleTextBlock(
                            text = text.trim(),
                            confidence = confidence,
                            x = startX.toFloat(),
                            y = startY.toFloat(),
                            width = newCropW.toFloat(),
                            height = newCropH.toFloat()
                        )
                    )
                } else {
                    Log.d(TAG, "Rejected by isValidText [Box $index]: '$text'")
                }
            } catch (e: Exception) {
                Log.e(TAG, "خطأ في قص الصورة للصندوق $index", e)
            } finally {
                crop?.recycle()
            }
        }

        Log.d(TAG, "Calling mergeBlocksIntelligently to merge ${resultBlocks.size} blocks...")
        val finalBlocks = mergeBlocksIntelligently(resultBlocks)

        val extractedText = finalBlocks.joinToString("\n") { it.text }
        if (extractedText.isNotBlank()) {
            Log.e("PADDLE_TEXT", "TEXT = \n$extractedText")
        }

        return finalBlocks
    }

    private fun detectTextBoxes(bitmap: Bitmap): List<TextBox> {
        var resized: Bitmap? = null
        var tensor: OnnxTensor? = null
        var result: OrtSession.Result? = null

        return try {
            val maxEdge = max(bitmap.width, bitmap.height)
            val limit = when {
                maxEdge > 2000 -> 1536f
                maxEdge > 1200 -> 1280f
                else -> 960f
            }

            val scale = min(limit / bitmap.width, limit / bitmap.height)
            var targetW = (bitmap.width * scale).toInt()
            var targetH = (bitmap.height * scale).toInt()

            targetW = (targetW + 31) / 32 * 32
            targetH = (targetH + 31) / 32 * 32

            resized = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
            val buffer = bitmapToFloatBuffer(resized)

            tensor = OnnxTensor.createTensor(ortEnvironment, buffer, longArrayOf(1, 3, targetH.toLong(), targetW.toLong()))
            result = detSession?.run(mapOf("x" to tensor))

            val output = result?.get(0)?.value as? Array<Array<Array<FloatArray>>> ?: return emptyList()
            val map = output[0][0]

            val scaleX = bitmap.width.toFloat() / targetW
            val scaleY = bitmap.height.toFloat() / targetH

            extractAndUnclipBoxes(map, threshold = 0.4f, minArea = 30, scaleX, scaleY, bitmap.width, bitmap.height)

        } catch (e: Exception) {
            Log.e(TAG, "خطأ في مرحلة الـ Detection", e)
            emptyList()
        } finally {
            resized?.recycle()
            tensor?.close()
            result?.close()
        }
    }

    private fun extractAndUnclipBoxes(
        map: Array<FloatArray>,
        threshold: Float,
        minArea: Int,
        scaleX: Float,
        scaleY: Float,
        maxWidth: Int,
        maxHeight: Int
    ): List<TextBox> {
        val h = map.size
        val w = map[0].size
        val visited = Array(h) { BooleanArray(w) }
        val rawBoxes = mutableListOf<TextBox>()

        val unclipRatio = 1.3f

        for (y in 0 until h) {
            for (x in 0 until w) {
                if (visited[y][x] || map[y][x] < threshold) continue

                var minX = x; var maxX = x
                var minY = y; var maxY = y
                var area = 0

                val queue = ArrayDeque<Pair<Int, Int>>()
                queue.add(Pair(x, y))
                visited[y][x] = true

                while (queue.isNotEmpty()) {
                    val (cx, cy) = queue.removeFirst()
                    area++

                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    val neighbors = arrayOf(
                        Pair(cx - 1, cy),
                        Pair(cx + 1, cy),
                        Pair(cx, cy - 1),
                        Pair(cx, cy + 1),
                        Pair(cx - 1, cy - 1),
                        Pair(cx + 1, cy - 1),
                        Pair(cx - 1, cy + 1),
                        Pair(cx + 1, cy + 1)
                    )
                    for ((nx, ny) in neighbors) {
                        if (nx < 0 || nx >= w || ny < 0 || ny >= h) continue
                        if (!visited[ny][nx] && map[ny][nx] >= threshold) {
                            visited[ny][nx] = true
                            queue.add(Pair(nx, ny))
                        }
                    }
                }

                if (area >= minArea) {
                    val bw = maxX - minX
                    val bh = maxY - minY

                    val aspectRatio = if (bh > 0) bw.toFloat() / bh else 1f
                    if (bw < 3 && bh < 3) continue

                    val perimeter = 2 * (bw + bh)
                    val distance = if (perimeter > 0) (area * unclipRatio) / perimeter else 0f

                    val finalMinX = max(0, ((minX - distance) * scaleX).toInt())
                    val finalMinY = max(0, ((minY - distance) * scaleY).toInt())
                    val finalMaxX = min(maxWidth, ((maxX + distance) * scaleX).toInt())
                    val finalMaxY = min(maxHeight, ((maxY + distance) * scaleY).toInt())

                    val finalW = finalMaxX - finalMinX
                    val finalH = finalMaxY - finalMinY
                    if (finalW >= 5 && finalH >= 5) {
                        rawBoxes.add(TextBox(finalMinX, finalMinY, finalMaxX, finalMaxY))
                    }
                }
            }
        }

        return mergeOverlappingBoxes(rawBoxes)
    }

    private fun mergeOverlappingBoxes(boxes: List<TextBox>): List<TextBox> {
        if (boxes.size <= 1) return boxes

        val sorted = boxes.sortedBy { it.minY * 100000 + it.minX }
        val merged = mutableListOf<TextBox>()
        val used = BooleanArray(sorted.size)

        for (i in sorted.indices) {
            if (used[i]) continue
            var cur = sorted[i]
            for (j in i + 1 until sorted.size) {
                if (used[j]) continue
                val other = sorted[j]

                val iou = computeIoU(cur, other)
                if (iou > 0.5f) {
                    cur = TextBox(
                        min(cur.minX, other.minX),
                        min(cur.minY, other.minY),
                        max(cur.maxX, other.maxX),
                        max(cur.maxY, other.maxY)
                    )
                    used[j] = true
                }
            }
            merged.add(cur)
        }

        return merged
    }

    private fun computeIoU(a: TextBox, b: TextBox): Float {
        val interMinX = max(a.minX, b.minX)
        val interMinY = max(a.minY, b.minY)
        val interMaxX = min(a.maxX, b.maxX)
        val interMaxY = min(a.maxY, b.maxY)

        val interW = max(0, interMaxX - interMinX)
        val interH = max(0, interMaxY - interMinY)
        val interArea = interW * interH

        if (interArea == 0) return 0f

        val aArea = (a.maxX - a.minX) * (a.maxY - a.minY)
        val bArea = (b.maxX - b.minX) * (b.maxY - b.minY)
        val unionArea = aArea + bArea - interArea

        return if (unionArea <= 0) 0f else interArea.toFloat() / unionArea
    }

    private fun preprocessForRecognition(bitmap: Bitmap): Bitmap {
        if (bitmap.width < 10 || bitmap.height < 10) return bitmap

        return try {
            val result = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(result)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)

            val contrastScale = 1.15f
            val contrastTranslate = 5f
            val colorMatrix = ColorMatrix(floatArrayOf(
                contrastScale, 0f, 0f, 0f, contrastTranslate,
                0f, contrastScale, 0f, 0f, contrastTranslate,
                0f, 0f, contrastScale, 0f, contrastTranslate,
                0f, 0f, 0f, 1f, 0f
            ))
            paint.colorFilter = ColorMatrixColorFilter(colorMatrix)
            canvas.drawBitmap(bitmap, 0f, 0f, paint)

            val sharpened = applySharpen(result, strength = 0.25f)

            result.recycle()
            sharpened
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في preprocessing", e)
            bitmap
        }
    }

    private fun applySharpen(bitmap: Bitmap, strength: Float): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val output = IntArray(w * h)
        val s = strength.coerceIn(0f, 1f)

        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val idx = y * w + x

                val center = pixels[idx]
                val top    = pixels[(y - 1) * w + x]
                val bottom = pixels[(y + 1) * w + x]
                val left   = pixels[y * w + (x - 1)]
                val right  = pixels[y * w + (x + 1)]

                val rSharp = (5 * Color.red(center) - Color.red(top) - Color.red(bottom) - Color.red(left) - Color.red(right))
                val gSharp = (5 * Color.green(center) - Color.green(top) - Color.green(bottom) - Color.green(left) - Color.green(right))
                val bSharp = (5 * Color.blue(center) - Color.blue(top) - Color.blue(bottom) - Color.blue(left) - Color.blue(right))

                val rFinal = (Color.red(center) * (1 - s) + rSharp.coerceIn(0, 255) * s).toInt().coerceIn(0, 255)
                val gFinal = (Color.green(center) * (1 - s) + gSharp.coerceIn(0, 255) * s).toInt().coerceIn(0, 255)
                val bFinal = (Color.blue(center) * (1 - s) + bSharp.coerceIn(0, 255) * s).toInt().coerceIn(0, 255)

                output[idx] = Color.rgb(rFinal, gFinal, bFinal)
            }
        }

        for (x in 0 until w) {
            output[x] = pixels[x]
            output[(h - 1) * w + x] = pixels[(h - 1) * w + x]
        }
        for (y in 0 until h) {
            output[y * w] = pixels[y * w]
            output[y * w + (w - 1)] = pixels[y * w + (w - 1)]
        }

        val result = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        result.setPixels(output, 0, w, 0, 0, w, h)
        return result
    }

    private fun applyBasicDeskewIfNecessary(bitmap: Bitmap): Bitmap {
        if (bitmap.height > bitmap.width * 1.5) {
            return bitmap
        }

        if (bitmap.width < 30 || bitmap.height < 10) {
            return bitmap
        }

        return try {
            val angle = estimateSkewAngle(bitmap)

            if (abs(angle) < 0.5f || abs(angle) > 15f) {
                return bitmap
            }

            val matrix = Matrix()
            matrix.postRotate(-angle, bitmap.width / 2f, bitmap.height / 2f)
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            rotated
        } catch (e: Exception) {
            Log.e(TAG, "خطأ في deskew", e)
            bitmap
        }
    }

    private fun estimateSkewAngle(bitmap: Bitmap): Float {
        val w = bitmap.width
        val h = bitmap.height

        val scale = if (w > 300) 300f / w else 1f
        val sw = (w * scale).toInt()
        val sh = (h * scale).toInt()

        val small = Bitmap.createScaledBitmap(bitmap, sw, sh, false)
        val gray = IntArray(sw * sh)
        small.getPixels(gray, 0, sw, 0, 0, sw, sh)
        small.recycle()

        val grayF = FloatArray(sw * sh) { i ->
            val c = gray[i]
            (0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)) / 255f
        }

        var bestAngle = 0f
        var bestScore = -1f

        for (angleTenths in -20..20) {
            val angle = angleTenths * 0.5f
            val score = computeProjectionScore(grayF, sw, sh, angle)
            if (score > bestScore) {
                bestScore = score
                bestAngle = angle
            }
        }

        return bestAngle
    }

    private fun computeProjectionScore(gray: FloatArray, w: Int, h: Int, angleDeg: Float): Float {
        val rad = Math.toRadians(angleDeg.toDouble()).toFloat()
        val cosA = kotlin.math.cos(rad.toDouble()).toFloat()
        val sinA = kotlin.math.sin(rad.toDouble()).toFloat()
        val cx = w / 2f
        val cy = h / 2f

        val projection = FloatArray(h)

        for (y in 0 until h) {
            var rowSum = 0f
            for (x in 0 until w) {
                val rx = (x - cx) * cosA - (y - cy) * sinA + cx
                val ry = (x - cx) * sinA + (y - cy) * cosA + cy
                val ix = rx.toInt()
                val iy = ry.toInt()
                if (ix < 0 || ix >= w || iy < 0 || iy >= h) {
                    rowSum += 1f
                } else {
                    rowSum += gray[iy * w + ix]
                }
            }
            projection[y] = rowSum
        }

        val mean = projection.average().toFloat()
        var variance = 0f
        for (v in projection) variance += (v - mean) * (v - mean)
        return variance
    }

    private fun recognizeCrop(processedBitmap: Bitmap): Pair<String, Float> {
        val targetHeight = 48
        val aspectRatio = processedBitmap.width.toFloat() / processedBitmap.height.toFloat()
        val targetWidth = min(
            320,
            max(
                48,
                (targetHeight * aspectRatio).toInt()
            )
        )

        var resizedBitmap: Bitmap? = null
        var inputTensor: OnnxTensor? = null
        var result: OrtSession.Result? = null

        return try {
            resizedBitmap = Bitmap.createScaledBitmap(processedBitmap, targetWidth, targetHeight, true)
            val floatBuffer = bitmapToFloatBuffer(resizedBitmap)
            val shape = longArrayOf(1, 3, targetHeight.toLong(), targetWidth.toLong())

            inputTensor = OnnxTensor.createTensor(ortEnvironment, floatBuffer, shape)
            result = recSession?.run(mapOf("x" to inputTensor))

            val outputData = result?.get(0)?.value as? Array<Array<FloatArray>> ?: return Pair("", 0f)
            val sequence = outputData[0]

            val sample = sequence[0]

            Log.e(
                "OCR_SHAPE",
                "firstStep min=${sample.minOrNull()} max=${sample.maxOrNull()}"
            )

            Log.e(
                "OCR_SIZE",
                "crop=${processedBitmap.width}x${processedBitmap.height} target=${targetWidth}x${targetHeight}"
            )

            if (dictionary.size > 10000) {
                decodeGreedy(sequence)
            } else {
                decodeBeamSearch(sequence, beamWidth = 5)
            }


            val resultText = decodeGreedy(sequence)

            Log.e(
                "OCR_OUTPUT",
                "text=[${resultText.first}] conf=${resultText.second} crop=${processedBitmap.width}x${processedBitmap.height}"
            )

            resultText

        } catch (e: Exception) {
            Log.e(TAG, "خطأ أثناء فك التشفير (Recognition)", e)
            Pair("", 0f)
        } finally {
            resizedBitmap?.recycle()
            inputTensor?.close()
            result?.close()
        }
    }

    private fun decodeGreedy(sequence: Array<FloatArray>): Pair<String, Float> {
        val extractedText = StringBuilder()
        var lastIndex = 0
        var confidenceSum = 0f
        var validCharCount = 0

        for (step in sequence) {
            var maxProb = 0f
            var maxIdx = 0
            for (i in step.indices) {
                if (step[i] > maxProb) {
                    maxProb = step[i]
                    maxIdx = i
                }
            }

            if (maxIdx != 0 && maxIdx != lastIndex && maxIdx < dictionary.size) {
                extractedText.append(dictionary[maxIdx])
                confidenceSum += maxProb
                validCharCount++
            }
            lastIndex = maxIdx
        }

        val finalConf = if (validCharCount == 0) 0f else (confidenceSum / validCharCount)
        return Pair(extractedText.toString().trim(), finalConf)
    }

    private fun decodeBeamSearch(sequence: Array<FloatArray>, beamWidth: Int): Pair<String, Float> {
        data class Beam(
            val text: String,
            val lastIdx: Int,
            val logProb: Float,
            val charCount: Int,
            val confSum: Float
        )

        var beams = listOf(Beam("", 0, 0f, 0, 0f))

        for (probs in sequence) {
            val topK = probs.indices
                .sortedByDescending { probs[it] }
                .take(beamWidth)

            val newBeams = mutableListOf<Beam>()
            for (beam in beams) {
                for (idx in topK) {
                    val prob = probs[idx]
                    val logP = if (prob > 0f) kotlin.math.ln(prob.toDouble()).toFloat() else -20f

                    if (idx == 0) {
                        newBeams.add(Beam(beam.text, 0, beam.logProb + logP, beam.charCount, beam.confSum))
                    } else if (idx != beam.lastIdx && idx < dictionary.size) {
                        val newText = beam.text + dictionary[idx]
                        newBeams.add(Beam(newText, idx, beam.logProb + logP, beam.charCount + 1, beam.confSum + prob))
                    } else {
                        newBeams.add(Beam(beam.text, beam.lastIdx, beam.logProb + logP, beam.charCount, beam.confSum))
                    }
                }
            }

            beams = newBeams
                .sortedByDescending { it.logProb }
                .take(beamWidth)
        }

        val best = beams.firstOrNull() ?: return Pair("", 0f)
        val finalConf = if (best.charCount == 0) 0f else best.confSum / best.charCount
        return Pair(best.text.trim(), finalConf)
    }

    private fun mergeBlocksIntelligently(blocks: List<PaddleTextBlock>): List<PaddleTextBlock> {
        if (blocks.isEmpty()) return emptyList()

        val sortedBlocks = blocks.sortedBy { it.y }
        val lines = mutableListOf<MutableList<PaddleTextBlock>>()

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

        val intermediateBlocks = mutableListOf<PaddleTextBlock>()

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

        val finalLines = mutableListOf<MutableList<PaddleTextBlock>>()
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

        val result = mutableListOf<PaddleTextBlock>()
        for (line in finalLines) {
            line.sortBy { it.x }
            result.addAll(line)
        }

        return result
    }

    private fun mergeTwoBlocks(a: PaddleTextBlock, b: PaddleTextBlock, separator: String): PaddleTextBlock {
        val newX = min(a.x, b.x)
        val newY = min(a.y, b.y)
        val newMaxX = max(a.x + a.width, b.x + b.width)
        val newMaxY = max(a.y + a.height, b.y + b.height)

        val combinedText = "${a.text}$separator${b.text}"
        val totalLen = a.text.length + b.text.length
        val avgConf = if (totalLen > 0) {
            (a.confidence * a.text.length + b.confidence * b.text.length) / totalLen
        } else 1f

        return PaddleTextBlock(combinedText, avgConf, newX, newY, newMaxX - newX, newMaxY - newY)
    }

    private fun isValidText(text: String, confidence: Float): Boolean {
        val trimmed = text.trim()

        if (trimmed.isEmpty() || confidence < 0.35f) return false

        if (trimmed.length == 1 && !trimmed[0].isLetterOrDigit() && confidence < 0.55f) return false

        return true
    }

    private fun bitmapToFloatBuffer(bitmap: Bitmap): FloatBuffer {
        val width = bitmap.width
        val height = bitmap.height
        val area = width * height

        val floatBuffer = ByteBuffer.allocateDirect(4 * 3 * area).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val pixels = IntArray(area)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val color = pixels[i]
            floatBuffer.put(i, (Color.red(color) / 255f - 0.5f) / 0.5f)
            floatBuffer.put(i + area, (Color.green(color) / 255f - 0.5f) / 0.5f)
            floatBuffer.put(i + 2 * area, (Color.blue(color) / 255f - 0.5f) / 0.5f)
        }

        floatBuffer.rewind()
        return floatBuffer
    }

    override fun close() {
        try {
            detSession?.close()
            recSession?.close()
            ortEnvironment?.close()
            dictionary.clear()
        } catch (e: Exception) {
            Log.e(TAG, "خطأ أثناء تنظيف الذاكرة وإغلاق ONNX", e)
        }
    }
}
