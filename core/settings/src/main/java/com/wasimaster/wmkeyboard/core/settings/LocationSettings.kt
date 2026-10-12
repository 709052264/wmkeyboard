package com.wasimaster.wmkeyboard.core.settings

import com.wasimaster.wmkeyboard.core.location.LocationFormat
import com.wasimaster.wmkeyboard.core.location.LocationFormatter
import com.wasimaster.wmkeyboard.core.location.LocationPrecision
import com.wasimaster.wmkeyboard.core.location.MapLink
import com.wasimaster.wmkeyboard.core.location.SavedPlace

/**
 * The Location tool's settings (#350), one nested bag for the reason
 * [NotesSettings] gives. Each field persists under its own `location_*`
 * DataStore key through the matching setter; the saved places are one JSON
 * string, so they ride along with backups and sync like any other preference.
 */
data class LocationSettings(
    /** What Insert types when no other format chip was picked. */
    val format: LocationFormat = LocationFormat.MESSAGE,
    /** Which map a link opens. */
    val mapLink: MapLink = MapLink.GOOGLE,
    /** How much of the position a share gives away. */
    val precision: LocationPrecision = LocationPrecision.EXACT,
    /**
     * Ask the address server for a short address. Off, nothing about the
     * position leaves the phone until the user sends the text themselves.
     */
    val lookupAddress: Boolean = true,
    /**
     * Read the position as soon as the panel opens, when Android already
     * allows it. Off, the panel waits for Locate me every time.
     */
    val locateOnOpen: Boolean = true,
    /** The Custom format's template; see [LocationFormatter.TOKENS]. */
    val template: String = LocationFormatter.DEFAULT_TEMPLATE,
    val places: List<SavedPlace> = emptyList(),
)
