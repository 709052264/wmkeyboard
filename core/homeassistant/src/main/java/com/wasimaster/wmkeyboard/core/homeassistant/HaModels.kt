package com.wasimaster.wmkeyboard.core.homeassistant

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * One entity as Home Assistant reports it, and the shape every other platform
 * is mapped onto. [attributes] stays raw JSON because every domain has its
 * own, and the typed accessors below read the ones the keyboard draws.
 * Timestamps are epoch seconds, whichever API delivered them.
 */
@Serializable
data class HaEntity(
    @SerialName("entity_id") val entityId: String,
    val state: String,
    val attributes: JsonObject = JsonObject(emptyMap()),
    @SerialName("last_changed") val lastChanged: Double = 0.0,
    @SerialName("last_updated") val lastUpdated: Double = 0.0,
    /** The key of the home this entity belongs to (`HomeRef`); blank until a backend stamps it. */
    val home: String = "",
) {
    val domain: String get() = entityId.substringBefore('.')
    val objectId: String get() = entityId.substringAfter('.', entityId)

    /** The name shown: HA's friendly name, or the object id made readable. */
    val friendlyName: String
        get() = attributes.str("friendly_name")?.takeIf { it.isNotBlank() }
            ?: objectId.replace('_', ' ').replaceFirstChar { it.uppercase() }

    val icon: String? get() = attributes.str("icon")
    val unit: String? get() = attributes.str("unit_of_measurement")
    val deviceClass: String? get() = attributes.str("device_class")
    val stateClass: String? get() = attributes.str("state_class")
    val entityPicture: String? get() = attributes.str("entity_picture")
    val supportedFeatures: Int get() = attributes.int("supported_features") ?: 0
    fun supports(flag: Int): Boolean = supportedFeatures and flag != 0

    val isOn: Boolean get() = state == "on"
    val isUnavailable: Boolean get() = state == "unavailable"
    val isUnknown: Boolean get() = state == "unknown"
    val usable: Boolean get() = !isUnavailable && !isUnknown
    val numeric: Double? get() = state.toDoubleOrNull()
    val assumedState: Boolean get() = attributes.bool("assumed_state") == true

    // Light
    val brightness: Int? get() = attributes.int("brightness")
    val brightnessPct: Int? get() = brightness?.let { (it * PERCENT + HALF_BYTE) / BYTE_MAX }
    val colorTempKelvin: Int? get() = attributes.int("color_temp_kelvin")
    val minColorTempKelvin: Int get() = attributes.int("min_color_temp_kelvin") ?: DEFAULT_MIN_KELVIN
    val maxColorTempKelvin: Int get() = attributes.int("max_color_temp_kelvin") ?: DEFAULT_MAX_KELVIN
    val colorMode: String? get() = attributes.str("color_mode")
    val supportedColorModes: List<String> get() = attributes.strings("supported_color_modes")
    val hsColor: Pair<Double, Double>?
        get() = attributes.numbers("hs_color").takeIf { it.size == 2 }?.let { it[0] to it[1] }
    val rgbColor: Triple<Int, Int, Int>?
        get() = attributes.numbers("rgb_color").takeIf { it.size >= 3 }?.let { Triple(it[0].toInt(), it[1].toInt(), it[2].toInt()) }
    val effectList: List<String> get() = attributes.strings("effect_list")
    val effect: String? get() = attributes.str("effect")
    val supportsBrightness: Boolean
        get() = supportedColorModes.any { it != "onoff" } || brightness != null
    val supportsColorTemp: Boolean get() = "color_temp" in supportedColorModes
    val supportsColor: Boolean get() = supportedColorModes.any { it in COLOR_MODES }

    // Cover and valve
    val currentPosition: Int? get() = attributes.int("current_position")
    val currentTiltPosition: Int? get() = attributes.int("current_tilt_position")

    // Climate, water heater, weather
    val currentTemperature: Double? get() = attributes.num("current_temperature")
    val targetTemperature: Double? get() = attributes.num("temperature")
    val targetTempHigh: Double? get() = attributes.num("target_temp_high")
    val targetTempLow: Double? get() = attributes.num("target_temp_low")
    val minTemp: Double? get() = attributes.num("min_temp")
    val maxTemp: Double? get() = attributes.num("max_temp")
    val targetTempStep: Double? get() = attributes.num("target_temp_step")
    val hvacModes: List<String> get() = attributes.strings("hvac_modes")
    val hvacAction: String? get() = attributes.str("hvac_action")
    val presetModes: List<String> get() = attributes.strings("preset_modes")
    val presetMode: String? get() = attributes.str("preset_mode")
    val fanModes: List<String> get() = attributes.strings("fan_modes")
    val fanMode: String? get() = attributes.str("fan_mode")
    val operationList: List<String> get() = attributes.strings("operation_list")
    val operationMode: String? get() = attributes.str("operation_mode")
    val temperatureUnit: String? get() = attributes.str("temperature_unit")

    // Fan
    val percentage: Int? get() = attributes.int("percentage")
    val percentageStep: Double get() = attributes.num("percentage_step") ?: 1.0
    val oscillating: Boolean? get() = attributes.bool("oscillating")
    val direction: String? get() = attributes.str("direction")

    // Media player
    val mediaTitle: String? get() = attributes.str("media_title")
    val mediaArtist: String? get() = attributes.str("media_artist")
    val mediaAlbum: String? get() = attributes.str("media_album_name")
    val mediaSeries: String? get() = attributes.str("media_series_title")
    val volumeLevel: Double? get() = attributes.num("volume_level")
    val isMuted: Boolean get() = attributes.bool("is_volume_muted") == true
    val sourceList: List<String> get() = attributes.strings("source_list")
    val source: String? get() = attributes.str("source")
    val appName: String? get() = attributes.str("app_name")
    val mediaDuration: Double? get() = attributes.num("media_duration")
    val mediaPosition: Double? get() = attributes.num("media_position")

    // Vacuum
    val fanSpeedList: List<String> get() = attributes.strings("fan_speed_list")
    val fanSpeed: String? get() = attributes.str("fan_speed")
    val batteryLevel: Int? get() = attributes.int("battery_level")

    // Humidifier
    val targetHumidity: Double? get() = attributes.num("humidity")
    val currentHumidity: Double? get() = attributes.num("current_humidity")
    val availableModes: List<String> get() = attributes.strings("available_modes")
    val mode: String? get() = attributes.str("mode")
    val minHumidity: Double get() = attributes.num("min_humidity") ?: 0.0
    val maxHumidity: Double get() = attributes.num("max_humidity") ?: PERCENT.toDouble()

    // Number, select, text
    val min: Double? get() = attributes.num("min")
    val max: Double? get() = attributes.num("max")
    val step: Double? get() = attributes.num("step")
    val options: List<String> get() = attributes.strings("options")
    val maxLength: Int? get() = attributes.int("max")
    val pattern: String? get() = attributes.str("pattern")

    // Lock and alarm
    val codeFormat: String? get() = attributes.str("code_format")
    val codeArmRequired: Boolean get() = attributes.bool("code_arm_required") != false
    val changedBy: String? get() = attributes.str("changed_by")

    // Weather
    val weatherTemperature: Double? get() = attributes.num("temperature")
    val weatherHumidity: Double? get() = attributes.num("humidity")

    // Person, device tracker
    val latitude: Double? get() = attributes.num("latitude")
    val longitude: Double? get() = attributes.num("longitude")

    companion object {
        private const val PERCENT = 100
        private const val BYTE_MAX = 255
        private const val HALF_BYTE = 127
        private const val DEFAULT_MIN_KELVIN = 2000
        private const val DEFAULT_MAX_KELVIN = 6500
        private val COLOR_MODES = setOf("hs", "rgb", "rgbw", "rgbww", "xy")

        /** A REST state object, whose timestamps are ISO strings. */
        fun fromRest(o: JsonObject): HaEntity? {
            val id = o.str("entity_id") ?: return null
            return HaEntity(
                entityId = id,
                state = o.str("state") ?: "unknown",
                attributes = o.obj("attributes") ?: JsonObject(emptyMap()),
                lastChanged = HaTime.parse(o["last_changed"]) ?: 0.0,
                lastUpdated = HaTime.parse(o["last_updated"]) ?: 0.0,
            )
        }

        /** One entry of a `subscribe_entities` `a` (add) map: `{s, a, lc, lu}`. */
        fun fromCompressed(id: String, o: JsonObject): HaEntity {
            val lc = o.num("lc") ?: 0.0
            return HaEntity(
                entityId = id,
                state = o.str("s") ?: "unknown",
                attributes = o.obj("a") ?: JsonObject(emptyMap()),
                lastChanged = lc,
                lastUpdated = o.num("lu") ?: lc,
            )
        }
    }

    /** This entity after a `subscribe_entities` `c` (change) entry: `{"+": {s, a, lc, lu}, "-": {a: [keys]}}`. */
    fun applyCompressedChange(change: JsonObject): HaEntity {
        val plus = change.obj("+")
        val minus = change.obj("-")
        val attrs = LinkedHashMap<String, JsonElement>(attributes)
        minus?.strings("a")?.forEach { attrs.remove(it) }
        plus?.obj("a")?.forEach { (k, v) -> attrs[k] = v }
        val lc = plus?.num("lc") ?: lastChanged
        return copy(
            state = plus?.str("s") ?: state,
            attributes = JsonObject(attrs),
            lastChanged = lc,
            lastUpdated = plus?.num("lu") ?: if (plus?.num("lc") != null) lc else lastUpdated,
        )
    }
}

