package com.wasimaster.wmkeyboard.ime.homeassistant.googlehome

import android.content.Context
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.home.ConnectivityState
import com.google.home.DeviceType
import com.google.home.DeviceTypeFactory
import com.google.home.FactoryRegistry
import com.google.home.ForcePermissionFlow
import com.google.home.Home
import com.google.home.HomeClient
import com.google.home.HomeConfig
import com.google.home.HomeDevice
import com.google.home.HomeException
import com.google.home.PermissionsResultStatus
import com.google.home.PermissionsState
import com.google.home.Room
import com.google.home.Structure
import com.google.home.Trait
import com.google.home.google.ExtendedFanControl
import com.google.home.matter.standard.AirPurifierDevice
import com.google.home.matter.standard.BooleanState
import com.google.home.matter.standard.ColorControl
import com.google.home.matter.standard.ColorTemperatureLightDevice
import com.google.home.matter.standard.ContactSensorDevice
import com.google.home.matter.standard.DimmableLightDevice
import com.google.home.matter.standard.DimmablePlugInUnitDevice
import com.google.home.matter.standard.DoorLock
import com.google.home.matter.standard.DoorLockDevice
import com.google.home.matter.standard.ExtendedColorLightDevice
import com.google.home.matter.standard.FanControl
import com.google.home.matter.standard.FanDevice
import com.google.home.matter.standard.GenericSwitchDevice
import com.google.home.matter.standard.HeatPumpDevice
import com.google.home.matter.standard.HeatingCoolingUnitDevice
import com.google.home.matter.standard.HumiditySensorDevice
import com.google.home.matter.standard.IlluminanceMeasurement
import com.google.home.matter.standard.LevelControl
import com.google.home.matter.standard.LightSensorDevice
import com.google.home.matter.standard.MediaPlayback
import com.google.home.matter.standard.OccupancySensing
import com.google.home.matter.standard.OccupancySensorDevice
import com.google.home.matter.standard.OnOff
import com.google.home.matter.standard.OnOffLightDevice
import com.google.home.matter.standard.OnOffLightSwitchDevice
import com.google.home.matter.standard.OnOffPluginUnitDevice
import com.google.home.matter.standard.RelativeHumidityMeasurement
import com.google.home.matter.standard.RoomAirConditionerDevice
import com.google.home.matter.standard.RootNodeDevice
import com.google.home.matter.standard.SpeakerDevice
import com.google.home.matter.standard.TemperatureMeasurement
import com.google.home.matter.standard.TemperatureSensorDevice
import com.google.home.matter.standard.Thermostat
import com.google.home.matter.standard.ThermostatDevice
import com.google.home.matter.standard.WindowCovering
import com.google.home.matter.standard.WindowCoveringDevice
import com.wasimaster.wmkeyboard.core.homeassistant.HaCommandException
import com.wasimaster.wmkeyboard.core.homeassistant.HaEntity
import com.wasimaster.wmkeyboard.core.homeassistant.HaInstance
import com.wasimaster.wmkeyboard.core.homeassistant.HaPhase
import com.wasimaster.wmkeyboard.core.homeassistant.HaRegistry
import com.wasimaster.wmkeyboard.core.homeassistant.HaServiceCall
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeBackend
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeFeature
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKeys
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKind
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** The structure Google let the app into, as the consent flow reported it. */
/**
 * One consent result. [stamp] makes each result its own value: picking the
 * same home again would otherwise build an equal grant, which a StateFlow
 * drops, and the editor waiting on it would never hear back.
 */
data class GoogleHomeGrant(val structureId: String, val structureName: String, val account: String = "", val stamp: Long = 0)

/**
 * The Google Home APIs, full flavour with the SDK present. One [HomeClient]
 * per process, built on first use with the device types and traits the
 * panel draws. Consent runs in the settings activity ([register],
 * [requestAccess]); the keyboard reads the granted structure through
 * [GoogleHomeBackend].
 */
object GoogleHomeSupport {
    const val AVAILABLE = true

    private val _grants = MutableStateFlow<GoogleHomeGrant?>(null)
    val grants: StateFlow<GoogleHomeGrant?> = _grants.asStateFlow()

