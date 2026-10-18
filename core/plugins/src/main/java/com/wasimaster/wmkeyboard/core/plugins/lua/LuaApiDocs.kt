package com.wasimaster.wmkeyboard.core.plugins.lua

/**
 * One short paragraph for each name in [LuaApi], shown when the plugin editor
 * explains the name under the caret.
 *
 * Kept in Kotlin rather than in string resources: this is documentation of an
 * English programming API, sitting beside identifiers that are never translated,
 * and a hundred paragraphs in the translation pipeline would cost far more than
 * they give. It says what `docs/.../plugins/api-reference.mdx` says, and
 * `LuaApiDriftTest` checks that every name has a paragraph and that the reference
 * names nothing the API does not have.
 */
object LuaApiDocs {

    fun of(path: String): String? = docs[path]

    /** Every path that has a paragraph. */
    val paths: Set<String>
        get() = docs.keys

    private val docs: Map<String, String> = mapOf(
        "_G" to "The table of global variables. Reading _G.name is the same as reading name.",
        "_VERSION" to "The Lua version, as text.",
        "assert" to "Raises an error with message when value is false or nil. Returns all its arguments otherwise.",
        "error" to "Stops the current call with message. The plugin stays loaded and the message goes to the log.",
        "getmetatable" to "The metatable of value, or nil. For a string it is the text \"protected\", because the string metatable is frozen.",
        "setmetatable" to "Sets the metatable of table and returns table.",
        "ipairs" to "Walks a list in order: for i, value in ipairs(list) do ... end. Stops at the first nil.",
        "pairs" to "Walks every key of a table in no fixed order: for key, value in pairs(t) do ... end.",
        "next" to "The key after key in table, and its value. Nil at the end.",
        "pcall" to "Calls f with the arguments and catches an error. Returns true and the results, or false and the error.",
        "xpcall" to "Like pcall, but passes an error to handler first.",
        "rawequal" to "Whether a and b are the same value, without metamethods.",
        "rawget" to "table[key], without metamethods.",
        "rawlen" to "The length of a table or string, without metamethods.",
        "rawset" to "Sets table[key] = value, without metamethods.",
        "select" to "With a number, the arguments from position n. With \"#\", how many arguments there are.",
        "tonumber" to "value as a number, or nil when it is not one. base reads text in another base, from 2 to 36.",
        "tostring" to "value as text.",
        "type" to "The type of value as text: \"nil\", \"number\", \"string\", \"boolean\", \"table\" or \"function\".",
        "print" to "Writes its arguments as one line of the plugin log. The plugin editor shows it in the console.",
        "collectgarbage" to "Does nothing and returns 0. Memory is managed for the plugin.",
        "string" to "Text functions. Also reachable as methods: s:upper() is string.upper(s).",
        "table" to "Functions for lists and tables.",
        "math" to "Mathematical functions and constants.",
        "bit32" to "Bitwise operations on 32-bit numbers.",
        "os" to "A reduced os library: time, clock and date only.",
        "ui" to "Builds the widgets render() returns. Each function returns a plain table; you can write the table yourself.",
        "wm" to "The whole host API: log, json, ui and, with their permissions, storage and reminders. There is no way to read text, the clipboard, files or the network.",

        "string.byte" to "The character codes of s from i to j.",
        "string.char" to "A string made of the given character codes.",
        "string.dump" to "Always fails in a plugin: compiled Lua cannot be loaded, so there is nothing to dump to.",
        "string.find" to "Where pattern first matches in s, from init: start and end positions, then any captures. plain = true searches for the text itself.",
        "string.format" to "Text built from a format and values, like C printf: %d, %s, %5.2f.",
        "string.gmatch" to "An iterator over each match of pattern in s: for word in s:gmatch(\"%a+\") do ... end.",
        "string.gsub" to "s with each match of pattern replaced, and how many were replaced. replacement can be text, a table or a function.",
        "string.len" to "The length of s in bytes. Use #s for the same thing.",
        "string.lower" to "s in small letters.",
        "string.match" to "The captures of the first match of pattern in s, or the whole match when there are none.",
        "string.rep" to "s repeated n times, with separator between. Stops with an error above 256 KB.",
        "string.reverse" to "s back to front, byte by byte.",
        "string.sub" to "The part of s from i to j. Negative positions count from the end.",
        "string.upper" to "s in capital letters.",

        "table.concat" to "The items of list joined into one string with separator. Stops with an error above 1 MB.",
        "table.insert" to "Adds value to list, at the end or at position.",
        "table.pack" to "The arguments in a new table, with n set to how many there are.",
        "table.remove" to "Removes and returns the item at position, or the last item.",
        "table.sort" to "Sorts list in place, with comparator(a, b) returning true when a comes first.",
        "table.unpack" to "The items of list as separate values. In Lua 5.2 there is no global unpack.",

        "math.abs" to "The absolute value of x.",
        "math.acos" to "The arc cosine of x, in radians.",
        "math.asin" to "The arc sine of x, in radians.",
        "math.atan" to "The arc tangent of x, in radians.",
        "math.atan2" to "The arc tangent of y / x, in radians, using the signs of both to find the quadrant.",
        "math.ceil" to "The smallest whole number not less than x.",
        "math.cos" to "The cosine of x, in radians.",
        "math.cosh" to "The hyperbolic cosine of x.",
        "math.deg" to "x radians in degrees.",
        "math.exp" to "e to the power x.",
        "math.floor" to "The largest whole number not greater than x.",
        "math.fmod" to "The remainder of x / y, with the sign of x.",
        "math.frexp" to "m and e such that x = m * 2 ^ e.",
        "math.huge" to "A number larger than any other.",
        "math.ldexp" to "m * 2 ^ e.",
        "math.log" to "The logarithm of x, natural or in base. There is no math.log10; use math.log(x, 10).",
        "math.max" to "The largest of its arguments.",
        "math.min" to "The smallest of its arguments.",
        "math.modf" to "The whole and fractional parts of x.",
        "math.pi" to "The number pi.",
        "math.pow" to "x to the power y. The same as x ^ y.",
        "math.rad" to "x degrees in radians.",
        "math.random" to "A random number: from 0 to 1 with no arguments, from 1 to m, or from m to n.",
        "math.randomseed" to "Sets the seed of math.random.",
        "math.sin" to "The sine of x, in radians.",
        "math.sinh" to "The hyperbolic sine of x.",
        "math.sqrt" to "The square root of x.",
        "math.tan" to "The tangent of x, in radians.",
        "math.tanh" to "The hyperbolic tangent of x.",

        "bit32.arshift" to "x shifted right by shift, keeping the sign bit.",
        "bit32.band" to "The bitwise and of its arguments.",
        "bit32.bnot" to "The bitwise not of x.",
        "bit32.bor" to "The bitwise or of its arguments.",
        "bit32.btest" to "Whether the bitwise and of its arguments is not zero.",
        "bit32.bxor" to "The bitwise exclusive or of its arguments.",
        "bit32.extract" to "The bits of n from field, width bits wide.",
        "bit32.lrotate" to "x rotated left by shift.",
        "bit32.lshift" to "x shifted left by shift.",
        "bit32.replace" to "n with the bits from field, width bits wide, set to value.",
        "bit32.rrotate" to "x rotated right by shift.",
        "bit32.rshift" to "x shifted right by shift.",

        "os.clock" to "Seconds since this plugin session started.",
        "os.date" to "The date as text, or as a table with \"*t\". Understands %Y %y %m %d %H %M %S %j %p %A %a %B %b %c %x %X and %%, and a leading ! for UTC.",
        "os.time" to "The time now, in seconds. Given a table { year, month, day, hour, min, sec }, that moment in the phone's time zone instead; hour is 12 when not given, and a field past its range rolls over, so day = 32 is the next month.",

        "wm.api_version" to "The API level this keyboard gives plugins. Now 2.",
        "wm.plugin_id" to "The id from your manifest.",
        "wm.plugin_version" to "The pluginVersion from your manifest.",
        "wm.log" to "Writes message as one line of your plugin log, readable in Settings and in the editor console.",
        "wm.json" to "JSON to and from Lua tables.",
        "wm.json.decode" to "text read as JSON: a table, text, number or boolean. On failure, nil and a reason.",
        "wm.json.encode" to "value written as JSON text. A table with keys 1 to n becomes an array. On failure, nil and a reason.",
        "wm.ui" to "Writes to your own widgets.",
        "wm.ui.set_input" to "Puts text in your input widget id. Applied after your handler returns. Up to 8 KB.",
        "wm.storage" to "Text saved on this device for your plugin, kept across sessions and deleted on uninstall. Only there when the manifest declares the storage permission; otherwise nil.",
        "wm.storage.get" to "The text saved under key, or nil.",
        "wm.storage.set" to "Saves value under key. Returns true, or nil and a reason when over the quota: 128 keys, 64 characters a key, 8 KB a value, 64 KB in all.",
        "wm.storage.remove" to "Deletes key.",
        "wm.storage.keys" to "A list of every saved key.",
        "wm.reminders" to "Reminders the app shows in a notification at a time you set, with your plugin's name as the title. Only there when the manifest declares the reminders permission; otherwise nil.",
        "wm.reminders.set" to "Shows text at time, in seconds as os.time gives, setting or replacing the reminder id. Returns true, or nil and a reason: a time already past, no text, or over 64 reminders. Text up to 200 characters.",
        "wm.reminders.cancel" to "Cancels the reminder id. Returns true when there was one.",
        "wm.reminders.list" to "Every reminder not shown yet, soonest first: a list of { id, time, text }. A reminder is gone from the list once it is shown.",

        "ui.column" to "Widgets stacked top to bottom. Put the children in the table: ui.column { ui.label { text = \"a\" }, ui.label { text = \"b\" } }.",
        "ui.row" to "Widgets side by side, sharing the width equally.",
        "ui.label" to "Text. style is \"title\", \"body\" or \"caption\"; body is the default.",
        "ui.output" to "A result, with the keyboard's own Insert and Copy buttons under it. Insert is the only way your result reaches the user's text. insertable and copyable are on unless set to false; mono draws the text monospaced.",
        "ui.button" to "A button. on_event hears { type = \"click\", id = id }. style = \"primary\" highlights it; enabled = false greys it out.",
        "ui.toggle" to "A switch. on_event hears { type = \"toggle\", id = id, value = true or false }.",
        "ui.input" to "A text box the keyboard owns. on_event hears { type = \"input_changed\", id = id, value = text } after each change. Write to it with wm.ui.set_input.",
        "ui.date" to "A date the user picks with the keyboard's own buttons. value is seconds as os.time gives, or nil for none yet. time = true picks a time of day too; step is the minutes the smaller time buttons move, 15 when not given. on_event hears { type = \"date_changed\", id = id, value = seconds or nil }.",
        "ui.spacer" to "Empty space, height from 0 to 64. 8 when not given.",
        "ui.divider" to "A line across the panel.",
        "ui.progress" to "An indeterminate bar that shows something is happening.",
        "ui.tabs" to "A tab strip, top level only, up to 8 pages. Put ui.page tables in it. on_event hears { type = \"tab_selected\", id = id, index = n }, counting from 0.",
        "ui.page" to "One page of ui.tabs: a title, and its widgets in the table.",
    )
}
