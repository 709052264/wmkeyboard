package com.wasimaster.wmkeyboard.core.voice.executorch

import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * ExecuTorch, built for the keyboard's speech models alone, through
 * `native/executorch-jni`: a selective build carrying only the operators those
 * models use, the XNNPACK delegate and the Module extension. Asr4All, Parakeet
 * and Moonshine all run on it; each has its own Kotlin pipeline over [Program].
 *
 * The libraries ship in `src/main/jniLibs`, so both editions carry them; a
 * build without them (F-Droid deletes prebuilt libraries) has [available]
 * false and offers none of these engines.
 *
 * A program is not thread-safe, and the models are large, so every call runs
 * on [dispatcher], one thread, and [open] keeps one set of programs at a time:
 * opening another engine's model closes the last one.
 */
object EtNative {

    /** Whether the runtime loaded on this device and build. */
    val available: Boolean by lazy { runCatching { System.loadLibrary("wmexecutorch") }.isSuccess }

    /** The one thread every program call runs on. */
    val dispatcher: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "executorch").apply { isDaemon = true } }
            .asCoroutineDispatcher()
    }

    /** An opened program, memory-mapped. [close] frees it. */
    class Program internal constructor(private var handle: Long) : EtRunner, AutoCloseable {

        private val shapes = HashMap<String, MethodShape?>()

        override fun shape(method: String): MethodShape? = shapes.getOrPut(method) {
            nativeMethodMeta(handle, method)?.let(::parseShape)
        }

        override fun run(method: String, inputs: List<Tensor>): List<Tensor> {
            check(handle != 0L) { "program is closed" }
            val out = nativeExecute(
                handle,
                method,
                Array(inputs.size) { inputs[it].data },
                Array(inputs.size) { inputs[it].shape },
            )
            return List(out.size / 2) { i ->
                Tensor(out[i * 2 + 1] as? LongArray ?: LongArray(0), out[i * 2] ?: FloatArray(0))
            }
        }

        /** A constant method's value, such as Parakeet's `blank_id`, or null without one. */
        fun constant(method: String): Long? =
            runCatching { run(method, emptyList()).firstOrNull()?.scalar }.getOrNull()

        override fun close() {
            val h = handle
            handle = 0L
            if (h != 0L) nativeClose(h)
        }
    }

    /** Opens [file], or null when it is not a program this runtime can load. */
    fun open(file: File): Program? {
        check(available) { "the ExecuTorch runtime is not in this build" }
        val h = nativeOpen(file.absolutePath)
        return if (h == 0L) null else Program(h)
    }

    private var openFiles: List<String> = emptyList()
    private var openPrograms: List<Program> = emptyList()

    /** Whether any program is open, so a memory trim knows there is something to free. Any thread. */
    @Volatile var isOpen: Boolean = false
        private set

    /**
     * The programs for [files], opened if they are not the set already open,
     * which is closed first. On [dispatcher] only. Throws when one will not load.
     */
    fun programs(files: List<File>): List<Program> {
        val paths = files.map { it.absolutePath }
        if (paths == openFiles && openPrograms.isNotEmpty()) return openPrograms
        release()
        val opened = ArrayList<Program>(files.size)
        try {
            for (f in files) opened += open(f) ?: error("${f.name} did not load")
        } catch (t: Throwable) {
            opened.forEach { it.close() }
            throw t
        }
        openFiles = paths
        openPrograms = opened
        isOpen = true
        return opened
    }

    /** Closes whatever is open. On [dispatcher] only, so never under a running model. */
    fun release() {
        openPrograms.forEach { it.close() }
        openPrograms = emptyList()
        openFiles = emptyList()
        isOpen = false
    }

    /** Reads [nativeMethodMeta]'s flat layout: counts, then dtype, rank, dims per tensor. */
    internal fun parseShape(flat: LongArray): MethodShape {
        var at = 2
        fun next(): TensorSpec {
            val dtype = flat[at++].toInt()
            val rank = flat[at++].toInt()
            val dims = LongArray(rank) { flat[at++] }
            return TensorSpec(dtype, dims)
        }
        val inputs = List(flat[0].toInt()) { next() }
        val outputs = List(flat[1].toInt()) { next() }
        return MethodShape(inputs, outputs)
    }

    @JvmStatic private external fun nativeOpen(path: String): Long

    @JvmStatic private external fun nativeClose(handle: Long)

    @JvmStatic private external fun nativeMethodMeta(handle: Long, method: String): LongArray?

    @JvmStatic private external fun nativeExecute(
        handle: Long,
        method: String,
        inputs: Array<Any>,
        shapes: Array<LongArray>,
    ): Array<Any?>
}
