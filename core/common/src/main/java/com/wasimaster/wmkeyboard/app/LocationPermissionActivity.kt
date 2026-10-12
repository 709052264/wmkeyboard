package com.wasimaster.wmkeyboard.app

/**
 * Trampoline for the Location tool (#350): the disclosure, then Android's
 * precise-or-approximate dialog, on behalf of the IME, which cannot show
 * either. The tool re-reads the grant when the keyboard comes back.
 */
class LocationPermissionActivity : PermissionRequestActivity() {
    override val disclosure = PermissionDisclosures.LOCATION
}
