package com.wasimaster.wmkeyboard.app.tips

import android.content.Context
import androidx.core.content.edit
import com.wasimaster.wmkeyboard.app.DOCS_URL
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.tools.ToolHttpException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Getting a Play tipper's name onto the Supporters list.
 *
 * Play tells the app an order is paid; only the docs site's `/api/play-tip`
 * can add a name to the shared list, and it checks the purchase token with
 * Google first. Between the two sits this queue, because the tip and the
 * credit are separate things: the money has arrived the moment Play says so,
 * and the name is a courtesy that may have to wait for a network, or for the
 * server to be set up at all. So a credit is written down before it is sent
 * and crossed off only when the server has answered for it.
 *
 * Its own SharedPreferences file, not KeyboardSettings: nothing here is a
 * setting, and none of it should travel in a backup.
 */
internal class TipCredits(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The name typed last time, offered again so a second tip is one press. */
    var name: String
        get() = prefs.getString(KEY_NAME, null).orEmpty()
        set(value) = prefs.edit { putString(KEY_NAME, value.trim()) }

    /**
     * Remembers the name meant for a tier's next order before Play's sheet
     * opens. The order can come back after the screen, or the whole process,
     * is gone: Play hands an unfinished purchase back on the next connection,
     * and this is how it gets its name.
     */
    fun intend(tier: TipTier, name: String) = prefs.edit { putString(KEY_INTENT + tier.productId, name.trim()) }

    fun intendedName(tier: TipTier): String = prefs.getString(KEY_INTENT + tier.productId, null).orEmpty()

    /**
     * Queues the credit for one paid order, once, and forgets the intent. A
     * blank name queues nothing: the user chose to stay off the list.
     */
    @Synchronized
    fun enqueue(tier: TipTier, purchaseToken: String, name: String) {
        prefs.edit { remove(KEY_INTENT + tier.productId) }
        if (name.isBlank()) return
        val queue = read()
        if (queue.any { it.token == purchaseToken }) return
        write(queue + Credit(tier.productId, purchaseToken, name.trim(), System.currentTimeMillis()))
    }

    /**
     * Sends every queued credit. Blocking; call it off the main thread.
     * Returns the names the server has just listed.
     */
    fun flush(): List<String> {
        val listed = mutableListOf<String>()
        for (credit in synchronized(this) { read() }) {
            when (send(credit)) {
                Outcome.LISTED -> listed += credit.name
                Outcome.DONE -> Unit
                Outcome.RETRY -> if (System.currentTimeMillis() - credit.queuedAt < GIVE_UP_MS) continue
            }
            synchronized(this) { write(read().filterNot { it.token == credit.token }) }
        }
        return listed
    }

    private enum class Outcome { LISTED, DONE, RETRY }

    private fun send(credit: Credit): Outcome = try {
        val body = JSONObject()
            .put("productId", credit.productId)
            .put("purchaseToken", credit.token)
            .put("name", credit.name)
            .toString()
        val reply = ToolHttp.postJson(
            PLAY_TIP_URL,
            body,
            timeoutMs = 20_000,
            source = NetSource.SUPPORTERS,
            route = "/api/play-tip",
        )
        // "ok" is a new entry; "test" and "anonymous" are answers that will
        // never change, so they are done too, just with nothing to show.
        if (JSONObject(reply).optString("status") == "ok") Outcome.LISTED else Outcome.DONE
    } catch (error: ToolHttpException) {
        // A 4xx is the server's considered no (a token Google does not know,
        // an order refunded since), and asking again gets the same answer.
        // A 5xx is a server that is not set up yet or cannot reach Google.
        DebugLog.w(TAG, "play-tip answered ${error.status}")
        if (error.status in 400..499) Outcome.DONE else Outcome.RETRY
    } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
        DebugLog.w(TAG, "play-tip unreachable: ${error.message.orEmpty()}")
        Outcome.RETRY
    }

    private data class Credit(val productId: String, val token: String, val name: String, val queuedAt: Long)

    private fun read(): List<Credit> = runCatching {
        val array = JSONArray(prefs.getString(KEY_QUEUE, null) ?: return emptyList())
        (0 until array.length()).map { i ->
            val item = array.getJSONObject(i)
            Credit(item.getString("p"), item.getString("t"), item.getString("n"), item.optLong("q"))
        }
    }.getOrDefault(emptyList())

    private fun write(queue: List<Credit>) {
        val array = JSONArray()
        queue.forEach {
            array.put(JSONObject().put("p", it.productId).put("t", it.token).put("n", it.name).put("q", it.queuedAt))
        }
        prefs.edit { if (queue.isEmpty()) remove(KEY_QUEUE) else putString(KEY_QUEUE, array.toString()) }
    }
}

private const val PLAY_TIP_URL = "$DOCS_URL/api/play-tip"
private const val PREFS = "tips"
private const val KEY_NAME = "name"
private const val KEY_INTENT = "intent_"
private const val KEY_QUEUE = "queue"

/** A credit the server still has not taken after this long is dropped, not carried forever. */
private const val GIVE_UP_MS = 30L * 24 * 60 * 60 * 1000

private const val TAG = "Tips"
