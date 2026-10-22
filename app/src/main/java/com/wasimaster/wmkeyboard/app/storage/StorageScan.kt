package com.wasimaster.wmkeyboard.app.storage

import com.wasimaster.wmkeyboard.core.download.FileDownloader
import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import com.wasimaster.wmkeyboard.core.directboot.DirectBoot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File

/**
 * Measures what the app is using on disk.
 *
 * The headline number has to be the same one the system shows under Settings →
 * Apps → Storage, or the screen is worse than useless: a user who sees 4 GB
 * here and 6 GB there trusts neither. So the totals are not ours at all — they
 * come from [StorageStatsManager], which is what the system's own page reads.
 * Only the *breakdown* is ours, and whatever our categories fail to account for
 * is shown as a residual rather than quietly dropped. The bar therefore always
 * adds up to the system's figure.
 *
 * Three things a naive `File.length()` walk gets wrong, all handled here:
 *
 *  * **Apparent size is not disk usage.** A 40-byte SVG in an icon pack still
 *    costs a whole filesystem block. With a few thousand stickers and icons the
 *    difference runs to megabytes, which is enough to make the residual look
 *    like a bug. Every file is rounded up to the block size, `du`-style.
 *  * **`dataDir/lib` is a symlink** into `/data/app`, and following it counts
 *    the native libraries a second time — they are already in `appBytes`.
 *  * **Direct boot storage is real storage.** `queryStatsForPackage` covers the
 *    device-protected tree as well as the credential-encrypted one, so anything
 *    that only walks `filesDir` under-reports and inflates the residual.
 */

/** The six roots everything is measured under, plus the filesystem block size. */
internal data class StorageRoots(
    /** Credential-encrypted `files/`; readable only once the user has unlocked. */
    val files: File,
    val cache: File,
    val codeCache: File,
    /**
     * `no_backup/`, where the downloaded app update waits to be installed. Its
     * own root because it is the one place in the app holding a file measured
     * in tens of megabytes that the user did not ask to keep, and the cache is
     * not an option for it: Android empties the cache under exactly the
     * storage pressure a download that size creates.
     */
    val noBackupFiles: File,
    /** Device-protected `files/`; readable from boot. See [DirectBoot]. */
    val deFiles: File,
    val deCache: File,
    val prefs: File,
    val dePrefs: File,
    val dataDir: File,
    val deDataDir: File,
    val blockSize: Long,
    /** False before first unlock, when the credential-encrypted roots cannot be read. */
    val unlocked: Boolean,
    /** The installed package's split APKs, Play's on-demand modules among them. */
    val appSplits: List<File> = emptyList(),
)

internal fun storageRoots(context: Context): StorageRoots {
    val de = DirectBoot.deviceContext(context)
    val dataDir = context.dataDir
    return StorageRoots(
        files = context.filesDir,
        cache = context.cacheDir,
        codeCache = context.codeCacheDir,
        noBackupFiles = context.noBackupFilesDir,
        deFiles = de.filesDir,
        deCache = de.cacheDir,
        prefs = File(dataDir, "shared_prefs"),
        dePrefs = File(de.dataDir, "shared_prefs"),
        dataDir = dataDir,
        deDataDir = de.dataDir,
        blockSize = runCatching { StatFs(dataDir.path).blockSizeLong }
            .getOrNull()?.takeIf { it > 0 } ?: DEFAULT_BLOCK,
        unlocked = DirectBoot.isUserUnlocked(context),
        appSplits = context.applicationInfo.splitSourceDirs.orEmpty().map(::File),
    )
}

private const val DEFAULT_BLOCK = 4096L

/** One category's measured share. */
internal data class CategorySize(val bytes: Long, val items: Int)

/**
 * A snapshot of the whole picture. Emitted repeatedly while a scan runs, with
 * [scanning] true until the last category has been measured.
 */
