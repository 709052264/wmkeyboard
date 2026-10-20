plugins {
    // By id, not version-catalog alias: AGP is already on the build classpath
    // through build-logic, and Gradle refuses a versioned plugin request for
    // a plugin the classpath already carries.
    id("com.android.dynamic-feature")
}

// The on-demand home of the whisper.cpp and Whistle native libraries,
// Play builds only: :app lists this module in `dynamicFeatures` when
// wmkb.enablePlayStore is on, and no other build references it at all. Every
// other channel packages the same files in the base APK from :core:voice.
// The module has no code: WhisperCpp and WhistleEngine stay in :core:voice and
// load the libraries through FeatureModules.loadLibrary once Play has
// installed this split. It packages :core:voice's src/main/jniLibs and
// src/whistle/jniLibs (Whistle, arm64 only) in place, which :core:voice
// leaves out of itself on this channel, so the files keep the paths the native
// build scripts and F-Droid's recipe know. Whistle rides here rather than in a
// split of its own because bundletool wants every module with native code on
// the base's three ABIs, and Whistle has one. Used only by the GGML Whisper
// models and Whistle, both of which the user picks and downloads first.
// ExecuTorch, which used to ride here too, is :feature:executorch.
android {
    namespace = "com.wasimaster.wmkeyboard.speechfeature"
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
        variant.sources.jniLibs?.addStaticSourceDirectory("../../core/voice/src/main/jniLibs")
        variant.sources.jniLibs?.addStaticSourceDirectory("../../core/voice/src/whistle/jniLibs")
    }
}

dependencies {
    // Dynamic-feature convention: every feature depends on the base app.
    implementation(project(":app"))
}
