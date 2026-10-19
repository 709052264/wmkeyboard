package com.wasimaster.wmkeyboard.ime

import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper

/**
 * Every call the keyboard makes into the field, written to logcat, for
 * working out which of them an editor reacts to (#760: Gmail's compose body
 * dropping the keyboard after one of WM's keystrokes, and not after
 * HeliBoard's).
 *
 * Off unless asked for on the device:
 *
 *     adb shell setprop log.tag.WMKB-IC VERBOSE
 *
 * then `adb logcat -s WMKB-IC`. Only method names, offsets and lengths are
 * logged, never the text, so a trace can be shared like the debug log.
 */
internal class TracingInputConnection(val base: InputConnection) : InputConnectionWrapper(base, true) {

    override fun beginBatchEdit(): Boolean = log("beginBatchEdit") { super.beginBatchEdit() }

    override fun endBatchEdit(): Boolean = log("endBatchEdit") { super.endBatchEdit() }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
        log("commitText len=${text?.length} pos=$newCursorPosition") { super.commitText(text, newCursorPosition) }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
        log("setComposingText len=${text?.length} pos=$newCursorPosition") {
            super.setComposingText(text, newCursorPosition)
        }

    override fun setComposingRegion(start: Int, end: Int): Boolean =
        log("setComposingRegion $start..$end") { super.setComposingRegion(start, end) }

    override fun finishComposingText(): Boolean = log("finishComposingText") { super.finishComposingText() }

    override fun setSelection(start: Int, end: Int): Boolean =
        log("setSelection $start..$end") { super.setSelection(start, end) }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
        log("deleteSurroundingText $beforeLength,$afterLength") {
            super.deleteSurroundingText(beforeLength, afterLength)
        }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
        log("deleteSurroundingTextInCodePoints $beforeLength,$afterLength") {
            super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        }

    override fun sendKeyEvent(event: KeyEvent?): Boolean =
        log("sendKeyEvent action=${event?.action} code=${event?.keyCode} meta=${event?.metaState}") {
            super.sendKeyEvent(event)
        }

    override fun performEditorAction(editorAction: Int): Boolean =
        log("performEditorAction $editorAction") { super.performEditorAction(editorAction) }

    override fun performContextMenuAction(id: Int): Boolean =
        log("performContextMenuAction $id") { super.performContextMenuAction(id) }

    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean =
        log("performPrivateCommand $action") { super.performPrivateCommand(action, data) }

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean =
        log("requestCursorUpdates $cursorUpdateMode") { super.requestCursorUpdates(cursorUpdateMode) }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? =
        super.getTextBeforeCursor(n, flags).also { Log.v(TAG, "getTextBeforeCursor $n -> ${it?.length}") }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? =
        super.getTextAfterCursor(n, flags).also { Log.v(TAG, "getTextAfterCursor $n -> ${it?.length}") }

    override fun getSelectedText(flags: Int): CharSequence? =
        super.getSelectedText(flags).also { Log.v(TAG, "getSelectedText -> ${it?.length}") }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? =
        super.getExtractedText(request, flags).also {
            Log.v(TAG, "getExtractedText -> start=${it?.startOffset} sel=${it?.selectionStart}..${it?.selectionEnd}")
        }

    private inline fun log(call: String, block: () -> Boolean): Boolean =
        block().also { Log.v(TAG, "$call -> $it") }

    companion object {
        const val TAG = "WMKB-IC"

        /** Read once per connection by the service, not on every call. */
        fun enabled(): Boolean = Log.isLoggable(TAG, Log.VERBOSE)

        /** The editor's side of the conversation, for the same trace. */
        fun noteSelection(oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int, candStart: Int, candEnd: Int) {
            Log.v(TAG, "onUpdateSelection $oldStart..$oldEnd -> $newStart..$newEnd cand=$candStart..$candEnd")
        }
    }
}
