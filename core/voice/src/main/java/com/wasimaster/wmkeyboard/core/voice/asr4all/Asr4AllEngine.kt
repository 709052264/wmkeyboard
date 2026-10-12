package com.wasimaster.wmkeyboard.core.voice.asr4all

import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Offline asr4all dictation: a streaming English recogniser that transcribes
 * while the user speaks, with punctuation and capitals from its own second
 * pass. Run by ExecuTorch through [Asr4AllNative].
 *
 * Everything that touches the model runs on [dispatcher], one thread, since a
 * loaded program is not thread-safe; callers switch to it rather than lock.
 * One program is kept open between dictations: loading one takes about as
 * long as a short phrase does to say.
 */
object Asr4AllEngine {

    /** The one thread every program call runs on. */
    val dispatcher: CoroutineDispatcher by lazy {
        Executors.newSingleThreadExecutor { r -> Thread(r, "asr4all").apply { isDaemon = true } }
            .asCoroutineDispatcher()
    }

    /** Whether this device and build can run asr4all at all. */
    val available: Boolean get() = Asr4AllNative.available

    @Volatile private var program: Asr4AllNative.Program? = null

    /** Whether a program is open, so a memory trim knows there is something to free. Any thread. */
    val isOpen: Boolean get() = program != null
    private var metadata: Asr4AllMetadata? = null
    private var loaded: Asr4AllSize? = null

    /** Opens [size] if it is not the model already open. On [dispatcher] only. Throws when it will not load. */
    fun warm(filesDir: File, size: Asr4AllSize) {
        if (loaded == size && program != null) return
        release()
        val meta = Asr4AllMetadata.parse(Asr4AllStore.file(filesDir, size, size.metadata).readText())
        val opened = Asr4AllNative.open(Asr4AllStore.file(filesDir, size, size.program))
            ?: error("The asr4all model did not load")
        program = opened
        metadata = meta
        loaded = size
    }

    /** A fresh utterance on [size], opening it first if need be. On [dispatcher] only. */
    fun newStream(filesDir: File, size: Asr4AllSize): Asr4AllStream {
        warm(filesDir, size)
        return Asr4AllStream(program!!, metadata!!)
    }

    /** Closes the open program. On [dispatcher] only, so never under a running stream. */
    fun release() {
        program?.close()
        program = null
        metadata = null
        loaded = null
    }
}