@Serializable
data class HaArea(
    @SerialName("area_id") val areaId: String,
    val name: String,
    @SerialName("floor_id") val floorId: String? = null,
    val icon: String? = null,
) {
    companion object {
        fun parse(o: JsonObject): HaArea? {
            val id = o.str("area_id") ?: return null
            val name = o.str("name") ?: return null
            return HaArea(id, name, o.str("floor_id"), o.str("icon"))
        }
    }
}

@Serializable
data class HaFloor(
    @SerialName("floor_id") val floorId: String,
    val name: String,
    val level: Int? = null,
    val icon: String? = null,
) {
    companion object {
        fun parse(o: JsonObject): HaFloor? {
            val id = o.str("floor_id") ?: return null
            val name = o.str("name") ?: return null
            return HaFloor(id, name, o.int("level"), o.str("icon"))
        }
    }
}

@Serializable
data class HaDevice(
    val id: String,
    @SerialName("area_id") val areaId: String? = null,
    val name: String? = null,
    @SerialName("name_by_user") val nameByUser: String? = null,
) {
    val displayName: String? get() = nameByUser ?: name

    companion object {
        fun parse(o: JsonObject): HaDevice? {
            val id = o.str("id") ?: return null
            return HaDevice(id, o.str("area_id"), o.str("name"), o.str("name_by_user"))
        }
    }
}

