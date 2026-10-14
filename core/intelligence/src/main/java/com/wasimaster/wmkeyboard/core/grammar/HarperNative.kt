package com.wasimaster.wmkeyboard.core.grammar

import com.wasimaster.wmkeyboard.core.modules.FeatureModules
import com.wasimaster.wmkeyboard.core.modules.GatedLibrary

/**
 * JNI surface of the bundled Harper grammar engine (libharper_jni.so, built
 * from native/harper-jni). The library is optional at runtime: a build made
 * without the Rust toolchain simply ships no .so, and [available] turns the
 * grammar tool into a "not in this build" panel instead of crashing. Play
 * builds carry it in the on-demand `:feature:grammar` split instead of the
 * base APK, fetched the first time the grammar tool or the spell checker runs.
 */
internal object HarperNative {
    const val LIBRARY = "harper_jni"

    private val library = GatedLibrary({ FeatureModules.grammar }) {
        runCatching { FeatureModules.loadLibrary(LIBRARY) }.isSuccess
    }

    /** Loaded the first time this is read, never at class init: see [GrammarChecker.bundled]. */
    val available: Boolean get() = library.available

    /**
     * Lints [text] and returns a JSON array of lints with UTF-16 spans.
     * The native linter cache is thread-local — only call through
     * [GrammarChecker]'s single-thread dispatcher.
     */
    external fun nativeLint(text: String, dialect: Int): String?

    /** Pre-builds the linter for [dialect] so the first real check is fast. */
    external fun nativeWarmUp(dialect: Int)

    /**
     * Drops every cached rule set. Thread-local like the cache it clears, so
     * this too must go through [GrammarChecker]'s single thread — called on
     * any other one it frees nothing and says nothing about it.
     */
    external fun nativeRelease()
}
