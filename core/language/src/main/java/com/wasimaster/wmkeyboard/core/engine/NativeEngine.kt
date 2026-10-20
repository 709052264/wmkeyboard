package com.wasimaster.wmkeyboard.core.engine

import java.io.File

/**
 * The always-shipped native engine, libwm_engine_jni (`native/wm-engine-jni`
 * over the `native/wm-engine` crate): its loading, and the switch over the
 * code moving into it from Kotlin (native/RUST_NEON_PLAN.md).
 *
 * On a device the library is in the base APK, so `System.loadLibrary` finds
 * it, at startup and in direct boot alike; it never moves to an on-demand
 * gate. In a JVM unit test it is the host build `native/wm-engine-jni/build-host.sh`
 * makes, found by walking up from the test's working directory to
 * `settings.gradle.kts`; the Gradle test tasks of every module that reaches
 * it run that script first. `-Dwmkeyboard.engine.lib=<path>` or
 * `WM_ENGINE_LIB` name a library outright.
 *
 * Every class with `external fun`s into the library ([com.wasimaster.wmkeyboard.core.transliteration]'s
 * `EngineNative`, the prediction bridges) calls [requireLoaded] or checks
 * [useNative] first, so one load serves them all.
 *
 * ## The switch
 *
 * While prediction moves from Kotlin to Rust, both implementations exist,
 * and every migrated piece asks [useNative] which to run. [forceKotlin]
 * turns the Rust side off for all of them at once, and pins the SIMD
 * kernels to their scalar references: the "Native prediction engine"
 * setting sets it, and so does `-Dwmkeyboard.native=kotlin` or
 * `WM_NATIVE=kotlin` for a test or eval run, which is how the parity tests
 * compare the two on the same inputs. Pieces whose Kotlin copy has been
 * deleted run in Rust regardless; the transliteration engines always did.
 */
object NativeEngine {
    const val LIBRARY = "wm_engine_jni"

    /** Bits of [cpuFeatures]: what the kernels may use on this CPU. */
    const val FEATURE_NEON = 1
    const val FEATURE_DOTPROD = 2
    const val FEATURE_FP16 = 4

    private val failure: Throwable? = try {
        load()
        null
    } catch (e: UnsatisfiedLinkError) {
        e
    } catch (e: IllegalStateException) {
        e
    }

    /** Whether the library loaded in this process. */
    val loaded: Boolean get() = failure == null

    /**
     * Run the Kotlin implementation of every piece that still has one, and
     * the kernels' scalar references. Read on every call, so flipping it
     * takes effect on the next keystroke.
     */
    @Volatile
    var forceKotlin: Boolean = defaultForceKotlin()
        set(value) {
            field = value
            if (loaded) setForceScalar(value)
        }

    init {
        if (loaded && forceKotlin) setForceScalar(true)
    }

    /** Whether a piece with both implementations should run its Rust one. */
    val useNative: Boolean get() = loaded && !forceKotlin

    /** Throws what loading threw, for code that has no Kotlin to fall back on. */
    fun requireLoaded() {
        failure?.let { throw it }
    }

    private fun defaultForceKotlin(): Boolean {
        val value = System.getProperty("wmkeyboard.native") ?: System.getenv("WM_NATIVE")
        return value.equals("kotlin", ignoreCase = true)
    }

    private fun load() {
        val explicit = System.getProperty("wmkeyboard.engine.lib") ?: System.getenv("WM_ENGINE_LIB")
        if (!explicit.isNullOrEmpty()) {
            System.load(explicit)
            return
        }
        // ART reports itself as Dalvik; Robolectric runs on a desktop JVM.
        if (System.getProperty("java.vm.name") == "Dalvik") {
            System.loadLibrary(LIBRARY)
            return
        }
        val host = hostLibrary() ?: error(
            "libwm_engine_jni is not built for this machine: run native/wm-engine-jni/build-host.sh " +
                "(needs cargo), or point -Dwmkeyboard.engine.lib at a build of it",
        )
        System.load(privateCopy(host).absolutePath)
    }

    /**
     * A copy of [library] for this class loader alone. A JVM binds a native
     * library to the first class loader that loads its path, and Robolectric
     * gives every sandbox a loader of its own, so a second test class in the
     * same JVM failed with "already loaded in another classloader". A copy
     * at a fresh path is a fresh library to the JVM.
     */
    private fun privateCopy(library: File): File {
        val copy = File.createTempFile("wm_engine_jni", "." + library.extension)
        copy.deleteOnExit()
        library.copyTo(copy, overwrite = true)
        return copy
    }

    /** The host build under the repo's `native/wm-engine-jni/target`, release first. */
    private fun hostLibrary(): File? {
        val names = listOf("libwm_engine_jni.dylib", "libwm_engine_jni.so", "wm_engine_jni.dll")
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").isFile) {
                for (profile in listOf("release", "debug")) {
                    for (name in names) {
                        val file = File(dir, "native/wm-engine-jni/target/$profile/$name")
                        if (file.isFile) return file
                    }
                }
                return null
            }
            dir = dir.parentFile
        }
        return null
    }

    /** The [FEATURE_NEON]... bits this CPU has, whatever [forceKotlin] says. */
    fun cpuFeatures(): Int = if (loaded) nativeCpuFeatures() else 0

    /**
     * `query · row` for every [dim]-wide row of [matrix] (row-major) into
     * [out], in one call; returns the rows scored. NEON on arm64.
     */
    fun dotRows(query: FloatArray, matrix: FloatArray, dim: Int, out: FloatArray): Int {
        requireLoaded()
        return nativeDotRows(query, matrix, dim, out)
    }

    private external fun setForceScalar(on: Boolean)
    private external fun nativeCpuFeatures(): Int
    private external fun nativeDotRows(query: FloatArray, matrix: FloatArray, dim: Int, out: FloatArray): Int
}
