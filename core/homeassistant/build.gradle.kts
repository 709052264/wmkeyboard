plugins {
    alias(libs.plugins.android.library)
    id("wmkeyboard.detekt")
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

// The Home Assistant client: REST, the WebSocket API with its compressed
// entity stream, OAuth, the service catalogue per domain, state formatting
// and icon mapping. An Android library because every module here carries the
// `capabilities` dimension, but nothing under src/main touches an Android
// class, so the whole protocol runs in plain JVM unit tests against OkHttp's
// MockWebServer. The keyboard module adds the Android glue (mDNS, token
// storage, the panel) on top.
android {
    namespace = "com.wasimaster.wmkeyboard.homeassistant"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
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
    lint { lintConfig = rootProject.file("config/lint/lint.xml") }

    testOptions {
        unitTests.all {
            // The interop test talks to a real Home Assistant and is skipped
            // unless asked for: -Pha.interop=1 -Pha.url=http://host:8123 -Pha.token=…
            it.systemProperty("ha.interop", providers.gradleProperty("ha.interop").getOrElse(""))
            it.systemProperty("ha.url", providers.gradleProperty("ha.url").getOrElse(""))
            it.systemProperty("ha.token", providers.gradleProperty("ha.token").getOrElse(""))
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // The WebSocket. Already in the APK as Coil's network engine.
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
