package com.wasimaster.wmkeyboard.core.providers

import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
import com.wasimaster.wmkeyboard.core.util.PlayServices
import com.wasimaster.wmkeyboard.core.tools.offlinegif.OfflineGifPacks

/**
 * Which build this is, as far as providers go. GitHub is the direct download:
 * neither the Play nor the F-Droid flag. Whether the build may reach the
 * network at all is a separate question, [ProviderEnv.offline], because the
 * no-internet APK is a GitHub build with the permission stripped.
 */
enum class ProviderChannel {
    PLAY, GITHUB, FDROID;

    companion object {
        val current: ProviderChannel
            get() = when {
                BuildConfig.ENABLE_FDROID -> FDROID
                BuildConfig.ENABLE_PLAY_STORE -> PLAY
                else -> GITHUB
            }
    }
}

/** What decides which providers can be listed and asked right now. */
data class ProviderEnv(
    val channel: ProviderChannel,
    /** No internet permission: only providers on the phone can ever answer. */
    val offline: Boolean,
    /** ML Kit translation is compiled into this build (the full flavour). */
    val onDeviceTranslate: Boolean,
    /** At least one offline GIF pack is imported. */
    val offlineGifPacks: Boolean,
    /** The Google Home APIs can run: the build carries them and Play services is on the phone. */
    val googleHome: Boolean = false,
)

/**
 * What lower modules cannot see for themselves. ML Kit lives in
 * `:core:intelligence`, above this module, so the application installs the
 * answer at start-up, the way `BackupClients.install` hands down its client ids.
 */
object ProviderEnvironment {

    @Volatile
    var onDeviceTranslate: Boolean = false
        private set

    @Volatile
    var googleHome: Boolean = false
        private set

    fun install(onDeviceTranslate: Boolean, googleHome: Boolean = false) {
        this.onDeviceTranslate = onDeviceTranslate
        this.googleHome = googleHome
    }

    fun current(): ProviderEnv = ProviderEnv(
        channel = ProviderChannel.current,
        offline = !InternetPermission.granted,
        onDeviceTranslate = onDeviceTranslate,
        offlineGifPacks = OfflineGifPacks.hasPacks,
        // The build carries the Home APIs, and the phone has the Play services they run through.
        googleHome = googleHome && PlayServices.available,
    )
}

/**
 * Which provider types a build can add. Every build lists every kind it can
 * run, in the same order ([ProviderTypes.all]), with nothing put first: the
 * no-internet build simply cannot run what needs the network.
 */
object ProviderCatalog {

    /** Whether this build can run [type] at all. */
    fun runnable(type: ProviderType, env: ProviderEnv): Boolean = when {
        env.offline && !type.onDevice -> false
        type === ProviderTypes.ML_KIT && !env.onDeviceTranslate -> false
        type === ProviderTypes.GOOGLE_HOME && !env.googleHome -> false
        else -> true
    }

    /**
     * The types the add sheet offers for [tool]: every runnable one, minus a
     * type already in [chain] that [tool] cannot list twice
     * ([ProviderTool.allowsAnother]).
     */
    fun addable(tool: ProviderTool, chain: ProviderChain, env: ProviderEnv): List<ProviderType> =
        ProviderTypes.serving(tool)
            .filter { runnable(it, env) }
            .filter { tool.allowsAnother(it) || chain.entries.none { entry -> entry.typeId == it.id } }
}
