package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.net.Uri
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.AutoAwesome
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Delete
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.Download
import com.wasimaster.wmkeyboard.core.icons.symbols.outlined.FileOpen
import com.wasimaster.wmkeyboard.core.imagegen.ClipTokenizer
import com.wasimaster.wmkeyboard.core.imagegen.ImageGen
import com.wasimaster.wmkeyboard.core.imagegen.ImageGenDownloads
import com.wasimaster.wmkeyboard.core.imagegen.ImageGenEngine
import com.wasimaster.wmkeyboard.core.imagegen.ImageGenEvent
import com.wasimaster.wmkeyboard.core.imagegen.ImageGenImport
import com.wasimaster.wmkeyboard.core.imagegen.ImageGenModels
import com.wasimaster.wmkeyboard.core.imagegen.ImageGenRequest
import com.wasimaster.wmkeyboard.core.imagegen.ImageRuntime
import com.wasimaster.wmkeyboard.core.imagegen.ModelSpec
import com.wasimaster.wmkeyboard.core.imagegen.PromptSafety
import com.wasimaster.wmkeyboard.core.modules.NativeConsent
import com.wasimaster.wmkeyboard.core.stickers.StickerPackStore
import com.wasimaster.wmkeyboard.core.stickers.SubjectCutout
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings route of the sticker generator; `?pack=` names the pack its stickers go to. */
internal const val STICKER_GENERATOR_ROUTE = "sticker_generator"

/**
 * Type a prompt, get stickers: the model runs on this phone (its own process,
 * see ImageGenService), the prompt is translated to English when it is not,
 * and a picked result goes through the background remover into the sticker
 * editor, from where it is saved into a pack like any sticker.
 */
