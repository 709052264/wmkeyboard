package com.wasimaster.wmkeyboard.core.plugins.lua

import com.wasimaster.wmkeyboard.core.plugins.PluginPermission

/** What a name in the plugin API is. */
enum class LuaApiKind { FUNCTION, TABLE, CONSTANT }

/** One name a plugin can reach, by its full dotted path: `print`, `string.format`, `wm.json.decode`. */
data class LuaApiEntry(
    val path: String,
    val kind: LuaApiKind,
    /** How it is called, `decode(text)`, or null for a table or a constant. */
    val signature: String? = null,
    /** Present only when the manifest declares this permission. */
    val permission: PluginPermission? = null,
) {
    /** The last segment of [path]. */
    val name: String
        get() = path.substringAfterLast('.')

    /** The table [path] lives in, or empty for a global. */
    val parent: String
        get() = if ('.' in path) path.substringBeforeLast('.') else ""
}

/**
 * A `ui.*` constructor as the prelude writes it: the named fields it copies from
 * its argument, whether it takes children in the array part, the values a field
 * may take, and the widget type it produces.
 */
data class LuaUiShape(
    val constructor: String,
    val fields: List<String>,
    val takesChildren: Boolean,
    /** Null for `ui.page`, which is part of a tab strip rather than a widget of its own. */
    val widgetType: String?,
    val values: Map<String, List<String>> = emptyMap(),
    /** True for a control whose id is what `on_event` hears. */
    val needsId: Boolean = false,
)

/**
 * Every name a plugin script can reach, as the editor's completion, hover and
 * diagnostics see it.
 *
 * Written by hand, and held true by `LuaApiDriftTest`, which builds a real
 * sandbox and compares each table in it with this list in both directions. The
 * value is not in generating the list but in proving it matches: a name added to
 * the sandbox and not here, or listed here and gone from the sandbox, fails the
 * build.
 */
object LuaApi {

    /** Why a standard Lua name is nil in a plugin. */
    enum class NilReason { LOADS_CODE, FILES, CODE_FROM_TEXT, COROUTINES, JAVA_OR_DEBUGGER }

    /** Standard Lua globals the sandbox removes, and why. */
    val nilled: Map<String, NilReason> = mapOf(
        "require" to NilReason.LOADS_CODE,
        "package" to NilReason.LOADS_CODE,
        "io" to NilReason.FILES,
        "loadfile" to NilReason.FILES,
        "dofile" to NilReason.FILES,
        "load" to NilReason.CODE_FROM_TEXT,
        "loadstring" to NilReason.CODE_FROM_TEXT,
        "coroutine" to NilReason.COROUTINES,
        "luajava" to NilReason.JAVA_OR_DEBUGGER,
        "debug" to NilReason.JAVA_OR_DEBUGGER,
    )

    /** `wm` members that do not exist and never will: the API has no way to read text, files or the network. */
    val never: Set<String> = setOf(
        "text", "clipboard", "http", "net", "field", "insert", "keys", "input", "contacts", "files", "fs", "exec",
    )

    val labelStyles: List<String> = listOf("title", "body", "caption")
    val buttonStyles: List<String> = listOf("primary")
    val eventTypes: List<String> = listOf("click", "toggle", "input_changed", "tab_selected", "date_changed")

    val uiShapes: Map<String, LuaUiShape> = listOf(
        LuaUiShape("column", emptyList(), takesChildren = true, widgetType = "column"),
        LuaUiShape("row", emptyList(), takesChildren = true, widgetType = "row"),
        LuaUiShape("label", listOf("text", "style"), takesChildren = false, widgetType = "label", values = mapOf("style" to labelStyles)),
        LuaUiShape(
            "output", listOf("id", "text", "mono", "insertable", "copyable"), takesChildren = false,
            widgetType = "output",
        ),
        LuaUiShape(
            "button", listOf("id", "text", "style", "enabled"), takesChildren = false, widgetType = "button",
            values = mapOf("style" to buttonStyles), needsId = true,
        ),
        LuaUiShape("toggle", listOf("id", "label", "checked"), takesChildren = false, widgetType = "toggle", needsId = true),
        LuaUiShape("input", listOf("id", "label", "placeholder"), takesChildren = false, widgetType = "input", needsId = true),
        LuaUiShape(
            "date", listOf("id", "label", "value", "time", "step"), takesChildren = false, widgetType = "date",
            needsId = true,
        ),
        LuaUiShape("spacer", listOf("height"), takesChildren = false, widgetType = "spacer"),
        LuaUiShape("divider", emptyList(), takesChildren = false, widgetType = "divider"),
        LuaUiShape("progress", emptyList(), takesChildren = false, widgetType = "progress"),
        LuaUiShape("tabs", listOf("id"), takesChildren = true, widgetType = "tabs"),
        LuaUiShape("page", listOf("title"), takesChildren = true, widgetType = null),
    ).associateBy { it.constructor }

    /** Every `type` string the renderer draws. */
    val widgetTypes: Set<String> = uiShapes.values.mapNotNull { it.widgetType }.toSet()

