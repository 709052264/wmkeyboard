package com.wasimaster.wmkeyboard.core.plugins.lua

import com.wasimaster.wmkeyboard.core.plugins.PluginUiCodec
import com.wasimaster.wmkeyboard.core.plugins.lua.LuaDiagnosticCode as Code

private val CONTRACT_NAMES = setOf("render", "on_event")
private val CHECKED_ROOTS = setOf("wm", "ui", "string", "table", "math", "bit32", "os")
private val OS_REMOVED = setOf("execute", "exit", "getenv", "remove", "rename", "tmpname", "difftime", "setlocale")
private val LUA51_GLOBALS: Map<String, String?> = mapOf(
    "unpack" to "table.unpack",
    "setfenv" to null,
    "getfenv" to null,
    "module" to null,
)
private val LUA51_MEMBERS: Map<String, String?> = mapOf(
    "table.maxn" to "#",
    "table.getn" to "#",
    "table.setn" to null,
    "table.foreach" to "pairs",
    "table.foreachi" to "ipairs",
    "math.log10" to "math.log(x, 10)",
    "math.mod" to "math.fmod",
    "string.gfind" to "string.gmatch",
)

/**
 * The checks that need to know what names mean, so they run only on a script
 * that parses. Each is written to stay silent on the demo plugins: a check that
 * fires on the code the documentation teaches from is a wrong check, and
 * `LuaDemoCorpusTest` holds every one of them to that.
 */
