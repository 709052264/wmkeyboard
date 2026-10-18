package com.wasimaster.wmkeyboard.ime.send

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The Android half of the direct link (#729): a Wi-Fi this phone opens for
 * the other, or the other's Wi-Fi joined.
 *
 * Hosting tries a Wi-Fi Direct group first (`WifiP2pManager.createGroup`),
 * which works from API 24 and keeps the phone's own Wi-Fi connection, and
 * falls back to a local-only hotspot (API 26+) on phones whose P2P stack
 * refuses. Either way the result is an SSID, a passphrase and the address this
 * phone has on that network, which go into the QR code.
 *
 * Joining uses `WifiNetworkSpecifier` on API 29+, a per-app connection the
 * user confirms once in a system sheet, which hands back the [Network] the
 * engine's sockets are then bound to; below that, the legacy
 * `WifiConfiguration` path.
 *
 * Permissions: `NEARBY_WIFI_DEVICES` from API 33 (declared `neverForLocation`),
 * `ACCESS_FINE_LOCATION` before that, for the group and the hotspot. Joining
 * by specifier needs neither. All callbacks arrive on the main thread.
 */
class DirectWifi(context: Context) : DirectLink {

    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val p2p = if (app.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)) {
        app.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    } else {
        null
    }

    private enum class Mode { HOST_P2P, HOST_HOTSPOT, JOIN }

    private var mode: Mode? = null
    private var listener: DirectLink.Listener? = null
    private var channel: WifiP2pManager.Channel? = null
    private var p2pReceiver: BroadcastReceiver? = null
    private var hotspot: WifiManager.LocalOnlyHotspotReservation? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var legacyReceiver: BroadcastReceiver? = null
    private var legacyNetworkId = -1
    private var hostingReported = false
    private val timeout = Runnable { onHostTimeout() }

    override val active: Boolean get() = mode != null

    override fun hasPermission(): Boolean = hasPermission(app)

    override fun canHost(): Boolean = wifi != null && (p2p != null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)

    // ---- hosting ----

    override fun host(listener: DirectLink.Listener) {
        stop()
        this.listener = listener
        hostingReported = false
        if (wifi?.isWifiEnabled != true && p2p == null) {
            fail(DirectFailure.WIFI_OFF)
            return
        }
        if (!hasPermission()) {
            fail(DirectFailure.NO_PERMISSION)
            return
        }
        val manager = p2p
        val ch = manager?.initialize(app, Looper.getMainLooper(), null)
        if (manager == null || ch == null) {
            hostHotspot()
            return
        }
        mode = Mode.HOST_P2P
        channel = ch
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
                onP2pConnectionChanged(manager, ch)
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        p2pReceiver = receiver
        main.postDelayed(timeout, HOST_TIMEOUT_MS)
        // A group left over from last time would answer "busy".
        val create = object : WifiP2pManager.ActionListener {
            override fun onSuccess() = Unit

            override fun onFailure(reason: Int) {
                if (mode == Mode.HOST_P2P) hostHotspot()
            }
        }
        try {
            manager.removeGroup(ch, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = manager.createGroup(ch, create)

                override fun onFailure(reason: Int) = manager.createGroup(ch, create)
            })
        } catch (e: SecurityException) {
            fail(DirectFailure.NO_PERMISSION)
        }
    }

    private fun onP2pConnectionChanged(manager: WifiP2pManager, ch: WifiP2pManager.Channel) {
        if (mode != Mode.HOST_P2P || hostingReported) return
        try {
            manager.requestConnectionInfo(ch) { info ->
                if (info == null || !info.groupFormed || !info.isGroupOwner) return@requestConnectionInfo
                manager.requestGroupInfo(ch) { group ->
                    if (group == null || hostingReported || mode != Mode.HOST_P2P) return@requestGroupInfo
                    val address = info.groupOwnerAddress?.hostAddress ?: ownAddress("p2p") ?: return@requestGroupInfo
                    val band = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && group.frequency > 3_000) "5" else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "2" else ""
                    hostingReported = true
                    main.removeCallbacks(timeout)
                    listener?.onHosting(group.networkName.orEmpty(), group.passphrase.orEmpty(), address, band)
                }
            }
        } catch (e: SecurityException) {
            fail(DirectFailure.NO_PERMISSION)
        }
    }

    private fun onHostTimeout() {
        if (hostingReported) return
        when (mode) {
            Mode.HOST_P2P -> hostHotspot()
            Mode.HOST_HOTSPOT -> fail(DirectFailure.GROUP_FAILED)
            else -> Unit
        }
    }

    /** The second way to open a Wi-Fi: Android's own hotspot, reserved for this app alone. */
    private fun hostHotspot() {
        tearDownP2p()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || wifi == null) {
            fail(DirectFailure.UNSUPPORTED)
            return
        }
        mode = Mode.HOST_HOTSPOT
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, HOST_TIMEOUT_MS)
        val callback = object : WifiManager.LocalOnlyHotspotCallback() {
            override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation?) {
                if (mode != Mode.HOST_HOTSPOT) {
                    reservation?.close()
                    return
                }
                hotspot = reservation
                val (ssid, passphrase) = credentials(reservation)
                if (ssid.isEmpty()) {
                    fail(DirectFailure.GROUP_FAILED)
                    return
                }
                // The hotspot's own address is on a fresh interface; look for it a moment later.
                main.postDelayed({
                    if (mode != Mode.HOST_HOTSPOT) return@postDelayed
                    val address = ownAddress("ap", "swlan", "wlan1", "softap") ?: HOTSPOT_DEFAULT_ADDRESS
                    hostingReported = true
                    main.removeCallbacks(timeout)
                    listener?.onHosting(ssid, passphrase, address, "")
                }, HOTSPOT_SETTLE_MS)
            }

            override fun onStopped() {
                if (mode == Mode.HOST_HOTSPOT) {
                    mode = null
                    hotspot = null
                    listener?.onStopped()
                }
            }

            override fun onFailed(reason: Int) {
                if (mode == Mode.HOST_HOTSPOT) fail(if (reason == ERROR_NO_CHANNEL || reason == ERROR_INCOMPATIBLE_MODE) DirectFailure.UNSUPPORTED else DirectFailure.GROUP_FAILED)
            }
        }
        try {
            wifi.startLocalOnlyHotspot(callback, main)
        } catch (e: SecurityException) {
            fail(DirectFailure.NO_PERMISSION)
        } catch (e: IllegalStateException) {
            fail(DirectFailure.GROUP_FAILED)
        }
    }

    @Suppress("DEPRECATION") // WifiConfiguration: the only shape below API 30.
    private fun credentials(reservation: WifiManager.LocalOnlyHotspotReservation?): Pair<String, String> {
        if (reservation == null) return "" to ""
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = reservation.softApConfiguration
            return config.ssid.orEmpty() to config.passphrase.orEmpty()
        }
        val config = reservation.wifiConfiguration ?: return "" to ""
        return config.SSID.orEmpty().trim('"') to config.preSharedKey.orEmpty().trim('"')
    }

    /** This phone's IPv4 on an interface whose name starts with one of [prefixes]. */
    private fun ownAddress(vararg prefixes: String): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.filter { nic -> nic.isUp && !nic.isLoopback && prefixes.any { nic.name.startsWith(it) } }
            ?.flatMap { it.inetAddresses.toList() }
            ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    // ---- joining ----

    override fun join(ssid: String, passphrase: String, listener: DirectLink.Listener) {
        stop()
        this.listener = listener
        mode = Mode.JOIN
        if (wifi?.isWifiEnabled == false) {
            fail(DirectFailure.WIFI_OFF)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val cm = connectivity ?: run {
                fail(DirectFailure.JOIN_FAILED)
                return
            }
            val specifier = WifiNetworkSpecifier.Builder()
                .setSsid(ssid)
                .apply { if (passphrase.isNotEmpty()) setWpa2Passphrase(passphrase) }
                .build()
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(specifier)
                .build()
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    if (mode == Mode.JOIN) main.post { listener.onJoined(network) }
                }

                override fun onUnavailable() {
                    if (mode == Mode.JOIN) main.post { fail(DirectFailure.JOIN_FAILED) }
                }

                override fun onLost(network: Network) {
                    if (mode == Mode.JOIN) main.post { listener.onStopped() }
                }
            }
            networkCallback = callback
            try {
                cm.requestNetwork(request, callback, JOIN_TIMEOUT_MS.toInt())
            } catch (e: SecurityException) {
                fail(DirectFailure.NO_PERMISSION)
            } catch (e: RuntimeException) {
                fail(DirectFailure.JOIN_FAILED)
            }
            return
        }
        joinLegacy(ssid, passphrase, listener)
    }

    /** API 24–28: add the network and switch to it; the state broadcast says when it is up. */
    @Suppress("DEPRECATION") // The only API that joins a network before 29.
    private fun joinLegacy(ssid: String, passphrase: String, listener: DirectLink.Listener) {
        val manager = wifi ?: run {
            fail(DirectFailure.JOIN_FAILED)
            return
        }
        val config = WifiConfiguration().apply {
            SSID = "\"$ssid\""
            if (passphrase.isEmpty()) allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE) else preSharedKey = "\"$passphrase\""
        }
        val id = try {
            manager.addNetwork(config)
        } catch (e: SecurityException) {
            fail(DirectFailure.NO_PERMISSION)
            return
        }
        if (id < 0) {
            fail(DirectFailure.JOIN_FAILED)
            return
        }
        legacyNetworkId = id
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (mode != Mode.JOIN) return
                val info = manager.connectionInfo ?: return
                if (info.ssid?.trim('"') == ssid && info.networkId == id) {
                    listener.onJoined(null)
                    legacyReceiver?.let { runCatching { app.unregisterReceiver(it) } }
                    legacyReceiver = null
                }
            }
        }
        ContextCompat.registerReceiver(
            app, receiver, IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        legacyReceiver = receiver
        main.postDelayed({ if (mode == Mode.JOIN && legacyReceiver != null) fail(DirectFailure.TIMEOUT) }, JOIN_TIMEOUT_MS)
        manager.disconnect()
        manager.enableNetwork(id, true)
        manager.reconnect()
    }

    // ---- teardown ----

    override fun stop() {
        main.removeCallbacks(timeout)
        tearDownP2p()
        hotspot?.let { runCatching { it.close() } }
        hotspot = null
        networkCallback?.let { cb -> runCatching { connectivity?.unregisterNetworkCallback(cb) } }
        networkCallback = null
        legacyReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        legacyReceiver = null
        if (legacyNetworkId >= 0) {
            @Suppress("DEPRECATION")
            runCatching { wifi?.removeNetwork(legacyNetworkId) }
            legacyNetworkId = -1
        }
        mode = null
        listener = null
    }

    private fun tearDownP2p() {
        p2pReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        p2pReceiver = null
        val ch = channel
        if (ch != null) {
            runCatching { p2p?.removeGroup(ch, null) }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) runCatching { ch.close() }
        }
        channel = null
    }

    private fun fail(reason: DirectFailure) {
        val l = listener
        stop()
        l?.onFailed(reason)
    }

    companion object {
        private const val HOST_TIMEOUT_MS = 15_000L
        private const val HOTSPOT_SETTLE_MS = 800L
        private const val JOIN_TIMEOUT_MS = 30_000L
        private const val HOTSPOT_DEFAULT_ADDRESS = "192.168.43.1"
        private const val ERROR_NO_CHANNEL = WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL
        private const val ERROR_INCOMPATIBLE_MODE = WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE

        /** The grant a group or a hotspot needs on this Android version. */
        fun hasPermission(context: Context): Boolean {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.NEARBY_WIFI_DEVICES
            } else {
                Manifest.permission.ACCESS_FINE_LOCATION
            }
            return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
}
