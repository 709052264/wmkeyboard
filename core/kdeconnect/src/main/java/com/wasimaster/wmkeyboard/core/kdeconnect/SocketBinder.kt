package com.wasimaster.wmkeyboard.core.kdeconnect

import java.net.DatagramSocket
import java.net.Socket

/**
 * Pins the engine's outgoing sockets to one network.
 *
 * On a phone the devices this engine talks to are not always on the default
 * network: a Nearby transfer runs over a Wi-Fi Direct group or a local hotspot
 * the phone joined just for it, while mobile data stays the default route. A
 * plain socket would be routed by that default and never reach the peer. The
 * host implements this with `Network.bindSocket`, something a JVM module cannot
 * name, and installs it on [KdeConnectEngine.socketBinder].
 *
 * Each method is called once per socket, right after it is created and before
 * it connects or sends. Listening sockets are left alone: they accept on every
 * interface, and the network a connection arrived on is the one its replies
 * take.
 */
interface KdeSocketBinder {
    /** A TCP socket about to dial a device: the control link, or a payload fetch. */
    fun bindOutgoing(socket: Socket)

    /** The UDP socket announcements are sent from. */
    fun bindDatagram(socket: DatagramSocket)
}
