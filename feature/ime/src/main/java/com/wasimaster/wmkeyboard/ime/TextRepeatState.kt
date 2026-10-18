package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.core.tools.TextRepeat

/** Which of the Text repeat panel's two boxes has the keys. */
enum class TextRepeatField { TEXT, SEPARATOR }

/**
 * The copies the chip found in the field when it opened the tool: what they
 * read as, and how many characters before the cursor they take. Insert
 * checks the field still ends with [text] before it touches anything.
 */
data class TextRepeatRun(val text: String, val length: Int, val count: Int)

/**
 * The Text repeat panel's live state. The options themselves (count, mode,
 * separator, the toggles) are settings, so the panel reopens the way it was
 * left; this holds what belongs to one visit.
 */
data class TextRepeatUi(
    val text: String = "",
    /** The separator typed into the Custom box; read when the setting says CUSTOM. */
    val customSeparator: String = "",
    val focused: TextRepeatField? = null,
    /** Set while the panel was opened from the chip, until Insert has acted on it. */
    val run: TextRepeatRun? = null,
    /** What the last Insert wrote, while the field still ends with it. */
    val lastInsert: String? = null,
    /** Texts repeated before, newest first; empty while recents are off or paused. */
    val recents: List<String> = emptyList(),
    /** Bumped when an Insert lands, for the panel's pulse. */
    val insertTick: Int = 0,
) {
    fun withFocusedText(value: String): TextRepeatUi = when (focused) {
        TextRepeatField.SEPARATOR -> copy(customSeparator = value)
        else -> copy(text = value)
    }
}

/**
 * What the panel would write right now: the box's text under the saved
 * options and the limit. One place, so the preview the panel draws and the
 * text Insert commits can never disagree.
 */
fun KeyboardUiState.textRepeatSpec(): TextRepeat.Spec {
    val options = settings.textRepeat
    return TextRepeat.Spec(
        text = textRepeat.text,
        count = options.count,
        mode = options.mode,
        separator = options.separator,
        customSeparator = textRepeat.customSeparator,
        numbered = options.numbered,
        alternateCase = options.alternateCase,
        maxChars = settings.toolLimits.textRepeatMaxChars,
    )
}

/** What the Text repeat panel's controls ask the service for. */
sealed interface TextRepeatAction {
    data class Focus(val field: TextRepeatField?) : TextRepeatAction
    data class Clear(val field: TextRepeatField) : TextRepeatAction
    data class Count(val value: Int) : TextRepeatAction
    data class Mode(val mode: TextRepeat.Mode) : TextRepeatAction
    data class Separator(val separator: TextRepeat.Separator) : TextRepeatAction
    data class Numbered(val on: Boolean) : TextRepeatAction
    data class AlternateCase(val on: Boolean) : TextRepeatAction
    data object Insert : TextRepeatAction
    data object Copy : TextRepeatAction
    data object Undo : TextRepeatAction
    data class PickRecent(val text: String) : TextRepeatAction
    data object ClearRecents : TextRepeatAction
}
