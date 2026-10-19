package com.wasimaster.wmkeyboard.app.assistant

import com.wasimaster.wmkeyboard.app.SettingsSearchEntry
import com.wasimaster.wmkeyboard.app.searchSettings
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.tools.AiToolCall
import com.wasimaster.wmkeyboard.core.tools.AiToolLoop
import com.wasimaster.wmkeyboard.core.tools.AiToolResult
import com.wasimaster.wmkeyboard.core.tools.AiToolSpec
import com.wasimaster.wmkeyboard.core.tools.WmDocs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The five things the Settings assistant (#788) can do, as tools a model calls.
 *
 * * `search_settings` reads the real settings index, the one the search screen
 *   ranks, so every row the assistant names is one that exists.
 * * `show_setting` puts a row a search returned in front of the user, as a
 *   card with where it lives and an Open button, by the id the search gave.
 *   It never leaves the conversation: opening is the user's tap. An id the
 *   index does not hold is refused, so a made-up route goes nowhere.
 * * `get_setting` and `propose_change` are about [AssistantSetting]s only. A
 *   proposal is not a change: it is put in front of the user with the current
 *   and the proposed value, and only their Apply writes it.
 * * `search_docs` searches the documentation site ([WmDocs]), for what no
 *   settings row explains: which files an import takes, how a feature works.
 *   Only the query leaves the phone.
 *
 * None of these return a value outside [AssistantSetting], which holds no
 * secret, so a key or an address cannot reach the model through them.
 */
internal class AssistantTools(
    private val index: List<SettingsSearchEntry>,
    private val settings: () -> KeyboardSettings,
    private val host: Host,
    private val refs: SettingRefs = SettingRefs(),
) : AiToolLoop.Executor {

    /** What the tools ask of the screen. Called on the tool's own thread. */
    interface Host {
        /** Shows [entry] as a card the user can open it from. */
        fun show(entry: SettingsSearchEntry)

        /** Shows a proposal for the user to apply or ignore. Returns its id. */
        fun propose(setting: AssistantSetting, from: String, to: String): Int
    }

    val specs: List<AiToolSpec> = listOf(
        AiToolSpec(
            SEARCH,
            "Find settings rows by what they do. Returns up to $MAX_RESULTS rows, each with an id, its title, " +
                "where it lives and what it says. Search before you name or open a setting.",
            schema("query" to "Words describing the setting, in the user's language or English."),
        ),
        AiToolSpec(
            SHOW,
            "Show one settings row to the user as a card with where it lives and an Open button. " +
                "It does not leave the chat. Use an id from $SEARCH.",
            schema("id" to "The id $SEARCH gave for the row."),
        ),
        AiToolSpec(
            GET,
            "Read the current value of a setting you can change. Names: ${AssistantSetting.all.joinToString { it.name }}.",
            schema("name" to "One of the names listed."),
        ),
        AiToolSpec(
            PROPOSE,
            "Propose changing a setting. The user sees the current and the new value and decides; " +
                "nothing changes until they press Apply. Only the names $GET lists can be proposed.",
            schema(
                "name" to "One of the names $GET lists.",
                "value" to "The new value, in the form $GET describes.",
            ),
        ),
        AiToolSpec(
            DOCS,
            "Search WM Keyboard's documentation, for questions a settings row does not answer: how a feature works, " +
                "which files an import accepts, how to do something step by step. Returns sections with their links.",
            schema("query" to "What to look for, in English."),
        ),
    )

    override fun run(call: AiToolCall): AiToolResult = runCatching {
        when (call.name) {
            SEARCH -> search(call)
            SHOW -> show(call)
            GET -> get(call)
            PROPOSE -> propose(call)
            DOCS -> docs(call)
            else -> AiToolResult(call, "", error = "No tool named ${call.name}.")
        }
    }.getOrElse { AiToolResult(call, "", error = it.message ?: "The tool failed.") }

    private fun search(call: AiToolCall): AiToolResult {
        val query = call.argument("query") ?: return AiToolResult(call, "", error = "No query given.")
        val hits = searchSettings(query, index).take(MAX_RESULTS)
        if (hits.isEmpty()) return AiToolResult(call, "No settings row matches \"$query\".")
        return AiToolResult(
            call,
            hits.joinToString("\n") { entry ->
                buildString {
                    append("id=").append(refs.idFor(entry.key)).append(" | ").append(entry.title)
                    if (entry.screen.isNotEmpty()) append(" | in ").append(entry.screen)
                    if (entry.subtitle.isNotEmpty()) append(" | ").append(entry.subtitle)
                    changeable(entry)?.let { append(" | changeable as ").append(it.name) }
                }
            },
        )
    }

    private fun show(call: AiToolCall): AiToolResult {
        val id = call.argument("id") ?: return AiToolResult(call, "", error = "No id given.")
        val entry = entry(id)
            ?: return AiToolResult(call, "", error = "No settings row has the id \"$id\". Search first.")
        host.show(entry)
        return AiToolResult(
            call,
            "Showed ${entry.title}${if (entry.screen.isEmpty()) "" else " in ${entry.screen}"}. " +
                "The user opens it from the card when they want to.",
        )
    }

    /** The row a short id from [search] names; a full key is still taken, for an id written out whole. */
    private fun entry(id: String): SettingsSearchEntry? {
        val key = refs.keyFor(id) ?: id
        return index.firstOrNull { it.key == key }
    }

    private fun get(call: AiToolCall): AiToolResult {
        val setting = setting(call) ?: return unknown(call)
        return AiToolResult(
            call,
            "${setting.name} is ${setting.read(settings())}; it accepts ${setting.accepts}.",
        )
    }

    private fun propose(call: AiToolCall): AiToolResult {
        val setting = setting(call) ?: return unknown(call)
        val raw = call.argument("value") ?: return AiToolResult(call, "", error = "No value given.")
        val to = setting.parse(raw)
            ?: return AiToolResult(call, "", error = "${setting.name} accepts ${setting.accepts}, not \"$raw\".")
        val from = setting.read(settings())
        if (from == to) return AiToolResult(call, "${setting.name} is already $to. Nothing to propose.")
        host.propose(setting, from, to)
        return AiToolResult(
            call,
            "Proposed ${setting.name}: $from -> $to. The user decides with Apply; do not say it is changed.",
        )
    }

    private fun docs(call: AiToolCall): AiToolResult {
        val query = call.argument("query") ?: return AiToolResult(call, "", error = "No query given.")
        return AiToolResult(call, WmDocs.format(query, WmDocs.search(query, source = NetSource.AI_CHAT)))
    }

    private fun setting(call: AiToolCall): AssistantSetting? =
        call.argument("name")?.let(AssistantSetting::byName)

    private fun unknown(call: AiToolCall) = AiToolResult(
        call, "",
        error = "Not a setting that can be changed here. Names: ${AssistantSetting.all.joinToString { it.name }}. " +
            "Open the row instead and let the user change it.",
    )

    /** The changeable setting a row is, when it is one. */
    private fun changeable(entry: SettingsSearchEntry): AssistantSetting? =
        AssistantSetting.all.firstOrNull { it.titleRes != 0 && it.titleRes == entry.titleRes }

    private fun schema(vararg properties: Pair<String, String>): JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for ((name, description) in properties) {
                putJsonObject(name) {
                    put("type", "string")
                    put("description", description)
                }
            }
        }
        put("required", buildJsonArray { properties.forEach { add(JsonPrimitive(it.first)) } })
    }

    companion object {
        const val SEARCH = "search_settings"
        const val SHOW = "show_setting"
        const val GET = "get_setting"
        const val PROPOSE = "propose_change"
        const val DOCS = "search_docs"
        const val MAX_RESULTS = 8
    }
}

/**
 * Short ids for the rows the assistant has been shown: `s1`, `s2`, in the order
 * searches returned them. A row's own key holds spaces and a `#`, which a
 * markdown link cannot carry, and the answer links rows as `setting:s3`. Only a
 * row a search returned has an id, so a link can only name a row that exists.
 */
internal class SettingRefs {
    private val keys = HashMap<String, String>()
    private val ids = HashMap<String, String>()

    @Synchronized
    fun idFor(key: String): String = ids.getOrPut(key) { "s${ids.size + 1}".also { keys[it] = key } }

    @Synchronized
    fun keyFor(id: String): String? = keys[id.trim().removePrefix(LINK_SCHEME)]

    @Synchronized
    fun clear() {
        keys.clear()
        ids.clear()
    }

    companion object {
        /** The scheme of a link to a row in an answer: `[Long-press delay](setting:s3)`. */
        const val LINK_SCHEME = "setting:"
    }
}
