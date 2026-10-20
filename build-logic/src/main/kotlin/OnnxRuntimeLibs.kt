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
 * Copies `libonnxruntime.so` for each ABI out of Microsoft's
 * onnxruntime-android AAR into a jniLibs directory (`<abi>/libonnxruntime.so`),
 * for the `wmkeyboard.onnxruntime` convention. Only the C runtime: the AAR's
 * Java API and its JNI library (`libonnxruntime4j_jni.so`) stay out, since
 * libwmsherpa calls the runtime's C API directly.
 *
 * Fails when an ABI's library is missing, so a version that drops one breaks
 * the build instead of shipping a Sherpa that cannot load.
 */
abstract class OnnxRuntimeLibs : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archives: ConfigurableFileCollection

    /** ABIs to copy, in the jniLibs spelling. */
    @get:Input
    abstract val abis: ListProperty<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @get:Inject
    abstract val archiveOperations: ArchiveOperations

    @get:Inject
    abstract val fileSystemOperations: FileSystemOperations

    @TaskAction
    fun unpack() {
        val wanted = abis.get()
        fileSystemOperations.sync {
            for (archive in archives.files) {
                from(archiveOperations.zipTree(archive)) {
                    include(wanted.map { "jni/$it/libonnxruntime.so" })
                    eachFile { path = path.removePrefix("jni/") }
                }
            }
            includeEmptyDirs = false
            into(outputDir)
        }
        val out = outputDir.get().asFile
        val missing = wanted.filterNot { out.resolve("$it/libonnxruntime.so").isFile }
        check(missing.isEmpty()) { "libonnxruntime.so missing for $missing in ${archives.files.map { it.name }}" }
    }
}
