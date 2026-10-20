import com.android.build.api.variant.LibraryAndroidComponentsExtension
import io.gitlab.arturbosch.detekt.Detekt
import java.util.Properties

// Type-resolved detekt for a library module. detekt 1.23's Android integration
// targets the AGP 7/8 variant API and registers no `detekt<Variant>` tasks
// under AGP 9, so this plugin registers them by hand — same approach as the
// hand-rolled tasks in :app, generalized over whatever debug variants the
// module declares (detektDebug everywhere: no library has a flavour).
//
// Each module analyses only its own sources, against its own compile
// classpath. Analysing another module's sources with this module's classpath
// (the pre-modular sweep) makes detekt see those classes both as source and as
// binary, which produces false positives (UnreachableCode storms) — hence one
// task per module rather than one repo-wide task.

plugins {
    id("io.gitlab.arturbosch.detekt")
}

// detekt.yml configures the `formatting` ruleset, which lives in a separate
// artifact — without it on detektPlugins every task fails config validation.
// Version tracks build-logic/build.gradle.kts (and the main catalog).
dependencies {
    "detektPlugins"("io.gitlab.arturbosch.detekt:detekt-formatting:1.23.8")
}

detekt {
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    buildUponDefaultConfig = true
    allRules = false
    parallel = true
    ignoreFailures = false
    basePath = rootProject.projectDir.absolutePath
}

// The modules with ML Kit code keep it in src/google, and F-Droid's stand-ins
// for it in src/nogoogle; a build compiles one or the other (see
// :core:intelligence), so it analyses that one.
val googleSdks = run {
    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.exists()) file.inputStream().use { load(it) }
    }
    !(providers.gradleProperty("wmkb.enableFdroid").orNull
        ?: localProperties.getProperty("wmkb.enableFdroid")
        ?: System.getenv("WMKB_ENABLE_FDROID")
        ?: "false").toBoolean()
}

val components = extensions.getByType(LibraryAndroidComponentsExtension::class.java)
components.onVariants { variant ->
    if (variant.buildType != "debug") return@onVariants
    val variantName = variant.name.replaceFirstChar { it.uppercase() }
    // The variant's resolved compile classpath. Taken from the variant API
    // rather than the compile task (`compileDebugKotlin`), which the
    // built-in-Kotlin AGP registers later than this callback runs.
    val variantClasspath = variant.compileClasspath
    val sourceDirs = buildList {
        add("src/main/java")
        add(if (googleSdks) "src/google/java" else "src/nogoogle/java")
    }

    tasks.register("detekt$variantName", Detekt::class.java) {
        description = "Runs detekt with type resolution over this module's $variantName sources."
        group = "verification"

        setSource(files(sourceDirs.map { layout.projectDirectory.dir(it) }))
        include("**/*.kt")
        exclude("**/build/**", "**/resources/**")

        classpath.setFrom(
            variantClasspath,
            components.sdkComponents.bootClasspath,
        )

        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        parallel = true
        ignoreFailures = false
        basePath = rootProject.projectDir.absolutePath
        jvmTarget = "11"

        reports {
            html.outputLocation.set(layout.buildDirectory.file("reports/detekt/detekt$variantName.html"))
            xml.outputLocation.set(layout.buildDirectory.file("reports/detekt/detekt$variantName.xml"))
            sarif.outputLocation.set(layout.buildDirectory.file("reports/detekt/detekt$variantName.sarif"))
            md.required.set(false)
            txt.required.set(false)
        }
    }
}
