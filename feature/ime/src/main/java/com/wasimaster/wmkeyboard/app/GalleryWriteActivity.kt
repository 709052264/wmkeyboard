package com.wasimaster.wmkeyboard.app

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Invisible trampoline for Android's "Allow WM Keyboard to modify this
 * photo?" (#708), which the gallery editor's "Replace the original" needs for
 * a photo another app made. An input method cannot launch an intent sender
 * for a result, so this does, and hands the answer back to the keyboard in
 * the same process, which then writes the photo itself.
 */
class GalleryWriteActivity : ComponentActivity() {

    private class Request(val sender: IntentSender, val onAnswer: (Boolean) -> Unit)

    companion object {
        private var pending: Request? = null

        /** Asks through [sender]; [onAnswer] runs on the main thread with the user's yes or no. */
        fun intent(context: Context, sender: IntentSender, onAnswer: (Boolean) -> Unit): Intent {
            pending?.onAnswer?.invoke(false)
            pending = Request(sender, onAnswer)
            return Intent(context, GalleryWriteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        private fun answer(yes: Boolean) {
            val request = pending ?: return
            pending = null
            request.onAnswer(yes)
        }
    }

    private val consent = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        answer(result.resultCode == RESULT_OK)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A restarted activity is already waiting on the dialog it started.
        if (savedInstanceState != null) return
        val request = pending
        val launched = request != null &&
            runCatching { consent.launch(IntentSenderRequest.Builder(request.sender).build()) }.isSuccess
        if (!launched) {
            answer(false)
            finish()
        }
    }
}
