package com.wasimaster.wmkeyboard.core.modules

import android.content.Context
import android.system.Os
import com.wasimaster.wmkeyboard.core.debug.DebugLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Native delivery for the builds Play Feature Delivery does not reach: the
 * GitHub build, and the F-Droid build once the user opts in.
 *
 * Such a build leaves a set of native libraries out of the APK and writes a
 * [NativePins] table in their place. [install] reads it at startup and puts
 * one [DownloadedModuleGate] behind each gate it names, the same
 * [ModuleGate] shape Play's modules use, so every facade, banner and
 * `awaitInstalled` caller works unchanged: a missing library is fetched the
 * first time its feature asks, verified against its pin, and loaded from
 * app storage.
 *
 * Two kinds of loading:
 * - the project's own libraries go through [FeatureModules.loadLibrary],
 *   which [install] points at the downloaded files;
 * - a library a dependency loads itself with `System.loadLibrary` (ML Kit,
 *   LiteRT, LiteRT-LM) cannot be redirected, so its facade calls [preload]
 *   first: a plain `dlopen` from the always-shipped engine library puts it in
 *   the class loader's namespace under its soname, where the dependency's own
 *   call finds it.
 *
 * Nothing here runs on a build without the pin table, which is every Play
 * build and every build that bundles its libraries.
 */
object NativeDelivery {
    private const val TAG = "NativeDelivery"

    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    private var store: NativeStore? = null

    @Volatile
    private var pins: List<NativePin> = emptyList()

    /** Every gate this build delivers, by name. */
    private val gates = HashMap<String, DownloadedModuleGate>()

    @Volatile
    private var dlopen: ((String) -> String?)? = null

    private val preloaded = HashSet<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** This process's ABI, in the jniLibs spelling the pins use. */
    @Volatile
    var abi: String = ""
        private set

    /**
     * Reads the pin table, if this build has one, and installs the gates.
     * [needOptIn] is true on F-Droid. [dlopen] is the engine library's plain
     * `dlopen`, handed in from the module above this one.
     */
    fun install(context: Context, needOptIn: Boolean, dlopen: (String) -> String?) {
        val app = context.applicationContext
        val text = runCatching { app.assets.open(NativePins.ASSET).bufferedReader().use { it.readText() } }.getOrNull()
            ?: return
        abi = processAbi(app)
        val mine = NativePins.parse(text).filter { it.abi == abi }
        if (mine.isEmpty()) return
        NativeConsent.attach(app, needOptIn)
        val nativeStore = NativeStore(File(app.noBackupFilesDir, "natives"))
        store = nativeStore
        pins = mine
        this.dlopen = dlopen
        synchronized(gates) {
            for ((name, gatePins) in mine.groupBy { it.gate }) {
                gates[name] = DownloadedModuleGate(name, gatePins, nativeStore, scope)
            }
        }
        gate("speech")?.let { FeatureModules.speech = it }
        gate("onnx")?.let { FeatureModules.onnx = it }
        gate("grammar")?.let { FeatureModules.grammar = it }
        gate("ocr")?.let { FeatureModules.ocr = it }
        gate("litert")?.let { FeatureModules.litert = it }
        gate("handwriting")?.let { FeatureModules.handwriting = it }
        gate("scanner")?.let { FeatureModules.scanner = it }
        gate("npu")?.let { FeatureModules.npu = it }
        FeatureModules.loadLibrary = { name ->
            val file = fileFor(name)
            if (file != null) System.load(file.path) else System.loadLibrary(name)
        }
        active = true
        // A library a previous build pinned and this one does not is dead weight.
        scope.launch { runCatching { nativeStore.prune(mine) } }
    }

    /** The gate named [name], for the facades that keep their own gate type (LLM, translate). */
    fun gate(name: String): ModuleGate? = synchronized(gates) { gates[name] }

    /** Every pin of this build for this ABI, for import and the storage screen. */
    fun pins(): List<NativePin> = pins

    fun store(): NativeStore? = store

    /**
     * The downloaded file of `lib<name>.so`, or null when this build ships it
     * (or has not got it yet).
     */
    fun fileFor(name: String): File? {
        val nativeStore = store ?: return null
        val pin = pins.firstOrNull { it.name == "lib$name.so" } ?: return null
        return nativeStore.fileOf(pin).takeIf { nativeStore.installed(pin) }
    }

