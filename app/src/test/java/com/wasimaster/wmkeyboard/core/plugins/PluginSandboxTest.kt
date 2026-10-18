package com.wasimaster.wmkeyboard.core.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaValue

/**
 * The sandbox's guarantees, stated as tests.
 *
 * Everything here is a claim the plugin documentation makes to users about what
 * a plugin can never do, so a failure in this file is a broken promise rather
 * than a broken feature.
 */
class PluginSandboxTest {

    private val printed = ArrayList<String>()

    private fun sandbox(budget: PluginBudget = PluginBudget()): Globals =
        PluginSandbox.create(budget) { printed += it }

    /** Runs [source] with a generous allowance and returns its result. */
    private fun run(source: String, limit: PluginLimit = PluginLimit.LOAD): LuaValue {
        val budget = PluginBudget()
        val globals = sandbox(budget)
        budget.begin(limit)
        return PluginSandbox.compile(globals, source).call()
    }

    // ---- what is not there ----------------------------------------------

    @Test
    fun `the dangerous standard libraries are absent`() {
        val absent = listOf(
            "io", "os.execute", "os.getenv", "os.remove", "os.exit", "os.rename", "os.tmpname",
            "require", "package", "coroutine", "debug", "luajava",
            "load", "loadstring", "loadfile", "dofile",
        )
        for (name in absent) {
            assertEquals(
                "$name should not be reachable",
                LuaValue.TRUE,
                run("return ($name) == nil"),
            )
        }
    }

    @Test
    fun `the harmless standard libraries are present`() {
        for (name in listOf("string", "table", "math", "bit32", "os", "print", "pcall", "tostring")) {
            assertEquals(
                "$name should be reachable",
                LuaValue.TRUE,
                run("return ($name) ~= nil"),
            )
        }
    }

    @Test
    fun `a script cannot load more code at runtime`() {
        // Every route from a string to a running chunk is gone, so a plugin
        // cannot fetch or assemble code that was never reviewed.
        assertEquals(LuaValue.TRUE, run("return load == nil and loadstring == nil"))
    }

    @Test
    fun `precompiled chunks cannot be loaded because there is no undumper`() {
        val globals = sandbox()
        assertTrue(globals.undumper == null)
    }

    @Test
    fun `the resource finder cannot reach the classpath`() {
        val globals = sandbox()
        assertTrue(globals.finder.findResource("java/lang/String.class") == null)
    }

    @Test
    fun `collectgarbage does nothing and says so`() {
        assertEquals(0.0, run("return collectgarbage('count')").todouble(), 0.0)
    }

    // ---- runaway scripts -------------------------------------------------

    @Test
    fun `an infinite loop is stopped by the instruction budget`() {
        val budget = PluginBudget()
        val globals = sandbox(budget)
        budget.begin(PluginLimit(instructions = 100_000, wallMillis = 60_000))
        val chunk = PluginSandbox.compile(globals, "while true do end")

        val abort = runCatching { chunk.call() }.exceptionOrNull()
        assertTrue("expected PluginAbort, got ${abort ?: "nothing"}", abort is PluginAbort)
        assertEquals(PluginAbortReason.INSTRUCTIONS, (abort as PluginAbort).reason)
    }

    @Test
    fun `a slow loop is stopped by the deadline`() {
        var now = 0L
        val budget = PluginBudget { now }
        val globals = sandbox(budget)
        budget.begin(PluginLimit(instructions = Long.MAX_VALUE, wallMillis = 10))
        // The clock jumps past the deadline as soon as the script starts running.
        now = 999_000_000L
        val chunk = PluginSandbox.compile(globals, "while true do end")

        val abort = runCatching { chunk.call() }.exceptionOrNull()
        assertEquals(PluginAbortReason.DEADLINE, (abort as PluginAbort).reason)
    }

    @Test
    fun `cancelling stops a running script`() {
        val budget = PluginBudget()
        val globals = sandbox(budget)
        budget.begin(PluginLimit(instructions = Long.MAX_VALUE, wallMillis = 60_000))
        budget.cancel()

        val abort = runCatching { PluginSandbox.compile(globals, "while true do end").call() }
            .exceptionOrNull()
        assertEquals(PluginAbortReason.CANCELLED, (abort as PluginAbort).reason)
    }

