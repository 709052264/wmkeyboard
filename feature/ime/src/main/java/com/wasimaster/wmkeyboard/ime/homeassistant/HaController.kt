package com.wasimaster.wmkeyboard.ime.homeassistant

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.wasimaster.wmkeyboard.core.homeassistant.HaActions
import com.wasimaster.wmkeyboard.core.homeassistant.HaAssistReply
import com.wasimaster.wmkeyboard.core.homeassistant.HaAuth
import com.wasimaster.wmkeyboard.core.homeassistant.HaDomains
import com.wasimaster.wmkeyboard.core.homeassistant.HaEntity
import com.wasimaster.wmkeyboard.core.homeassistant.HaFound
import com.wasimaster.wmkeyboard.core.homeassistant.HaHttpException
import com.wasimaster.wmkeyboard.core.homeassistant.HaIcons
import com.wasimaster.wmkeyboard.core.homeassistant.HaInsertFormat
import com.wasimaster.wmkeyboard.core.homeassistant.HaKind
import com.wasimaster.wmkeyboard.core.homeassistant.HaPhase
import com.wasimaster.wmkeyboard.core.homeassistant.HaPrimary
import com.wasimaster.wmkeyboard.core.homeassistant.HaServiceCall
import com.wasimaster.wmkeyboard.core.homeassistant.HaStateFormat
import com.wasimaster.wmkeyboard.core.homeassistant.HaUrl
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeAccount
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeKeys
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeRef
import com.wasimaster.wmkeyboard.core.providers.ProviderCredential
import com.wasimaster.wmkeyboard.core.providers.ProviderTool
import com.wasimaster.wmkeyboard.core.providers.ProviderTypes
import com.wasimaster.wmkeyboard.core.settings.HaOpenOn
import com.wasimaster.wmkeyboard.core.settings.HomeAssistantSettings
import com.wasimaster.wmkeyboard.core.settings.ProviderEdits
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * The keyboard half of the smart home tool.
 *
 * Holds the panel's [HaUi], turns the panel's [HaAction]s into service calls
 * through [SmartHomeHub], and types into the field. Owned by the service
 * and built on first use; nothing here runs until the panel is opened.
 *
 * Every id the panel hands back is a [HomeRef]: the home's key and the
 * entity id. The hub routes by the first; [HaActions] gets the second.
 *
 * Taps are optimistic: the tile shows the state the call should produce, and
 * the server's next event confirms it. A tile the server contradicts, or a
 * call it refuses, shakes and goes back to the truth.
 */
