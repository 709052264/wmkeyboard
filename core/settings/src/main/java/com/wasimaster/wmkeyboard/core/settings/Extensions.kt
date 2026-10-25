package com.wasimaster.wmkeyboard.core.settings

/**
 * A feature bundle the user can switch off as one thing (SETTINGS_REDESIGN.md
 * §3). A tool has a switch of its own on the Tools list, but a feature is
 * bigger than a tool: AI is two tools, a chat, an assistant, a memory screen,
 * a provider page and a button on every settings screen. Off means gone from
 * everywhere the bundle shows: its Home row, its rows under Advanced, its
 * entries on the Tools list and in every tool picker, its search results, its
 * buttons on the keyboard's toolbar and in its toolbox, its panels, and the
 * background work it owns. On brings all of it back with the settings it had.
 *
 * Each entry names what it owns: the [tools], and the settings [routes] as
 * the nav graph declares them (`{arg}` segments included). A Home row is a
 * route like any other, so the root list filters on [routes] too. A route or
 * a tool belongs to at most one bundle; [ExtensionsTest] holds that.
 *
 * The enabled set is stored in [AppUiSettings.extensions]. Nothing stored
 * means an install from before the screen existed, and that install keeps
 * everything on; onboarding writes [defaultsFor] the depth the user picked.
 */
