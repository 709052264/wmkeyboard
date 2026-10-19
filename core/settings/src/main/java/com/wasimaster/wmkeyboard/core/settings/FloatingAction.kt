package com.wasimaster.wmkeyboard.core.settings

/**
 * What one gesture on the floating button does (#755), or what one slot of its
 * quick menu runs.
 *
 * Stored as a short token (`keyboard`, `ask_ai`, `tool:VOICE`), so a tool the
 * enum later drops reads back as nothing rather than as a crash, and a menu is
 * one comma-joined string.
 */
sealed interface FloatingAction {
    val token: String

    /** The gesture is not bound. */
    data object None : FloatingAction {
        override val token = "none"
    }

    /** Brings the keyboard up, field or no field; puts it away while it is up (#374, #697). */
    data object Keyboard : FloatingAction {
        override val token = "keyboard"
    }

    /** Opens the Ask AI sheet about what is on screen (#697). */
    data object AskAi : FloatingAction {
        override val token = "ask_ai"
    }

    /**
     * Waits for a tap on a word anywhere on screen, then opens the Look up
     * card for it (#765). Reads the screen, so it needs screen reading.
     */
    data object LookUp : FloatingAction {
        override val token = "look_up"
    }

    /** Fans the quick menu out around the button. Never a menu slot itself. */
    data object Menu : FloatingAction {
        override val token = "menu"
    }

    /** Puts the button away until the keyboard next opens or closes. */
    data object Hide : FloatingAction {
        override val token = "hide"
    }

    /** Runs a toolbar tool, bringing the keyboard up first when the tool needs it. */
    data class Tool(val tool: ToolbarTool) : FloatingAction {
        override val token get() = "$TOOL_PREFIX${tool.name}"
    }

    companion object {
        private const val TOOL_PREFIX = "tool:"

        /** The token's action, or null for one this build does not know. */
        fun parse(token: String): FloatingAction? = when (token) {
            None.token -> None
            Keyboard.token -> Keyboard
            AskAi.token -> AskAi
            LookUp.token -> LookUp
            Menu.token -> Menu
            Hide.token -> Hide
            else -> token.removePrefix(TOOL_PREFIX)
                .takeIf { token.startsWith(TOOL_PREFIX) }
                ?.let { name -> ToolbarTool.entries.firstOrNull { it.name == name } }
                ?.let(::Tool)
        }

        /** A stored quick menu: the slots this build still knows, in order, each once. */
        fun parseMenu(stored: String): List<FloatingAction> =
            stored.split(',')
                .mapNotNull { parse(it.trim()) }
                .filter { it.fitsMenu() }
                .distinct()
                .take(MAX_FLOATING_MENU_ITEMS)

        fun menuToken(items: List<FloatingAction>): String =
            items.filter { it.fitsMenu() }.distinct().take(MAX_FLOATING_MENU_ITEMS).joinToString(",") { it.token }
    }
}

/** A quick menu slot cannot open the menu it sits in, nor do nothing. */
fun FloatingAction.fitsMenu(): Boolean = this != FloatingAction.None && this != FloatingAction.Menu

/** How many slots the quick menu fans out; past this the ring stops reading as a ring. */
const val MAX_FLOATING_MENU_ITEMS = 8

/** The quick menu a fresh install opens with: the reasons people asked for the button. */
val DefaultFloatingMenu: List<FloatingAction> = listOf(
    FloatingAction.Tool(ToolbarTool.VOICE),
    FloatingAction.AskAi,
    FloatingAction.Tool(ToolbarTool.CLIPBOARD),
    FloatingAction.Tool(ToolbarTool.EMOJI),
    FloatingAction.Keyboard,
)

/** The button's colours. */
enum class FloatingButtonStyle {
    /** Dark on a light system theme's apps and light on a dark one's, like the shade. */
    AUTO,
    DARK,
    LIGHT,

    /** The system's accent (Material You on Android 12 and up). */
    ACCENT,
}

/** The size slider's range, in dp. */
val FloatingButtonSizeRange = 40..72

/** How long the button waits before fading, in seconds; 0 never fades. */
val FloatingButtonIdleRange = 0..10
