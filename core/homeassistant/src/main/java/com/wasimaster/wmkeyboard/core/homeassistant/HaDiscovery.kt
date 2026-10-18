package com.wasimaster.wmkeyboard.core.homeassistant

/** An instance announced on the LAN over `_home-assistant._tcp`. */
data class HaFound(
    val name: String,
    val uuid: String?,
    val version: String?,
    val internalUrl: String?,
    val externalUrl: String?,
    val host: String,
    val port: Int,
) {
    /** The address to connect to from inside the house: what HA says its internal URL is, else where it answered from. */
    val internal: HaUrl? get() = internalUrl?.let(HaUrl::parse) ?: HaUrl.parse("http://$host:$port")

    val external: HaUrl? get() = externalUrl?.let(HaUrl::parse)

    /** Still setting itself up: the landing page, no Core behind it yet. */
    val isLandingPage: Boolean get() = version == "0000.0.0"

    companion object {
        const val SERVICE_TYPE = "_home-assistant._tcp"

        /** From the resolved mDNS record's TXT attributes. */
        fun fromTxt(attributes: Map<String, String?>, host: String, port: Int): HaFound = HaFound(
            name = attributes["location_name"]?.takeIf { it.isNotBlank() } ?: "Home",
            uuid = attributes["uuid"],
            version = attributes["version"],
            internalUrl = attributes["internal_url"]?.takeIf { it.isNotBlank() },
            externalUrl = attributes["external_url"]?.takeIf { it.isNotBlank() } ?: attributes["base_url"]?.takeIf { it.isNotBlank() && it.startsWith("https") },
            host = host,
            port = port,
        )
    }
}

/** The LAN browse, implemented with Android's NsdManager on the keyboard side. */
interface HaDiscovery {
    fun start(onFound: (HaFound) -> Unit)
    fun stop()
}
