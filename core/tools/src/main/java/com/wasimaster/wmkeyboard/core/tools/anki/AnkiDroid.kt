package com.wasimaster.wmkeyboard.core.tools.anki

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Locale

/**
 * A looked-up word as an AnkiDroid note (#765).
 *
 * Two ways in. [add] talks to AnkiDroid's own content provider, the one its
 * Instant-Add API wraps, and puts the note straight into a deck; that needs
 * AnkiDroid's permission ([PERMISSION]), which the user grants like any other.
 * [editorIntent] needs nothing: it opens AnkiDroid's note editor filled in,
 * and the user saves the note there.
 *
 * The provider is spoken to directly, with the URIs and columns of
 * AnkiDroid's `FlashCardsContract`, rather than through its API library: that
 * library is published only on JitPack, which the F-Droid and the no-internet
 * builds cannot take.
 */
object AnkiDroid {

    const val PACKAGE = "com.ichi2.anki"
    const val PERMISSION = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

    private const val AUTHORITY = "com.ichi2.anki.flashcards"
    private val AUTHORITY_URI: Uri = Uri.parse("content://$AUTHORITY")
    private val NOTES: Uri = Uri.withAppendedPath(AUTHORITY_URI, "notes")
    private val NOTES_V2: Uri = Uri.withAppendedPath(AUTHORITY_URI, "notes_v2")
    private val MODELS: Uri = Uri.withAppendedPath(AUTHORITY_URI, "models")
    private val DECKS: Uri = Uri.withAppendedPath(AUTHORITY_URI, "decks")

    private const val FIELD_SEPARATOR = "\u001f"
    private const val PROVIDER_SPEC_KEY = "com.ichi2.anki.provider.spec"

    /** The note type this app makes in AnkiDroid, once. Its first field is what duplicates are found by. */
    const val NOTE_TYPE = "WM Keyboard Look-up"

    /** The note type's fields, in order. */
    val FIELDS = listOf("Word", "Reading", "Pitch", "Meaning", "Sentence", "Source")

    private const val FRONT = """<div class="word">{{Word}}</div>{{#Sentence}}<div class="sentence">{{Sentence}}</div>{{/Sentence}}"""
    private const val BACK = """{{FrontSide}}<hr id="answer">""" +
        """{{#Reading}}<div class="reading">{{Reading}}</div>{{/Reading}}""" +
        """{{#Pitch}}<div class="pitch">{{Pitch}}</div>{{/Pitch}}""" +
        """<div class="meaning">{{Meaning}}</div>""" +
        """{{#Source}}<div class="source">{{Source}}</div>{{/Source}}"""
    private const val CSS = ".card { font-family: sans-serif; font-size: 20px; text-align: center; }\n" +
        ".word { font-size: 40px; }\n.sentence { font-size: 18px; opacity: 0.8; margin-top: 12px; }\n" +
        ".reading { font-size: 24px; }\n.pitch, .source { font-size: 14px; opacity: 0.7; }\n" +
        ".meaning { text-align: left; margin-top: 12px; }"

    /** What goes into one note. [meaning] may hold simple HTML (line breaks, lists). */
    data class Note(
        val word: String,
        val reading: String = "",
        val pitch: String = "",
        val meaning: String = "",
        val sentence: String = "",
        val source: String = "",
    ) {
        val fields: List<String> get() = listOf(word, reading, pitch, meaning, sentence, source)
    }

    sealed interface Result {
        data object Added : Result

        /** The deck's note type already has a note for this word. */
        data object AlreadyThere : Result

        /** AnkiDroid is not installed, or too old to have the provider. */
        data object NotInstalled : Result

        /** AnkiDroid is there, but the user has not let this app add to it. */
        data object NoPermission : Result
        data object Failed : Result
    }

    fun isInstalled(context: Context): Boolean = providerPackage(context) != null

    fun hasPermission(context: Context): Boolean =
        context.checkPermission(PERMISSION, Process.myPid(), Process.myUid()) == PackageManager.PERMISSION_GRANTED

