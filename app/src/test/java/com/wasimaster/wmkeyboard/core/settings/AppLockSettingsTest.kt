package com.wasimaster.wmkeyboard.core.settings

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fingerprint lock's stored shape.
 *
 * Its four preferences ride the generic backup walk rather than being listed
 * anywhere, so what this pins down is that they are treated as ordinary
 * settings: carried to a new phone, not withheld as a credential, and not
 * dropped as install-local state.
 */
class AppLockSettingsTest {

    private val prefs = mutablePreferencesOf(
        booleanPreferencesKey("app_lock_enabled") to true,
        stringSetPreferencesKey("app_lock_targets") to setOf("screen_dictionary", "action_factory_reset"),
        stringPreferencesKey("app_lock_relock") to "AFTER_5_MIN",
        booleanPreferencesKey("app_lock_allow_credential") to false,
        stringPreferencesKey("app_lock_method") to "PIN",
        stringPreferencesKey("app_lock_secret") to "pbkdf2-sha256\$1\$00\$00",
        booleanPreferencesKey("app_lock_biometric_shortcut") to false,
    )

    private fun entries(includeSecrets: Boolean = false) =
        SettingsBackup.decode(
            SettingsBackup.encode(prefs, includeSecrets, appVersion = 1, appVersionName = "1.0"),
        )!!.entries.associate { it.name to it.value }

    @Test
    fun `the defaults object is the shipped shape`() {
        assertEquals(AppLockSettings(), AppLockDefaults)
        assertFalse("the lock ships on", AppLockDefaults.enabled)
        assertTrue("the lock ships with picks already made", AppLockDefaults.lockedTargets.isEmpty())
        assertEquals(AppLockRelock.ON_LEAVE, AppLockDefaults.relock)
        // On by default: it is the only way back after the sensor locks out,
        // and the only thing that works on a phone with no reader.
        assertTrue("the screen-lock fallback ships off", AppLockDefaults.allowDeviceCredential)
        assertEquals(AppLockMethod.BIOMETRIC, AppLockDefaults.method)
        assertEquals(null, AppLockDefaults.secret)
        assertTrue(AppLockDefaults.biometricShortcut)
    }

    @Test
    fun `a secret method with no secret reads as the fingerprint`() {
        // A backup restored without its secrets carries the method and not
        // the hash. The lock must then ask for the fingerprint, not for a PIN
        // nobody can check, and not for nothing at all.
        assertFalse(AppLockSettings(method = AppLockMethod.PIN).usesSecret)
        assertTrue(AppLockSettings(method = AppLockMethod.PIN, secret = "x").usesSecret)
        assertFalse(AppLockSettings(method = AppLockMethod.BIOMETRIC, secret = "x").usesSecret)
    }

    @Test
    fun `the config survives a backup round trip`() {
        val decoded = entries()
        assertEquals(true, decoded["app_lock_enabled"])
        assertEquals(setOf("screen_dictionary", "action_factory_reset"), decoded["app_lock_targets"])
        assertEquals("AFTER_5_MIN", decoded["app_lock_relock"])
        assertEquals(false, decoded["app_lock_allow_credential"])
        assertEquals("PIN", decoded["app_lock_method"])
        assertEquals(false, decoded["app_lock_biometric_shortcut"])
    }

    @Test
    fun `only the stored secret is treated as a credential`() {
        // The rest is a flag, a list of screen names and policy values.
        // Listing them in SECRET_KEYS would drop them from every backup that
        // leaves out the API keys, which is the common case. The PIN hash is
        // the one that must stay behind: a short PIN falls to its hash.
        val secret = SettingsBackup.SECRET_KEYS.filter { it.startsWith("app_lock_") }
        assertEquals("app lock keys marked as credentials", listOf("app_lock_secret"), secret)
        assertEquals(6, entries().keys.count { it.startsWith("app_lock_") })
        assertFalse("app_lock_secret" in entries())
        assertTrue("app_lock_secret" in entries(includeSecrets = true))
    }

    @Test
    fun `the config travels to a new phone`() {
        // The opposite mistake: TRANSIENT_KEYS is for things that describe
        // this install, like a folder URI. These describe what the user wants,
        // so they belong on the new device.
        val transient = SettingsBackup.TRANSIENT_KEYS.filter { it.startsWith("app_lock_") }
        assertEquals("app lock keys dropped from backups", emptyList<String>(), transient)
    }

    @Test
    fun `every relock value round trips through its name`() {
        // The stored form is the enum name, and the reader matches on it. A
        // renamed member would silently reset every user to the default.
        for (policy in AppLockRelock.entries) {
            assertNotNull(
                "${policy.name} does not read back",
                AppLockRelock.entries.find { it.name == policy.name },
            )
        }
    }

    @Test
    fun `an unknown relock name is not a member`() {
        // What the repository's reader falls back on. Restoring a backup from
        // a build with a value this one has never heard of must land on the
        // default rather than throw on the screen that would fix it.
        assertEquals(null, AppLockRelock.entries.find { it.name == "AFTER_2_HOURS" })
    }
}