    @Test
    fun `pcall cannot catch the abort`() {
        // This is the load-bearing one. If pcall could swallow PluginAbort, a
        // script could survive its own termination and loop forever.
        val budget = PluginBudget()
        val globals = sandbox(budget)
        budget.begin(PluginLimit(instructions = 100_000, wallMillis = 60_000))
        val chunk = PluginSandbox.compile(
            globals,
            """
            while true do
              pcall(function() while true do end end)
            end
            """.trimIndent(),
        )

        val abort = runCatching { chunk.call() }.exceptionOrNull()
        assertTrue("pcall swallowed the abort: ${abort ?: "nothing thrown"}", abort is PluginAbort)
    }

    @Test
    fun `xpcall cannot catch the abort either`() {
        val budget = PluginBudget()
        val globals = sandbox(budget)
        budget.begin(PluginLimit(instructions = 100_000, wallMillis = 60_000))
        val chunk = PluginSandbox.compile(
            globals,
            """
            while true do
              xpcall(function() while true do end end, function(e) return e end)
            end
            """.trimIndent(),
        )

        assertTrue(runCatching { chunk.call() }.exceptionOrNull() is PluginAbort)
    }

    @Test
    fun `an ordinary lua error is still an ordinary lua error`() {
        val budget = PluginBudget()
        val globals = sandbox(budget)
        budget.begin(PluginLimit.LOAD)
        val thrown = runCatching { PluginSandbox.compile(globals, "error('boom')").call() }
            .exceptionOrNull()
        assertTrue(thrown is LuaError)
        assertTrue(thrown!!.message!!.contains("boom"))
        // ...and a script may still catch its own errors.
        assertEquals(LuaValue.TRUE, run("local ok = pcall(function() error('x') end); return ok == false"))
    }

    @Test
    fun `a fresh budget lets the next call run again`() {
        val budget = PluginBudget()
        val globals = sandbox(budget)
        budget.begin(PluginLimit(instructions = 100_000, wallMillis = 60_000))
        runCatching { PluginSandbox.compile(globals, "while true do end").call() }

        budget.begin(PluginLimit.EVENT)
        assertEquals(3.0, PluginSandbox.compile(globals, "return 1 + 2").call().todouble(), 0.0)
    }

    // ---- the process-global string metatable -----------------------------

    @Test
    fun `a script cannot reach the shared string metatable`() {
        assertEquals(LuaValue.valueOf("protected"), run("return getmetatable('')"))
    }

    @Test
    fun `a script cannot rewrite string methods for every other plugin`() {
        val result = run(
            """
            local ok, err = pcall(function()
              local mt = getmetatable('')
              mt.__index.rep = function() return 'hijacked' end
            end)
            return ok
            """.trimIndent(),
        )
        assertEquals(LuaValue.FALSE, result)
        // And the method still does what it always did, in a brand new sandbox.
        assertEquals(LuaValue.valueOf("aaa"), run("return ('a'):rep(3)"))
    }

    @Test
    fun `a script cannot replace the string metatable outright`() {
        assertEquals(
            LuaValue.FALSE,
            run("return (pcall(function() setmetatable('', {}) end))"),
        )
    }

    @Test
    fun `two sandboxes do not share their string tables`() {
        val firstBudget = PluginBudget()
        val secondBudget = PluginBudget()
        val first = sandbox(firstBudget)
        val second = sandbox(secondBudget)
        firstBudget.begin(PluginLimit.LOAD)
        secondBudget.begin(PluginLimit.LOAD)

        PluginSandbox.compile(first, "string.shout = function(s) return s end").call()
        assertEquals(LuaValue.TRUE, PluginSandbox.compile(first, "return string.shout ~= nil").call())
        assertEquals(LuaValue.TRUE, PluginSandbox.compile(second, "return string.shout == nil").call())
    }

    // ---- amplifier caps ---------------------------------------------------

    @Test
    fun `string rep cannot build an enormous string`() {
        assertEquals(LuaValue.FALSE, run("return (pcall(function() return ('x'):rep(99999999) end))"))
        // The overflow case: length * count wraps an int in luaj's own arithmetic.
        assertEquals(LuaValue.FALSE, run("return (pcall(function() return ('xxxx'):rep(2147483647) end))"))
        // Something reasonable still works.
        assertEquals(1000, run("return ('x'):rep(1000)").checkjstring().length)
    }

    @Test
    fun `an absurd pattern is refused before the matcher sees it`() {
        val pattern = "a".repeat(PluginSandbox.MAX_PATTERN_BYTES + 1)
        assertEquals(
            LuaValue.FALSE,
            run("return (pcall(function() return ('abc'):match('$pattern') end))"),
        )
    }

