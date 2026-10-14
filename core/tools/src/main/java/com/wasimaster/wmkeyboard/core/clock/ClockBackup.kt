package com.wasimaster.wmkeyboard.core.clock

import android.content.Context
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * The clock's part of a backup: alarms, timer presets, countdowns and the
 * tool's settings. Not what is running (timers, the stopwatch, a ring) and not
 * the history: a restore on another phone should set its alarms, not ring
 * yesterday's timer or claim it measured runs it never saw.
 */
object ClockBackup {

    fun portable(context: Context): JsonElement {
        val d = ClockStore.get(context).data
        val keep = ClockData(
            alarms = d.alarms.map { it.copy(snoozedUntil = 0, snoozeCount = 0, skipUntil = 0) },
            presets = d.presets,
            countdowns = d.countdowns,
            lastId = d.lastId,
        )
        return buildJsonObject {
            put("data", ClockStore.json.encodeToJsonElement(ClockData.serializer(), keep))
            ClockPrefs.raw(context)?.let { put("config", JsonPrimitive(it)) }
        }
    }

    /** Alarms in a bundle, for the restore dialog. */
    fun count(element: JsonElement): Int =
        runCatching { element.jsonObject["data"]?.jsonObject?.get("alarms")?.jsonArray?.size ?: 0 }.getOrDefault(0)

    /**
     * Puts a bundle's alarms, presets and countdowns in place of this phone's,
     * keeping what is running here, and re-arms. Returns false when the bundle
     * could not be read.
     */
    fun restore(context: Context, obj: JsonObject): Boolean {
        val incoming = runCatching {
            obj["data"]?.let { ClockStore.json.decodeFromJsonElement(ClockData.serializer(), it) }
        }.getOrNull() ?: return false
        ClockEngine.update(context) { d ->
            d.copy(
                alarms = incoming.alarms.sortedWith(ClockActions.AlarmOrder),
                presets = incoming.presets,
                countdowns = incoming.countdowns,
                // Never reuse an id something on this phone already holds.
                lastId = maxOf(d.lastId, incoming.lastId),
            )
        }
        (obj["config"] as? JsonPrimitive)?.content?.let { ClockPrefs.restore(context, it) }
        return true
    }
}
