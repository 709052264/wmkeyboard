package com.wasimaster.wmkeyboard.ime.gallery

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.wasimaster.wmkeyboard.app.GalleryPickActivity
import com.wasimaster.wmkeyboard.app.GalleryPermissionActivity
import com.wasimaster.wmkeyboard.core.media.gallery.AlbumKind
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryAlbum
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryItem
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryKind
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryQuality
import com.wasimaster.wmkeyboard.core.media.gallery.GallerySentHistory
import com.wasimaster.wmkeyboard.core.media.gallery.GalleryStore
import com.wasimaster.wmkeyboard.core.media.gallery.MediaPrep
import com.wasimaster.wmkeyboard.core.media.gallery.edit.EditHistory
import com.wasimaster.wmkeyboard.core.media.gallery.edit.PhotoEdit
import com.wasimaster.wmkeyboard.core.media.gallery.edit.PhotoEditRenderer
import com.wasimaster.wmkeyboard.core.media.gallery.edit.StickerLayer
import com.wasimaster.wmkeyboard.core.media.gallery.edit.TextLayer
import com.wasimaster.wmkeyboard.core.settings.GalleryCaption
import com.wasimaster.wmkeyboard.core.settings.GalleryFallback
import com.wasimaster.wmkeyboard.core.settings.GallerySettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The keyboard half of the gallery tool (#708).
 *
 * Holds the panel's [GalleryUi]: the albums, the open album's rows paged in
 * from [GalleryStore], the selection, each picked photo's edit, and a send in
 * progress. Turns the panel's [GalleryAction]s into changes to that state,
 * into copies made by [MediaPrep], and into commits through the [Host]. Owned
 * by the service and built on first use; nothing here runs until the panel is
 * opened or a picker hands something back.
 */
internal class GalleryController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val host: Host,
) {

    /** What the controller needs from the service, which owns the field and the keyboard's state. */
    interface Host {
        val settings: GallerySettings
        fun updateSettings(change: (GallerySettings) -> GallerySettings)
        fun haptic()

        /** Whether the field is incognito: nothing is remembered from it. */
        fun incognito(): Boolean

        /** Which field is open now, to stop a send that outlives it. */
        fun field(): FieldKey?

        /** The MIME types the open field takes through the keyboard. */
        fun acceptedMimes(): List<String>

        /** Puts [file] into the field. False when the field would not take it. */
        fun commit(file: File, mime: String): Boolean
        fun insertText(text: String)
        fun openCamera()
        fun closePanel()
        fun openSettings()
        fun beginText(entry: GalleryTextEntry)
        fun endText()
        fun currentText(): GalleryTextEntry?
        fun saveToGallery(file: File, mime: String)
        fun stickers(): List<StickerChoice>

        /** A FileProvider URI for a copy in the media cache, for share and copy. */
        fun fileUri(file: File): Uri?
    }

    /** One field: the app it is in and its id, which together outlive a keyboard restart. */
    data class FieldKey(val packageName: String?, val fieldId: Int?)

    private val app = context.applicationContext
    private val _state = MutableStateFlow(GalleryUi())
    val state: StateFlow<GalleryUi> = _state

    val callbacks = GalleryCallbacks(state = state, onAction = ::dispatch, stickers = { host.stickers() })

    private val history by lazy { GallerySentHistory.get(app) }
    private var observer: ContentObserver? = null
    private var loadJob: Job? = null
    private var albumsJob: Job? = null
    private var refreshJob: Job? = null
    private var sendJob: Job? = null
    private var noticeJob: Job? = null
    private var open = false

    /** The field the picks were made for; a different one drops them. */
    private var lastField: FieldKey? = null

    /** Kept across one close: the camera tile closes the panel and its photo comes back to the same picks. */
    private var keepPicksOnClose = false

    /** Files the Ask fallback is holding while the user chooses. */
    private var refused: List<MediaPrep.Prepared> = emptyList()
    private var refusedCaption: String = ""

    /** Ids for picker results and new layers; negative for picks so they never meet a library row's id. */
    private var nextPickId = -1L
    private var nextLayerId = 1L

    // ---- Lifecycle --------------------------------------------------------

    fun onPanelOpen() {
        open = true
        noteField()
        val s = host.settings
        _state.update {
            it.copy(
                columns = s.columns,
                quality = if (it.selection.isEmpty()) s.quality else it.quality,
                expanded = s.startExpanded,
                albumsOpen = false,
                viewer = null,
                editor = null,
                peek = null,
                sendMenuOpen = false,
            )
        }
        refreshField()
        refreshAccess(reload = true)
        if (observer == null) {
            observer = GalleryStore.watch(app) { scheduleRefresh() }
        }
        GalleryPickActivity.consume().takeIf { it.isNotEmpty() }?.let(::addPicked)
    }

    fun onPanelClose() {
        open = false
        endTextIfAny()
        observer?.let { GalleryStore.stopWatching(app, it) }
        observer = null
        loadJob?.cancel()
        refreshJob?.cancel()
        val keep = keepPicksOnClose
        keepPicksOnClose = false
        _state.update {
            it.copy(
                viewer = null,
                editor = null,
                peek = null,
                albumsOpen = false,
                sendMenuOpen = false,
                askFallback = 0,
                notice = null,
                selection = if (keep || it.sending != null) it.selection else emptyList(),
                edits = if (keep || it.sending != null) it.edits else emptyMap(),
                caption = if (keep) it.caption else "",
            )
        }
    }

    /**
     * Back from somewhere: a grant answered, or a system picker. Picks made
     * with the panel up join the selection; with it down (a Play build, where
     * the tool is the picker) they go straight out.
     */
    fun onKeyboardShown() {
        noteField()
        if (open) {
            refreshField()
            refreshAccess(reload = _state.value.access == GalleryStore.Access.NONE)
        }
        val picks = GalleryPickActivity.consume()
        if (picks.isEmpty()) return
        if (open) addPicked(picks) else sendPicked(picks)
    }

    /**
     * Picks made for one field do not carry over to another. Coming back from
     * a picker or a grant is the same field again, so those keep them.
     */
    private fun noteField() {
        val now = host.field()
        if (lastField != null && now != lastField) onFieldChanged()
        lastField = now
    }

    private fun onFieldChanged() {
        if (_state.value.sending != null) return
        _state.update { it.copy(selection = emptyList(), edits = emptyMap(), caption = "", askFallback = 0) }
        refused = emptyList()
    }

    fun onFinish() {
        scope.launch(Dispatchers.IO) { MediaPrep.sweep(app) }
    }

    private fun refreshField() {
        val accepted = host.acceptedMimes()
        fun takes(mime: String) = accepted.any { ClipDescription.compareMimeTypes(mime, it) }
        _state.update {
            it.copy(fieldTakesImages = takes("image/jpeg") || takes("image/png"), fieldTakesVideos = takes("video/mp4"))
        }
    }

    private fun refreshAccess(reload: Boolean) {
        val access = GalleryStore.access(app)
        val changed = access != _state.value.access
        _state.update { it.copy(access = access) }
        if (access != GalleryStore.Access.NONE && (reload || changed)) {
            loadAlbums(restore = true)
            load(reset = true)
        }
    }

    private fun scheduleRefresh() {
        refreshJob?.cancel()
        refreshJob = scope.launch {
            // A burst of changes (a sync, a burst shot) is one refresh.
            delay(REFRESH_DEBOUNCE_MS)
            loadAlbums(restore = false)
            load(reset = true, keepCount = true)
        }
    }

    // ---- Loading -----------------------------------------------------------

    private fun sentIds(): List<Long> = if (host.settings.keepHistory) history.ids() else emptyList()

    private fun loadAlbums(restore: Boolean) {
        albumsJob?.cancel()
        albumsJob = scope.launch {
            val videos = host.settings.showVideos
            val sent = withContext(Dispatchers.IO) { sentIds() }
            val albums = withContext(Dispatchers.IO) { GalleryStore.albums(app, videos, sent) }
            val remembered = host.settings.lastAlbum
            _state.update { s ->
                val current = if (restore && host.settings.rememberAlbum && remembered.isNotEmpty()) {
                    albums.firstOrNull { it.key == remembered } ?: s.album
                } else {
                    albums.firstOrNull { it.key == s.album.key } ?: s.album
                }
                s.copy(albums = albums, album = current, sentIds = sent.toHashSet())
            }
            if (restore && _state.value.items.isEmpty()) load(reset = true)
        }
    }

    private fun load(reset: Boolean, keepCount: Boolean = false) {
        val s = _state.value
        if (s.access == GalleryStore.Access.NONE) return
        if (!reset && (s.loading || s.endReached)) return
        loadJob?.cancel()
        val offset = if (reset) 0 else s.items.size
        val limit = if (reset && keepCount) maxOf(PAGE, s.items.size) else PAGE
        val album = s.album
        val filter = s.filter
        _state.update { it.copy(loading = true) }
        loadJob = scope.launch {
            val videos = host.settings.showVideos
            val sent = if (album.kind == AlbumKind.SENT) withContext(Dispatchers.IO) { sentIds() } else emptyList()
            val page = withContext(Dispatchers.IO) {
                GalleryStore.page(app, album, filter, offset, limit, videos, sent)
            }
            _state.update {
                if (it.album.key != album.key || it.filter != filter) return@update it
                it.copy(
                    items = if (reset) page else it.items + page,
                    loading = false,
                    endReached = page.size < limit,
                )
            }
        }
    }

    // ---- Actions -----------------------------------------------------------

    @Suppress("detekt.CyclomaticComplexMethod", "detekt.LongMethod")
    private fun dispatch(action: GalleryAction) {
        when (action) {
            GalleryAction.AskPermission -> launch(Intent(app, GalleryPermissionActivity::class.java))
            GalleryAction.PickWithSystem -> launch(GalleryPickActivity.intent(app, files = false, limit = room()))
            GalleryAction.PickFiles -> launch(GalleryPickActivity.intent(app, files = true, limit = room()))
            GalleryAction.OpenCamera -> {
                keepPicksOnClose = true
                host.openCamera()
            }
            GalleryAction.OpenSettings -> host.openSettings()

            is GalleryAction.OpenAlbum -> {
                _state.update {
                    it.copy(album = action.album, albumsOpen = false, items = emptyList(), endReached = false)
                }
                if (host.settings.rememberAlbum) host.updateSettings { it.copy(lastAlbum = action.album.key) }
                load(reset = true)
            }
            GalleryAction.ToggleAlbums -> _state.update { it.copy(albumsOpen = !it.albumsOpen) }
            is GalleryAction.SetFilter -> {
                if (action.filter == _state.value.filter) return
                _state.update { it.copy(filter = action.filter, items = emptyList(), endReached = false) }
                load(reset = true)
            }
            GalleryAction.LoadMore -> load(reset = false)
            is GalleryAction.SetColumns -> {
                val c = action.columns.coerceIn(GallerySettings.MIN_COLUMNS, GallerySettings.MAX_COLUMNS)
                if (c == _state.value.columns) return
                _state.update { it.copy(columns = c) }
                host.updateSettings { it.copy(columns = c) }
            }
            GalleryAction.ToggleExpanded -> _state.update { it.copy(expanded = !it.expanded) }
            is GalleryAction.SetExpanded -> _state.update { it.copy(expanded = action.on) }
            is GalleryAction.Peek -> _state.update { it.copy(peek = action.item) }

            is GalleryAction.Toggle -> toggle(action.item)
            is GalleryAction.SelectRange -> selectRange(action.items, action.select)
            is GalleryAction.Remove -> remove(action.id)
            is GalleryAction.Move -> _state.update { s ->
                if (action.from !in s.selection.indices || action.to !in s.selection.indices) {
                    s
                } else {
                    s.copy(selection = s.selection.toMutableList().apply { add(action.to, removeAt(action.from)) })
                }
            }
            GalleryAction.ClearSelection -> {
                host.haptic()
                _state.update { it.copy(selection = emptyList(), edits = emptyMap(), sendMenuOpen = false) }
            }

            is GalleryAction.OpenViewer -> _state.update {
                it.copy(viewer = ViewerUi(fromSelection = action.fromSelection, index = action.index), peek = null)
            }
            is GalleryAction.ViewerPage -> _state.update { it.copy(viewer = it.viewer?.copy(index = action.index)) }
            GalleryAction.ViewerChrome -> _state.update { s -> s.copy(viewer = s.viewer?.let { v -> v.copy(chrome = !v.chrome) }) }
            GalleryAction.ViewerMute -> _state.update { s -> s.copy(viewer = s.viewer?.let { v -> v.copy(muted = !v.muted) }) }
            GalleryAction.ViewerInfo -> _state.update { s -> s.copy(viewer = s.viewer?.let { v -> v.copy(infoOpen = !v.infoOpen) }) }
            GalleryAction.CloseViewer -> {
                endTextIfAny()
                _state.update { it.copy(viewer = null) }
            }

            is GalleryAction.SetQuality -> _state.update { it.copy(quality = action.quality) }
            GalleryAction.CaptionFocus -> {
                if (host.settings.caption == GalleryCaption.OFF) return
                host.beginText(GalleryTextEntry(GalleryTextField.CAPTION, _state.value.caption))
            }
            GalleryAction.TextDone -> host.currentText()?.let(::onTextEnter) ?: endTextIfAny()
            is GalleryAction.Send -> send(action.mode)
            is GalleryAction.SendMenu -> _state.update { it.copy(sendMenuOpen = action.open) }
            is GalleryAction.FallbackChoice -> fallbackChoice(action.share)
            GalleryAction.NoticeDismiss -> _state.update { it.copy(notice = null) }

            is GalleryAction.OpenEditor -> openEditor(action.id)
            GalleryAction.CloseEditor -> {
                endTextIfAny()
                _state.update { it.copy(editor = null) }
            }
            is GalleryAction.Tool -> {
                endTextIfAny()
                editor { it.copy(tool = action.tool, selectedLayer = null) }
            }
            is GalleryAction.EditPreview -> history { it.preview(action.edit) }
            is GalleryAction.EditCommit -> history { it.push(action.edit) }
            GalleryAction.Undo -> history { it.undone() }
            GalleryAction.Redo -> history { it.redone() }
            GalleryAction.ResetEdit -> {
                host.haptic()
                history { it.push(PhotoEdit.NONE) }
            }
            is GalleryAction.Brush -> editor { it.copy(brush = action.brush) }
            is GalleryAction.BrushColor -> {
                editor { it.copy(color = action.color) }
                recolourSelectedLayer(action.color)
            }
            is GalleryAction.BrushSize -> editor { it.copy(brushSize = action.size) }
            is GalleryAction.Aspect -> editor { it.copy(aspect = action.aspect) }
            is GalleryAction.Knob -> editor { it.copy(knob = action.knob) }
            is GalleryAction.AddText -> addText(action.x, action.y)
            is GalleryAction.TypeLayer -> typeLayer(action.id)
            is GalleryAction.SelectLayer -> editor { it.copy(selectedLayer = action.id) }
            is GalleryAction.DeleteLayer -> deleteLayer(action.id)
            is GalleryAction.LayerStyle -> {
                editor { it.copy(textStyle = action.style) }
                editLayer { it.copy(style = action.style) }
            }
            is GalleryAction.LayerAlign -> {
                editor { it.copy(textAlign = action.align) }
                editLayer { it.copy(align = action.align) }
            }
            is GalleryAction.AddSticker -> addSticker(action.path, action.emoji)
            is GalleryAction.Compare -> editor { it.copy(comparing = action.on) }
        }
    }

    private fun launch(intent: Intent) {
        runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** How many more fit in the selection; at least one, so a picker always opens. */
    private fun room(): Int = (host.settings.maxSelection - _state.value.selection.size).coerceAtLeast(1)

    // ---- Selection ----------------------------------------------------------

    private fun toggle(item: GalleryItem) {
        val s = _state.value
        if (s.selection.any { it.id == item.id }) {
            remove(item.id)
            return
        }
        if (s.selection.size >= host.settings.maxSelection) {
            notice(GalleryNotice.Kind.LIMIT, host.settings.maxSelection)
            return
        }
        if (host.settings.haptics) host.haptic()
        _state.update { it.copy(selection = it.selection + item) }
        if (item.isVideo && !s.fieldTakesVideos && s.fieldTakesImages) notice(GalleryNotice.Kind.NO_VIDEOS)
    }

    private fun selectRange(items: List<GalleryItem>, select: Boolean) {
        val max = host.settings.maxSelection
        var hitLimit = false
        _state.update { s ->
            val ids = s.selectedIds
            if (select) {
                val adding = items.filter { it.id !in ids }
                val fits = (max - s.selection.size).coerceAtLeast(0)
                if (adding.size > fits) hitLimit = true
                s.copy(selection = s.selection + adding.take(fits))
            } else {
                val gone = items.mapTo(HashSet()) { it.id }
                s.copy(selection = s.selection.filterNot { it.id in gone }, edits = s.edits - gone)
            }
        }
        if (hitLimit) notice(GalleryNotice.Kind.LIMIT, max)
    }

    private fun remove(id: Long) {
        if (host.settings.haptics) host.haptic()
        _state.update { s ->
            val selection = s.selection.filterNot { it.id == id }
            s.copy(
                selection = selection,
                edits = s.edits - id,
                // Paging the selection, the viewer stays on a real page.
                viewer = s.viewer?.let { v ->
                    if (!v.fromSelection) v else if (selection.isEmpty()) null else v.copy(index = v.index.coerceAtMost(selection.lastIndex))
                },
                editor = s.editor?.takeIf { it.itemId != id },
                sendMenuOpen = s.sendMenuOpen && selection.isNotEmpty(),
            )
        }
    }

    /** Picks from a system picker, joining the selection as items of their own. */
    private fun addPicked(picks: List<GalleryPickActivity.Picked>) {
        val items = picks.map(::itemOf)
        val max = host.settings.maxSelection
        val fits = (max - _state.value.selection.size).coerceAtLeast(0)
        _state.update { it.copy(selection = it.selection + items.take(fits)) }
        if (items.size > fits) notice(GalleryNotice.Kind.LIMIT, max)
    }

    private fun itemOf(p: GalleryPickActivity.Picked): GalleryItem {
        val kind = when {
            p.mime == "image/gif" -> GalleryKind.GIF
            p.mime.startsWith("video/") -> GalleryKind.VIDEO
            else -> GalleryKind.IMAGE
        }
        return GalleryItem(
            id = nextPickId--, mime = p.mime, kind = kind, name = p.name, width = 0, height = 0, orientation = 0,
            size = p.size, durationMs = 0, dateMs = System.currentTimeMillis(), bucketId = "", bucketName = "",
            favorite = false, sourceUri = p.uri,
        )
    }

    // ---- Text ---------------------------------------------------------------

    private fun endTextIfAny() {
        if (host.currentText() != null) host.endText()
        _state.update { it.copy(editor = it.editor?.copy(typingLayer = null)) }
    }

    /** Called by the service as the box is typed into. */
    fun onText(entry: GalleryTextEntry) {
        when (entry.field) {
            GalleryTextField.CAPTION -> _state.update { it.copy(caption = entry.text.take(CAPTION_MAX)) }
            GalleryTextField.LAYER -> {
                val id = _state.value.editor?.itemId ?: return
                val edit = _state.value.editOf(id)
                history {
                    it.preview(edit.copy(texts = edit.texts.map { t -> if (t.id == entry.ref) t.copy(text = entry.text) else t }))
                }
            }
        }
    }

    /** Enter in the box, or its Done: the caption is kept, a text layer is finished. */
    fun onTextEnter(entry: GalleryTextEntry) {
        onText(entry)
        if (entry.field == GalleryTextField.LAYER) {
            val id = _state.value.editor?.itemId
            if (id != null) {
                val edit = _state.value.editOf(id)
                val texts = edit.texts.filterNot { it.id == entry.ref && it.text.isBlank() }
                history { it.push(edit.copy(texts = texts)) }
            }
        }
        host.endText()
        _state.update { it.copy(editor = it.editor?.copy(typingLayer = null)) }
    }

    // ---- Editor -------------------------------------------------------------

    private fun openEditor(id: Long) {
        val item = _state.value.selection.firstOrNull { it.id == id }
            ?: _state.value.items.firstOrNull { it.id == id } ?: return
        if (item.kind != GalleryKind.IMAGE) return
        // Editing a photo is choosing it.
        if (_state.value.selection.none { it.id == id }) toggle(item)
        if (_state.value.selection.none { it.id == id }) return
        _state.update { it.copy(editor = EditorUi(itemId = id), sendMenuOpen = false) }
    }

    private fun editor(change: (EditorUi) -> EditorUi) = _state.update { it.copy(editor = it.editor?.let(change)) }

    private fun history(change: (EditHistory) -> EditHistory) {
        val id = _state.value.editor?.itemId ?: return
        _state.update { s -> s.copy(edits = s.edits + (id to change(s.edits[id] ?: EditHistory()))) }
    }

    private fun editLayer(change: (TextLayer) -> TextLayer) {
        val e = _state.value.editor ?: return
        val layerId = e.selectedLayer ?: e.typingLayer ?: return
        val edit = _state.value.editOf(e.itemId)
        if (edit.texts.none { it.id == layerId }) return
        history { it.push(edit.copy(texts = edit.texts.map { t -> if (t.id == layerId) change(t) else t })) }
    }

    private fun recolourSelectedLayer(color: Int) {
        val e = _state.value.editor ?: return
        if (e.tool != EditorTool.TEXT) return
        editLayer { it.copy(color = color) }
    }

    private fun addText(x: Float, y: Float) {
        val e = _state.value.editor ?: return
        val edit = _state.value.editOf(e.itemId)
        val layer = TextLayer(
            id = nextLayerId++, text = "", color = e.color, style = e.textStyle, align = e.textAlign,
            center = com.wasimaster.wmkeyboard.core.media.gallery.edit.NPoint(x.coerceIn(0.05f, 0.95f), y.coerceIn(0.05f, 0.95f)),
        )
        history { it.push(edit.copy(texts = edit.texts + layer)) }
        editor { it.copy(tool = EditorTool.TEXT, selectedLayer = layer.id, typingLayer = layer.id) }
        host.beginText(GalleryTextEntry(GalleryTextField.LAYER, "", layer.id))
    }

    private fun typeLayer(id: Long) {
        val e = _state.value.editor ?: return
        val layer = _state.value.editOf(e.itemId).texts.firstOrNull { it.id == id } ?: return
        editor { it.copy(selectedLayer = id, typingLayer = id, textStyle = layer.style, textAlign = layer.align) }
        host.beginText(GalleryTextEntry(GalleryTextField.LAYER, layer.text, id))
    }

    private fun deleteLayer(id: Long) {
        val e = _state.value.editor ?: return
        val edit = _state.value.editOf(e.itemId)
        if (e.typingLayer == id) endTextIfAny()
        host.haptic()
        history { it.push(edit.copy(texts = edit.texts.filterNot { it.id == id }, stickers = edit.stickers.filterNot { it.id == id })) }
        editor { it.copy(selectedLayer = null) }
    }

    private fun addSticker(path: String?, emoji: String?) {
        val e = _state.value.editor ?: return
        val edit = _state.value.editOf(e.itemId)
        // Each new one a little off the last, so a second does not hide under the first.
        val nudge = (edit.stickers.size % STICKER_STAGGER) * 0.06f
        val layer = StickerLayer(
            id = nextLayerId++, path = path, emoji = emoji,
            center = com.wasimaster.wmkeyboard.core.media.gallery.edit.NPoint(0.5f + nudge, 0.5f + nudge),
        )
        host.haptic()
        history { it.push(edit.copy(stickers = edit.stickers + layer)) }
        editor { it.copy(selectedLayer = layer.id) }
    }

    // ---- Sending --------------------------------------------------------------

    private fun notice(kind: GalleryNotice.Kind, count: Int = 0) {
        _state.update { it.copy(notice = GalleryNotice(kind, count)) }
        noticeJob?.cancel()
        noticeJob = scope.launch {
            delay(NOTICE_MS)
            _state.update { if (it.notice?.kind == kind) it.copy(notice = null) else it }
        }
    }

    private fun options(quality: GalleryQuality): MediaPrep.Options {
        val s = host.settings
        return MediaPrep.Options(
            quality = quality,
            stripLocation = s.stripLocation,
            stripAll = s.stripAllMetadata,
            randomName = s.randomFileNames,
        )
    }

    /** The sticker pictures an edit places, decoded once per send. */
    private class StickerCache : PhotoEditRenderer.StickerSource {
        private val cache = HashMap<String, Bitmap?>()
        override fun bitmap(layer: StickerLayer): Bitmap? {
            val path = layer.path ?: return null
            return cache.getOrPut(path) { runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }
        }
    }

    /** The copy of [item] that is sent, made on the calling (background) thread. */
    private fun prepare(item: GalleryItem, edit: PhotoEdit, quality: GalleryQuality, stickers: StickerCache): MediaPrep.Prepared? {
        val opts = options(quality)
        val mime = item.mime
        return when {
            item.kind == GalleryKind.VIDEO -> MediaPrep.prepareVideo(app, item, opts)
            !mime.startsWith("image/") ->
                MediaPrep.copyAsIs(app, item, mime, item.name.substringAfterLast('.', MediaPrep.extensionFor(mime)), opts)
            edit.isEmpty -> MediaPrep.prepareImage(app, item, opts)
            else -> MediaPrep.prepareImage(app, item, opts) { bmp -> PhotoEditRenderer.render(bmp, edit, stickers) }
        }
    }

    private fun send(mode: SendMode) {
        val s = _state.value
        if (s.selection.isEmpty() || sendJob?.isActive == true) return
        endTextIfAny()
        host.haptic()
        val captionSetting = host.settings.caption
        val caption = if (mode == SendMode.WITHOUT_CAPTION || captionSetting == GalleryCaption.OFF) "" else s.caption.trim()
        val quality = if (mode == SendMode.AS_FILE) GalleryQuality.ORIGINAL else s.quality
        val items = s.selection
        val edits = items.associate { it.id to s.editOf(it.id) }
        val field = host.field()
        _state.update { it.copy(sending = SendProgress(0, items.size), sendMenuOpen = false, editor = null, viewer = null) }
        sendJob = scope.launch {
            try {
                val stickers = StickerCache()
                val prepared = ArrayList<Pair<GalleryItem, MediaPrep.Prepared>>()
                var failed = 0
                for (item in items) {
                    _state.update { it.copy(sending = it.sending?.copy(preparingId = item.id)) }
                    val p = withContext(Dispatchers.IO) { prepare(item, edits[item.id] ?: PhotoEdit.NONE, quality, stickers) }
                    if (p == null) failed++ else prepared += item to p
                }
                if (failed > 0) notice(GalleryNotice.Kind.FAILED, failed)
                if (prepared.isEmpty()) return@launch
                val delivered = when (mode) {
                    SendMode.COPY -> copy(prepared.map { it.second })
                    SendMode.SHARE_TO_APP -> share(prepared.map { it.second }, caption, toApp = field?.packageName)
                    SendMode.SHARE -> share(prepared.map { it.second }, caption, toApp = null)
                    SendMode.SAVE -> {
                        prepared.forEach { (_, p) -> host.saveToGallery(p.file, p.mime) }
                        notice(GalleryNotice.Kind.SAVED, prepared.size)
                        false
                    }
                    else -> commitAll(prepared, caption, captionSetting, field, items.size)
                }
                val savedEdited = host.settings.saveEdited && mode != SendMode.SAVE
                if (savedEdited) {
                    prepared.filter { (item, _) -> edits[item.id]?.isEmpty == false }
                        .forEach { (_, p) -> host.saveToGallery(p.file, p.mime) }
                }
                if (delivered) finishSend(prepared.map { it.first })
            } finally {
                _state.update { it.copy(sending = null) }
            }
        }
    }

    /**
     * Commits each copy in turn, a breath apart, while the field stays the one
     * they were sent to. True when something reached the app, by commit or by
     * the fallback.
     */
    private suspend fun commitAll(
        prepared: List<Pair<GalleryItem, MediaPrep.Prepared>>,
        caption: String,
        placement: GalleryCaption,
        field: FieldKey?,
        total: Int,
    ): Boolean {
        val captionFirst = caption.isNotEmpty() && placement == GalleryCaption.BEFORE
        if (captionFirst) host.insertText(caption)
        val notTaken = ArrayList<MediaPrep.Prepared>()
        var committed = 0
        for ((i, pair) in prepared.withIndex()) {
            if (host.field() != field) break
            val p = pair.second
            if (host.commit(p.file, p.mime)) committed++ else notTaken += p
            _state.update { it.copy(sending = SendProgress(i + 1, total)) }
            if (i < prepared.lastIndex) delay(SEND_GAP_MS)
        }
        if (caption.isNotEmpty() && !captionFirst && committed > 0) host.insertText(caption)
        if (notTaken.isEmpty()) return committed > 0
        // What the field would not take: the setting says where it goes.
        val leftCaption = if (committed == 0 && !captionFirst) caption else ""
        return when (host.settings.fallback) {
            GalleryFallback.SHARE_TO_APP -> share(notTaken, leftCaption, toApp = field?.packageName) || committed > 0
            GalleryFallback.CLIPBOARD -> copy(notTaken) || committed > 0
            GalleryFallback.ASK -> {
                refused = notTaken
                refusedCaption = leftCaption
                _state.update { it.copy(askFallback = notTaken.size) }
                committed > 0
            }
        }
    }

    private fun fallbackChoice(share: Boolean?) {
        val files = refused
        val caption = refusedCaption
        refused = emptyList()
        _state.update { it.copy(askFallback = 0) }
        when (share) {
            true -> if (share(files, caption, toApp = host.field()?.packageName)) finishSend(emptyList())
            false -> if (copy(files)) finishSend(emptyList())
            null -> Unit
        }
    }

    private fun finishSend(sent: List<GalleryItem>) {
        val ids = sent.filter { !it.isPicked }.map { it.id }
        if (ids.isNotEmpty() && host.settings.keepHistory && !host.incognito()) {
            scope.launch(Dispatchers.IO) { history.record(ids) }
            _state.update { it.copy(sentIds = it.sentIds + ids) }
        }
        _state.update { it.copy(selection = emptyList(), edits = emptyMap(), caption = "", viewer = null, editor = null) }
        if (host.settings.closeAfterSend && open) host.closePanel()
    }

    private fun copy(files: List<MediaPrep.Prepared>): Boolean {
        val uris = files.mapNotNull { host.fileUri(it.file) }
        if (uris.isEmpty()) return false
        val mimes = files.map { it.mime }.distinct().toTypedArray()
        val clip = ClipData(ClipDescription("media", mimes), ClipData.Item(uris.first()))
        uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        val ok = runCatching {
            (app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
        }.isSuccess
        if (ok) notice(GalleryNotice.Kind.COPIED, uris.size)
        return ok
    }

    /** Android's share screen with [files], aimed at [toApp] when it can take them there. */
    private fun share(files: List<MediaPrep.Prepared>, caption: String, toApp: String?): Boolean {
        val uris = files.mapNotNull { host.fileUri(it.file) }
        if (uris.isEmpty()) return false
        val mime = commonMime(files.map { it.mime })
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        intent.type = mime
        // The grant travels on the clip data; the extra alone carries none.
        intent.clipData = ClipData(ClipDescription("media", arrayOf(mime)), ClipData.Item(uris.first())).also { clip ->
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
        }
        if (caption.isNotEmpty()) intent.putExtra(Intent.EXTRA_TEXT, caption)
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        // Aimed at the app first, with no lookup beforehand: package visibility
        // hides other apps' share screens from a query but not from a launch,
        // so the launch itself is the test, and the chooser is the way out.
        val chooser = Intent.createChooser(intent, null).addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        val ok = (toApp != null && runCatching { context.startActivity(Intent(intent).setPackage(toApp)) }.isSuccess) ||
            runCatching { context.startActivity(chooser) }.isSuccess
        if (ok) notice(GalleryNotice.Kind.SHARED, uris.size)
        return ok
    }

    /**
     * Picks from a Play build's photo picker, with no panel to show them in:
     * prepared and sent at once, as a tap on Send would.
     */
    private fun sendPicked(picks: List<GalleryPickActivity.Picked>) {
        val items = picks.map(::itemOf)
        _state.update { it.copy(selection = items, edits = emptyMap(), quality = host.settings.quality, caption = "") }
        send(SendMode.NORMAL)
    }

    companion object {
        const val PAGE = 120
        const val CAPTION_MAX = 4096
        private const val SEND_GAP_MS = 350L
        private const val NOTICE_MS = 2600L
        private const val REFRESH_DEBOUNCE_MS = 450L
        private const val STICKER_STAGGER = 5

        /** The narrowest type that covers every one of [mimes]: `image/jpeg`, `image/*`, or `*/*`. */
        fun commonMime(mimes: List<String>): String {
            val distinct = mimes.distinct()
            if (distinct.size == 1) return distinct.single()
            val tops = distinct.map { it.substringBefore('/') }.distinct()
            return if (tops.size == 1) "${tops.single()}/*" else "*/*"
        }
    }
}

/** An album's stable name for remembering it: the folder's id, or the collection's name. */
val GalleryAlbum.key: String get() = if (kind == AlbumKind.BUCKET) "bucket:$id" else kind.name