    @Volatile private var client: HomeClient? = null

    private val registry = FactoryRegistry(
        types = listOf(
            OnOffLightDevice, DimmableLightDevice, ColorTemperatureLightDevice, ExtendedColorLightDevice,
            OnOffPluginUnitDevice, OnOffLightSwitchDevice, GenericSwitchDevice,
            ThermostatDevice, DoorLockDevice, WindowCoveringDevice, FanDevice, SpeakerDevice,
            ContactSensorDevice, OccupancySensorDevice, TemperatureSensorDevice, LightSensorDevice, RootNodeDevice,
            // A device of a type not listed here arrives with no traits, and draws nothing.
            RoomAirConditionerDevice, HeatPumpDevice, HeatingCoolingUnitDevice, AirPurifierDevice,
            DimmablePlugInUnitDevice, HumiditySensorDevice,
        ),
        traits = listOf(
            OnOff, LevelControl, ColorControl, Thermostat, DoorLock, WindowCovering, FanControl, MediaPlayback,
            TemperatureMeasurement, BooleanState, OccupancySensing, IlluminanceMeasurement,
            RelativeHumidityMeasurement, ExtendedFanControl,
        ),
    )

    fun client(context: Context): HomeClient {
        client?.let { return it }
        synchronized(this) {
            client?.let { return it }
            val made = Home.getClient(
                context.applicationContext,
                homeConfig = HomeConfig(coroutineContext = Dispatchers.IO, factoryRegistry = registry),
            )
            client = made
            return made
        }
    }

    fun create(context: Context, account: HomeAccount, scope: CoroutineScope): HomeBackend? =
        GoogleHomeBackend(account.key, account, client(context), scope)

    /** Must run in an Activity's `onCreate`, before any [requestAccess]. */
    fun register(activity: ComponentActivity) {
        val c = client(activity)
        c.registerActivityResultCallerForPermissions(activity)
        activity.lifecycleScope.launch {
            c.hasPermissions().collect { state ->
                if (state == PermissionsState.GRANTED) refreshGrant(c)
            }
        }
    }

    /** Opens Google's account and structure picker; consent lands in [grants]. */
    fun requestAccess(activity: ComponentActivity, force: Boolean) {
        activity.lifecycleScope.launch {
            try {
                val c = client(activity)
                val result = c.requestPermissions(ForcePermissionFlow.FORCE_LAUNCH)
                if (result.status == PermissionsResultStatus.SUCCESS || force) refreshGrant(c)
            } catch (_: HomeException) {
                // The flow reports its own failure on screen; nothing to add.
            }
        }
    }

    private suspend fun refreshGrant(c: HomeClient) {
        val structure = withTimeoutOrNull(STRUCTURE_TIMEOUT_MS) { c.structures().first { it.isNotEmpty() } }?.firstOrNull() ?: return
        _grants.value = GoogleHomeGrant(structure.id.id, structure.name, stamp = SystemClock.elapsedRealtimeNanos())
    }

    private const val STRUCTURE_TIMEOUT_MS = 5_000L
}

/**
 * The granted structure as a [HomeBackend]: its rooms as areas, its devices
 * as entities by the traits they carry, live from the SDK's flows. The SDK
 * keeps its own connection to Google, so [start] only starts the collectors.
 */