    private fun providerPackage(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveContentProvider(AUTHORITY, PackageManager.ComponentInfoFlags.of(0L))?.packageName
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveContentProvider(AUTHORITY, 0)?.packageName
        }
    }.getOrNull()

    /** The provider's spec version: 2 and up can be asked for duplicates by checksum. */
    private fun providerSpec(context: Context): Int = runCatching {
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.resolveContentProvider(AUTHORITY, PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.resolveContentProvider(AUTHORITY, PackageManager.GET_META_DATA)
        }
        info?.metaData?.getInt(PROVIDER_SPEC_KEY, 1) ?: 1
    }.getOrDefault(1)

    /**
     * Adds [note] to [deckName], making the deck and the note type on first
     * use. Blocking (several provider calls): call it off the main thread.
     */
    fun add(context: Context, note: Note, deckName: String): Result {
        if (!isInstalled(context)) return Result.NotInstalled
        if (!hasPermission(context)) return Result.NoPermission
        val resolver = context.contentResolver
        return try {
            val deckId = deckId(resolver, deckName) ?: return Result.Failed
            val modelId = modelId(resolver, deckId) ?: return Result.Failed
            if (providerSpec(context) >= 2 && isDuplicate(resolver, modelId, note.word)) return Result.AlreadyThere
            val values = ContentValues().apply {
                put("mid", modelId)
                put("flds", note.fields.joinToString(FIELD_SEPARATOR))
                put("tags", "wm_keyboard")
            }
            val noteUri = resolver.insert(NOTES, values) ?: return Result.Failed
            moveCards(resolver, noteUri, deckId)
            Result.Added
        } catch (_: SecurityException) {
            Result.NoPermission
        } catch (_: RuntimeException) {
            Result.Failed
        }
    }

    /** The new note's cards go to [deckId]; AnkiDroid files them under the note type's deck otherwise. */
    private fun moveCards(resolver: ContentResolver, noteUri: Uri, deckId: Long) {
        val cards = Uri.withAppendedPath(noteUri, "cards")
        resolver.query(cards, null, null, null, null)?.use { cursor ->
            val ord = cursor.getColumnIndex("ord")
            while (ord >= 0 && cursor.moveToNext()) {
                val values = ContentValues().apply { put("deck_id", deckId) }
                resolver.update(Uri.withAppendedPath(cards, cursor.getString(ord)), values, null, null)
            }
        }
    }

    private fun deckId(resolver: ContentResolver, name: String): Long? {
        resolver.query(DECKS, null, null, null, null)?.use { cursor ->
            val nameColumn = cursor.getColumnIndex("deck_name")
            val idColumn = cursor.getColumnIndex("deck_id")
            while (nameColumn >= 0 && idColumn >= 0 && cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == name) return cursor.getLong(idColumn)
            }
        }
        val values = ContentValues().apply { put("deck_name", name) }
        return resolver.insert(DECKS, values)?.lastPathSegment?.toLongOrNull()
    }

    private fun modelId(resolver: ContentResolver, deckId: Long): Long? {
        resolver.query(MODELS, null, null, null, null)?.use { cursor ->
            val nameColumn = cursor.getColumnIndex("name")
            val idColumn = cursor.getColumnIndex("_id")
            while (nameColumn >= 0 && idColumn >= 0 && cursor.moveToNext()) {
                if (cursor.getString(nameColumn) == NOTE_TYPE) return cursor.getLong(idColumn)
            }
        }
        val values = ContentValues().apply {
            put("name", NOTE_TYPE)
            put("field_names", FIELDS.joinToString(FIELD_SEPARATOR))
            put("num_cards", 1)
            put("css", CSS)
            put("deck_id", deckId)
            put("sort_field_index", 0)
        }
        val modelUri = resolver.insert(MODELS, values) ?: return null
        val template = ContentValues().apply {
            put("card_template_name", "Recognition")
            put("question_format", FRONT)
            put("answer_format", BACK)
        }
        resolver.update(Uri.withAppendedPath(Uri.withAppendedPath(modelUri, "templates"), "0"), template, null, null)
        return modelUri.lastPathSegment?.toLongOrNull()
    }

    private fun isDuplicate(resolver: ContentResolver, modelId: Long, word: String): Boolean {
        val selection = String.format(Locale.US, "mid=%d and csum in (%d)", modelId, fieldChecksum(word))
        resolver.query(NOTES_V2, arrayOf("_id", "flds"), selection, null, null)?.use { cursor ->
            while (cursor.moveToNext()) {
                if (cursor.getString(1).orEmpty().substringBefore(FIELD_SEPARATOR) == word) return true
            }
        }
        return false
    }

    /**
     * Anki's checksum of a note's first field: the first eight hex digits of
     * the SHA-1 of its text. The field is plain text here, so there is no
     * HTML to strip first.
     */
    internal fun fieldChecksum(text: String): Long {
        val digest = MessageDigest.getInstance("SHA1").digest(text.toByteArray(Charsets.UTF_8))
        return BigInteger(1, digest).toString(16).padStart(40, '0').take(8).toLong(16)
    }

    /**
     * AnkiDroid's note editor, filled in with [note], for the user to save:
     * the way in that needs no permission. Null when AnkiDroid is not there.
     */
    fun editorIntent(context: Context, note: Note): Intent? {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .setPackage(PACKAGE)
            .putExtra(Intent.EXTRA_SUBJECT, note.word)
            .putExtra(Intent.EXTRA_TEXT, editorBack(note))
        return intent.takeIf { context.packageManager.queryIntentActivities(it, 0).isNotEmpty() }
    }

    /** The back of the card when it goes through the editor: everything but the word, one item a line. */
    internal fun editorBack(note: Note): String =
        listOf(note.reading, note.pitch, note.meaning.replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), ""), note.sentence, note.source)
            .filter { it.isNotBlank() }
            .joinToString("\n")
}
