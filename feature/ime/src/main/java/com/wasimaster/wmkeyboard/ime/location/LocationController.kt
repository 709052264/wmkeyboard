package com.wasimaster.wmkeyboard.ime.location

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.StringRes
import com.wasimaster.wmkeyboard.app.LocationPermissionActivity
import com.wasimaster.wmkeyboard.core.location.GeoPoint
import com.wasimaster.wmkeyboard.core.location.LocationAccess
import com.wasimaster.wmkeyboard.core.location.LocationFix
import com.wasimaster.wmkeyboard.core.location.LocationFixer
import com.wasimaster.wmkeyboard.core.location.LocationFormat
import com.wasimaster.wmkeyboard.core.location.LocationFormatter
import com.wasimaster.wmkeyboard.core.location.LocationGrant
import com.wasimaster.wmkeyboard.core.location.LocationPrecision
import com.wasimaster.wmkeyboard.core.location.LocationShare
import com.wasimaster.wmkeyboard.core.location.ReverseGeocoder
import com.wasimaster.wmkeyboard.core.location.SavedPlace
import com.wasimaster.wmkeyboard.core.location.SavedPlaces
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.LocationSettings
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import com.wasimaster.wmkeyboard.ime.R
import com.wasimaster.wmkeyboard.ime.ui.LocationCallbacks
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** The two halves of the Location panel. */
enum class LocationTab { HERE, PLACES }

enum class LocationStatus { IDLE, LOCATING, READY, FAILED }

enum class AddressState { NONE, LOOKING, DONE, FAILED, OFF }

/**
 * What the Location panel shows (#350). Held by [LocationController] rather
 * than [com.wasimaster.wmkeyboard.ime.KeyboardUiState]: nothing else on the
 * keyboard reads it, and the panel collects it straight from the controller
 * the way the KDE Connect panel reads its hub.
 */
data class LocationUi(
    val tab: LocationTab = LocationTab.HERE,
    /** False on a build without the location permission: places only. */
    val declared: Boolean = true,
    val grant: LocationGrant = LocationGrant.NONE,
    val servicesOn: Boolean = true,
    val status: LocationStatus = LocationStatus.IDLE,
    val fix: LocationFix? = null,
    /** The address for [addressPoint] at [addressPrecision]. */
    val address: String? = null,
    val addressPoint: GeoPoint? = null,
    val addressPrecision: LocationPrecision? = null,
    val addressState: AddressState = AddressState.NONE,
    /** Picked in the panel; null reads the settings, and so does a fresh visit. */
    val precision: LocationPrecision? = null,
    val format: LocationFormat? = null,
    /** The saved place opened to its actions. */
    val openPlace: Long? = null,
    /** The panel's Photo button: the camera opens with its location stamp on, this once. */
    val stampOnce: Boolean = false,
) {
    /** The address that belongs to the reading on screen at [precision], if it is in. */
    fun addressFor(precision: LocationPrecision): String? {
        val fix = fix ?: return null
        return address?.takeIf { addressPrecision == precision && addressPoint == precision.apply(fix.point) }
    }
}

/** The precision and format the panel is using right now: its own pick, else the settings. */
fun LocationUi.precisionOr(settings: LocationSettings): LocationPrecision = precision ?: settings.precision

fun LocationUi.formatOr(settings: LocationSettings): LocationFormat = format ?: settings.format

/** The share the panel would insert for the current reading; null without one. */
fun LocationUi.currentShare(settings: LocationSettings, timeText: String): LocationShare? {
    val fix = fix ?: return null
    val precision = precisionOr(settings)
    return LocationShare(
        point = fix.point,
        precision = precision,
        address = addressFor(precision),
        accuracyM = fix.accuracyM,
        timeText = timeText,
    )
}

fun SavedPlace.share(precision: LocationPrecision): LocationShare = LocationShare(
    point = point,
    precision = precision,
    address = address.ifBlank { null },
    placeName = name.ifBlank { null },
)