@Composable
internal fun StickerGeneratorScreen(settings: LiveSettings, packId: String?, onNavigate: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val states by ImageGenDownloads.states.collectAsState()
    var imported by remember { mutableStateOf(ImageGenImport.installed(context)) }
    LaunchedEffect(Unit) { ImageGenDownloads.refresh(context) }

    val catalog = remember { ImageGenModels.Catalog.entries.filter { it.runsHere } }
    var modelId by remember { mutableStateOf(prefs.getString(KEY_MODEL, null) ?: ImageGenModels.Catalog.SDXS.id) }
    val selectedCatalog = ImageGenModels.Catalog.byId(modelId)
    val modelReady = when {
        selectedCatalog != null -> states[modelId] == ImageGenDownloads.Status.Downloaded
        else -> imported.any { it.id == modelId }
    }
    var spec by remember { mutableStateOf<ModelSpec?>(null) }
    var tokenizer by remember { mutableStateOf<ClipTokenizer?>(null) }
    LaunchedEffect(modelId, modelReady) {
        spec = null
        tokenizer = null
        if (!modelReady) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val loaded = loadSpec(context, modelId)
            spec = loaded?.first
            tokenizer = loaded?.second
        }
    }

    var prompt by remember { mutableStateOf("") }
    var style by remember { mutableStateOf(StickerPrompt.Style.byId(prefs.getString(KEY_STYLE, null))) }
    var english by remember { mutableStateOf<StickerPrompt.English?>(null) }
    var englishFor by remember { mutableStateOf("") }
    var negative by remember { mutableStateOf("") }
    var size by remember { mutableIntStateOf(prefs.getInt(KEY_SIZE, 0)) }
    var variants by remember { mutableIntStateOf(prefs.getInt(KEY_VARIANTS, 0)) }
    var steps by remember { mutableIntStateOf(0) }
    var guidance by remember { mutableStateOf(Float.NaN) }
    var seedLocked by remember { mutableStateOf(false) }
    var seed by remember { mutableStateOf(Random.nextLong(0, MAX_SEED)) }
    var safety by remember { mutableStateOf(prefs.getBoolean(KEY_SAFETY, true)) }
    var removeBackground by remember { mutableStateOf(prefs.getBoolean(KEY_CUTOUT, true)) }

    var job by remember { mutableStateOf<Job?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var message by remember { mutableStateOf<String?>(null) }
    val results = remember { mutableStateListOf<Result>() }
    var rewriting by remember { mutableStateOf(false) }
    var consentFor by remember { mutableStateOf<ImageGenModels.Catalog?>(null) }
    var importing by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<Result?>(null) }
    var savingPick by remember { mutableStateOf(false) }

    // Generated files are scratch: gone when the screen goes.
    DisposableEffect(Unit) {
        onDispose {
            job?.cancel()
            File(context.cacheDir, ImageGenEngine.OUTPUT_DIR).deleteRecursively()
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        importing = true
        scope.launch {
            val result = withContext(Dispatchers.IO) { ImageGenImport.import(context, uris) }
            importing = false
            message = when (result) {
                is ImageGenImport.Result.Ok -> {
                    imported = ImageGenImport.installed(context)
                    modelId = result.model.id
                    prefs.edit().putString(KEY_MODEL, modelId).apply()
                    context.getString(R.string.stickergen_import_ok, result.model.name)
                }
                is ImageGenImport.Result.Missing -> context.getString(R.string.stickergen_import_missing, result.files.joinToString(", "))
                is ImageGenImport.Result.Invalid -> context.getString(R.string.stickergen_import_invalid, result.reason)
                is ImageGenImport.Result.Failed -> context.getString(R.string.stickergen_import_failed, result.message)
            }
        }
    }

    fun generate() {
        val current = spec ?: return
        val typed = prompt.trim()
        if (typed.isEmpty()) return
        PromptSafety.blocked(typed)?.let {
            message = context.getString(R.string.stickergen_prompt_blocked)
            return
        }
        results.clear()
        message = null
        job?.cancel()
        val launched = scope.launch {
            status = context.getString(R.string.stickergen_status_translating)
            val en = if (englishFor == typed && english != null) english!! else StickerPrompt.toEnglish(context, settings.value, typed)
            english = en
            englishFor = typed
            if (PromptSafety.blocked(en.text) != null) {
                status = null
                message = context.getString(R.string.stickergen_prompt_blocked)
                return@launch
            }
            if (en.failed) message = context.getString(R.string.stickergen_translate_failed)
            if (!seedLocked) seed = Random.nextLong(0, MAX_SEED)
            val count = variants.takeIf { it in 1..ImageGenEngine.MAX_VARIANTS }
                ?: ImageGenEngine.defaultVariants(context, current.runtime)
            val request = ImageGenRequest(
                modelId = modelId,
                prompt = en.text + style.suffix,
                negative = negative.trim(),
                size = size.takeIf { it in current.sizes } ?: current.sizes.min(),
                steps = steps,
                guidance = guidance,
                seed = seed,
                variants = count,
                safetyFilter = safety,
            )
            repeat(count) { results.add(Result.Pending) }
            status = context.getString(R.string.stickergen_status_starting)
            ImageGen.generate(context, request).collect { event ->
                when (event) {
                    ImageGenEvent.Loading -> status = context.getString(R.string.stickergen_status_loading)
                    is ImageGenEvent.Progress -> {
                        progress = (event.variant + event.step.toFloat() / event.steps) / count
                        status = context.getString(R.string.stickergen_status_drawing, event.variant + 1, count)
                        val preview = event.preview?.let { withContext(Dispatchers.IO) { decode(it) } }
                        if (preview != null && event.variant in results.indices) results[event.variant] = Result.Preview(preview)
                    }
                    is ImageGenEvent.Image -> {
                        val image = withContext(Dispatchers.IO) { decode(event.file) }
                        if (image != null && event.variant in results.indices) {
                            results[event.variant] = Result.Done(image, event.file, event.seed)
                        }
                        progress = (event.variant + 1f) / count
                    }
                    is ImageGenEvent.Blocked -> {
                        if (event.variant == ImageGenEngine.PROMPT) {
                            message = context.getString(R.string.stickergen_prompt_blocked)
                            results.clear()
                        } else if (event.variant in results.indices) {
                            results[event.variant] = Result.Blocked
                        }
                    }
                    is ImageGenEvent.Failed -> message = context.getString(R.string.stickergen_failed, event.message)
                    ImageGenEvent.Done -> Unit
                }
            }
            status = null
            progress = 0f
            // Places nothing ever filled (a cancel, a failure) go.
            results.removeAll { it == Result.Pending }
        }
        job = launched
        // Only the run that is still the current one clears it: a cancelled
        // run can finish after the next one started.
        launched.invokeOnCompletion { if (job === launched) job = null }
    }

    // --- Model ---------------------------------------------------------------
    SettingsGroup(
        stringResource(R.string.stickergen_model_title),
        info = stringResource(R.string.stickergen_model_info),
    ) {
        for (model in catalog) {
            item(key = model.id) {
                val state = states[model.id] ?: ImageGenDownloads.Status.NotDownloaded
                ModelRow(
                    title = model.displayName,
                    subtitle = modelSubtitle(context, model, state),
                    selected = modelId == model.id,
                    downloaded = state == ImageGenDownloads.Status.Downloaded,
                    downloading = state is ImageGenDownloads.Status.Downloading,
                    onSelect = {
                        modelId = model.id
                        prefs.edit().putString(KEY_MODEL, model.id).apply()
                        if (state != ImageGenDownloads.Status.Downloaded && state !is ImageGenDownloads.Status.Downloading) {
                            consentFor = model
                        }
                    },
                    onDownload = { consentFor = model },
                    onCancel = { ImageGenDownloads.cancel(model) },
                    onDelete = {
                        ImageGenDownloads.delete(context, model)
                        ImageGenDownloads.refresh(context)
                    },
                )
            }
        }
        for (model in imported) {
            item(key = model.id) {
                ModelRow(
                    title = model.name,
                    subtitle = stringResource(R.string.stickergen_model_imported, Formatter.formatShortFileSize(context, model.bytes)),
                    selected = modelId == model.id,
                    downloaded = true,
                    downloading = false,
                    onSelect = {
                        modelId = model.id
                        prefs.edit().putString(KEY_MODEL, model.id).apply()
                    },
                    onDownload = {},
                    onCancel = {},
                    onDelete = {
                        ImageGenImport.delete(context, model.id)
                        imported = ImageGenImport.installed(context)
                    },
                )
            }
        }
        item {
            WmRow(
                title = stringResource(if (importing) R.string.stickergen_importing else R.string.stickergen_import_title),
                subtitle = stringResource(R.string.stickergen_import_subtitle),
                icon = Icons.Outlined.FileOpen,
                enabled = !importing,
                onClick = { importLauncher.launch(arrayOf("*/*")) },
            )
        }
    }

    // --- Prompt --------------------------------------------------------------
    SettingsGroup(stringResource(R.string.stickergen_prompt_title)) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = { prompt = it },
                    label = { Text(stringResource(R.string.stickergen_prompt_label)) },
                    placeholder = { Text(stringResource(R.string.stickergen_prompt_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    maxLines = 5,
                )
                // Counted on what the model will read: the English, with the style.
                val counted = remember(prompt, english, englishFor, style, tokenizer) {
                    val t = tokenizer ?: return@remember null
                    val text = (if (englishFor == prompt.trim()) english?.text else null) ?: prompt.trim()
                    if (text.isEmpty()) null else t.prompt(text + style.suffix)
                }
                if (counted != null) {
                    CaptionText(
                        stringResource(R.string.stickergen_tokens, counted.used, ClipTokenizer.MAX_TOKENS - 2),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    if (counted.ignored.isNotEmpty()) {
                        CaptionText(stringResource(R.string.stickergen_tokens_ignored, counted.ignored), error = true)
                    }
                }
                val en = english
                if (en != null && englishFor == prompt.trim() && en.source.isNotEmpty()) {
                    OutlinedTextField(
                        value = en.text,
                        onValueChange = { english = en.copy(text = it) },
                        label = { Text(stringResource(R.string.stickergen_english_label, en.source)) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        maxLines = 4,
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for (s in StickerPrompt.Style.entries) {
                        FilterChip(
                            selected = style == s,
                            onClick = {
                                style = s
                                prefs.edit().putString(KEY_STYLE, s.id).apply()
                            },
                            label = { Text(stringResource(s.label)) },
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (job == null) {
                        Button(
                            onClick = { generate() },
                            enabled = spec != null && prompt.isNotBlank(),
                        ) { Text(stringResource(R.string.stickergen_generate)) }
                    } else {
                        OutlinedButton(onClick = { job?.cancel() }) { Text(stringResource(CommonR.string.common_cancel)) }
                    }
                    if (StickerPrompt.canRewrite(settings.value)) {
                        TextButton(
                            enabled = !rewriting && job == null && prompt.isNotBlank(),
                            onClick = {
                                rewriting = true
                                scope.launch {
                                    val better = withContext(Dispatchers.IO) { StickerPrompt.rewrite(settings.value, prompt) }
                                    rewriting = false
                                    if (better == null || better.equals("no", ignoreCase = true)) {
                                        message = context.getString(R.string.stickergen_rewrite_failed)
                                    } else {
                                        prompt = better
                                    }
                                }
                            },
                        ) { Text(stringResource(if (rewriting) R.string.stickergen_rewriting else R.string.stickergen_rewrite)) }
                    }
                }
                if (spec == null && !modelReady) {
                    CaptionText(stringResource(R.string.stickergen_need_model), modifier = Modifier.padding(top = 4.dp))
                }
                status?.let {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                    )
                    CaptionText(it, modifier = Modifier.padding(top = 4.dp))
                }
                message?.let { CaptionText(it, modifier = Modifier.padding(top = 4.dp), error = true) }
            }
        }
    }

    // --- Results -------------------------------------------------------------
    if (results.isNotEmpty()) {
        SettingsGroup(stringResource(R.string.stickergen_results_title)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    for (row in results.chunked(2)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                            for (r in row) {
                                ResultCell(r, Modifier.weight(1f)) { if (r is Result.Done) picked = r }
                            }
                            if (row.size == 1) Box(Modifier.weight(1f))
                        }
                    }
                    CaptionText(stringResource(R.string.stickergen_results_hint))
                }
            }
        }
    }

    // --- Advanced ------------------------------------------------------------
    SettingsGroup(stringResource(R.string.stickergen_advanced_title), foldKey = "advanced") {
        val current = spec
        if (current != null && current.sizes.size > 1) {
            item {
                ChoiceChips(
                    title = stringResource(R.string.stickergen_size_title),
                    options = current.sizes.map { it to "$it × $it" },
                    selected = size.takeIf { it in current.sizes } ?: current.sizes.min(),
                ) {
                    size = it
                    prefs.edit().putInt(KEY_SIZE, it).apply()
                }
            }
        }
        item {
            val auto = ImageGenEngine.defaultVariants(context, current?.runtime ?: ImageRuntime.MNN)
            ChoiceChips(
                title = stringResource(R.string.stickergen_variants_title),
                options = listOf(0 to stringResource(R.string.stickergen_variants_auto, auto)) +
                    (1..ImageGenEngine.MAX_VARIANTS).map { it to "$it" },
                selected = variants,
            ) {
                variants = it
                prefs.edit().putInt(KEY_VARIANTS, it).apply()
            }
        }
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = seed.toString(),
                    onValueChange = { v -> v.toLongOrNull()?.let { seed = it.coerceIn(0, MAX_SEED) } },
                    label = { Text(stringResource(R.string.stickergen_seed_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            ToggleSetting(
                title = stringResource(R.string.stickergen_seed_lock_title),
                subtitle = stringResource(R.string.stickergen_seed_lock_subtitle),
                checked = seedLocked,
            ) { seedLocked = it }
        }
        if (current != null && current.adjustableSteps) {
            item {
                ChoiceChips(
                    title = stringResource(R.string.stickergen_steps_title),
                    options = listOf(0 to stringResource(R.string.stickergen_default_value, current.steps)) +
                        listOf(4, 8, 12, 20, 30).map { it to "$it" },
                    selected = steps,
                ) { steps = it }
            }
        }
        if (current != null && current.takesGuidance) {
            item {
                ChoiceChips(
                    title = stringResource(R.string.stickergen_guidance_title),
                    options = listOf(0 to stringResource(R.string.stickergen_default_value_float, current.guidance)) +
                        listOf(3, 5, 7, 9, 12).map { it to "$it" },
                    selected = if (guidance.isNaN()) 0 else guidance.toInt(),
                ) { guidance = if (it == 0) Float.NaN else it.toFloat() }
            }
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedTextField(
                        value = negative,
                        onValueChange = { negative = it },
                        label = { Text(stringResource(R.string.stickergen_negative_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 3,
                    )
                }
            }
        } else if (current != null) {
            item {
                CaptionText(
                    stringResource(R.string.stickergen_no_guidance),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
        item {
            ToggleSetting(
                title = stringResource(R.string.stickergen_cutout_title),
                subtitle = stringResource(
                    if (SubjectCutout.supported) R.string.stickergen_cutout_subtitle else R.string.stickergen_cutout_unsupported,
                ),
                checked = removeBackground && SubjectCutout.supported,
                enabled = SubjectCutout.supported,
            ) {
                removeBackground = it
                prefs.edit().putBoolean(KEY_CUTOUT, it).apply()
            }
        }
        item {
            ToggleSetting(
                title = stringResource(R.string.stickergen_safety_title),
                subtitle = stringResource(R.string.stickergen_safety_subtitle),
                checked = safety,
            ) {
                safety = it
                prefs.edit().putBoolean(KEY_SAFETY, it).apply()
            }
        }
    }

    consentFor?.let { model ->
        LicenceDialog(model) { accepted ->
            consentFor = null
            if (accepted) ImageGenDownloads.start(context, model)
        }
    }

    picked?.let { result ->
        val done = result as Result.Done
        PickDialog(
            image = done.image,
            busy = savingPick,
            onDismiss = { if (!savingPick) picked = null },
            onUse = {
                savingPick = true
                scope.launch {
                    val target = withContext(Dispatchers.IO) { targetPack(context, packId, prefs.getString(KEY_PACK, null)) }
                    val bytes = withContext(Dispatchers.IO) {
                        stickerBytes(context, done.file, removeBackground && SubjectCutout.supported)
                    }
                    savingPick = false
                    picked = null
                    if (target == null || bytes == null) {
                        message = context.getString(R.string.stickergen_save_failed)
                        return@launch
                    }
                    prefs.edit().putString(KEY_PACK, target).apply()
                    StickerEditHandoff.current = StickerEditRequest(target, bytes, stickerId = null)
                    onNavigate(STICKER_EDITOR_ROUTE)
                }
            },
        )
    }
}

/** One cell of the results grid. */
private sealed interface Result {
    data object Pending : Result
    data class Preview(val image: ImageBitmap) : Result
    data class Done(val image: ImageBitmap, val file: File, val seed: Long) : Result
    data object Blocked : Result
}

@Composable
private fun ResultCell(result: Result, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(enabled = result is Result.Done, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        when (result) {
            Result.Pending -> Text(stringResource(R.string.stickergen_cell_waiting), style = MaterialTheme.typography.bodySmall)
            Result.Blocked -> Text(
                stringResource(R.string.stickergen_cell_blocked),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp),
            )
            is Result.Preview -> Image(result.image, contentDescription = null, alpha = PREVIEW_ALPHA)
            is Result.Done -> Image(result.image, contentDescription = stringResource(R.string.stickergen_cell_desc))
        }
    }
}

@Composable
private fun ModelRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    downloaded: Boolean,
    downloading: Boolean,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    WmRow(
        title = title,
        subtitle = subtitle,
        leading = { RadioButton(selected = selected, onClick = onSelect) },
        onClick = onSelect,
        trailing = {
            when {
                downloading -> TextButton(onClick = onCancel) { Text(stringResource(CommonR.string.common_cancel)) }
                downloaded -> IconButton(onClick = onDelete) {
                    Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.stickergen_model_delete, title))
                }
                else -> IconButton(onClick = onDownload) {
                    Icon(Icons.Outlined.Download, contentDescription = stringResource(R.string.stickergen_model_download, title))
                }
            }
        },
    )
}

@Composable
private fun ChoiceChips(title: String, options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((value, label) in options) {
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
            }
        }
    }
}

