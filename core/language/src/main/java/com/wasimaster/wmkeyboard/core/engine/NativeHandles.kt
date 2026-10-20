package com.wasimaster.wmkeyboard.core.engine

import java.lang.ref.PhantomReference
import java.lang.ref.ReferenceQueue
import java.util.concurrent.ConcurrentHashMap

/**
 * Closes a Rust handle once the Kotlin object that held it is gone.
 *
 * A mapped dictionary or n-gram pack lives in the native library behind a
 * `long` handle, and the Kotlin object over it is dropped, not closed: the
 * IME swaps a dictionary by replacing the reference, and an in-flight
 * suggestion pass may still be reading the old one. So each owner is
 * [track]ed with a phantom reference, and the handles of owners the
 * collector has cleared are closed the next time anything is tracked, or
 * when [releaseUnreachable] is called. The Rust side keeps an object alive
 * while a call is using it, so a late close is never a use-after-free.
 *
 * `java.lang.ref.Cleaner` would do this on its own thread, but it needs
 * API 33.
 */
object NativeHandles {
    private class Release(val handle: Long, val close: (Long) -> Unit)

    private val queue = ReferenceQueue<Any>()

    /** The references themselves must stay reachable, or they are never enqueued. */
    private val live = ConcurrentHashMap<PhantomReference<Any>, Release>()

    /**
     * Closes [handle] with [close] once [owner] is unreachable. [close] must
     * not hold [owner], or it never becomes unreachable: pass a function
     * reference such as `PredictionNative::dictClose`.
     */
    fun track(owner: Any, handle: Long, close: (Long) -> Unit) {
        releaseUnreachable()
        if (handle != 0L) live[PhantomReference(owner, queue)] = Release(handle, close)
    }

    fun releaseUnreachable() {
        while (true) {
            val ref = queue.poll() ?: return
            live.remove(ref)?.let { it.close(it.handle) }
        }
    }

    /** Handles still held, for tests. */
    val tracked: Int get() = live.size
}