@Suppress("detekt.TooManyFunctions")
class GoogleHomeBackend(
    override val key: String,
    initial: HomeAccount,
    private val client: HomeClient,
    private val scope: CoroutineScope,
) : HomeBackend {

    override val kind: HomeKind = HomeKind.GOOGLE_HOME
    override val features: Set<HomeFeature> = setOf(HomeFeature.LIVE)

    @Volatile private var account = initial

    private val _phase = MutableStateFlow(HaPhase.OFFLINE)
    override val phase: StateFlow<HaPhase> = _phase.asStateFlow()

    private val _entities = MutableStateFlow<Map<String, HaEntity>>(emptyMap())
    override val entities: StateFlow<Map<String, HaEntity>> = _entities.asStateFlow()

    private val _changes = MutableSharedFlow<Set<String>>(extraBufferCapacity = CHANGE_BUFFER)
    override val changes: SharedFlow<Set<String>> = _changes.asSharedFlow()

    private val _registry = MutableStateFlow(HaRegistry())
    override val registry: StateFlow<HaRegistry> = _registry.asStateFlow()

    private val _instance = MutableStateFlow<HaInstance?>(null)
    override val instance: StateFlow<HaInstance?> = _instance.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    override val error: StateFlow<String?> = _error.asStateFlow()

    private var runner: Job? = null
    private var structureJobs: List<Job> = emptyList()
    private val deviceJobs = ConcurrentHashMap<String, Job>()

    /** The latest traits of each device, by entity id, for commands. */
    private val live = ConcurrentHashMap<String, GoogleHomeMapping.Live>()
    private val rooms = ConcurrentHashMap<String, Room>()
    private val deviceRooms = ConcurrentHashMap<String, String?>()

    override fun start() {
        if (runner?.isActive == true) return
        if (!account.ready) return
        _phase.value = HaPhase.CONNECTING
        runner = scope.launch {
            launch {
                client.hasPermissions().collect { state ->
                    if (state == PermissionsState.NOT_GRANTED || state == PermissionsState.PERMISSIONS_STATE_UNAVAILABLE) {
                        _phase.value = HaPhase.TOKEN_REJECTED
                    }
                }
            }
            try {
                client.structures().collect { set -> watch(pick(set)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: HomeException) {
                _error.value = e.message
                _phase.value = HaPhase.UNREACHABLE
            }
        }
    }

    private fun pick(set: Set<Structure>): Structure? {
        val wanted = account.extra(HomeKeys.STRUCTURE_ID)
        return set.firstOrNull { it.id.id == wanted } ?: set.firstOrNull()
    }

    private fun watch(structure: Structure?) {
        structureJobs.forEach { it.cancel() }
        deviceJobs.values.forEach { it.cancel() }
        deviceJobs.clear()
        if (structure == null) {
            _phase.value = HaPhase.UNREACHABLE
            _error.value = "no home granted"
            return
        }
        _instance.value = HaInstance(name = structure.name, uuid = structure.id.id)
        structureJobs = listOf(
            scope.launch {
                structure.rooms().collect { set ->
                    rooms.clear()
                    set.forEach { rooms[it.id.id] = it }
                    publishRegistry()
                }
            },
            scope.launch {
                structure.devices().collect { set ->
                    val seen = HashSet<String>()
                    for (device in set) {
                        val id = device.id.id
                        seen += id
                        deviceRooms[id] = device.roomId?.id
                        if (deviceJobs.containsKey(id)) continue
                        deviceJobs[id] = launch { follow(device) }
                    }
                    for (gone in deviceJobs.keys.toList()) {
                        if (gone !in seen) {
                            deviceJobs.remove(gone)?.cancel()
                            publishDevice(gone, emptyList())
                        }
                    }
                    publishRegistry()
                    _phase.value = HaPhase.LIVE
                    _error.value = null
                }
            },
        )
    }

    /**
     * One device as it changes. [HomeDevice.types] says which types the device
     * has; each type's own flow ([HomeDevice.type]) is what emits a fresh
     * snapshot of its traits when an attribute changes, so every type is
     * followed and the device is mapped again from the latest of each.
     */
    private suspend fun follow(device: HomeDevice) = coroutineScope {
        val latest = ConcurrentHashMap<DeviceTypeFactory<*>, DeviceType>()
        val watchers = HashMap<DeviceTypeFactory<*>, Job>()
        fun remap() {
            val types = latest.values.toList()
            val traits: Set<Trait> = types.flatMap { it.traits() }.toSet()
            val online = device.sourceConnectivity.connectivityState.let { it == ConnectivityState.ONLINE || it == ConnectivityState.PARTIALLY_ONLINE }
            publishDevice(device.id.id, GoogleHomeMapping.entities(device, types.map { it.factory.toString() }.toSet(), traits, online))
            publishRegistry()
        }
        device.types().collect { types ->
            val now = types.associateBy { it.factory }
            for (gone in watchers.keys - now.keys) {
                watchers.remove(gone)?.cancel()
                latest.remove(gone)
            }
            latest.putAll(now)
            for (factory in now.keys) {
                if (factory in watchers) continue
                @Suppress("UNCHECKED_CAST")
                val typed = factory as DeviceTypeFactory<DeviceType>
                watchers[factory] = launch {
                    device.type(typed).collect { snapshot ->
                        latest[factory] = snapshot
                        remap()
                    }
                }
            }
            remap()
        }
    }

    /**
     * Replaces everything [deviceId] contributes with [built], in one step:
     * every device's flow runs on the IO pool, and two read-modify-writes of
     * the map side by side would each drop the other's device.
     */
    private fun publishDevice(deviceId: String, built: List<Pair<HaEntity, GoogleHomeMapping.Live>>) = synchronized(publishLock) {
        val old = live.entries.filter { it.value.deviceId == deviceId }.map { it.key }.toSet()
        val fresh = built.associate { it.first.entityId to it.first }
        (old - fresh.keys).forEach { live.remove(it) }
        built.forEach { (entity, l) -> live[entity.entityId] = l }
        if (old.isNotEmpty() || fresh.isNotEmpty()) publish(_entities.value - old + fresh)
    }

    private val publishLock = Any()

    private fun publishRegistry() {
        _registry.value = GoogleHomeMapping.registry(rooms.values.toList(), live.values.toList(), deviceRooms)
    }

    private fun publish(next: Map<String, HaEntity>) {
        val stamped = next.mapValues { (_, e) -> if (e.home == key) e else e.copy(home = key) }
        val previous = _entities.value
        val changed = LinkedHashSet<String>()
        for ((id, e) in stamped) if (previous[id] != e) changed += id
        for (id in previous.keys) if (id !in stamped) changed += id
        _entities.value = stamped
        if (changed.isNotEmpty()) _changes.tryEmit(changed)
    }

    override fun stop() {
        runner?.cancel()
        runner = null
        structureJobs.forEach { it.cancel() }
        structureJobs = emptyList()
        deviceJobs.values.forEach { it.cancel() }
        deviceJobs.clear()
        _phase.value = HaPhase.OFFLINE
    }

    override fun refresh() {
        val running = runner?.isActive == true
        stop()
        if (running) start()
    }

    override fun clear() {
        live.clear()
        rooms.clear()
        deviceRooms.clear()
        _entities.value = emptyMap()
        _registry.value = HaRegistry()
        _instance.value = null
        _error.value = null
    }

    override fun update(account: HomeAccount): Boolean {
        val previous = this.account
        this.account = account
        val changed = previous.extra(HomeKeys.STRUCTURE_ID) != account.extra(HomeKeys.STRUCTURE_ID) || previous.signedIn != account.signedIn
        if (changed) {
            val running = runner?.isActive == true
            stop()
            clear()
            if (running) start()
        }
        return changed
    }

    override suspend fun callService(call: HaServiceCall) {
        val id = call.entityId ?: throw HaCommandException("no_target", "no entity")
        val l = live[id] ?: throw HaCommandException("not_found", id)
        try {
            val done = GoogleHomeMapping.command(call, l, _entities.value[id])
            if (!done) throw HaCommandException("unsupported", "${call.domain}.${call.service}")
        } catch (e: HomeException) {
            throw HaCommandException(e.error.code.toString(), e.message ?: "refused")
        }
    }

    override suspend fun states(): Map<String, HaEntity> {
        // The SDK's flows are the truth; a caller that cannot wait gets what they last delivered.
        if (_entities.value.isEmpty()) withTimeoutOrNull(STATES_TIMEOUT_MS) { entities.first { it.isNotEmpty() } }
        return _entities.value
    }

    override fun webUrl(entityId: String?): String? = null

    private companion object {
        const val CHANGE_BUFFER = 256
        const val STATES_TIMEOUT_MS = 4_000L
    }
}

/** For [HomeBackend] callers that wrap SDK failures as I/O. */
internal fun HomeException.asIo(): IOException = IOException(message, this)