/** The model licence (and Qualcomm's runtime licence for the NPU build) before the phone fetches anything. */
@Composable
private fun LicenceDialog(model: ImageGenModels.Catalog, onDone: (Boolean) -> Unit) {
    val context = LocalContext.current
    val npu = model.runtime == ImageRuntime.QNN
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Text(stringResource(R.string.stickergen_licence_title, model.displayName)) },
        text = {
            Text(
                stringResource(
                    if (npu) R.string.stickergen_licence_body_npu else R.string.stickergen_licence_body,
                    Formatter.formatShortFileSize(context, model.downloadBytes() + ImageGenModels.SAFETY.bytes),
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = {
                NativeConsent.set(model.licence, true)
                if (npu) NativeConsent.set(NativeConsent.QUALCOMM, true)
                onDone(true)
            }) { Text(stringResource(R.string.stickergen_licence_accept)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = {
                    NativeConsent.licenceUrls[model.licence]?.let { url ->
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }) { Text(stringResource(R.string.stickergen_licence_view)) }
                TextButton(onClick = { onDone(false) }) { Text(stringResource(CommonR.string.common_cancel)) }
            }
        },
    )
}

@Composable
private fun PickDialog(image: ImageBitmap, busy: Boolean, onDismiss: () -> Unit, onUse: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.stickergen_pick_title)) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Image(image, contentDescription = null, modifier = Modifier.size(200.dp).clip(RoundedCornerShape(12.dp)))
                if (busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    CaptionText(stringResource(R.string.stickergen_pick_busy), modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onUse) { Text(stringResource(R.string.stickergen_pick_use)) } },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

