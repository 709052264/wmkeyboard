package com.wasimaster.wmkeyboard.core.layout

/**
 * What a binding to a keyboard mode draws when its mode is gone (#835): not
 * the old name, which made a dead key look like a working one, and not the
 * face an unlabelled working mode key gets.
 */
const val MissingModeLabel = "⊘"

/**
 * The face a binding to the mode [id] draws (#821, #835), from [names], the
 * modes there are now by id.
 *
 * A binding is by id and its name is looked up when it is drawn, so a rename
 * reaches every key without an edit to the layout. The pickers store no name
 * for that reason, and a blank [stored] label draws the mode's name. A label
 * the user typed is theirs and stays, except one that is the name of some
 * other mode, which is a picker's from before a re-pick. A mode that is gone
 * draws [MissingModeLabel], whatever was stored.
 */
fun modeBindingLabel(id: String, stored: String, names: Map<String, String>): String {
    val live = names[id] ?: return MissingModeLabel
    return if (stored.isBlank() || (stored != live && stored in names.values)) live else stored
}

/** [this] with every binding to a mode on it labelled by [modeBindingLabel]: the key, its arms, its alternates, its hold. */
fun Key.withModeNames(names: Map<String, String>): Key {
    fun KeyAlternate.named(): KeyAlternate =
        (action as? KeyAction.Mode)?.let { copy(label = modeBindingLabel(it.id, label, names)) } ?: this
    var key = this
    (action as? KeyAction.Mode)?.let { key = key.copy(label = modeBindingLabel(it.id, label, names)) }
    if (flickActions.values.any { it.action is KeyAction.Mode }) {
        key = key.copy(flickActions = key.flickActions.mapValues { it.value.named() })
    }
    if (actionAlternates.any { it.action is KeyAction.Mode }) {
        key = key.copy(actionAlternates = key.actionAlternates.map { it.named() })
    }
    holdAction?.takeIf { it.action is KeyAction.Mode }?.let { key = key.copy(holdAction = it.named()) }
    return key
}
