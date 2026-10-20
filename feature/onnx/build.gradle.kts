plugins {
    // By id, not version-catalog alias: AGP is already on the build classpath
    // through build-logic, and Gradle refuses a versioned plugin request for
    // a plugin the classpath already carries.
    id("com.android.dynamic-feature")
    // Microsoft's libonnxruntime.so, out of its AAR, as this split's library.
    id("wmkeyboard.onnxruntime")
}

// The on-demand home of ONNX Runtime and libwmsherpa, the sherpa-onnx bridge
// behind Offline Sherpa, Play builds only: :app lists this module in
// `dynamicFeatures` when wmkb.enablePlayStore is on, and no other build
// references it. Every other build has :core:voice package the same two
// libraries (src/onnx/jniLibs and the wmkeyboard.onnxruntime convention), or
// pins them for native delivery. No code: SherpaNative stays in :core:voice
// and loads both through FeatureModules.loadLibrary, the runtime first, once
// Play has installed this split. About 33 MB per ABI, fetched only when
// someone picks Sherpa.
android {
    namespace = "com.wasimaster.wmkeyboard.onnxfeature"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 24
        // See :feature:llm: :app has a `languages` dimension and this module
        // does not, and Play takes the `en` bundle.
        missingDimensionStrategy("languages", "en", "intl")
        // bundletool requires every module with native libraries to support
        // the SAME ABI set: the release base's three.
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
        variant.sources.jniLibs?.addStaticSourceDirectory("../../core/voice/src/onnx/jniLibs")
    }
}

dependencies {
    // Dynamic-feature convention: every feature depends on the base app.
    implementation(project(":app"))
}
