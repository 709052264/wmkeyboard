package com.wasimaster.wmkeyboard.core.plugins.lua

enum class LuaSeverity { ERROR, WARNING, INFO }

/**
 * Every kind of problem the plugin editor reports. The words are in the app, one
 * string for each code, so each code fixes what [LuaDiagnostic.arg1] and
 * [LuaDiagnostic.arg2] hold. A problem that can name what was probably meant
 * comes as two codes, one with the suggestion and one without, so no string
 * carries an optional sentence.
 */
enum class LuaDiagnosticCode(val severity: LuaSeverity) {
    // ---- the text ----------------------------------------------------------

    /** arg1: the characters. */
    UNKNOWN_CHARACTER(LuaSeverity.ERROR),
    UNCLOSED_STRING(LuaSeverity.ERROR),
    UNCLOSED_LONG_STRING(LuaSeverity.ERROR),
    UNCLOSED_COMMENT(LuaSeverity.ERROR),

    /** arg1: the bracket nothing closes. */
    UNCLOSED_BRACKET(LuaSeverity.ERROR),

    /** arg1: the closing bracket found; arg2: the one that would close what is open. */
    WRONG_CLOSER(LuaSeverity.ERROR),

    /** arg1: a closing bracket with nothing open. */
    STRAY_CLOSER(LuaSeverity.ERROR),

    /** arg1: the keyword that starts the block; arg2: `end` or `until`. */
    UNCLOSED_BLOCK(LuaSeverity.ERROR),

    /** arg1: the text found; arg2: what the parser would take there, joined with commas. */
    SYNTAX_EXPECTED(LuaSeverity.ERROR),

    /** arg1: the text found. */
    SYNTAX(LuaSeverity.ERROR),

    /** arg1: what the parser would take, joined with commas. */
    SYNTAX_AT_END_EXPECTED(LuaSeverity.ERROR),
    SYNTAX_AT_END(LuaSeverity.ERROR),

    // ---- the sandbox -------------------------------------------------------

    /** arg1: the name, for each of these five. */
    REMOVED_LOADS_CODE(LuaSeverity.ERROR),
    REMOVED_FILES(LuaSeverity.ERROR),
    REMOVED_CODE_FROM_TEXT(LuaSeverity.ERROR),
    REMOVED_COROUTINES(LuaSeverity.ERROR),
    REMOVED_JAVA_OR_DEBUGGER(LuaSeverity.ERROR),

    /** arg1: the full name, `wm.clipboard`. */
    NEVER_IN_API(LuaSeverity.ERROR),

    /** arg1: the full name, `os.getenv`. */
    OS_REDUCED(LuaSeverity.ERROR),

    /** arg1: the full name. */
    UNKNOWN_MEMBER(LuaSeverity.WARNING),

    /** arg1: the full name; arg2: the real name nearest to it. */
    MEMBER_TYPO(LuaSeverity.WARNING),

    /** arg1: the Lua 5.1 name; arg2: what Lua 5.2 has in its place. */
    LUA51_NAME(LuaSeverity.WARNING),

    /** arg1: a Lua 5.1 name Lua 5.2 has nothing in place of. */
    LUA51_GONE(LuaSeverity.WARNING),
    STRING_DUMP(LuaSeverity.WARNING),
    STORAGE_NOT_DECLARED(LuaSeverity.WARNING),
    REMINDERS_NOT_DECLARED(LuaSeverity.WARNING),
    FRONTIER_PATTERN(LuaSeverity.WARNING),

    // ---- the plugin contract -----------------------------------------------

    MISSING_RENDER(LuaSeverity.ERROR),
    RENDER_RETURNS_NOTHING(LuaSeverity.WARNING),
    RENDER_PARAMETERS(LuaSeverity.INFO),
    ON_EVENT_PARAMETERS(LuaSeverity.INFO),

    /** arg1: the event type written. */
    UNKNOWN_EVENT_TYPE(LuaSeverity.WARNING),

    /** arg1: the event type written; arg2: the real type nearest to it. */
    EVENT_TYPE_TYPO(LuaSeverity.WARNING),

    /** arg1: the id. */
    UNKNOWN_EVENT_ID(LuaSeverity.INFO),

    /** arg1: the field; arg2: the constructor, `ui.column`. */
    UNKNOWN_UI_FIELD(LuaSeverity.WARNING),

    /** arg1: the field; arg2: the real field nearest to it. */
    UI_FIELD_TYPO(LuaSeverity.WARNING),

    /** arg1: the constructor, `ui.button`. */
    MISSING_ID(LuaSeverity.WARNING),

