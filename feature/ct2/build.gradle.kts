plugins {
    // By id, not version-catalog alias: AGP is already on the build classpath
    // through build-logic, and Gradle refuses a versioned plugin request for
    // a plugin the classpath already carries.
    id("com.android.dynamic-feature")
}

// The on-demand home of CTranslate2, the NLLB translator's runtime, Play
// builds only: :app lists this module in `dynamicFeatures` when
// wmkb.enablePlayStore is on, and no
// other build references it at all. Every other channel packages the same
// file in the base APK from :core:intelligence, or pins it for download. The
// module has no code: Ct2Native stays in :core:intelligence and loads
// the library through FeatureModules.loadLibrary once Play has installed this
// split, which the NLLB model download asks for first. It
// packages :core:intelligence's src/ct2/jniLibs in place, which
// :core:intelligence adds to itself only off Play. About 2.7 MB on arm64.
android {
    namespace = "com.wasimaster.wmkeyboard.ct2feature"
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
        variant.sources.jniLibs?.addStaticSourceDirectory("../../core/intelligence/src/ct2/jniLibs")
    }
}

dependencies {
    // Dynamic-feature convention: every feature depends on the base app.
    implementation(project(":app"))
}
