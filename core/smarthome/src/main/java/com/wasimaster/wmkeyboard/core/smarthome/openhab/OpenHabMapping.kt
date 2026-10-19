package com.wasimaster.wmkeyboard.core.smarthome.openhab

import com.wasimaster.wmkeyboard.core.homeassistant.HaArea
import com.wasimaster.wmkeyboard.core.homeassistant.HaEntity
import com.wasimaster.wmkeyboard.core.homeassistant.HaEntityEntry
import com.wasimaster.wmkeyboard.core.homeassistant.HaFeatures
import com.wasimaster.wmkeyboard.core.homeassistant.HaFloor
import com.wasimaster.wmkeyboard.core.homeassistant.HaRegistry
import com.wasimaster.wmkeyboard.core.homeassistant.HaServiceCall
import com.wasimaster.wmkeyboard.core.homeassistant.HaTime
import com.wasimaster.wmkeyboard.core.smarthome.arr
import com.wasimaster.wmkeyboard.core.smarthome.bool
import com.wasimaster.wmkeyboard.core.smarthome.num
import com.wasimaster.wmkeyboard.core.smarthome.obj
import com.wasimaster.wmkeyboard.core.smarthome.objects
import com.wasimaster.wmkeyboard.core.smarthome.parseJson
import com.wasimaster.wmkeyboard.core.smarthome.prim
import com.wasimaster.wmkeyboard.core.smarthome.slug
import com.wasimaster.wmkeyboard.core.smarthome.str
import com.wasimaster.wmkeyboard.core.smarthome.strings
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.roundToInt

/** One openHAB item as `GET /rest/items` lists it. */
data class OhItem(
    val name: String,
    val label: String?,
    /** `Switch`, `Dimmer`, `Color`, `Rollershutter`, `Contact`, `Number`, `Number:Temperature`, `String`, `Player`, `Group`… */
    val type: String,
    val state: String,
    val tags: Set<String>,
    val groupNames: List<String>,
    val category: String?,
    /** `stateDescription`: pattern, readOnly, min/max/step, options. */
    val stateDescription: JsonObject?,
    val commandOptions: List<Pair<String, String>>,
) {
    val displayName: String get() = label?.takeIf { it.isNotBlank() } ?: name
    val baseType: String get() = type.substringBefore(':')
    val isGroup: Boolean get() = baseType == "Group"
    val readOnly: Boolean get() = stateDescription?.bool("readOnly") == true
    val options: List<Pair<String, String>>
        get() = stateDescription?.objects("options")?.mapNotNull { o -> o.str("value")?.let { it to (o.str("label") ?: it) } }
            ?.takeIf { it.isNotEmpty() } ?: commandOptions

    fun hasTag(vararg names: String): Boolean = names.any { n -> tags.any { it.equals(n, ignoreCase = true) } }

    companion object {
        fun parse(o: JsonObject): OhItem? {
            val name = o.str("name") ?: return null
            return OhItem(
                name = name,
                label = o.str("label"),
                type = o.str("type") ?: "String",
                state = o.str("state") ?: "NULL",
                tags = o.strings("tags").toSet(),
                groupNames = o.strings("groupNames"),
                category = o.str("category"),
                stateDescription = o.obj("stateDescription"),
                commandOptions = o.obj("commandDescription")?.objects("commandOptions")?.mapNotNull { c -> c.str("command")?.let { it to (c.str("label") ?: it) } }.orEmpty(),
            )
        }
    }
}

/**
 * openHAB items onto Home Assistant's domains, by item type and semantic
 * tag, and a service call back onto the command the item takes. Rooms come
 * from the semantic model: a Group tagged as a Location is an area, and an
 * item is in the first Location found walking up its groups.
 */
@Suppress("detekt.TooManyFunctions")
object OpenHabMapping {

    fun parseItems(body: String): List<OhItem> = parseJson(body).arr()?.mapNotNull { it.obj()?.let(OhItem::parse) }.orEmpty()

    fun entityId(item: OhItem): String? = domainOf(item)?.let { "$it.oh_" + slug(item.name) }

    /** The item an entity id names, from the list. */
    fun itemOf(items: Collection<OhItem>, entityId: String): OhItem? {
        val raw = entityId.substringAfter("oh_", "")
        return items.firstOrNull { slug(it.name) == raw }
    }

