package com.wasimaster.wmkeyboard.app.modules

import android.app.Application
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.modules.NativeDelivery
import com.wasimaster.wmkeyboard.core.transliteration.NativeLinker

/**
 * The [installOnDemandDelivery] of every channel that is not Play.
 *
 * A build with native delivery (the GitHub build, and F-Droid's behind its
 * opt-in) left the native libraries behind optional features out of its APK
 * and pinned them in assets/native/pins.tsv: [NativeDelivery] reads the pins
 * and puts a downloading gate behind every gate they name. A build without
 * the pins (`-Pwmkb.nativeDelivery=false`, the no-internet build) compiles
 * everything in, the default always-installed gates in FeatureModules are
 * already telling the truth, and this does nothing.
 */
fun installOnDemandDelivery(app: Application) {
    NativeDelivery.install(app, needOptIn = BuildConfig.ENABLE_FDROID, dlopen = NativeLinker::dlopen)
}
