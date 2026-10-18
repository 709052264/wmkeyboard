package com.wasimaster.wmkeyboard.ime.kaomoji

import com.wasimaster.wmkeyboard.core.emoji.KaomojiKind
import com.wasimaster.wmkeyboard.core.emoji.KaomojiLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Which of the kaomoji tab's text boxes has the keys. */
enum class KaomojiTextField {
    /** The search above the grid: filters by tag and by the face itself. */
    SEARCH,

    /** The face in the add/edit sheet. */
    FACE,

    /** A tag's name: a new one in the editor, or a rename in the tag list. */
    TAG,
}

/**
 * The kaomoji tab's text box while it has the keys. Lives in
 * `KeyboardUiState` so the shared capture path types into it; the rest of the
 * tab's state is the controller's [KaomojiUi].
 */
data class KaomojiTextEntry(val field: KaomojiTextField, val text: String = "")

/** A face being added ([id] null) or changed in the keyboard. */
data class KaomojiEdit(
    val id: Long?,
    val text: String = "",
    val tags: List<String> = emptyList(),
    /** Null guesses from the text when saved. */
    val kind: KaomojiKind? = null,
    /** Which box the keys type into: the face, or a new tag's name. */
    val focused: KaomojiTextField = KaomojiTextField.FACE,
    val tagDraft: String = "",
    /** Delete was tapped once; the second tap deletes. */
    val confirmDelete: Boolean = false,
)

/** The kaomoji tab, as the controller publishes it. */
data class KaomojiUi(
    val library: KaomojiLibrary = KaomojiLibrary.EMPTY,
    /** The tag strip's choice per tab (the merged tab, or the split pair), keyed by tab id. */
    val views: Map<String, String> = emptyMap(),
    /** The grid in edit mode: a tap edits, a held face drags to a new place. */
    val editMode: Boolean = false,
    /** A long-pressed face showing its action bar. */
    val held: Long? = null,
    /** The held face's Delete was tapped once. */
    val confirmDelete: Long? = null,
    /** What the search box holds; only meaningful while [searching]. */
    val query: String = "",
    val searching: Boolean = false,
    val edit: KaomojiEdit? = null,
    /** The tag list is open in place of the grid. */
    val tagsOpen: Boolean = false,
    /** The tag being renamed; empty for a new one. */
    val tagRename: String? = null,
    /** The tag whose Delete was tapped once. */
    val tagConfirmDelete: String? = null,
) {
    fun view(tab: String): String = views[tab] ?: KaomojiLibrary.VIEW_ALL
}

/** Everything the kaomoji tab can ask of the controller. */
sealed interface KaomojiAction {
    /** A face tapped: into the field it goes. */
    data class Insert(val text: String) : KaomojiAction
    data class SelectView(val tab: String, val view: String) : KaomojiAction
    /** A face long-pressed (null lets go). */
    data class Hold(val id: Long?) : KaomojiAction
    data class Favourite(val id: Long) : KaomojiAction
    data class Copy(val id: Long) : KaomojiAction
    /** Two taps: the first arms it. */
    data class Delete(val id: Long) : KaomojiAction
    data class Edit(val id: Long) : KaomojiAction
    /** A new face; [tab] seeds its tag and kind from what the tab is showing. */
    data class Add(val tab: String) : KaomojiAction
    data object ToggleEditMode : KaomojiAction
    /** The ids of one view in their new order, after a drag. */
    data class Reorder(val ids: List<Long>) : KaomojiAction
    data object ToggleSearch : KaomojiAction
    data object ToggleTags : KaomojiAction
    data class TagMove(val name: String, val delta: Int) : KaomojiAction
    data class TagHidden(val name: String, val hidden: Boolean) : KaomojiAction
    /** Starts typing a tag's new name; an empty [name] makes a new tag. */
    data class TagRename(val name: String) : KaomojiAction
    /** Two taps: the first arms it. */
    data class TagDelete(val name: String) : KaomojiAction
    data class EditorFocus(val field: KaomojiTextField) : KaomojiAction
    /** A tag chip in the editor: on the face, or off it. */
    data class EditorTag(val name: String) : KaomojiAction
    data class EditorKind(val kind: KaomojiKind) : KaomojiAction
    data object EditorSave : KaomojiAction
    data object EditorCancel : KaomojiAction
    /** Two taps, like [Delete]. */
    data object EditorDelete : KaomojiAction
    /** Puts away the search or a tag rename, handing the keys back. */
    data object CloseText : KaomojiAction
    data object ClearRecents : KaomojiAction
    /** The library screen in the settings app. */
    data object OpenSettings : KaomojiAction
}

/** The kaomoji tab's link to its controller; travels on `ToolHoldCallbacks`. */
data class KaomojiCallbacks(
    val state: StateFlow<KaomojiUi> = MutableStateFlow(KaomojiUi()),
    val onAction: (KaomojiAction) -> Unit = {},
)