enum class Extension(
    val tools: Set<ToolbarTool> = emptySet(),
    val routes: Set<String> = emptySet(),
) {
    /** The two AI tools, the chat, the settings assistant, memory, history and the chat button. */
    AI(
        tools = setOf(ToolbarTool.AI, ToolbarTool.AI_REPLY),
        routes = setOf(
            "ai", "ai_actions", "ai_reply_tones", "ai_history", "ai_memory", "ai_chat",
            "ai_chat/{conversationId}", "settings_assistant", "ai_action_edit/{actionId}",
            "ai_tools", "ai_mcp", "ai_mcp_edit/{serverId}",
        ),
    ),

    /** Dictation: the Voice tool, the microphone in the keyboard's own fields, the Voice typing screen. */
    VOICE(
        tools = setOf(ToolbarTool.VOICE),
        routes = setOf("voice"),
    ),

    /** The clipboard panel, its history, its search and the recent-copies row. */
    CLIPBOARD(
        tools = setOf(ToolbarTool.CLIPBOARD, ToolbarTool.CLIPBOARD_SEARCH, ToolbarTool.RECENT_COPIES),
        routes = setOf("clipboard", "phoneformats", "cliprules"),
    ),

    /** Snippets and their triggers. */
    TEXT_EXPANSION(
        tools = setOf(ToolbarTool.SNIPPETS),
        routes = setOf(
            "expander", "expander/folder/{folderId}", "expander/folder/{folderId}/new",
            "expander/edit/{snippetId}",
        ),
    ),

    /** Vocabulary packs, the word of the day and the nudges. */
    VOCABULARY(
        tools = setOf(ToolbarTool.VOCABULARY),
        routes = setOf(
            "vocab/packs", "vocab/lists", "vocab/list/{packId}", "vocab/review", "vocab/browse",
            "vocab/word/{packId}/{word}",
        ),
    ),

    /** The notebook in the keyboard and the quick note action. */
    NOTES(
        tools = setOf(ToolbarTool.NOTES, ToolbarTool.QUICK_NOTE),
        routes = setOf("notes", "notes/edit/{noteId}", "notes/tags", "notes/trash"),
    ),

    /** The to-do list. */
    TODO(
        tools = setOf(ToolbarTool.TODO),
        routes = setOf("todo"),
    ),

    /** GIFs, stickers and emoji mixes: the three picture panels and the pack screens. */
    STICKERS(
        tools = setOf(ToolbarTool.GIF, ToolbarTool.STICKER, ToolbarTool.EMOJI_MIX),
        routes = setOf(
            "sticker_packs", "signal_stickers", "whatsapp_stickers", "telegram_stickers",
            "sticker_generator", "sticker_pack/{packId}", "sticker_pack/{packId}/add", "emoji/mixes",
        ),
    ),

    /** Text scan, the QR and barcode reader and the document scanner. */
    SCANNERS(
        tools = setOf(ToolbarTool.OCR, ToolbarTool.QR_SCAN, ToolbarTool.DOC_SCAN),
    ),

    /** The Translate tool and its engines. */
    TRANSLATE(
        tools = setOf(ToolbarTool.TRANSLATE),
    ),

    /** Lua plugins: the tool, the list, the IDE. */
    PLUGINS(
        tools = setOf(ToolbarTool.PLUGINS),
        routes = setOf("plugins", "plugin/{pluginId}", "plugin_ide", "plugin_ide/{draftId}"),
    ),

    /** Add-on repositories and the store built from them. */
    ADDON_REPOSITORIES(
        routes = setOf("addons", "addon_repo/{repoUrl}", "addon/{repoUrl}/{addonId}"),
    ),

    /** Other devices: KDE Connect, Send files and Smart home. */
    CONNECT(
        tools = setOf(ToolbarTool.KDE_CONNECT, ToolbarTool.SEND_FILES, ToolbarTool.HOME_ASSISTANT),
        routes = setOf("kdeconnect/devices", "send/instances", "send/history", "homeassistant/entities"),
    ),

    /** Keyboard modes: per-app and per-field bundles of overrides. */
    MODES(
        tools = setOf(ToolbarTool.MODES),
        routes = setOf("modes", "mode_edit/{modeId}"),
    ),

    /** The selection actions bar and the selection-menu sheet. */
    SELECTION_ACTIONS(
        tools = setOf(ToolbarTool.SELECTION_ACTIONS),
        routes = setOf(
            "selection_macros", "selection_macros/actions", "selection_macros/icons",
            "selection_macros/ai", "selection_macros/zones",
        ),
    ),

    /** The button drawn over other apps. */
    FLOATING_BUTTON(
        routes = setOf("floating_button"),
    ),

    /** Typing on a physical keyboard through the engine, and the tool shortcuts. */
    HARDWARE_KEYBOARD(
        routes = setOf("typing/hardware", "hwshortcuts"),
    ),

    /** The handwriting panel and its models. */
    HANDWRITING(
        tools = setOf(ToolbarTool.HANDWRITING),
    ),

    /** Typing statistics: the counting, the screen and the tool that opens it. */
    STATISTICS(
        tools = setOf(ToolbarTool.STATISTICS),
        routes = setOf("statistics"),
    ),
    ;

    companion object {
        /** Every bundle, as a set. The answer for an install that never chose. */
        val All: Set<Extension> = entries.toSet()

        /**
         * What "keep it simple" starts with: the features other keyboards have
         * an answer to. Voice, the clipboard, GIFs and stickers, translate and
         * text expansion are on Gboard or SwiftKey; the physical keyboard
         * stays because it is only ever met when one is plugged in. Nothing
         * here changes a keyboard default: every master switch these bundles
         * carry is on by default already.
         */
        val SimpleDefaults: Set<Extension> = setOf(
            VOICE, CLIPBOARD, STICKERS, TRANSLATE, TEXT_EXPANSION, HARDWARE_KEYBOARD,
        )

        /**
         * What "a good middle" starts with: Simple plus the bundles that are a
         * panel or a screen rather than a system of their own. Left off: AI,
         * plugins, repositories, other devices, modes and the floating button,
         * each of which asks for an account, a pairing or a grant before it
         * does anything.
         */
        val StandardDefaults: Set<Extension> = SimpleDefaults + setOf(
            VOCABULARY, NOTES, TODO, SCANNERS, SELECTION_ACTIONS, HANDWRITING, STATISTICS,
        )

        /** The bundles on at [depth] before the user touches the screen. */
        fun defaultsFor(depth: SettingsDepth): Set<Extension> = when (depth) {
            SettingsDepth.SIMPLE -> SimpleDefaults
            SettingsDepth.STANDARD, SettingsDepth.AUTO -> StandardDefaults
            SettingsDepth.EVERYTHING -> All
        }

        private val byTool: Map<ToolbarTool, Extension> =
            entries.flatMap { ext -> ext.tools.map { it to ext } }.toMap()

        private val byRoute: Map<String, Extension> =
            entries.flatMap { ext -> ext.routes.map { it to ext } }.toMap()

        /** The bundle [tool] belongs to, or null for a tool that is its own. */
        fun owning(tool: ToolbarTool): Extension? = byTool[tool]

        /**
         * The bundle that owns the screen [pattern] names, or null. [pattern]
         * is the nav graph's own spelling, `{arg}` segments included; a filled
         * route is matched by the caller through its allowlist first.
         */
        fun owningRoute(pattern: String): Extension? = byRoute[pattern]

        /**
         * The stored names back as bundles. A name this build does not know
         * (one removed later, or a typo in a synced store) is dropped rather
         * than failing the whole read.
         */
        fun decode(names: Set<String>): Set<Extension> =
            names.mapNotNullTo(LinkedHashSet()) { name -> entries.firstOrNull { it.name == name } }
    }
}

/**
 * The bundles switched on, for every reader: the stored set, or everything
 * for an install that never stored one. See [AppUiSettings.extensions].
 */
fun KeyboardSettings.extensionsResolved(): Set<Extension> = appUi.extensions ?: Extension.All

/** Whether [extension] is on; see [extensionsResolved]. */
fun KeyboardSettings.extensionOn(extension: Extension): Boolean = extension in extensionsResolved()

/** Whether [tool] is free to show: its bundle is on, or it belongs to none. */
fun Set<Extension>.allows(tool: ToolbarTool): Boolean =
    Extension.owning(tool)?.let { it in this } ?: true

/** Whether the screen [pattern] names is free to open; see [allows]. */
fun Set<Extension>.allowsRoute(pattern: String): Boolean =
    Extension.owningRoute(pattern)?.let { it in this } ?: true
