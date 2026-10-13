package com.wasimaster.wmkeyboard.core.integrity

import com.wasimaster.wmkeyboard.core.debug.DebugLog

/**
 * The one switch every SHA-256 / size check on a downloaded or imported file
 * goes through (Privacy → Verify downloaded files).
 *
 * On by default and meant to stay on. It exists for one emergency: a hosted
 * data file had to change, every installed build still pins the old hash, and
 * the fixed build has not reached the user yet. Turning it off lets those
 * builds take the new file instead of failing the download until they update.
 *
 * Published from the settings the way [com.wasimaster.wmkeyboard.core.netlog.NetLog.enabled]
 * is, so a download manager deep in a feature module reads it without being
 * handed the settings. A process that has not read settings yet keeps the safe
 * default.
 *
 * Not covered, on purpose: the APK installer's signing-certificate pin (an
 * identity check, not a checksum — an update signed by someone else is never
 * installed), the rule that an executable add-on must publish a checksum at
 * all, and the CRCs inside file formats (gzip, zip) that the decoders check.
 */
object IntegrityChecks {
    private const val TAG = "IntegrityChecks"

    @Volatile
    var enabled: Boolean = true

    /**
     * Whether a file whose check came out [matches] may be used. Always true
     * while checks are off; a mismatch let through is logged with [what] so a
     * bug report shows it happened.
     */
    fun accept(matches: Boolean, what: String): Boolean {
        if (matches) return true
        if (enabled) return false
        DebugLog.w(TAG, "checksum mismatch ignored (verification off): $what")
        return true
    }
}
