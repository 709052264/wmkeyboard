plugins {
    alias(libs.plugins.android.library)
    id("wmkeyboard.detekt")
    alias(libs.plugins.kotlin.serialization)
    // Applied at the root with their versions; see build.gradle.kts there.
    id("org.jetbrains.kotlinx.kover")
    id("com.autonomousapps.dependency-analysis")
}

// Coverage for the root's merged report (`./gradlew koverHtmlReportUnit`):
// this module's debug unit tests.
kover {
    currentProject {
        createVariant("unit") { add("debug") }
    }
}

// The Send protocol (issue #729): the client side of timvisee/send, the
// maintained fork of Firefox Send. Key derivation, the aes128gcm content
// encoding (RFC 8188), the metadata envelope, the WebSocket upload and the
// authenticated download, plus the share-link grammar and the instance
// catalogue. An Android library like the modules around it, but nothing
// under src/main touches an Android class: it is javax.crypto, OkHttp and plain streams, so the whole protocol
// runs in JVM unit tests against the RFC's vectors and a mock server.
android {
    namespace = "com.wasimaster.wmkeyboard.send"
    compileSdk {
        version = release(37)
    }
    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    lint { lintConfig = rootProject.file("config/lint/lint.xml") }

    testOptions {
        unitTests.all {
            // The interop test uploads to and downloads from a public Send
            // instance and is skipped unless asked for: -Psend.interop=1
            it.systemProperty("send.interop", providers.gradleProperty("send.interop").getOrElse(""))
            it.systemProperty("send.interop.url", providers.gradleProperty("send.interop.url").getOrElse(""))
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
    implementation(libs.kotlinx.serialization.json)
    api(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
}
