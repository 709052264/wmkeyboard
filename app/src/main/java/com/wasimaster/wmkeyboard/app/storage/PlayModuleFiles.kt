package com.wasimaster.wmkeyboard.app.storage

import android.annotation.SuppressLint
import android.content.Context
import com.wasimaster.wmkeyboard.app.modules.PlayModules
import java.io.File

/**
 * Where one of Play's on-demand modules sits on disk.
 *
 * Installed for good, a module is `split_<name>.apk` in the package, with a
 * `split_<name>.config.<abi|lang|dpi>.apk` beside it for each of its config
 * splits. Installed this session and not yet merged into the package by a
 * restart, SplitCompat keeps copies of the same files under
 * `files/splitcompat/`: `<name>.apk` and `<name>.config.*.apk`, with the
 * native libraries unpacked into a `<name>…` directory. The dot after the
 * name keeps `llama` from claiming `llamagpu`'s files.
 */
internal fun installedSplitsOf(roots: StorageRoots, module: String): List<File> =
    roots.appSplits.filter { it.name == "split_$module.apk" || it.name.startsWith("split_$module.config.") }

/** SplitCompat's copies of [module], the files that hold it until the next restart. */
internal fun emulatedSplitsOf(roots: StorageRoots, module: String): List<File> {
    val found = ArrayList<File>()
    fun visit(dir: File) {
        for (child in dir.listFiles().orEmpty()) {
            when {
                // A file or a directory of the module's counts whole.
                child.ownedBy(module) -> found += child
                child.isDirectory -> visit(child)
            }
        }
    }
    visit(File(roots.files, "splitcompat"))
    return found
}

private fun File.ownedBy(module: String): Boolean = name == module || name.startsWith("$module.")

/** Every file that holds [module], wherever it is. */
internal fun moduleFiles(roots: StorageRoots, module: String): List<File> =
    installedSplitsOf(roots, module) + emulatedSplitsOf(roots, module)

/** The installed modules, in name order. Empty outside the Play build. */
internal fun installedPlayModules(): List<String> =
    runCatching { PlayModules.installed() }.getOrDefault(emptySet()).sorted()

/** The split APKs inside the package that belong to an installed module. */
internal fun installedModuleSplits(roots: StorageRoots): List<File> =
    installedPlayModules().flatMap { installedSplitsOf(roots, it) }

/**
 * The name Play shows for [module] while it downloads: the module's own
 * `dist:title`, `<module>_module_title` in the base resources. The module's
 * id when a module has none.
 */
@SuppressLint("DiscouragedApi")
internal fun playModuleTitle(context: Context, module: String): String {
    val id = context.resources.getIdentifier("${module}_module_title", "string", context.packageName)
    return if (id != 0) context.getString(id) else module
}
