package com.wasimaster.wmkeyboard.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Invisible trampoline for the AI reply tool's tidy-up (#234): shows Android's
 * own "allow WM Keyboard to delete this screenshot?" dialog.
 *
 * From Android 11 an app may only delete media it created; anything else needs
 * that consent, and the consent is an activity-result flow an input method
 * cannot run. The keyboard starts this only after the chosen reply is already
 * in the field, so the field losing focus for the length of one dialog costs
 * nothing — the next keystroke lands where it would have anyway.
 *
 * The request travels in [pending] rather than as an extra: a [PendingIntent]
 * is parcelable, but reading one back is a version-split API, and the IME and
 * this activity share a process.
 */
class ReplyShotDeleteActivity : ComponentActivity() {

    companion object {
        @Volatile
        private var pending: PendingIntent? = null

        /** Shows the consent dialog for [request]. Call from the IME. */
        fun start(context: Context, request: PendingIntent) {
            pending = request
            context.startActivity(
                Intent(context, ReplyShotDeleteActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }
    }

    private val launcher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) {
        // Allowed or not, there is nothing to report back: the user just
        // answered the system's own dialog, which said what it would do.
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val request = pending.also { pending = null }
        if (request == null) {
            finish()
            return
        }
        runCatching {
            launcher.launch(IntentSenderRequest.Builder(request.intentSender).build())
        }.onFailure { finish() }
    }
}
