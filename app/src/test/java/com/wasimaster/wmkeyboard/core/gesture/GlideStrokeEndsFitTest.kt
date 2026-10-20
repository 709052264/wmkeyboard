package com.wasimaster.wmkeyboard.core.gesture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GlideStroke.endsFit], the gate a backspaced glide's stroke passes before
 * the next word may claim it (#805): a swipe for "the" must not teach "a".
 */
class GlideStrokeEndsFitTest {

    private val keyWidth = 100f

    /** QWERTY letter rows, centres in pixels, keys [keyWidth] wide and tall. */
    private val keys: List<KeyCenter> = buildList {
        val rows = listOf("qwertyuiop" to 0f, "asdfghjkl" to 0.5f, "zxcvbnm" to 1.5f)
        rows.forEachIndexed { row, (letters, indent) ->
            letters.forEachIndexed { i, ch ->
                add(KeyCenter(ch, (i + indent + 0.5f) * keyWidth, (row + 0.5f) * keyWidth))
            }
        }
    }

    private val map = GlideKeyMap.of(keys, keyWidth)

    private fun at(ch: Char, dx: Float = 0f, dy: Float = 0f): GesturePoint {
        val k = keys.first { it.codePoint == ch.code }
        return GesturePoint(k.x + dx * keyWidth, k.y + dy * keyWidth)
    }

    private fun stroke(vararg points: GesturePoint) = GlideStroke(points.toList(), keys, keyWidth)

    private val theStroke = stroke(at('t'), at('h'), at('e'))

    @Test
    fun `the stroke's own word fits`() {
        assertTrue(theStroke.endsFit("the", map))
        assertTrue(theStroke.endsFit("The", map))
    }

    @Test
    fun `a word the ends never came near does not fit (#805)`() {
        assertFalse(theStroke.endsFit("a", map))
        assertFalse(theStroke.endsFit("and", map))
    }

    @Test
    fun `a misread with the same ends fits`() {
        // "tie" for "the": the corrections #213 is about, which must keep teaching.
        assertTrue(theStroke.endsFit("tie", map))
        assertTrue(theStroke.endsFit("tee", map))
    }

    @Test
    fun `an end two keys off fits, three keys off does not`() {
        // r is t's neighbour, e two keys away, w three.
        assertTrue(stroke(at('r'), at('h'), at('e')).endsFit("the", map))
        assertTrue(stroke(at('e'), at('h'), at('e')).endsFit("the", map))
        assertFalse(stroke(at('w'), at('h'), at('e')).endsFit("the", map))
        // The same at the other end: t is two keys from e, y three.
        assertTrue(stroke(at('t'), at('h'), at('t')).endsFit("the", map))
        assertFalse(stroke(at('t'), at('h'), at('y')).endsFit("the", map))
    }

    @Test
    fun `an end on the far edge of the key below fits`() {
        // Rows taller than keys are wide, the way the keyboard draws them.
        val tall = keys.map { KeyCenter(it.codePoint, it.x, it.y * 1.4f) }
        val stroke = GlideStroke(
            listOf(GesturePoint(450f, 70f + 1.4f * keyWidth * 1.45f), GesturePoint(250f, 70f)),
            tall,
            keyWidth,
        )
        assertTrue(stroke.endsFit("te", GlideKeyMap.of(tall, keyWidth)))
    }

    @Test
    fun `a sloppy end inside the slack fits`() {
        assertTrue(stroke(at('t', dx = 0.6f, dy = 0.4f), at('h'), at('e', dy = 0.7f)).endsFit("the", map))
    }

    @Test
    fun `nothing to judge fits nothing`() {
        assertFalse(stroke().endsFit("the", map))
        assertFalse(theStroke.endsFit("", map))
        assertFalse(theStroke.endsFit("দি", map))
    }
}
