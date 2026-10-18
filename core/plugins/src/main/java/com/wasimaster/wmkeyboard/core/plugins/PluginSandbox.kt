package com.wasimaster.wmkeyboard.core.plugins

import org.luaj.vm2.Globals
import org.luaj.vm2.LuaString
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.compiler.LuaC
import org.luaj.vm2.lib.BaseLib
import org.luaj.vm2.lib.Bit32Lib
import org.luaj.vm2.lib.DebugLib
import org.luaj.vm2.lib.ResourceFinder
import org.luaj.vm2.lib.StringLib
import org.luaj.vm2.lib.TableLib
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JseMathLib
import java.io.OutputStream
import java.io.PrintStream
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * Builds the locked-down Lua environment a plugin script runs inside.
 *
 * The rule this file exists to enforce: **a plugin can compute and it can draw,
 * and it can do nothing else.** It has no filesystem, no network, no reflection,
 * no class loading, no Android, no way to load code it did not ship with, and no
 * way to outlive or outlast the panel it is drawn in. Anything a plugin is
 * allowed to touch arrives through the `wm` table the host installs, and every
 * value crossing that boundary is a Lua string, number, boolean or table — never
 * a Java object.
 *
 * ## What is installed, and what is not
 *
 * In: [BaseLib], [Bit32Lib], [TableLib], [StringLib], [JseMathLib], and the
 * [LuaC] compiler. Out, each for a specific reason:
 *
 *  * `PackageLib` — `require` resolves names through `Class.forName` and calls
 *    the constructor, which is a direct route from a script to arbitrary JVM
 *    classes. This is the single most important omission in the file.
 *  * `CoroutineLib` — luaj implements coroutines as real, non-daemon Java
 *    threads, and reclaims them only when they yield. A script that spawns
 *    coroutines which never yield leaks OS threads that outlive the keyboard.
 *  * `IoLib`, `OsLib` — files, processes, environment. `os` is replaced by
 *    [safeOs], which can tell the time and nothing else.
 *  * `LuajavaLib` — direct Java interop; the whole sandbox in one library.
 *    Never referenced anywhere in the app, so R8 strips it from the APK
 *    entirely, which is a nice property to have on top of not installing it.
 *  * `DebugLib` — installed, because the interpreter's per-instruction hook
 *    lives on it, but removed from the globals table immediately afterwards so
 *    no script can reach it.
 *
 * ## How a runaway script is stopped
 *
 * The interpreter calls `globals.debuglib.onInstruction` before every bytecode
 * whenever `debuglib` is non-null — no coroutine, no `debug.sethook`, nothing
 * the script can see or unset. [SandboxDebugLib] spends that call on
 * [PluginBudget.onInstruction], which throws [PluginAbort] once the script is
 * out of instructions, out of time, or cancelled. [PluginAbort] extends `Error`,
 * which luaj's `pcall` does not catch, so a script cannot survive its own
 * termination.
 *
 * The gap, stated plainly: luaj's pattern matcher runs in Java between
 * instructions, so a pathological `string.gsub` burns wall-clock where the hook
 * never fires. [guardStringTable] shrinks the inputs that can reach it; the
 * runtime's watchdog is what actually contains it.
 *
 * ## Why the string metatable is frozen
 *
 * `LuaString.s_metatable` is a **process-global static**, and luaj's `StringLib`
 * populates it with the `string` table of whichever `Globals` happened to be
 * built first. Left alone, `getmetatable("").__index.rep = evil` in one plugin
 * would rewrite `("x"):rep(n)` for every other plugin in the process, and for
 * every sandbox built afterwards. [installStringGuards] replaces it with a
 * frozen table that refuses writes and hides itself behind `__metatable`.
 */
object PluginSandbox {

    /** Cap on strings produced by the amplifying `string` functions. */
    const val MAX_STRING_BYTES = 256 * 1024

    /** Patterns longer than this are refusals, not features. */
    const val MAX_PATTERN_BYTES = 256

    /** Cap on one `table.concat` result. */
    const val MAX_CONCAT_BYTES = 1024 * 1024

