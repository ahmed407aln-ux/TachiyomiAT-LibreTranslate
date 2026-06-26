package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.translation.data.TranslationFont
import eu.kanade.translation.recognizer.TextRecognizerLanguage
import eu.kanade.translation.translator.TextTranslatorLanguage
import eu.kanade.translation.translator.TextTranslators
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableMap
import tachiyomi.domain.translation.TranslationPreferences
import tachiyomi.i18n.at.ATMR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.codeshipping.llamakotlin.LlamaModel
import java.io.File
import java.io.FileOutputStream
import android.provider.OpenableColumns
import android.net.Uri


object SettingsTranslationScreen : SearchableSettings {
    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = ATMR.strings.pref_category_translations

    @Composable
    override fun getPreferences(): List<Preference> {
        val entries = TranslationFont.entries
        val translationPreferences = remember { Injekt.get<TranslationPreferences>() }
        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                pref = translationPreferences.autoTranslateAfterDownload(),
                title = stringResource(ATMR.strings.pref_translate_after_downloading),
            ),
            Preference.PreferenceItem.ListPreference(
                pref = translationPreferences.translationFont(),
                title = stringResource(ATMR.strings.pref_reader_font),
                entries = entries.withIndex().associate { it.index to it.value.label }.toImmutableMap(),
            ),
            getTranslationLangGroup(translationPreferences),
            getTranslatioEngineGroup(translationPreferences),
            getTranslatioAdvancedGroup(translationPreferences),
        )
    }

    @Composable
    private fun getTranslationLangGroup(
        translationPreferences: TranslationPreferences,
    ): Preference.PreferenceGroup {
        val fromLangs = TextRecognizerLanguage.entries
        val toLangs = TextTranslatorLanguage.entries
        return Preference.PreferenceGroup(
            title = stringResource(ATMR.strings.pref_group_setup),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.translateFromLanguage(),
                    title = stringResource(ATMR.strings.pref_translate_from),
                    entries = fromLangs.associate { it.name to it.label }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.translateToLanguage(),
                    title = stringResource(ATMR.strings.pref_translate_to),
                    entries = toLangs.associate { it.name to it.label }.toImmutableMap(),
                ),
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.textRecognizerType(),
                    title = "OCR Engine",
                    entries = mapOf(
                        1 to "Google ML Kit (default)",
                        2 to "PaddleOCR (bata)",
                        3 to "ML Kit + PaddleOCR (bata)"
                    ).toImmutableMap(),
                )
            ),
        )
    }

    @Composable
    private fun getTranslatioEngineGroup(
        translationPreferences: TranslationPreferences,
    ): Preference.PreferenceGroup {
        val engines = TextTranslators.entries
        return Preference.PreferenceGroup(
            title = stringResource(ATMR.strings.pref_group_engine),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.ListPreference(
                    pref = translationPreferences.translationEngine(),
                    title = stringResource(ATMR.strings.pref_translator_engine),
                    entries = engines.withIndex().associate { it.index to it.value.label }.toImmutableMap(),
                ),

                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationEngineApiKey(),
                    subtitle = stringResource(ATMR.strings.pref_sub_engine_api_key),
                    title = stringResource(ATMR.strings.pref_engine_api_key),
                ),

                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationServerUrl(),
                    title = "Server URL",
                    subtitle = "مثال: http://192.168.1.100",
                ),
            ),
        )
    }

    @Composable
    private fun getTranslatioAdvancedGroup(
        translationPreferences: TranslationPreferences,
    ): Preference.PreferenceGroup {
        val context = LocalContext.current
        var showChatDialog by remember { mutableStateOf(false) }

        val filePickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument()
        ) { uri ->
            uri?.let { selectedUri ->
                try {
                    val takeFlags: Int = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    context.contentResolver.takePersistableUriPermission(selectedUri, takeFlags)
                    translationPreferences.externalGgufModelPath().set(selectedUri.toString())
                } catch (e: Exception) {
                    // تجاهل
                }
            }
        }

        // الطريقة الأكثر أماناً لقراءة حالة التفضيلات المخصصة في هذا التطبيق
        val ggufPathPref = translationPreferences.externalGgufModelPath()
        val ggufPath by ggufPathPref.changes().collectAsState(initial = ggufPathPref.get())

        // 1. استدعاء النافذة (يجب أن يكون هنا قبل return لكي يظهر فوق الشاشة)
        if (showChatDialog) {
            LlmTestChatDialog(
                modelPath = ggufPath,
                onDismiss = { showChatDialog = false }
            )
        }

        return Preference.PreferenceGroup(
            title = stringResource(ATMR.strings.pref_group_advanced),
            preferenceItems = persistentListOf(
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationEngineModel(),
                    title = stringResource(ATMR.strings.pref_engine_model),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationEngineTemperature(),
                    title = stringResource(ATMR.strings.pref_engine_temperature),
                ),
                Preference.PreferenceItem.EditTextPreference(
                    pref = translationPreferences.translationEngineMaxOutputTokens(),
                    title = stringResource(ATMR.strings.pref_engine_max_output),
                ),
                // زر اختيار الملف
                Preference.PreferenceItem.TextPreference(
                    title = "GGUF model external path",
                    subtitle = if (ggufPath.isEmpty()) "The model file has not yet been selected." else ggufPath,
                    onClick = {
                        filePickerLauncher.launch(arrayOf("*/*"))
                    }
                ),
                // 2. الزر الجديد الخاص بتجربة المحادثة
                Preference.PreferenceItem.TextPreference(
                    title = "chatting with the model",
                    subtitle = "Open a window for a live test",
                    onClick = { showChatDialog = true }
                ),
            ),
        )
    }
}
@Composable
fun LlmTestChatDialog(
    modelPath: String,
    onDismiss: () -> Unit
) {
    var inputText by remember { mutableStateOf("") }
    var outputText by remember { mutableStateOf("The model's response will appear here...") }
    var isGenerating by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = { if (!isGenerating) onDismiss() },
        title = { Text("Engine test") },
        text = {
            Column {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    label = { Text("Enter your message (Prompt)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(16.dp))
                Column(modifier = Modifier.height(200.dp).verticalScroll(rememberScrollState())) {
                    Text(text = outputText)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (modelPath.isBlank()) {
                        outputText = "Please select the model file first from the settings!"
                        return@Button
                    }
                    isGenerating = true
                    outputText = "Loading the model (may take a few seconds)..."

                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val uri = Uri.parse(modelPath)
                            val realPath = getRealPathForChat(context, uri)

                            if (realPath == null || !File(realPath).exists()) {
                                outputText = "Error: The actual file was not found.."
                                isGenerating = false
                                return@launch
                            }

                            // تحميل المودل
                            val model = LlamaModel.load(realPath) {
                                contextSize = 2048
                                threads = 4
                            }

                            outputText = "In progress...\n"

                            // صيغة جيما الصحيحة
                            val prompt = "<start_of_turn>user\n$inputText<end_of_turn>\n<start_of_turn>model\n"

                            model.generateStream(prompt).collect { token ->
                                withContext(Dispatchers.Main) {
                                    outputText += token
                                }
                            }

                            model.close() // تفريغ الذاكرة بعد الرد
                            isGenerating = false

                        } catch (e: Exception) {
                            outputText = "A software error occurred:${e.message}"
                            isGenerating = false
                        }
                    }
                },
                enabled = !isGenerating && inputText.isNotBlank()
            ) {
                Text(if (isGenerating) "Processing in progress..." else "send")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isGenerating) {
                Text("closing")
            }
        }
    )
}

// دالة مساعدة لاستخراج مسار الملف للدردشة
fun getRealPathForChat(context: android.content.Context, uri: Uri): String? {
    if (uri.scheme == "file") return uri.path
    val cursor = context.contentResolver.query(uri, null, null, null, null)
    val nameIndex = cursor?.getColumnIndex(OpenableColumns.DISPLAY_NAME)
    cursor?.moveToFirst()
    val name = cursor?.getString(nameIndex ?: 0) ?: "test_model.gguf"
    cursor?.close()
    val file = File(context.cacheDir, name)
    if (!file.exists()) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val outputStream = FileOutputStream(file)
            inputStream?.copyTo(outputStream)
            inputStream?.close()
            outputStream.close()
        } catch (e: Exception) { return null }
    }
    return file.absolutePath
}
