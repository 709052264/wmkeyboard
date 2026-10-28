package com.wasimaster.wmkeyboard.core.settings.sink

import com.wasimaster.wmkeyboard.core.net.BackupTraffic
import com.wasimaster.wmkeyboard.core.netlog.NetLog
import com.wasimaster.wmkeyboard.core.netlog.NetSource
import com.wasimaster.wmkeyboard.core.settings.NfsConfig
import com.wasimaster.wmkeyboard.core.settings.sink.nfs.Nfs3Client
import com.wasimaster.wmkeyboard.core.settings.sink.nfs.NfsStatusException
import com.wasimaster.wmkeyboard.core.settings.sink.nfs.RpcDeniedException
import com.wasimaster.wmkeyboard.core.settings.sink.nfs.RpcUnavailableException
import com.wasimaster.wmkeyboard.core.util.runCancellable
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A [BackupSink] over a folder in an NFS export, through [Nfs3Client].
 *
 * Like SMB: a backup goes to a `.part` name and is renamed over the final
 * one, which NFS does atomically, and every verb is one short session.
 *
 * NFS without Kerberos has no sign-in and no encryption. The server trusts
 * the user and group ids the phone says it is ([NfsConfig.uid]), and the
 * file crosses the network as it is; the screen says so, and an encrypted
 * backup is the answer to the second.
 */
class NfsSink(private val config: NfsConfig) : BackupSink {

    override val id: String get() = ID

    private val parts: List<String> =
        config.path.replace('\\', '/').split('/').map { it.trim() }.filter { it.isNotEmpty() }

    /** The backup folder's handle, made level by level where missing. */
    private fun folder(nfs: Nfs3Client): ByteArray {
        var at = nfs.root
        for (part in parts) at = nfs.mkdir(at, part)
        return at
    }

    override suspend fun readiness(): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            if (!config.configured) throw BackupSinkException(SinkError.NOT_CONFIGURED)
            session { folder(it) }
            Unit
        }
    }

    override suspend fun write(
        name: String,
        mimeType: String,
        body: (OutputStream) -> Unit,
    ): Result<SinkEntry> = withContext(Dispatchers.IO) {
        runCancellable {
            val bytes = ByteArrayOutputStream().also(body).toByteArray()
            session { nfs ->
                val dir = folder(nfs)
                val part = name + AutoBackupNaming.PART_SUFFIX
                try {
                    nfs.write(nfs.create(dir, part), bytes)
                    nfs.rename(dir, part, name)
                } catch (failure: Throwable) {
                    runCatching { nfs.remove(dir, part) }
                    throw failure
                }
            }
            SinkEntry(id = name, name = name, sizeBytes = bytes.size.toLong(), modifiedAtMs = 0L)
        }
    }

    override suspend fun list(): Result<List<SinkEntry>> = withContext(Dispatchers.IO) {
        runCancellable {
            session { nfs ->
                nfs.list(folder(nfs))
                    .filter { !it.directory && AutoBackupNaming.isListed(it.name) }
                    .map { SinkEntry(id = it.name, name = it.name, sizeBytes = it.size, modifiedAtMs = it.modifiedMs) }
            }
        }
    }

    override suspend fun read(entry: SinkEntry): Result<InputStream> = withContext(Dispatchers.IO) {
        runCancellable {
            session { nfs ->
                val file = nfs.lookup(folder(nfs), entry.name)
                    ?: throw BackupSinkException(SinkError.TARGET_MISSING)
                nfs.read(file).inputStream()
            }
        }
    }

    override suspend fun delete(entry: SinkEntry): Result<Unit> = withContext(Dispatchers.IO) {
        runCancellable {
            session { nfs -> nfs.remove(folder(nfs), entry.name) }
        }
    }

    /** Mounts, runs [block], unmounts, and maps every way that can fail onto a [SinkError]. */
    private fun <T> session(block: (Nfs3Client) -> T): T {
        val host = config.host.trim()
        val export = config.export.trim()
        val call = NetLog.callTo(
            source = NetSource.BACKUP,
            method = "NFS",
            scheme = "nfs",
            host = host,
            port = config.port.takeIf { it > 0 } ?: Nfs3Client.NFS_DEFAULT_PORT,
            route = export,
            background = BackupTraffic.unattended,
        )
        var client: Nfs3Client? = null
        try {
            client = Nfs3Client.open(
                host, export, config.uid, config.gid, config.port, config.mountPort,
                CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS,
            ) { sent, received ->
                if (sent > 0) call.sent(sent)
                if (received > 0) call.received(received)
            }
            return block(client)
        } catch (failure: Throwable) {
            val error = errorOf(failure)
            BackupLog.w("nfs failed: ${failure.message.orEmpty()} => $error", failure)
            call.fail(failure)
            throw BackupSinkException(error, failure)
        } finally {
            if (client != null) {
                runCatching { client.close() }
                Nfs3Client.unmount(host, export, config.uid, config.gid, config.mountPort, CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS)
            }
            call.end()
        }
    }

    companion object {
        const val ID = "nfs"

        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 30_000

        /**
         * What an NFS failure means to the user. A refused MOUNT is nearly
         * always the export not allowing this phone, or its port: see
         * [Nfs3Client]. Public for the tests.
         */
        fun errorOf(failure: Throwable): SinkError = when (failure) {
            is BackupSinkException -> failure.reason
            is RpcDeniedException -> SinkError.PERMISSION_LOST
            is RpcUnavailableException -> SinkError.TARGET_MISSING
            is NfsStatusException -> when (failure.status) {
                Nfs3Client.NFS3ERR_PERM, Nfs3Client.NFS3ERR_ACCES, Nfs3Client.NFS3ERR_ROFS -> SinkError.PERMISSION_LOST
                Nfs3Client.NFS3ERR_NOENT, Nfs3Client.NFS3ERR_NOTDIR, Nfs3Client.NFS3ERR_STALE -> SinkError.TARGET_MISSING
                Nfs3Client.NFS3ERR_NOSPC, Nfs3Client.NFS3ERR_DQUOT, Nfs3Client.NFS3ERR_FBIG -> SinkError.OUT_OF_SPACE
                else -> SinkError.IO
            }
            else -> SinkError.IO
        }
    }
}
