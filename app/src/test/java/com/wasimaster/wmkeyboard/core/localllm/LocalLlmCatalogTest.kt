package com.wasimaster.wmkeyboard.core.localllm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalLlmCatalogTest {

    // The catalog order is a quality ranking, not a size ranking — the
    // settings list and the panel picker both render it as-is, so the models
    // worth using have to come first.
    @Test
    fun `recommended models lead the catalog`() {
        val tiers = LocalLlmCatalog.models.map { it.tier }
        assertEquals(
            listOf(ModelTier.RECOMMENDED, ModelTier.RECOMMENDED),
            tiers.take(2),
        )
        assertTrue(
            "a recommended model is buried below other tiers",
            tiers.indexOfLast { it == ModelTier.RECOMMENDED } == 1,
        )
    }

    @Test
    fun `ids are unique and directory-safe`() {
        val ids = LocalLlmCatalog.models.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        ids.forEach { id ->
            assertTrue("id '$id' has unsafe chars", id.matches(Regex("[a-z0-9.\\-]+")))
        }
    }

    @Test
    fun `file names match their declared format`() {
        LocalLlmCatalog.models.forEach { model ->
            assertTrue(
                "${model.id}: ${model.fileName} vs ${model.format}",
                model.fileName.endsWith(".${model.format.extension}"),
            )
        }
    }

    @Test
    fun `download url resolves repo main`() {
        val model = LocalLlmCatalog.models.first()
        assertEquals(
            "https://huggingface.co/${model.repo}/resolve/main/${model.fileName}",
            LocalLlmCatalog.downloadUrl(model),
        )
    }

    @Test
    fun `byId finds every model and misses unknowns`() {
        LocalLlmCatalog.models.forEach { assertEquals(it, LocalLlmCatalog.byId(it.id)) }
        assertNull(LocalLlmCatalog.byId("nope"))
        assertNull(LocalLlmCatalog.byId(""))
    }

    // LiteRT-LM stays the default: its rows come first, and the GGUF rows
    // that llama.cpp runs follow as a block of their own.
    @Test
    fun `gguf rows follow every litert row`() {
        val runtimes = LocalLlmCatalog.models.map { it.runtime }
        val firstGguf = runtimes.indexOf(LocalLlmRuntime.LLAMA_CPP)
        assertTrue("no GGUF rows", firstGguf > 0)
        assertTrue(runtimes.drop(firstGguf).all { it == LocalLlmRuntime.LLAMA_CPP })
    }

    // A pinned commit is what keeps the SHA-256 true: "main" can move.
    @Test
    fun `gguf rows pin a commit and a sha256`() {
        LocalLlmCatalog.models.filter { it.format == ModelFormat.GGUF }.forEach { model ->
            assertTrue(model.id, model.id.startsWith("gguf-"))
            assertTrue(model.id, model.revision.matches(Regex("[0-9a-f]{40}")))
            assertTrue(model.id, model.sha256.orEmpty().matches(Regex("[0-9a-f]{64}")))
            assertEquals(
                "https://huggingface.co/${model.repo}/resolve/${model.revision}/${model.fileName}",
                LocalLlmCatalog.downloadUrl(model),
            )
            assertFalse(model.id, model.vision)
            assertTrue(model.id, model.npuBuilds.isEmpty())
        }
    }

    @Test
    fun `runtime of an imported file follows its extension`() {
        assertEquals(LocalLlmRuntime.LLAMA_CPP, LocalLlmCatalog.runtimeOf("custom:mine.Q4_K_M.gguf"))
        assertEquals(LocalLlmRuntime.LITERT_LM, LocalLlmCatalog.runtimeOf("custom:mine.litertlm"))
        assertEquals(LocalLlmRuntime.LLAMA_CPP, LocalLlmCatalog.runtimeOf("gguf-qwen3-0.6b"))
        assertNull(LocalLlmCatalog.runtimeOf(""))
        // llama.cpp here has no vision projector: an imported GGUF never sees.
        assertEquals(false, LocalLlmCatalog.canSee("custom:mine.gguf"))
        assertNull(LocalLlmCatalog.canSee("custom:mine.litertlm"))
    }
}
