package com.wasimaster.wmkeyboard.app.kdeconnect

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.wasimaster.wmkeyboard.R
import com.wasimaster.wmkeyboard.core.kdeconnect.KdeDevice
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.kdeconnect.KdeConnectHub
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What the two activities below share: bring the link up, wait for a computer,
 * send, and stay alive until the last byte has gone.
 *
 * The staying alive is not tidiness. A `content://` URI handed over by another
 * app — or by the document picker — is readable only while the activity it was
 * granted to exists, and the computer fetches a file a moment *after* it is
 * offered. Finishing at "queued" would revoke the grant under the transfer.
 */
abstract class KdeSendActivity : ComponentActivity() {

    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KdeConnectHub.attach(this)
        KdeConnectHub.hold(KdeConnectHub.Reason.SHARE)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        KdeConnectHub.release(KdeConnectHub.Reason.SHARE)
        super.onDestroy()
    }

    /**
     * Sends to [deviceId] if given, else to the only paired computer, else asks
     * which. Finishes the activity when done.
     *
     * The link comes up one computer at a time, so the first to connect is not
     * the only one there (#703): with several paired, the picker lists them all
     * and enables each as it connects, rather than sending to whichever won.
     */
    protected fun deliver(uris: List<Uri>, text: String?, deviceId: String?) {
        lifecycleScope.launch {
            val settings = SettingsRepository(applicationContext).settings.first().kdeConnect
            if (!settings.enabled) return@launch done(getString(R.string.kdeconnect_share_off))
            // The keyboard normally feeds the hub its settings; a share can
            // arrive in a process where it has not run yet.
            KdeConnectHub.applySettings(settings)
            progress(getString(R.string.kdeconnect_share_connecting))
            if (deviceId != null) {
                KdeConnectHub.awaitConnected(deviceId, CONNECT_TIMEOUT_MS)?.let { return@launch send(it, uris, text) }
            }
            val connected = KdeConnectHub.awaitConnected(CONNECT_TIMEOUT_MS)
            val paired = KdeConnectHub.state.value.paired
            when {
                connected.isEmpty() -> done(getString(R.string.kdeconnect_share_none))
                paired.size <= 1 && connected.size == 1 -> send(connected.single(), uris, text)
                else -> choose { send(it, uris, text) }
            }
        }
    }

    /**
     * Every paired computer, in a stable order so a row never moves under the
     * finger; the ones not connected yet are greyed and fill in live.
     */
    private fun choose(onPick: (KdeDevice) -> Unit) {
        dialog?.dismiss()
        val adapter = DeviceAdapter()
        var watch: Job? = null
        dialog = AlertDialog.Builder(this)
            .setTitle(R.string.kdeconnect_share_choose)
            .setAdapter(adapter) { _, which ->
                watch?.cancel()
                adapter.getItem(which)?.let(onPick)
            }
            .setOnCancelListener { finish() }
            .show()
        watch = lifecycleScope.launch {
            KdeConnectHub.state.collect { state -> adapter.show(state.paired) }
        }
    }

    private inner class DeviceAdapter : ArrayAdapter<KdeDevice>(this, android.R.layout.select_dialog_item) {
        fun show(devices: List<KdeDevice>) {
            setNotifyOnChange(false)
            clear()
            addAll(devices)
            notifyDataSetChanged()
        }

        override fun areAllItemsEnabled(): Boolean = false

        override fun isEnabled(position: Int): Boolean = getItem(position)?.reachable == true

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = super.getView(position, convertView, parent) as TextView
            val device = getItem(position) ?: return view
            val ready = isEnabled(position)
            view.text = if (ready) device.name else getString(R.string.kdeconnect_share_choose_waiting, device.name)
            view.isEnabled = ready
            view.alpha = if (ready) 1f else 0.5f
            return view
        }
    }

    private fun send(device: KdeDevice, uris: List<Uri>, text: String?) {
        lifecycleScope.launch {
            val queued = KdeConnectHub.send(this@KdeSendActivity, device.id, uris, text)
            if (uris.isNotEmpty() && queued == 0) return@launch done(getString(R.string.kdeconnect_share_unreadable))
            if (queued > 0) {
                progress(getString(R.string.kdeconnect_share_sending, device.name))
                // Queued a moment ago: give the engine a beat to list them.
                delay(300)
                while (KdeConnectHub.activeOutgoing(device.id) > 0) delay(250)
            }
            done(getString(R.string.kdeconnect_share_sent, device.name))
        }
    }

    private fun progress(message: String) {
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this).setMessage(message).setOnCancelListener { finish() }.show()
    }

    private fun done(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).show()
        finish()
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 8_000L
    }
}

/**
 * "Send to computer" in another app's share sheet. Reached only through the
 * manifest's `KdeShareAlias`, which [KdeConnectHub] enables once something is
 * paired.
 */
class KdeShareActivity : KdeSendActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris: List<Uri> = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.stream())
            Intent.ACTION_SEND_MULTIPLE -> intent.streams()
            else -> emptyList()
        }
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (uris.isEmpty() && text.isNullOrBlank()) {
            finish()
            return
        }
        deliver(uris, text, deviceId = null)
    }

    @Suppress("DEPRECATION")
    private fun Intent.stream(): Uri? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableExtra(Intent.EXTRA_STREAM)

    @Suppress("DEPRECATION")
    private fun Intent.streams(): List<Uri> =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else getParcelableArrayListExtra(Intent.EXTRA_STREAM)).orEmpty()
}

/** The keyboard's "Files…": a document picker the keyboard itself cannot show. */
class KdeFilePickerActivity : KdeSendActivity() {
    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) finish() else deliver(uris, text = null, deviceId = intent?.getStringExtra(KdeConnectHub.EXTRA_DEVICE))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) picker.launch(arrayOf("*/*"))
    }
}
