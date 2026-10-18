package com.wasimaster.wmkeyboard.core.plugins

import com.wasimaster.wmkeyboard.plugins.R
import org.luaj.vm2.Globals
import org.luaj.vm2.LuaError
import org.luaj.vm2.LuaTable
import org.luaj.vm2.LuaValue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs one plugin at a time, off the keyboard's thread, with a watchdog.
 *
 * The contract the rest of the app relies on: **nothing here can make the
 * keyboard wait.** Every Lua call happens on a dedicated daemon thread, results
 * come back through [Listener], and the worst a broken plugin can do is fail to
 * draw its own panel.
 *
 * ## Stopping a plugin that will not stop
 *
 * Two mechanisms, because one is not enough. [PluginBudget]'s instruction hook
 * catches everything that is executing Lua — an infinite loop dies in
 * microseconds. What it cannot catch is a thread inside luaj's own Java code,
 * where no bytecode boundary is ever crossed; a pathological `string.gsub` is
 * the realistic case. The watchdog covers that: it notices a call that is past
 * its deadline with the hook plainly not firing, and *abandons* the thread.
 *
 * Abandoning is not killing. `Thread.stop` is gone from the platform for good
 * reasons and luaj never polls for interrupts, so a thread spinning in Java is a
 * thread that keeps spinning. What abandonment does is sever it: the session is
 * marked revoked so every `wm.*` call it might still make throws instead of
 * doing anything, its executor is shut down, its priority is dropped, and the
 * runtime forgets it exists. It burns CPU until the process ends and can affect
 * nothing else. A plugin that does this twice is disabled — see
 * [PluginStore.recordAbandon] — because a plugin that hangs the panel twice has
 * used up the benefit of the doubt.
 *
 * ## Drafts
 *
 * The plugin editor runs a script nobody has installed, through [open] with a
 * [Draft]. A draft is never in the store, earns no strikes, and uses whatever
 * log and storage the editor hands it, so running half-written code can never
 * disable or overwrite the installed plugin it will one day replace.
 *
 * ## Threading
 *
 * [open], [dispatch] and [close] are called from the keyboard's thread. Every
 * [Listener] callback is handed to [post], which the host supplies to hop back
 * to its own thread; tests pass a direct runner. Nothing in here calls back into
 * itself, and the listener must not re-enter the runtime synchronously.
 */
