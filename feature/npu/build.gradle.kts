plugins {
    // By id, not version-catalog alias: AGP is already on the build classpath
    // through build-logic, and Gradle refuses a versioned plugin request for
    // a plugin the classpath already carries.
    id("com.android.dynamic-feature")
    // The Qualcomm NPU runtime, as this split's assets.
    id("wmkeyboard.npu-runtime")
}

// The on-demand home of the Qualcomm NPU runtime behind Compute = NPU for
// on-device AI (#804), Play builds only: :app lists this module in
// `dynamicFeatures` when wmkb.enablePlayStore and wmkb.enableNpu are both on,
// and no other build references it. Every other build packages the same
// assets in the base APK from :core:intelligence. No code and no native
// libraries: NpuRuntime in :core:intelligence copies the assets into app
// storage, where LiteRT and the DSP loader read them, so this split needs
// neither a dex nor the ABI parity placeholders :feature:llm carries. About
// 14 MB, fetched only by phones whose chip NpuSupport lists, and only once
// someone picks NPU.
android {
    namespace = "com.wasimaster.wmkeyboard.npufeature"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 24
        // See :feature:llm: :app has a `languages` dimension and this module
        // does not, and Play takes the `en` bundle.
        missingDimensionStrategy("languages", "en", "intl")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint { lintConfig = rootProject.file("config/lint/lint.xml") }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

dependencies {
    // Dynamic-feature convention: every feature depends on the base app.
    implementation(project(":app"))
}
