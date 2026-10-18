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

// The other smart home platforms as HomeBackend adapters: SmartThings over
// Samsung's cloud, a Philips Hue bridge over CLIP v2 on the LAN, openHAB over
// its REST API, each mapped onto Home Assistant's entity shape so the one
// panel draws them all. Pure JVM like :core:homeassistant: the keyboard adds
// the OkHttp client with its network log and gate, and mDNS.
android {
    namespace = "com.wasimaster.wmkeyboard.smarthome"
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
    api(project(":core:homeassistant"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
