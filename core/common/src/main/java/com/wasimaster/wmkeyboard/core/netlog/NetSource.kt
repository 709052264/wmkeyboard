package com.wasimaster.wmkeyboard.core.netlog

import com.wasimaster.wmkeyboard.core.settings.ToolbarTool

/**
 * Which part of the keyboard made a network request, for the network activity
 * log.
 *
 * [id] is what the log file stores, so it is stable across renames: never store
 * [name]. The labels, the "why it happened" line and the "what was sent" note
 * live in `:app`, next to the screen that shows them.
 *
 * [tool] is the toolbar tool a source belongs to, when it belongs to one. The
 * screen draws that tool's icon in that tool's colour, so a row in the log looks
 * like the key that caused it. Sources that are not tools (downloads, backup,
 * updates) borrow the icon of the settings screen that owns them instead.
 *
 * [background] is the default for requests nobody asked for at that moment: a
 * link preview for a copied URL, refilling the photo pool, a scheduled backup.
 * A call site can override it, for the sources that happen both ways.
 */
enum class NetSource(
    val id: String,
    val tool: ToolbarTool? = null,
    val background: Boolean = false,
) {
    TRANSLATE("translate", ToolbarTool.TRANSLATE),

    /** DeepL Write, from the grammar panel or the selection bar. Its settings sit on the Translate page. */
    DEEPL_WRITE("deepl_write", ToolbarTool.TRANSLATE),

    /** A LanguageTool server the grammar tool's list asks: the public API, or one the user runs. */
    LANGUAGETOOL("languagetool", ToolbarTool.GRAMMAR),
    GIF("gif", ToolbarTool.GIF),
    STICKER("sticker", ToolbarTool.STICKER),
    WEB_SEARCH("web_search", ToolbarTool.WEB_SEARCH),
    IMAGE_SEARCH("image_search", ToolbarTool.IMAGE_SEARCH),

    /** A camera photo uploaded to a reverse image search site (#349). */
    PHOTO_SEARCH("photo_search", ToolbarTool.CAMERA),
    AI("ai", ToolbarTool.AI),
    AI_CHAT("ai_chat", ToolbarTool.AI),

    /**
     * A search or a page the AI tool made on its own, because the model asked
     * for it (#470). Its own source rather than [WEB_SEARCH]: the user did not
     * run that search, and a log that said they had would be wrong about the
     * one thing it exists to record.
     */
    AI_TOOLS("ai_tools", ToolbarTool.AI),

    /**
     * A request to one of the MCP servers the user added (#573): listing its
     * tools on Connect, or a call a model made. Not [AI_TOOLS], because the
     * host is the user's choice and the request carries whatever the model
     * wrote as the tool's arguments.
     */
    AI_MCP("ai_mcp", ToolbarTool.AI),
    TRANSCRIPTION("transcription", ToolbarTool.VOICE),
    WIKIPEDIA("wikipedia", ToolbarTool.WIKIPEDIA),
    DICTIONARY("dictionary", ToolbarTool.DICTIONARY),
    VOCABULARY("vocabulary", ToolbarTool.VOCABULARY),

    /** Synonyms for a word held on the suggestion strip (#321). Its settings sit on the Suggestions page. */
    SYNONYMS("synonyms"),
    WEATHER("weather", ToolbarTool.WEATHER),

    /**
     * The address for a shared position (#350): the point at the share's
     * precision, once per share, never in the background.
     */
    LOCATION("location", ToolbarTool.LOCATION),
    CURRENCY("currency", ToolbarTool.CURRENCY),
    /** Searching photos is asked for; refilling the rotation pool passes `background` itself. */
    PHOTOS("photos"),
    LINK_PREVIEW("link_preview", ToolbarTool.CLIPBOARD, background = true),

    /** Thumbnails and animations drawn by the GIF, sticker and image panels. */
    MEDIA_IMAGES("media_images"),
    DOWNLOAD_WORDLIST("download_wordlist"),
    DOWNLOAD_NGRAM("download_ngram"),
    DOWNLOAD_CJK("download_cjk"),
    DOWNLOAD_EMOJI("download_emoji", ToolbarTool.EMOJI),
    DOWNLOAD_WHISPER("download_whisper", ToolbarTool.VOICE),

    /** FUTO Swipe's glide models, when that glide engine is turned on. */
    DOWNLOAD_GLIDE("download_glide"),
    DOWNLOAD_LLM("download_llm", ToolbarTool.AI),

    /**
     * An on-device translation model: a Bergamot direction from Mozilla's
     * Firefox Translations servers, or the NLLB model from Hugging Face.
     */
    DOWNLOAD_TRANSLATE("download_translate", ToolbarTool.TRANSLATE),
    DOWNLOAD_VOCAB("download_vocab", ToolbarTool.VOCABULARY),

    /** An offline dictionary for the Dictionary tool (#665). */
    DOWNLOAD_DICTIONARY("download_dictionary", ToolbarTool.DICTIONARY),
    DOWNLOAD_FONT("download_font"),
    DOWNLOAD_CUTOUT("download_cutout", ToolbarTool.STICKER),

    /** A photo the scan text tool sends to the user's online text reader (#469). */
    OCR_ONLINE("ocr_online", ToolbarTool.OCR),

    /** Tesseract language data for the scan text tool. */
    DOWNLOAD_OCR("download_ocr", ToolbarTool.OCR),

    /** EmbeddingGemma 2, the model behind search by meaning. */
    DOWNLOAD_SEMANTIC("download_semantic"),

    /** The sticker generator's models and picture filter (data repository, or Qualcomm's bucket for NPU builds). */
    DOWNLOAD_IMAGEGEN("download_imagegen"),

    /** The upscaler's two networks (data repository): the sticker editor's and the photo editor's Upscale. */
    DOWNLOAD_UPSCALE("download_upscale"),

    /** IndicXlit, the neural readings on the Indic phonetic layouts. */
    DOWNLOAD_XLIT("download_xlit"),

    /** Meta's MMS voices, which read text aloud offline (Read back, the speaker buttons). */
    DOWNLOAD_VOICE_OUT("download_voice_out", ToolbarTool.READ_BACK),

    /**
     * A native library a GitHub or F-Droid build fetches the first time a
     * feature needs it: the project's own from the data repository, ML Kit's,
     * LiteRT's and Qualcomm's straight from their Maven repositories.
     */
    DOWNLOAD_NATIVE("download_native"),

    /** An interface language pack the standard GitHub build fetches when its language is picked. */
    DOWNLOAD_LANGUAGE("download_language"),
    ADDONS("addons"),
    KEYMAN("keyman"),
    SIGNAL_STICKERS("signal_stickers", ToolbarTool.STICKER),
    TELEGRAM_STICKERS("telegram_stickers", ToolbarTool.STICKER),

    /** The Rboard theme list and the packs picked from it, on the Themes screen. */
    RBOARD_THEMES("rboard_themes"),
    LINK_IMPORT("link_import"),
    BACKUP("backup"),
    UPDATES("updates", background = true),

    /** About › Supporters, when its refresh button is pressed. */
    SUPPORTERS("supporters"),
    /** A file sent or received by the Send files tool (#729): a link or a device nearby. */
    SEND("send", ToolbarTool.SEND_FILES),
    KDE_CONNECT("kde_connect", ToolbarTool.KDE_CONNECT),

    /** The user's own Home Assistant server: its socket, its REST calls, a camera picture. */
    HOME_ASSISTANT("home_assistant", ToolbarTool.HOME_ASSISTANT),

    /** The other homes of the smart home tool: Samsung's cloud, a Hue bridge on the LAN, an openHAB server, Tuya's and SwitchBot's clouds. */
    SMARTTHINGS("smartthings", ToolbarTool.HOME_ASSISTANT),
    HUE("hue", ToolbarTool.HOME_ASSISTANT),
    OPENHAB("openhab", ToolbarTool.HOME_ASSISTANT),
    TUYA("tuya", ToolbarTool.HOME_ASSISTANT),
    SWITCHBOT("switchbot", ToolbarTool.HOME_ASSISTANT),
    OTHER("other"),
    ;

    companion object {
        fun of(id: String): NetSource = entries.firstOrNull { it.id == id } ?: OTHER
    }
}