/**
 * The Location tool's brain (#350): permission, one reading, the address,
 * inserting, and the saved places.
 *
 * Reads the position only from [locate], [useForTools], or a panel opened
 * while Android already allows it and the user left "Locate when the tool
 * opens" on. Each read is one `getCurrentLocation` per provider and nothing
 * stays registered. The reading lives in memory for [KEEP_MS] so reopening
 * the panel a minute later does not ask again, then it is dropped.
 */
internal class LocationController(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: () -> KeyboardSettings,
    private val repository: SettingsRepository,
    private val insertText: (String) -> Unit,
    private val haptic: () -> Unit,
    private val panelOpen: () -> Boolean,
    private val openToolSettings: () -> Unit,
    /** The weather location changed under the weather panel; refetch if it is open. */
    private val onWeatherLocationChanged: (latitude: Float, longitude: Float) -> Unit,
    /** Opens the camera tool's panel. */
    private val openCamera: () -> Unit,
) {
    private val context = context.applicationContext
    private val fixer by lazy { LocationFixer(this.context) }

    private val _state = MutableStateFlow(LocationUi())
    val state: StateFlow<LocationUi> = _state

    private var fixJob: Job? = null
    private var addressJob: Job? = null
    private var toolsJob: Job? = null

    private enum class Pending { NONE, LOCATE, TOOLS, CAMERA }

    private var pending = Pending.NONE
    private var pendingSince = 0L

    val callbacks = LocationCallbacks(
        state = state,
        onLocate = ::locate,
        onTab = ::setTab,
        onPrecision = ::cyclePrecision,
        onFormat = ::selectFormat,
        onFormatDefault = ::setDefaultFormat,
        onInsert = ::insertCurrent,
        onSave = ::savePlace,
        onOpenMaps = ::openInMaps,
        onUseForWeather = ::useForWeather,
        onPlace = ::insertPlace,
        onPlaceMore = ::togglePlace,
        onPlaceDelete = ::deletePlace,
        onAllowPrecise = ::allowPrecise,
        onServicesSettings = ::openServicesSettings,
        onUseMyLocationForTools = ::useForTools,
        onOpenSettings = openToolSettings,
        onPhoto = ::photo,
        onLocateForCamera = ::locateForCamera,
        onCaption = ::insertCaption,
        onStampConsumed = { _state.update { it.copy(stampOnce = false) } },
    )

    private val locationSettings: LocationSettings get() = settings().location

    // ---- lifecycle hooks the service calls ----

    fun onPanelOpen() {
        refreshAccess()
        val now = System.currentTimeMillis()
        _state.update { ui ->
            val expired = ui.fix != null && now - ui.fix.timeMillis > KEEP_MS
            ui.copy(
                precision = null,
                format = null,
                openPlace = null,
                fix = if (expired) null else ui.fix,
                address = if (expired) null else ui.address,
                addressPoint = if (expired) null else ui.addressPoint,
                addressState = if (expired) AddressState.NONE else ui.addressState,
                status = if (expired) LocationStatus.IDLE else ui.status,
            )
        }
        val ui = _state.value
        val stale = ui.fix == null || now - ui.fix.timeMillis > STALE_MS
        val wanted = pending == Pending.LOCATE || (locationSettings.locateOnOpen && stale)
        if (ui.declared && ui.grant != LocationGrant.NONE && ui.servicesOn && wanted) {
            pending = Pending.NONE
            startFix()
        } else {
            ensureAddress()
        }
    }

    fun onPanelClose() {
        fixJob?.cancel()
        _state.update { ui ->
            if (ui.status == LocationStatus.LOCATING) {
                ui.copy(status = if (ui.fix != null) LocationStatus.READY else LocationStatus.IDLE)
            } else {
                ui
            }
        }
    }

    /**
     * The keyboard came back, which is how a permission answer arrives: the
     * trampoline finished and the app refocused. Carries on with whatever the
     * user had pressed before Android asked.
     */
    fun onKeyboardShown() {
        if (pending == Pending.NONE) return
        refreshAccess()
        val grant = _state.value.grant
        if (grant == LocationGrant.NONE) {
            // The field may restart before the dialog is even answered; give
            // the user time before forgetting what they pressed.
            if (SystemClock.elapsedRealtime() - pendingSince > PENDING_MS) pending = Pending.NONE
            return
        }
        val what = pending
        pending = Pending.NONE
        when (what) {
            Pending.LOCATE -> if (panelOpen()) startFix()
            Pending.TOOLS -> useForTools()
            Pending.CAMERA -> startFix()
            Pending.NONE -> Unit
        }
    }

    private fun refreshAccess() {
        val declared = LocationAccess.declared(context)
        _state.update {
            it.copy(
                declared = declared,
                grant = if (declared) LocationAccess.grant(context) else LocationGrant.NONE,
                servicesOn = LocationAccess.servicesOn(context),
            )
        }
    }

    private fun requestPermission(next: Pending) {
        pending = next
        pendingSince = SystemClock.elapsedRealtime()
        runCatching {
            context.startActivity(
                Intent(context, LocationPermissionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // ---- reading the position ----

    fun locate() {
        haptic()
        refreshAccess()
        val ui = _state.value
        when {
            !ui.declared -> Unit
            ui.grant == LocationGrant.NONE -> requestPermission(Pending.LOCATE)
            // The panel shows "Location is off" with a button; nothing to read.
            !ui.servicesOn -> Unit
            else -> startFix()
        }
    }

    /**
     * The camera's location stamp or caption was turned on: make sure a
     * reading is coming, asking Android first if it has to. Unlike [locate]
     * this works with the Location panel closed, which it is.
     */
    private fun locateForCamera() {
        refreshAccess()
        val ui = _state.value
        when {
            !ui.declared -> toast(R.string.ime_location_no_permission_build_toast)
            ui.grant == LocationGrant.NONE -> requestPermission(Pending.CAMERA)
            !ui.servicesOn -> openServicesSettings()
            ui.status == LocationStatus.LOCATING -> Unit
            ui.fix != null && ui.fix.isRecent() -> ensureAddress()
            else -> startFix()
        }
    }

    /** The panel's Photo button: the camera, with the stamp on for this photo. */
    private fun photo() {
        haptic()
        _state.update { it.copy(stampOnce = true) }
        openCamera()
    }

    /**
     * The camera's caption: the current reading in the default format, typed
     * before the photo goes so apps that take the box as a caption use it.
     * Nothing when there is no reading yet.
     */
    private fun insertCaption() {
        val ui = _state.value
        val fix = ui.fix ?: return
        val share = ui.currentShare(locationSettings, timeText(fix)) ?: return
        insertText(render(share, locationSettings.format))
    }

    private fun allowPrecise() {
        haptic()
        requestPermission(Pending.LOCATE)
    }

    private fun startFix() {
        fixJob?.cancel()
        // Android's own cache first: free, and on screen at once if it is recent.
        val quick = runCatching { fixer.lastKnown(QUICK_MS) }.getOrNull()
        _state.update { ui ->
            val shown = if (quick != null && quick.betterThan(ui.fix.takeIf { it?.isRecent() == true })) quick else ui.fix
            ui.copy(status = LocationStatus.LOCATING, fix = shown)
        }
        fixJob = scope.launch {
            var answered = false
            try {
                fixer.fixes().collect { fix ->
                    answered = true
                    _state.update { it.copy(fix = fix) }
                }
            } catch (e: CancellationException) {
                // The panel closed; onPanelClose already settled the status.
                throw e
            } catch (_: Exception) {
                // A provider that threw is a reading that did not come.
            }
            _state.update { ui ->
                ui.copy(
                    status = when {
                        answered || (ui.fix != null && ui.fix.isRecent()) -> LocationStatus.READY
                        else -> LocationStatus.FAILED
                    },
                )
            }
            ensureAddress()
        }
    }

    private fun LocationFix.isRecent(): Boolean = System.currentTimeMillis() - timeMillis <= STALE_MS

    // ---- the address ----

    private fun ensureAddress(precisionOverride: LocationPrecision? = null) {
        val ui = _state.value
        val fix = ui.fix ?: return
        if (!locationSettings.lookupAddress) {
            _state.update { it.copy(addressState = AddressState.OFF) }
            return
        }
        val precision = precisionOverride ?: ui.precisionOr(locationSettings)
        val point = precision.apply(fix.point)
        if (ui.addressFor(precision) != null) return
        val language = language()
        ReverseGeocoder.cached(point, precision, language)?.let { cached ->
            _state.update {
                it.copy(address = cached, addressPoint = point, addressPrecision = precision, addressState = AddressState.DONE)
            }
            return
        }
        addressJob?.cancel()
        _state.update { it.copy(addressState = AddressState.LOOKING) }
        addressJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ReverseGeocoder.lookup(point, precision, language) }
            }
            _state.update { now ->
                // A newer reading or another precision asked since; this answer is for neither.
                val current = now.fix?.let { now.precisionOr(locationSettings).apply(it.point) }
                if (current != point) return@update now
                val address = result.getOrNull()
                now.copy(
                    address = address,
                    addressPoint = point,
                    addressPrecision = precision,
                    addressState = if (address != null) AddressState.DONE else AddressState.FAILED,
                )
            }
        }
    }

    private fun language(): String =
        context.resources.configuration.locales[0]?.language?.ifBlank { null } ?: "en"

    // ---- panel controls ----

    private fun setTab(tab: LocationTab) {
        haptic()
        _state.update { it.copy(tab = tab, openPlace = null) }
    }

    private fun cyclePrecision() {
        haptic()
        val next = _state.value.precisionOr(locationSettings).next()
        _state.update { it.copy(precision = next) }
        scope.launch { repository.setLocationPrecision(next) }
        ensureAddress(next)
    }

    private fun selectFormat(format: LocationFormat) {
        haptic()
        _state.update { it.copy(format = format) }
    }

    private fun setDefaultFormat(format: LocationFormat) {
        haptic()
        _state.update { it.copy(format = format) }
        scope.launch { repository.setLocationFormat(format) }
        toast(R.string.ime_location_format_default_toast, context.getString(format.labelRes))
    }

    private fun timeText(fix: LocationFix): String =
        android.text.format.DateFormat.getTimeFormat(context).format(Date(fix.timeMillis))

    private fun insertCurrent() {
        haptic()
        val fix = _state.value.fix ?: return
        scope.launch {
            // An address on its way is worth a moment's wait for the formats that print it.
            val format = _state.value.formatOr(locationSettings)
            if (format != LocationFormat.LINK && format != LocationFormat.COORDINATES &&
                format != LocationFormat.DMS && format != LocationFormat.PLUS_CODE
            ) {
                withTimeoutOrNull(ADDRESS_WAIT_MS) { addressJob?.join() }
            }
            val ui = _state.value
            val share = ui.currentShare(locationSettings, timeText(ui.fix ?: fix)) ?: return@launch
            insertText(render(share, ui.formatOr(locationSettings)))
        }
    }

    private fun render(share: LocationShare, format: LocationFormat): String {
        val s = locationSettings
        return LocationFormatter.format(share, format, s.mapLink, s.template)
    }

    private fun insertPlace(id: Long) {
        haptic()
        val place = locationSettings.places.firstOrNull { it.id == id } ?: return
        val ui = _state.value
        insertText(render(place.share(ui.precisionOr(locationSettings)), ui.formatOr(locationSettings)))
    }

    private fun togglePlace(id: Long) {
        haptic()
        _state.update { it.copy(openPlace = if (it.openPlace == id) null else id) }
    }

    private fun savePlace() {
        haptic()
        val ui = _state.value
        val fix = ui.fix ?: return
        val address = ui.addressFor(LocationPrecision.EXACT) ?: ui.address.takeIf { ui.addressState == AddressState.DONE }
        scope.launch {
            var saved: SavedPlace? = null
            repository.updateLocationPlaces { places ->
                val name = address?.substringBefore(',')?.trim()?.ifBlank { null }
                    ?: context.getString(R.string.ime_location_place_default_name, places.size + 1)
                val place = SavedPlace(SavedPlaces.newId(places), name, fix.point, address.orEmpty())
                saved = place
                (places + place).takeLast(SavedPlaces.MAX)
            }
            saved?.let { toast(R.string.ime_location_saved_toast, it.name) }
        }
    }

    private fun deletePlace(id: Long) {
        haptic()
        val place = locationSettings.places.firstOrNull { it.id == id } ?: return
        _state.update { it.copy(openPlace = null) }
        scope.launch {
            repository.updateLocationPlaces { places -> places.filterNot { it.id == id } }
            toast(R.string.ime_location_place_deleted_toast, place.name)
        }
    }

    private fun openInMaps(point: GeoPoint, label: String?) {
        haptic()
        val lat = point.latitude
        val lon = point.longitude
        val query = "$lat,$lon" + (label?.takeIf { it.isNotBlank() }?.let { "(" + Uri.encode(it) + ")" } ?: "")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$query"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            toast(R.string.ime_location_no_maps_toast)
        }
    }

    private fun openServicesSettings() {
        haptic()
        runCatching { context.startActivity(LocationAccess.servicesSettingsIntent()) }
    }

    /**
     * Makes [point] the place the weather tool forecasts for and the compass
     * points qibla from. Named after its city when the address lookup is on.
     */
    private fun useForWeather(point: GeoPoint, name: String?) {
        haptic()
        scope.launch { saveForTools(point, name) }
    }

    private suspend fun saveForTools(point: GeoPoint, name: String?) {
        val label = name?.ifBlank { null } ?: if (locationSettings.lookupAddress) {
            val city = LocationPrecision.CITY
            withContext(Dispatchers.IO) {
                runCatching { ReverseGeocoder.lookup(city.apply(point), city, language()) }.getOrNull()
            }?.substringBefore(',')?.trim()
        } else {
            null
        }
        val latitude = point.latitude.toFloat()
        val longitude = point.longitude.toFloat()
        repository.setWeatherLocation(latitude, longitude, label.orEmpty())
        toast(R.string.ime_location_weather_saved_toast)
        onWeatherLocationChanged(latitude, longitude)
    }

    /**
     * "Use my location" on the weather and compass panels: one reading, as
     * coarse as a forecast needs, stored as the weather location.
     */
    fun useForTools() {
        haptic()
        refreshAccess()
        val ui = _state.value
        when {
            !ui.declared -> {
                toast(R.string.ime_location_no_permission_build_toast)
                return
            }
            ui.grant == LocationGrant.NONE -> {
                requestPermission(Pending.TOOLS)
                return
            }
            !ui.servicesOn -> {
                openServicesSettings()
                return
            }
        }
        toolsJob?.cancel()
        toolsJob = scope.launch {
            toast(R.string.ime_location_tools_finding_toast)
            val fix = runCatching { fixer.lastKnown(TOOLS_MAX_AGE_MS) }.getOrNull()
                ?: try {
                    fixer.fixes(goodEnoughM = TOOLS_GOOD_ENOUGH_M).lastOrNull()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            if (fix == null) {
                toast(R.string.ime_location_tools_failed_toast)
            } else {
                saveForTools(fix.point, null)
            }
        }
    }

    private fun toast(@StringRes res: Int, vararg args: Any) {
        Toast.makeText(context, context.getString(res, *args), Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** A reading this young is shown as current, and the panel does not ask again. */
        const val STALE_MS = 2 * 60_000L

        /** Past this the reading is dropped from memory altogether. */
        const val KEEP_MS = 15 * 60_000L

        /** Android's cached reading is used straight away when it is this fresh. */
        const val QUICK_MS = 60_000L

        /** How long Insert waits for an address already on its way. */
        const val ADDRESS_WAIT_MS = 3_000L

        /** How long a permission request waits for its answer. */
        const val PENDING_MS = 2 * 60_000L

        /** A forecast and a qibla bearing do not need a fresh fix. */
        const val TOOLS_MAX_AGE_MS = 30 * 60_000L
        const val TOOLS_GOOD_ENOUGH_M = 500f
    }
}
