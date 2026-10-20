package com.wasimaster.wmkeyboard.core.semantic

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import android.os.Process
import android.util.Size
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import java.io.File

/**
 * Runs [EmbeddingEngine] in a process of its own (`:semantic` in the
 * manifest), so the few hundred megabytes a loaded model takes are never the
 * keyboard's: if the system needs the memory back it ends this process, and
 * typing never notices. [Embeddings] is the other end.
 *
 * A [Messenger] service: a request names texts or pictures, the answer is
 * their vectors, every request on one background thread in arrival order.
 * The model is let go when nothing has asked for a while; the pictures half
 * sooner, since only indexing uses it.
 */
class EmbeddingService : Service() {

    private lateinit var worker: HandlerThread
    private lateinit var handler: Handler
    private lateinit var messenger: Messenger
    private var engine: EmbeddingEngine? = null

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("semantic-engine", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        handler = Handler(worker.looper) { message -> serve(message); true }
        messenger = Messenger(handler)
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    override fun onDestroy() {
        handler.post {
            engine?.close()
            engine = null
        }
        worker.quitSafely()
        super.onDestroy()
    }

    private fun serve(message: Message) {
        when (message.what) {
            RELEASE_ALL -> {
                engine?.close()
                engine = null
                return
            }
            RELEASE_VISION -> {
                engine?.closeVision()
                return
            }
        }
        val reply = Message.obtain(null, message.what, message.arg1, 0)
        val data = Bundle()
        try {
            val request = message.data
            val engine = engine ?: EmbeddingEngine(applicationContext).also { engine = it }
            val vectors: List<FloatArray?> = when (message.what) {
                TEXTS -> engine.texts(
                    request.getStringArrayList(KEY_INPUTS).orEmpty(),
                    EmbeddingInput.Role.entries[request.getInt(KEY_ROLE)],
                )
                IMAGES -> request.getStringArrayList(KEY_INPUTS).orEmpty().map { input ->
                    val bitmap = decode(input) ?: return@map null
                    try {
                        engine.image(bitmap)
                    } finally {
                        bitmap.recycle()
                    }
                }
                else -> emptyList()
            }
            val flat = FloatArray(vectors.size * EmbeddingInput.DIMENSIONS)
            val valid = BooleanArray(vectors.size)
            for ((k, vector) in vectors.withIndex()) {
                if (vector == null) continue
                System.arraycopy(vector, 0, flat, k * EmbeddingInput.DIMENSIONS, EmbeddingInput.DIMENSIONS)
                valid[k] = true
            }
            data.putFloatArray(KEY_VECTORS, flat)
            data.putBooleanArray(KEY_VALID, valid)
        } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
            DebugLog.w(TAG, "embedding failed: ${t.javaClass.simpleName}: ${t.message}")
            data.putString(KEY_ERROR, t.message ?: t.javaClass.simpleName)
            // A model that will not load or ran out of memory is let go whole.
            engine?.close()
            engine = null
        }
        reply.data = data
        runCatching { message.replyTo?.send(reply) }
        handler.removeMessages(RELEASE_ALL)
        handler.removeMessages(RELEASE_VISION)
        handler.sendEmptyMessageDelayed(RELEASE_VISION, VISION_IDLE_MS)
        handler.sendEmptyMessageDelayed(RELEASE_ALL, IDLE_MS)
    }

    /**
     * A picture from a file path or a `content://` address, decoded no larger
     * than the vision tower needs, or null when it is not a picture Android
     * can read (an animated sticker's first frame is read; a Lottie one is not).
     */
    private fun decode(input: String): Bitmap? = runCatching {
        // A photo in the phone's library has a thumbnail the system already
        // made: reading it is a small file instead of a whole camera JPEG,
        // and it comes the right way up.
        if (input.startsWith("content://media/") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { contentResolver.loadThumbnail(Uri.parse(input), Size(THUMBNAIL_SIDE, THUMBNAIL_SIDE), null) }
                .getOrNull()
                ?.let { return@runCatching it }
        }
        val open = {
            if (input.startsWith("content://")) contentResolver.openInputStream(Uri.parse(input)) else File(input).inputStream()
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= DECODE_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        open()?.use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    companion object {
        private const val TAG = "EmbeddingService"

        internal const val TEXTS = 1
        internal const val IMAGES = 2
        internal const val RELEASE_ALL = 100
        internal const val RELEASE_VISION = 101

        internal const val KEY_INPUTS = "inputs"
        internal const val KEY_ROLE = "role"
        internal const val KEY_VECTORS = "vectors"
        internal const val KEY_VALID = "valid"
        internal const val KEY_ERROR = "error"

        /** The text model stays loaded this long after the last request: a search is a burst of them. */
        private const val IDLE_MS = 3 * 60_000L

        /** The vision tower, only ever used by an indexing pass, goes sooner. */
        private const val VISION_IDLE_MS = 20_000L

        /** The library thumbnail asked for; still more than the tower looks at. */
        private const val THUMBNAIL_SIDE = 512

        /** Pictures are decoded down to about this side; the tower sees a few hundred pixels at most. */
        private const val DECODE_SIDE = 1024

        /** The manifest's `android:process` suffix. */
        const val PROCESS_SUFFIX = ":semantic"

        /** Whether this process is the service's own, where the app does nothing but this. */
        fun isServiceProcess(): Boolean = DebugLog.processName().endsWith(PROCESS_SUFFIX)
    }
}
