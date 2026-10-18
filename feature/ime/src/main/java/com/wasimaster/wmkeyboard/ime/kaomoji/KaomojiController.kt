package com.wasimaster.wmkeyboard.ime.kaomoji

import com.wasimaster.wmkeyboard.core.emoji.KaomojiKind
import com.wasimaster.wmkeyboard.core.emoji.KaomojiLibrary
import com.wasimaster.wmkeyboard.core.emoji.KaomojiStore
import com.wasimaster.wmkeyboard.core.settings.KaomojiSettings
import com.wasimaster.wmkeyboard.core.settings.KaomojiStartView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

/**
 * The emoji panel's kaomoji tab: holds its [KaomojiUi], turns the tab's
 * [KaomojiAction]s into changes to the shared [KaomojiStore], and types into
 * the tab's text box through the service. Built with the service; the library
 * itself is read on first use, off the main thread.
 *
 * The settings app writes the same file. Its library screen bumps
 * `KaomojiSettings.version`, and the service calls [reload] when it moves, so
 * this side never saves its stale copy over the user's edit.
 */
internal class KaomojiController(
    private val scope: CoroutineScope,
    /** The library file, or null while the phone is locked after a reboot. */
    private val storeFile: () -> File?,
    private val settings: () -> KaomojiSettings,
    /** Commits a face to the field the way a text-art tap always has. */
    private val insertText: (String) -> Unit,
    private val copyText: (String) -> Unit,
    private val haptic: () -> Unit,
    private val openSettings: () -> Unit,
    /** Hands the keys to the tab's text box, with what it should start with. */
    private val beginText: (KaomojiTextEntry) -> Unit,
    /** Gives the keys back to the field. */
    private val endText: () -> Unit,
    private val currentText: () -> KaomojiTextEntry?,
) {
    private val _state = MutableStateFlow(KaomojiUi())
    val state: StateFlow<KaomojiUi> = _state

    val callbacks = KaomojiCallbacks(state = state, onAction = ::dispatch)

    @Volatile
    private var store: KaomojiStore? = null
    private var loading: Job? = null
    private var saveJob: Job? = null

    /** The library as last published, for the suggestion strip and the emoji search. */
    val library: KaomojiLibrary get() = _state.value.library

    /** Reads the library if nothing has yet. Cheap to call on every panel open. */
    fun ensureLoaded() {
        if (store != null || loading?.isActive == true) return
        loading = scope.launch(Dispatchers.IO) {
            val loaded = KaomojiStore(storeFile())
            store = loaded
            publish(loaded)
        }
    }

    /** The phone was unlocked after a reboot: the real file can be read now. */
    fun reattach() {
        loading?.cancel()
        store = null
        ensureLoaded()
    }

    /** The emoji panel opened: catch up with the settings app, and pick each tab's starting tag. */
    fun onPanelOpen() {
        // A box left open by a panel that closed some other way (an insert
        // that returns to the keys) has nothing to type into any more.
        if (currentText() == null) {
            _state.update { it.copy(searching = false, query = "", edit = null, tagRename = null) }
        }
        val current = store
        if (current == null) {
            ensureLoaded()
        } else {
            scope.launch(Dispatchers.IO) { if (current.reloadIfChanged()) publish(current) }
        }
        val start = settings().startView
        _state.update { ui ->
            val views = if (start == KaomojiStartView.LAST_USED) {
                ui.views
            } else {
                val view = startView(start, ui.library)
                mapOf(KAOMOJI_TAB_ID to view, EMOTICON_TAB_ID to view)
            }
            ui.copy(views = views, held = null, confirmDelete = null, editMode = false, tagConfirmDelete = null)
        }
    }

    /** The panel closed: the box gives the keys back, a half-made face is dropped. */
    fun onPanelClose() {
        if (currentText() != null) endText()
        _state.update {
            it.copy(
                searching = false,
                query = "",
                edit = null,
                tagRename = null,
                held = null,
                confirmDelete = null,
                tagConfirmDelete = null,
                tagsOpen = false,
                editMode = false,
            )
        }
    }

    /** The settings app or a restore rewrote the file. */
    fun reload() {
        val current = store ?: return ensureLoaded()
        scope.launch(Dispatchers.IO) {
            current.reload()
            publish(current)
        }
    }

    /** Whether [text] is a face from the library, so a strip tap knows not to file it as an emoji. */
    fun owns(text: String): Boolean = library.items.any { it.text == text }

    /** Faces to offer in the suggestion strip for the typed [word]; none while the setting is off. */
    fun suggestionsFor(word: String): List<String> {
        if (!settings().suggest) return emptyList()
        return library.suggestionsFor(word, SUGGESTION_LIMIT).map { it.text }
    }

    /** A face went into the field from outside the tab (the strip, the emoji search). */
    fun recordUse(text: String) {
        mutate(save = true) { it.recordUse(text) }
    }

    // ---- the text box ----------------------------------------------------

    /** Called by the service as the box is typed into. */
    fun onText(entry: KaomojiTextEntry) {
        when (entry.field) {
            KaomojiTextField.SEARCH -> _state.update { it.copy(query = entry.text) }
            KaomojiTextField.FACE -> _state.update { ui ->
                ui.copy(edit = ui.edit?.copy(text = entry.text.take(KaomojiStore.MAX_TEXT_LENGTH), confirmDelete = false))
            }
            KaomojiTextField.TAG -> _state.update { ui ->
                val draft = entry.text.replace("\n", " ").take(KaomojiStore.MAX_TAG_LENGTH)
                if (ui.edit != null) ui.copy(edit = ui.edit.copy(tagDraft = draft)) else ui
            }
        }
    }

    /** Enter in the box. */
    fun onTextEnter(entry: KaomojiTextEntry) {
        when (entry.field) {
            // The search filters as it is typed; Enter puts the first match in.
            KaomojiTextField.SEARCH -> {
                val first = _state.value.let { ui -> ui.library.search(ui.query, null).firstOrNull() }
                if (first != null) dispatch(KaomojiAction.Insert(first.text))
            }
            KaomojiTextField.FACE -> dispatch(KaomojiAction.EditorSave)
            KaomojiTextField.TAG -> {
                val ui = _state.value
                if (ui.edit != null) addEditorTag(entry.text) else finishRename(entry.text)
            }
        }
    }

    // ---- actions -------------------------------------------------------

    @Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod")
    private fun dispatch(action: KaomojiAction) {
        when (action) {
            is KaomojiAction.Insert -> {
                insertText(action.text)
                recordUse(action.text)
            }
            is KaomojiAction.SelectView -> {
                haptic()
                _state.update { it.copy(views = it.views + (action.tab to action.view), held = null, confirmDelete = null) }
            }
            is KaomojiAction.Hold -> {
                if (action.id != null) haptic()
                _state.update { it.copy(held = action.id, confirmDelete = null) }
            }
            is KaomojiAction.Favourite -> {
                haptic()
                mutate { s -> s.item(action.id)?.let { s.setFavourite(it.id, !it.favourite) } }
            }
            is KaomojiAction.Copy -> {
                _state.value.library.items.firstOrNull { it.id == action.id }?.let { copyText(it.text) }
                _state.update { it.copy(held = null) }
            }
            is KaomojiAction.Delete -> {
                haptic()
                if (_state.value.confirmDelete != action.id) {
                    _state.update { it.copy(confirmDelete = action.id) }
                } else {
                    mutate { it.delete(action.id) }
                    _state.update { it.copy(held = null, confirmDelete = null) }
                }
            }
            is KaomojiAction.Edit -> {
                val item = _state.value.library.items.firstOrNull { it.id == action.id } ?: return
                openEditor(KaomojiEdit(id = item.id, text = item.text, tags = item.tags, kind = item.kind))
            }
            is KaomojiAction.Add -> {
                val ui = _state.value
                val view = ui.view(action.tab)
                val seedTag = view.takeIf { !it.startsWith('\u0001') }
                openEditor(
                    KaomojiEdit(
                        id = null,
                        tags = listOfNotNull(seedTag),
                        kind = if (action.tab == EMOTICON_TAB_ID && settings().splitEmoticons) KaomojiKind.EMOTICON else null,
                    ),
                )
            }
            KaomojiAction.ToggleEditMode -> {
                haptic()
                _state.update { it.copy(editMode = !it.editMode, held = null, confirmDelete = null, tagsOpen = false) }
            }
            is KaomojiAction.Reorder -> mutate { it.reorder(action.ids) }
            KaomojiAction.ToggleSearch -> {
                haptic()
                if (_state.value.searching) {
                    closeText()
                } else {
                    _state.update { it.copy(searching = true, query = "", held = null, tagsOpen = false) }
                    beginText(KaomojiTextEntry(KaomojiTextField.SEARCH))
                }
            }
            KaomojiAction.ToggleTags -> {
                haptic()
                _state.update { it.copy(tagsOpen = !it.tagsOpen, editMode = false, held = null, tagConfirmDelete = null) }
            }
            is KaomojiAction.TagMove -> mutate { s ->
                val tags = s.library().tags.toMutableList()
                val from = tags.indexOfFirst { it.equals(action.name, ignoreCase = true) }
                val to = (from + action.delta).coerceIn(0, tags.lastIndex)
                if (from >= 0 && from != to) {
                    tags.add(to, tags.removeAt(from))
                    s.reorderTags(tags)
                }
            }
            is KaomojiAction.TagHidden -> mutate { it.setTagHidden(action.name, action.hidden) }
            is KaomojiAction.TagRename -> {
                haptic()
                _state.update { it.copy(tagRename = action.name, tagConfirmDelete = null) }
                beginText(KaomojiTextEntry(KaomojiTextField.TAG, action.name))
            }
            is KaomojiAction.TagDelete -> {
                haptic()
                if (_state.value.tagConfirmDelete != action.name) {
                    _state.update { it.copy(tagConfirmDelete = action.name) }
                } else {
                    mutate { it.removeTag(action.name) }
                    _state.update { ui ->
                        ui.copy(
                            tagConfirmDelete = null,
                            views = ui.views.mapValues { (_, v) ->
                                if (v.equals(action.name, ignoreCase = true)) KaomojiLibrary.VIEW_ALL else v
                            },
                        )
                    }
                }
            }
            is KaomojiAction.EditorFocus -> focusEditor(action.field)
            is KaomojiAction.EditorTag -> _state.update { ui ->
                val edit = ui.edit ?: return
                val has = edit.tags.any { it.equals(action.name, ignoreCase = true) }
                ui.copy(
                    edit = edit.copy(
                        tags = if (has) edit.tags.filterNot { it.equals(action.name, ignoreCase = true) } else edit.tags + action.name,
                    ),
                )
            }
            is KaomojiAction.EditorKind -> _state.update { ui -> ui.copy(edit = ui.edit?.copy(kind = action.kind)) }
            KaomojiAction.EditorSave -> saveEditor()
            KaomojiAction.EditorCancel -> {
                _state.update { it.copy(edit = null) }
                closeText()
            }
            KaomojiAction.EditorDelete -> {
                val edit = _state.value.edit ?: return
                haptic()
                if (edit.id == null) return dispatch(KaomojiAction.EditorCancel)
                if (!edit.confirmDelete) {
                    _state.update { it.copy(edit = edit.copy(confirmDelete = true)) }
                } else {
                    mutate { it.delete(edit.id) }
                    dispatch(KaomojiAction.EditorCancel)
                }
            }
            KaomojiAction.CloseText -> closeText()
            KaomojiAction.ClearRecents -> {
                haptic()
                mutate { it.clearRecents() }
            }
            KaomojiAction.OpenSettings -> openSettings()
        }
    }

    private fun openEditor(edit: KaomojiEdit) {
        haptic()
        _state.update { it.copy(edit = edit, held = null, confirmDelete = null, searching = false, tagRename = null) }
        beginText(KaomojiTextEntry(KaomojiTextField.FACE, edit.text))
    }

    private fun focusEditor(field: KaomojiTextField) {
        val edit = _state.value.edit ?: return
        if (field == KaomojiTextField.SEARCH || edit.focused == field) return
        _state.update { it.copy(edit = edit.copy(focused = field)) }
        beginText(KaomojiTextEntry(field, if (field == KaomojiTextField.FACE) edit.text else edit.tagDraft))
    }

    private fun addEditorTag(raw: String) {
        val name = KaomojiStore.cleanTag(raw)
        _state.update { ui ->
            val edit = ui.edit ?: return@update ui
            val tags = if (name == null || edit.tags.any { it.equals(name, ignoreCase = true) }) edit.tags else edit.tags + name
            ui.copy(edit = edit.copy(tags = tags, tagDraft = ""))
        }
        // Back to the face, which is what the sheet is about.
        focusEditor(KaomojiTextField.FACE)
    }

    private fun saveEditor() {
        val edit = _state.value.edit ?: return
        val text = KaomojiStore.cleanText(edit.text) ?: return
        // A tag typed but not yet added with Enter is still meant.
        val tags = KaomojiStore.cleanTag(edit.tagDraft)
            ?.takeIf { draft -> edit.tags.none { it.equals(draft, ignoreCase = true) } }
            ?.let { edit.tags + it } ?: edit.tags
        haptic()
        mutate { s ->
            if (edit.id == null) {
                s.add(text, tags, edit.kind)
            } else {
                s.update(edit.id, text, tags, edit.kind ?: KaomojiKind.of(text))
            }
        }
        _state.update { it.copy(edit = null) }
        closeText()
    }

    private fun finishRename(raw: String) {
        val from = _state.value.tagRename ?: return closeText()
        val name = KaomojiStore.cleanTag(raw)
        if (name != null) {
            mutate { s -> if (from.isEmpty()) s.addTag(name) else s.renameTag(from, name) }
            if (from.isNotEmpty()) {
                _state.update { ui ->
                    ui.copy(views = ui.views.mapValues { (_, v) -> if (v.equals(from, ignoreCase = true)) name else v })
                }
            }
        }
        closeText()
    }

    private fun closeText() {
        _state.update { it.copy(searching = false, query = "", tagRename = null, edit = null) }
        if (currentText() != null) endText()
    }

    /**
     * Applies [change] to the store and publishes the result. The store does
     * its own locking; the write goes to disk a moment later, so a run of taps
     * is one save.
     */
    private fun mutate(save: Boolean = true, change: (KaomojiStore) -> Unit) {
        val s = store
        if (s == null) {
            ensureLoaded()
            return
        }
        change(s)
        publish(s)
        if (save) scheduleSave(s)
    }

    private fun scheduleSave(s: KaomojiStore) {
        saveJob?.cancel()
        saveJob = scope.launch(Dispatchers.IO) {
            delay(SAVE_DELAY_MS)
            s.save()
        }
    }

    private fun publish(s: KaomojiStore) {
        val library = s.library()
        _state.update { ui ->
            // A tag that went away takes its tab's choice with it.
            val tags = library.tags.map { it.lowercase(Locale.ROOT) }.toSet()
            val views = ui.views.mapValues { (_, v) ->
                if (v.startsWith('\u0001') || v.lowercase(Locale.ROOT) in tags) v else KaomojiLibrary.VIEW_ALL
            }
            ui.copy(library = library, views = views)
        }
    }

    private fun startView(start: KaomojiStartView, library: KaomojiLibrary): String = when (start) {
        KaomojiStartView.RECENT ->
            if (library.items.any { it.lastUsedAt > 0 }) KaomojiLibrary.VIEW_RECENT else KaomojiLibrary.VIEW_ALL
        KaomojiStartView.FAVOURITES ->
            if (library.items.any { it.favourite }) KaomojiLibrary.VIEW_FAVOURITES else KaomojiLibrary.VIEW_ALL
        KaomojiStartView.ALL, KaomojiStartView.LAST_USED -> KaomojiLibrary.VIEW_ALL
    }

    companion object {
        /** The tabs' ids; the same strings as the panel's `KAOMOJI_TAB` and `EMOTICON_TAB`. */
        const val KAOMOJI_TAB_ID = "★kaomoji"
        const val EMOTICON_TAB_ID = "★emoticon"
        private const val SAVE_DELAY_MS = 600L
        private const val SUGGESTION_LIMIT = 2
    }
}
