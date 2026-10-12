package com.wasimaster.wmkeyboard.core.voice.asr4all

import java.io.File

/**
 * ExecuTorch, built for these models alone, through `native/asr4all-jni`: a
 * selective build carrying only the operators the asr4all programs use and
 * the XNNPACK delegate. The libraries ship in the full flavour's `jniLibs`;
 * lite builds have none, so [available] is false there.
 *
 * One [Program] is one opened `.pte`. It is not thread-safe; the engine runs
 * every call for it on one thread.
 */
object Asr4AllNative {

    /** Whether the runtime loaded on this device and build. */
    val available: Boolean by lazy { runCatching { System.loadLibrary("wmasr4all") }.isSuccess }

    /** An opened program, memory-mapped. [close] frees it. */
    class Program internal constructor(private var handle: Long) : Asr4AllRunner, AutoCloseable {

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

        override fun close() {
            val h = handle
            handle = 0L
            if (h != 0L) nativeClose(h)
        }
    }

    /** Opens [file], or null when it is not a program this runtime can load. */
    fun open(file: File): Program? {
        check(available) { "the asr4all runtime is not in this build" }
        val h = nativeOpen(file.absolutePath)
        return if (h == 0L) null else Program(h)
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
