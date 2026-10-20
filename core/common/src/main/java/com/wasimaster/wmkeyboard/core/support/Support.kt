package com.wasimaster.wmkeyboard.core.support

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.wasimaster.wmkeyboard.config.BuildConfig
import com.wasimaster.wmkeyboard.core.debug.DebugLog

/**
 * Where a problem goes: the project's issue tracker, and the maintainer's
 * inbox.
 *
 * These live in one place because several screens need them and none of them
 * should be inventing their own address: the AI panel's "report this
 * generation" button, About's bug-report rows, and anything added later. A
 * report button is also a Play requirement for surfaces that show generated
 * content, so the address it opens has to be the real, monitored one rather
 * than a placeholder someone forgot to fill in.
 *
 * Nothing here sends anything: every entry point builds an intent and hands it
 * to the user's mail app, so the user reads — and can edit or abandon — the
 * whole message before it leaves the device. That matters most for
 * [aiGenerationReport], which quotes the text the model saw.
 */
object Support {

    /** The maintainer's inbox, for reports and bugs. */
    const val EMAIL = "arianmollik323@gmail.com"

    const val SOURCE_URL = "https://github.com/wasi-master/WMKeyboard"

    /** Preferred first stop for a bug: public, searchable, and trackable. */
    const val ISSUES_URL = "$SOURCE_URL/issues"

    /** The community group: questions, layouts and themes, release news. */
    const val TELEGRAM_URL = "https://t.me/wmkeyboard"

    // The project's social accounts. Every handle is `wmkeyboard` except
    // Instagram, where that name was taken.
    const val X_URL = "https://x.com/wmkeyboard"
    const val REDDIT_URL = "https://www.reddit.com/r/wmkeyboard"
    const val INSTAGRAM_URL = "https://www.instagram.com/wmkeyboardapp"
    const val TIKTOK_URL = "https://www.tiktok.com/@wmkeyboard"

    /**
     * Longest run of the user's own text (input, and the model's output) that
     * a report quotes. A long document would make the draft unreadable, and
     * the first few thousand characters are what a report is actually about.
     */
    private const val QUOTE_LIMIT = 4_000

    /** Build and device line every report carries, so a reply can start. */
    fun environment(): String = buildString {
        val channel = when {
            BuildConfig.ENABLE_PLAY_STORE -> " (Play Store)"
            BuildConfig.ENABLE_FDROID -> " (F-Droid)"
            else -> ""
        }
        appendLine(
            "version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) " +
                "${DebugLog.flavorTag}$channel ${BuildConfig.BUILD_TYPE}",
        )
        appendLine("android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        append("device: ${Build.MANUFACTURER} ${Build.MODEL}")
    }

    /**
     * A pre-filled bug report: the environment block plus headings for the
     * things that always get asked back.
     */
    fun bugReport(): String = buildString {
        appendLine("What happened:")
        appendLine()
        appendLine("What I expected:")
        appendLine()
        appendLine("Steps to reproduce:")
        appendLine("1. ")
        appendLine()
        appendLine("---- build ----")
        appendLine(environment())
    }

    /**
     * A report about one AI result. The user's complaint goes at the top,
     * where the mail app puts the cursor; everything the maintainer needs to
     * judge the generation — which model, which action, what went in and what
     * came out — follows below the rule.
     */
    fun aiGenerationReport(
        action: String,
        provider: String,
        model: String,
        input: String,
        output: String,
        instruction: String = "",
    ): String = buildString {
        appendLine("What's wrong with this generation:")
        appendLine()
        appendLine()
        appendLine("---- generation ----")
        appendLine("action: $action")
        // Only the Custom action has one, and without it the output reads as
        // though the model produced it unprompted.
        if (instruction.isNotBlank()) appendLine("instruction: ${quote(instruction)}")
        appendLine("provider: $provider")
        appendLine("model: ${model.ifBlank { "(unset)" }}")
        appendLine(environment())
        appendLine()
        appendLine("---- input ----")
        appendLine(quote(input))
        appendLine()
        appendLine("---- output ----")
        appendLine(quote(output))
    }

    /**
     * A report about one GIF or sticker a provider served up. The user's
     * complaint goes first; everything needed to find the result again —
     * which provider, which search, and the file itself — follows.
     *
     * The keyboard doesn't own this content and can't moderate it: Klipy and
     * GIPHY return whatever their catalogues hold for a query. Play asks for a
     * reporting route on surfaces like that, and a maintainer can only act on
     * a report (block the id, tighten the content filter, raise it with the
     * provider) if it names the exact result.
     */
    fun mediaReport(
        kind: String,
        provider: String,
        query: String,
        id: String,
        url: String,
    ): String = buildString {
        appendLine("What's wrong with this $kind:")
        appendLine()
        appendLine()
        appendLine("---- $kind ----")
        appendLine("provider: $provider")
        appendLine("search: ${query.ifBlank { "(trending, no search term)" }}")
        appendLine("id: $id")
        appendLine("url: $url")
        appendLine(environment())
    }

    private fun quote(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "(empty)"
        return if (trimmed.length <= QUOTE_LIMIT) {
            trimmed
        } else {
            trimmed.take(QUOTE_LIMIT) + "\n… (truncated)"
        }
    }

    /**
     * Opens the user's mail app on a draft to [EMAIL]. `mailto:` rather than
     * ACTION_SEND so the chooser offers mail apps only — a report is not a
     * thing to accidentally post to a group chat.
     *
     * Returns false when the device has no mail app, which callers should say
     * out loud rather than swallow: the button did nothing visible otherwise.
     */
    fun email(context: Context, subject: String, body: String): Boolean {
        val uri = Uri.parse("mailto:$EMAIL?subject=${Uri.encode(subject)}&body=${Uri.encode(body)}")
        val intent = Intent(Intent.ACTION_SENDTO, uri).apply {
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}