    /** arg1: the id. */
    DUPLICATE_ID(LuaSeverity.INFO),

    /** arg1: the style. */
    UNKNOWN_LABEL_STYLE(LuaSeverity.WARNING),

    /** arg1: the style; arg2: the real style nearest to it. */
    LABEL_STYLE_TYPO(LuaSeverity.WARNING),

    /** arg1: the style. */
    PLAIN_BUTTON_STYLE(LuaSeverity.INFO),
    TOO_MANY_TABS(LuaSeverity.WARNING),

    // ---- Lua ---------------------------------------------------------------

    /** arg1: the name, for each of these five. */
    UNDEFINED_GLOBAL(LuaSeverity.WARNING),

    /** arg1: the name; arg2: the name nearest to it. */
    GLOBAL_TYPO(LuaSeverity.WARNING),
    ACCIDENTAL_GLOBAL(LuaSeverity.WARNING),
    REPLACES_BUILTIN(LuaSeverity.WARNING),
    UNUSED_LOCAL(LuaSeverity.INFO),
    SHADOWS_LIBRARY(LuaSeverity.INFO),
    LOOP_NEVER_ENDS(LuaSeverity.WARNING),
}

data class LuaDiagnostic(
    val code: LuaDiagnosticCode,
    val span: LuaSpan,
    val arg1: String? = null,
    val arg2: String? = null,
) {
    val severity: LuaSeverity
        get() = code.severity
}

/** What the manifest being edited declares, for the checks that depend on it. */
data class LuaHostShape(val storage: Boolean, val reminders: Boolean = false)

/**
 * Everything wrong with a script, as the plugin editor shows it.
 *
 * A script that does not parse gets its one structural problem, placed as
 * exactly as the tokens allow, and nothing else: warnings about names in a file
 * that is half typed are noise. A script that parses gets every check in
 * [LuaChecks].
 */
object LuaDiagnostics {
    const val MAX_DIAGNOSTICS = 200
    private const val MAX_EXPECTED = 4
    private const val MAX_SHOWN = 40
    private const val MAX_HEAD_TOKENS = 64
    private const val OPENERS = "({["
    private const val CLOSERS = ")}]"

    /** With [host] null, the checks that need the manifest are skipped. */
    fun of(document: LuaDocument, host: LuaHostShape? = null): List<LuaDiagnostic> {
        val out = ArrayList<LuaDiagnostic>()
        lexical(document.tokens, out)
        val syntax = document.syntax
        if (syntax is LuaSyntax.Invalid && out.isEmpty()) out += structure(document, syntax)
        document.analysis?.let { LuaChecks(document, it, host, out).run() }
        return out.distinct()
            .sortedWith(compareBy<LuaDiagnostic>({ it.span.start }, { it.code.severity }))
            .take(MAX_DIAGNOSTICS)
    }

    private fun lexical(tokens: LuaTokens, out: MutableList<LuaDiagnostic>) {
        var index = 0
        while (index < tokens.size) {
            val kind = tokens.kind(index)
            val start = tokens.start(index)
            val end = tokens.end(index)
            when {
                kind == LuaTokenKind.UNKNOWN -> {
                    var last = index
                    while (last + 1 < tokens.size && tokens.kind(last + 1) == LuaTokenKind.UNKNOWN) last++
                    val until = tokens.end(last)
                    out += LuaDiagnostic(
                        LuaDiagnosticCode.UNKNOWN_CHARACTER,
                        LuaSpan(start, until),
                        tokens.source.substring(start, minOf(until, start + MAX_SHOWN)),
                    )
                    index = last
                }
                !tokens.unterminated(index) -> Unit
                // Only the opener is marked: the rest of the file is not the problem.
                kind == LuaTokenKind.STRING ->
                    out += LuaDiagnostic(LuaDiagnosticCode.UNCLOSED_STRING, LuaSpan(start, minOf(end, start + 1)))
                kind == LuaTokenKind.LONG_STRING -> out += LuaDiagnostic(
                    LuaDiagnosticCode.UNCLOSED_LONG_STRING,
                    LuaSpan(start, minOf(end, start + tokens.level(index) + 2)),
                )
                kind == LuaTokenKind.LONG_COMMENT -> out += LuaDiagnostic(
                    LuaDiagnosticCode.UNCLOSED_COMMENT,
                    LuaSpan(start, minOf(end, start + tokens.level(index) + 4)),
                )
            }
            index++
        }
    }

