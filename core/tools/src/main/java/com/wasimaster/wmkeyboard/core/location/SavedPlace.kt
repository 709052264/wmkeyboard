package com.wasimaster.wmkeyboard.core.location

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * A place the user named (#350): Home, Work, the café. Inserting one needs no
 * permission and no network, which is the whole point of saving it.
 *
 * The exact point is kept. Precision is applied when it is shared, like a
 * live reading, so changing the precision later changes every place at once.
 */
data class SavedPlace(
    val id: Long,
    val name: String,
    val point: GeoPoint,
    /** The short address it was saved with; may be blank. */
    val address: String = "",
)

/** [SavedPlace] lists as one JSON string in a single preference. */
object SavedPlaces {

    /** Enough for every place anyone types a name for; the list is drawn whole. */
    const val MAX = 50

    fun decode(text: String?): List<SavedPlace> {
        if (text.isNullOrBlank()) return emptyList()
        val array = runCatching { Json.parseToJsonElement(text).jsonArray }.getOrNull() ?: return emptyList()
        return array.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val id = obj.number("id")?.toLong() ?: obj["id"]?.jsonPrimitive?.longOrNull ?: return@mapNotNull null
            val lat = obj.number("lat") ?: return@mapNotNull null
            val lon = obj.number("lon") ?: return@mapNotNull null
            val point = GeoPoint(lat, lon).takeIf { it.isValid } ?: return@mapNotNull null
            SavedPlace(
                id = id,
                name = obj.string("name").orEmpty(),
                point = point,
                address = obj.string("address").orEmpty(),
            )
        }.distinctBy { it.id }.take(MAX)
    }

    fun encode(places: List<SavedPlace>): String = JsonArray(
        places.take(MAX).map { place ->
            buildJsonObject {
                put("id", place.id)
                put("name", place.name)
                put("lat", place.point.latitude)
                put("lon", place.point.longitude)
                if (place.address.isNotBlank()) put("address", place.address)
            }
        },
    ).toString()

    /** An id no place in [places] has: the clock, nudged past any collision. */
    fun newId(places: List<SavedPlace>, now: Long = System.currentTimeMillis()): Long {
        var id = now
        val taken = places.mapTo(HashSet()) { it.id }
        while (id in taken) id++
        return id
    }

    private fun JsonObject.number(key: String): Double? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.doubleOrNull

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
}
