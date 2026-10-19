import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
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

// Build-wide flags and API keys for library modules. The app module keeps its
// own copies of these fields for the app-package screens; both read the same
// local.properties / environment values, so they cannot drift.
val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun apiKey(propertyName: String, envName: String): String =
    localProperties.getProperty(propertyName)?.trim()
        ?: System.getenv(envName)?.trim()
        ?: ""

/** The Google Home APIs SDK is checked in under feature/ime/libs/googlehome; without it the flag cannot be on. */
val googleHomeSdk: Boolean = rootProject.file("feature/ime/libs/googlehome").listFiles()?.any { it.name.endsWith(".aar") } == true ||
    rootProject.file("feature/ime/libs/googlehome/m2/com/google/android/gms/play-services-home").isDirectory

fun flag(propertyName: String, envName: String): Boolean =
    (providers.gradleProperty(propertyName).orNull
        ?: localProperties.getProperty(propertyName)
        ?: System.getenv(envName)
        ?: "false").toBoolean()


android {
    namespace = "com.wasimaster.wmkeyboard.config"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }
    defaultConfig {
        minSdk = 24
        // A library module gets no version from the app, so these have to be
        // stated. They come from gradle.properties, the same place :app reads,
        // because DebugLog stamps them on every crash report: hardcoded copies
        // drifted and reported "1.0 (1)" for every build ever shipped.
        buildConfigField("int", "VERSION_CODE", providers.gradleProperty("wmkb.versionCode").get())
        buildConfigField("String", "VERSION_NAME", "\"${providers.gradleProperty("wmkb.versionName").get()}\"")
        buildConfigField("String", "KLIPY_API_KEY", "\"${apiKey("wmkb.klipyApiKey", "WMKB_KLIPY_API_KEY")}\"")
        buildConfigField("String", "GIPHY_API_KEY", "\"${apiKey("wmkb.giphyApiKey", "WMKB_GIPHY_API_KEY")}\"")
        buildConfigField("String", "BRAVE_API_KEY", "\"${apiKey("wmkb.braveApiKey", "WMKB_BRAVE_API_KEY")}\"")
        buildConfigField("String", "TRANSLATE_API_KEY", "\"${apiKey("wmkb.translateApiKey", "WMKB_TRANSLATE_API_KEY")}\"")
        buildConfigField("String", "UNSPLASH_API_KEY", "\"${apiKey("wmkb.unsplashApiKey", "WMKB_UNSPLASH_API_KEY")}\"")
        buildConfigField("String", "PEXELS_API_KEY", "\"${apiKey("wmkb.pexelsApiKey", "WMKB_PEXELS_API_KEY")}\"")
        // The SmartThings OAuth app this build signs in through (smart home
        // tool). Blank leaves only the pasted-token way in.
        buildConfigField("String", "SMARTTHINGS_CLIENT_ID", "\"${apiKey("wmkb.smartThingsClientId", "WMKB_SMARTTHINGS_CLIENT_ID")}\"")
        buildConfigField("String", "SMARTTHINGS_CLIENT_SECRET", "\"${apiKey("wmkb.smartThingsClientSecret", "WMKB_SMARTTHINGS_CLIENT_SECRET")}\"")
        buildConfigField("Boolean", "ENABLE_PLAY_STORE", "${flag("wmkb.enablePlayStore", "WMKB_ENABLE_PLAY_STORE")}")
        buildConfigField("Boolean", "ENABLE_FDROID", "${flag("wmkb.enableFdroid", "WMKB_ENABLE_FDROID")}")
        // Diagnostic builds only: on an uncaught exception, replace Android's
        // "app has stopped" dialog with a screen showing the stack trace, with
        // buttons to copy or share it. For getting a trace off a device that
        // belongs to someone else — no adb, no developer options, and a crash
        // at startup means the in-app log screen is unreachable. Off by default;
        // build with -Pwmkb.enableCrashScreen=true.
        buildConfigField("Boolean", "ENABLE_CRASH_SCREEN", "${flag("wmkb.enableCrashScreen", "WMKB_ENABLE_CRASH_SCREEN")}")
    }

    flavorDimensions += "capabilities"
    productFlavors {
        create("full") {
            dimension = "capabilities"
            buildConfigField("Boolean", "ENABLE_ML_KIT_HANDWRITING", "true")
            buildConfigField("Boolean", "ENABLE_ML_KIT_SCANNERS", "true")
            buildConfigField("Boolean", "ENABLE_GRAMMAR", "true")
            buildConfigField("Boolean", "ENABLE_LOCAL_LLM", "true")
            buildConfigField("Boolean", "ENABLE_WHISPER", "true")
            // The Google Home APIs: their SDK is a full-only dependency, and
            // the grant only works for a signing certificate that has an
            // OAuth client, so each channel says whether it carries one.
            buildConfigField("Boolean", "ENABLE_GOOGLE_HOME", "${flag("wmkb.enableGoogleHome", "WMKB_ENABLE_GOOGLE_HOME") && googleHomeSdk}")
        }
        create("lite") {
            dimension = "capabilities"
            buildConfigField("Boolean", "ENABLE_ML_KIT_HANDWRITING", "false")
            buildConfigField("Boolean", "ENABLE_ML_KIT_SCANNERS", "false")
            // Harper is open source and needs no Play services, so lite has it
            // too; only the ML Kit and LiteRT features stay full-only.
            buildConfigField("Boolean", "ENABLE_GRAMMAR", "true")
            buildConfigField("Boolean", "ENABLE_LOCAL_LLM", "false")
            buildConfigField("Boolean", "ENABLE_WHISPER", "false")
            buildConfigField("Boolean", "ENABLE_GOOGLE_HOME", "false")
        }
    }
    buildFeatures { buildConfig = true }
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
}
