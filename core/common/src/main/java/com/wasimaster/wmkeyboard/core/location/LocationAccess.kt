package com.wasimaster.wmkeyboard.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/** How much of the phone's position Android lets this app read. */
enum class LocationGrant {
    NONE,

    /** "Approximate" on Android 12 and later: a point snapped to a grid of a few kilometres. */
    APPROXIMATE,
    PRECISE,
}

/**
 * The questions the Location tool asks Android before it reads a position (#350).
 *
 * Nothing here reads one: that is [LocationFixer], and only after the user
 * pressed a button.
 */
object LocationAccess {

    @Volatile
    private var declaredCache: Boolean? = null

    /**
     * Whether this build asks for location at all. A build made with
     * `-Pwmkb.noLocation=true` strips both permissions from the manifest, and
     * then the tool keeps its saved places and hides "Locate me". Read from the
     * installed package, so a fork that removes the lines some other way is
     * covered too.
     */
    fun declared(context: Context): Boolean = declaredCache ?: run {
        val requested = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
                .requestedPermissions
        }.getOrNull().orEmpty()
        (Manifest.permission.ACCESS_COARSE_LOCATION in requested).also { declaredCache = it }
    }

    fun grant(context: Context): LocationGrant {
        fun has(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return when {
            has(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationGrant.PRECISE
            has(Manifest.permission.ACCESS_COARSE_LOCATION) -> LocationGrant.APPROXIMATE
            else -> LocationGrant.NONE
        }
    }

    /** The system-wide location switch in quick settings. */
    fun servicesOn(context: Context): Boolean {
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return LocationManagerCompat.isLocationEnabled(manager)
    }

    /** Android's own location settings, where the switch lives. */
    fun servicesSettingsIntent(): Intent =
        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * Reads the phone's position once, on request.
 *
 * Only the platform's [LocationManager]: no Play services, so it behaves the
 * same on a de-Googled phone. It asks every provider that is switched on at
 * once (the AOSP fused provider from Android 12, GPS, and the network one) and
 * reports each better answer as it lands, so a coarse network fix can be on
 * screen in a second while GPS is still searching. Nothing keeps listening
 * afterwards: every request is a single `getCurrentLocation`, cancelled with
 * the flow.
 */
class LocationFixer(context: Context) {

    private val context = context.applicationContext
    private val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /** Providers worth asking right now, best first. */
    private fun providers(): List<String> {
        val lm = manager ?: return emptyList()
        return listOf(FUSED, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { name ->
            runCatching { LocationManagerCompat.hasProvider(lm, name) && lm.isProviderEnabled(name) }
                .getOrDefault(false)
        }
    }

    /**
     * The freshest reading Android already holds, if it is younger than
     * [maxAgeMs]. Costs nothing and turns on no radio.
     */
    @SuppressLint("MissingPermission")
    fun lastKnown(maxAgeMs: Long, now: Long = System.currentTimeMillis()): LocationFix? {
        val lm = manager ?: return null
        if (LocationAccess.grant(context) == LocationGrant.NONE) return null
        return providers()
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull()?.toFix() }
            .filter { now - it.timeMillis in 0..maxAgeMs }
            .fold(null as LocationFix?) { best, fix -> if (fix.betterThan(best)) fix else best }
    }

    /**
     * Each better reading as it arrives. Completes once one is within
     * [goodEnoughM], once every provider has answered, or after [timeoutMs],
     * whichever comes first. Emits nothing when there is no permission, no
     * provider, or no answer in time.
     */
    @SuppressLint("MissingPermission")
    fun fixes(goodEnoughM: Float = GOOD_ENOUGH_M, timeoutMs: Long = TIMEOUT_MS): Flow<LocationFix> = callbackFlow {
        val lm = manager
        val asked = providers()
        if (lm == null || asked.isEmpty() || LocationAccess.grant(context) == LocationGrant.NONE) {
            close()
            return@callbackFlow
        }
        val signal = CancellationSignal()
        var best: LocationFix? = null
        var waiting = asked.size
        // Every callback lands on the main thread, so the two counters above
        // are only ever touched from one thread.
        val executor = ContextCompat.getMainExecutor(context)
        for (provider in asked) {
            val started = runCatching {
                LocationManagerCompat.getCurrentLocation(lm, provider, signal, executor) { location: Location? ->
                    waiting--
                    val fix = location?.toFix()
                    if (fix != null && fix.betterThan(best)) {
                        best = fix
                        trySend(fix)
                        if ((fix.accuracyM ?: Float.MAX_VALUE) <= goodEnoughM) close()
                    }
                    if (waiting <= 0) close()
                }
            }
            // A provider that refused (a SecurityException after a revoke) is
            // simply one answer fewer.
            if (started.isFailure) waiting--
        }
        if (waiting <= 0) close()
        val timeout = launch {
            delay(timeoutMs)
            close()
        }
        awaitClose {
            signal.cancel()
            timeout.cancel()
        }
    }

    private fun Location.toFix(): LocationFix? {
        val point = GeoPoint(latitude, longitude)
        if (!point.isValid) return null
        return LocationFix(
            point = point,
            accuracyM = if (hasAccuracy()) accuracy else null,
            timeMillis = time,
            provider = provider.orEmpty(),
        )
    }

    companion object {
        /** `LocationManager.FUSED_PROVIDER`, API 31; spelled out so older SDK levels compile the same list. */
        private const val FUSED = "fused"

        /** Stop as soon as a reading is this tight: a building, not a block. */
        const val GOOD_ENOUGH_M = 25f

        /** GPS from cold can take a minute; past this the best answer so far is the answer. */
        const val TIMEOUT_MS = 20_000L
    }
}
