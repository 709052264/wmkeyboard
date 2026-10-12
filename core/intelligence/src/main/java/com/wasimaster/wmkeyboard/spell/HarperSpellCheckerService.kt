package com.wasimaster.wmkeyboard.spell

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.ContactsContract
import dalvik.system.BaseDexClassLoader
import android.service.textservice.SpellCheckerService
import android.view.textservice.SentenceSuggestionsInfo
import android.view.textservice.SuggestionsInfo
import android.view.textservice.TextInfo
import com.wasimaster.wmkeyboard.core.grammar.GrammarChecker
import com.wasimaster.wmkeyboard.core.grammar.GrammarFix
import com.wasimaster.wmkeyboard.core.grammar.GrammarLint
import com.wasimaster.wmkeyboard.core.prediction.ContactEmails
import com.wasimaster.wmkeyboard.core.prediction.ContactNames
import com.wasimaster.wmkeyboard.core.settings.GrammarDialect
import com.wasimaster.wmkeyboard.core.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Exposes the bundled Harper engine as a system-wide spell checker, so the
 * red underlines and correction menus in *other* apps come from the same
 * checker as the keyboard's own grammar tool.
 *
 * Full builds only: lite ships no `libharper_jni.so` at all, so advertising
 * a spell checker there would put an entry in the system settings that
 * could never find anything. The service and its manifest entry live in
 * `src/full` for that reason.
 *
 * Harper is a sentence-level checker — its lints carry spans and can cover
 * several words ("their" → "they're") — so the real work happens in
 * [Session.onGetSentenceSuggestionsMultiple], which is the only spell
 * checker API that can report an offset and length per problem. The older
 * per-word [Session.onGetSuggestions] is implemented too, because the
 * framework still falls back to it on some paths, but it can only ever flag
 * the single word it is handed.
 *
 * The checker runs in the app's own process, independent of the keyboard: it
 * loads the user's contacts and warms Harper itself, so underlines and
 * contact-aware corrections work in every app even when WMKeyboard is not the
 * active input method.
 */
/**
 * A Harper fix as the whole-span replacement the spell checker UI needs.
 *
 * The framework can only swap a flagged span for a string, so an insertion
 * has to become "the span plus the insertion". Deletions return null: the
 * correction menu would show an empty row, which cannot be read or chosen.
 */
internal fun spellReplacementFor(span: String, fix: GrammarFix): String? = when (fix.kind) {
    "replace" -> fix.text
    "insertAfter" -> fix.text?.let { span + it }
    else -> null
}

/**
 * Keeps the spell checker listed in Android's settings only where it can
 * answer. Every edition ships Harper now, lite included, but a build made
 * without it (F-Droid strips the prebuilt library) declares the same service,
 * and a listed spell checker that never returns a result is worse than none.
 */
object HarperSpellChecker {

    /**
     * Enables or disables [HarperSpellCheckerService] to match whether this
     * install carries `libharper_jni.so`. Asks the class loader where the
     * library is rather than loading it, so a process start pays nothing.
     * Writes only when the state is wrong.
     */
    fun syncAvailability(context: Context) {
        val packaged = runCatching {
            (context.classLoader as? BaseDexClassLoader)?.findLibrary("harper_jni") != null
        }.getOrDefault(false)
        val component = ComponentName(context, HarperSpellCheckerService::class.java)
        val pm = context.packageManager
        val wanted = if (packaged) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }
        runCatching {
            if (pm.getComponentEnabledSetting(component) != wanted) {
                pm.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
            }
        }
    }
}

class HarperSpellCheckerService : SpellCheckerService() {

    override fun createSession(): Session = HarperSession(applicationContext)

    private class HarperSession(private val context: Context) : Session() {

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /**
         * Mirrors the user's grammar dialect. Read through a collected flow
         * rather than per-request: the framework calls into a session on a
         * binder thread and expects a prompt answer, and a DataStore read on
         * every keystroke-driven check would dominate the cost of the check
         * itself.
         */
        @Volatile
        private var dialectOrdinal: Int = GrammarDialect.AMERICAN.ordinal

        /**
         * When true, still flag typos (underline stays) but hand the framework
         * no replacements and the "mark but don't show suggestions UI" flag, so
         * the correction popup never appears. Only honoured on Android 12+
         * (API 31), where [SuggestionsInfo.RESULT_ATTR_DONT_SHOW_UI_FOR_SUGGESTIONS]
         * exists; older versions ignore the flag, so the toggle does nothing
         * there. Mirrored through the settings flow for the same reason as
         * [dialectOrdinal].
         */
        @Volatile
        private var noSuggestionsUi = false

        /** Whether Harper has been warmed at least once this session. */
        @Volatile
        private var warmed = false

