package com.wasimaster.wmkeyboard.core.settings

/** When the keyboard keeps its socket to Home Assistant open. */
enum class HaLinkLifetime {
    /** Only while the Home Assistant panel is open. */
    PANEL,

    /** While the keyboard is on screen, and a minute after, so a quick app switch does not reconnect. */
    KEYBOARD,
}

/** Which tab the panel opens on. */
enum class HaOpenOn { FAVOURITES, LAST_TAB }

/** How many tiles fit across. */
enum class HaTileSize { COMFORTABLE, COMPACT }

/**
 * The Home Assistant tool, grouped the way every family here is: one
 * [KeyboardSettings] slot, flat `ha_*` DataStore keys.
 *
 * The tokens live here too, under keys listed in `SettingsBackup.SECRET_KEYS`,
 * so an export leaves them out unless asked and the direct-boot mirror never
 * holds them. Everything else is plain preference.
 */
data class HomeAssistantSettings(
    /** The master switch. Off until the user connects a server. */
    val enabled: Boolean = false,
    /** The address inside the house, as typed: `homeassistant.local:8123`, `192.168.1.5`. */
    val internalUrl: String = "",
    /** An address that works from anywhere (Nabu Casa, a reverse proxy). Blank for none. */
    val externalUrl: String = "",
    /** Try the house address first and fall back to the remote one when it does not answer. */
    val preferInternal: Boolean = true,
    /** What the server calls itself, learnt on connect. */
    val instanceName: String = "",
    /** The server's stable id, learnt on connect; a different one means a different house. */
    val instanceId: String = "",

    // Tokens. Secrets: see SettingsBackup.SECRET_KEYS.
    val accessToken: String = "",
    val refreshToken: String = "",
    /** When [accessToken] stops working, epoch millis; 0 for a long-lived token, which never does. */
    val tokenExpiresAtMs: Long = 0L,
    /** Whether [accessToken] is a long-lived token the user pasted rather than one a sign-in minted. */
    val longLivedToken: Boolean = false,
    /** The `state` of a browser sign-in under way, checked when the browser comes back. Not a secret. */
    val authState: String = "",

    val lifetime: HaLinkLifetime = HaLinkLifetime.KEYBOARD,
    /** Entity ids on the Favourites tab, in the user's order. */
    val favourites: List<String> = emptyList(),
    /** Entity ids the panel never lists. */
    val hidden: Set<String> = emptySet(),
    /** Whole domains the panel never lists (`sensor`, `automation`, …). */
    val hiddenDomains: Set<String> = emptySet(),
    val openOn: HaOpenOn = HaOpenOn.FAVOURITES,
    /** One tab per Home Assistant area, after Favourites. */
    val areasAsTabs: Boolean = true,
    val tileSize: HaTileSize = HaTileSize.COMFORTABLE,
    /** Locks, alarms, garage doors and sirens take a hold instead of a tap. */
    val confirmRisky: Boolean = true,
    /** What a sensor tile types: `value`, `value_unit` or `name_value` (see `HaInsertFormat`). */
    val insertFormat: String = "value_unit",
    /** The typed command bar at the bottom of the panel. */
    val assistEnabled: Boolean = true,
    /** The conversation agent Assist uses; blank for the server's default. */
    val assistAgentId: String = "",
    /** Send the keyboard's current language with each Assist sentence. */
    val assistFollowsKeyboardLanguage: Boolean = true,
    /** Keep the last states on the phone so the panel draws before the socket is up. */
    val cacheStates: Boolean = true,
    /** Offer a strip chip when a sentence reads like a command for the house. */
    val smartChip: Boolean = true,
    /** The panel tab last used. Remembered, never shown as a setting. */
    val lastTab: String = "",
) {
    /** Signed in, one way or the other. */
    val hasToken: Boolean get() = accessToken.isNotBlank()

    /** Something to connect to. */
    val hasServer: Boolean get() = internalUrl.isNotBlank() || externalUrl.isNotBlank()

    companion object {
        const val MAX_FAVOURITES = 200
        const val URL_MAX = 300
    }
}
