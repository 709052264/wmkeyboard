package com.wasimaster.wmkeyboard.core.localllm

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Telling a vision projector (an `mmproj` GGUF) from a model, and pairing the two. */
class GgufProjectorTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** A GGUF v3 header with [pairs] as its key-value pairs, no tensors. */
    private fun gguf(vararg pairs: Pair<String, Any>): ByteArray {
        val out = ByteArrayOutputStream()
        fun u32(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        fun u64(v: Long) = out.write(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(v).array())
        fun str(v: String) = v.toByteArray().let { u64(it.size.toLong()); out.write(it) }
        out.write("GGUF".toByteArray())
        u32(3)
        u64(0)
        u64(pairs.size.toLong())
        for ((key, value) in pairs) {
            str(key)
            when (value) {
                is String -> { u32(8); str(value) }
                is Int -> { u32(4); u32(value) }
                is List<*> -> { u32(9); u32(8); u64(value.size.toLong()); value.forEach { str(it as String) } }
                else -> error("unsupported")
            }
        }
        return out.toByteArray()
    }

    private fun file(name: String, bytes: ByteArray): File = temp.newFile(name).apply { writeBytes(bytes) }

    @Test
    fun `architecture is read from the header`() {
        assertEquals("qwen2vl", GgufFile.architecture(gguf("general.architecture" to "qwen2vl").inputStream()))
        // Writers put it first, but a few keys before it are skipped over.
        val late = gguf("general.type" to "model", "x.count" to 7, "x.tags" to listOf("a", "bc"), "general.architecture" to "clip")
        assertEquals("clip", GgufFile.architecture(late.inputStream()))
        assertNull(GgufFile.architecture(gguf("general.name" to "nothing").inputStream()))
        assertNull(GgufFile.architecture("not a gguf at all".toByteArray().inputStream()))
    }

    @Test
    fun `a projector is told by its header or its name`() {
        assertTrue(GgufFile.isProjector(file("vision.gguf", gguf("general.architecture" to "clip"))))
        assertTrue(GgufFile.isProjector(file("mmproj-x.gguf", gguf("general.architecture" to "whatever"))))
        assertFalse(GgufFile.isProjector(file("model.gguf", gguf("general.architecture" to "llama"))))
    }

    @Test
    fun `name keys drop mmproj and the quantization`() {
        assertEquals("smolvlm500minstruct", GgufFile.nameKey("SmolVLM-500M-Instruct-Q8_0.gguf"))
        assertEquals("smolvlm500minstruct", GgufFile.nameKey("mmproj-SmolVLM-500M-Instruct-f16.gguf"))
        assertEquals("qwen25vl3binstruct", GgufFile.nameKey("Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf"))
        assertEquals("", GgufFile.nameKey("mmproj-model-f16.gguf"))
    }

    @Test
    fun `projectors pair with the model of the same name`() {
        val text = gguf("general.architecture" to "llama")
        val clip = gguf("general.architecture" to "clip")
        val smol = file("SmolVLM-500M-Instruct-Q8_0.gguf", text)
        val qwen = file("Qwen2.5-VL-3B-Instruct-Q4_K_M.gguf", text)
        val plain = file("Llama-3.2-1B-Instruct-Q4_K_M.gguf", text)
        // Same family, no projector of its own: Qwen 2.5 VL's is not its.
        val cousin = file("qwen2.5-0.5b-instruct-q4_k_m.gguf", text)
        val smolProjector = file("mmproj-SmolVLM-500M-Instruct-Q8_0.gguf", clip)
        val qwenProjector = file("mmproj-Qwen2.5-VL-3B-Instruct-f16.gguf", clip)
        assertEquals(smolProjector, GgufFile.projectorFor(smol))
        assertEquals(qwenProjector, GgufFile.projectorFor(qwen))
        assertNull(GgufFile.projectorFor(plain))
        assertNull(GgufFile.projectorFor(cousin))
        assertNull(GgufFile.projectorFor(smolProjector))
    }

    @Test
    fun `a lone model and a lone projector pair whatever their names`() {
        val dir = temp.newFolder("one")
        val model = File(dir, "gemma-3-4b-it-Q4_K_M.gguf").apply { writeBytes(gguf("general.architecture" to "gemma3")) }
        val projector = File(dir, "mmproj-model-f16.gguf").apply { writeBytes(gguf("general.architecture" to "clip")) }
        assertEquals(projector, GgufFile.projectorFor(model))
        // A second model makes the unnamed projector nobody's.
        File(dir, "other-Q8_0.gguf").writeBytes(gguf("general.architecture" to "llama"))
        assertNull(GgufFile.projectorFor(model))
    }

    @Test
    fun `imported projectors are no models to pick`() {
        val filesDir = temp.newFolder("files")
        val custom = LocalLlmStore.customDir(filesDir).apply { mkdirs() }
        File(custom, "SmolVLM-500M-Instruct-Q8_0.gguf").writeBytes(gguf("general.architecture" to "llama"))
        File(custom, "mmproj-SmolVLM-500M-Instruct-Q8_0.gguf").writeBytes(gguf("general.architecture" to "clip"))
        assertEquals(listOf("SmolVLM-500M-Instruct-Q8_0.gguf"), LocalLlmStore.customModels(filesDir).map { it.name })
        assertEquals(1, LocalLlmStore.customProjectors(filesDir).size)
        // Deleting the model takes its projector with it.
        LocalLlmStore.deleteCustom(filesDir, "SmolVLM-500M-Instruct-Q8_0.gguf")
        assertTrue(custom.listFiles().orEmpty().isEmpty())
    }
}
