package com.wasimaster.wmkeyboard.app

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.common.R as CommonR
import com.wasimaster.wmkeyboard.core.script.FancyStyles
import com.wasimaster.wmkeyboard.core.settings.CustomFancyFont
import com.wasimaster.wmkeyboard.core.settings.MAX_CUSTOM_FANCY_FONTS
import com.wasimaster.wmkeyboard.core.settings.MAX_FANCY_ALPHABET_LENGTH
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.core.settings.toFancyStyle
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Fancy Text › Your fonts: Fancy Text styles the user makes by pasting an
 * alphabet, for the Unicode "fonts" the built-in table leaves out (the ones
 * fancy-text websites assemble from look-alike symbols). Drawn on the Fancy
 * Text language page under the style picker.
 *
 * Each font writes [com.wasimaster.wmkeyboard.core.settings.LayoutBehaviorSettings.customFancyFonts];
 * the settings read installs it into `FancyStyles`, so it joins the strip
 * over the keys, the Style row above and the selection actions the moment it
 * is saved.
 */
@Composable
internal fun CustomFancyFontsGroup(repository: SettingsRepository, settings: LiveSettings) {
    val scope = rememberCoroutineScope()
    val fonts = settings.watch { it.layoutBehavior.customFancyFonts }
    // null = no dialog; a font with an empty id = adding a new one.
    var editing by remember { mutableStateOf<CustomFancyFont?>(null) }

    SettingsGroup(
        stringResource(R.string.languages_fancy_fonts_group),
        info = stringResource(R.string.languages_fancy_fonts_info),
    ) {
        item {
            ActionRow(
                title = R.string.languages_fancy_fonts_add_title,
                subtitle = stringResource(
                    if (fonts.size < MAX_CUSTOM_FANCY_FONTS) {
                        R.string.languages_fancy_fonts_add_subtitle
                    } else {
                        R.string.languages_fancy_fonts_full
                    },
                ),
                action = stringResource(CommonR.string.common_add),
                enabled = fonts.size < MAX_CUSTOM_FANCY_FONTS,
            ) { editing = CustomFancyFont(id = "", name = "", lower = "") }
        }
        item(visible = fonts.isEmpty()) { CaptionText(stringResource(R.string.languages_fancy_fonts_empty)) }
        for (font in fonts) item(key = font.id) {
            val style = remember(font) { font.toFancyStyle() }
            WmRow(
                title = font.name,
                subtitle = FancyStyles.transform(PREVIEW_TEXT, style),
                swipeActions = listOf(
                    deleteSwipeAction(stringResource(R.string.languages_fancy_fonts_delete_desc, font.name)) {
                        scope.launch { repository.editCustomFancyFonts { list -> list.filterNot { it.id == font.id } } }
                    },
                ),
            ) { editing = font }
        }
    }

    editing?.let { start ->
        CustomFancyFontDialog(
            initial = start,
            onDismiss = { editing = null },
            onDelete = if (start.id.isEmpty()) {
                null
            } else {
                {
                    editing = null
                    scope.launch { repository.editCustomFancyFonts { list -> list.filterNot { it.id == start.id } } }
                }
            },
        ) { saved ->
            editing = null
            val added = start.id.isEmpty()
            val font = if (added) saved.copy(id = FancyStyles.CUSTOM_PREFIX + UUID.randomUUID().toString().take(ID_LENGTH)) else saved
            scope.launch {
                repository.editCustomFancyFonts { list ->
                    if (added) list + font else list.map { if (it.id == font.id) font else it }
                }
                // A font just made is a font the user wants to type in.
                if (added) repository.setFancyStyle(font.id)
            }
        }
    }
}