internal data class StorageReport(
    val appBytes: Long = 0L,
    val dataBytes: Long = 0L,
    val cacheBytes: Long = 0L,
    val freeBytes: Long = 0L,
    val deviceBytes: Long = 0L,
    val sizes: Map<String, CategorySize> = emptyMap(),
    /**
     * False when the totals are our own estimate rather than the system's.
     * Only happens below API 26, where [StorageStatsManager] does not exist.
     */
    val exact: Boolean = true,
    val scanning: Boolean = true,
    /** How far through the categories a running scan is, 0..1. */
    val progress: Float = 0f,
    /**
     * The part of [appBytes] that is Play's on-demand modules, shown in their
     * own row rather than in App package's. Code, not data, so it is never
     * part of what the data rows have to add up to.
     */
    val moduleSplitBytes: Long = 0L,
) {
    /** App package's own row: the app, without the modules listed beside it. */
    val packageBytes: Long get() = (appBytes - moduleSplitBytes).coerceAtLeast(0L)

    /** What the system's App info page calls "Total". */
    val totalBytes: Long get() = appBytes + dataBytes

    /** What it calls "User data": everything but the cache. */
    val userDataBytes: Long get() = (dataBytes - cacheBytes).coerceAtLeast(0L)

    /**
     * How much of the device is in use. Derived from the capacity rather than
     * measured, so this and [freeBytes] add up to [deviceBytes] exactly — which
     * is what the device's own storage screen shows, and being a gigabyte out
     * from it would undo the point of the whole screen.
     */
    val deviceUsedBytes: Long get() = (deviceBytes - freeBytes).coerceAtLeast(0L)

    fun bytesOf(id: String): Long = sizes[id]?.bytes ?: 0L

    fun itemsOf(id: String): Int = sizes[id]?.items ?: 0
}

/**
 * The last complete measurement, held for the life of the process.
 *
 * Walking the whole data tree takes about a second, and re-deriving it every
 * time the user steps back out of a category made the screen rebuild itself in
 * front of them: rows reordering as sizes landed, the ring replaying its grow,
 * and the scroll position collapsing because the list was briefly short enough
 * to have nowhere to scroll to. None of that told anyone anything — the numbers
 * were seconds old and identical.
 *
 * So the screen paints from here first and only measures again when there is a
 * reason to: a deletion, a deliberate refresh, or enough time passing that the
 * figures could have moved on their own (a download finishing behind the app).
 * A re-measure that follows a paint never replaces the numbers until it has all
 * of them, so the screen updates in one step instead of shuffling.
 */
internal object StorageSnapshot {

    /** The most recent complete report, or null before the first scan. */
    @Volatile
    var last: StorageReport? = null
        private set

    @Volatile
    private var takenAt = 0L

    @Volatile
    private var stale = true

    fun publish(report: StorageReport) {
        last = report
        takenAt = SystemClock.elapsedRealtime()
        stale = false
    }

    /** Something was deleted: what is on screen is no longer true. */
    fun invalidate() {
        stale = true
    }

    /** Whether re-entering the screen can skip measuring entirely. */
    fun isFresh(): Boolean =
        !stale && last != null && SystemClock.elapsedRealtime() - takenAt < FRESH_FOR_MS

    private const val FRESH_FOR_MS = 30_000L
}

/**
 * Measures every category, emitting as it goes so the screen fills in rather
 * than staring at a spinner: the totals land immediately, then one category at
 * a time, largest trees last.
 */
internal fun scanStorage(
    context: Context,
    categories: List<StorageCategory> = StorageCategories.all,
): Flow<StorageReport> = flow {
    val roots = storageRoots(context)
    // The app's own size arrives with the totals, so it can be shown at once
    // rather than sitting at zero until the walk finishes.
    val sizes = linkedMapOf(
        StorageCategories.APP_PACKAGE to CategorySize(0L, 0),
    )
    var report = totals(context, roots).copy(
        moduleSplitBytes = installedModuleSplits(roots).sumOf { diskUsage(it, roots.blockSize) },
    )
    sizes[StorageCategories.APP_PACKAGE] = CategorySize(report.packageBytes, 0)
    report = report.copy(sizes = LinkedHashMap(sizes))
    emit(report)
    val walked = categories.filterNot { it.synthetic }
    walked.forEachIndexed { index, category ->
        val paths = category.paths(roots)
        sizes[category.id] = CategorySize(
            bytes = paths.sumOf { diskUsage(it, roots.blockSize) },
            items = category.count(roots),
        )
        report = report.copy(
            sizes = LinkedHashMap(sizes),
            progress = (index + 1).toFloat() / walked.size,
        )
        emit(report)
    }
    emit(report.withResiduals(categories).copy(scanning = false, progress = 1f))
}.flowOn(Dispatchers.IO)

