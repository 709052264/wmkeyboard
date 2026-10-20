import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    id("wmkeyboard.detekt")
    alias(libs.plugins.kotlin.compose)
    id("wmkeyboard.compose-metrics")
    alias(libs.plugins.kotlin.serialization)
    // Applied at the root with their versions; see build.gradle.kts there.
    id("org.jetbrains.kotlinx.kover")
    id("com.autonomousapps.dependency-analysis")
}

// Coverage for the root's merged report (`./gradlew koverHtmlReportUnit`):
// this module's full-flavour debug unit tests.
kover {
    currentProject {
        createVariant("unit") { add("fullDebug") }
    }
}

// Same channel flag :app reads. It decides where the LiteRT interpreter that
// runs Whisper lives: non-Play full builds compile src/whisperbridge (and the
// litert dependency) straight into this module; Play builds leave both out of
// the base APK — the on-demand :feature:litert module carries them instead,
// so the interpreter only reaches devices that use offline dictation. See
// WhisperRuntime.kt for the seam, and :core:intelligence for the same
// arrangement this one copies.
val playStoreChannel = run {
    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    (providers.gradleProperty("wmkb.enablePlayStore").orNull
        ?: localProperties.getProperty("wmkb.enablePlayStore")
        ?: System.getenv("WMKB_ENABLE_PLAY_STORE")
        ?: "false").toBoolean()
}

android {
    namespace = "com.wasimaster.wmkeyboard.voice"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 24
    }

    flavorDimensions += "capabilities"
    productFlavors {
        create("full") { dimension = "capabilities" }
        create("lite") { dimension = "capabilities" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true }
    lint { lintConfig = rootProject.file("config/lint/lint.xml") }
}

// Play builds leave src/main/jniLibs (whisper.cpp, RNNoise) and
// src/full/jniLibs (Whistle) out of this module, so out of the base APK: the
// on-demand :feature:speech split packages the same directories instead, and
// :feature:executorch packages src/executorch/jniLibs (ExecuTorch), which
// every other build adds below. The files stay here because the native build scripts and
// F-Droid's recipe write and scan exactly these paths.
//
// Through the new DSL's extension type, not the `android` accessor's
// `sourceSets`: under AGP 9 that one is typed as the old API's
// AndroidLibrarySourceSet, and the container's objects fail the cast at
// configuration time.
if (playStoreChannel) {
    extensions.getByType<com.android.build.api.dsl.LibraryExtension>().sourceSets.apply {
        getByName("main").jniLibs.setSrcDirs(emptyList<String>())
        getByName("full").jniLibs.setSrcDirs(emptyList<String>())
    }
}

// Offline Sherpa's libraries: libwmsherpa (native/sherpa-onnx-jni) from
// src/onnx/jniLibs, and Microsoft's libonnxruntime.so out of its AAR. Kept
// apart from src/main/jniLibs because they ride their own gate, `onnx`: on
// Play the on-demand :feature:onnx split packages both instead, so this
// module carries neither there.
if (!playStoreChannel) apply(plugin = "wmkeyboard.onnxruntime")

androidComponents {
    onVariants { variant ->
        // Static-source-dir mechanism rather than a flavour folder for the
        // same AGP 9 reason as the channel seams in :app — and conditional,
        // which a flavour folder cannot be.
        if (!playStoreChannel && variant.flavorName == "full") {
            variant.sources.kotlin?.addStaticSourceDirectory("src/whisperbridge/java")
        }
        // ExecuTorch rides its own gate, `executorch`, and Offline Sherpa's
        // bridge the `onnx` gate: both kept apart from src/main/jniLibs so
        // Play's :feature:executorch and :feature:onnx splits can carry them
        // alone. Elsewhere they are packaged here, or pinned for native delivery.
        if (!playStoreChannel) {
            variant.sources.jniLibs?.addStaticSourceDirectory("src/executorch/jniLibs")
            variant.sources.jniLibs?.addStaticSourceDirectory("src/onnx/jniLibs")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        extraWarnings.set(true)
        freeCompilerArgs.addAll("-Xreport-all-warnings")
        allWarningsAsErrors.set(providers.gradleProperty("warningsAsErrors").map { it.toBoolean() }.orElse(false))
    }
}

dependencies {
    api(project(":core:language"))
    api(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    testImplementation(libs.junit)
    if (!playStoreChannel) {
        "fullImplementation"(libs.litert)
    }
}