class PluginRuntime(
    private val store: PluginStore,
    private val listener: Listener,
    private val limits: Limits = Limits(),
    private val post: (() -> Unit) -> Unit = { it() },
    /** Extra signal for the plugin editor. The keyboard passes none. */
    private val observer: Observer? = null,
    /**
     * Runs on the plugin thread after an installed plugin changed its
     * reminders, so the host can move its wake-up. Must not block.
     */
    private val onRemindersChanged: () -> Unit = {},
) {

    /**
     * Everything the host needs to hear about. Delivered through [post].
     *
     * The messages arrive as [PluginText] rather than as words, because this
     * runtime has no Context. The host turns one into a line of text with
     * `message.resolve(context)` where it draws it.
     */
    interface Listener {
        fun onUi(pluginId: String, ui: RenderedUi)

        /** A call is taking long enough to be worth a spinner. */
        fun onBusy(pluginId: String, busy: Boolean)

        /** The script failed. The plugin is still loaded and can be retried. */
        fun onError(pluginId: String, message: PluginText)

        /** `wm.ui.set_input` wrote to one of the plugin's own text boxes. */
        fun onInputWrite(pluginId: String, inputId: String, text: String)

        /** The session is over and will not recover. [disabled] means it used its last strike. */
        fun onStopped(pluginId: String, message: PluginText, disabled: Boolean)
    }

    /** Which call into the script a report is about. */
    enum class Phase { LOAD, EVENT, RENDER }

    /**
     * Why a call failed, told the way an author needs it. Unlike the [Listener]'s
     * message, [text] is not cut short, so a runtime error keeps its traceback.
     * [chunk] and [line] are read off the first line of [text], when it names one.
     */
    data class Failure(
        val kind: Kind,
        val text: String,
        val chunk: String?,
        val line: Int?,
        val phase: Phase,
    ) {
        enum class Kind { COMPILE, RUNTIME, INSTRUCTIONS, DEADLINE, CANCELLED, ABANDONED, RECURSION, UNEXPECTED }
    }

    /**
     * What an authoring host wants beyond what the keyboard needs: every printed
     * line, what each call spent, why a call failed in full, and each event as it
     * arrives.
     *
     * Called **on the plugin thread**, not through [post], and [onFailure] for an
     * abandoned session on the watchdog's. Posting one runnable per printed line
     * would be a flood, and printing inside a render loop is what everyone does
     * the first time they debug one. An implementation must not block and must not
     * touch UI state directly. Every method has a do-nothing default.
     */
    interface Observer {
        fun onPrint(pluginId: String, line: String) {}

        fun onPhase(pluginId: String, phase: Phase, usage: PluginBudget.Usage) {}

        fun onFailure(pluginId: String, failure: Failure) {}

        fun onEvent(pluginId: String, event: PluginEvent) {}
    }

    /** A session supplied in full, rather than one read out of the store. */
    data class Draft(
        val plugin: InstalledPlugin,
        val script: String,
        val log: PluginLog,
        val storage: PluginStorage?,
        /** Runaway strikes belong to installed plugins. A draft never earns one. */
        val recordStrikes: Boolean = false,
        /** `wm.reminders`, when the manifest declares it. The editor's never reach the shade. */
        val reminders: PluginReminders? = null,
    )

    data class Limits(
        val load: PluginLimit = PluginLimit.LOAD,
        val event: PluginLimit = PluginLimit.EVENT,
        val render: PluginLimit = PluginLimit.RENDER,
        val watchdogPeriodMillis: Long = 250,
        val watchdogGraceMillis: Long = 500,
        val busyAfterMillis: Long = 150,
    )

    private val watchdog: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "wm-plugin-watchdog").apply { isDaemon = true }
        }

    private var session: Session? = null

    /** The plugin currently loaded, if any. */
    @get:Synchronized
    val activePluginId: String?
        get() = session?.plugin?.id

    // ---- lifecycle -------------------------------------------------------

    /** Loads a plugin and renders it. Replaces whatever was open. */
    @Synchronized
    fun open(pluginId: String) {
        close()
        val plugin = store.plugin(pluginId)
        if (plugin == null) {
            post { listener.onError(pluginId, PluginText.of(R.string.core_plugins_error_not_installed)) }
            return
        }
        if (!plugin.enabled) {
            post { listener.onError(pluginId, PluginText.of(R.string.core_plugins_error_turned_off)) }
            return
        }
        val script = store.script(pluginId)
        if (script == null) {
            post { listener.onError(pluginId, PluginText.of(R.string.core_plugins_error_script_missing)) }
            return
        }

        val log = PluginLog(store.logFile(pluginId))
        val storage = if (plugin.grants(PluginPermission.Storage)) {
            PluginStorage(store.storageFile(pluginId))
        } else {
            null
        }
        val reminders = if (plugin.grants(PluginPermission.Reminders)) {
            PluginReminders(store.remindersFile(pluginId), onRemindersChanged)
        } else {
            null
        }
        start(Draft(plugin, script, log, storage, recordStrikes = true, reminders = reminders))
    }

    /** Loads a script the caller supplies, installed or not, and renders it. Replaces whatever was open. */
    @Synchronized
    fun open(draft: Draft) {
        close()
        start(draft)
    }

    /** Hands a user action to the script, then re-renders. */
    @Synchronized
    fun dispatch(event: PluginEvent) {
        val current = session ?: return
        submit(current, limits.event, Phase.EVENT) { handle(current, event) }
    }

    /** Ends the session. Safe to call when nothing is open. */
    @Synchronized
    fun close() {
        val current = session ?: return
        session = null
        current.revoked = true
        current.budget.cancel()
        current.watch?.cancel(false)
        current.log.flush()
        current.executor.shutdown()
    }

    /** Ends the session and stops the watchdog. For the service's own teardown. */
    @Synchronized
    fun shutdown() {
        close()
        watchdog.shutdownNow()
    }

    private fun start(draft: Draft) {
        val fresh = Session(draft.plugin, draft.log, draft.storage, draft.recordStrikes, draft.reminders)
        session = fresh
        fresh.watch = watchdog.scheduleWithFixedDelay(
            { patrol(fresh) },
            limits.watchdogPeriodMillis,
            limits.watchdogPeriodMillis,
            TimeUnit.MILLISECONDS,
        )
        submit(fresh, limits.load, Phase.LOAD) { load(fresh, draft.script) }
    }

    // ---- work on the plugin thread ---------------------------------------

    private fun load(current: Session, script: String) {
        val globals = PluginSandbox.create(current.budget) { line ->
            current.log.add(line)
            observer?.onPrint(current.plugin.id, line)
        }
        val api = PluginHostApi(
            plugin = current.plugin,
            log = current.log,
            storage = current.storage,
            setInput = { id, text -> current.pendingInputs.add(id to text) },
            revoked = { current.revoked },
            onLine = { line -> observer?.onPrint(current.plugin.id, line) },
            reminders = current.reminders,
        )
        globals.set("wm", api.table())
        PluginSandbox.compile(globals, PluginPrelude.SOURCE, PluginPrelude.CHUNK_NAME).call()
        // Split so a compile error can be told from a runtime one. The keyboard
        // hears both the same way; only an observer learns which it was.
        val chunk = try {
            PluginSandbox.compile(globals, script)
        } catch (error: LuaError) {
            throw CompileFailure(error)
        }
        chunk.call()
        current.globals = globals
        finishPhase(current)
        render(current)
    }

    private fun handle(current: Session, event: PluginEvent) {
        val globals = current.globals ?: return
        observer?.onEvent(current.plugin.id, event)
        val handler = globals.get("on_event")
        if (handler.isfunction()) handler.call(eventTable(event))
        flushInputs(current)
        finishPhase(current)
        render(current)
    }

    private fun render(current: Session) {
        val globals = current.globals ?: return
        // A fresh allowance every time: drawing is not charged against whatever
        // ran before it, or a plugin that thinks hard once could never draw again.
        current.budget.begin(limits.render)
        current.phase = Phase.RENDER
        val renderFn = globals.get("render")
        if (!renderFn.isfunction()) {
            val message = PluginText.of(R.string.core_plugins_error_no_render)
            deliver(current) { listener.onError(current.plugin.id, message) }
            return
        }
        val ui = PluginUiCodec.fromLua(renderFn.call())
        flushInputs(current)
        deliver(current) { listener.onUi(current.plugin.id, ui) }
    }

    /**
     * Tells an observer what the phase that just ran spent, before render begins
     * the next allowance and the numbers are gone. Only with an observer: without
     * one the budget runs exactly as it always has.
     */
    private fun finishPhase(current: Session) {
        val watcher = observer ?: return
        current.budget.end()
        watcher.onPhase(current.plugin.id, current.phase, current.budget.usage())
    }

    private fun flushInputs(current: Session) {
        if (current.pendingInputs.isEmpty()) return
        val writes = current.pendingInputs.toList()
        current.pendingInputs.clear()
        for ((id, text) in writes) {
            deliver(current) { listener.onInputWrite(current.plugin.id, id, text) }
        }
    }

    private fun eventTable(event: PluginEvent): LuaTable {
        val table = LuaTable()
        table.set("id", event.id)
        when (event) {
            is PluginEvent.Click -> table.set("type", "click")
            is PluginEvent.ToggleChanged -> {
                table.set("type", "toggle")
                table.set("value", LuaValue.valueOf(event.value))
            }

            is PluginEvent.InputChanged -> {
                table.set("type", "input_changed")
                table.set("value", event.value)
            }

            is PluginEvent.TabSelected -> {
                table.set("type", "tab_selected")
                table.set("index", event.index)
            }

            is PluginEvent.DateChanged -> {
                table.set("type", "date_changed")
                // nil for a cleared date, which is how a script tells "no date".
                table.set("value", event.value?.let { LuaValue.valueOf(it.toDouble()) } ?: LuaValue.NIL)
            }
        }
        return table
    }

    // ---- scheduling and failure -------------------------------------------

    @Suppress("TooGenericExceptionCaught")
    private fun submit(current: Session, limit: PluginLimit, phase: Phase, body: () -> Unit) {
        val finished = AtomicBoolean(false)
        val busy = watchdog.schedule(
            {
                if (!finished.get() && !current.revoked) {
                    deliver(current) { listener.onBusy(current.plugin.id, true) }
                }
            },
            limits.busyAfterMillis,
            TimeUnit.MILLISECONDS,
        )
        val submitted = runCatching {
            current.executor.execute {
                current.thread = Thread.currentThread()
                current.phase = phase
                current.budget.begin(limit)
                try {
                    body()
                } catch (failure: Throwable) {
                    onFailure(current, failure)
                } finally {
                    current.budget.end()
                    observer?.onPhase(current.plugin.id, current.phase, current.budget.usage())
                    finished.set(true)
                    busy.cancel(false)
                    deliver(current) { listener.onBusy(current.plugin.id, false) }
                }
            }
        }.isSuccess
        // The executor is already shutting down: the session is over and there
        // is nobody left to tell.
        if (!submitted) busy.cancel(false)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun onFailure(current: Session, failure: Throwable) {
        when (failure) {
            is PluginAbort -> {
                val reason = failure.reason.name.lowercase()
                current.log.add("stopped: $reason")
                observe(current, abortKind(failure.reason), "stopped: $reason")
                // Cancellation is the host closing the panel, which the user did
                // on purpose and does not need to be told about.
                if (failure.reason != PluginAbortReason.CANCELLED) {
                    stop(current, PluginText.of(failure.reason.messageRes), strike = true)
                }
            }

            is CompileFailure -> scriptFailed(current, failure.error, Failure.Kind.COMPILE)
            is LuaError -> scriptFailed(current, failure, Failure.Kind.RUNTIME)

            is StackOverflowError -> {
                current.log.add("error: stack overflow")
                observe(current, Failure.Kind.RECURSION, "stack overflow")
                stop(current, PluginText.of(R.string.core_plugins_stopped_recursion), strike = false)
            }

            else -> {
                // The class name alone is not a bug report. A bare
                // "NoClassDefFoundError" in someone's log says a plugin broke and
                // nothing about what broke it, so the message comes too.
                val name = failure.javaClass.simpleName
                val detail = failure.message?.take(MAX_ERROR)
                current.log.add("error: " + if (detail != null) "$name: $detail" else name)
                observe(current, Failure.Kind.UNEXPECTED, failure.message?.let { "$name: $it" } ?: name)
                stop(current, PluginText.of(R.string.core_plugins_stopped_unexpected), strike = false)
            }
        }
    }

    /** A script that failed to compile or raised an error. The session survives either. */
    private fun scriptFailed(current: Session, error: LuaError, kind: Failure.Kind) {
        val raw = error.message?.take(MAX_ERROR)
        current.log.add("error: ${raw ?: "the script failed"}")
        observe(current, kind, error.message.orEmpty())
        // The Lua virtual machine wrote this, so it goes to the panel as
        // it arrived. Only the stand-in for a missing message is ours.
        val message = raw?.let { PluginText.Script(it) }
            ?: PluginText.of(R.string.core_plugins_error_script_failed)
        deliver(current) { listener.onError(current.plugin.id, message) }
    }

    private fun observe(current: Session, kind: Failure.Kind, text: String) {
        val watcher = observer ?: return
        val (chunk, line) = locate(text)
        watcher.onFailure(current.plugin.id, Failure(kind, text, chunk, line, current.phase))
    }

    private fun abortKind(reason: PluginAbortReason): Failure.Kind = when (reason) {
        PluginAbortReason.INSTRUCTIONS -> Failure.Kind.INSTRUCTIONS
        PluginAbortReason.DEADLINE -> Failure.Kind.DEADLINE
        PluginAbortReason.CANCELLED -> Failure.Kind.CANCELLED
    }

    /** Ends the session for a reason the user should see. */
    private fun stop(current: Session, message: PluginText, strike: Boolean) {
        val disabled = if (strike && current.recordStrikes) {
            store.recordAbandon(current.plugin.id)?.enabled == false
        } else {
            false
        }
        deliverFinal { listener.onStopped(current.plugin.id, message, disabled) }
        synchronized(this) {
            if (session === current) close()
        }
    }

    /**
     * One watchdog tick. Only fires for a thread that is past its deadline and
     * is not in a host call — by which point the instruction hook has plainly
     * not run, so waiting longer would not help.
     */
    private fun patrol(current: Session) {
        if (current.revoked) return
        if (!current.budget.isStuck(limits.watchdogGraceMillis)) return
        abandon(current)
    }

    private fun abandon(current: Session) {
        current.revoked = true
        current.budget.cancel()
        current.watch?.cancel(false)
        current.log.add("abandoned: exceeded its time limit")
        current.log.flush()
        observe(current, Failure.Kind.ABANDONED, "abandoned: exceeded its time limit")
        // Severed rather than stopped: it may still be running, but every route
        // from it back into the app now refuses, and it is the lowest-priority
        // daemon on the device.
        runCatching { current.executor.shutdownNow() }
        runCatching { current.thread?.priority = Thread.MIN_PRIORITY }

        val record = if (current.recordStrikes) store.recordAbandon(current.plugin.id) else null
        val disabled = record?.enabled == false
        val message = PluginText.of(
            if (disabled) {
                R.string.core_plugins_stopped_not_responding_disabled
            } else {
                R.string.core_plugins_stopped_not_responding
            },
        )
        deliverFinal { listener.onStopped(current.plugin.id, message, disabled) }
        synchronized(this) { if (session === current) session = null }
    }

    /**
     * Delivers an ordinary update, dropped if the session has been superseded —
     * a render that finished after the user closed the panel is noise.
     */
    private fun deliver(current: Session, body: () -> Unit) {
        if (current.revoked) return
        post {
            if (!current.revoked) body()
        }
    }

    /**
     * Delivers a terminal message, always.
     *
     * Deliberately not routed through [deliver]: ending a session is precisely
     * the moment [Session.revoked] gets set, so filtering on it here would drop
     * every "this plugin stopped responding" notice and leave the panel sitting
     * empty forever with nothing to explain why.
     */
    private fun deliverFinal(body: () -> Unit) {
        post(body)
    }

    // ---- one plugin's world -----------------------------------------------

    private class Session(
        val plugin: InstalledPlugin,
        val log: PluginLog,
        val storage: PluginStorage?,
        val recordStrikes: Boolean,
        val reminders: PluginReminders?,
    ) {
        val budget = PluginBudget()

        /** Written on the plugin thread inside a handler, drained straight after. */
        val pendingInputs = ArrayList<Pair<String, String>>()

        @Volatile
        var revoked = false

        @Volatile
        var thread: Thread? = null

        /** The call the plugin thread is in, for an observer's reports. */
        @Volatile
        var phase = Phase.LOAD

        var globals: Globals? = null
        var watch: ScheduledFuture<*>? = null

        val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "wm-plugin-${plugin.id}").apply {
                isDaemon = true
                priority = Thread.NORM_PRIORITY - 1
            }
        }
    }

    /** A compile error, carried past the chunk call so [onFailure] can say which kind it was. */
    private class CompileFailure(val error: LuaError) : RuntimeException(error.message, error) {
        override fun fillInStackTrace(): Throwable = this
    }

    companion object {
        private const val MAX_ERROR = 300

        /** `main.lua:12: ...`, `@main.lua:12 ...`, or the prelude's equivalent, at the start of a line. */
        private val LOCATION = Regex("""^\s*@?(main\.lua|prelude\.lua):(\d+)\b""")

        /**
         * The chunk and line a luaj message starts with, or nulls when it names
         * none. Only the first line is read: the frames of a traceback below it
         * name other places, and the first line is where the error happened.
         */
        fun locate(text: String): Pair<String?, Int?> {
            val match = LOCATION.find(text.substringBefore('\n')) ?: return null to null
            return match.groupValues[1] to match.groupValues[2].toIntOrNull()
        }
    }
}