    /** One `print` line, after which the rest is dropped. */
    const val MAX_PRINT_CHARS = 512

    /**
     * Builds a fresh environment. One per plugin per panel session, discarded
     * when the panel closes or the plugin is stopped.
     *
     * [print] receives anything the script prints, for the plugin's own log.
     */
    fun create(budget: PluginBudget, print: (String) -> Unit): Globals {
        val globals = Globals()

        installDecoyPackage(globals)

        // Plain BaseLib, not JseBaseLib: the latter's resource finder reads the
        // filesystem. This one reads the classpath, which is neutered below.
        globals.load(BaseLib())
        globals.load(Bit32Lib())
        globals.load(TableLib())
        globals.load(StringLib())
        globals.load(JseMathLib())
        LuaC.install(globals)

        // Sets globals.debuglib, which is what makes the interpreter call the
        // hook; then the table itself goes, so no script can see it.
        globals.load(SandboxDebugLib(budget))
        globals.set("debug", LuaValue.NIL)

        // No undumper means no precompiled chunk can ever be loaded, by us or by
        // anything else. Left null deliberately -- LoadState.install() is the
        // call that would set it, and it is never made.
        globals.undumper = null

        globals.finder = ResourceFinder { null }
        globals.STDOUT = NULL_PRINT_STREAM
        globals.STDERR = NULL_PRINT_STREAM
        globals.STDIN = null

        // Every route from a string to running code.
        globals.set("load", LuaValue.NIL)
        globals.set("loadstring", LuaValue.NIL)
        globals.set("loadfile", LuaValue.NIL)
        globals.set("dofile", LuaValue.NIL)
        globals.set("require", LuaValue.NIL)
        globals.set("package", LuaValue.NIL)
        globals.set("io", LuaValue.NIL)
        globals.set("luajava", LuaValue.NIL)
        globals.set("coroutine", LuaValue.NIL)

        globals.set("collectgarbage", CollectGarbageStub)
        globals.set("print", PrintFunction(print))
        globals.set("os", safeOs())

        guardStringTable(globals.get("string"))
        guardTableLib(globals.get("table"))
        installStringGuards()
        return globals
    }

    /**
     * Compiles [source] in [globals]. Source only — there is no undumper, so a
     * byte array that looks like a compiled chunk cannot get in this way either.
     */
    fun compile(globals: Globals, source: String, chunkName: String = "@main.lua"): LuaValue =
        globals.load(source, chunkName)

    /**
     * Absorbs the `package.loaded[...] = lib` writes the standard libraries make
     * as they install.
     *
     * Several of them — `Bit32Lib` and `StringLib` among them — do it without
     * checking whether `package` exists first, so loading them into a
     * PackageLib-less environment throws before the sandbox is even built.
     * Installing the real `PackageLib` to satisfy them is not an option: its
     * `require` resolves module names through `Class.forName` and instantiates
     * what it finds, which is a straight line from a script to arbitrary JVM
     * classes. This decoy is a plain table with nowhere to go, and [create]
     * removes it once the libraries are in.
     */
    private fun installDecoyPackage(globals: Globals) {
        val decoy = LuaTable()
        decoy.set("loaded", LuaTable())
        decoy.set("preload", LuaTable())
        globals.set("package", decoy)
    }

    // ---- the instruction hook ------------------------------------------

    /**
     * The per-instruction hook. Kept as thin as it looks: the interpreter calls
     * this before every bytecode, so anything expensive here is a tax on every
     * plugin.
     *
     * `super.onInstruction` still runs so the call stack stays accurate and Lua
     * errors keep their line numbers; luaj short-circuits it cheaply when no
     * Lua-level hook function is set, which is always the case here.
     */
    private class SandboxDebugLib(private val budget: PluginBudget) : DebugLib() {
        override fun onInstruction(pc: Int, v: Varargs, top: Int) {
            budget.onInstruction()
            super.onInstruction(pc, v, top)
        }
    }

    // ---- replacements for what was removed ------------------------------

