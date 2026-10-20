plugins {
    // By id, not version-catalog alias: AGP is already on the build classpath
    // through build-logic, and Gradle refuses a versioned plugin request for
    // a plugin the classpath already carries.
    id("com.android.dynamic-feature")
}

// The on-demand home of the Harper grammar library, Play builds only:
// :app lists this module in `dynamicFeatures` when wmkb.enablePlayStore is
// on, and no other build references it at all. Every other channel packages
// the same files in the base APK from :core:intelligence. The module has no
// code: HarperNative stays in :core:intelligence and loads the library
// through FeatureModules.loadLibrary once Play has installed this split. It
// packages :core:intelligence's src/main/jniLibs in place (only Harper is
// there; Tesseract is in src/full), which :core:intelligence drops from
// itself on this channel. About 2.3 MB on arm64, used only by the grammar
// tool and the spell checker.
android {
    namespace = "com.wasimaster.wmkeyboard.grammarfeature"
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
        // Both flavours: the base carries these libraries in full and lite
        // alike off Play.
        variant.sources.jniLibs?.addStaticSourceDirectory("../../core/intelligence/src/main/jniLibs")
    }
}

dependencies {
    // Dynamic-feature convention: every feature depends on the base app.
    implementation(project(":app"))
}
