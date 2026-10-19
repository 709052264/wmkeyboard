package com.wasimaster.wmkeyboard.ime.homeassistant

import com.wasimaster.wmkeyboard.core.homeassistant.HaActions
import com.wasimaster.wmkeyboard.core.homeassistant.HaAssistReply
import com.wasimaster.wmkeyboard.core.homeassistant.HaEntity
import com.wasimaster.wmkeyboard.core.homeassistant.HaFound
import com.wasimaster.wmkeyboard.core.homeassistant.HaGlyph
import com.wasimaster.wmkeyboard.core.homeassistant.HaInsertFormat
import com.wasimaster.wmkeyboard.core.homeassistant.HaKind
import com.wasimaster.wmkeyboard.core.homeassistant.HaPhase
import com.wasimaster.wmkeyboard.core.homeassistant.HaPrimary
import com.wasimaster.wmkeyboard.core.homeassistant.backend.HomeRef
import com.wasimaster.wmkeyboard.core.settings.HaTileSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One tab along the top: Favourites, an area, everything, or the entities with no area. */
data class HaTab(val id: String, val name: String, val areaId: String? = null, val count: Int = 0) {
    companion object {
        const val FAVOURITES = "fav"
        const val ALL = "all"
        const val UNASSIGNED = "other"
        fun area(id: String) = "area:$id"
    }
}

/**
 * One tile, ready to draw: the entity plus everything the panel would
 * otherwise work out per frame. [stateLine] is the human state, [value] the
 * big figure a sensor shows, [pendingState] what the tile claims while the
 * server has not confirmed a tap yet.
 */
data class HaTile(
    val entity: HaEntity,
    val name: String,
    val areaName: String?,
    val kind: HaKind,
    val glyph: HaGlyph,
    val stateLine: String,
    val value: String?,
    val primary: HaPrimary,
    val hasSwitch: Boolean,
    val risky: Boolean,
    val favourite: Boolean,
    val pendingState: String? = null,
    /** The last time the server changed it while the panel watched, for the pulse. */
    val changedAtMs: Long = 0L,
) {
    /** The [HomeRef]: the home's key and the entity id, which is what every action names. */
    val id: String get() = HomeRef.of(entity)

    /** On, as the tile shows it: the pending state wins while it lasts. */
    val shownOn: Boolean get() = (pendingState ?: entity.state) == "on"
    val shownState: String get() = pendingState ?: entity.state
    val isPending: Boolean get() = pendingState != null
}

/** The Assist bar's state. */
data class AssistUi(
    val open: Boolean = false,
    val query: String = "",
    val pending: Boolean = false,
    val reply: HaAssistReply? = null,
    val conversationId: String? = null,
    val error: String? = null,
    /** The sentence the reply answered, shown over it. */
    val asked: String = "",
)

/** A camera still as the expanded card shows it. */
data class HaSnapshotUi(val entityId: String, val bytes: ByteArray? = null, val loading: Boolean = false, val error: String? = null)

/**
 * Something to put back. [at] makes each offer its own: the same star taken
 * off twice is two offers, and the bar keys its swipe state by the offer.
 */
sealed interface HaUndo {
    data class Unfavourited(val id: String, val index: Int, val at: Long = System.nanoTime()) : HaUndo
    data class Hidden(val id: String, val at: Long = System.nanoTime()) : HaUndo
}

/** What the keyboard's text box in the panel is for. */
enum class HaTextField { SEARCH, ASSIST, CODE, VALUE }

/**
 * The text box the panel is typing into: lives in `KeyboardUiState` so the
 * shared capture path can type into it. [ref] is the entity a code or value
 * is for, and for a code which action follows it (`lock`, `unlock`, `arm:away`, `disarm`).
 */
data class HaTextEntry(val field: HaTextField, val text: String = "", val ref: String = "", val purpose: String = "")

/** Everything the Home Assistant panel draws; owned by [HaController]. */
data class HaUi(
    val enabled: Boolean = false,
    /** At least one home is listed. */
    val hasServer: Boolean = false,
    /** At least one home is signed in or paired, so there is something to connect. */
    val hasToken: Boolean = false,
    /** The homes, in list order, for the header's dots. */
    val homes: List<HomeStatus> = emptyList(),
    val phase: HaPhase = HaPhase.OFFLINE,
    /** The tiles come from the last session's cache and the socket is not up yet. */
    val fromCache: Boolean = false,
    val instanceName: String = "",
    val tabs: List<HaTab> = emptyList(),
    val tab: String = HaTab.FAVOURITES,
    val tiles: List<HaTile> = emptyList(),
    /** How many entities the server has at all; zero while nothing has loaded. */
    val entityCount: Int = 0,
    val expanded: String? = null,
    /** Whether any listed home has an assistant; the bar needs one. */
    val hasAssist: Boolean = false,
    /** The search box is open; its text is in `KeyboardUiState.haText`. */
    val searchOpen: Boolean = false,
    val search: String = "",
    val assist: AssistUi = AssistUi(),
    val assistEnabled: Boolean = true,
    val confirmRisky: Boolean = true,
    val tileSize: HaTileSize = HaTileSize.COMFORTABLE,
    val insertFormat: HaInsertFormat = HaInsertFormat.VALUE_UNIT,
    /** Entities whose last tap the server contradicted or refused, with a nonce so the same tile can shake twice. */
    val shake: Pair<String, Long>? = null,
    /** Scenes and scripts just run, by the time they ran, for the flash. */
    val ranAt: Map<String, Long> = emptyMap(),
    val snapshot: HaSnapshotUi? = null,
    /** An instance seen on the LAN while nothing is set up yet. */
    val found: HaFound? = null,
    val lastError: String? = null,
    val undo: HaUndo? = null,
) {
    val connected: Boolean get() = phase == HaPhase.LIVE || phase == HaPhase.REST_FALLBACK
    val expandedTile: HaTile? get() = expanded?.let { id -> tiles.firstOrNull { it.id == id } }
}