internal class LuaChecks(
    private val document: LuaDocument,
    private val analysis: LuaAnalysis,
    private val host: LuaHostShape?,
    private val out: MutableList<LuaDiagnostic>,
) {
    private val source = document.source
    private val tokens = document.tokens
    private val blocks = document.blocks
    private val apiGlobals: Set<String> = LuaApi.children("").mapTo(HashSet()) { it.name }
    private val written: Set<String> = analysis.globals.filter { it.write }.mapTo(HashSet()) { it.name }

    fun run() {
        globals()
        accidentalGlobals()
        locals()
        members()
        contract()
        widgets()
        patterns()
        loops()
    }

    private fun add(code: Code, span: LuaSpan, arg1: String? = null, arg2: String? = null) {
        out += LuaDiagnostic(code, span, arg1, arg2)
    }

    // ---- globals and locals ------------------------------------------------

    private fun globals() {
        val known by lazy { (apiGlobals + written + analysis.symbols.map { it.name }).toSet() }
        for (use in analysis.globals) {
            val name = use.name
            if (use.write) {
                if (name in apiGlobals) add(Code.REPLACES_BUILTIN, use.span, name)
                continue
            }
            // A name the script assigns somewhere is its own, whatever Lua calls it.
            if (name in written || name in apiGlobals || name == "_ENV") continue
            val reason = LuaApi.nilled[name]
            when {
                reason != null -> add(removed(reason), use.span, name)
                name in LUA51_GLOBALS -> lua51(name, LUA51_GLOBALS[name], use.span)
                else -> {
                    val near = closest(name, known)
                    if (near != null) add(Code.GLOBAL_TYPO, use.span, name, near) else add(Code.UNDEFINED_GLOBAL, use.span, name)
                }
            }
        }
    }

    /**
     * A global that behaves like a forgotten `local`. Four things must all hold,
     * because a plugin keeps its state in globals on purpose: it is first used
     * inside a function, that first use assigns it, it is never used at the top
     * of the file, and every use is inside that one function. First means first
     * to run, so `clicks = (clicks or 0) + 1` reads before it writes. A value that
     * carries over from one event to the next is read before it is written, or
     * read in another function, and is left alone.
     */
    private fun accidentalGlobals() {
        for ((name, uses) in analysis.globals.groupBy { it.name }) {
            if (name in apiGlobals || name in CONTRACT_NAMES || name in LuaApi.nilled) continue
            val first = uses.minBy { it.order }
            if (!first.write || first.function == 0) continue
            if (uses.all { inside(it.function, first.function) }) add(Code.ACCIDENTAL_GLOBAL, first.span, name)
        }
    }

    private fun inside(function: Int, home: Int): Boolean {
        var at = function
        while (at != 0) {
            if (at == home) return true
            at = analysis.function(at)?.parent ?: 0
        }
        return false
    }

    private fun locals() {
        for (symbol in analysis.symbols) {
            val declaration = symbol.declaration ?: continue
            val declared = symbol.kind == LuaSymbolKind.LOCAL || symbol.kind == LuaSymbolKind.LOCAL_FUNCTION
            if (declared && symbol.reads.isEmpty() && !symbol.name.startsWith("_")) {
                add(Code.UNUSED_LOCAL, declaration, symbol.name)
            }
            if (symbol.name in CHECKED_ROOTS) add(Code.SHADOWS_LIBRARY, declaration, symbol.name)
        }
    }

    // ---- members of the API ------------------------------------------------

    private fun members() {
        val defined = writtenPaths()
        for (use in analysis.globals) {
            if (use.write || use.name !in CHECKED_ROOTS || use.name in written) continue
            var path = use.name
            var at = tokens.indexAt(use.span.start)
            while (true) {
                val dot = tokens.nextCode(at)
                if (dot < 0 || !tokens.matches(dot, ".")) break
                val member = tokens.nextCode(dot)
                if (member < 0 || tokens.kind(member) != LuaTokenKind.NAME) break
                if (LuaApi.find(path)?.kind != LuaApiKind.TABLE) break
                val name = tokens.text(member)
                val full = "$path.$name"
                if (LuaApi.find(full) == null) {
                    if (full !in defined) unknownMember(path, name, full, tokens.span(member))
                    break
                }
                when (full) {
                    "wm.storage" -> if (host != null && !host.storage) add(Code.STORAGE_NOT_DECLARED, tokens.span(member))
                    "wm.reminders" -> if (host != null && !host.reminders) add(Code.REMINDERS_NOT_DECLARED, tokens.span(member))
                    "string.dump" -> add(Code.STRING_DUMP, tokens.span(member))
                }
                path = full
                at = member
            }
        }
    }

    private fun unknownMember(path: String, name: String, full: String, span: LuaSpan) {
        when {
            path == "wm" && name in LuaApi.never -> add(Code.NEVER_IN_API, span, full)
            path == "os" && name in OS_REMOVED -> add(Code.OS_REDUCED, span, full)
            full in LUA51_MEMBERS -> lua51(full, LUA51_MEMBERS[full], span)
            else -> {
                val near = closest(name, LuaApi.children(path).map { it.name })
                if (near != null) add(Code.MEMBER_TYPO, span, full, "$path.$near") else add(Code.UNKNOWN_MEMBER, span, full)
            }
        }
    }

    private fun lua51(name: String, replacement: String?, span: LuaSpan) {
        if (replacement != null) add(Code.LUA51_NAME, span, name, replacement) else add(Code.LUA51_GONE, span, name)
    }

    /** Dotted names the script assigns itself, `string.shout = ` or `function string.shout(`, which exist once it runs. */
    private fun writtenPaths(): Set<String> {
        val paths = HashSet<String>()
        for (index in 0 until tokens.size) {
            val kind = tokens.kind(index)
            if (kind == LuaTokenKind.OPERATOR && tokens.matches(index, "=")) {
                chain(tokens.prevCode(index), backwards = true)?.let(paths::add)
            } else if (kind == LuaTokenKind.KEYWORD && tokens.matches(index, "function")) {
                chain(tokens.nextCode(index), backwards = false)?.let(paths::add)
            }
        }
        return paths
    }

    private fun chain(from: Int, backwards: Boolean): String? {
        val parts = ArrayList<String>()
        var at = from
        while (at >= 0 && tokens.kind(at) == LuaTokenKind.NAME) {
            parts += tokens.text(at)
            val dot = if (backwards) tokens.prevCode(at) else tokens.nextCode(at)
            if (dot < 0 || !(tokens.matches(dot, ".") || (!backwards && tokens.matches(dot, ":")))) break
            at = if (backwards) tokens.prevCode(dot) else tokens.nextCode(dot)
        }
        if (parts.size < 2) return null
        return (if (backwards) parts.asReversed() else parts).joinToString(".")
    }

    // ---- the plugin contract -----------------------------------------------

    private fun contract() {
        if ("render" !in written) add(Code.MISSING_RENDER, LuaSpan(0, source.indexOf('\n').let { if (it < 0) source.length else it }))
        topFunction("render")?.let { render ->
            if (render.parameters.isNotEmpty()) add(Code.RENDER_PARAMETERS, render.nameSpan)
            if (!render.returnsValue) add(Code.RENDER_RETURNS_NOTHING, render.nameSpan)
        }
        val onEvent = topFunction("on_event") ?: return
        if (onEvent.parameters.size > 1) add(Code.ON_EVENT_PARAMETERS, onEvent.nameSpan)
        eventComparisons(onEvent)
    }

    private fun topFunction(name: String): LuaFunction? =
        analysis.functions.firstOrNull { it.name == name && it.kind == LuaFunctionKind.GLOBAL && it.parent == 0 }

    /** `e.type == "clik"` and `e.id == "stop"` inside `on_event`, where `e` is its own parameter. */
    private fun eventComparisons(onEvent: LuaFunction) {
        val first = onEvent.parameters.firstOrNull() ?: return
        val event = analysis.symbols.firstOrNull {
            it.kind == LuaSymbolKind.PARAMETER && it.function == onEvent.id && it.name == first
        } ?: return
        val ids = literalIds()
        for (read in event.reads) {
            val subject = tokens.indexAt(read.start)
            val dot = tokens.nextCode(subject)
            val field = if (dot >= 0 && tokens.matches(dot, ".")) tokens.nextCode(dot) else -1
            if (field < 0) continue
            val isType = tokens.matches(field, "type")
            if (!isType && !tokens.matches(field, "id")) continue
            val literal = comparedLiteral(subject, field) ?: continue
            val value = literalText(literal) ?: continue
            val span = tokens.span(literal)
            if (isType) {
                if (value in LuaApi.eventTypes) continue
                val near = closest(value, LuaApi.eventTypes)
                if (near != null) add(Code.EVENT_TYPE_TYPO, span, value, near) else add(Code.UNKNOWN_EVENT_TYPE, span, value)
            } else if (!ids.isNullOrEmpty() && value !in ids) {
                add(Code.UNKNOWN_EVENT_ID, span, value)
            }
        }
    }

    /** The string literal `e.field` is compared with, on either side of `==` or `~=`, and with nothing joined to it. */
    private fun comparedLiteral(subject: Int, field: Int): Int? {
        val after = tokens.nextCode(field)
        if (after >= 0 && (tokens.matches(after, "==") || tokens.matches(after, "~="))) {
            val literal = tokens.nextCode(after)
            if (literal >= 0 && tokens.kind(literal) == LuaTokenKind.STRING) {
                val next = tokens.nextCode(literal)
                if (next < 0 || !tokens.matches(next, "..")) return literal
            }
        }
        val before = tokens.prevCode(subject)
        if (before >= 0 && (tokens.matches(before, "==") || tokens.matches(before, "~="))) {
            val literal = tokens.prevCode(before)
            if (literal >= 0 && tokens.kind(literal) == LuaTokenKind.STRING) {
                val previous = tokens.prevCode(literal)
                if (previous < 0 || !tokens.matches(previous, "..")) return literal
            }
        }
        return null
    }

    /** Every id written as a plain string, or null when any id is built while the plugin runs. */
    private fun literalIds(): Set<String>? {
        val ids = HashSet<String>()
        for (index in 0 until tokens.size) {
            if (tokens.kind(index) != LuaTokenKind.NAME || !tokens.matches(index, "id")) continue
            val before = tokens.prevCode(index)
            if (before < 0 || !(tokens.matches(before, "{") || tokens.matches(before, ",") || tokens.matches(before, ";"))) continue
            val key = keyAt(index) ?: continue
            ids += key.literal ?: return null
        }
        return ids
    }

    // ---- widgets -----------------------------------------------------------

    private class Key(val name: String, val token: Int, val valueToken: Int, val literal: String?)

    private class TableFields(val keys: List<Key>, val positional: Int, val bracketKeys: Boolean)

    private fun widgets() {
        if ("ui" in written) return
        val ids = HashSet<String>()
        for (use in analysis.globals) {
            if (use.write || use.name != "ui") continue
            val uiToken = tokens.indexAt(use.span.start)
            val dot = tokens.nextCode(uiToken)
            if (dot < 0 || !tokens.matches(dot, ".")) continue
            val nameToken = tokens.nextCode(dot)
            if (nameToken < 0 || tokens.kind(nameToken) != LuaTokenKind.NAME) continue
            val shape = LuaApi.uiShapes[tokens.text(nameToken)] ?: continue
            var open = tokens.nextCode(nameToken)
            if (open >= 0 && tokens.matches(open, "(")) open = tokens.nextCode(open)
            if (open < 0 || !tokens.matches(open, "{")) continue
            val table = fieldsOf(open)
            val constructor = "ui.${shape.constructor}"
            val call = LuaSpan(tokens.start(uiToken), tokens.end(nameToken))
            for (key in table.keys) {
                if (key.name in shape.fields) continue
                val near = closest(key.name, shape.fields)
                val span = tokens.span(key.token)
                if (near != null) add(Code.UI_FIELD_TYPO, span, key.name, near) else add(Code.UNKNOWN_UI_FIELD, span, key.name, constructor)
            }
            val id = table.keys.firstOrNull { it.name == "id" }
            if (shape.needsId && id == null && !table.bracketKeys) add(Code.MISSING_ID, call, constructor)
            val idText = id?.literal
            if (shape.needsId && idText != null && !ids.add(idText)) add(Code.DUPLICATE_ID, tokens.span(id.valueToken), idText)
            val style = table.keys.firstOrNull { it.name == "style" }
            val styleText = style?.literal
            if (style != null && styleText != null) {
                val span = tokens.span(style.valueToken)
                if (shape.constructor == "label" && styleText !in LuaApi.labelStyles) {
                    val near = closest(styleText, LuaApi.labelStyles)
                    if (near != null) add(Code.LABEL_STYLE_TYPO, span, styleText, near) else add(Code.UNKNOWN_LABEL_STYLE, span, styleText)
                }
                if (shape.constructor == "button" && styleText !in LuaApi.buttonStyles) add(Code.PLAIN_BUTTON_STYLE, span, styleText)
            }
            if (shape.constructor == "tabs" && table.positional > PluginUiCodec.MAX_TABS) add(Code.TOO_MANY_TABS, call)
        }
    }

    /** The fields of the table constructor that opens at [open], one level deep. A function inside is skipped whole. */
    private fun fieldsOf(open: Int): TableFields {
        val keys = ArrayList<Key>()
        var positional = 0
        var bracketKeys = false
        var depth = 0
        var fieldStart = true
        var at = tokens.nextCode(open)
        while (at >= 0) {
            if (tokens.kind(at) == LuaTokenKind.KEYWORD && tokens.matches(at, "function")) {
                val end = blocks.closerOf(at)
                if (end > at) {
                    fieldStart = false
                    at = tokens.nextCode(end)
                    continue
                }
            }
            if (depth == 0 && fieldStart) {
                fieldStart = false
                val key = keyAt(at)
                when {
                    key != null -> keys += key
                    tokens.matches(at, "[") -> bracketKeys = true
                    !tokens.matches(at, "}") -> positional++
                }
            }
            if (tokens.kind(at) == LuaTokenKind.OPERATOR) {
                when {
                    tokens.matches(at, "(") || tokens.matches(at, "{") || tokens.matches(at, "[") -> depth++
                    tokens.matches(at, ")") || tokens.matches(at, "}") || tokens.matches(at, "]") -> {
                        if (depth == 0) break
                        depth--
                    }
                    depth == 0 && (tokens.matches(at, ",") || tokens.matches(at, ";")) -> fieldStart = true
                }
            }
            at = tokens.nextCode(at)
        }
        return TableFields(keys, positional, bracketKeys)
    }

    /** `name = value` starting at [token], or null. The value counts as literal only when it is one string and nothing more. */
    private fun keyAt(token: Int): Key? {
        if (tokens.kind(token) != LuaTokenKind.NAME) return null
        val equals = tokens.nextCode(token)
        if (equals < 0 || !tokens.matches(equals, "=")) return null
        val value = tokens.nextCode(equals)
        if (value < 0) return null
        val after = tokens.nextCode(value)
        val alone = after < 0 || tokens.matches(after, ",") || tokens.matches(after, ";") || tokens.matches(after, "}")
        return Key(tokens.text(token), token, value, if (alone) literalText(value) else null)
    }

    private fun literalText(token: Int): String? {
        if (tokens.kind(token) != LuaTokenKind.STRING || tokens.unterminated(token)) return null
        val text = tokens.text(token)
        if (text.length < 2 || '\\' in text) return null
        return text.substring(1, text.length - 1)
    }

    // ---- patterns and loops ------------------------------------------------

    /** `%f[` frontier patterns throw inside luaj's pattern matcher, so the plugin stops with an error. */
    private fun patterns() {
        var from = source.indexOf("%f[")
        while (from >= 0) {
            val token = tokens.indexAt(from)
            if (token < 0) break
            val kind = tokens.kind(token)
            if (kind == LuaTokenKind.STRING || kind == LuaTokenKind.LONG_STRING) add(Code.FRONTIER_PATTERN, tokens.span(token))
            from = source.indexOf("%f[", maxOf(tokens.end(token), from + 1))
        }
    }

    /** `while true do` and `repeat ... until false` with no way out, which only the instruction budget ends. */
    private fun loops() {
        for (index in 0 until tokens.size) {
            if (tokens.kind(index) != LuaTokenKind.KEYWORD) continue
            if (tokens.matches(index, "while")) {
                val condition = tokens.nextCode(index)
                val body = if (condition >= 0) tokens.nextCode(condition) else -1
                if (condition < 0 || body < 0 || !tokens.matches(condition, "true") || !tokens.matches(body, "do")) continue
                val end = blocks.closerOf(body)
                if (end > body && !exits(body, end)) add(Code.LOOP_NEVER_ENDS, LuaSpan(tokens.start(index), tokens.end(condition)))
            } else if (tokens.matches(index, "until")) {
                val condition = tokens.nextCode(index)
                if (condition < 0 || !tokens.matches(condition, "false")) continue
                val after = tokens.nextCode(condition)
                if (after >= 0 && (tokens.matches(after, "and") || tokens.matches(after, "or") ||
                        (tokens.kind(after) == LuaTokenKind.OPERATOR && !tokens.matches(after, ";")))
                ) continue
                val start = blocks.openerOf(index)
                if (start >= 0 && !exits(start, index)) add(Code.LOOP_NEVER_ENDS, LuaSpan(tokens.start(index), tokens.end(condition)))
            }
        }
    }

    /** Whether anything between two tokens can leave the loop: `break`, `return`, `goto` or a call to `error`, outside any inner function. */
    private fun exits(from: Int, to: Int): Boolean {
        var at = from + 1
        while (at < to) {
            val kind = tokens.kind(at)
            if (kind == LuaTokenKind.KEYWORD) {
                if (tokens.matches(at, "function")) {
                    val end = blocks.closerOf(at)
                    if (end > at) {
                        at = end + 1
                        continue
                    }
                }
                if (tokens.matches(at, "break") || tokens.matches(at, "return") || tokens.matches(at, "goto")) return true
            } else if (kind == LuaTokenKind.NAME && tokens.matches(at, "error")) {
                return true
            }
            at++
        }
        return false
    }

    // ---- names nearly right ------------------------------------------------

    /** The candidate within typing distance of [word], or null. A short word must be nearer. */
    private fun closest(word: String, candidates: Collection<String>): String? {
        if (word.length < 3) return null
        val limit = if (word.length <= 4) 1 else 2
        var best: String? = null
        var bestDistance = limit + 1
        for (candidate in candidates) {
            if (candidate == word || kotlin.math.abs(candidate.length - word.length) > limit) continue
            val distance = editDistance(word, candidate)
            if (distance < bestDistance || (distance == bestDistance && best != null && candidate < best)) {
                best = candidate
                bestDistance = distance
            }
        }
        return best?.takeIf { bestDistance <= limit }
    }

    private fun removed(reason: LuaApi.NilReason): Code = when (reason) {
        LuaApi.NilReason.LOADS_CODE -> Code.REMOVED_LOADS_CODE
        LuaApi.NilReason.FILES -> Code.REMOVED_FILES
        LuaApi.NilReason.CODE_FROM_TEXT -> Code.REMOVED_CODE_FROM_TEXT
        LuaApi.NilReason.COROUTINES -> Code.REMOVED_COROUTINES
        LuaApi.NilReason.JAVA_OR_DEBUGGER -> Code.REMOVED_JAVA_OR_DEBUGGER
    }
}

/** Edits between two words, a swap of neighbours counting as one and case not counting at all. */
internal fun editDistance(a: String, b: String): Int {
    val d = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) d[i][0] = i
    for (j in 0..b.length) d[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            val cost = if (a[i - 1].lowercaseChar() == b[j - 1].lowercaseChar()) 0 else 1
            var value = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) value = minOf(value, d[i - 2][j - 2] + 1)
            d[i][j] = value
        }
    }
    return d[a.length][b.length]
}
