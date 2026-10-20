package com.wasimaster.wmkeyboard.core.input.rime

import com.wasimaster.wmkeyboard.core.input.composer.CjkDictCatalog
import com.wasimaster.wmkeyboard.core.input.composer.CjkDictStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class RimeStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun zip(file: File, entries: Map<String, String>): File {
        ZipOutputStream(file.outputStream()).use { out ->
            for ((name, text) in entries) {
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    private val schema = """
        # Rime schema
        schema:
          schema_id: my_wubi
          name: "My Wubi"
          version: "1"
        engine:
          processors: []
    """.trimIndent()

    @Test
    fun `an imported zip is unpacked from the folder its schema is in`() {
        val files = tmp.newFolder("files")
        val source = zip(
            tmp.root.resolve("mine.zip"),
            mapOf(
                "rime-mine-master/README.md" to "readme",
                "rime-mine-master/my_wubi.schema.yaml" to schema,
                "rime-mine-master/my_wubi.dict.yaml" to "---\nname: my_wubi\n...\n",
                "rime-mine-master/default.yaml" to "not ours to replace",
                "rime-mine-master/licenses/LICENSE" to "licence",
            ),
        )
        val imported = RimeStore.import(files, source, "mine.zip")
        assertEquals("mine", imported.name)
        assertEquals(listOf("my_wubi"), imported.schemas)
        val shared = RimeStore.sharedDir(files)
        assertTrue(File(shared, "my_wubi.dict.yaml").isFile)
        assertFalse("RIME's configuration is never replaced", File(shared, "default.yaml").exists())
        assertFalse("licences stay out of the data directory", File(shared, "licenses").exists())
        assertEquals(listOf("mine"), RimeStore.imports(files))
        assertEquals("My Wubi", RimeStore.schemaName(File(shared, "my_wubi.schema.yaml")))

        RimeStore.removeImport(files, "mine")
        assertFalse(File(shared, "my_wubi.schema.yaml").exists())
        assertEquals(emptyList<String>(), RimeStore.imports(files))
    }

    @Test(expected = IOException::class)
    fun `a zip without a schema is refused`() {
        val files = tmp.newFolder("files")
        RimeStore.import(files, zip(tmp.root.resolve("x.zip"), mapOf("a.dict.yaml" to "x")), "x.zip")
    }

    @Test(expected = IOException::class)
    fun `an entry that climbs out of the folder is refused`() {
        val files = tmp.newFolder("files")
        RimeStore.import(
            files,
            zip(tmp.root.resolve("evil.zip"), mapOf("a.schema.yaml" to schema, "../../escape.yaml" to "x")),
            "evil.zip",
        )
    }

    @Test
    fun `a downloaded pack is unpacked once, and removed with its download`() {
        val files = tmp.newFolder("files")
        val pack = RimeSchemaCatalog.BASE
        val download = CjkDictStore.packFile(files, pack).apply { parentFile?.mkdirs() }
        zip(download, mapOf("default.yaml" to "config_version: 1\n", "luna_pinyin.schema.yaml" to schema))
        assertEquals("", RimeStore.desiredToken(tmp.newFolder("empty")))
        val token = RimeStore.desiredToken(files)
        assertNotEquals("", token)

        RimeStore.syncShared(files)
        val shared = RimeStore.sharedDir(files)
        assertTrue(File(shared, "default.yaml").isFile)
        assertEquals(listOf("luna_pinyin.schema.yaml"), RimeStore.schemaFiles(files).map { it.name })

        RimeStore.recordDeploy(files, token, listOf("my_wubi"))
        assertEquals(token to listOf("my_wubi"), RimeStore.deployed(files))

        CjkDictStore.delete(files, pack)
        RimeStore.syncShared(files)
        assertFalse(File(shared, "default.yaml").exists())
        assertEquals("", RimeStore.desiredToken(files))
    }

    @Test
    fun `the RIME packs download through the CJK catalog but are not offered with its dictionaries`() {
        for (pack in RimeSchemaCatalog.packs) {
            assertEquals(pack, CjkDictCatalog.byId(pack.id))
            assertEquals(pack, CjkDictCatalog.byFileName(pack.fileName))
            assertFalse(pack in CjkDictCatalog.forLang(pack.langId))
        }
    }
}