/** The entity registry's row for an entity: where it lives, what it is called, whether it is hidden. */
@Serializable
data class HaEntityEntry(
    @SerialName("entity_id") val entityId: String,
    @SerialName("area_id") val areaId: String? = null,
    @SerialName("device_id") val deviceId: String? = null,
    val name: String? = null,
    @SerialName("original_name") val originalName: String? = null,
    val icon: String? = null,
    @SerialName("hidden_by") val hiddenBy: String? = null,
    @SerialName("disabled_by") val disabledBy: String? = null,
    @SerialName("entity_category") val entityCategory: String? = null,
    val platform: String? = null,
) {
    companion object {
        fun parse(o: JsonObject): HaEntityEntry? {
            val id = o.str("entity_id") ?: return null
            return HaEntityEntry(
            entityId = id,
            areaId = o.str("area_id"),
            deviceId = o.str("device_id"),
            name = o.str("name"),
            originalName = o.str("original_name"),
            icon = o.str("icon"),
            hiddenBy = o.str("hidden_by"),
            disabledBy = o.str("disabled_by"),
            entityCategory = o.str("entity_category"),
            platform = o.str("platform"),
            )
        }
    }
}

/**
 * Areas, floors, devices and entity entries joined: an entity's area is its
 * own, or its device's. Everything is optional, because an instance may have
 * never set up an area.
 */
