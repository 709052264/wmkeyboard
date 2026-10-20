import com.android.build.api.variant.LibraryAndroidComponentsExtension

// Generates :core:common's `FeatureModules` object from native/gates.json
// (FeatureModulesTask): one ModuleGate property per gate, and the name-keyed
// views that NativeDelivery and :app's installOnDemandDelivery install the
// gates through. Applied by :core:common only.

// One task per variant: AGP points each one's output at a directory of its
// own variant's, which one shared task could not have.
extensions.getByType(LibraryAndroidComponentsExtension::class.java).onVariants { variant ->
    val task = tasks.register(
        "generateFeatureModules${variant.name.replaceFirstChar { it.uppercase() }}",
        FeatureModulesTask::class.java,
    ) {
        table.set(rootProject.layout.projectDirectory.file(NativeGates.PATH))
    }
    variant.sources.kotlin?.addGeneratedSourceDirectory(task, FeatureModulesTask::outputDir)
}
