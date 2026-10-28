package com.wasimaster.wmkeyboard.core.settings.sink.nfs

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * XDR (RFC 4506) and ONC RPC version 2 over TCP (RFC 5531): what NFS 3, its
 * MOUNT protocol and the portmapper are all spoken in. Everything is
 * big-endian and padded to four bytes.
 *
 * Hand-written for the same reason the SMB client is: what NFS needs of it is
 * a few hundred lines, and the Java libraries for it pull in far more.
 */
class XdrWriter {
    private val out = ByteArrayOutputStream()

    fun int(value: Int) = apply {
        out.write(value ushr 24)
        out.write(value ushr 16)
        out.write(value ushr 8)
        out.write(value)
    }

    fun long(value: Long) = apply {
        int((value ushr 32).toInt())
        int(value.toInt())
    }

    fun bool(value: Boolean) = int(if (value) 1 else 0)

    /** Fixed-length opaque data: the bytes and their padding, no length. */
    fun fixed(bytes: ByteArray) = apply {
        out.write(bytes)
        repeat(pad(bytes.size)) { out.write(0) }
    }

    /** Variable-length opaque data: the length, then [fixed]. */
    fun opaque(bytes: ByteArray) = int(bytes.size).fixed(bytes)

    fun string(value: String) = opaque(value.toByteArray(Charsets.UTF_8))

    fun bytes(): ByteArray = out.toByteArray()

    fun writeTo(stream: OutputStream) = out.writeTo(stream)

    val size: Int get() = out.size()

    companion object {
        fun pad(length: Int): Int = (4 - length % 4) % 4
    }
}

class XdrReader(private val buf: ByteArray, private var at: Int = 0) {

    fun int(): Int {
        if (at + 4 > buf.size) throw EOFException("xdr: short")
        val v = ((buf[at].toInt() and 0xff) shl 24) or ((buf[at + 1].toInt() and 0xff) shl 16) or
            ((buf[at + 2].toInt() and 0xff) shl 8) or (buf[at + 3].toInt() and 0xff)
        at += 4
        return v
    }

    fun uint(): Long = int().toLong() and 0xffffffffL

    fun long(): Long = (uint() shl 32) or uint()

    fun bool(): Boolean = int() != 0

    fun fixed(length: Int): ByteArray {
        if (length < 0 || at + length > buf.size) throw EOFException("xdr: short")
        val v = buf.copyOfRange(at, at + length)
        at += length + XdrWriter.pad(length)
        return v
    }

    fun opaque(): ByteArray = fixed(int())

    fun string(): String = opaque().toString(Charsets.UTF_8)

    fun skip(bytes: Int) {
        if (at + bytes > buf.size) throw EOFException("xdr: short")
        at += bytes
    }
}

/** The server refused the call itself: a version it does not have, or the credentials. */
class RpcDeniedException(val authError: Boolean, message: String) : IOException(message)

/** The program, version or procedure is not there. */
class RpcUnavailableException(message: String) : IOException(message)

/**
 * One TCP connection to one RPC program. Calls are sequential: send one
 * record, read records until the reply with the same transaction id.
 */
class RpcClient private constructor(
    private val socket: Socket,
    /** Bytes sent and received, for the network activity log. */
    private val onTraffic: (Long, Long) -> Unit,
) : AutoCloseable {

    private val input = DataInputStream(socket.getInputStream().buffered())
    private val output = socket.getOutputStream().buffered()
    private var xid = SecureRandom().nextInt()

    fun call(program: Int, version: Int, procedure: Int, credential: ByteArray, args: ByteArray): XdrReader {
        val id = ++xid
        val header = XdrWriter()
            .int(id)
            .int(MSG_CALL)
            .int(RPC_VERSION)
            .int(program)
            .int(version)
            .int(procedure)
            .fixed(credential)
            // Verifier: AUTH_NONE, no body.
            .int(AUTH_NONE).int(0)
        val length = header.size + args.size
        // Record marking: one fragment, the last, so the top bit is set.
        val mark = XdrWriter().int(LAST_FRAGMENT or length)
        mark.writeTo(output)
        header.writeTo(output)
        output.write(args)
        output.flush()
        onTraffic(4L + length, 0L)

        while (true) {
            val record = readRecord()
            onTraffic(0L, record.size.toLong() + 4)
            val reply = XdrReader(record)
            if (reply.int() != id) continue
            if (reply.int() != MSG_REPLY) throw IOException("rpc: not a reply")
            when (reply.int()) {
                MSG_ACCEPTED -> {
                    reply.int() // verifier flavour
                    reply.opaque() // verifier body
                    when (val status = reply.int()) {
                        ACCEPT_SUCCESS -> return reply
                        PROG_UNAVAIL, PROG_MISMATCH, PROC_UNAVAIL ->
                            throw RpcUnavailableException("rpc: program $program v$version unavailable ($status)")
                        else -> throw IOException("rpc: call not accepted ($status)")
                    }
                }
                else -> {
                    val reject = reply.int()
                    throw RpcDeniedException(reject == REJECT_AUTH_ERROR, "rpc: denied ($reject, ${runCatching { reply.int() }.getOrNull()})")
                }
            }
        }
    }

    private fun readRecord(): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val mark = input.readInt()
            val length = mark and LAST_FRAGMENT.inv()
            if (length > MAX_RECORD) throw IOException("rpc: record of $length bytes")
            val part = ByteArray(length)
            input.readFully(part)
            out.write(part)
            if (mark and LAST_FRAGMENT != 0) return out.toByteArray()
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        const val AUTH_NONE = 0
        const val AUTH_SYS = 1

        private const val RPC_VERSION = 2
        private const val MSG_CALL = 0
        private const val MSG_REPLY = 1
        private const val MSG_ACCEPTED = 0
        private const val ACCEPT_SUCCESS = 0
        private const val PROG_UNAVAIL = 1
        private const val PROG_MISMATCH = 2
        private const val PROC_UNAVAIL = 3
        private const val REJECT_AUTH_ERROR = 1
        private const val LAST_FRAGMENT = Int.MIN_VALUE
        private const val MAX_RECORD = 64 * 1024 * 1024

        fun connect(host: String, port: Int, connectTimeoutMs: Int, readTimeoutMs: Int, onTraffic: (Long, Long) -> Unit): RpcClient {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
                socket.soTimeout = readTimeoutMs
                socket.tcpNoDelay = true
            } catch (failure: Throwable) {
                runCatching { socket.close() }
                throw failure
            }
            return RpcClient(socket, onTraffic)
        }

        /**
         * AUTH_SYS: the client's word for who it is. [uid] and [gid] are what
         * the server checks file permissions against; nothing proves them.
         */
        fun authSys(uid: Int, gid: Int, machine: String = "wmkeyboard"): ByteArray {
            val body = XdrWriter()
                .int((System.currentTimeMillis() / 1000).toInt())
                .string(machine)
                .int(uid)
                .int(gid)
                .int(1).int(gid)
                .bytes()
            return XdrWriter().int(AUTH_SYS).opaque(body).bytes()
        }

        /** No credentials, for the portmapper. */
        val NONE: ByteArray = XdrWriter().int(AUTH_NONE).int(0).bytes()
    }
}
