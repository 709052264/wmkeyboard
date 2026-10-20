package com.wasimaster.wmkeyboard.core.semantic

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Message
import android.os.Messenger
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The one way the rest of the app turns texts and pictures into vectors:
 * EmbeddingGemma 2, running in [EmbeddingService]'s own process. Every call
 * suspends until the answer is back and returns null, never throws, when it
 * cannot be had: the model not downloaded, the service gone, a timeout. A
 * caller then shows what keyword search found and nothing more.
 *
 * Binds on first use and unbinds once nothing has asked for a while, so the
 * service's process can be reclaimed.
 */
object Embeddings {

    private const val TAG = "Embeddings"

    /** The first call loads the model, which on a slow phone takes a while. */
    private const val TIMEOUT_MS = 90_000L

    /** Unbound this long after the last answer. */
    private const val UNBIND_AFTER_MS = 4 * 60_000L

    private val thread = HandlerThread("semantic-client").apply { start() }
    private val handler = Handler(thread.looper) { message -> answer(message); true }
    private val replies = Messenger(handler)
    private val nextId = AtomicInteger(1)
    private val pending = HashMap<Int, CompletableDeferred<Bundle>>()
    private val binding = Mutex()

    @Volatile private var service: Messenger? = null
    @Volatile private var connection: ServiceConnection? = null
    @Volatile private var bound: CompletableDeferred<Messenger?>? = null
    @Volatile private var appContext: Context? = null

    private val unbind = Runnable { disconnect() }

    /** Whether the text model is on this install and could answer. Never downloads. */
    fun available(context: Context): Boolean = SemanticDownloads.ready(context, SemanticModels.Pack.TEXT)

    /** Whether pictures can be embedded too. */
    fun picturesAvailable(context: Context): Boolean = SemanticDownloads.ready(context, SemanticModels.Pack.PICTURES)

    /** One unit vector per text, or null. */
    suspend fun texts(context: Context, texts: List<String>, role: EmbeddingInput.Role): List<FloatArray>? {
        if (texts.isEmpty()) return emptyList()
        if (!available(context)) return null
        val request = Bundle().apply {
            putStringArrayList(EmbeddingService.KEY_INPUTS, ArrayList(texts))
            putInt(EmbeddingService.KEY_ROLE, role.ordinal)
        }
        return ask(context, EmbeddingService.TEXTS, request)?.map { it ?: return null }
    }

    /** A query typed in a search box, as one vector, or null. */
    suspend fun query(context: Context, text: String): FloatArray? =
        texts(context, listOf(text), EmbeddingInput.Role.QUERY)?.firstOrNull()

    /**
     * One vector per picture (a file path or a `content://` address); a
     * picture that could not be read is an empty array in its place. Null
     * when nothing could be had at all.
     */
    suspend fun images(context: Context, inputs: List<String>): List<FloatArray>? {
        if (inputs.isEmpty()) return emptyList()
        if (!picturesAvailable(context)) return null
        val request = Bundle().apply { putStringArrayList(EmbeddingService.KEY_INPUTS, ArrayList(inputs)) }
        return ask(context, EmbeddingService.IMAGES, request)?.map { it ?: FloatArray(0) }
    }

    private suspend fun ask(context: Context, what: Int, request: Bundle): List<FloatArray?>? {
        val messenger = connect(context) ?: return null
        val id = nextId.getAndIncrement()
        val answer = CompletableDeferred<Bundle>()
        synchronized(pending) { pending[id] = answer }
        handler.removeCallbacks(unbind)
        val sent = runCatching {
            messenger.send(Message.obtain(null, what, id, 0).apply {
                data = request
                replyTo = replies
            })
        }.isSuccess
        val data = if (sent) withTimeoutOrNull(TIMEOUT_MS) { answer.await() } else null
        synchronized(pending) { pending.remove(id) }
        handler.postDelayed(unbind, UNBIND_AFTER_MS)
        if (data == null) {
            if (!sent) disconnect()
            return null
        }
        data.getString(EmbeddingService.KEY_ERROR)?.let {
            DebugLog.w(TAG, "the service could not embed: $it")
            return null
        }
        val flat = data.getFloatArray(EmbeddingService.KEY_VECTORS) ?: return null
        val valid = data.getBooleanArray(EmbeddingService.KEY_VALID) ?: return null
        val width = EmbeddingInput.DIMENSIONS
        return List(valid.size) { k -> if (valid[k]) flat.copyOfRange(k * width, (k + 1) * width) else null }
    }

    private fun answer(message: Message) {
        val waiting = synchronized(pending) { pending[message.arg1] } ?: return
        waiting.complete(message.data ?: Bundle())
    }

    private suspend fun connect(context: Context): Messenger? {
        service?.let { return it }
        return binding.withLock {
            service?.let { return@withLock it }
            val app = context.applicationContext
            appContext = app
            val ready = CompletableDeferred<Messenger?>()
            bound = ready
            val conn = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                    val messenger = binder?.let(::Messenger)
                    service = messenger
                    ready.complete(messenger)
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    // The process was ended (memory, most likely). The next
                    // call binds again and the system restarts it.
                    service = null
                    failPending()
                }

                override fun onBindingDied(name: ComponentName?) = disconnect()
            }
            val ok = runCatching {
                app.bindService(Intent(app, EmbeddingService::class.java), conn, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
            if (!ok) {
                runCatching { app.unbindService(conn) }
                return@withLock null
            }
            connection = conn
            withTimeoutOrNull(TIMEOUT_MS) { ready.await() }
        }
    }

    private fun failPending() {
        val waiting = synchronized(pending) { pending.values.toList() }
        for (deferred in waiting) deferred.complete(Bundle().apply { putString(EmbeddingService.KEY_ERROR, "service died") })
    }

    private fun disconnect() {
        val conn = connection ?: return
        connection = null
        service = null
        bound?.complete(null)
        runCatching { appContext?.unbindService(conn) }
        failPending()
    }
}
