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
 * [forceKotlin] pins the SIMD kernels to their scalar references;
 * `-Dwmkeyboard.native=kotlin` or `WM_NATIVE=kotlin` set it for a test or
 * eval run. While prediction moved from Kotlin to Rust it also turned each
 * migrated piece back to its Kotlin copy, and a setting drove it. Every
 * copy has since been deleted from the app, kept in the test sources as the
 * reference its parity test compares against, so prediction runs in Rust
 * regardless, as the transliteration engines always did. The OCR kernels
 * ([boxAverageBgr], [argmaxRows]) still answer false while [useNative] is
 * off, and their callers run the Kotlin loops they replaced.
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

    /**
     * The photo editor's 3x3 sharpen of rows [first] until [first] + [count]
     * of [src] ([width] ARGB colour ints a row, neighbours clamped to its
     * edges) into [out], in one call, with NEON on arm64. Bit for bit what
     * `PhotoEditRenderer.sharpenRows` gives. False, writing nothing, when the
     * library is missing or [useNative] is off: the caller then runs that
     * Kotlin loop itself.
     */
    fun sharpen(src: IntArray, width: Int, first: Int, count: Int, amount: Float, out: IntArray): Boolean =
        useNative && try {
            nativeSharpen(src, width, first, count, amount, out) == count
        } catch (_: UnsatisfiedLinkError) {
            // A library built before the kernel existed: the Kotlin loop still works.
            false
        }

    /**
     * The ARGB photo [pixels] ([width] × [height]) shrunk at [scale] (under
     * 1) by averaging each output pixel's whole box of photo pixels, into
     * the [outW] × [outH] corner of [out], rows [stride] pixels apart, three
     * floats per pixel: BGR, each `(value / 255 - mean) / std`. The rest of
     * [out] is zeroed. [norm] is the three means, then the three deviations.
     * Bit for bit what `PaddleOcrPipeline.detectorInput` computes in Kotlin;
     * NEON on arm64. False, with [out] untouched, when [useNative] is off,
     * the library predates the kernel, or the shape is not one the kernel
     * takes: run the Kotlin then.
     */
    fun boxAverageBgr(
        pixels: IntArray, width: Int, height: Int, scale: Float,
        outW: Int, outH: Int, stride: Int, norm: FloatArray, out: FloatArray,
    ): Boolean = useNative && try {
        nativeBoxAverageBgr(pixels, width, height, scale, outW, outH, stride, norm, out)
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    /**
     * The index of the first largest value in each [cols]-wide row of
     * [data] into [out], one per row, as a strict `>` scan from index 0
     * finds it (so a NaN never wins). True when it ran: false when
     * [useNative] is off, the library predates the kernel or [out] could
     * not take every row, and then the caller runs its Kotlin. NEON on arm64.
     */
    fun argmaxRows(data: FloatArray, cols: Int, out: IntArray): Boolean {
        if (!useNative || cols <= 0) return false
        val rows = data.size / cols
        return rows <= out.size && try {
            nativeArgmaxRows(data, cols, out) == rows
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    private external fun setForceScalar(on: Boolean)
    private external fun nativeCpuFeatures(): Int
    private external fun nativeDotRows(query: FloatArray, matrix: FloatArray, dim: Int, out: FloatArray): Int
    private external fun nativeSharpen(src: IntArray, width: Int, first: Int, count: Int, amount: Float, out: IntArray): Int
    private external fun nativeBoxAverageBgr(
        pixels: IntArray, width: Int, height: Int, scale: Float,
        outW: Int, outH: Int, stride: Int, norm: FloatArray, out: FloatArray,
    ): Boolean
    private external fun nativeArgmaxRows(data: FloatArray, cols: Int, out: IntArray): Int
}
