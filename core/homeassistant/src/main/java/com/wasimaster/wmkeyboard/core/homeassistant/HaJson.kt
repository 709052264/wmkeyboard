package com.wasimaster.wmkeyboard.core.homeassistant

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.GregorianCalendar
import java.util.TimeZone

/** The one JSON configuration: Home Assistant adds fields freely, so unknown keys never fail. */
val HaJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

internal fun JsonElement?.asObjectOrNull(): JsonObject? = (this as? JsonObject)

internal fun JsonElement?.asArrayOrNull(): JsonArray? = (this as? JsonArray)

internal fun JsonElement?.asPrimitiveOrNull(): JsonPrimitive? = this as? JsonPrimitive

internal fun JsonObject.str(key: String): String? = this[key].asPrimitiveOrNull()?.takeIf { it !is JsonNull }?.contentOrNull

internal fun JsonObject.num(key: String): Double? = this[key].asPrimitiveOrNull()?.doubleOrNull

internal fun JsonObject.int(key: String): Int? = num(key)?.toInt()

internal fun JsonObject.bool(key: String): Boolean? = this[key].asPrimitiveOrNull()?.booleanOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key].asObjectOrNull()

internal fun JsonObject.arr(key: String): JsonArray? = this[key].asArrayOrNull()

internal fun JsonObject.strings(key: String): List<String> =
    arr(key)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

internal fun JsonObject.numbers(key: String): List<Double> =
    arr(key)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }.orEmpty()

internal fun JsonElement.objectOf(key: String): JsonObject? = jsonObject[key]?.let { it as? JsonObject }

internal fun JsonElement.arrayOf(key: String): JsonArray? = jsonObject[key]?.let { it as? JsonArray }

internal fun JsonElement.stringContent(): String? = (this as? JsonPrimitive)?.contentOrNull

/** Home Assistant's timestamps: ISO 8601 over REST, epoch seconds over the compressed socket stream. */
object HaTime {

    private val ISO = Regex(
        """^(\d{4})-(\d{2})-(\d{2})[T ](\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,9}))?(Z|[+-]\d{2}:?\d{2})?$""",
    )

    /** Epoch seconds, or null for a string that is not a timestamp. */
    @Suppress("detekt.MagicNumber")
    fun parseIso(text: String?): Double? {
        val m = ISO.matchEntire(text?.trim() ?: return null) ?: return null
        val g = m.groupValues
        val cal = GregorianCalendar(TimeZone.getTimeZone("UTC"))
        cal.clear()
        cal.set(g[1].toInt(), g[2].toInt() - 1, g[3].toInt(), g[4].toInt(), g[5].toInt(), g[6].toInt())
        var seconds = cal.timeInMillis / 1000.0
        if (g[7].isNotEmpty()) seconds += ("0." + g[7]).toDouble()
        val zone = g[8]
        if (zone.isNotEmpty() && zone != "Z") {
            val sign = if (zone[0] == '-') -1 else 1
            val digits = zone.drop(1).replace(":", "")
            val offset = digits.take(2).toInt() * 3600 + digits.drop(2).toInt() * 60
            seconds -= sign * offset
        }
        return seconds
    }

    /** A JSON value that is either an ISO string (REST) or epoch seconds (socket). */
    fun parse(element: JsonElement?): Double? {
        val p = element as? JsonPrimitive ?: return null
        p.doubleOrNull?.let { return it }
        return parseIso(p.contentOrNull)
    }

    fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0
}