private fun modelSubtitle(context: Context, model: ImageGenModels.Catalog, state: ImageGenDownloads.Status): String {
    val size = Formatter.formatShortFileSize(context, model.downloadBytes())
    val what = context.getString(
        when (model) {
            ImageGenModels.Catalog.SDXS -> R.string.stickergen_model_sdxs
            ImageGenModels.Catalog.SD15_NPU -> R.string.stickergen_model_npu
        },
    )
    return when (state) {
        ImageGenDownloads.Status.Downloaded -> context.getString(R.string.stickergen_model_ready, what)
        is ImageGenDownloads.Status.Downloading -> context.getString(
            R.string.stickergen_model_downloading,
            Formatter.formatShortFileSize(context, state.bytes),
            Formatter.formatShortFileSize(context, state.total),
        )
        is ImageGenDownloads.Status.Failed -> context.getString(state.messageRes, state.messageArg)
        ImageGenDownloads.Status.NotDownloaded -> context.getString(R.string.stickergen_model_not_downloaded, what, size)
    }
}

/** The model's spec and tokenizer, read off disk. */
private fun loadSpec(context: Context, modelId: String): Pair<ModelSpec, ClipTokenizer>? = runCatching {
    val noBackup = context.noBackupFilesDir
    val catalog = ImageGenModels.Catalog.byId(modelId)
    if (catalog?.runtime == ImageRuntime.QNN) {
        val merges = ImageGenModels.file(noBackup, catalog, catalog.parts.first())
        return@runCatching ImageGenModels.npuSpec() to ClipTokenizer.fromFile(merges)
    }
    val dir = if (catalog != null) ImageGenModels.dir(noBackup, catalog) else File(ImageGenModels.importedRoot(noBackup), modelId)
    val files = dir.listFiles().orEmpty()
    val json = files.firstOrNull { it.name.endsWith("wmimage.json") }?.readText() ?: return@runCatching null
    val merges = files.firstOrNull { it.name.endsWith("merges.txt") } ?: return@runCatching null
    val spec = ModelSpec.parse(modelId, json) ?: return@runCatching null
    spec to ClipTokenizer.fromFile(merges)
}.getOrNull()

