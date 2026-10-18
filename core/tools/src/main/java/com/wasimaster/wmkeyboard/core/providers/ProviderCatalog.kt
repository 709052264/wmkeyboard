package com.wasimaster.wmkeyboard.core.providers

import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.netlog.InternetPermission
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

    fun install(onDeviceTranslate: Boolean) {
        this.onDeviceTranslate = onDeviceTranslate
    }

    fun current(): ProviderEnv = ProviderEnv(
        channel = ProviderChannel.current,
        offline = !InternetPermission.granted,
        onDeviceTranslate = onDeviceTranslate,
        offlineGifPacks = OfflineGifPacks.hasPacks,
    )
}

/**
 * The curated part: which provider types a build offers, and which it
 * suggests first.
 *
 * Every build can add every type it can run, self-hosted servers included.
 * What changes is the order and the grouping of the add sheet. The F-Droid
 * build suggests free software and open data, and lists the services that
 * want a proprietary key after them. Play and GitHub suggest what works with
 * nothing typed in. The no-internet build lists only what runs on the phone.
 */
object ProviderCatalog {

    /** Whether this build can run [type] at all. */
    fun runnable(type: ProviderType, env: ProviderEnv): Boolean = when {
        env.offline && !type.onDevice -> false
        type === ProviderTypes.ML_KIT && !env.onDeviceTranslate -> false
        else -> true
    }

    fun suggested(type: ProviderType, env: ProviderEnv): Boolean = when (env.channel) {
        ProviderChannel.FDROID -> type.freeSoftware || type.onDevice
        ProviderChannel.PLAY, ProviderChannel.GITHUB -> type.onDevice || type.readyOutOfTheBox
    }

    /** One group of the add sheet. */
    data class Group(val suggested: Boolean, val types: List<ProviderType>)

    /**
     * The types the add sheet offers for [tool]: every runnable one, minus a
     * single-instance type already in [chain]. Suggested ones first.
     */
    fun addable(tool: ProviderTool, chain: ProviderChain, env: ProviderEnv): List<Group> {
        val types = ProviderTypes.serving(tool)
            .filter { runnable(it, env) }
            .filter { it.multiInstance || chain.entries.none { entry -> entry.typeId == it.id } }
        val (first, rest) = types.partition { suggested(it, env) }
        return listOf(Group(true, first), Group(false, rest)).filter { it.types.isNotEmpty() }
    }
}
