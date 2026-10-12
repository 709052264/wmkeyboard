package com.wasimaster.wmkeyboard.core.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A place on the globe in WGS 84 degrees, the system every map link, Plus code
 * and `geo:` URI speaks.
 *
 * Doubles, not the `Float` the weather tool stores: a float carries about seven
 * significant digits, which is a metre or so at the equator. Fine for a
 * forecast, not for "I am at this gate".
 */
data class GeoPoint(val latitude: Double, val longitude: Double) {

    /** Inside the ranges a map would accept. */
    val isValid: Boolean
        get() = latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
            !latitude.isNaN() && !longitude.isNaN()

    /** Great-circle distance in metres (haversine). */
    fun distanceTo(other: GeoPoint): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(other.longitude - longitude)
        val a = sin(dLat / 2) * sin(dLat / 2) + cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing towards [other], degrees clockwise from true north, 0 until 360. */
    fun bearingTo(other: GeoPoint): Double {
        val lat1 = Math.toRadians(latitude)
        val lat2 = Math.toRadians(other.latitude)
        val dLon = Math.toRadians(other.longitude - longitude)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    companion object {
        /** Mean Earth radius (IUGG), metres. */
        const val EARTH_RADIUS_M = 6_371_008.8
    }
}

/**
 * One reading from the phone's location providers.
 *
 * [accuracyM] is the radius Android reports for 68 % confidence, or null when
 * the provider gave none. [timeMillis] is wall-clock time of the reading, which
 * can be older than the request: Android hands back a cached fix when it has a
 * fresh enough one.
 */
data class LocationFix(
    val point: GeoPoint,
    val accuracyM: Float?,
    val timeMillis: Long,
    val provider: String,
) {
    /** True when this reading is a better answer than [other]: tighter, or as tight and newer. */
    fun betterThan(other: LocationFix?): Boolean {
        if (other == null) return true
        val mine = accuracyM ?: Float.MAX_VALUE
        val theirs = other.accuracyM ?: Float.MAX_VALUE
        return mine < theirs || (mine == theirs && timeMillis > other.timeMillis)
    }
}
