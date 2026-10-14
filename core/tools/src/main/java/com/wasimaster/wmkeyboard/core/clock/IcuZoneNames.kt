package com.wasimaster.wmkeyboard.core.clock

import android.icu.text.TimeZoneNames
import java.util.Locale

/**
 * Zone names from the ICU data Android ships: "Dhaka", "Bangladesh" in
 * English, "ঢাকা", "বাংলাদেশ" in Bengali. Both calls exist from API 24.
 */
class IcuZoneNames(private val locale: Locale = Locale.getDefault()) : ZoneNames {

    private val names: TimeZoneNames? = runCatching { TimeZoneNames.getInstance(locale) }.getOrNull()

    override fun city(id: String): String? =
        runCatching { names?.getExemplarLocationName(id) }.getOrNull()?.takeIf { it.isNotBlank() }

    override fun canonical(id: String): String =
        runCatching { android.icu.util.TimeZone.getCanonicalID(id) }.getOrNull()?.takeIf { it.isNotEmpty() } ?: id

    override fun region(id: String): String? = runCatching {
        val code = android.icu.util.TimeZone.getRegion(id)
        if (code.isNullOrEmpty() || code == "001") null else Locale("", code).getDisplayCountry(locale)
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
