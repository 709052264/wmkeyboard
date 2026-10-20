import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    id("wmkeyboard.detekt")
    alias(libs.plugins.kotlin.compose)
    id("wmkeyboard.compose-metrics")
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

android {
    namespace = "com.wasimaster.wmkeyboard.input"
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
    buildFeatures { compose = true }
    lint { lintConfig = rootProject.file("config/lint/lint.xml") }
}

// Play builds leave src/main/jniLibs (librime, behind the RIME engine) out of
// this module, so out of the base APK: the on-demand :feature:rime split
// packages the same directory instead. The file stays here because
// native/librime-jni/build.sh and F-Droid's recipe write and scan this path.
// Through the new DSL's extension type, for the AGP 9 reason given in
// :core:intelligence.
val playStoreChannel = run {
    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    (providers.gradleProperty("wmkb.enablePlayStore").orNull
        ?: localProperties.getProperty("wmkb.enablePlayStore")
        ?: System.getenv("WMKB_ENABLE_PLAY_STORE")
        ?: "false").toBoolean()
}
if (playStoreChannel) {
    extensions.getByType<com.android.build.api.dsl.LibraryExtension>().sourceSets
        .getByName("main").jniLibs.setSrcDirs(emptyList<String>())
}

androidComponents {
    onVariants { variant ->
        // Mozc's library, off Play: in a directory of its own so the on-demand
        // :feature:mozc split carries it alone on Play, as :feature:rime does
        // src/main/jniLibs. A build with native delivery pins it instead
        // (wmkeyboard.native-pins); native/mozc-jni/build.sh writes here.
        if (!playStoreChannel) {
            variant.sources.jniLibs?.addStaticSourceDirectory("src/mozc/jniLibs")
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
    api(project(":core:language"))
    api(project(":core:common"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    // Rime schemas and dictionary headers are YAML, read with the same parser
    // and the same safe settings as FUTO layouts in `:core:language`.
    implementation(libs.snakeyaml)

    // This module's first test source set. The language-pack reader opens an
    // SQLite database, which is an Android API, so its test needs Robolectric
    // rather than a plain JVM run.
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
}
