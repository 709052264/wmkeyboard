package com.wasimaster.wmkeyboard.core.localllm

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlamaCppTest {

    // Snapdragon 8 Elite: two prime and six performance cores, all big.
    @Test
    fun `threads count every core at half the top capacity or more`() {
        assertEquals(8, LlamaThreads.pick(8, listOf(800, 800, 800, 800, 800, 800, 1024, 1024), emptyList()))
    }

    // Helio G99: two A76 and six A55. The A55s would hold every step back.
    @Test
    fun `threads leave out little cores`() {
        assertEquals(2, LlamaThreads.pick(8, listOf(250, 250, 250, 250, 250, 250, 1024, 1024), emptyList()))
    }

    @Test
    fun `threads fall back to clocks, then to half the cores`() {
        val freqs = listOf(1_800_000L, 1_800_000L, 1_800_000L, 1_800_000L, 2_400_000L, 2_400_000L, 2_400_000L, 3_000_000L)
        assertEquals(4, LlamaThreads.pick(8, emptyList(), freqs))
        assertEquals(4, LlamaThreads.pick(8, emptyList(), List(8) { 2_000_000L }))
        assertEquals(1, LlamaThreads.pick(1, emptyList(), emptyList()))
        assertEquals(8, LlamaThreads.pick(16, List(16) { 1024L }, emptyList()))
    }

    @Test
    fun `a gguf is told by its first four bytes`() {
        val dir = Files.createTempDirectory("gguf").toFile()
        try {
            val real = File(dir, "download").apply { writeBytes("GGUF\u0003\u0000\u0000\u0000".toByteArray()) }
            val other = File(dir, "notes.bin").apply { writeBytes("PK\u0003\u0004".toByteArray()) }
            val short = File(dir, "x").apply { writeBytes("GG".toByteArray()) }
            assertTrue(GgufFile.isGguf(real))
            assertTrue(GgufFile.runsOnLlamaCpp(real))
            assertFalse(GgufFile.isGguf(other))
            assertFalse(GgufFile.isGguf(short))
            assertFalse(GgufFile.isGguf(File(dir, "missing")))
            // A LiteRT-LM extension wins over content: those files are never GGUFs.
            val litert = File(dir, "model.litertlm").apply { writeBytes("GGUF".toByteArray()) }
            assertFalse(GgufFile.runsOnLlamaCpp(litert))
            assertTrue(GgufFile.runsOnLlamaCpp(File(dir, "named.gguf")))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `kv cache cost per token`() {
        // Qwen 2.5 0.5B: 24 layers, 14 heads, 2 KV heads, 896 wide -> 64 per head.
        val info = LlamaModelInfo(494_000_000, 0, 32768, 24, 14, 2, 896, 0, true, 151936)
        assertEquals(24L * 2 * 2 * 64 * 2, info.kvBytesPerToken)
    }
}