    private fun structure(document: LuaDocument, syntax: LuaSyntax.Invalid): LuaDiagnostic {
        val tokens = document.tokens
        brackets(tokens)?.let { return it }
        val atEnd = syntax.found == "<EOF>"
        if (atEnd) unclosedBlock(document)?.let { return it }
        val expected = syntax.expected.filterNot { it.startsWith("<") }.take(MAX_EXPECTED).joinToString(", ").ifEmpty { null }
        if (atEnd) {
            val last = (tokens.size - 1 downTo 0).firstOrNull { tokens.kind(it).isCode }
            val span = last?.let(tokens::span) ?: syntax.at
            return if (expected != null) {
                LuaDiagnostic(LuaDiagnosticCode.SYNTAX_AT_END_EXPECTED, span, expected)
            } else {
                LuaDiagnostic(LuaDiagnosticCode.SYNTAX_AT_END, span)
            }
        }
        val found = syntax.found.take(MAX_SHOWN)
        return if (expected != null) {
            LuaDiagnostic(LuaDiagnosticCode.SYNTAX_EXPECTED, syntax.at, found, expected)
        } else {
            LuaDiagnostic(LuaDiagnosticCode.SYNTAX, syntax.at, found)
        }
    }

    /**
     * The first bracket that does not pair, from the tokens alone. Where the
     * parser can only say what it expected at the end of the file, this can
     * point at the bracket that was never closed.
     */
    private fun brackets(tokens: LuaTokens): LuaDiagnostic? {
        val open = ArrayList<Int>()
        for (index in 0 until tokens.size) {
            if (tokens.kind(index) != LuaTokenKind.OPERATOR || tokens.end(index) - tokens.start(index) != 1) continue
            val character = tokens.source[tokens.start(index)]
            if (character in OPENERS) {
                open += index
                continue
            }
            if (character !in CLOSERS) continue
            val top = open.lastOrNull()
                ?: return LuaDiagnostic(LuaDiagnosticCode.STRAY_CLOSER, tokens.span(index), character.toString())
            val wanted = CLOSERS[OPENERS.indexOf(tokens.source[tokens.start(top)])]
            if (wanted != character) {
                return LuaDiagnostic(LuaDiagnosticCode.WRONG_CLOSER, tokens.span(index), character.toString(), wanted.toString())
            }
            open.removeAt(open.size - 1)
        }
        val unclosed = open.lastOrNull() ?: return null
        return LuaDiagnostic(LuaDiagnosticCode.UNCLOSED_BRACKET, tokens.span(unclosed), tokens.text(unclosed))
    }

    /**
     * The block a missing `end` belongs to. Pairing keywords gives every `end` to
     * the nearest open block, so an `if` that lost its `end` looks like the
     * function around it did. Indentation tells them apart: an `end` indented
     * less than the line that opened the block it was given to belongs further
     * out, and that block is the one left open.
     */
    private fun unclosedBlock(document: LuaDocument): LuaDiagnostic? {
        val tokens = document.tokens
        val blocks = document.blocks
        if (blocks.unclosed.isEmpty()) return null
        var opener = blocks.unclosed.last()
        for (index in 0 until tokens.size) {
            val closer = blocks.closerOf(index)
            if (closer < 0) continue
            if (indentOf(document.source, tokens.start(closer)) < indentOf(document.source, tokens.start(index))) opener = index
        }
        val head = headOf(tokens, opener)
        val closer = if (tokens.matches(opener, "repeat")) "until" else "end"
        return LuaDiagnostic(LuaDiagnosticCode.UNCLOSED_BLOCK, tokens.span(head), tokens.text(head), closer)
    }

    /** The keyword a block's statement starts with: `if` for its `then`, `while` or `for` for its `do`. */
    private fun headOf(tokens: LuaTokens, opener: Int): Int {
        val heads = when {
            tokens.matches(opener, "then") -> setOf("if", "elseif")
            tokens.matches(opener, "do") -> setOf("while", "for")
            else -> return opener
        }
        var at = tokens.prevCode(opener)
        var steps = 0
        while (at >= 0 && steps++ < MAX_HEAD_TOKENS) {
            if (tokens.kind(at) == LuaTokenKind.KEYWORD) {
                val word = tokens.text(at)
                if (word in heads) return at
                if (word !in EXPRESSION_KEYWORDS) break
            }
            at = tokens.prevCode(at)
        }
        return opener
    }

    private val EXPRESSION_KEYWORDS = setOf("and", "or", "not", "nil", "true", "false", "in")

    private fun indentOf(source: String, offset: Int): Int {
        var start = offset
        while (start > 0 && source[start - 1] != '\n' && source[start - 1] != '\r') start--
        var end = start
        while (end < source.length && (source[end] == ' ' || source[end] == '\t')) end++
        return end - start
    }
}
