package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.settings.TextEditAction
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement

/** Which way a multi-finger swipe went (issue #647). */
enum class SwipeDirection(val token: Char) { UP('U'), DOWN('D'), LEFT('L'), RIGHT('R') }

/**
 * One multi-finger swipe on the key grid (issue #647): how many fingers, and
 * which way they went together.
 *
 * Written as two characters, `2U`, `3L`: the slot names in the stored map and
 * nothing else, so a direction or count this build lacks drops that slot alone.
 */
data class FingerSwipe(val fingers: Int, val direction: SwipeDirection) {
    init {
        require(fingers in FingerSwipeCounts) { "fingers=$fingers" }
    }

    val token: String get() = "$fingers${direction.token}"

    companion object {
        fun parse(token: String): FingerSwipe? {
            if (token.length != 2) return null
            val fingers = token[0].digitToIntOrNull()?.takeIf { it in FingerSwipeCounts } ?: return null
            val direction = SwipeDirection.entries.firstOrNull { it.token == token[1] } ?: return null
            return FingerSwipe(fingers, direction)
        }

        /** Every slot, in the order the settings page lists them. */
        val all: List<FingerSwipe> = FingerSwipeCounts.flatMap { count ->
            SwipeDirection.entries.map { FingerSwipe(count, it) }
        }
    }
}

/** How many fingers a swipe may be made with: past four, a hand is resting, not swiping. */
val FingerSwipeCounts = 2..4

/**
 * What a fresh install binds (issue #647's own suggestion): two fingers up to
 * select everything, left and right to undo and redo, down to paste. Three and
 * four fingers start empty.
 */
val DefaultFingerSwipes: Map<FingerSwipe, KeyAlternate> = mapOf(
    FingerSwipe(2, SwipeDirection.UP) to KeyAlternate(KeyAction.Edit(TextEditAction.SELECT_ALL)),
    FingerSwipe(2, SwipeDirection.LEFT) to KeyAlternate(KeyAction.Tool(ToolbarTool.UNDO)),
    FingerSwipe(2, SwipeDirection.RIGHT) to KeyAlternate(KeyAction.Tool(ToolbarTool.REDO)),
    FingerSwipe(2, SwipeDirection.DOWN) to KeyAlternate(KeyAction.Edit(TextEditAction.PASTE)),
)

/**
 * What [swipe] runs: the user's binding, else the default, else nothing. A
 * binding to [KeyAction.None] is the user having cleared a default, and also
 * answers null.
 */
fun Map<FingerSwipe, KeyAlternate>.actionOf(swipe: FingerSwipe): KeyAlternate? =
    (this[swipe] ?: DefaultFingerSwipes[swipe])?.takeUnless { it.action == KeyAction.None }

/**
 * The stored form of the bindings: one JSON object keyed by [FingerSwipe.token],
 * each value a [KeyAlternate] in the layout format, so a swipe can run anything
 * a key can and reads back through the same tolerant decoder.
 *
 * A slot this build cannot name, or an entry it cannot decode, costs that entry
 * and no more. An action tag from a newer build decodes to
 * [KeyAction.Unknown] and is dropped too, rather than kept as a swipe that does
 * nothing while claiming to do something.
 */
object FingerSwipeCodec {
    fun decode(json: String?): Map<FingerSwipe, KeyAlternate> {
        if (json.isNullOrBlank()) return emptyMap()
        val root = runCatching { layoutJson.parseToJsonElement(json) as? JsonObject }.getOrNull()
            ?: return emptyMap()
        return root.entries.mapNotNull { (token, element) ->
            val swipe = FingerSwipe.parse(token) ?: return@mapNotNull null
            val binding = runCatching { layoutJson.decodeFromJsonElement<KeyAlternate>(element) }.getOrNull()
                ?: return@mapNotNull null
            if (binding.action is KeyAction.Unknown) null else swipe to binding
        }.toMap()
    }

    fun encode(map: Map<FingerSwipe, KeyAlternate>): String =
        JsonObject(
            map.entries
                .sortedBy { FingerSwipe.all.indexOf(it.key) }
                .associate { (swipe, binding) -> swipe.token to layoutJson.encodeToJsonElement(binding) },
        ).toString()
}
