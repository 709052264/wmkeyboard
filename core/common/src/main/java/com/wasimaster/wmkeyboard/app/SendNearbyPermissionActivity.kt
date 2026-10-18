package com.wasimaster.wmkeyboard.app

/**
 * Trampoline for the Send files tool's direct link (#729): the disclosure,
 * then the system dialog for Nearby devices (Android 13+) or location
 * (before). The tool re-reads the grant when the keyboard comes back.
 */
class SendNearbyPermissionActivity : PermissionRequestActivity() {
    override val disclosure = PermissionDisclosures.NEARBY_WIFI
}
