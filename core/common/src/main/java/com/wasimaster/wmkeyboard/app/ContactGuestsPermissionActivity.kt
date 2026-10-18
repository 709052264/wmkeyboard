package com.wasimaster.wmkeyboard.app

/**
 * Trampoline for the calendar tool's guest row: READ_CONTACTS, so typing a
 * name offers that person's address. The row re-reads the grant when the
 * keyboard comes back.
 */
class ContactGuestsPermissionActivity : PermissionRequestActivity() {
    override val disclosure = PermissionDisclosures.CONTACT_GUESTS
}
