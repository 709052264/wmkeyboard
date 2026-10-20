package com.wasimaster.wmkeyboard.core.modules

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.random.Random

class NativeDeliveryTest {

    @get:Rule val temp = TemporaryFolder()

    private fun sha(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private val dataPin = NativePin(
        gate = "speech", abi = "arm64-v8a", name = "libwmexecutorch.so",
        sha256 = "a".repeat(64), size = 3_800_000, source = NativeSource.Data,
    )

    private val mavenPin = NativePin(
        gate = "npu", abi = "arm64-v8a", name = "libQnnHtp.so", sha256 = "b".repeat(64), size = 4_158_496,
        source = NativeSource.Maven(
            "https://repo1.maven.org/maven2", "com.qualcomm.qti:qnn-runtime:2.51.0", "jni/arm64-v8a/libQnnHtp.so",
        ),
        licence = NativeConsent.QUALCOMM,
    )

    @Test fun pinsRoundTrip() {
        val text = "# comment\n" + NativePins.format(dataPin) + "\n" + NativePins.format(mavenPin) + "\n"
        assertEquals(listOf(dataPin, mavenPin), NativePins.parse(text))
    }

    @Test fun malformedPinLinesAreSkipped() {
        val good = NativePins.format(dataPin)
        val text = listOf(
            good,
            good.replace("a".repeat(64), "xyz"),
            good.replace("3800000", "-1"),
            good.replace("\tdata\t", "\tftp://x\t"),
            "speech\tarm64-v8a",
        ).joinToString("\n")
        assertEquals(listOf(dataPin), NativePins.parse(text))
    }

    @Test fun mavenUrlFollowsTheRepositoryLayout() {
        val source = mavenPin.source as NativeSource.Maven
        assertEquals(
            "https://repo1.maven.org/maven2/com/qualcomm/qti/qnn-runtime/2.51.0/qnn-runtime-2.51.0.aar",
            source.url,
        )
    }

    /** A zip held in memory, read the way the downloader reads a remote one. */
    private class MemorySource(private val bytes: ByteArray) : RangeSource {
        var reads = 0
        override val length: Long get() = bytes.size.toLong()
        override fun read(offset: Long, count: Int): ByteArray {
            reads++
            return bytes.copyOfRange(offset.toInt(), offset.toInt() + count)
        }
        override fun open(offset: Long, count: Long): InputStream {
            reads++
            return ByteArrayInputStream(bytes, offset.toInt(), count.toInt())
        }
    }

    private fun zip(vararg entries: Pair<String, ByteArray>, stored: Set<String> = emptySet()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.setComment("an aar")
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                if (name in stored) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.crc = java.util.zip.CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun extractsOneDeflatedEntry() {
        val lib = Random(1).nextBytes(300_000) + ByteArray(200_000)
        val source = MemorySource(zip("classes.jar" to Random(2).nextBytes(50_000), "jni/arm64-v8a/libx.so" to lib))
        val entry = ZipRangeReader.find(source, "jni/arm64-v8a/libx.so")!!
        assertEquals(lib.size.toLong(), entry.size)
        val out = ByteArrayOutputStream()
        ZipRangeReader.extract(source, entry, out)
        assertArrayEquals(lib, out.toByteArray())
        assertNull(ZipRangeReader.find(source, "jni/x86/libx.so"))
    }

    @Test fun extractsAStoredEntry() {
        val lib = Random(3).nextBytes(10_000)
        val source = MemorySource(zip("lib.so" to lib, stored = setOf("lib.so")))
        val out = ByteArrayOutputStream()
        ZipRangeReader.extract(source, ZipRangeReader.find(source, "lib.so")!!, out)
        assertArrayEquals(lib, out.toByteArray())
    }

    @Test(expected = java.io.IOException::class)
    fun corruptDataFailsTheCrc() {
        val lib = Random(4).nextBytes(10_000)
        val bytes = zip("lib.so" to lib, stored = setOf("lib.so"))
        val source = MemorySource(bytes)
        val entry = ZipRangeReader.find(source, "lib.so")!!
        bytes[(entry.localHeaderOffset + 30 + "lib.so".length + 100).toInt()] =
            (bytes[(entry.localHeaderOffset + 30 + "lib.so".length + 100).toInt()] + 1).toByte()
        ZipRangeReader.extract(source, entry, ByteArrayOutputStream())
    }

    @Test fun storeInstallsOnlyAMatchingFile() {
        val root = temp.newFolder("natives")
        val store = NativeStore(root)
        val lib = Random(5).nextBytes(4096)
        val pin = dataPin.copy(sha256 = sha(lib), size = lib.size.toLong())
        val wrong = temp.newFile("wrong").apply { writeBytes(Random(6).nextBytes(4096)) }
        assertFalse(store.install(pin, wrong))
        assertFalse(store.installed(pin))
        val right = temp.newFile("right").apply { writeBytes(lib) }
        assertTrue(store.install(pin, right))
        assertTrue(store.installed(pin))
        assertFalse("the staged file is consumed", right.exists())
        val installed = store.fileOf(pin)
        assertArrayEquals(lib, installed.readBytes())
        assertFalse("installed libraries are read-only", installed.canWrite())
    }

    @Test fun importFindsThePinByHash() {
        val store = NativeStore(temp.newFolder("natives"))
        val lib = Random(7).nextBytes(2048)
        val pin = dataPin.copy(sha256 = sha(lib), size = lib.size.toLong())
        val file = temp.newFile("libwmexecutorch.so").apply { writeBytes(lib) }
        assertEquals(pin, store.pinFor(file, listOf(mavenPin, pin)))
        assertNull(store.pinFor(file, listOf(mavenPin)))
    }

    @Test fun pruneKeepsOnlyPinnedLibraries() {
        val root = temp.newFolder("natives")
        val store = NativeStore(root)
        val lib = Random(8).nextBytes(1024)
        val pin = dataPin.copy(sha256 = sha(lib), size = lib.size.toLong())
        store.install(pin, temp.newFile("a").apply { writeBytes(lib) })
        val stale = File(root, "f".repeat(64)).apply { mkdirs() }
        File(stale, "libold.so").apply { writeBytes(ByteArray(10)); setReadOnly() }
        File(root, "${"c".repeat(64)}.part").writeBytes(ByteArray(5))
        store.prune(listOf(pin))
        assertTrue(store.installed(pin))
        assertFalse(stale.exists())
        assertEquals(listOf(pin.sha256), root.list()!!.toList())
    }
}