    val entries: List<LuaApiEntry> = buildList {
        fun function(path: String, signature: String, permission: PluginPermission? = null) =
            add(LuaApiEntry(path, LuaApiKind.FUNCTION, signature, permission))
        fun table(path: String, permission: PluginPermission? = null) = add(LuaApiEntry(path, LuaApiKind.TABLE, permission = permission))
        fun constant(path: String) = add(LuaApiEntry(path, LuaApiKind.CONSTANT))

        // The base library, as the sandbox leaves it.
        table("_G")
        constant("_VERSION")
        function("assert", "assert(value, message)")
        function("error", "error(message, level)")
        function("getmetatable", "getmetatable(value)")
        function("setmetatable", "setmetatable(table, metatable)")
        function("ipairs", "ipairs(list)")
        function("pairs", "pairs(table)")
        function("next", "next(table, key)")
        function("pcall", "pcall(f, ...)")
        function("xpcall", "xpcall(f, handler, ...)")
        function("rawequal", "rawequal(a, b)")
        function("rawget", "rawget(table, key)")
        function("rawlen", "rawlen(value)")
        function("rawset", "rawset(table, key, value)")
        function("select", "select(n, ...)")
        function("tonumber", "tonumber(value, base)")
        function("tostring", "tostring(value)")
        function("type", "type(value)")
        function("print", "print(...)")
        function("collectgarbage", "collectgarbage(...)")
        table("string")
        table("table")
        table("math")
        table("bit32")
        table("os")
        table("ui")
        table("wm")

        for ((name, parameters) in listOf(
            "byte" to "s, i, j", "char" to "...", "dump" to "f", "find" to "s, pattern, init, plain",
            "format" to "format, ...", "gmatch" to "s, pattern", "gsub" to "s, pattern, replacement, n",
            "len" to "s", "lower" to "s", "match" to "s, pattern, init", "rep" to "s, n, separator",
            "reverse" to "s", "sub" to "s, i, j", "upper" to "s",
        )) function("string.$name", "$name($parameters)")

        for ((name, parameters) in listOf(
            "concat" to "list, separator, i, j", "insert" to "list, position, value", "pack" to "...",
            "remove" to "list, position", "sort" to "list, comparator", "unpack" to "list, i, j",
        )) function("table.$name", "$name($parameters)")

        for ((name, parameters) in listOf(
            "abs" to "x", "acos" to "x", "asin" to "x", "atan" to "x", "atan2" to "y, x", "ceil" to "x", "cos" to "x",
            "cosh" to "x", "deg" to "x", "exp" to "x", "floor" to "x", "fmod" to "x, y", "frexp" to "x", "ldexp" to "m, e",
            "log" to "x, base", "max" to "x, ...", "min" to "x, ...", "modf" to "x", "pow" to "x, y", "rad" to "x",
            "random" to "m, n", "randomseed" to "x", "sin" to "x", "sinh" to "x", "sqrt" to "x", "tan" to "x", "tanh" to "x",
        )) function("math.$name", "$name($parameters)")
        constant("math.huge")
        constant("math.pi")

        for ((name, parameters) in listOf(
            "arshift" to "x, shift", "band" to "...", "bnot" to "x", "bor" to "...", "btest" to "...", "bxor" to "...",
            "extract" to "n, field, width", "lrotate" to "x, shift", "lshift" to "x, shift",
            "replace" to "n, value, field, width", "rrotate" to "x, shift", "rshift" to "x, shift",
        )) function("bit32.$name", "$name($parameters)")

        function("os.clock", "clock()")
        function("os.date", "date(format, time)")
        function("os.time", "time(table)")

        constant("wm.api_version")
        constant("wm.plugin_id")
        constant("wm.plugin_version")
        function("wm.log", "log(message)")
        table("wm.json")
        function("wm.json.decode", "decode(text)")
        function("wm.json.encode", "encode(value)")
        table("wm.ui")
        function("wm.ui.set_input", "set_input(id, text)")
        table("wm.storage", PluginPermission.Storage)
        function("wm.storage.get", "get(key)", PluginPermission.Storage)
        function("wm.storage.set", "set(key, value)", PluginPermission.Storage)
        function("wm.storage.remove", "remove(key)", PluginPermission.Storage)
        function("wm.storage.keys", "keys()", PluginPermission.Storage)
        table("wm.reminders", PluginPermission.Reminders)
        function("wm.reminders.set", "set(id, time, text)", PluginPermission.Reminders)
        function("wm.reminders.cancel", "cancel(id)", PluginPermission.Reminders)
        function("wm.reminders.list", "list()", PluginPermission.Reminders)

        for (shape in uiShapes.values) {
            val signature = when {
                shape.fields.isEmpty() && !shape.takesChildren -> "${shape.constructor}()"
                shape.takesChildren && shape.fields.isEmpty() -> "${shape.constructor} { ... }"
                shape.takesChildren -> "${shape.constructor} { ${shape.fields.joinToString { "$it = ..." }}, ... }"
                else -> "${shape.constructor} { ${shape.fields.joinToString { "$it = ..." }} }"
            }
            function("ui.${shape.constructor}", signature)
        }
    }

    private val byPath: Map<String, LuaApiEntry> = entries.associateBy { it.path }
    private val byParent: Map<String, List<LuaApiEntry>> = entries.groupBy { it.parent }

    fun find(path: String): LuaApiEntry? = byPath[path]

    /** The names directly inside [parent], or the globals for an empty one. */
    fun children(parent: String): List<LuaApiEntry> = byParent[parent].orEmpty()
}
