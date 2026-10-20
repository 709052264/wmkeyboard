import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import java.util.Properties
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Attribute

// Native delivery for the builds Play Feature Delivery does not reach (GitHub,
// and F-Droid behind its opt-in): the native libraries behind optional
// features are left out of the APK, and `NativePinsTask` writes their sizes
// and SHA-256s into assets/native/pins.tsv, which NativeDelivery in
// :core:common reads at startup to fetch each one the first time its feature
// asks. Applied by :app only off Play and with the internet permission.
//
// What is delivered, and from where, is listed here and nowhere else. The
// project's own libraries are pinned from the jniLibs the build would have
// packaged and published on the data repository's `natives` release.
// Dependencies' libraries are pinned from the AAR this build resolved and read
// out of that AAR on its own Maven repository: ML Kit's, LiteRT's and
// Qualcomm's terms do not allow mirroring them.

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun prop(name: String, env: String, default: String): String =
    providers.gradleProperty(name).orNull ?: localProperties.getProperty(name) ?: System.getenv(env) ?: default

/** Our own libraries: name to gate, and whether only the full flavour has it. */
data class Own(val gate: String, val fullOnly: Boolean)

val ownSpecs = mapOf(
    "libwmwhispercpp.so" to Own("speech", false),
    "libwmwhispercpp_v82.so" to Own("speech", false),
    "libwmexecutorch.so" to Own("speech", false),
    "libwmwhistle.so" to Own("speech", true),
    "libharper_jni.so" to Own("grammar", false),
    "libwmtess.so" to Own("ocr", true),
)

/** Where the build keeps them; F-Droid's recipe builds and scans the same paths. */
val ownDirs = mapOf(
    "core/voice/src/main/jniLibs" to false,
    "core/voice/src/full/jniLibs" to true,
    "core/intelligence/src/main/jniLibs" to false,
    "core/intelligence/src/full/jniLibs" to true,
)

/** A dependency's library: its gate, the module whose AAR holds it, a licence. Full flavour only. */
data class Dep(val gate: String, val module: String, val licence: String = "")

val dependencySpecs = mapOf(
    "libtensorflowlite_jni.so" to Dep("litert", "com.google.ai.edge.litert:litert"),
    "liblitertlm_jni.so" to Dep("llm", "com.google.ai.edge.litertlm:litertlm-android"),
    "libtranslate_jni.so" to Dep("translate", "com.google.mlkit:translate"),
    "liblanguage_id_l2c_jni.so" to Dep("translate", "com.google.mlkit:language-id"),
    "libdigitalink.so" to Dep("handwriting", "com.google.mlkit:digital-ink-recognition"),
    "libmlkit_google_ocr_pipeline.so" to Dep("scanner", "com.google.mlkit:text-recognition-bundled-common"),
    "libbarhopper_v3.so" to Dep("scanner", "com.google.mlkit:barcode-scanning"),
)

// The Qualcomm NPU runtime (#804), when this build offers the NPU at all. Keep
// in step with NpuSupport.runtimeLibraries: arm64 only, the chips it lists.
val npuEnabled = prop("wmkb.enableNpu", "WMKB_ENABLE_NPU", "true").toBoolean() &&
    !prop("wmkb.enableFdroid", "WMKB_ENABLE_FDROID", "false").toBoolean()
val npuSpecs = if (!npuEnabled) emptyMap() else mapOf(
    "libLiteRtDispatch_Qualcomm.so" to Dep("npu", "com.google.ai.edge.litert:litert-npu-runtime-qualcomm", "qualcomm"),
    "libQnnSystem.so" to Dep("npu", "com.qualcomm.qti:qnn-runtime", "qualcomm"),
    "libQnnHtp.so" to Dep("npu", "com.qualcomm.qti:qnn-runtime", "qualcomm"),
    // One gate per Hexagon generation, so a phone fetches its own chip's pair.
    "libQnnHtpV79Stub.so" to Dep("npu-V79", "com.qualcomm.qti:qnn-runtime", "qualcomm"),
    "libQnnHtpV79Skel.so" to Dep("npu-V79", "com.qualcomm.qti:qnn-runtime", "qualcomm"),
    "libQnnHtpV81Stub.so" to Dep("npu-V81", "com.qualcomm.qti:qnn-runtime", "qualcomm"),
    "libQnnHtpV81Skel.so" to Dep("npu-V81", "com.qualcomm.qti:qnn-runtime", "qualcomm"),
)

val pinAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

// None of them goes into the APK. Our own libraries reach :app from the core
// modules' jniLibs, the dependencies' from their AARs; one exclude list covers
// both, and the NPU runtime is never packaged here at all.
extensions.getByType(ApplicationExtension::class.java).packaging.jniLibs.excludes +=
    (ownSpecs.keys + dependencySpecs.keys).map { "**/$it" }

// The NPU runtime is not a dependency of the app, so its two AARs are resolved
// here as files, the way the wmkeyboard.npu-runtime convention takes them.
val npuAars = configurations.create("nativePinsNpu") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}
if (npuEnabled) {
    val catalog = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
    for (alias in listOf("litert-npu-runtime-qualcomm", "qnn-runtime")) {
        val module = catalog.findLibrary(alias).get().get()
        dependencies.add(npuAars.name, "${module.module.group}:${module.module.name}:${module.versionConstraint.requiredVersion}@aar")
    }
}

val artifactType = Attribute.of("artifactType", String::class.java)

extensions.getByType(ApplicationAndroidComponentsExtension::class.java).onVariants { variant ->
    val full = variant.productFlavors.any { it.second == "full" }
    val task = tasks.register(
        "nativePins${variant.name.replaceFirstChar { it.uppercase() }}",
        NativePinsTask::class.java,
    ) {
        val own = ArrayList<String>()
        for ((dir, fullDir) in ownDirs) {
            if (fullDir && !full) continue
            for (abi in pinAbis) {
                val abiDir = rootProject.file("$dir/$abi")
                for (file in abiDir.listFiles().orEmpty().sortedBy { it.name }) {
                    val spec = ownSpecs[file.name] ?: continue
                    if (spec.fullOnly && !full) continue
                    own += listOf(spec.gate, abi, file.name, file.path).joinToString("\t")
                    ownFiles.from(file)
                }
            }
        }
        ownLibraries.set(own)
        abis.set(pinAbis)
        val deps = if (full) dependencySpecs + npuSpecs else emptyMap()
        mavenLibraries.set(deps.map { (name, dep) -> listOf(name, dep.gate, dep.module, dep.licence).joinToString("\t") })
        // The raw AARs of this variant's own runtime classpath: the exact
        // versions whose Java code the APK carries, so the pinned library is
        // the one that code expects.
        val view = variant.runtimeConfiguration.incoming.artifactView {
            attributes { attribute(artifactType, "aar") }
            lenient(true)
            componentFilter { it is ModuleComponentIdentifier }
        }
        val npuView = npuAars.incoming.artifactView {
            componentFilter { it is ModuleComponentIdentifier }
        }
        aars.from(view.files, npuView.files)
        aarCoordinates.set(
            view.artifacts.resolvedArtifacts.zip(npuView.artifacts.resolvedArtifacts) { a, b ->
                (a + b).mapNotNull { artifact ->
                    val id = artifact.id.componentIdentifier as? ModuleComponentIdentifier ?: return@mapNotNull null
                    "${artifact.file.path}\t${id.group}:${id.module}:${id.version}"
                }
            },
        )
        outputDir.set(layout.buildDirectory.dir("generated/nativePins/${variant.name}"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(task, NativePinsTask::outputDir)
}
