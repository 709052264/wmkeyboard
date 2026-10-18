package com.wasimaster.wmkeyboard.core.smarthome

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** The one JSON configuration: every platform adds fields freely, so unknown keys never fail. */
val HomeJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject

internal fun JsonElement?.arr(): JsonArray? = this as? JsonArray

internal fun JsonElement?.prim(): JsonPrimitive? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }

internal fun JsonObject.str(key: String): String? = this[key].prim()?.contentOrNull

internal fun JsonObject.num(key: String): Double? = this[key].prim()?.doubleOrNull

internal fun JsonObject.int(key: String): Int? = num(key)?.toInt()

internal fun JsonObject.bool(key: String): Boolean? = this[key].prim()?.booleanOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key].obj()

internal fun JsonObject.arr(key: String): JsonArray? = this[key].arr()

internal fun JsonObject.strings(key: String): List<String> = arr(key)?.mapNotNull { it.prim()?.contentOrNull }.orEmpty()

internal fun JsonObject.objects(key: String): List<JsonObject> = arr(key)?.mapNotNull { it.obj() }.orEmpty()

internal fun parseJson(text: String): JsonElement? = runCatching { HomeJson.parseToJsonElement(text) }.getOrNull()

/** An entity object id: lower case, letters, digits and underscores, the way Home Assistant spells them. */
internal fun slug(raw: String): String {
    val sb = StringBuilder(raw.length)
    var underscore = false
    for (ch in raw.lowercase()) {
        val ok = ch in 'a'..'z' || ch in '0'..'9'
        if (ok) {
            sb.append(ch)
            underscore = false
        } else if (!underscore && sb.isNotEmpty()) {
            sb.append('_')
            underscore = true
        }
    }
    return sb.toString().trimEnd('_').ifEmpty { "x" }
}
