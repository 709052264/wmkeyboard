package com.wasimaster.wmkeyboard.ime.homeassistant

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.wasimaster.wmkeyboard.core.homeassistant.HaBytesResponse
import com.wasimaster.wmkeyboard.core.homeassistant.HaDiscovery
import com.wasimaster.wmkeyboard.core.homeassistant.HaFound
import com.wasimaster.wmkeyboard.core.homeassistant.HaHttp
import com.wasimaster.wmkeyboard.core.homeassistant.HaHttpResponse
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.tools.ToolHttp
import com.wasimaster.wmkeyboard.core.tools.ToolHttpException
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The engine's HTTP, routed through [ToolHttp] so every request lands in the
 * network activity log under the tool's own name and stops in a build without
 * the internet permission. The path shown in the log is the API route and
 * never the query, which could carry a sentence the user typed.
 */
class ToolHaHttp(private val cacheDir: File) : HaHttp {

    private fun route(url: String): String? = ToolHttp.run { runCatching { java.net.URL(url).path }.getOrNull() }

    override fun get(url: String, headers: Map<String, String>, timeoutMs: Int): HaHttpResponse =
        ToolHttp.exchange(url, "GET", null, headers, emptySet(), timeoutMs, NetSource.HOME_ASSISTANT, route(url)) { ex ->
            HaHttpResponse(ex.status, ex.body?.readText().orEmpty())
        }

    override fun postJson(url: String, body: String, headers: Map<String, String>, timeoutMs: Int): HaHttpResponse =
        ToolHttp.exchange(url, "POST", body, headers, emptySet(), timeoutMs, NetSource.HOME_ASSISTANT, route(url)) { ex ->
            HaHttpResponse(ex.status, ex.body?.readText().orEmpty())
        }

    override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>, timeoutMs: Int): HaHttpResponse =
        try {
            HaHttpResponse(HTTP_OK, ToolHttp.postForm(url, form, timeoutMs, NetSource.HOME_ASSISTANT, route(url)))
        } catch (e: ToolHttpException) {
            HaHttpResponse(e.status, e.apiMessage.orEmpty())
        }

    override fun getBytes(url: String, headers: Map<String, String>, timeoutMs: Int): HaBytesResponse {
        val target = File(cacheDir, "homeassistant/snapshot-${System.nanoTime()}.bin")
        return try {
            ToolHttp.download(url, target, timeoutMs, MAX_SNAPSHOT_BYTES, null, headers, NetSource.HOME_ASSISTANT, route(url))
            HaBytesResponse(HTTP_OK, target.readBytes(), null)
        } catch (e: ToolHttpException) {
            HaBytesResponse(e.status, ByteArray(0), null)
        } finally {
            target.delete()
        }
    }

    private companion object {
        const val HTTP_OK = 200
        const val MAX_SNAPSHOT_BYTES = 8L * 1024 * 1024
    }
}

/**
 * `_home-assistant._tcp` over Android's `NsdManager`: every instance on the
 * LAN, with the addresses its record names. Browse only, nothing announced.
 * Best effort, like the KDE Connect one: `NsdManager` fails in vendor-specific
 * ways, and "type the address" is always there.
 */
class HaAndroidMdns(context: Context) : HaDiscovery {

    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private val resolving = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    override fun start(onFound: (HaFound) -> Unit) {
        val manager = nsd ?: return
        stop()
        runCatching {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicast = wifi?.createMulticastLock("wmkb-homeassistant")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                val name = service.serviceName ?: return
                if (!resolving.add(name)) return
                resolve(manager, service, onFound)
            }
        }
        runCatching { manager.discoverServices(HaFound.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { discovery = listener }
    }

    @Suppress("DEPRECATION") // resolveService: its replacement is API 34, and this floor is 24.
    private fun resolve(manager: NsdManager, service: NsdServiceInfo, onFound: (HaFound) -> Unit) {
        val listener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                resolving.remove(info.serviceName.orEmpty())
            }

            override fun onServiceResolved(info: NsdServiceInfo) {
                resolving.remove(info.serviceName.orEmpty())
                val host = info.host?.hostAddress ?: return
                val txt = info.attributes.mapValues { (_, v) -> v?.toString(Charsets.UTF_8) }
                onFound(HaFound.fromTxt(txt, host, info.port))
            }
        }
        runCatching { manager.resolveService(service, listener) }.onFailure { resolving.remove(service.serviceName.orEmpty()) }
    }

    @Synchronized
    override fun stop() {
        discovery?.let { runCatching { nsd?.stopServiceDiscovery(it) } }
        discovery = null
        runCatching { multicast?.release() }
        multicast = null
        resolving.clear()
    }
}

/**
 * `_hue._tcp` over `NsdManager`: every Hue bridge on the LAN, by address.
 * The bridge's name and id are asked of its open config endpoint by the
 * caller, so this only hands over the host.
 */
class HueAndroidMdns(context: Context) {

    private val app = context.applicationContext
    private val nsd = app.getSystemService(Context.NSD_SERVICE) as? NsdManager
    private var discovery: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private val resolving = ConcurrentHashMap.newKeySet<String>()

    @Synchronized
    fun start(onFound: (host: String) -> Unit) {
        val manager = nsd ?: return
        stop()
        runCatching {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicast = wifi?.createMulticastLock("wmkb-hue")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onServiceLost(service: NsdServiceInfo) = Unit
            override fun onServiceFound(service: NsdServiceInfo) {
                val name = service.serviceName ?: return
                if (!resolving.add(name)) return
                @Suppress("DEPRECATION") // resolveService: its replacement is API 34, and this floor is 24.
                runCatching {
                    manager.resolveService(
                        service,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                                resolving.remove(info.serviceName.orEmpty())
                            }

                            override fun onServiceResolved(info: NsdServiceInfo) {
                                resolving.remove(info.serviceName.orEmpty())
                                info.host?.hostAddress?.let(onFound)
                            }
                        },
                    )
                }.onFailure { resolving.remove(name) }
            }
        }
        runCatching { manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onSuccess { discovery = listener }
    }

    @Synchronized
    fun stop() {
        discovery?.let { runCatching { nsd?.stopServiceDiscovery(it) } }
        discovery = null
        runCatching { multicast?.release() }
        multicast = null
        resolving.clear()
    }

    private companion object {
        const val SERVICE_TYPE = "_hue._tcp"
    }
}