    @Test
    fun `ordinary pattern matching is untouched`() {
        assertEquals(LuaValue.valueOf("world"), run("return ('hello world'):match('(w%a+)')"))
        // Consecutive pairs: "he" and "ll" match, the trailing "o" does not.
        assertEquals(LuaValue.valueOf("h-el-lo"), run("return (('hello'):gsub('(%a)(%a)', '%1-%2'))"))
    }

    @Test
    fun `table concat cannot build an enormous string`() {
        val source = """
            local t = {}
            for i = 1, 200 do t[i] = ('x'):rep(200000) end
            return (pcall(table.concat, t))
        """.trimIndent()
        assertEquals(LuaValue.FALSE, run(source))
    }

    // ---- what still works ------------------------------------------------

    @Test
    fun `a plugin can still do the work a plugin does`() {
        val source = """
            local function caesar(text, shift)
              return (text:gsub('%a', function(c)
                local base = c:match('%u') and 65 or 97
                return string.char((c:byte() - base + shift) % 26 + base)
              end))
            end
            return caesar('Hello, World!', 3)
        """.trimIndent()
        assertEquals(LuaValue.valueOf("Khoor, Zruog!"), run(source))
    }

    @Test
    fun `math and bit32 and table all work`() {
        assertEquals(4.0, run("return math.max(1, 4, 3)").todouble(), 0.0)
        assertEquals(12.0, run("return bit32.band(28, 13)").todouble(), 0.0)
        assertEquals(LuaValue.valueOf("a,b,c"), run("return table.concat({'a','b','c'}, ',')"))
    }

    @Test
    fun `os can tell the time and nothing else`() {
        assertTrue(run("return os.time()").todouble() > 0)
        assertTrue(run("return os.clock()").todouble() >= 0)
        assertEquals(LuaValue.valueOf("2021-01-01"), run("return os.date('!%Y-%m-%d', 1609459200)"))
        assertEquals(2021.0, run("return os.date('!*t', 1609459200).year").todouble(), 0.0)
    }

    @Test
    fun `os time reads a date table in local time and rolls fields over`() {
        val expected = java.util.Calendar.getInstance().apply {
            clear()
            set(2026, java.util.Calendar.NOVEMBER, 1, 9, 30, 0)
        }.timeInMillis / 1000
        // Day 32 of October is the first of November.
        assertEquals(expected.toDouble(), run("return os.time { year = 2026, month = 10, day = 32, hour = 9, min = 30 }").todouble(), 0.0)
        // hour defaults to noon, as in standard Lua.
        assertEquals("12", run("return os.date('%H', os.time { year = 2026, month = 1, day = 5 })").tojstring())
        val missing = runCatching { run("return os.time { year = 2026 }") }.exceptionOrNull()
        assertTrue(missing.toString(), missing is org.luaj.vm2.LuaError)
    }

    @Test
    fun `print goes to the plugin log rather than anywhere else`() {
        val budget = PluginBudget()
        val globals = sandbox(budget)
        budget.begin(PluginLimit.LOAD)
        PluginSandbox.compile(globals, "print('hello', 42)").call()
        assertEquals(listOf("hello\t42"), printed)
    }

    @Test
    fun `a print flood cannot be used to build an enormous log line`() {
        val budget = PluginBudget()
        val globals = sandbox(budget)
        budget.begin(PluginLimit.LOAD)
        PluginSandbox.compile(globals, "print(('x'):rep(100000))").call()
        assertEquals(PluginSandbox.MAX_PRINT_CHARS, printed.single().length)
    }

    // ---- deep recursion --------------------------------------------------

    @Test
    fun `runaway recursion fails without taking the host down`() {
        val budget = PluginBudget()
        val globals = sandbox(budget)
        budget.begin(PluginLimit(instructions = Long.MAX_VALUE, wallMillis = 60_000))
        val chunk = PluginSandbox.compile(globals, "local function f() return f() + 1 end return f()")

        val thrown = runCatching { chunk.call() }.exceptionOrNull()
        // Either a Lua stack overflow or a JVM one -- what matters is that it is
        // thrown rather than swallowed, so the runtime's boundary can catch it.
        assertTrue("expected a throwable, got none", thrown != null)
        assertFalse("the host should still work", run("return 1 + 1").todouble() != 2.0)
    }
}
