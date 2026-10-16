package com.wasimaster.wmkeyboard.core.kdeconnect

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.put

/**
 * Sync files between the phone and WM Keyboard on a computer, both ways
 * (`kdeconnect.wmkeyboard.sync`, `.sync.request`). The engine only carries
 * them: what a sync file means, and where it is kept, is the host's.
 *
 * A file rides in the packet itself, gzip and base64, rather than as a
 * payload: sync files are small, and a packet needs no second socket.
 */
class WmkSyncPlugin internal constructor(host: KdePluginHost) : KdePlugin(host) {
    override val incoming = setOf(KdeTypes.WMK_SYNC, KdeTypes.WMK_SYNC_REQUEST)
    override val outgoing = setOf(KdeTypes.WMK_SYNC, KdeTypes.WMK_SYNC_REQUEST)
    override val key = KdePluginKeys.SYNC

    /** Whether [deviceId] is WM Keyboard and within reach. */
    fun canSync(deviceId: String): Boolean = host.device(deviceId)?.canSend(KdeTypes.WMK_SYNC) == true

    /** Sends a sync file. False when the device is out of reach or is not WM Keyboard. */
    fun send(deviceId: String, fileName: String, bytes: ByteArray): Boolean {
        if (!enabledFor(deviceId) || !isSyncFileName(fileName)) return false
        val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()
        return host.send(
            deviceId,
            kdePacket(KdeTypes.WMK_SYNC) {
                put("name", fileName)
                put("data", Base64.getEncoder().encodeToString(zipped))
            },
        )
    }

    /** Asks the device for a sync pass and its file. */
    fun request(deviceId: String): Boolean =
        enabledFor(deviceId) && host.send(deviceId, kdePacket(KdeTypes.WMK_SYNC_REQUEST))

    override fun onConnected(deviceId: String) {
        val device = host.device(deviceId) ?: return
        if (KdeTypes.WMK_SYNC in device.accepts && enabledFor(deviceId)) {
            host.emit(KdeEvent.SyncReachable(deviceId, device.name))
        }
    }

    override fun onPacket(deviceId: String, packet: KdePacket) {
        if (!enabledFor(deviceId)) return
        when (packet.type) {
            KdeTypes.WMK_SYNC -> {
                val name = packet.string("name") ?: return
                // Only a sync file's own name: nothing that walks out of the
                // mailbox it is written to.
                if (!isSyncFileName(name)) return
                val data = packet.string("data") ?: return
                val bytes = runCatching {
                    val zipped = Base64.getDecoder().decode(data)
                    GZIPInputStream(ByteArrayInputStream(zipped)).use { input ->
                        val out = ByteArrayOutputStream()
                        val buffer = ByteArray(BUFFER)
                        var total = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            total += n
                            if (total > MAX_FILE_BYTES) return
                            out.write(buffer, 0, n)
                        }
                        out.toByteArray()
                    }
                }.getOrNull() ?: return
                val deviceName = host.device(deviceId)?.name.orEmpty()
                host.emit(KdeEvent.SyncFile(deviceId, deviceName, name, bytes))
            }
            KdeTypes.WMK_SYNC_REQUEST -> host.emit(KdeEvent.SyncRequested(deviceId))
        }
    }

    companion object {
        private const val BUFFER = 64 * 1024
        private const val MAX_FILE_BYTES = 32L * 1024 * 1024

        /** `wmkeyboard-sync_<eight hex>…​.wmsync.json|enc`, and no path in it. */
        private val SYNC_NAME = Regex("""^wmkeyboard-sync_[0-9a-f]{8}[^/\\]*\.wmsync( \(\d+\))?\.(json|enc)$""")

        fun isSyncFileName(name: String): Boolean = SYNC_NAME.matches(name) && !name.contains("..")
    }
}

/**
 * What a computer's pairing code says: `wmkeyboard://pair?h=&p=&i=&f=&t=&n=`,
 * its address, TCP port, device id, certificate fingerprint, a one-time token
 * and its name. The computer accepts a pair request that brings the token
 * back without asking; the fingerprint lets the phone be sure it reached the
 * computer the code came from.
 */
data class KdePairCode(
    val host: String,
    val port: Int,
    val deviceId: String,
    val fingerprint: String,
    val token: String,
    val name: String,
) {
    companion object {
        fun parse(link: String): KdePairCode? {
            val uri = runCatching { java.net.URI(link.trim()) }.getOrNull() ?: return null
            // The web form the computer's QR code holds, which a phone camera
            // opens: https://<docs>/open/#wmkeyboard://pair?... (the fragment
            // comes back decoded once).
            if (uri.scheme.equals("https", ignoreCase = true) && uri.rawPath.orEmpty().trimEnd('/') == "/open") {
                val inner = uri.fragment?.trim().orEmpty()
                return if (inner.startsWith("wmkeyboard://pair")) parse(inner) else null
            }
            if (uri.scheme != "wmkeyboard" || uri.host != "pair") return null
            val query = uri.rawQuery ?: return null
            val q = query.split('&').mapNotNull { part ->
                val i = part.indexOf('=')
                if (i <= 0) null
                else {
                    val raw = part.substring(i + 1)
                    // Already decoded on the way in (a web link's fragment)
                    // leaves a bare % that a second decode would choke on.
                    part.substring(0, i) to (runCatching { java.net.URLDecoder.decode(raw, Charsets.UTF_8.name()) }.getOrNull() ?: raw)
                }
            }.toMap()
            val host = q["h"]?.takeIf { it.isNotBlank() } ?: return null
            val port = q["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val id = q["i"]?.takeIf { it.isNotBlank() } ?: return null
            val token = q["t"]?.takeIf { it.length >= 16 } ?: return null
            return KdePairCode(host, port, id, q["f"].orEmpty(), token, q["n"].orEmpty())
        }

        /** Fingerprints compared without their colons or case. */
        fun sameFingerprint(a: String, b: String): Boolean =
            a.isNotEmpty() && a.replace(":", "").equals(b.replace(":", ""), ignoreCase = true)
    }
}
