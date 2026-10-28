package com.wasimaster.wmkeyboard.core.settings.sink

/**
 * The OAuth client ids, and the token exchangers built from them.
 *
 * Dropbox, Microsoft and Box all require an app registered in their console, and
 * the id that produces is a build-time fact rather than a user setting. It is
 * supplied the same way every other key in this project is — `local.properties`
 * or an environment variable, read into `BuildConfig` — and `:app` hands it
 * here at startup, because a library module cannot see `:app`'s `BuildConfig`.
 *
 * A build with no id compiled in simply has no Dropbox or OneDrive
 * destination. That is the same shape as the Google Drive seam, and it means a
 * fork can be built without asking anyone for credentials.
 *
 * These are **not secrets**. The sign-in uses PKCE precisely so that no client
 * secret has to ship in an app that anybody can unpack. What they identify is
 * the app, not the user.
 */
object BackupClients {

    @Volatile
    var dropboxClientId: String = ""

    @Volatile
    var oneDriveClientId: String = ""

    @Volatile
    var boxClientId: String = ""

    /**
     * Box is the one that needs a secret too: its token endpoint refuses a
     * client without one, PKCE or not. It ships in the APK like the id, so
     * it identifies the app and protects nothing; Box's own docs accept that
     * for apps on a device.
     */
    @Volatile
    var boxClientSecret: String = ""

    /** Set once by `:app` from its own `BuildConfig`. */
    fun install(dropbox: String, oneDrive: String, box: String = "", boxSecret: String = "") {
        dropboxClientId = dropbox
        oneDriveClientId = oneDrive
        boxClientId = box
        boxClientSecret = boxSecret
    }

    val dropboxAvailable: Boolean get() = dropboxClientId.isNotEmpty()

    val oneDriveAvailable: Boolean get() = oneDriveClientId.isNotEmpty()

    val boxAvailable: Boolean get() = boxClientId.isNotEmpty() && boxClientSecret.isNotEmpty()

    private val dropboxTokens: OAuthTokens by lazy {
        OAuthTokens(tokenUrl = DropboxSink.TOKEN_URL, clientId = dropboxClientId)
    }

    private val oneDriveTokens: OAuthTokens by lazy {
        OAuthTokens(
            tokenUrl = OneDriveSink.TOKEN_URL,
            clientId = oneDriveClientId,
            // Microsoft wants the scope repeated on a refresh, unlike Dropbox.
            extraParams = mapOf("scope" to OneDriveSink.SCOPE),
        )
    }

    fun dropbox(): OAuthTokens? = if (dropboxAvailable) dropboxTokens else null

    fun oneDrive(): OAuthTokens? = if (oneDriveAvailable) oneDriveTokens else null

    private val boxTokens: OAuthTokens by lazy {
        OAuthTokens(
            tokenUrl = BoxSink.TOKEN_URL,
            clientId = boxClientId,
            extraParams = mapOf("client_secret" to boxClientSecret),
        )
    }

    fun box(): OAuthTokens? = if (boxAvailable) boxTokens else null
}
