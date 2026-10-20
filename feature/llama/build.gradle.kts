plugins {
    // By id, not version-catalog alias: AGP is already on the build classpath
    // through build-logic, and Gradle refuses a versioned plugin request for
    // a plugin the classpath already carries.
    id("com.android.dynamic-feature")
}

// The on-demand home of llama.cpp, Play builds only: :app lists this module
// in `dynamicFeatures` when wmkb.enablePlayStore is on, and no other build
// references it at all. Every other channel packages the same files in the
// base APK from :core:intelligence, or pins them for download. The module has
// no code: LlamaNative stays in :core:intelligence and loads the library
// through FeatureModules.loadLibrary once Play has installed this split,
// which the first GGUF model asks for. It packages :core:intelligence's
// src/llama/jniLibs in place, which :core:intelligence adds to itself only
// off Play.
android {
    namespace = "com.wasimaster.wmkeyboard.llamafeature"
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

    // Must mirror the base app's dimension: each base variant needs a
    // matching variant here (fullRelease base -> fullRelease split).
    flavorDimensions += "capabilities"
    productFlavors {
        create("full") { dimension = "capabilities" }
        create("lite") { dimension = "capabilities" }
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
        // Both flavours: llama.cpp needs no Google code, so lite has it too.
        variant.sources.jniLibs?.addStaticSourceDirectory("../../core/intelligence/src/llama/jniLibs")
    }
}

dependencies {
    // Dynamic-feature convention: every feature depends on the base app.
    implementation(project(":app"))
}
