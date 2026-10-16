package com.wasimaster.wmkeyboard.spell

import com.wasimaster.wmkeyboard.core.grammar.GrammarFix

/**
 * A Harper fix as the whole-span replacement the spell checker UI needs.
 *
 * The framework can only swap a flagged span for a string, so an insertion
 * has to become "the span plus the insertion". Deletions return null: the
 * correction menu would show an empty row, which cannot be read or chosen.
 */
fun spellReplacementFor(span: String, fix: GrammarFix): String? = when (fix.kind) {
    "replace" -> fix.text
    "insertAfter" -> fix.text?.let { span + it }
    else -> null
}