/** One font's name, alphabets and direction: the one form for adding and changing. */
@Composable
private fun CustomFancyFontDialog(
    initial: CustomFancyFont,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (CustomFancyFont) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var lower by remember { mutableStateOf(initial.lower) }
    var upper by remember { mutableStateOf(initial.upper) }
    var digits by remember { mutableStateOf(initial.digits) }
    var reversed by remember { mutableStateOf(initial.reversed) }
    val draft = CustomFancyFont(initial.id, name.trim(), lower.trim(), upper.trim(), digits.trim(), reversed)
    val style = remember(draft) { draft.toFancyStyle() }
    val lowerCount = remember(lower) { FancyStyles.splitAlphabet(lower).size }
    val upperCount = remember(upper) { FancyStyles.splitAlphabet(upper).size }
    val digitCount = remember(digits) { FancyStyles.splitAlphabet(digits).size }
    val valid = draft.name.isNotEmpty() && lowerCount in 1..LETTERS && upperCount <= LETTERS && digitCount <= DIGITS

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initial.id.isEmpty()) R.string.languages_fancy_fonts_add_title else R.string.languages_fancy_fonts_edit_title,
                ),
            )
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                if (lowerCount > 0) {
                    Text(
                        FancyStyles.transform(PREVIEW_TEXT, style),
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.replace("\n", " ").take(MAX_NAME_LENGTH) },
                    label = { Text(stringResource(R.string.languages_fancy_fonts_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                AlphabetField(
                    value = lower,
                    count = lowerCount,
                    max = LETTERS,
                    label = R.string.languages_fancy_fonts_lower_label,
                    hint = R.string.languages_fancy_fonts_lower_hint,
                ) { pasted ->
                    // A whole a–z A–Z 0–9 run pasted into the first field is
                    // the common case (that is how fancy-text sites show a
                    // font), so it is shared out over the three fields.
                    val split = if (upper.isBlank() && digits.isBlank()) splitRun(pasted) else null
                    if (split != null) {
                        lower = split.first
                        upper = split.second
                        digits = split.third
                    } else {
                        lower = pasted
                    }
                }
                AlphabetField(
                    value = upper,
                    count = upperCount,
                    max = LETTERS,
                    label = R.string.languages_fancy_fonts_upper_label,
                    hint = R.string.languages_fancy_fonts_upper_hint,
                ) { upper = it }
                AlphabetField(
                    value = digits,
                    count = digitCount,
                    max = DIGITS,
                    label = R.string.languages_fancy_fonts_digits_label,
                    hint = R.string.languages_fancy_fonts_digits_hint,
                ) { digits = it }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(
                            stringResource(R.string.languages_fancy_fonts_reversed_title),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(R.string.languages_fancy_fonts_reversed_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = reversed, onCheckedChange = { reversed = it })
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete) {
                        Text(stringResource(CommonR.string.common_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSave(draft) }) {
                Text(stringResource(CommonR.string.common_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(CommonR.string.common_cancel)) } },
    )
}

/** One alphabet: the pasted forms, how many were read and how many fit. */
@Composable
private fun AlphabetField(
    value: String,
    count: Int,
    max: Int,
    @StringRes label: Int,
    @StringRes hint: Int,
    onChange: (String) -> Unit,
) {
    val over = count > max
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.replace('\n', ' ').take(MAX_FANCY_ALPHABET_LENGTH)) },
        label = { Text(stringResource(label)) },
        supportingText = {
            Text(
                if (over) {
                    stringResource(R.string.languages_fancy_fonts_too_many, count, max)
                } else {
                    stringResource(hint, count)
                },
            )
        },
        isError = over,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * [text] shared out as small letters, capitals and digits when it holds
 * exactly 52 or 62 forms (a–z A–Z, with or without 0–9), or null for
 * anything else. A spaced list stays a spaced list in each field.
 */
private fun splitRun(text: String): Triple<String, String, String>? {
    val forms = FancyStyles.splitAlphabet(text)
    if (forms.size != LETTERS * 2 && forms.size != LETTERS * 2 + DIGITS) return null
    val glue = if (text.trim().any { it.isWhitespace() }) " " else ""
    return Triple(
        forms.subList(0, LETTERS).joinToString(glue),
        forms.subList(LETTERS, LETTERS * 2).joinToString(glue),
        forms.drop(LETTERS * 2).joinToString(glue),
    )
}

private const val LETTERS = 26
private const val DIGITS = 10
private const val MAX_NAME_LENGTH = 40
private const val ID_LENGTH = 8

/** Every letter both ways round and every digit, so a preview shows the whole font. */
private const val PREVIEW_TEXT = "The quick brown fox jumps over the lazy dog 0123456789"