@Serializable
data class HaRegistry(
    val areas: List<HaArea> = emptyList(),
    val floors: List<HaFloor> = emptyList(),
    val devices: List<HaDevice> = emptyList(),
    val entries: List<HaEntityEntry> = emptyList(),
) {
    @kotlinx.serialization.Transient
    private val entryById: Map<String, HaEntityEntry> = entries.associateBy { it.entityId }

    @kotlinx.serialization.Transient
    private val deviceById: Map<String, HaDevice> = devices.associateBy { it.id }

    @kotlinx.serialization.Transient
    private val areaById: Map<String, HaArea> = areas.associateBy { it.areaId }

    fun entry(entityId: String): HaEntityEntry? = entryById[entityId]

    fun area(areaId: String?): HaArea? = areaId?.let { areaById[it] }

    fun areaIdOf(entityId: String): String? {
        val e = entryById[entityId] ?: return null
        return e.areaId ?: e.deviceId?.let { deviceById[it]?.areaId }
    }

    fun areaOf(entityId: String): HaArea? = area(areaIdOf(entityId))

    /** Hidden or disabled in the registry, or a config/diagnostic entity (a firmware version, a signal strength). */
    fun isHidden(entityId: String): Boolean {
        val e = entryById[entityId] ?: return false
        return e.hiddenBy != null || e.disabledBy != null || e.entityCategory != null
    }

    /** The user's name for the entity when the registry has one, else the state's. */
    fun nameOf(entity: HaEntity): String = entryById[entity.entityId]?.name?.takeIf { it.isNotBlank() } ?: entity.friendlyName

    fun iconOf(entity: HaEntity): String? = entryById[entity.entityId]?.icon ?: entity.icon

    /** Areas in the order Home Assistant lists them, floors first by level. */
    fun orderedAreas(): List<HaArea> {
        val floorLevel = floors.associate { it.floorId to (it.level ?: 0) }
        return areas.sortedWith(compareBy({ floorLevel[it.floorId] ?: Int.MAX_VALUE }, { it.name.lowercase() }))
    }

    companion object {
        fun parse(areas: JsonArray?, floors: JsonArray?, devices: JsonArray?, entries: JsonArray?): HaRegistry = HaRegistry(
            areas = areas?.mapNotNull { HaArea.parse(it.jsonObject) }.orEmpty(),
            floors = floors?.mapNotNull { HaFloor.parse(it.jsonObject) }.orEmpty(),
            devices = devices?.mapNotNull { HaDevice.parse(it.jsonObject) }.orEmpty(),
            entries = entries?.mapNotNull { HaEntityEntry.parse(it.jsonObject) }.orEmpty(),
        )
    }
}

/** What the instance says about itself. */
@Serializable
data class HaInstance(
    val name: String = "Home",
    val version: String? = null,
    val uuid: String? = null,
    val language: String? = null,
    val temperatureUnit: String? = null,
) {
    companion object {
        /** From `GET /api/config` or the socket's `get_config`. */
        fun parse(o: JsonObject): HaInstance = HaInstance(
            name = o.str("location_name")?.takeIf { it.isNotBlank() } ?: "Home",
            version = o.str("version"),
            uuid = null,
            language = o.str("language"),
            temperatureUnit = o.obj("unit_system")?.str("temperature"),
        )
    }
}

/** Where the connection stands, which the panel's status dot draws. */
enum class HaPhase {
    /** Switched off, or stopped on purpose. */
    OFFLINE,
    CONNECTING,
    AUTHENTICATING,
    /** The socket is up and the entity stream is flowing. */
    LIVE,
    /** The socket failed but REST answers; states refresh on a timer. */
    REST_FALLBACK,
    /** Reachable, but the token was refused. Sign in again. */
    TOKEN_REJECTED,
    /** Nothing answers at either address. */
    UNREACHABLE,
}

/** A conversation agent, from `conversation/agent/list`. */
@Serializable
data class HaAgent(val id: String, val name: String, @SerialName("supported_languages") val supportedLanguages: List<String> = emptyList()) {
    companion object {
        fun parse(o: JsonObject): HaAgent? {
            val id = o.str("id") ?: return null
            val langs = (o["supported_languages"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
            return HaAgent(id, o.str("name") ?: id, langs)
        }
    }
}
