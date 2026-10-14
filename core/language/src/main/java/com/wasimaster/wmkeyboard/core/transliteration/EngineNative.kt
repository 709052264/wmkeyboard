package com.wasimaster.wmkeyboard.core.transliteration

import java.io.File

/**
 * JNI surface of the text engines: libwm_engine_jni, built from
 * `native/wm-engine-jni` over the `native/wm-engine` crate, which is the one
 * implementation of the phonetic engines the Android app and WMK Desktop
 * share. The Kotlin objects in this package ([AvroPhonetic], [Khipro],
 * [BijoyAnsi] and the rest) are facades over these calls and keep the
 * signatures the rest of the app always had.
 *
 * On a device the library is in the base APK, so `System.loadLibrary` finds
 * it. In a JVM unit test it is the host build `native/wm-engine-jni/build-host.sh`
 * makes, found by walking up from the test's working directory to
 * `settings.gradle.kts`; the Gradle test tasks of the modules that touch the
 * engine run that script first. `-Dwmkeyboard.engine.lib=<path>` or
 * `WM_ENGINE_LIB` name a library outright.
 *
 * Strings cross as they are; enums cross as ordinals in declaration order,
 * which the Rust side mirrors, so neither enum may be reordered on its own.
 * A panic inside an engine comes back as a [RuntimeException].
 */
internal object EngineNative {
    const val LIBRARY = "wm_engine_jni"

    init {
        load()
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
        System.load(host.absolutePath)
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

    // Phonetic engines.
    external fun avro(input: String): String
    external fun assamese(input: String): String
    external fun assameseToBengali(text: String): String
    external fun hindi(input: String): String
    external fun hindiVariants(input: String): Array<String>
    external fun urdu(input: String): String
    external fun urduVariants(input: String): Array<String>
    external fun arabic(input: String): String
    external fun arabicVariants(input: String): Array<String>
    external fun persian(input: String): String
    external fun persianVariants(input: String): Array<String>
    external fun indic(languageId: String, input: String): String
    external fun indicVariants(languageId: String, input: String): Array<String>
    external fun indicRomanPrep(languageId: String, input: String): String
    external fun indicDevanagariPrep(languageId: String, dev: String): String
    external fun indicRerankFlatList(languageId: String): Boolean
    external fun indicToDevanagari(script: Int, text: String): String
    external fun indicFromDevanagari(script: Int, dev: String): String

    // Khipro.
    external fun khipro(roman: String, variant: Int): String
    external fun khiproWarm(variant: Int)
    external fun khiproSpellings(word: String): Array<String>
    external fun khiproRomanizerWarm()

    // Bijoy ANSI.
    external fun bijoy(text: String, version: Int, before: String): String
    external fun bijoyNeedsConversion(text: String): Boolean

    // Bengali graphemes. Lengths are UTF-16 units; `previous` is a code point or -1.
    external fun clusterDeleteLength(text: String): Int
    external fun vowelFormAfter(previous: Int): Int
    external fun vowelKeyText(kar: Int, form: Int): String?

    // Romanizers.
    external fun bengaliRomanize(text: String): String
    external fun bengaliRomanizeWord(word: String): String
    external fun bengaliNormalize(text: String): String
    external fun devanagariRomanize(text: String): String
    external fun devanagariRomanizeWord(word: String): String
    external fun devanagariNormalize(text: String): String
    external fun urduRomanize(text: String): String
    external fun urduRomanizeWord(word: String): String
    external fun urduNormalize(text: String): String
}