/**
 * The two read-only rows that make the breakdown add up: whatever the system
 * reports minus everything we could name. Clamped at zero, because block
 * accounting can make our walk marginally overshoot on a category the system
 * counts differently, and a negative row would be nonsense.
 */
internal fun StorageReport.withResiduals(categories: List<StorageCategory>): StorageReport {
    val measured = categories.filterNot { it.synthetic }
    val cacheSum = measured.filter { it.group == StorageGroup.CACHE }.sumOf { bytesOf(it.id) }
    // The modules' row counts their installed splits, which are the app's
    // code and not its data.
    val dataSum = measured.filterNot { it.group == StorageGroup.CACHE }.sumOf { bytesOf(it.id) } - moduleSplitBytes
    val withApp = sizes + (StorageCategories.APP_PACKAGE to CategorySize(packageBytes, 0))
    return copy(
        sizes = withApp +
            (StorageCategories.OTHER_CACHE to CategorySize((cacheBytes - cacheSum).coerceAtLeast(0L), 0)) +
            (StorageCategories.OTHER_DATA to CategorySize((userDataBytes - dataSum).coerceAtLeast(0L), 0)),
    )
}

// ---- totals ----

private fun totals(context: Context, roots: StorageRoots): StorageReport {
    val stats = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) systemTotals(context) else null
    val device = deviceTotals(context, roots)
    return stats?.copy(freeBytes = device.first, deviceBytes = device.second)
        ?: estimatedTotals(context, roots).copy(freeBytes = device.first, deviceBytes = device.second)
}

/**
 * The system's own figures. No permission is needed to ask about our own
 * package — `PACKAGE_USAGE_STATS` is only for asking about somebody else's, and
 * this screen must keep working whether or not the user granted it for the
 * clipboard's "show source app" setting.
 */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.O)
private fun systemTotals(context: Context): StorageReport? = runCatching {
    val manager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
    val stats = manager.queryStatsForPackage(
        StorageManager.UUID_DEFAULT,
        context.packageName,
        Process.myUserHandle(),
    )
    StorageReport(
        appBytes = stats.appBytes,
        // getDataBytes already includes the cache; the system's page subtracts
        // it to show "User data", and so does [StorageReport.userDataBytes].
        dataBytes = stats.dataBytes,
        cacheBytes = stats.cacheBytes,
    )
}.getOrNull()

/** The system's figure for the installed app, or null below API 26 or when it will not say. */
internal fun systemAppBytes(context: Context): Long? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) systemTotals(context)?.appBytes else null

/** Below API 26 there is no [StorageStatsManager], so measure it ourselves. */
private fun estimatedTotals(context: Context, roots: StorageRoots): StorageReport {
    val info = context.applicationInfo
    val code = buildList {
        add(File(info.sourceDir))
        info.splitSourceDirs?.forEach { add(File(it)) }
        info.nativeLibraryDir?.let { add(File(it)) }
    }
    val cache = diskUsage(roots.cache, roots.blockSize) +
        diskUsage(roots.codeCache, roots.blockSize) +
        diskUsage(roots.deCache, roots.blockSize)
    return StorageReport(
        appBytes = code.sumOf { diskUsage(it, roots.blockSize) },
        dataBytes = diskUsage(roots.dataDir, roots.blockSize) +
            diskUsage(roots.deDataDir, roots.blockSize),
        cacheBytes = cache,
        exact = false,
    )
}

