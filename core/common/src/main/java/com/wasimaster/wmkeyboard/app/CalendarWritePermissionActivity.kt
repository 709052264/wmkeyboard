package com.wasimaster.wmkeyboard.app

/**
 * Trampoline for the calendar tool's Save: the disclosure, then the system
 * dialog for WRITE_CALENDAR (with READ_CALENDAR alongside), on behalf of the
 * IME. The tool saves the waiting event when the keyboard comes back with
 * the grant.
 */
class CalendarWritePermissionActivity : PermissionRequestActivity() {
    override val disclosure = PermissionDisclosures.CALENDAR_WRITE

    override fun alreadyGranted(disclosure: PermissionDisclosure): Boolean =
        disclosure.requested.all { checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
}
