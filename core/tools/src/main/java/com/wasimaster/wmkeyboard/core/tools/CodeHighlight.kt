package com.wasimaster.wmkeyboard.core.tools

/**
 * Colours for a fenced code block: comments, strings, numbers, keywords and
 * type names. One tokenizer for every language, told apart only by which
 * comment markers they use and a shared keyword list, because a note or a chat
 * answer needs code to be readable at a glance, not an IDE's precision.
 *
 * No Compose, like [AiMarkdownBlocks]: the caller maps [Kind] to colours.
 */
object CodeHighlight {

    enum class Kind { COMMENT, STRING, NUMBER, KEYWORD, TYPE }

    /** [kind] over `[start, end)` of the code. */
    data class Token(val start: Int, val end: Int, val kind: Kind)

    private val HASH_COMMENTS = setOf(
        "python", "py", "ruby", "rb", "sh", "bash", "zsh", "shell", "console", "yaml", "yml", "toml",
        "perl", "pl", "r", "php", "make", "makefile", "dockerfile", "powershell", "ps1", "nix", "conf", "ini",
    )
    private val DASH_COMMENTS = setOf("sql", "lua", "haskell", "hs", "elm", "ada")
    private val SLASH_COMMENTS_OFF = setOf(
        "python", "py", "ruby", "rb", "sh", "bash", "zsh", "shell", "console", "yaml", "yml", "toml",
        "sql", "lua", "haskell", "hs", "html", "xml", "svg", "markdown", "md", "ini", "conf",
    )
    private val MARKUP = setOf("html", "xml", "svg", "vue", "markdown", "md")

    private val KEYWORDS = (
        "abstract and as assert async await break case catch class const continue def default defer del delete do " +
            "elif else enum except export extends extern false final finally fn for foreach from func function get go " +
            "goto if impl implements import in instanceof interface is lambda let loop match mod module mut namespace " +
            "new nil none not null object of or override package pass private protected pub public raise readonly " +
            "record ref require require_once return sealed select self set static struct super switch then this " +
            "throw throws trait true try type typeof union unsafe use using val var void when where while with yield " +
            "int long short byte char float double bool boolean string str echo local end begin elsif unless until " +
            "fun data inline internal open lateinit suspend companion operator infix reified crossinline noinline " +
            "True False None"
        ).split(' ').toHashSet()

    fun tokens(code: String, language: String): List<Token> {
        val lang = language.lowercase()
        val hash = lang in HASH_COMMENTS
        val dash = lang in DASH_COMMENTS
        val slash = lang !in SLASH_COMMENTS_OFF
        val markup = lang in MARKUP
        val css = lang == "css" || lang == "scss" || lang == "less"
        val out = ArrayList<Token>()
        var i = 0
        val n = code.length
        while (i < n) {
            val c = code[i]
            val start = i
            when {
                slash && c == '/' && i + 1 < n && code[i + 1] == '/' && !css -> {
                    i = code.indexOf('\n', i).let { if (it < 0) n else it }
                    out += Token(start, i, Kind.COMMENT)
                }
                (slash || css) && c == '/' && i + 1 < n && code[i + 1] == '*' -> {
                    i = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    out += Token(start, i, Kind.COMMENT)
                }
                markup && code.startsWith("<!--", i) -> {
                    i = code.indexOf("-->", i + 4).let { if (it < 0) n else it + 3 }
                    out += Token(start, i, Kind.COMMENT)
                }
                hash && c == '#' && (i == 0 || code[i - 1].isWhitespace()) -> {
                    i = code.indexOf('\n', i).let { if (it < 0) n else it }
                    out += Token(start, i, Kind.COMMENT)
                }
                dash && c == '-' && i + 1 < n && code[i + 1] == '-' -> {
                    i = code.indexOf('\n', i).let { if (it < 0) n else it }
                    out += Token(start, i, Kind.COMMENT)
                }
                c == '"' || c == '\'' || c == '`' -> {
                    // An apostrophe in prose-like markup is not a string.
                    if (c == '\'' && markup) {
                        i++
                        continue
                    }
                    i++
                    while (i < n && code[i] != c) {
                        if (code[i] == '\\') i++
                        // Only backticks (template strings) run across lines.
                        if (i < n && code[i] == '\n' && c != '`') break
                        i++
                    }
                    if (i < n && code[i] == c) i++
                    out += Token(start, i, Kind.STRING)
                }
                c.isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit() && code[i - 1] != '_') -> {
                    i++
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '.' || code[i] == '_')) i++
                    out += Token(start, i, Kind.NUMBER)
                }
                c.isLetter() || c == '_' || c == '$' || c == '@' -> {
                    i++
                    while (i < n && (code[i].isLetterOrDigit() || code[i] == '_')) i++
                    val word = code.substring(start, i)
                    when {
                        word in KEYWORDS -> out += Token(start, i, Kind.KEYWORD)
                        word[0] == '@' -> out += Token(start, i, Kind.TYPE)
                        word[0].isUpperCase() && word.length > 1 && !css -> out += Token(start, i, Kind.TYPE)
                    }
                }
                else -> i++
            }
        }
        return out
    }
}
