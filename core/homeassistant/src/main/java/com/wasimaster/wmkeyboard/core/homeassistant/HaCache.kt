package com.wasimaster.wmkeyboard.core.homeassistant

import kotlinx.serialization.Serializable

/**
 * What the keyboard keeps on disk between sessions: the last instance, every
 * entity and the registry, so the panel draws at once and the socket only has
 * to bring it up to date. No tokens here; those are settings.
 */
@Serializable
data class HaSnapshot(
    val instance: HaInstance? = null,
    val entities: List<HaEntity> = emptyList(),
    val registry: HaRegistry = HaRegistry(),
    val savedAtMs: Long = 0L,
    val version: Int = VERSION,
) {
    companion object {
        const val VERSION = 1
    }
}

object HaCache {
    fun encode(snapshot: HaSnapshot): String = HaJson.encodeToString(HaSnapshot.serializer(), snapshot)

    /** Null for anything unreadable, including a snapshot from another layout version. */
    fun decode(text: String): HaSnapshot? = runCatching { HaJson.decodeFromString(HaSnapshot.serializer(), text) }
        .getOrNull()?.takeIf { it.version == HaSnapshot.VERSION }
}
