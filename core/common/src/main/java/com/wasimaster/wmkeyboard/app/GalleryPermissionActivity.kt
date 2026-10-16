package com.wasimaster.wmkeyboard.app

/**
 * Trampoline for the gallery tool's photos and videos (#708): the disclosure,
 * then the system dialog. The tool re-reads the grant when the keyboard comes
 * back, partial ("Select photos") or whole.
 */
class GalleryPermissionActivity : PermissionRequestActivity() {
    override val disclosure = PermissionDisclosures.GALLERY
}