    /** `collectgarbage` answers plausibly and does nothing; no `System.gc`, no heap probing. */
    private object CollectGarbageStub : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs = LuaValue.ZERO
    }

    private class PrintFunction(private val sink: (String) -> Unit) : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val line = buildString {
                for (i in 1..args.narg()) {
                    if (i > 1) append('\t')
                    append(args.arg(i).tojstring())
                    if (length > MAX_PRINT_CHARS) break
                }
            }
            sink(line.take(MAX_PRINT_CHARS))
            return LuaValue.NONE
        }
    }

    /**
     * `os`, with everything that touches the machine removed: no `getenv`, no
     * `execute`, no `exit`, no `remove`, no `rename`, no `tmpname`.
     */
    private fun safeOs(): LuaTable {
        val started = System.nanoTime()
        val os = LuaTable()
        os.set("time", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val fields = args.arg1()
                if (fields.istable()) return timeOf(fields.checktable())
                return LuaValue.valueOf((System.currentTimeMillis() / MILLIS_PER_SECOND).toDouble())
            }
        })
        os.set("clock", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs =
                LuaValue.valueOf((System.nanoTime() - started).toDouble() / NANOS_PER_SECOND)
        })
        os.set("date", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val format = args.optjstring(1, "%c").take(MAX_DATE_FORMAT)
                val seconds = if (args.isnumber(2)) args.checklong(2) else null
                return formatDate(format, seconds)
            }
        })
        return os
    }

    private fun formatDate(format: String, seconds: Long?): Varargs {
        val utc = format.startsWith("!")
        val spec = if (utc) format.substring(1) else format
        val calendar = if (utc) {
            Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT)
        } else {
            Calendar.getInstance(Locale.ROOT)
        }
        if (seconds != null) calendar.timeInMillis = seconds * MILLIS_PER_SECOND
        if (spec == "*t") return dateTable(calendar)
        return LuaValue.valueOf(strftime(spec, calendar))
    }

    /**
     * `os.time { year = 2026, month = 10, day = 20, hour = 9 }`: that moment in
     * the phone's time zone, as standard Lua reads the table. `hour` defaults to
     * 12, `min` and `sec` to 0, and a field past its range rolls into the next
     * one, so `day = d + 1` on the last day of a month is the first of the next.
     * That roll-over is what makes "tomorrow" and "in a week" one line of Lua.
     */
    private fun timeOf(fields: LuaTable): Varargs {
        fun field(name: String, default: Int?): Int {
            val value = fields.get(name)
            if (value.isnil() && default != null) return default
            if (!value.isnumber()) LuaValue.error("field '$name' missing in date table")
            val number = value.todouble()
            if (!number.isFinite() || kotlin.math.abs(number) > MAX_DATE_FIELD) {
                LuaValue.error("field '$name' is out of range")
            }
            return number.toInt()
        }
        val calendar = Calendar.getInstance(Locale.ROOT)
        calendar.isLenient = true
        calendar.clear()
        calendar.set(
            field("year", null),
            field("month", null) - 1,
            field("day", null),
            field("hour", NOON),
            field("min", 0),
            field("sec", 0),
        )
        return LuaValue.valueOf((calendar.timeInMillis / MILLIS_PER_SECOND).toDouble())
    }

    private fun dateTable(calendar: Calendar): LuaTable {
        val table = LuaTable()
        table.set("year", calendar.get(Calendar.YEAR))
        table.set("month", calendar.get(Calendar.MONTH) + 1)
        table.set("day", calendar.get(Calendar.DAY_OF_MONTH))
        table.set("hour", calendar.get(Calendar.HOUR_OF_DAY))
        table.set("min", calendar.get(Calendar.MINUTE))
        table.set("sec", calendar.get(Calendar.SECOND))
        table.set("wday", calendar.get(Calendar.DAY_OF_WEEK))
        table.set("yday", calendar.get(Calendar.DAY_OF_YEAR))
        table.set("isdst", LuaValue.FALSE)
        return table
    }

    /** A small, fixed strftime subset. Unknown specifiers are left as written. */
    @Suppress("CyclomaticComplexMethod")
    private fun strftime(spec: String, calendar: Calendar): String {
        val out = StringBuilder()
        var i = 0
        while (i < spec.length) {
            val c = spec[i]
            if (c != '%' || i == spec.lastIndex) {
                out.append(c)
                i++
                continue
            }
            i++
            when (spec[i]) {
                'Y' -> out.append(calendar.get(Calendar.YEAR))
                'y' -> out.append(pad(calendar.get(Calendar.YEAR) % 100))
                'm' -> out.append(pad(calendar.get(Calendar.MONTH) + 1))
                'd' -> out.append(pad(calendar.get(Calendar.DAY_OF_MONTH)))
                'H' -> out.append(pad(calendar.get(Calendar.HOUR_OF_DAY)))
                'M' -> out.append(pad(calendar.get(Calendar.MINUTE)))
                'S' -> out.append(pad(calendar.get(Calendar.SECOND)))
                'j' -> out.append(calendar.get(Calendar.DAY_OF_YEAR))
                'p' -> out.append(if (calendar.get(Calendar.AM_PM) == Calendar.AM) "AM" else "PM")
                'A' -> out.append(WEEKDAYS[calendar.get(Calendar.DAY_OF_WEEK) - 1])
                'a' -> out.append(WEEKDAYS[calendar.get(Calendar.DAY_OF_WEEK) - 1].take(3))
                'B' -> out.append(MONTHS[calendar.get(Calendar.MONTH)])
                'b' -> out.append(MONTHS[calendar.get(Calendar.MONTH)].take(3))
                'x' -> out.append(strftime("%Y-%m-%d", calendar))
                'X' -> out.append(strftime("%H:%M:%S", calendar))
                'c' -> out.append(strftime("%a %b %d %H:%M:%S %Y", calendar))
                '%' -> out.append('%')
                else -> out.append('%').append(spec[i])
            }
            i++
        }
        return out.toString()
    }

    private fun pad(value: Int): String = if (value < 10) "0$value" else value.toString()

    // ---- amplifier guards ------------------------------------------------

    /**
     * Caps the `string` functions that turn a small script into a large
     * allocation or a long Java-side loop.
     *
     * `string.rep` is the direct one — luaj allocates `length * n` bytes in a
     * single call, and the multiply can overflow an `int` on the way. The
     * pattern functions are the indirect one: their matcher backtracks, and it
     * runs in Java where the instruction hook cannot interrupt it. Bounding the
     * subject and the pattern does not make backtracking linear, it just makes
     * the accidental cases small; the watchdog handles the deliberate ones.
     */
    private fun guardStringTable(string: LuaValue) {
        if (!string.istable()) return
        val table = string.checktable()
        table.set("rep", RepGuard(table.get("rep")))
        for (name in PATTERN_FUNCTIONS) {
            val original = table.get(name)
            if (original.isfunction()) table.set(name, PatternGuard(original))
        }
    }

    private fun guardTableLib(tableLib: LuaValue) {
        if (!tableLib.istable()) return
        val table = tableLib.checktable()
        val original = table.get("concat")
        if (original.isfunction()) table.set("concat", ConcatGuard(original))
    }

    private class RepGuard(private val delegate: LuaValue) : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val source = args.arg(1)
            val count = args.optint(2, 0)
            if (source.isstring() && count > 0) {
                val separator = args.optjstring(3, "").length.toLong()
                val length = (source.checkjstring().length.toLong() + separator) * count.toLong()
                if (length > MAX_STRING_BYTES) {
                    LuaValue.error("string.rep would build a string over ${MAX_STRING_BYTES / 1024} KB")
                }
            }
            return delegate.invoke(args)
        }
    }

    private class PatternGuard(private val delegate: LuaValue) : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val subject = args.arg(1)
            if (subject.isstring() && subject.checkjstring().length > MAX_STRING_BYTES) {
                LuaValue.error("that string is too long to search")
            }
            val pattern = args.arg(2)
            if (pattern.isstring() && pattern.checkjstring().length > MAX_PATTERN_BYTES) {
                LuaValue.error("that pattern is too long")
            }
            return delegate.invoke(args)
        }
    }

    private class ConcatGuard(private val delegate: LuaValue) : VarArgFunction() {
        override fun invoke(args: Varargs): Varargs {
            val list = args.arg(1)
            if (list.istable()) {
                val table = list.checktable()
                var total = 0L
                var i = 1
                while (i <= table.length()) {
                    val value = table.get(i)
                    if (value.isstring()) total += value.checkjstring().length
                    if (total > MAX_CONCAT_BYTES) {
                        LuaValue.error("table.concat would build a string over ${MAX_CONCAT_BYTES / 1024} KB")
                    }
                    i++
                }
            }
            return delegate.invoke(args)
        }
    }

    // ---- the process-global string metatable -----------------------------

    private val guardLock = Any()

    @Volatile
    private var frozenStringIndex: LuaValue? = null

    /**
     * Replaces the process-global string metatable with one that cannot be read
     * or written from Lua.
     *
     * `__metatable` is what hides it: luaj's `getmetatable` returns that field
     * when present rather than the table itself, so a script asking for a
     * string's metatable gets the word "protected" and no reference at all. The
     * write refusal in [ReadOnlyLuaTable] is the belt to that braces.
     *
     * Called on every [create] rather than once, so that a metatable somehow
     * replaced by a previous run is put back before the next plugin starts.
     */
    private fun installStringGuards() {
        val index = sharedStringIndex()
        val metatable = ReadOnlyLuaTable()
        metatable.rawset(LuaValue.INDEX, index)
        metatable.rawset(LuaValue.METATABLE, LuaValue.valueOf("protected"))
        metatable.seal()
        LuaString.s_metatable = metatable
    }

    /**
     * The frozen `string` table every Lua string resolves its methods through,
     * built once per process from a scratch environment and guarded the same way
     * a plugin's own `string` table is.
     */
    private fun sharedStringIndex(): LuaValue {
        frozenStringIndex?.let { return it }
        return synchronized(guardLock) {
            frozenStringIndex ?: buildFrozenStringIndex().also { frozenStringIndex = it }
        }
    }

    private fun buildFrozenStringIndex(): LuaValue {
        val scratch = Globals()
        installDecoyPackage(scratch)
        scratch.load(StringLib())
        val source = scratch.get("string")
        guardStringTable(source)
        val frozen = ReadOnlyLuaTable()
        if (source.istable()) {
            val table = source.checktable()
            var key: LuaValue = LuaValue.NIL
            while (true) {
                val entry = table.next(key)
                val entryKey = entry.arg1()
                if (entryKey.isnil()) break
                frozen.rawset(entryKey, entry.arg(2))
                key = entryKey
            }
        }
        frozen.seal()
        return frozen
    }

    /**
     * A table that refuses every write once sealed.
     *
     * Both `rawset` overloads are covered because every other write path in
     * luaj — `set`, `rawset(String, ...)`, `table.insert`, `table.sort` — funnels
     * into one of them.
     */
    private class ReadOnlyLuaTable : LuaTable() {
        private var sealed = false

        fun seal() {
            sealed = true
        }

        override fun rawset(key: Int, value: LuaValue) {
            if (sealed) LuaValue.error(PROTECTED)
            super.rawset(key, value)
        }

        override fun rawset(key: LuaValue, value: LuaValue) {
            if (sealed) LuaValue.error(PROTECTED)
            super.rawset(key, value)
        }
    }

    // ---- constants -------------------------------------------------------

    private const val PROTECTED = "attempt to modify a protected table"
    private const val MILLIS_PER_SECOND = 1000L
    private const val NANOS_PER_SECOND = 1_000_000_000.0
    private const val MAX_DATE_FORMAT = 64
    private const val NOON = 12

    /** Far past any real date field; keeps every sum inside an Int. */
    private const val MAX_DATE_FIELD = 1_000_000.0

    private val PATTERN_FUNCTIONS = arrayOf("find", "match", "gmatch", "gsub")

    private val WEEKDAYS = arrayOf(
        "Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday",
    )

    private val MONTHS = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    /** Anything the interpreter tries to write goes nowhere. */
    private val NULL_PRINT_STREAM = PrintStream(
        object : OutputStream() {
            override fun write(b: Int) = Unit
            override fun write(b: ByteArray, off: Int, len: Int) = Unit
        },
    )
}