    @Suppress("detekt.CyclomaticComplexMethod", "detekt.ReturnCount")
    fun domainOf(item: OhItem): String? {
        if (item.isGroup) return null
        val light = item.hasTag("Light", "Lightbulb") || item.category.equals("light", true) || item.category.equals("lightbulb", true)
        return when (item.baseType) {
            "Switch" -> when {
                item.hasTag("Lock") -> "lock"
                light -> "light"
                item.readOnly -> "binary_sensor"
                else -> "switch"
            }
            "Dimmer", "Color" -> "light"
            "Rollershutter" -> "cover"
            "Contact" -> "binary_sensor"
            "Number" -> if (item.hasTag("Setpoint", "Control") && !item.readOnly) "number" else "sensor"
            "String" -> when {
                item.readOnly -> "sensor"
                item.options.isNotEmpty() -> "select"
                else -> "text"
            }
            "Player" -> "media_player"
            else -> null
        }
    }

    // ---- Rooms -----------------------------------------------------------------

    /** The semantic Location tags openHAB ships, lower-cased; a group with one is a room (or a floor). */
    private val FLOOR_TAGS = setOf("floor", "groundfloor", "firstfloor", "secondfloor", "thirdfloor", "attic", "basement")
    private val BUILDING_TAGS = setOf("location", "indoor", "outdoor", "building", "house", "apartment", "summerhouse", "shed", "garage", "carport")
    private val ROOM_TAGS = setOf(
        "room", "bedroom", "kitchen", "livingroom", "bathroom", "office", "corridor", "cellar", "garden", "terrace", "patio",
        "porch", "driveway", "guestroom", "diningroom", "familyroom", "boilerroom", "laundryroom", "entry", "hall", "lobby",
        "stairway", "loft", "veranda", "garage", "basement", "attic",
    )

    private fun OhItem.locationTag(): String? = tags.map { it.lowercase() }.firstOrNull { it in ROOM_TAGS || it in FLOOR_TAGS || it in BUILDING_TAGS }

    /** Areas, floors, and each item's area. */
    fun registry(items: List<OhItem>): HaRegistry {
        val byName = items.associateBy { it.name }
        val groups = items.filter { it.isGroup }
        val rooms = groups.filter { g -> g.locationTag()?.let { it in ROOM_TAGS } == true }
        val floors = groups.filter { g -> g.locationTag()?.let { it in FLOOR_TAGS && it !in ROOM_TAGS } == true }
        val roomIds = rooms.map { it.name }.toSet()
        fun roomOf(item: OhItem, depth: Int = 0): String? {
            if (depth > MAX_GROUP_DEPTH) return null
            for (g in item.groupNames) {
                if (g in roomIds) return g
            }
            for (g in item.groupNames) {
                val parent = byName[g] ?: continue
                roomOf(parent, depth + 1)?.let { return it }
            }
            return null
        }
        fun floorOf(room: OhItem): String? = room.groupNames.firstOrNull { g -> floors.any { it.name == g } }
        return HaRegistry(
            areas = rooms.map { HaArea(it.name, it.displayName, floorId = floorOf(it)) },
            floors = floors.mapIndexed { i, f -> HaFloor(f.name, f.displayName, level = i) },
            entries = items.mapNotNull { item -> entityId(item)?.let { HaEntityEntry(it, areaId = roomOf(item)) } },
        )
    }

    // ---- Items to entities ---------------------------------------------------------

    fun entities(items: List<OhItem>, nowSeconds: Double = HaTime.nowSeconds()): Map<String, HaEntity> =
        items.mapNotNull { entity(it, nowSeconds) }.associateBy { it.entityId }

