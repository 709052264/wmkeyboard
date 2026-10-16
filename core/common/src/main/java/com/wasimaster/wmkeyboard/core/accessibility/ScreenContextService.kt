package com.wasimaster.wmkeyboard.core.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Reads the text on screen when the user asks the AI about it (#697).
 *
 * An input method sees its own field and nothing else, and the floating AI
 * button's whole point is a question about everything else: the article, the
 * chat, the error dialog. Window content is only handed to an accessibility
 * service, so this is one, with exactly that one power and nothing else:
 *
 *  * it reads nothing on its own. The service is idle until [read] is called,
 *    and [read] is only called from a press of the floating AI button;
 *  * what it reads is the visible text of the app in front, once, handed to
 *    the AI chat as the message's attachment, where the user sees it before
 *    anything is sent;
 *  * it performs no actions, injects no input and keeps no copy.
 *
 * It runs in the app's main process, beside the keyboard, so [read] is a plain
 * in-process call through [current]. Separate from [TouchPassthroughService]
 * on purpose: that one promises it cannot read the screen, and turning on
 * TalkBack gestures must never be the same switch as letting the AI see.
 */
class ScreenContextService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
    }

    override fun onDestroy() {
        if (current === this) current = null
        super.onDestroy()
    }

    /** Events are not used; the tree is read on demand in [read]. */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    /**
     * The app in front, as its package and its visible text in reading order,
     * or null when there is no app window to read. The keyboard's own window
     * and its floating buttons are skipped: the question is about the app.
     */
    fun read(): Screen? {
        val root = appRoot() ?: return null
        val packageName = root.packageName?.toString().orEmpty()
        val lines = ArrayList<String>()
        var length = 0
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        stack.addLast(root)
        while (stack.isNotEmpty() && length < MAX_CHARS) {
            val node = stack.removeLast()
            if (!node.isVisibleToUser) continue
            val text = node.text?.toString()?.trim().orEmpty()
                .ifEmpty { node.contentDescription?.toString()?.trim().orEmpty() }
            // A password field's text is dots at best; never pass it on.
            if (text.isNotEmpty() && !node.isPassword && lines.lastOrNull() != text) {
                lines.add(text)
                length += text.length + 1
            }
            // Children pushed last-first so they pop in screen order.
            for (index in node.childCount - 1 downTo 0) {
                node.getChild(index)?.let { stack.addLast(it) }
            }
        }
        val body = lines.joinToString("\n").take(MAX_CHARS)
        return Screen(packageName, body)
    }

    /**
     * The root of the app window the user is looking at. The active window is
     * normally it, since neither the keyboard nor the floating buttons take
     * focus; the window list is the fallback for the moments it is not.
     */
    private fun appRoot(): AccessibilityNodeInfo? {
        rootInActiveWindow?.takeIf { it.packageName?.toString() != packageName }?.let { return it }
        return runCatching {
            windows.asSequence()
                .filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                .mapNotNull { it.root }
                .firstOrNull { it.packageName?.toString() != packageName }
        }.getOrNull()
    }

    /** What [read] found: whose screen, and the text on it. */
    data class Screen(val packageName: String, val text: String)

    companion object {
        /** The most text one read hands over; a long page is cut, not refused. */
        const val MAX_CHARS = 12_000

        /** The connected service, or null while it is off. */
        @Volatile
        var current: ScreenContextService? = null
            private set

        /**
         * Whether the user has turned the service on in Settings › Accessibility.
         * Read from the secure setting rather than [current], which is only set
         * once the system has bound it.
         */
        fun isEnabled(context: Context): Boolean {
            val own = ComponentName(context, ScreenContextService::class.java)
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == own }
        }
    }
}
