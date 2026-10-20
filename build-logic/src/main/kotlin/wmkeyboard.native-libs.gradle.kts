import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension

// The jniLibs of a core module that keeps libraries of on-demand gates
// (native/gates.json), per channel. Apply after com.android.library.
//
// - Play builds leave the module's src/main/jniLibs out, so out of the base
//   APK, when a gate's library lives there, and never add the module's other
//   gate directories (src/<name>/jniLibs): the gate's on-demand :feature:<id>
//   split packages the same directories instead (wmkeyboard.native-split).
// - Every other build adds those other directories here, each its own so a
//   Play split can carry it alone. A build with native delivery pins them
//   instead (wmkeyboard.native-pins), which excludes them from the APK again.
//
// The files stay where they are because the native build scripts and F-Droid's
// recipe write and scan exactly these paths.
//
// Through the new DSL's extension type, not the `android` accessor's
// `sourceSets`: under AGP 9 that one is typed as the old API's
// AndroidLibrarySourceSet, and the container's objects fail the cast at
// configuration time.

val root = rootDir.toPath()
val here = root.relativize(projectDir.toPath()).toString().replace('\\', '/')
val dirs = NativeGates.of(project).ownLibraries
    .map { (_, library) -> library.dir }
    .distinct()
    .filter { it.startsWith("$here/src/") }
    .map { it.removePrefix("$here/") }
check(dirs.isNotEmpty()) { "${project.path} keeps no library of native/gates.json" }

val playStoreChannel = (wmkbFlag("wmkb.enablePlayStore", "WMKB_ENABLE_PLAY_STORE") ?: "false").toBoolean()

if (playStoreChannel && "src/main/jniLibs" in dirs) {
    // `main` exists from the moment com.android.library is applied.
    extensions.getByType(LibraryExtension::class.java).sourceSets
        .getByName("main").jniLibs.setSrcDirs(emptyList<String>())
}

extensions.getByType(LibraryAndroidComponentsExtension::class.java).onVariants { variant ->
    if (!playStoreChannel) {
        for (dir in dirs - "src/main/jniLibs") variant.sources.jniLibs?.addStaticSourceDirectory(dir)
    }
}