    @Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod", "detekt.ReturnCount")
    fun entity(item: OhItem, nowSeconds: Double = HaTime.nowSeconds()): HaEntity? {
        val domain = domainOf(item) ?: return null
        val raw = item.state
        val undefined = raw == "NULL" || raw == "UNDEF"
        val attrs = LinkedHashMap<String, JsonElement>()
        attrs["friendly_name"] = JsonPrimitive(item.displayName)
        fun put(key: String, v: Any?) {
            when (v) {
                null -> Unit
                is String -> attrs[key] = JsonPrimitive(v)
                is Number -> attrs[key] = JsonPrimitive(v)
                is JsonElement -> attrs[key] = v
                is List<*> -> attrs[key] = JsonArray(v.map { x -> if (x is Number) JsonPrimitive(x) else JsonPrimitive(x.toString()) })
                else -> attrs[key] = JsonPrimitive(v.toString())
            }
        }
        val state: String = when (domain) {
            "light" -> when (item.baseType) {
                "Switch" -> {
                    put("supported_color_modes", listOf("onoff"))
                    if (raw == "ON") "on" else "off"
                }
                "Dimmer" -> {
                    val pct = raw.toDoubleOrNull() ?: 0.0
                    put("supported_color_modes", listOf("brightness"))
                    put("brightness", pctToByte(pct))
                    if (pct > 0) put("color_mode", "brightness")
                    if (pct > 0) "on" else "off"
                }
                else -> {
                    val parts = raw.split(',').mapNotNull { it.trim().toDoubleOrNull() }
                    val h = parts.getOrNull(0) ?: 0.0
                    val s = parts.getOrNull(1) ?: 0.0
                    val b = parts.getOrNull(2) ?: 0.0
                    put("supported_color_modes", listOf("hs"))
                    put("brightness", pctToByte(b))
                    put("hs_color", listOf(h, s))
                    if (b > 0) put("color_mode", "hs")
                    if (b > 0) "on" else "off"
                }
            }
            "switch" -> if (raw == "ON") "on" else "off"
            "lock" -> if (raw == "ON") "locked" else "unlocked"
            "binary_sensor" -> {
                put("device_class", when {
                    item.hasTag("Door", "Window", "OpenState") -> "opening"
                    item.hasTag("Motion", "Presence") -> "motion"
                    else -> null
                })
                if (raw == "OPEN" || raw == "ON") "on" else "off"
            }
            "cover" -> {
                val pos = raw.toDoubleOrNull()
                put("device_class", if (item.hasTag("Blinds")) "blind" else if (item.hasTag("Garage", "GarageDoor")) "garage" else "shutter")
                put("supported_features", HaFeatures.Cover.OPEN or HaFeatures.Cover.CLOSE or HaFeatures.Cover.STOP or HaFeatures.Cover.SET_POSITION)
                // openHAB: 0 is up (open), 100 is down (closed); Home Assistant counts the other way.
                if (pos != null) put("current_position", (PERCENT - pos).roundToInt())
                when {
                    pos == null -> "unknown"
                    pos >= PERCENT -> "closed"
                    else -> "open"
                }
            }
            "sensor" -> {
                val (value, unit) = splitUnit(raw)
                put("unit_of_measurement", unit ?: unitOfPattern(item))
                put("device_class", deviceClassOf(item))
                if (value.toDoubleOrNull() != null) put("state_class", "measurement")
                value
            }
            "number" -> {
                val (value, unit) = splitUnit(raw)
                put("unit_of_measurement", unit ?: unitOfPattern(item))
                put("min", item.stateDescription?.num("minimum") ?: 0)
                put("max", item.stateDescription?.num("maximum") ?: PERCENT)
                put("step", item.stateDescription?.num("step") ?: 1)
                put("mode", "slider")
                value
            }
            "select" -> {
                put("options", item.options.map { it.first })
                raw
            }
            "text" -> {
                put("max", TEXT_MAX)
                raw
            }
            "media_player" -> {
                put("supported_features", HaFeatures.Media.PLAY or HaFeatures.Media.PAUSE or HaFeatures.Media.NEXT_TRACK or HaFeatures.Media.PREVIOUS_TRACK)
                when (raw) {
                    "PLAY" -> "playing"
                    "PAUSE" -> "paused"
                    else -> "idle"
                }
            }
            else -> raw
        }
        return HaEntity(
            entityId = entityId(item) ?: return null,
            state = if (undefined && domain != "select" && domain != "text") "unknown" else state,
            attributes = JsonObject(attrs),
            lastChanged = nowSeconds,
            lastUpdated = nowSeconds,
        )
    }

    private fun deviceClassOf(item: OhItem): String? = when {
        item.hasTag("Temperature") || item.type == "Number:Temperature" -> "temperature"
        item.hasTag("Humidity") -> "humidity"
        item.hasTag("Power") || item.type == "Number:Power" -> "power"
        item.hasTag("Energy") || item.type == "Number:Energy" -> "energy"
        item.hasTag("Light") && item.baseType == "Number" -> "illuminance"
        item.hasTag("Pressure") -> "pressure"
        item.hasTag("CO2") -> "carbon_dioxide"
        item.hasTag("Voltage") -> "voltage"
        item.hasTag("Current") -> "current"
        else -> null
    }

