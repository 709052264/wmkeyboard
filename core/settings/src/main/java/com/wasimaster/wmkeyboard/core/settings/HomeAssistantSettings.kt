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
 * The smart home tool's panel preferences, grouped the way every family here
 * is: one [KeyboardSettings] slot, flat `ha_*` DataStore keys (the tool began
 * as Home Assistant alone, and the keys keep that name).
 *
 * The homes themselves, their addresses and their sign-ins, are the tool's
 * provider list (`ProviderTool.SMART_HOME`, see `homes()`), whose credentials
 * are already kept out of backups and the direct-boot mirror.
 */
data class HomeAssistantSettings(
    /** The master switch. Off until the user adds a home. */
    val enabled: Boolean = false,

    val lifetime: HaLinkLifetime = HaLinkLifetime.KEYBOARD,
    /** Entity refs (`HomeRef`: `home|entity_id`) on the Favourites tab, in the user's order. */
    val favourites: List<String> = emptyList(),
    /** Entity refs the panel never lists. */
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
    companion object {
        const val MAX_FAVOURITES = 200
    }
}
