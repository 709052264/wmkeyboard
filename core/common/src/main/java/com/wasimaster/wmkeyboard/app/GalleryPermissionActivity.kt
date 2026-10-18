package com.wasimaster.wmkeyboard.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build

/**
 * Trampoline for the gallery tool's photos and videos (#708): the disclosure,
 * then the system dialog. The tool re-reads the grant when the keyboard comes
 * back, partial ("Select photos") or whole.
 */
class GalleryPermissionActivity : PermissionRequestActivity() {
    override val disclosure = PermissionDisclosures.GALLERY

    /**
     * Photos alone is not done: the screenshots-in-clipboard grant asks for
     * them without videos, and a phone that granted that would otherwise
     * never be asked for the videos, which then never showed in the gallery.
     */
    override fun alreadyGranted(disclosure: PermissionDisclosure): Boolean {
        fun has(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
        if (!has(disclosure.permission)) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || has(Manifest.permission.READ_MEDIA_VIDEO)
    }
}
