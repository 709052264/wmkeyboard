import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension

// :app's half of native/gates.json, in every channel:
//
// - On Play, every gate's `:feature:<id>` module that settings.gradle.kts
//   included (it includes them from the same table, under the same flags) is
//   one of the app's `dynamicFeatures`: Play Feature Delivery fetches each the
//   first time its feature asks. Off Play none of them is in the build.
// - R8 keeps the JNI classes the table names (NativeKeepsTask), whichever
//   module the class lives in.
//
// Apply after com.android.application.

val gateTable = NativeGates.of(project)
val playStoreChannel = (wmkbFlag("wmkb.enablePlayStore", "WMKB_ENABLE_PLAY_STORE") ?: "false").toBoolean()

if (playStoreChannel) {
    val android = extensions.getByType(ApplicationExtension::class.java)
    for (gate in gateTable.gates) {
        val module = gate.playModule ?: continue
        if (rootProject.findProject(module) != null) android.dynamicFeatures += module
    }
}

val keeps = tasks.register("nativeGateKeeps", NativeKeepsTask::class.java) {
    table.set(rootProject.layout.projectDirectory.file(NativeGates.PATH))
    outputFile.set(layout.buildDirectory.file("generated/nativeGateKeeps/native-gates.pro"))
}

extensions.getByType(ApplicationAndroidComponentsExtension::class.java).onVariants { variant ->
    variant.proguardFiles.add(keeps.flatMap { it.outputFile })
}
