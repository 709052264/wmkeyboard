import javax.inject.Inject
import org.gradle.api.DefaultTask
import org.gradle.api.file.ArchiveOperations
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Copies the Qualcomm NPU runtime's arm64 libraries out of their AARs into
 * an assets directory, `npu/qualcomm/<file>.bin`, for the
 * `wmkeyboard.npu-runtime` convention. Assets rather than jniLibs: AGP would
 * store native libraries uncompressed (about 50 MB for two chip generations,
 * against about 14 compressed), and the runtime needs them as plain files on
 * disk anyway, which `NpuRuntime` in :core:intelligence unpacks them into.
 * The `.bin` suffix is what gets them compressed: AGP stores any `.so`
 * uncompressed, in assets as well (measured: 50 MB in the APK without it).
 *
 * Fails when a listed library is in neither AAR, so a version bump that
 * renames one breaks the build instead of shipping an NPU that cannot start.
 */
abstract class NpuRuntimeAssets : DefaultTask() {

    /** The AARs: LiteRT's Qualcomm dispatch runtime and Qualcomm's QNN runtime. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archives: ConfigurableFileCollection

    /** File names to take from the AARs' `jni/arm64-v8a/`. */
    @get:Input
    abstract val libraries: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val archiveOperations: ArchiveOperations

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun unpack() {
        val names = libraries.get()
        val wanted = names.map { "jni/arm64-v8a/$it" }
        fileSystemOperations.sync {
            for (archive in archives.files) {
                from(archiveOperations.zipTree(archive)) {
                    include(wanted)
                    eachFile { path = "npu/qualcomm/$name.bin" }
                }
            }
            includeEmptyDirs = false
            into(outputDir)
        }
        val out = outputDir.get().asFile.resolve("npu/qualcomm")
        val missing = names.filterNot { out.resolve("$it.bin").isFile }
        check(missing.isEmpty()) { "NPU runtime libraries missing from ${archives.files.map { it.name }}: $missing" }
    }
}