/**
 * The pack a picked sticker goes to: the route's, else the last one used,
 * else the first, else a new "Generated" pack.
 */
private fun targetPack(context: Context, routePack: String?, lastPack: String?): String? {
    val store = StickerPackStore.get(context)
    val packs = store.packs()
    routePack?.takeIf { id -> packs.any { it.id == id } }?.let { return it }
    lastPack?.takeIf { id -> packs.any { it.id == id } }?.let { return it }
    packs.firstOrNull()?.let { return it.id }
    return store.createPack(context.getString(R.string.stickergen_pack_name), System.currentTimeMillis())?.id
}

/** The picked picture as PNG bytes, its background removed when asked and possible. */
private suspend fun stickerBytes(context: Context, file: File, cutout: Boolean): ByteArray? {
    val bitmap = BitmapFactory.decodeFile(file.path) ?: return null
    var out = bitmap
    if (cutout && SubjectCutout.ensureModel(context)) {
        val result = SubjectCutout.cutOut(context, bitmap)
        if (result is SubjectCutout.Result.Ok) {
            out = bitmap.copy(Bitmap.Config.ARGB_8888, true)
            Canvas(out).drawBitmap(result.alpha, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
            result.alpha.recycle()
        }
    }
    return ByteArrayOutputStream().use { stream ->
        out.compress(Bitmap.CompressFormat.PNG, 100, stream)
        stream.toByteArray()
    }
}

private fun decode(file: File): ImageBitmap? = runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull()

private const val PREFS = "sticker_generator"
private const val KEY_MODEL = "model"
private const val KEY_STYLE = "style"
private const val KEY_SIZE = "size"
private const val KEY_VARIANTS = "variants"
private const val KEY_SAFETY = "safety"
private const val KEY_CUTOUT = "cutout"
private const val KEY_PACK = "pack"
private const val MAX_SEED = 2_147_483_647L
private const val PREVIEW_ALPHA = 0.6f