        /**
         * Contact indices, loaded in this process so the checker recognizes
         * names even when the WMKeyboard IME is not the active keyboard. Empty
         * until the setting is on and the Contacts permission granted; memory
         * only, never persisted.
         */
        @Volatile
        private var contactNames: ContactNames = ContactNames.EMPTY

        @Volatile
        private var contactEmails: ContactEmails = ContactEmails.EMPTY

        /** Latches the setting so contacts load once, not per settings change. */
        @Volatile
        private var contactsEnabled = false

        override fun onCreate() {
            val repository = SettingsRepository(context)
            scope.launch {
                repository.settings.collect { settings ->
                    val ordinal = settings.grammarDialect.ordinal
                    val dialectChanged = ordinal != dialectOrdinal
                    dialectOrdinal = ordinal
                    noSuggestionsUi = settings.spellCheckerNoSuggestions
                    // Warm on the first value too, not only on a change:
                    // building Harper's rule set takes ~100 ms, and the default
                    // dialect would otherwise leave the first underline late.
                    if (dialectChanged || !warmed) {
                        warmed = true
                        GrammarChecker.warmUp(ordinal)
                    }
                    if (settings.suggestionSources.contacts) {
                        if (!contactsEnabled) {
                            contactsEnabled = true
                            loadContacts()
                        }
                    } else {
                        contactsEnabled = false
                        contactNames = ContactNames.EMPTY
                        contactEmails = ContactEmails.EMPTY
                    }
                }
            }
        }

        override fun onClose() {
            scope.cancel()
        }

