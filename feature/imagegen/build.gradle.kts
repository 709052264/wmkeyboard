plugins {
    // By id, not version-catalog alias: AGP is already on the build classpath
    // through build-logic, and Gradle refuses a versioned plugin request for
    // a plugin the classpath already carries.
    id("com.android.dynamic-feature")
}

// The on-demand home of the sticker generator's runtimes, Play builds only:
// :app lists this module in `dynamicFeatures` when wmkb.enablePlayStore is
// on, and no other build references it at all. Every other channel packages
// the same files in the base APK from :core:intelligence, or pins them for
// download. The module has no code: ImageGenNative and QnnImageNative stay in
// :core:intelligence and load their libraries through
// FeatureModules.loadLibrary once Play has installed this split, which the
// generator asks for the first time it opens. It packages
// :core:intelligence's src/imagegen/jniLibs (MNN, every ABI, about 2.6 MB on
// arm64) and, with the NPU, src/imagegennpu/jniLibs (the arm64 QNN runner,
// under 0.5 MB). The NPU runner is here rather than in :feature:npu because
// that split carries no native libraries, and every split that does must
// cover the same ABIs; this one does through MNN's.
// The same flag :app reads for :feature:npu.
val npu = run {
    val local = java.util.Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { local.load(it) }
    (providers.gradleProperty("wmkb.enableNpu").orNull ?: local.getProperty("wmkb.enableNpu")
        ?: System.getenv("WMKB_ENABLE_NPU") ?: "true").toBoolean()
}

android {
    namespace = "com.wasimaster.wmkeyboard.imagegenfeature"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 24
        // See :feature:llm: :app has a `languages` dimension and this module
        // does not, and Play takes the `en` bundle.
        missingDimensionStrategy("languages", "en", "intl")
        // bundletool requires every module with native libraries to support
        // the SAME ABI set: the release base's three, which is also exactly
        // what the directory below carries.
        ndk {
            abiFilters += setOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
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

androidComponents {
    onVariants { variant ->
        variant.sources.jniLibs?.addStaticSourceDirectory("../../core/intelligence/src/imagegen/jniLibs")
        if (npu) variant.sources.jniLibs?.addStaticSourceDirectory("../../core/intelligence/src/imagegennpu/jniLibs")
    }
}

dependencies {
    // Dynamic-feature convention: every feature depends on the base app.
    implementation(project(":app"))
}