/** Everything the panel can ask for, as one type, so the callback bundle stays one field. */
sealed interface HaAction {
    data class Tab(val id: String) : HaAction
    data class Expand(val id: String) : HaAction
    data object Collapse : HaAction

    /** The one-tap action of a tile: toggle, run, press, insert or expand, by its kind. */
    data class Tap(val id: String) : HaAction
    data class Toggle(val id: String, val on: Boolean) : HaAction
    data class Run(val id: String) : HaAction

    // Light
    data class Brightness(val id: String, val pct: Int) : HaAction
    data class ColorTemp(val id: String, val kelvin: Int) : HaAction
    data class HsColor(val id: String, val hue: Double, val saturation: Double) : HaAction
    data class Effect(val id: String, val effect: String) : HaAction

    // Cover and valve
    enum class CoverCommand { OPEN, CLOSE, STOP }
    data class Cover(val id: String, val command: CoverCommand) : HaAction
    data class Position(val id: String, val pct: Int) : HaAction
    data class Tilt(val id: String, val pct: Int) : HaAction

    // Fan
    data class Percentage(val id: String, val pct: Int) : HaAction
    data class Oscillate(val id: String, val on: Boolean) : HaAction
    data class FanDirection(val id: String, val direction: String) : HaAction
    data class FanPreset(val id: String, val preset: String) : HaAction

    // Climate, water heater, humidifier
    data class Temperature(val id: String, val value: Double) : HaAction
    data class TemperatureRange(val id: String, val low: Double, val high: Double) : HaAction
    data class HvacMode(val id: String, val mode: String) : HaAction
    data class Preset(val id: String, val preset: String) : HaAction
    data class FanMode(val id: String, val mode: String) : HaAction
    data class Humidity(val id: String, val pct: Int) : HaAction
    data class Mode(val id: String, val mode: String) : HaAction

    // Media
    enum class MediaCommand { PLAY_PAUSE, NEXT, PREVIOUS, STOP }
    data class Media(val id: String, val command: MediaCommand) : HaAction
    data class Volume(val id: String, val level: Double) : HaAction
    data class Mute(val id: String, val muted: Boolean) : HaAction
    data class Source(val id: String, val source: String) : HaAction

    // Lock and alarm
    data class Lock(val id: String, val code: String? = null) : HaAction
    data class Unlock(val id: String, val code: String? = null) : HaAction
    data class OpenLock(val id: String, val code: String? = null) : HaAction
    data class Arm(val id: String, val mode: HaActions.ArmMode, val code: String? = null) : HaAction
    data class Disarm(val id: String, val code: String? = null) : HaAction

    /** Asks for a code first, then runs [purpose] (`lock`, `unlock`, `open`, `disarm`, `arm:away`…). */
    data class AskCode(val id: String, val purpose: String) : HaAction

    // Vacuum
    data class Vacuum(val id: String, val command: HaActions.VacuumCommand) : HaAction
    data class VacuumSpeed(val id: String, val speed: String) : HaAction

    // Number, select, text
    data class Value(val id: String, val value: Double) : HaAction
    data class Option(val id: String, val option: String) : HaAction
    data class EditText(val id: String) : HaAction

    // Sensors
    data class Insert(val id: String, val format: HaInsertFormat? = null) : HaAction
    data class DefaultInsertFormat(val format: HaInsertFormat) : HaAction

    // Curation
    data class Favourite(val id: String, val on: Boolean) : HaAction
    data class MoveFavourite(val id: String, val by: Int) : HaAction
    data class Hide(val id: String) : HaAction

    // Search and Assist
    data object SearchOpen : HaAction
    data object SearchClose : HaAction
    data object AssistOpen : HaAction
    data object AssistClose : HaAction
    data object AssistSend : HaAction
    data object AssistInsertReply : HaAction
    data object AssistClear : HaAction

    // Camera
    data class Snapshot(val id: String) : HaAction
    data class SendSnapshot(val id: String) : HaAction

    // Connection
    data object Connect : HaAction
    data object SignInFound : HaAction
    data object Retry : HaAction
    data object OpenSettings : HaAction
    data class OpenInHomeAssistant(val id: String?) : HaAction

    /** The Done beside the panel's text box: what Enter would do. */
    data object TextDone : HaAction
    data object TextCancel : HaAction

    data object Undo : HaAction
    data object UndoDismiss : HaAction
}

/** What the Home Assistant panel hands back; one bundle, see `ToolHoldCallbacks.homeAssistant`. */
data class HaCallbacks(
    val state: StateFlow<HaUi> = MutableStateFlow(HaUi()),
    val onAction: (HaAction) -> Unit = {},
)
