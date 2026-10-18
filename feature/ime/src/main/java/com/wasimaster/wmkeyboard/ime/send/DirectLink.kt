package com.wasimaster.wmkeyboard.ime.send

import android.net.Network

/**
 * A Wi-Fi link between two phones with no network between them: one opens a
 * Wi-Fi (a Wi-Fi Direct group, or a local-only hotspot where that fails) and
 * the other joins it. Everything else (finding the device, pairing, the bytes)
 * is the KDE Connect engine's, over that link.
 *
 * An interface so the controller stays free of the platform: `DirectWifi` is
 * the Android implementation, and tests hand in a stub.
 */
interface DirectLink {

    interface Listener {
        /** This phone's Wi-Fi is up; [hostAddress] is where it can be reached on it. */
        fun onHosting(ssid: String, passphrase: String, hostAddress: String, band: String)

        /** This phone is on the other one's Wi-Fi; sockets bound to [network] go over it. */
        fun onJoined(network: Network?)

        fun onFailed(reason: DirectFailure)

        /** The link went away: the group was removed, the network was lost. */
        fun onStopped()
    }

    /** Whether Android would let this app open or join a Wi-Fi right now. */
    fun hasPermission(): Boolean

    /** Whether the device has Wi-Fi hardware that can host a group or a hotspot. */
    fun canHost(): Boolean

    fun host(listener: Listener)

    fun join(ssid: String, passphrase: String, listener: Listener)

    /** Tears down whatever [host] or [join] set up. Safe to call twice. */
    fun stop()

    val active: Boolean
}
