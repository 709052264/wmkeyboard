package com.wasimaster.wmkeyboard.core.localllm

import android.os.Build
import com.wasimaster.wmkeyboard.config.BuildConfig
import java.io.File

/**
 * Which phones can run on-device models on their NPU (#804), and which of the
 * Qualcomm runtime's libraries that takes.
 *
 * NPU targets are per chip, not per brand: a model has to be compiled ahead of
 * time for one SoC (the catalog's [NpuBuild]s), and the QNN runtime that runs
 * it has one DSP library pair per Hexagon generation. This build packages the
 * pairs listed in [HTP_ARCH] only, so a chip outside it has no NPU choice at
 * all, whatever models exist for it upstream. Adding a chip is one entry here
 * and its libraries in the `wmkeyboard.npu-runtime` convention in build-logic.
 */
object NpuSupport {

    /**
     * Chip -> the Hexagon tensor processor generation its QNN libraries are
     * named after (`libQnnHtpV79Stub.so` and so on). SM8650 is the Snapdragon
     * 8 Gen 3 (Galaxy S24 series), SM8750 the 8 Elite (Galaxy S25 series),
     * SM8850 the 8 Elite Gen 5 (Galaxy S26 Ultra). SM8650 is here for the
     * sticker generator's NPU builds; no on-device AI model has one for it
     * yet, so the AI panel still never picks the NPU there.
     */
    internal val HTP_ARCH = mapOf(
        "SM8650" to "V75",
        "SM8750" to "V79",
        "SM8850" to "V81",
    )

    /** LiteRT's Qualcomm dispatch library, the one `Backend.NPU` is pointed at. */
    const val DISPATCH_LIBRARY = "libLiteRtDispatch_Qualcomm.so"

    /**
     * The chip id a [Build.SOC_MODEL] value stands for, or null. Vendors add
     * suffixes ("SM8750-AC" on some Galaxy builds) and the odd lowercase, so
     * only the `SM` + four digits prefix counts.
     */
    fun normalizeSoc(raw: String?): String? =
        SOC_PATTERN.find(raw.orEmpty().trim().uppercase())?.value

    /**
     * [normalizeSoc], narrowed to the chips this build has a runtime for, on
     * the platform the runtime needs: API 31 and a 64-bit ARM process.
     */
    fun supportedSoc(rawSoc: String?, sdkInt: Int, primaryAbi: String?): String? {
        if (sdkInt < MIN_SDK || primaryAbi != ARM64) return null
        return normalizeSoc(rawSoc)?.takeIf { it in HTP_ARCH }
    }

    /**
     * This phone's NPU target, or null when there is none: not a listed chip,
     * an older Android, an F-Droid build. Fixed for the process.
     */
    val soc: String? by lazy {
        if (!BuildConfig.ENABLE_NPU || Build.VERSION.SDK_INT < MIN_SDK) return@lazy null
        supportedSoc(Build.SOC_MODEL, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS.firstOrNull())
    }

    /** Whether the Compute row may offer NPU on this phone. */
    val available: Boolean get() = soc != null

    /**
     * The runtime files [soc] needs, in the order they are loaded: the QNN
     * system and HTP libraries, the generation's stub (ARM code that talks to
     * the DSP) and skel (Hexagon code the DSP loads), and the dispatch library
     * last. Never `libQnnHtpPrepare.so`: that one compiles a model on the
     * phone, and every catalog NPU build arrives compiled. Empty for a chip
     * this build has no runtime for.
     */
    fun runtimeLibraries(soc: String): List<String> {
        val arch = HTP_ARCH[soc] ?: return emptyList()
        return listOf(
            "libQnnSystem.so",
            "libQnnHtp.so",
            "libQnnHtp${arch}Stub.so",
            "libQnnHtp${arch}Skel.so",
            DISPATCH_LIBRARY,
        )
    }

    private const val MIN_SDK = 31
    private const val ARM64 = "arm64-v8a"
    private val SOC_PATTERN = Regex("^SM\\d{4}")
}

/**
 * Which file an engine with Compute set to NPU, or to CPU or GPU, should load
 * for one selected model (#804). A catalog model can have two files side by
 * side in its directory: the standard build every backend runs, and an
 * ahead-of-time build for this phone's chip that only the NPU can run. Either
 * may be missing, so the engine needs both answers to fall back.
 */
object NpuRouting {

    /**
     * [npu] is the NPU build to try first, [standard] the file every other
     * backend loads. Both are existing files or null.
     */
    data class Plan(val npu: File?, val standard: File?)

    /**
     * Resolves [modelFile], the file `LocalLlmStore.selectedModelFile` handed
     * out (whichever of the two builds is on disk), for a phone whose NPU
     * target is [soc] (null: no NPU). An imported file is taken at its word:
     * one whose name carries the chip id ("…sm8750.litertlm") is an NPU build,
     * anything else a standard one.
     */
    fun plan(modelFile: File, soc: String?): Plan {
        val dir = modelFile.parentFile
        val model = dir?.let { LocalLlmCatalog.byId(it.name) }
            ?.takeIf { m -> modelFile.name == m.fileName || m.npuBuilds.any { it.fileName == modelFile.name } }
        if (model == null) {
            val existing = modelFile.takeIf { it.isFile }
            val npuNamed = soc != null && modelFile.name.lowercase().contains(soc.lowercase())
            return if (npuNamed) Plan(npu = existing, standard = null) else Plan(npu = null, standard = existing)
        }
        val npu = soc?.let { LocalLlmCatalog.npuBuild(model, it) }
            ?.let { File(dir, it.fileName) }
            ?.takeIf { it.isFile }
        val standard = File(dir, model.fileName).takeIf { it.isFile }
        return Plan(npu = npu, standard = standard)
    }
}
