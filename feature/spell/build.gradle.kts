plugins {
    // By id, not version-catalog alias: AGP is already on the build classpath
    // through build-logic, and Gradle refuses a versioned plugin request for
    // a plugin the classpath already carries.
    id("com.android.dynamic-feature")
}

// The on-demand home of the spelling libraries, Hunspell's and divvunspell's,
// Play builds only: :app lists this module in `dynamicFeatures` when
// wmkb.enablePlayStore is on, and no other build references it at all. Every
// other channel packages the same files in the base APK from :core:prediction,
// or pins them for download. The module has no code: HunspellNative and
// DivvunNative stay in :core:prediction and load their libraries through
// FeatureModules.loadLibrary once Play has installed this split. It packages
// :core:prediction's src/main/jniLibs in place, which :core:prediction drops
// from itself on this channel. About 4 MB on arm64 (1.1 Hunspell, 2.8
// divvunspell), used only where a language has an imported .aff/.dic or a
// GiellaLT speller.
android {
    namespace = "com.wasimaster.wmkeyboard.spellfeature"
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
        variant.sources.jniLibs?.addStaticSourceDirectory("../../core/prediction/src/main/jniLibs")
    }
}

dependencies {
    // Dynamic-feature convention: every feature depends on the base app.
    implementation(project(":app"))
}