    /** `21.5 °C` as openHAB states a quantity: the number and the unit. */
    fun splitUnit(raw: String): Pair<String, String?> {
        val trimmed = raw.trim()
        val space = trimmed.indexOf(' ')
        if (space < 0) return trimmed to null
        val value = trimmed.substring(0, space)
        if (value.toDoubleOrNull() == null) return trimmed to null
        return value to trimmed.substring(space + 1).trim().ifEmpty { null }
    }

    /** The unit a state pattern like `%.1f °C` or `%d %%` carries. */
    fun unitOfPattern(item: OhItem): String? {
        val pattern = item.stateDescription?.str("pattern") ?: return null
        val after = pattern.substringAfter("%", "").dropWhile { it in "0123456789.-+ " }.drop(1).trim()
        return after.replace("%%", "%").replace("%unit%", "").trim().ifEmpty { null }
    }

    // ---- Service calls to commands -------------------------------------------------

    /** The command text `POST /rest/items/{name}` takes for [call], or null for a call the item cannot take. */
    @Suppress("detekt.CyclomaticComplexMethod", "detekt.ReturnCount")
    fun command(call: HaServiceCall, item: OhItem, current: HaEntity?): String? {
        val data = call.data
        val on = current?.state == "on"
        val mine = domainOf(item) ?: return null
        // A call for another kind of thing has no meaning here, whatever the service is called.
        if (call.domain != mine && call.domain != "homeassistant" && !(call.domain == "light" && mine == "switch")) return null
        return when (call.domain) {
            "light", "switch", "homeassistant" -> when (call.service) {
                "turn_on" -> {
                    val pct = data.num("brightness_pct") ?: data.num("brightness")?.let { byteToPct(it) }
                    val hs = data.arr("hs_color")
                    when {
                        item.baseType == "Color" && hs != null -> {
                            val h = hs.getOrNull(0).prim()?.doubleOrNull ?: 0.0
                            val s = hs.getOrNull(1).prim()?.doubleOrNull ?: 0.0
                            val b = pct ?: current?.brightnessPct?.toDouble()?.takeIf { it > 0 } ?: PERCENT.toDouble()
                            "${trim(h)},${trim(s)},${trim(b)}"
                        }
                        pct != null && item.baseType != "Switch" -> trim(pct.coerceIn(0.0, PERCENT.toDouble()))
                        else -> "ON"
                    }
                }
                "turn_off" -> "OFF"
                "toggle" -> if (on) "OFF" else "ON"
                else -> null
            }
            "lock" -> when (call.service) {
                "lock" -> "ON"
                "unlock", "open" -> "OFF"
                else -> null
            }
            "cover" -> when (call.service) {
                "open_cover" -> "UP"
                "close_cover" -> "DOWN"
                "stop_cover" -> "STOP"
                "set_cover_position" -> data.num("position")?.let { trim(PERCENT - it.coerceIn(0.0, PERCENT.toDouble())) }
                else -> null
            }
            "number", "input_number" -> if (call.service == "set_value") data.num("value")?.let(::trim) else null
            "select", "input_select" -> if (call.service == "select_option") data.str("option") else null
            "text", "input_text" -> if (call.service == "set_value") data.str("value") else null
            "media_player" -> when (call.service) {
                "media_play" -> "PLAY"
                "media_pause" -> "PAUSE"
                "media_play_pause" -> if (current?.state == "playing") "PAUSE" else "PLAY"
                "media_next_track" -> "NEXT"
                "media_previous_track" -> "PREVIOUS"
                "media_stop" -> "PAUSE"
                else -> null
            }
            else -> null
        }
    }

    /** The `openhab/items/{name}/statechanged` payload: the new state as text. */
    fun stateOfEvent(topic: String, payload: String): Pair<String, String>? {
        val name = topic.removePrefix("openhab/items/").removePrefix("smarthome/items/").substringBefore('/')
        if (name.isEmpty() || name == topic) return null
        val o = parseJson(payload).obj() ?: return null
        val value = o.str("value") ?: return null
        return name to value
    }

    private fun trim(v: Double): String = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else ((v * TENTH).roundToInt() / TENTH).toString()
    private fun pctToByte(pct: Double): Int = ((pct.coerceIn(0.0, PERCENT.toDouble()) * BYTE_MAX) / PERCENT).roundToInt()
    private fun byteToPct(b: Double): Double = (b.coerceIn(0.0, BYTE_MAX.toDouble()) * PERCENT) / BYTE_MAX

    private const val PERCENT = 100
    private const val BYTE_MAX = 255
    private const val TENTH = 10.0
    private const val TEXT_MAX = 255
    private const val MAX_GROUP_DEPTH = 6
}