@Suppress("detekt.TooManyFunctions", "detekt.LargeClass")
internal class HaController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val repository: SettingsRepository,
    private val settings: () -> HomeAssistantSettings,
    /** The homes as the settings resolve them now. */
    private val homes: () -> List<HomeAccount>,
    private val insertText: (String) -> Unit,
    private val haptic: () -> Unit,
    private val openToolSettings: () -> Unit,
    private val openRoute: (String) -> Unit,
    /** Hands the keys to the panel's text box, with what it should start with. */
    private val beginText: (HaTextEntry) -> Unit,
    /** Gives the keys back to the field. */
    private val endText: () -> Unit,
    /** What is in the panel's text box right now; it lives in the keyboard's state. */
    private val currentText: () -> HaTextEntry?,
    /** Puts a picture into the field, the way the gallery does. */
    private val commitImage: (File, String) -> Unit,
    /** The keyboard's current language tag, for Assist. */
    private val languageTag: () -> String,
) {

    private val app = context.applicationContext
    private val _state = MutableStateFlow(HaUi())
    val state: StateFlow<HaUi> = _state

    val callbacks = HaCallbacks(state = state, onAction = ::dispatch)

    private val format = HaStateFormat(HaResourceWords(app), Locale.getDefault())

    private var watch: List<Job> = emptyList()
    private var discovery: HaAndroidMdns? = null

    /** What each tapped tile claims until the server confirms it, by entity id. */
    private val pending = HashMap<String, Pending>()
    private val changedAt = HashMap<String, Long>()
    private var shakeNonce = 0L

    private data class Pending(val state: String, val job: Job)

    /**
     * The tool's settings as the repository last wrote them. A star or a
     * hide is written straight to the repository, and the service's own copy
     * ([settings]) catches up a beat later; the panel redraws from this one so
     * the star fills on the tap, not on the next event from the house.
     */
    private var fresh: HomeAssistantSettings? = null

    private fun prefs(): HomeAssistantSettings = fresh ?: settings()

    /** The entity id of a [HomeRef], for a service call. */
    private fun n(ref: String): String = HomeRef.native(ref)

    // ---- Lifecycle -----------------------------------------------------------

    fun onPanelOpen() {
        val s = settings()
        if (watch.isEmpty()) {
            watch = listOf(
                scope.launch { SmartHomeHub.state.collect { rebuild() } },
                scope.launch {
                    repository.settings.map { it.homeAssistant }.distinctUntilChanged().drop(1).collect {
                        fresh = it
                        rebuild()
                    }
                },
                scope.launch {
                    SmartHomeHub.changes.collect { ids ->
                        val now = System.currentTimeMillis()
                        ids.forEach { id ->
                            changedAt[id] = now
                            // The server agreed with the tap: the claim can go.
                            pending[id]?.let { p ->
                                if (SmartHomeHub.state.value.entities[id]?.state == p.state) clearPending(id)
                            }
                        }
                        rebuild()
                    }
                },
            )
        }
        val tab = when (s.openOn) {
            HaOpenOn.FAVOURITES -> HaTab.FAVOURITES
            HaOpenOn.LAST_TAB -> s.lastTab.ifEmpty { HaTab.FAVOURITES }
        }
        _state.update { it.copy(tab = tab, expanded = null, searchOpen = false, search = "", undo = null, snapshot = null) }
        rebuild()
        // Nothing set up yet: look around the LAN so the first tile the user sees is their own server.
        if (homes().isEmpty() && discovery == null) {
            val d = HaAndroidMdns(app)
            discovery = d
            d.start { found -> scope.launch { _state.update { it.copy(found = found) } } }
        }
    }

    fun onPanelClose() {
        endTextIfAny()
        discovery?.stop()
        discovery = null
        _state.update { it.copy(expanded = null, searchOpen = false, search = "", assist = it.assist.copy(open = false, pending = false), snapshot = null) }
    }

    /** Back from the browser or the settings page: the sign-in may have landed. */
    fun onKeyboardShown() {
        if (watch.isNotEmpty()) rebuild()
    }

    // ---- Building the view -----------------------------------------------------

    @Suppress("detekt.LongMethod", "detekt.CyclomaticComplexMethod")
    private fun rebuild() {
        val s = prefs()
        val hub = SmartHomeHub.state.value
        val entities = SmartHomeHub.entitiesWithCache()
        fun regOf(e: HaEntity): com.wasimaster.wmkeyboard.core.homeassistant.HaRegistry = SmartHomeHub.cachedRegistry(e.home) ?: hub.registryOf(e.home)
        // An area is its home's: two homes may each have a Kitchen.
        fun areaKeyOf(e: HaEntity): String? = regOf(e).areaIdOf(e.entityId)?.let { HomeRef.of(e.home, it) }
        val all = entities.values.asSequence()
            .filter { HaDomains.isListed(it.domain) }
            .filter { HomeRef.of(it) !in s.hidden && it.domain !in s.hiddenDomains }
            .filter { !regOf(it).isHidden(it.entityId) }
            .toList()
        val favourites = s.favourites.toSet()
        val several = hub.homes.size > 1

        val areaTabs = if (s.areasAsTabs) {
            val counts = HashMap<String, Int>()
            all.forEach { e -> areaKeyOf(e)?.let { counts[it] = (counts[it] ?: 0) + 1 } }
            hub.homes.flatMap { home ->
                val registry = SmartHomeHub.cachedRegistry(home.key) ?: hub.registryOf(home.key)
                registry.orderedAreas()
                    .map { it to HomeRef.of(home.key, it.areaId) }
                    .filter { (_, key) -> (counts[key] ?: 0) > 0 }
                    .map { (area, key) -> HaTab(HaTab.area(key), if (several && home.name.isNotBlank()) "${home.name} · ${area.name}" else area.name, key, counts[key] ?: 0) }
            }
        } else {
            emptyList()
        }
        val unassigned = if (s.areasAsTabs && areaTabs.isNotEmpty()) all.count { areaKeyOf(it) == null } else 0
        val tabs = buildList {
            add(HaTab(HaTab.FAVOURITES, app.getString(R.string.ime_ha_tab_favourites), count = favourites.size))
            addAll(areaTabs)
            if (unassigned > 0) add(HaTab(HaTab.UNASSIGNED, app.getString(R.string.ime_ha_tab_other), count = unassigned))
            add(HaTab(HaTab.ALL, app.getString(R.string.ime_ha_tab_all), count = all.size))
        }
        val current = _state.value
        val tabId = if (tabs.any { it.id == current.tab }) current.tab else HaTab.FAVOURITES

        val query = current.search.trim().lowercase(Locale.ROOT)
        fun nameOf(e: HaEntity): String = regOf(e).nameOf(e)
        val shown: List<HaEntity> = when {
            current.searchOpen && query.isNotEmpty() -> all.filter { e ->
                val name = nameOf(e).lowercase(Locale.ROOT)
                val area = regOf(e).areaOf(e.entityId)?.name?.lowercase(Locale.ROOT).orEmpty()
                name.contains(query) || e.entityId.contains(query) || area.contains(query)
            }.sortedWith(compareBy({ !nameOf(it).lowercase(Locale.ROOT).startsWith(query) }, { nameOf(it) }))
            tabId == HaTab.FAVOURITES -> s.favourites.mapNotNull { id -> entities[id] }
            tabId == HaTab.ALL -> all.sortedWith(compareBy({ kindRank(it) }, { nameOf(it) }))
            tabId == HaTab.UNASSIGNED -> all.filter { areaKeyOf(it) == null }.sortedWith(compareBy({ kindRank(it) }, { nameOf(it) }))
            else -> {
                val areaKey = tabId.removePrefix("area:")
                all.filter { areaKeyOf(it) == areaKey }.sortedWith(compareBy({ kindRank(it) }, { nameOf(it) }))
            }
        }
        val tiles = shown.map { e ->
            val registry = regOf(e)
            val glyph = HaIcons.styled(HaIcons.glyph(e, registry.iconOf(e)), e)
            val kind = HaDomains.kindOf(e)
            val ref = HomeRef.of(e)
            val claimed = pending[ref]?.state
            val shownEntity = if (claimed != null) e.copy(state = claimed) else e
            HaTile(
                entity = e,
                name = registry.nameOf(e),
                areaName = registry.areaOf(e.entityId)?.name,
                kind = kind,
                glyph = glyph,
                stateLine = format.state(shownEntity),
                value = if (kind == HaKind.SENSOR || kind == HaKind.WEATHER) format.value(e) else null,
                primary = HaDomains.primary(e),
                hasSwitch = HaDomains.hasSwitch(e),
                risky = HaDomains.isRisky(e),
                favourite = ref in favourites,
                pendingState = claimed,
                changedAtMs = changedAt[ref] ?: 0L,
            )
        }
        val listed = homes()
        _state.update {
            it.copy(
                enabled = s.enabled,
                hasServer = listed.isNotEmpty() || hub.homes.isNotEmpty(),
                hasToken = hub.homes.isNotEmpty(),
                homes = hub.homes,
                phase = hub.phase,
                fromCache = hub.fromCache,
                instanceName = hub.instanceName.ifBlank { app.getString(R.string.ime_ha_default_name) },
                tabs = tabs,
                tab = tabId,
                tiles = tiles,
                entityCount = entities.size,
                expanded = it.expanded?.takeIf { id -> entities.containsKey(id) },
                hasAssist = hub.assistHome != null,
                assistEnabled = s.assistEnabled && hub.assistHome != null,
                confirmRisky = s.confirmRisky,
                tileSize = s.tileSize,
                insertFormat = HaInsertFormat.of(s.insertFormat),
                lastError = hub.lastError,
            )
        }
    }

    /** Controls first, then what runs, then what only reads. */
    private fun kindRank(e: HaEntity): Int = when (HaDomains.kindOf(e)) {
        HaKind.LIGHT -> 0
        HaKind.SWITCH, HaKind.FAN -> 1
        HaKind.COVER, HaKind.VALVE, HaKind.LOCK, HaKind.ALARM -> 2
        HaKind.CLIMATE, HaKind.HUMIDIFIER, HaKind.WATER_HEATER -> 3
        HaKind.MEDIA, HaKind.VACUUM, HaKind.CAMERA, HaKind.SIREN -> 4
        HaKind.SCENE, HaKind.SCRIPT, HaKind.BUTTON, HaKind.AUTOMATION -> 5
        HaKind.NUMBER, HaKind.SELECT, HaKind.TEXT -> 6
        HaKind.SENSOR, HaKind.BINARY_SENSOR, HaKind.WEATHER, HaKind.PERSON, HaKind.OTHER -> 7
    }

    private fun entity(id: String): HaEntity? = SmartHomeHub.entity(id)

    // ---- The panel's text box ----------------------------------------------

    private var textField: HaTextField? = null

    private fun startText(entry: HaTextEntry) {
        textField = entry.field
        beginText(entry)
    }

    private fun endTextIfAny() {
        if (textField != null) {
            textField = null
            endText()
        }
    }

    /** Called by the service as the box is typed into. */
    fun onText(entry: HaTextEntry) {
        when (entry.field) {
            HaTextField.SEARCH -> {
                _state.update { it.copy(search = entry.text) }
                rebuild()
            }
            HaTextField.ASSIST -> _state.update { it.copy(assist = it.assist.copy(query = entry.text)) }
            HaTextField.CODE, HaTextField.VALUE -> Unit
        }
    }

    /** Enter in the box: search opens the first tile, Assist sends, a code runs its action, a value is set. */
    fun onTextEnter(entry: HaTextEntry) {
        when (entry.field) {
            HaTextField.SEARCH -> {
                _state.value.tiles.firstOrNull()?.let { tap(it.id) }
                return
            }
            HaTextField.ASSIST -> {
                sendAssist(entry.text)
                return
            }
            HaTextField.CODE -> {
                endTextIfAny()
                runCoded(entry.ref, entry.purpose, entry.text.trim())
            }
            HaTextField.VALUE -> {
                endTextIfAny()
                call(entry.ref, HaActions.setText(n(entry.ref), entry.text), expected = entry.text)
            }
        }
    }

    /** A smart chip or the AI handed the panel a sentence as it opened: send it straight away. */
    fun prefillAssist(sentence: String) {
        if (!settings().assistEnabled) return
        _state.update { it.copy(assist = it.assist.copy(open = true, query = sentence)) }
        sendAssist(sentence)
    }

    // ---- Actions -------------------------------------------------------------

    @Suppress("detekt.LongMethod", "detekt.CyclomaticComplexMethod")
    private fun dispatch(action: HaAction) {
        when (action) {
            is HaAction.Tab -> {
                endTextIfAny()
                _state.update { it.copy(tab = action.id, expanded = null, searchOpen = false, search = "", undo = null) }
                scope.launch { repository.setHaLastTab(action.id) }
                rebuild()
            }
            is HaAction.Expand -> {
                haptic()
                _state.update { it.copy(expanded = action.id, snapshot = null) }
                if (entity(action.id)?.domain == "camera") loadSnapshot(action.id)
            }
            HaAction.Collapse -> {
                endTextIfAny()
                _state.update { it.copy(expanded = null, snapshot = null) }
            }
            is HaAction.Tap -> tap(action.id)
            is HaAction.Toggle -> {
                haptic()
                call(action.id, if (action.on) HaActions.turnOn(n(action.id)) else HaActions.turnOff(n(action.id)), expected = if (action.on) "on" else "off")
            }
            is HaAction.Run -> run(action.id)

            is HaAction.Brightness -> call(action.id, HaActions.setBrightnessPct(n(action.id), action.pct), expected = if (action.pct > 0) "on" else "off")
            is HaAction.ColorTemp -> call(action.id, HaActions.setColorTempKelvin(n(action.id), action.kelvin))
            is HaAction.HsColor -> call(action.id, HaActions.setHsColor(n(action.id), action.hue, action.saturation))
            is HaAction.Effect -> call(action.id, HaActions.setEffect(n(action.id), action.effect))

            is HaAction.Cover -> {
                haptic()
                val (c, expected) = when (action.command) {
                    HaAction.CoverCommand.OPEN -> HaActions.openCover(n(action.id)) to "opening"
                    HaAction.CoverCommand.CLOSE -> HaActions.closeCover(n(action.id)) to "closing"
                    HaAction.CoverCommand.STOP -> HaActions.stopCover(n(action.id)) to null
                }
                if (entity(action.id)?.domain == "valve") {
                    call(action.id, when (action.command) {
                        HaAction.CoverCommand.OPEN -> HaActions.openValve(n(action.id))
                        HaAction.CoverCommand.CLOSE -> HaActions.closeValve(n(action.id))
                        HaAction.CoverCommand.STOP -> HaActions.stopValve(n(action.id))
                    }, expected)
                } else {
                    call(action.id, c, expected)
                }
            }
            is HaAction.Position -> call(
                action.id,
                if (entity(action.id)?.domain == "valve") HaActions.setValvePosition(n(action.id), action.pct) else HaActions.setCoverPosition(n(action.id), action.pct),
            )
            is HaAction.Tilt -> call(action.id, HaActions.setCoverTilt(n(action.id), action.pct))

            is HaAction.Percentage -> call(action.id, HaActions.setFanPercentage(n(action.id), action.pct), expected = if (action.pct > 0) "on" else "off")
            is HaAction.Oscillate -> call(action.id, HaActions.oscillate(n(action.id), action.on))
            is HaAction.FanDirection -> call(action.id, HaActions.setFanDirection(n(action.id), action.direction))
            is HaAction.FanPreset -> call(action.id, HaActions.setFanPreset(n(action.id), action.preset))

            is HaAction.Temperature -> call(
                action.id,
                if (entity(action.id)?.domain == "water_heater") HaActions.setWaterTemperature(n(action.id), action.value) else HaActions.setTemperature(n(action.id), action.value),
            )
            is HaAction.TemperatureRange -> call(action.id, HaActions.setTemperatureRange(n(action.id), action.low, action.high))
            is HaAction.HvacMode -> {
                haptic()
                call(action.id, HaActions.setHvacMode(n(action.id), action.mode), expected = action.mode)
            }
            is HaAction.Preset -> call(action.id, HaActions.setPresetMode(n(action.id), action.preset))
            is HaAction.FanMode -> call(action.id, HaActions.setFanMode(n(action.id), action.mode))
            is HaAction.Humidity -> call(action.id, HaActions.setHumidity(n(action.id), action.pct))
            is HaAction.Mode -> {
                haptic()
                val e = entity(action.id)
                call(
                    action.id,
                    when (e?.domain) {
                        "humidifier" -> HaActions.setHumidifierMode(n(action.id), action.mode)
                        "water_heater" -> HaActions.setWaterMode(n(action.id), action.mode)
                        else -> HaActions.setPresetMode(n(action.id), action.mode)
                    },
                    expected = if (e?.domain == "water_heater") action.mode else null,
                )
            }

            is HaAction.Media -> {
                haptic()
                val c = when (action.command) {
                    HaAction.MediaCommand.PLAY_PAUSE -> HaActions.mediaPlayPause(n(action.id))
                    HaAction.MediaCommand.NEXT -> HaActions.mediaNext(n(action.id))
                    HaAction.MediaCommand.PREVIOUS -> HaActions.mediaPrevious(n(action.id))
                    HaAction.MediaCommand.STOP -> HaActions.mediaStop(n(action.id))
                }
                call(action.id, c, expected = entity(action.id)?.let { HaActions.expectedState(it, c) })
            }
            is HaAction.Volume -> call(action.id, HaActions.setVolume(n(action.id), action.level))
            is HaAction.Mute -> call(action.id, HaActions.mute(n(action.id), action.muted))
            is HaAction.Source -> call(action.id, HaActions.selectSource(n(action.id), action.source))

            is HaAction.Lock -> coded(action.id, "lock", action.code)
            is HaAction.Unlock -> coded(action.id, "unlock", action.code)
            is HaAction.OpenLock -> coded(action.id, "open", action.code)
            is HaAction.Arm -> coded(action.id, "arm:${action.mode.name}", action.code)
            is HaAction.Disarm -> coded(action.id, "disarm", action.code)
            is HaAction.AskCode -> startText(HaTextEntry(HaTextField.CODE, "", action.id, action.purpose))

            is HaAction.Vacuum -> {
                haptic()
                call(
                    action.id, HaActions.vacuum(n(action.id), action.command),
                    expected = when (action.command) {
                        HaActions.VacuumCommand.START -> "cleaning"
                        HaActions.VacuumCommand.PAUSE -> "paused"
                        HaActions.VacuumCommand.RETURN -> "returning"
                        HaActions.VacuumCommand.STOP -> "idle"
                        HaActions.VacuumCommand.LOCATE -> null
                    },
                )
            }
            is HaAction.VacuumSpeed -> call(action.id, HaActions.setVacuumFanSpeed(n(action.id), action.speed))

            is HaAction.Value -> call(action.id, HaActions.setValue(n(action.id), action.value), expected = format.number(action.value, entity(action.id)?.step))
            is HaAction.Option -> {
                haptic()
                call(action.id, HaActions.selectOption(n(action.id), action.option), expected = action.option)
            }
            is HaAction.EditText -> startText(HaTextEntry(HaTextField.VALUE, entity(action.id)?.state.orEmpty(), action.id))

            is HaAction.Insert -> insert(action.id, action.format)
            is HaAction.DefaultInsertFormat -> {
                haptic()
                scope.launch { repository.setHaInsertFormat(action.format.id) }
            }

            is HaAction.Favourite -> {
                haptic()
                scope.launch {
                    // Where it stood, read from the list being written, not from a copy that may lag the last tap.
                    var index = -1
                    repository.updateHaFavourites { list ->
                        index = list.indexOf(action.id)
                        if (action.on) list + action.id else list - action.id
                    }
                    _state.update { it.copy(undo = if (!action.on && index >= 0) HaUndo.Unfavourited(action.id, index) else null) }
                }
            }
            is HaAction.MoveFavourite -> scope.launch {
                repository.updateHaFavourites { list ->
                    val i = list.indexOf(action.id)
                    val j = i + action.by
                    if (i < 0 || j !in list.indices) list else list.toMutableList().apply { add(j, removeAt(i)) }
                }
            }
            is HaAction.Hide -> {
                haptic()
                scope.launch { repository.updateHaHidden { it + action.id } }
                _state.update { it.copy(expanded = null, undo = HaUndo.Hidden(action.id)) }
            }

            HaAction.SearchOpen -> {
                _state.update { it.copy(searchOpen = true, search = "", expanded = null, assist = it.assist.copy(open = false)) }
                startText(HaTextEntry(HaTextField.SEARCH))
                rebuild()
            }
            HaAction.SearchClose -> {
                endTextIfAny()
                _state.update { it.copy(searchOpen = false, search = "") }
                rebuild()
            }
            HaAction.AssistOpen -> {
                _state.update { it.copy(assist = it.assist.copy(open = true), expanded = null, searchOpen = false, search = "") }
                startText(HaTextEntry(HaTextField.ASSIST, _state.value.assist.query))
                rebuild()
            }
            HaAction.AssistClose -> {
                endTextIfAny()
                _state.update { it.copy(assist = it.assist.copy(open = false)) }
            }
            HaAction.AssistSend -> sendAssist(currentText()?.takeIf { it.field == HaTextField.ASSIST }?.text ?: _state.value.assist.query)
            HaAction.AssistInsertReply -> _state.value.assist.reply?.speech?.takeIf { it.isNotBlank() }?.let(insertText)
            HaAction.AssistClear -> _state.update { it.copy(assist = AssistUi(open = it.assist.open)) }

            is HaAction.Snapshot -> loadSnapshot(action.id)
            is HaAction.SendSnapshot -> sendSnapshot(action.id)

            HaAction.Connect -> openToolSettings()
            HaAction.SignInFound -> signInToFound()
            HaAction.Retry -> {
                haptic()
                SmartHomeHub.refresh()
            }
            HaAction.OpenSettings -> openToolSettings()
            is HaAction.OpenInHomeAssistant -> openInHomeAssistant(action.id)

            HaAction.TextDone -> currentText()?.let(::onTextEnter) ?: endTextIfAny()
            HaAction.TextCancel -> {
                endTextIfAny()
                _state.update { it.copy(searchOpen = false, search = "", assist = it.assist.copy(open = false)) }
                rebuild()
            }
            HaAction.Undo -> undo()
            HaAction.UndoDismiss -> _state.update { it.copy(undo = null) }
        }
    }

    private fun tap(id: String) {
        val e = entity(id) ?: return
        when (HaDomains.primary(e)) {
            HaPrimary.TOGGLE -> {
                haptic()
                val c = HaActions.toggle(n(id))
                call(id, c, expected = HaActions.expectedState(e, c))
            }
            HaPrimary.RUN -> run(id)
            HaPrimary.PRESS -> {
                haptic()
                call(id, HaActions.press(n(id)))
                _state.update { it.copy(ranAt = it.ranAt + (id to System.currentTimeMillis())) }
            }
            HaPrimary.EXPAND -> dispatch(HaAction.Expand(id))
            HaPrimary.INSERT -> insert(id, null)
            HaPrimary.NONE -> Unit
        }
    }

    private fun run(id: String) {
        val e = entity(id) ?: return
        haptic()
        call(id, if (e.domain == "script") HaActions.runScript(n(id)) else HaActions.runScene(n(id)))
        _state.update { it.copy(ranAt = it.ranAt + (id to System.currentTimeMillis())) }
    }

    /** A lock or alarm action: with its code when one is needed and none was given, ask first. */
    private fun coded(id: String, purpose: String, code: String?) {
        val e = entity(id) ?: return
        val needsCode = e.codeFormat != null && code.isNullOrEmpty() &&
            (e.domain == "lock" || purpose == "disarm" || e.codeArmRequired)
        if (needsCode) {
            startText(HaTextEntry(HaTextField.CODE, "", id, purpose))
            return
        }
        runCoded(id, purpose, code)
    }

    private fun runCoded(id: String, purpose: String, code: String?) {
        haptic()
        val c: HaServiceCall = when {
            purpose == "lock" -> HaActions.lock(n(id), code)
            purpose == "unlock" -> HaActions.unlock(n(id), code)
            purpose == "open" -> HaActions.openLock(n(id), code)
            purpose == "disarm" -> HaActions.disarm(n(id), code)
            purpose.startsWith("arm:") -> HaActions.arm(n(id), HaActions.ArmMode.valueOf(purpose.removePrefix("arm:")), code)
            else -> return
        }
        call(id, c, expected = entity(id)?.let { HaActions.expectedState(it, c) })
    }

    private fun insert(id: String, format: HaInsertFormat?) {
        val e = entity(id) ?: return
        haptic()
        val chosen = format ?: HaInsertFormat.of(settings().insertFormat)
        insertText(this.format.insert(e, chosen, SmartHomeHub.state.value.registryOf(e).nameOf(e)))
    }

    /**
     * Sends [call] and claims [expected] on the tile until the server confirms
     * it. A refusal, or three seconds with no agreeing event, shakes the tile.
     */
    private fun call(id: String, call: HaServiceCall, expected: String? = null) {
        if (expected != null) {
            clearPending(id)
            pending[id] = Pending(
                expected,
                scope.launch {
                    delay(PENDING_MS)
                    if (pending[id]?.state == expected && entity(id)?.state != expected) {
                        pending.remove(id)
                        shake(id)
                    } else {
                        pending.remove(id)
                    }
                    rebuild()
                },
            )
            rebuild()
        }
        scope.launch {
            try {
                SmartHomeHub.callService(HomeRef.home(id), call)
                _state.update { it.copy(lastError = null) }
            } catch (e: IOException) {
                clearPending(id)
                shake(id)
                _state.update { it.copy(lastError = friendly(e)) }
                rebuild()
            }
        }
    }

    private fun clearPending(id: String) {
        pending.remove(id)?.job?.cancel()
    }

    private fun shake(id: String) {
        _state.update { it.copy(shake = id to ++shakeNonce) }
    }

    private fun friendly(e: Throwable): String = when (e) {
        is HaHttpException -> if (e.tokenRejected) app.getString(R.string.ime_ha_error_token) else app.getString(R.string.ime_ha_error_refused)
        is com.wasimaster.wmkeyboard.core.homeassistant.HaCommandException -> e.message ?: app.getString(R.string.ime_ha_error_refused)
        else -> app.getString(R.string.ime_ha_error_unreachable)
    }

    // ---- Assist -----------------------------------------------------------------

    private var assistJob: Job? = null

    private fun sendAssist(text: String) {
        val sentence = text.trim()
        if (sentence.isEmpty()) return
        haptic()
        val language = if (settings().assistFollowsKeyboardLanguage) languageTag().substringBefore('-').takeIf { it.isNotBlank() } else null
        _state.update { it.copy(assist = it.assist.copy(open = true, pending = true, error = null, asked = sentence, query = "")) }
        currentText()?.takeIf { it.field == HaTextField.ASSIST }?.let { beginText(it.copy(text = "")) }
        assistJob?.cancel()
        assistJob = scope.launch {
            try {
                val reply = SmartHomeHub.assist(sentence, language, _state.value.assist.conversationId)
                _state.update {
                    it.copy(
                        assist = it.assist.copy(
                            pending = false,
                            reply = reply,
                            conversationId = if (reply.continueConversation) reply.conversationId else null,
                        ),
                    )
                }
                // Assist names the home's own entity ids; the tiles go by refs.
                val home = SmartHomeHub.state.value.assistHome?.key.orEmpty()
                if (reply.failedEntityIds.isNotEmpty()) reply.failedEntityIds.forEach { shake(HomeRef.of(home, it)) }
                val now = System.currentTimeMillis()
                reply.successEntityIds.forEach { changedAt[HomeRef.of(home, it)] = now }
                if (reply.successEntityIds.isNotEmpty()) rebuild()
            } catch (e: IOException) {
                _state.update { it.copy(assist = it.assist.copy(pending = false, error = friendly(e))) }
            }
        }
    }

    /** What the AI's tool or a snippet asks: the same path, no panel state. */
    suspend fun assistForTool(sentence: String): HaAssistReply {
        val language = languageTag().substringBefore('-').takeIf { it.isNotBlank() }
        return SmartHomeHub.assist(sentence, language, null)
    }

    // ---- Camera ------------------------------------------------------------------

    private var snapshotJob: Job? = null

    private fun loadSnapshot(id: String) {
        snapshotJob?.cancel()
        _state.update { it.copy(snapshot = HaSnapshotUi(id, loading = true)) }
        snapshotJob = scope.launch {
            try {
                val bytes = SmartHomeHub.snapshot(id)
                _state.update { s -> if (s.snapshot?.entityId == id) s.copy(snapshot = HaSnapshotUi(id, bytes)) else s }
            } catch (e: IOException) {
                _state.update { s -> if (s.snapshot?.entityId == id) s.copy(snapshot = HaSnapshotUi(id, error = friendly(e))) else s }
            }
        }
    }

    private fun sendSnapshot(id: String) {
        val bytes = _state.value.snapshot?.takeIf { it.entityId == id }?.bytes ?: return
        haptic()
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val dir = File(app.cacheDir, "media").apply { mkdirs() }
            val png = bytes.size > 4 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte()
            val file = File(dir, "ha-${System.currentTimeMillis()}.${if (png) "png" else "jpg"}")
            file.writeBytes(bytes)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                commitImage(file, if (png) "image/png" else "image/jpeg")
            }
        }
    }

    // ---- Links out ---------------------------------------------------------------

    /**
     * The server the LAN announced becomes a Home Assistant row of the
     * smart home list, and the browser opens on its sign-in. The row shows
     * as "sign in" until the code comes back.
     */
    private fun signInToFound() {
        val found = _state.value.found ?: return
        val base: HaUrl = found.internal ?: return
        haptic()
        scope.launch {
            val id = ProviderCredential.newId(ProviderTypes.HOME_ASSISTANT.id)
            val state = HaAuth.newState(base, id)
            val credential = ProviderCredential(
                id = id,
                typeId = ProviderTypes.HOME_ASSISTANT.id,
                name = found.name,
                url = base.base,
                extra = buildMap {
                    found.external?.let { put(HomeKeys.EXTERNAL_URL, it.base) }
                    put(HomeKeys.AUTH_STATE, state)
                    found.uuid?.let { put(HomeKeys.INSTANCE_ID, it) }
                },
            )
            repository.saveProviders(ProviderEdits.saveCredential(repository.settings.first(), credential, ProviderTool.SMART_HOME))
            repository.setHaEnabled(true)
            runCatching {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(HaAuth.authorizeUrl(base, state))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun openInHomeAssistant(id: String?) {
        val url = SmartHomeHub.webUrl(id) ?: return
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun undo() {
        when (val u = _state.value.undo) {
            is HaUndo.Unfavourited -> scope.launch {
                repository.updateHaFavourites { list ->
                    if (u.id in list) list else list.toMutableList().apply { add(u.index.coerceIn(0, size), u.id) }
                }
            }
            is HaUndo.Hidden -> scope.launch { repository.updateHaHidden { it - u.id } }
            null -> Unit
        }
        _state.update { it.copy(undo = null) }
    }

    // ---- Snippets ------------------------------------------------------------------

    /**
     * `{ha:entity_id}` or `{ha:entity_id:attribute}`: the last known state, or
     * null when the tool is off or no home has seen the entity. The entity id
     * is searched across every home; `{ha:home_key|entity_id}` pins one.
     * Asks the homes for fresh states when they are connected or can connect
     * in a moment.
     */
    fun snippetValue(entityId: String, attribute: String?): String? {
        val s = settings()
        val listed = homes()
        if (!s.enabled || listed.isEmpty()) return null
        val hub = SmartHomeHub
        hub.attach(app)
        hub.batch {
            hub.apply(s, listed)
            hub.hold(SmartHomeHub.Reason.SNIPPET)
        }
        scope.launch {
            delay(SNIPPET_HOLD_MS)
            hub.release(SmartHomeHub.Reason.SNIPPET)
        }
        val all = hub.entitiesWithCache()
        val ref = if (HomeRef.isRef(entityId)) entityId else all.keys.firstOrNull { HomeRef.native(it) == entityId } ?: return null
        val e = all[ref] ?: return null
        return if (attribute == null) {
            format.insert(e, HaInsertFormat.VALUE_UNIT, (hub.cachedRegistry(e.home) ?: hub.state.value.registryOf(e)).nameOf(e))
        } else {
            e.attributes[attribute]?.let { v -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: v.toString() }
        }
    }

    companion object {
        const val TEXT_MAX = 200
        private const val PENDING_MS = 3_000L
        private const val SNIPPET_HOLD_MS = 15_000L
    }
}

/** The state vocabulary from the keyboard's own strings, so a tile reads in the user's language. */
private class HaResourceWords(private val context: Context) : com.wasimaster.wmkeyboard.core.homeassistant.HaWords {
    override fun word(w: com.wasimaster.wmkeyboard.core.homeassistant.HaWord): String = HaWordStrings.resource(w)?.let(context::getString)
        ?: com.wasimaster.wmkeyboard.core.homeassistant.HaWords.English.word(w)
}
