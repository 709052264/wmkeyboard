package com.wasimaster.wmkeyboard.core.input.mozc

import android.content.Context
import com.wasimaster.wmkeyboard.core.input.composer.Composer
import com.wasimaster.wmkeyboard.core.input.composer.ConversionEngine
import com.wasimaster.wmkeyboard.core.input.composer.ConversionEngines
import com.wasimaster.wmkeyboard.core.script.ComposerType
import java.util.IdentityHashMap

/**
 * Mozc as a [ConversionEngine]: once the user picks Mozc for Japanese, every
 * Japanese layout (romaji, flick, 12-key, JIS kana) types through
 * [MozcComposer] instead of the built-in composer, as soon as Mozc is open.
 * They all compose with the same built-in kana composer
 * ([ComposerType.ROMAJI]); only the conversion changes hands.
 *
 * The keyboard pushes the setting in with [configure] from its settings
 * collector, so [composerFor] answers from fields alone.
 */
object MozcEngine : ConversionEngine {

    override val id: String = "mozc"

    /** Whether Japanese converts on Mozc. */
    @Volatile
    var enabled: Boolean = false
        private set

    private val composers = IdentityHashMap<Composer, MozcComposer>()

    init {
        ConversionEngines.register(this)
    }

    /**
     * The user's settings: whether Mozc is picked, and "Match kana without
     * marks", which Mozc answers itself. Opens Mozc in the background when it
     * is picked, which on a locked phone only notes that it cannot yet.
     */
    fun configure(context: Context, enabled: Boolean, looseMarks: Boolean) {
        val changed = enabled != this.enabled
        this.enabled = enabled
        MozcRuntime.setLooseMarks(looseMarks)
        if (enabled) MozcRuntime.prepare(context) else if (changed) MozcRuntime.shutDown()
        if (changed) ConversionEngines.changed()
    }

    override fun composerFor(base: Composer, type: ComposerType, langId: String?): Composer? {
        if (!enabled || langId != "ja" || type != ComposerType.ROMAJI || !MozcRuntime.ready) return null
        return synchronized(composers) { composers.getOrPut(base) { MozcComposer(base) } }
    }
}
