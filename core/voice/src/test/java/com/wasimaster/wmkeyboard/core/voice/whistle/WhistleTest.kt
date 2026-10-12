package com.wasimaster.wmkeyboard.core.voice.whistle

import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// After the tests in PR #548 by @Guillermode20.
class WhistleTest {

    @Test fun mapsKeyboardLanguagesToWhistleCodes() {
        assertEquals("de", WhistleLanguages.codeFor("DE-de"))
        assertEquals("en", WhistleLanguages.codeFor("en_GB"))
        assertEquals("pl", WhistleLanguages.codeFor("pl"))
        assertNull(WhistleLanguages.codeFor("bn"))
        assertNull(WhistleLanguages.codeFor("ja"))
    }

    @Test fun aRightSizedFileCountsAsDownloaded() = withTempDir { root ->
        val model = WhistleModelStore.modelFile(root)
        model.parentFile?.mkdirs()
        model.writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(WhistleModelStore.isDownloaded(root))
        RandomAccessFile(model, "rw").use { it.setLength(WhistleModelStore.MODEL_BYTES) }
        assertTrue(WhistleModelStore.isDownloaded(root))
    }

    @Test fun importRefusesAFileWithTheWrongHash() = withTempDir { root ->
        val source = File(root, "incoming.cact")
        RandomAccessFile(source, "rw").use { it.setLength(WhistleModelStore.MODEL_BYTES) }
        assertFalse(WhistleModelStore.install(root, source))
        assertFalse(WhistleModelStore.modelFile(root).exists())
        assertFalse(WhistleModelStore.partialFile(root).exists())
    }

    private fun withTempDir(block: (File) -> Unit) {
        val root = Files.createTempDirectory("whistle-").toFile()
        try {
            block(root)
        } finally {
            root.deleteRecursively()
        }
    }
}
