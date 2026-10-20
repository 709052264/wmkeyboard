package com.wasimaster.wmkeyboard.app.assistant

import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository

/**
 * The settings the Settings assistant (#788) may change, and nothing else.
 *
 * Search hands the assistant every row there is, by title and place, so it can
 * explain and open any of them. Changing one is a narrower promise: a setting
 * is on this list only when it has a value the assistant can read, a type it
 * can check a proposal against, and a setter in [SettingsRepository] that the
 * settings screen itself calls. A raw preference write or a backup import
 * would skip the setter's own rules, so neither is a way in.
 *
 * Deliberately small, and deliberately free of anything secret: no keys, no
 * addresses, no account, no lock. Those screens can still be opened; their
 * values never reach the model, because nothing here can read them.
 *
 * [name] is what the model names a setting by. It is stable across languages
 * and builds, unlike the title, which is translated.
 */
internal sealed class AssistantSetting(
    val name: String,
    @StringRes val titleRes: Int,
) {
    /** The current value, written the way the model reads and writes it. */
    abstract fun read(settings: KeyboardSettings): String

    /** [raw] as this setting's value, written canonically, or null when it is not one. */
    abstract fun parse(raw: String): String?

    /** Writes a value [parse] accepted, through the screen's own setter. */
    abstract suspend fun write(repository: SettingsRepository, value: String)

    /** What the model is told the setting accepts. */
    abstract val accepts: String

    class Switch(
        name: String,
        @StringRes titleRes: Int,
        private val get: (KeyboardSettings) -> Boolean,
        private val set: suspend SettingsRepository.(Boolean) -> Unit,
    ) : AssistantSetting(name, titleRes) {
        override fun read(settings: KeyboardSettings) = get(settings).toString()

        override fun parse(raw: String): String? = when (raw.trim().lowercase()) {
            "true", "on", "yes", "1" -> "true"
            "false", "off", "no", "0" -> "false"
            else -> null
        }

        override suspend fun write(repository: SettingsRepository, value: String) =
            repository.set(value == "true")

        override val accepts = "true or false"
    }

    class Number(
        name: String,
        @StringRes titleRes: Int,
        val range: IntRange,
        private val unit: String,
        private val get: (KeyboardSettings) -> Int,
        private val set: suspend SettingsRepository.(Int) -> Unit,
    ) : AssistantSetting(name, titleRes) {
        override fun read(settings: KeyboardSettings) = get(settings).toString()

        override fun parse(raw: String): String? =
            raw.trim().removeSuffix(unit).trim().toIntOrNull()?.takeIf { it in range }?.toString()

        override suspend fun write(repository: SettingsRepository, value: String) =
            repository.set(value.toInt())

        override val accepts = "a whole number of $unit from ${range.first} to ${range.last}"

        /** [value] with its unit, the way a card shows it: `300 ms`. */
        fun withUnit(value: String) = "$value $unit"
    }

    companion object {
        val all: List<AssistantSetting> = listOf(
            Switch("autocorrect", R.string.typing_autocorrect_title, { it.correction.enabled }, { setAutocorrect(it) }),
            Switch("suggestions", R.string.typing_suggestions_title, { it.suggestions }, { setSuggestions(it) }),
            Switch(
                "auto_capitalize", R.string.typing_auto_capitalize_title,
                { it.autoText.capitalize }, { setAutoCapitalize(it) },
            ),
            Switch(
                "space_after_punctuation", R.string.typing_auto_space_punctuation_title,
                { it.autoText.spaceAfterPunctuation }, { setAutoSpaceAfterPunctuation(it) },
            ),
            Switch(
                "space_after_suggestion", R.string.typing_space_after_suggestion_title,
                { it.suggestionStrip.autoSpaceAfterSuggestion }, { setAutoSpaceAfterSuggestion(it) },
            ),
            Switch("glide_typing", R.string.typing_glide_typing_title, { it.gestureTyping }, { setGestureTyping(it) }),
            Switch(
                "two_thumb_typing", R.string.typing_two_thumb_title,
                { it.gesture.twoThumbGlide }, { setGestureTwoThumb(it) },
            ),
            Switch("number_row", R.string.layout_number_row_title, { it.numberRow }, { setNumberRow(it) }),
            Switch("key_vibration", R.string.keypress_haptics_title, { it.haptics.enabled }, { setHapticFeedback(it) }),
            Switch("key_sound", R.string.hardware_sound_key_title, { it.sound.enabled }, { setKeySound(it) }),
            Switch(
                "live_wpm_on_spacebar", R.string.typing_spacebar_live_wpm_title,
                { it.spacebarLiveWpm }, { setSpacebarLiveWpm(it) },
            ),
            Number(
                "long_press_delay", R.string.keypress_long_press_delay_title, 100..800, "ms",
                { it.longPressDelayMs }, { setLongPressDelayMs(it) },
            ),
        )

        fun byName(name: String): AssistantSetting? = all.firstOrNull { it.name == name.trim().lowercase() }
    }
}

/**
 * One change the assistant proposed: [from] is what the setting held when it
 * was proposed, [to] what it would become. Applied at most once, and only while
 * the setting still holds [from]; undone only while it still holds [to], so an
 * undo never writes over a change the user made by hand since.
 */
internal data class AssistantProposal(
    val id: Int,
    val setting: AssistantSetting,
    val from: String,
    val to: String,
    val state: State = State.PROPOSED,
) {
    enum class State { PROPOSED, APPLIED, UNDONE, STALE, DISMISSED }
}

/** Why an Apply or an Undo did nothing. [WRITE_FAILED]: the setting could not be saved. */
internal enum class AssistantRefusal { NOT_PROPOSED, CHANGED_SINCE, INVALID, WRITE_FAILED }

/**
 * The rules for acting on a proposal, apart from any screen or coroutine, so
 * the tests can hold them to it. [current] reads the live settings.
 */
internal object AssistantProposals {

    /** What [proposal] may do now: null when Apply is allowed, else why not. */
    fun applyRefusal(proposal: AssistantProposal, current: KeyboardSettings): AssistantRefusal? = when {
        proposal.state != AssistantProposal.State.PROPOSED -> AssistantRefusal.NOT_PROPOSED
        proposal.setting.parse(proposal.to) != proposal.to -> AssistantRefusal.INVALID
        proposal.setting.read(current) != proposal.from -> AssistantRefusal.CHANGED_SINCE
        else -> null
    }

    /** Null when Undo is allowed, else why not. */
    fun undoRefusal(proposal: AssistantProposal, current: KeyboardSettings): AssistantRefusal? = when {
        proposal.state != AssistantProposal.State.APPLIED -> AssistantRefusal.NOT_PROPOSED
        proposal.setting.read(current) != proposal.to -> AssistantRefusal.CHANGED_SINCE
        else -> null
    }
}