/** Free and total bytes on the volume the app lives on: `free to total`. */
private fun deviceTotals(context: Context, roots: StorageRoots): Pair<Long, Long> {
    val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        runCatching {
            val manager = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
            manager.getFreeBytes(StorageManager.UUID_DEFAULT) to
                manager.getTotalBytes(StorageManager.UUID_DEFAULT)
        }.getOrNull()
    } else {
        null
    } ?: runCatching {
        val fs = StatFs(roots.dataDir.path)
        fs.availableBlocksLong * fs.blockSizeLong to fs.blockCountLong * fs.blockSizeLong
    }.getOrDefault(0L to 0L)
    return raw.first to marketingCapacity(raw.second)
}

/**
 * The capacity a phone is sold as, from whatever its storage actually reports.
 *
 * `getTotalBytes` is documented as a display-ready figure, but what it hands
 * back is the medium's own size, and vendors disagree about the units: this
 * S25 returns exactly 256 GiB for a phone sold as 256 GB, which printed as
 * "274.88 GB" — a number that appears nowhere else on the device and reads as
 * a bug. Snapping to the nearest power-of-two capacity, in whichever unit the
 * vendor used, and then reporting it in the decimal units everything else on
 * this screen uses, gets back to the number on the box.
 */
internal fun marketingCapacity(bytes: Long): Long {
    if (bytes <= 0L) return 0L
    var size = 1L
    while (size <= MAX_CAPACITY_GB) {
        val decimal = size * 1_000_000_000L
        val binary = size shl 30
        if (bytes <= decimal || bytes <= binary) return decimal
        size *= 2
    }
    return bytes
}

private const val MAX_CAPACITY_GB = 4096L

// ---- walking ----

/**
 * Disk usage of one path, the way `du` counts it: every file rounded up to a
 * whole block, one block per directory, symlinks measured as nothing and never
 * followed.
 */
internal fun diskUsage(root: File, blockSize: Long = DEFAULT_BLOCK): Long {
    if (!root.exists() || isLink(root)) return 0L
    if (root.isFile) return blocksFor(root.length(), blockSize)
    var total = 0L
    val pending = ArrayDeque<File>()
    pending.addLast(root)
    while (pending.isNotEmpty()) {
        val dir = pending.removeLast()
        total += blockSize
        val children = dir.listFiles() ?: continue
        for (child in children) {
            if (isLink(child)) continue
            if (child.isDirectory) {
                pending.addLast(child)
            } else {
                // A paused download's `.part` is full length from its first byte; count what arrived.
                val bytes = if (child.name.endsWith(".part")) FileDownloader.bytesDone(child) else child.length()
                total += blocksFor(bytes, blockSize)
            }
        }
    }
    return total
}

private fun blocksFor(bytes: Long, blockSize: Long): Long =
    if (bytes <= 0L) 0L else ((bytes + blockSize - 1) / blockSize) * blockSize

/**
 * Whether a path is a symbolic link.
 *
 * The obvious test — canonical path differs from absolute path — is wrong here.
 * `/data/user/0` is itself a link to `/data/data`, so under it *every* path
 * looks like a link. Comparing the canonical parents instead asks the question
 * that actually matters: does this entry live where it claims to.
 */
private fun isLink(file: File): Boolean = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        java.nio.file.Files.isSymbolicLink(file.toPath())
    } else {
        val parent = file.parentFile?.canonicalFile ?: return@runCatching false
        file.canonicalFile.parentFile != parent
    }
}.getOrDefault(false)

/** Direct children of [dir], or an empty list when it is missing or unreadable. */
internal fun childrenOf(dir: File): List<File> = dir.listFiles()?.toList().orEmpty()

/** Every file under [root], flattened, links skipped. Used to list a category's items. */
internal fun filesUnder(root: File): List<File> {
    if (!root.exists() || isLink(root)) return emptyList()
    if (root.isFile) return listOf(root)
    val out = ArrayList<File>()
    val pending = ArrayDeque<File>()
    pending.addLast(root)
    while (pending.isNotEmpty()) {
        val children = pending.removeLast().listFiles() ?: continue
        for (child in children) {
            if (isLink(child)) continue
            if (child.isDirectory) pending.addLast(child) else out += child
        }
    }
    return out
}
