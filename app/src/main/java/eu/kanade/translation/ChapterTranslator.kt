package eu.kanade.translation

import android.content.Context
import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.lang.compareToCaseInsensitiveNaturalOrder
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.translation.data.TranslationProvider
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.Translation
import eu.kanade.translation.model.TranslationBlock
import eu.kanade.translation.recognizer.TextRecognizer
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslator
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TextTranslators
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import logcat.LogPriority
import mihon.core.archive.archiveReader
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.system.ImageUtil
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.at.ATMR
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream
import kotlin.math.abs

class ChapterTranslator(
    private val context: Context,
    private val provider: TranslationProvider,
    private val downloadProvider: DownloadProvider = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val translationPreferences: TranslationPreferences = Injekt.get(),
) {

    private val _queueState = MutableStateFlow<List<Translation>>(emptyList())
    val queueState = _queueState.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var translationJob: Job? = null

    val isRunning: Boolean
        get() = translationJob?.isActive == true

    @Volatile
    var isPaused: Boolean = false

    fun start(): Boolean {
        if (isRunning || queueState.value.isEmpty()) return false
        val pending = queueState.value.filter { it.status != Translation.State.TRANSLATED }
        pending.forEach { if (it.status != Translation.State.QUEUE) it.status = Translation.State.QUEUE }
        isPaused = false
        launchTranslatorJob()
        return pending.isNotEmpty()
    }

    fun stop(reason: String? = null) {
        cancelTranslatorJob()
        queueState.value.filter { it.status == Translation.State.TRANSLATING }
            .forEach { it.status = Translation.State.ERROR }
        if (reason != null) return
        isPaused = false
    }

    fun pause() {
        cancelTranslatorJob()
        queueState.value.filter { it.status == Translation.State.TRANSLATING }
            .forEach { it.status = Translation.State.QUEUE }
        isPaused = true
    }

    fun clearQueue() {
        cancelTranslatorJob()
        internalClearQueue()
    }

    private fun launchTranslatorJob() {
        if (isRunning) return

        translationJob = scope.launch {
            val activeTranslationFlow = queueState.transformLatest { queue ->
                while (true) {
                    val activeTranslations =
                        queue.asSequence().filter { it.status.value <= Translation.State.TRANSLATING.value }
                            .groupBy { it.source }.toList().take(5).map { (_, translations) -> translations.first() }
                    emit(activeTranslations)

                    if (activeTranslations.isEmpty()) break
                    val activeTranslationsErroredFlow =
                        combine(activeTranslations.map(Translation::statusFlow)) { states ->
                            states.contains(Translation.State.ERROR)
                        }.filter { it }
                    activeTranslationsErroredFlow.first()
                }
            }.distinctUntilChanged()

            supervisorScope {
                val translationJobs = mutableMapOf<Translation, Job>()

                activeTranslationFlow.collectLatest { activeTranslations ->
                    val translationJobsToStop = translationJobs.filter { it.key !in activeTranslations }
                    translationJobsToStop.forEach { (download, job) ->
                        job.cancel()
                        translationJobs.remove(download)
                    }

                    val translationsToStart = activeTranslations.filter { it !in translationJobs }
                    translationsToStart.forEach { translation ->
                        translationJobs[translation] = launchTranslationJob(translation)
                    }
                }
            }
        }
    }

    private fun CoroutineScope.launchTranslationJob(translation: Translation) = launchIO {
        try {
            translateChapter(translation)
            if (translation.status == Translation.State.TRANSLATED) {
                removeFromQueue(translation)
            }
            if (areAllTranslationsFinished()) {
                stop()
            }
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            logcat(LogPriority.ERROR, e)
            stop()
        }
    }

    private fun cancelTranslatorJob() {
        translationJob?.cancel()
        translationJob = null
    }

    fun queueChapter(manga: Manga, chapter: Chapter) {
        val source = sourceManager.get(manga.source) as? HttpSource ?: return
        if (provider.findTranslationFile(chapter.name, chapter.scanlator, manga.title, source) != null) return
        if (queueState.value.any { it.chapter.id == chapter.id }) return
        val fromLang = TextRecognizerLanguage.fromPref(translationPreferences.translateFromLanguage())
        val toLang = TextTranslatorLanguage.fromPref(translationPreferences.translateToLanguage())
        val engine = TextTranslators.fromPref(translationPreferences.translationEngine())
        if (engine == TextTranslators.MLKIT && !TextTranslatorLanguage.mlkitSupportedLanguages().contains(toLang)) {
            context.toast(ATMR.strings.error_mlkit_language_unsupported)
            return
        }
        val translation = Translation(source, manga, chapter, fromLang, toLang)
        addToQueue(translation)
    }

    private suspend fun translateChapter(translation: Translation) {
        val ocrEnginePref = translationPreferences.textRecognizerType().get()
        android.util.Log.e(
            "OCR_PREF",
            "ocrEnginePref=$ocrEnginePref"
        )


        val textRecognizer = eu.kanade.translation.recognizer.RecognizerFactory.createRecognizer(
            context,
            translation.fromLang,
            ocrEnginePref
        )
        val textTranslator = TextTranslators.fromPref(translationPreferences.translationEngine())
            .build(translationPreferences, translation.fromLang, translation.toLang)

        val isRtlLanguage = translation.fromLang.toString().lowercase().let {
            it.contains("ja") || it.contains("ko") || it.contains("zh") || it.contains("ar")
        }

        try {
            val translationMangaDir = provider.getMangaDir(translation.manga.title, translation.source)
            val saveFile = provider.getTranslationFileName(translation.chapter.name, translation.chapter.scanlator)

            val chapterPath = downloadProvider.findChapterDir(
                translation.chapter.name,
                translation.chapter.scanlator,
                translation.manga.title,
                translation.source,
            ) ?: throw IllegalStateException("Chapter directory not found")

            val pages = mutableMapOf<String, PageTranslation>()
            val streams = getChapterPages(chapterPath)

            // تحديد المتزامنات بناءً على عدد الأنوية المتاحة لتجنب اختناق المعالج
            val availableProcessors = Runtime.getRuntime().availableProcessors()
            // نستخدم أنوية المعالج مقسومة على 2 أو بحد أقصى 4، لتجنب تجميد الجهاز بالكامل
            val maxConcurrency = (availableProcessors / 2).coerceIn(2, 4)
            val ocrSemaphore = Semaphore(maxConcurrency)

            withContext(Dispatchers.IO) {
                val deferredPages = streams.map { (fileName, streamFn) ->
                    async {
                        ocrSemaphore.withPermit {
                            coroutineContext.ensureActive()

                            var bitmap: android.graphics.Bitmap? = null
                            try {
                                // التحسين 1: تحويل الـ Stream إلى Bitmap مباشرة في الذاكرة (Zero Disk I/O)
                                // هذا يعالج تعليقك السابق "i can't get the BitmapFactory.decodeStream() to work with the stream from .cbz archive"
                                // الـ cbz أحياناً يحتاج إلى قراءة الستريم بالكامل كـ ByteArray أولاً
                                val byteArray = streamFn().use { it.readBytes() }
                                bitmap = BitmapFactory.decodeByteArray(byteArray, 0, byteArray.size)

                                if (bitmap == null) throw IllegalStateException("Failed to decode image: $fileName")

                                val image = InputImage.fromBitmap(bitmap, 0)

                                val pageTranslation = PageTranslation(imgWidth = image.width.toFloat(), imgHeight = image.height.toFloat())

// التحقق من نوع المحرك المستخدم
                                if (textRecognizer is eu.kanade.translation.recognizer.PaddleTextRecognizer) {
                                    val paddleBlocks = withContext(Dispatchers.Default) {
                                        textRecognizer.process(image)
                                    }

                                    // تحويل مربعات PaddleOCR إلى TranslationBlock
                                    paddleBlocks.forEach { block ->
                                        pageTranslation.blocks.add(
                                            TranslationBlock(
                                                text = block.text,
                                                width = block.width,
                                                height = block.height,
                                                symWidth = 15f,
                                                symHeight = 15f,
                                                angle = 0f,
                                                x = block.x,
                                                y = block.y
                                            )
                                        )
                                    }
                                    // دمج البلوكات المتقاربة لـ PaddleOCR
                                    pageTranslation.blocks = smartMergeBlocks(pageTranslation.blocks, isRtlLanguage)

                                } else {
                                    // معالجة MLKit
                                    val latImage = image as com.google.mlkit.vision.common.InputImage
                                    val mlKitTextRecognizer = (textRecognizer as? eu.kanade.translation.recognizer.MlKitTextRecognizer)
                                    val mlKitResult = withContext(Dispatchers.Default) { mlKitTextRecognizer?.process(latImage) }

                                    if (mlKitResult != null) {
                                        val blocks = mlKitResult.textBlocks.filter { it.boundingBox != null && it.text.length > 1 }
                                        val merged = convertToPageTranslation(blocks, image.width, image.height, isRtlLanguage)
                                        pageTranslation.blocks.addAll(merged.blocks)
                                    }
                                }

                                if (pageTranslation.blocks.isNotEmpty()) Pair(fileName, pageTranslation) else null

                                if (pageTranslation.blocks.isNotEmpty()) Pair(fileName, pageTranslation) else null

                            } catch (e: Throwable) {
                                logcat(LogPriority.ERROR, e) { "Failed to extract text from $fileName" }
                                null
                            } finally {
                                // التحسين 2: تحرير الذاكرة العشوائية فوراً لتجنب OOM
                                bitmap?.recycle()
                            }
                        }
                    }
                }

                deferredPages.awaitAll().filterNotNull().forEach { (fileName, pageTrans) ->
                    pages[fileName] = pageTrans
                }
            }

            withContext(Dispatchers.IO) {
                textTranslator.translate(pages)
            }

            Json.encodeToStream(pages, translationMangaDir.createFile(saveFile)!!.openOutputStream())
            translation.status = Translation.State.TRANSLATED

        } catch (error: Throwable) {
            translation.status = Translation.State.ERROR
            logcat(LogPriority.ERROR, error)
        } finally {
            textRecognizer.close()
            withContext(Dispatchers.IO) {
                textTranslator.close()
            }
        }
    }

    private fun convertToPageTranslation(blocks: List<Text.TextBlock>, width: Int, height: Int, isRtlLanguage: Boolean): PageTranslation {
        val translation = PageTranslation(imgWidth = width.toFloat(), imgHeight = height.toFloat())
        for (block in blocks) {
            val bounds = block.boundingBox ?: continue
            val firstLine = block.lines.firstOrNull()
            val firstElement = firstLine?.elements?.firstOrNull()
            val symBounds = firstElement?.symbols?.firstOrNull()?.boundingBox

            translation.blocks.add(
                TranslationBlock(
                    text = block.text,
                    width = bounds.width().toFloat(),
                    height = bounds.height().toFloat(),
                    symWidth = symBounds?.width()?.toFloat() ?: 15f,
                    symHeight = symBounds?.height()?.toFloat() ?: 15f,
                    angle = firstLine?.angle ?: 0f,
                    x = bounds.left.toFloat(),
                    y = bounds.top.toFloat(),
                ),
            )
        }

        translation.blocks = smartMergeBlocks(translation.blocks, isRtlLanguage)
        return translation
    }

    private fun smartMergeBlocks(blocks: List<TranslationBlock>, isRtlLanguage: Boolean): MutableList<TranslationBlock> {
        if (blocks.isEmpty()) return mutableListOf()

        val clusters = mutableListOf<MutableList<TranslationBlock>>()
        val unmerged = blocks.toMutableList()

        while (unmerged.isNotEmpty()) {
            val currentCluster = mutableListOf(unmerged.removeAt(0))
            var hasMerged: Boolean

            do {
                hasMerged = false
                val iterator = unmerged.iterator()
                while (iterator.hasNext()) {
                    val next = iterator.next()
                    if (currentCluster.any { shouldMergeTextBlock(it, next) }) {
                        currentCluster.add(next)
                        iterator.remove()
                        hasMerged = true
                    }
                }
            } while (hasMerged)

            clusters.add(currentCluster)
        }

        return clusters.map { cluster ->
            // الفرز الذكي بناءً على اللغة
            val sortedCluster = if (isRtlLanguage) {
                // للمانجا: من الأعلى للأسفل، ومن اليمين لليسار
                cluster.sortedWith(compareBy<TranslationBlock> { it.y }.thenByDescending { it.x })
            } else {
                // للغات الغربية: من الأعلى للأسفل، ومن اليسار لليمين
                cluster.sortedWith(compareBy<TranslationBlock> { it.y }.thenBy { it.x })
            }

            var mergedBlock = sortedCluster[0]
            for (i in 1 until sortedCluster.size) {
                // التحسين 3: تمرير حالة اللغة لدالة الدمج لضمان ترتيب الكلمات الصحيح في الـ string
                mergedBlock = mergeTextBlockOrder(mergedBlock, sortedCluster[i], isRtlLanguage)
            }
            mergedBlock
        }.toMutableList()
    }

    private fun shouldMergeTextBlock(a: TranslationBlock, b: TranslationBlock): Boolean {
        val verticalMargin = kotlin.math.max(a.symHeight, b.symHeight) * 1.2f
        val horizontalMargin = kotlin.math.max(a.symHeight, b.symHeight) * 1.5f

        val isXOverlap = (b.x <= a.x + a.width + horizontalMargin) && (b.x + b.width >= a.x - horizontalMargin)
        val isYOverlap = (b.y <= a.y + a.height + verticalMargin) && (b.y + b.height >= a.y - verticalMargin)
        val isAngleSimilar = abs(a.angle - b.angle) < 10f

        return isXOverlap && isYOverlap && isAngleSimilar
    }

    private fun mergeTextBlockOrder(top: TranslationBlock, bottom: TranslationBlock, isRtlLanguage: Boolean): TranslationBlock {
        val newX = kotlin.math.min(top.x, bottom.x)
        val newY = kotlin.math.min(top.y, bottom.y)
        val newWidth = kotlin.math.max(top.x + top.width, bottom.x + bottom.width) - newX
        val newHeight = kotlin.math.max(top.y + top.height, bottom.y + bottom.height) - newY

        val avgSymHeight = (top.symHeight + bottom.symHeight) / 2
        val avgSymWidth = (top.symWidth + bottom.symWidth) / 2
        val avgAngle = (top.angle + bottom.angle) / 2

        // التحسين 3 (تابع): دمج النصوص بالترتيب الصحيح بناءً على اللغة
        val combinedText = if (isRtlLanguage) {
            // إذا كانا على نفس السطر تقريباً، والـ bottom على اليسار، نقرأ top أولاً ثم bottom
            if (abs(top.y - bottom.y) < avgSymHeight) {
                if (top.x > bottom.x) "${top.text} ${bottom.text}" else "${bottom.text} ${top.text}"
            } else {
                "${top.text} ${bottom.text}" // الأسطر الطبيعية (من أعلى لأسفل)
            }
        } else {
            if (abs(top.y - bottom.y) < avgSymHeight) {
                if (top.x < bottom.x) "${top.text} ${bottom.text}" else "${bottom.text} ${top.text}"
            } else {
                "${top.text} ${bottom.text}"
            }
        }

        // نفس المنطق لدمج الترجمة إن وجدت مسبقاً (رغم أنها عادة تكون فارغة هنا)
        val combinedTranslation = if (top.translation.isNotEmpty() && bottom.translation.isNotEmpty()) {
            if (isRtlLanguage) {
                if (abs(top.y - bottom.y) < avgSymHeight) {
                    if (top.x > bottom.x) "${top.translation} ${bottom.translation}" else "${bottom.translation} ${top.translation}"
                } else {
                    "${top.translation} ${bottom.translation}"
                }
            } else {
                if (abs(top.y - bottom.y) < avgSymHeight) {
                    if (top.x < bottom.x) "${top.translation} ${bottom.translation}" else "${bottom.translation} ${top.translation}"
                } else {
                    "${top.translation} ${bottom.translation}"
                }
            }
        } else {
            top.translation + bottom.translation // أحدهما أو كلاهما فارغ
        }

        return TranslationBlock(
            text = combinedText,
            translation = combinedTranslation,
            width = newWidth,
            height = newHeight,
            x = newX,
            y = newY,
            symHeight = avgSymHeight,
            symWidth = avgSymWidth,
            angle = avgAngle,
        )
    }

    private fun getChapterPages(chapterPath: UniFile): List<Pair<String, () -> InputStream>> {
        if (chapterPath.isFile) {
            val reader = chapterPath.archiveReader(context)
            return reader.useEntries { entries ->
                entries.filter { it.isFile && ImageUtil.isImage(it.name) { reader.getInputStream(it.name)!! } }
                    .sortedWith { f1, f2 -> f1.name.compareToCaseInsensitiveNaturalOrder(f2.name) }.map { entry ->
                        Pair(entry.name) { reader.getInputStream(entry.name)!! }
                    }.toList()
            }
        } else {
            return chapterPath.listFiles()!!.filter { ImageUtil.isImage(it.name) }.map { entry ->
                Pair(entry.name!!) { entry.openInputStream() }
            }.toList()
        }
    }

    private fun areAllTranslationsFinished(): Boolean {
        return queueState.value.none { it.status.value <= Translation.State.TRANSLATING.value }
    }

    private fun addToQueue(translation: Translation) {
        translation.status = Translation.State.QUEUE
        _queueState.update {
            it + translation
        }
    }

    private fun removeFromQueue(translation: Translation) {
        _queueState.update {
            if (translation.status == Translation.State.TRANSLATING || translation.status == Translation.State.QUEUE) {
                translation.status = Translation.State.NOT_TRANSLATED
            }
            it - translation
        }
    }

    private inline fun removeFromQueueIf(crossinline predicate: (Translation) -> Boolean) {
        _queueState.update { queue ->
            val translations = queue.filter { predicate(it) }
            translations.forEach { translation ->
                if (translation.status == Translation.State.TRANSLATING ||
                    translation.status == Translation.State.QUEUE
                ) {
                    translation.status = Translation.State.NOT_TRANSLATED
                }
            }
            queue - translations
        }
    }

    fun removeFromQueue(chapter: Chapter) {
        removeFromQueueIf { it.chapter.id == chapter.id }
    }

    fun removeFromQueue(manga: Manga) {
        removeFromQueueIf { it.manga.id == manga.id }
    }

    private fun internalClearQueue() {
        _queueState.update {
            it.forEach { translation ->
                if (translation.status == Translation.State.TRANSLATING ||
                    translation.status == Translation.State.QUEUE
                ) {
                    translation.status = Translation.State.NOT_TRANSLATED
                }
            }
            emptyList()
        }
    }
}
