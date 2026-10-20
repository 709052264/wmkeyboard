plugins {
    // By id, not version-catalog alias: AGP is already on the build classpath
    // through build-logic (see :feature:llm).
    id("com.android.dynamic-feature")
}

// The on-demand home of the Google Home APIs SDK, Play builds only, and only
// when the build links the SDK at all (settings.gradle.kts includes this
// module under the same conditions :feature:ime checks). The SDK is about
// 22 MB of dex that only people who add a Google Home ever use. The module
// compiles :feature:ime's src/googlehome, the code that touches the SDK,
// whose GoogleHomeBridge the base reaches by reflection through the facade
// in src/googlehomeplay.
android {
    namespace = "com.wasimaster.wmkeyboard.googlehomefeature"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 24
        // See :feature:llm: Play takes the `en` bundle.
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

androidComponents {
    onVariants { variant ->
        variant.sources.kotlin?.addStaticSourceDirectory("../ime/src/googlehome/java")
    }
}

// The newest SDK unpacked under :feature:ime's libs/googlehome/m2, which
// settings.gradle.kts registers as a repository.
val googleHomeVersion: String = rootProject.file("feature/ime/libs/googlehome/m2/com/google/android/gms/play-services-home")
    .listFiles().orEmpty().filter { it.isDirectory }.map { it.name }.maxOrNull()
    ?: error("the Google Home SDK is not under feature/ime/libs/googlehome/m2")

dependencies {
    implementation(project(":app"))
    // A feature compiles against the base's api surface only; these make the
    // classes src/googlehome uses visible, and AGP strips them from the split.
    implementation(project(":feature:ime"))
    implementation(project(":core:homeassistant"))
    implementation(project(":core:common"))
    // GoogleHomeMapping builds the panel's attribute JSON.
    implementation(libs.kotlinx.serialization.json)
    // Consent runs on the settings activity's lifecycle scope.
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation("com.google.android.gms:play-services-home:$googleHomeVersion")
    implementation("com.google.android.gms:play-services-home-types:$googleHomeVersion")
}
