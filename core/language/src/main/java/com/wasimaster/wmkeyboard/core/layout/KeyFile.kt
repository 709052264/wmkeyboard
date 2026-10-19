package com.wasimaster.wmkeyboard.core.layout

import com.wasimaster.wmkeyboard.core.util.JsonPretty
import com.wasimaster.wmkeyboard.core.util.firstJsonDocument
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.encodeToJsonElement

/**
 * One or more keys as a clipboard document (issue #769): what the layout
 * editor's Copy writes and its Paste reads, so a key moves with everything on
 * it (flicks, icon, corner hint, alternates, size) to another place in the
 * layer, another layer, or another layout.
 *
 * An envelope with a format tag for the reason [LayerFile] has one: every
 * [Key] field but the label has a default, so a bare object would decode from
 * almost any JSON, and the clipboard holds almost anything.
 */
@Serializable
private data class KeyEnvelope(
    val format: String,
    val version: Int,
    val appVersion: Int = 0,
    val keys: List<Key>,
)

object KeyFile {

    const val FORMAT = "wmkeyboard-keys"
    const val VERSION = 1

    /** Printed like [LayerFile.encode]: indented, defaults left out. */
    fun encode(keys: List<Key>, appVersion: Int): String = JsonPretty.print(
        layoutEditorJson.encodeToJsonElement(KeyEnvelope(FORMAT, VERSION, appVersion, keys)),
    )

    /**
     * The keys in [text], each through the repair a key in an imported layout
     * gets, or null when [text] is not copied keys or nothing in it survives.
     */
    fun decode(text: String): List<Key>? {
        val envelope = runCatching {
            LayoutCodec.json.decodeFromString<KeyEnvelope>(text.firstJsonDocument())
        }.getOrNull() ?: return null
        if (envelope.format != FORMAT) return null
        val repairs = mutableListOf<LayoutMessage>()
        return envelope.keys
            .mapNotNull { it.repairKey("", repairs) }
            .takeIf { it.isNotEmpty() }
    }
}