        /**
         * Reads contact names and email addresses into the in-memory indices.
         * No-op without the permission — the settings app requests it, and the
         * flow reloads once the setting is turned on after a grant.
         */
        private fun loadContacts() {
            if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            scope.launch {
                val loaded = withContext(Dispatchers.IO) {
                    runCatching {
                        val names = ArrayList<String>()
                        context.contentResolver.query(
                            ContactsContract.Contacts.CONTENT_URI,
                            arrayOf(ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                            null, null, null,
                        )?.use { cursor ->
                            val column = cursor.getColumnIndexOrThrow(
                                ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
                            )
                            while (cursor.moveToNext()) {
                                cursor.getString(column)?.let { names.add(it) }
                            }
                        }
                        val emails = ArrayList<String>()
                        context.contentResolver.query(
                            ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                            arrayOf(ContactsContract.CommonDataKinds.Email.ADDRESS),
                            null, null, null,
                        )?.use { cursor ->
                            val column = cursor.getColumnIndexOrThrow(
                                ContactsContract.CommonDataKinds.Email.ADDRESS
                            )
                            while (cursor.moveToNext()) {
                                cursor.getString(column)?.let { emails.add(it) }
                            }
                        }
                        ContactNames.fromNames(names) to ContactEmails.fromAddresses(emails)
                    }.getOrNull()
                }
                if (loaded != null) {
                    contactNames = loaded.first
                    contactEmails = loaded.second
                }
            }
        }

        /**
         * Sentence-level checking: one [SentenceSuggestionsInfo] per input,
         * each carrying a problem's offset and length so the host app can
         * underline exactly the span Harper flagged.
         */
        override fun onGetSentenceSuggestionsMultiple(
            textInfos: Array<out TextInfo>?,
            suggestionsLimit: Int,
        ): Array<SentenceSuggestionsInfo> {
            val inputs = textInfos ?: return emptyArray()
            if (!GrammarChecker.available) return inputs.map { emptySentence() }.toTypedArray()
            return inputs
                .map { info -> sentenceSuggestions(info, suggestionsLimit) }
                .toTypedArray()
        }

        /**
         * Per-word checking. The framework hands over one word with no
         * surrounding sentence, so only lints spanning the whole word are
         * meaningful — a grammar rule about the words around it has nothing
         * to work with here.
         */
        override fun onGetSuggestions(
            textInfo: TextInfo?,
            suggestionsLimit: Int,
        ): SuggestionsInfo {
            val word = textInfo?.text.orEmpty()
            if (!GrammarChecker.available || word.isBlank()) return noProblem()
            // A word that is one of the user's contacts is spelled right by
            // definition — never flag a name the phone knows.
            if (isKnownContact(word)) return noProblem()
            val lint = lint(word).firstOrNull { it.start == 0 && it.end == word.length }
                ?: return noProblem()
            return suggestionsInfo(word, lint, suggestionsLimit)
        }

        private fun sentenceSuggestions(
            info: TextInfo,
            suggestionsLimit: Int,
        ): SentenceSuggestionsInfo {
            val text = info.text.orEmpty()
            if (text.isBlank()) return emptySentence()
            val lints = lint(text)
                .filter { it.end > it.start }
                // Drop lints on a span that is a known contact name/address.
                .filterNot { isKnownContact(text.substring(it.start, it.end)) }
            if (lints.isEmpty()) return emptySentence()

            val infos = ArrayList<SuggestionsInfo>(lints.size)
            val offsets = ArrayList<Int>(lints.size)
            val lengths = ArrayList<Int>(lints.size)
            for (lint in lints) {
                val span = text.substring(lint.start, lint.end)
                infos.add(suggestionsInfo(span, lint, suggestionsLimit))
                offsets.add(lint.start)
                lengths.add(lint.end - lint.start)
            }
            return SentenceSuggestionsInfo(
                infos.toTypedArray(),
                offsets.toIntArray(),
                lengths.toIntArray(),
            )
        }

        private fun lint(text: String): List<GrammarLint> =
            // The framework's call is synchronous and already off the main
            // thread; GrammarChecker hops to its own single native thread.
            runBlocking { GrammarChecker.check(text, dialectOrdinal) }

        private fun suggestionsInfo(
            span: String,
            lint: GrammarLint,
            suggestionsLimit: Int,
        ): SuggestionsInfo {
            // "Squiggle only" mode: flag the typo so the underline stays, but
            // return no replacements and set the flag that asks the host to
            // skip the correction popup. Guarded to API 31 — the flag was added
            // in Android 12; below that the flag is meaningless and we would
            // just be dropping fixes the user still wants to see.
            if (noSuggestionsUi && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return SuggestionsInfo(
                    SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO or
                        SuggestionsInfo.RESULT_ATTR_DONT_SHOW_UI_FOR_SUGGESTIONS,
                    emptyArray(),
                )
            }
            val harper = lint.suggestions.mapNotNull { spellReplacementFor(span, it) }
            // A contact whose name the typo is close to leads the menu: fixing
            // a mistyped name to the right contact is the common case here.
            val replacements = (contactCorrections(span, suggestionsLimit) + harper)
                .filter { it.isNotBlank() && it != span }
                .distinct()
                .take(suggestionsLimit.coerceAtLeast(1))
            var flags = SuggestionsInfo.RESULT_ATTR_LOOKS_LIKE_TYPO
            if (replacements.isNotEmpty()) {
                flags = flags or SuggestionsInfo.RESULT_ATTR_HAS_RECOMMENDED_SUGGESTIONS
            }
            return SuggestionsInfo(flags, replacements.toTypedArray())
        }

        /** True when [span] is a contact name word or a known contact address. */
        private fun isKnownContact(span: String): Boolean {
            val s = span.trim().lowercase()
            if (s.isEmpty()) return false
            return if ('@' in s) contactEmails.contains(s) else contactNames.contains(s)
        }

        /**
         * Contact names close to [span] — a prefix of one, or a single typo
         * away — kept in their source capitalization for the correction menu.
         */
        private fun contactCorrections(span: String, limit: Int): List<String> {
            if (contactNames.isEmpty || limit <= 0) return emptyList()
            val s = span.lowercase()
            if (s.length < 2) return emptyList()
            val out = LinkedHashSet<String>()
            for (suggestion in contactNames.complete(s, limit)) {
                if (!suggestion.word.equals(span, ignoreCase = true)) out.add(suggestion.word)
            }
            if (out.size < limit) {
                for (variant in editsOne(s)) {
                    val display = contactNames.displayOf(variant) ?: continue
                    if (!display.equals(span, ignoreCase = true)) out.add(display)
                    if (out.size >= limit) break
                }
            }
            return out.take(limit).toList()
        }

        private fun noProblem() =
            SuggestionsInfo(SuggestionsInfo.RESULT_ATTR_IN_THE_DICTIONARY, emptyArray())

        private fun emptySentence() =
            SentenceSuggestionsInfo(emptyArray(), IntArray(0), IntArray(0))

        companion object {
            private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz"

            /** Every string one edit (Norvig) from [word]; deletions first. */
            private fun editsOne(word: String): Sequence<String> = sequence {
                for (i in word.indices) {
                    yield(word.substring(0, i) + word.substring(i + 1))
                }
                for (i in 0 until word.length - 1) {
                    yield(
                        word.substring(0, i) + word[i + 1] + word[i] + word.substring(i + 2)
                    )
                }
                for (i in word.indices) {
                    for (c in ALPHABET) {
                        yield(word.substring(0, i) + c + word.substring(i + 1))
                    }
                }
                for (i in 0..word.length) {
                    for (c in ALPHABET) {
                        yield(word.substring(0, i) + c + word.substring(i))
                    }
                }
            }
        }
    }
}