    /**
     * Loads `lib<name>.so` into the class loader's namespace with a plain
     * `dlopen`, so the dependency that calls `System.loadLibrary(name)` finds
     * it. True when it is loaded or the APK ships it; false when this build
     * delivers it and it is not here yet, or the linker refused it.
     */
    fun preload(name: String): Boolean {
        if (!active || pins.none { it.name == "lib$name.so" }) return true
        synchronized(preloaded) {
            if (name in preloaded) return true
            val file = fileFor(name) ?: return false
            val error = dlopen?.invoke(file.path)
            if (error != null) {
                DebugLog.w(TAG, "dlopen lib$name.so failed: $error")
                return false
            }
            preloaded += name
            return true
        }
    }

    /**
     * A directory holding every library of [gates] under its own name, for a
     * runtime that is handed a directory rather than files (LiteRT's NPU
     * dispatch, and the DSP side of QNN, which reads its skel by name from a
     * search path). Links into the store, or copies where links are refused.
     * Null until every library of those gates is installed.
     */
    fun directoryFor(gates: List<String>): File? {
        val nativeStore = store ?: return null
        val gatePins = pins.filter { it.gate in gates }
        if (gatePins.isEmpty() || !gatePins.all(nativeStore::installed)) return null
        val dir = File(nativeStore.root, "dirs/${gates.joinToString("+")}")
        dir.mkdirs()
        for (pin in gatePins) {
            val target = nativeStore.fileOf(pin)
            val link = File(dir, pin.name)
            if (link.exists() && runCatching { link.canonicalPath == target.canonicalPath }.getOrDefault(false)) continue
            link.delete()
            val linked = runCatching { Os.symlink(target.path, link.path) }.isSuccess
            if (!linked) target.copyTo(link, overwrite = true)
        }
        return dir
    }

    /**
     * Installs [file] when its hash is one of this build's pins, for an
     * import with no network. Returns the pin it matched, or null.
     */
    fun import(file: File): NativePin? {
        val nativeStore = store ?: return null
        val pin = nativeStore.pinFor(file, pins) ?: return null
        if (!nativeStore.install(pin, file, keepSource = true)) return null
        gate(pin.gate)?.let { (it as? DownloadedModuleGate)?.refresh() }
        return pin
    }

    /** `arm64` → `arm64-v8a`: the ABI this process loads its own libraries for. */
    private fun processAbi(context: Context): String =
        when (File(context.applicationInfo.nativeLibraryDir).name) {
            "arm64" -> "arm64-v8a"
            "arm" -> "armeabi-v7a"
            "x86_64" -> "x86_64"
            "x86" -> "x86"
            else -> android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty()
        }
}

/**
 * A [ModuleGate] whose module is a set of [NativePin]s downloaded into a
 * [NativeStore]. Installed when every pin is; [requestInstall] fetches the
 * missing ones in order and reports their combined bytes, or stops at the
 * first agreement [NativeConsent] says the user has not given yet.
 */
class DownloadedModuleGate(
    val name: String,
    private val pins: List<NativePin>,
    private val store: NativeStore,
    private val scope: CoroutineScope,
) : ModuleGate {

    private val mutable = MutableStateFlow<ModuleState>(current())

    override val state: StateFlow<ModuleState> = mutable.asStateFlow()

    override val onDemand: Boolean get() = true

    /** Bytes still to fetch. */
    val missingBytes: Long get() = pins.filterNot(store::installed).sumOf { it.size }

    /** The licences this gate's downloads need, if any. */
    val licences: List<String> get() = pins.mapNotNull { it.licence }.distinct()

    @Volatile private var job: Job? = null

    private fun current(): ModuleState = if (pins.all(store::installed)) ModuleState.Installed else ModuleState.Missing()

    /** Re-reads the store, after an import. */
    fun refresh() {
        if (job?.isActive != true) mutable.value = current()
    }

    @Synchronized
    override fun requestInstall() {
        if (pins.all(store::installed)) {
            mutable.value = ModuleState.Installed
            return
        }
        if (job?.isActive == true) return
        NativeConsent.missingFor(pins)?.let { needed ->
            mutable.value = ModuleState.Missing(failed = true, consent = needed)
            return
        }
        val todo = pins.filterNot(store::installed)
        val total = todo.sumOf { it.size }
        mutable.value = ModuleState.Installing(0, total)
        job = scope.launch {
            var done = 0L
            mutable.value = try {
                for (pin in todo) {
                    NativeDownloader.fetch(pin, store) { bytes -> mutable.value = ModuleState.Installing(done + bytes, total) }
                    done += pin.size
                }
                ModuleState.Installed
            } catch (e: CancellationException) {
                throw e
            } catch (@Suppress("TooGenericExceptionCaught") t: Throwable) {
                DebugLog.w("NativeDelivery", "fetching $name failed: ${t.javaClass.simpleName}: ${t.message}")
                ModuleState.Missing(failed = true)
            }
        }
    }
}
