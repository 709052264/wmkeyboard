package com.wasimaster.wmkeyboard.core.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The connection details of the destinations added after [ImapConfig]: Box,
 * pCloud, a Synology or QNAP NAS, NFS, MEGA, iCloud Drive and Proton Drive.
 *
 * Serializable rather than written out by hand like the older ones in
 * [BackupConfigs.kt], each stored as one section of its [BackupLocation] by
 * its own name. A field added later decodes to its default from a section
 * written before it, and every secret here is covered by the location list
 * being in [SettingsBackup.SECRET_KEYS] as a whole.
 *
 * Where a service lets the app sign in once and keep a session (MEGA, Proton,
 * pCloud), the session is what is kept and the password is not: a backup that
 * runs at night has nobody to type a 2FA code. iCloud and the NAS logins keep
 * the password, because their sessions do not outlive a day.
 */

/** A folder in the user's Box. The refresh token is [BackupLocation.refreshToken]. */
@Serializable
data class BoxConfig(
    /** Path from the top of All Files, made on first use. */
    val folder: String = DEFAULT_FOLDER,
) {
    companion object {
        const val DEFAULT_FOLDER = "WM Keyboard backups"
    }
}

/** Which of pCloud's two data regions an account lives in. Fixed when the account is made. */
@Serializable
enum class PCloudRegion(val apiHost: String) {
    @SerialName("us")
    US("api.pcloud.com"),

    @SerialName("eu")
    EU("eapi.pcloud.com"),
}

/** A folder in pCloud, through its own HTTP API rather than its WebDAV door. */
@Serializable
data class PCloudConfig(
    val region: PCloudRegion = PCloudRegion.US,
    /** The account's email, for the screen. */
    val email: String = "",
    /** What `userinfo?getauth=1` handed back. Empty means signed out. */
    val auth: String = "",
    val folder: String = DEFAULT_FOLDER,
) {
    val configured: Boolean get() = auth.isNotEmpty()

    companion object {
        const val DEFAULT_FOLDER = "WM Keyboard backups"
    }
}

/**
 * A Synology or QNAP NAS, through the vendor's own file manager API: DSM's
 * File Station, or QTS's. The same fields for both; which one the location is
 * decides how they are read.
 */
@Serializable
data class NasConfig(
    /**
     * `nas.local`, `192.168.1.20:5001` or a whole `https://` address. A bare
     * host gets HTTPS on the vendor's default port: 5001 on DSM, 443 on QTS.
     */
    val server: String = "",
    val user: String = "",
    val password: String = "",
    /**
     * Folder on the NAS, from its top: `/home/Drive/WM Keyboard` is the
     * user's Synology Drive, `/Public/WM Keyboard` a QNAP share.
     */
    val path: String = "",
    /**
     * What lets a login skip the 2FA code: DSM's device token (`did`), or
     * QTS's `qtoken`. Got by signing in once with a code on the screen.
     */
    val deviceToken: String = "",
    /**
     * Accepts the certificate the NAS itself made, which is what almost every
     * NAS on a home network has. The first connection records it in
     * [certPin]; a later one shown a different certificate refuses, the same
     * rule as an SFTP host key.
     */
    val ownCertificate: Boolean = false,
    /** SHA-256 of the accepted certificate, base64. Empty until a connection sees one. */
    val certPin: String = "",
) {
    val configured: Boolean get() = server.isNotEmpty() && user.isNotEmpty() && password.isNotEmpty()
}

/** An NFS export: version 3 over TCP, signed in as a numeric user. */
@Serializable
data class NfsConfig(
    val host: String = "",
    /** The exported directory, as the server names it: `/srv/nfs/backups`. */
    val export: String = "",
    /** Folder inside the export. Empty is the export's top. */
    val path: String = "",
    /**
     * The user and group ids files are written as. NFS without Kerberos takes
     * the client's word for these, so they decide whose files the backups
     * are on the server.
     */
    val uid: Int = DEFAULT_ID,
    val gid: Int = DEFAULT_ID,
    /** 0 asks the server's portmapper; NFS itself is nearly always 2049. */
    val port: Int = 0,
    /** 0 asks the portmapper for the mount service's port. */
    val mountPort: Int = 0,
) {
    val configured: Boolean get() = host.isNotEmpty() && export.isNotEmpty()

    companion object {
        const val DEFAULT_ID = 1000
    }
}

/**
 * A folder in MEGA. A signed-in session, not the password: [sessionId] and
 * [masterKey] are what a login produces, and they keep working until the
 * session is closed from MEGA's own settings.
 */
@Serializable
data class MegaConfig(
    val email: String = "",
    val sessionId: String = "",
    /** The account's master key, base64. Decrypts every file and folder key. */
    val masterKey: String = "",
    val folder: String = DEFAULT_FOLDER,
) {
    val configured: Boolean get() = sessionId.isNotEmpty() && masterKey.isNotEmpty()

    companion object {
        const val DEFAULT_FOLDER = "WM Keyboard backups"
    }
}

/**
 * A folder in iCloud Drive, through the web API iCloud.com itself uses.
 *
 * The password stays, unlike MEGA's and Proton's: Apple's sessions last about
 * a day, and the trust token from the 2FA sign-in only lets a fresh login skip
 * the code. The token lasts about a month, after which the location asks to
 * be signed in to again.
 */
@Serializable
data class ICloudConfig(
    val appleId: String = "",
    val password: String = "",
    /** From the 2FA sign-in. Empty means not signed in yet. */
    val trustToken: String = "",
    /** The cookies the last session left, as one `name=value; ...` string. */
    val cookies: String = "",
    /** Apple's random per-client id, made once. */
    val clientId: String = "",
    /** When [trustToken] was got, so the screen can say a sign-in is due. */
    val trustedAtMs: Long = 0L,
    val folder: String = DEFAULT_FOLDER,
) {
    val configured: Boolean get() = appleId.isNotEmpty() && password.isNotEmpty() && trustToken.isNotEmpty()

    companion object {
        const val DEFAULT_FOLDER = "WM Keyboard backups"
    }
}

/**
 * A folder in Proton Drive. The session and the salted key password, never
 * the account password: the second is what unlocks the account's keys, the
 * same thing Proton's own apps keep.
 */
@Serializable
data class ProtonConfig(
    val user: String = "",
    val uid: String = "",
    val accessToken: String = "",
    val refreshToken: String = "",
    /** The key password, salted and hashed as Proton's apps do, base64. */
    val saltedKeyPass: String = "",
    val folder: String = DEFAULT_FOLDER,
) {
    val configured: Boolean get() = uid.isNotEmpty() && refreshToken.isNotEmpty() && saltedKeyPass.isNotEmpty()

    companion object {
        const val DEFAULT_FOLDER = "WM Keyboard backups"
    }
}
