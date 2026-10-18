package com.wasimaster.wmkeyboard.ime

import com.wasimaster.wmkeyboard.ime.TwoThumbKit.drain
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.keyboard
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.settings
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.space
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.swipe
import com.wasimaster.wmkeyboard.ime.TwoThumbKit.tap
import com.wasimaster.wmkeyboard.ime.ui.GlideVerdict
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A two-thumb word that starts with a swipe gets the space in front a glided word gets (#735). */
@RunWith(RobolectricTestRunner::class)
class TwoThumbLeadingSpaceTest {

    @Test
    fun `control - an ordinary glide after a comma gets a leading space`() {
        val (service, editor) = keyboard(settings(twoThumb = false), initial = "Hi,")
        swipe(service, GlideVerdict.Word("there"))
        drain(service)
        assertEquals("Hi, there ", editor.text.toString())
    }

    @Test
    fun `a two-thumb word swiped after a comma is not glued to it`() {
        val (service, editor) = keyboard(settings(), initial = "Hi,")
        swipe(service)
        drain(service)
        service.onKey(space)
        drain(service)
        assertEquals("Hi, qy ", editor.text.toString())
    }

    @Test
    fun `a two-thumb word swiped after a space gets no second one`() {
        val (service, editor) = keyboard(settings(), initial = "Hi ")
        swipe(service)
        drain(service)
        assertEquals("Hi qy", editor.text.toString())
    }

    @Test
    fun `no leading space under a mode whose spaces are all the user's`() {
        val (service, editor) = keyboard(settings(noAutoSpaceMode = true), initial = "Hi,")
        swipe(service)
        drain(service)
        assertEquals("Hi,qy", editor.text.toString())
    }

    @Test
    fun `a two-thumb word that starts with a tap keeps ordinary typing rules`() {
        val (service, editor) = keyboard(settings(), initial = "Hi,")
        tap(service, "t")
        drain(service)
        swipe(service)
        drain(service)
        assertEquals("fixture: the tap and the swipe are one word after the comma", 'H', editor.text.first())
        assertEquals("Hi,t", editor.text.toString().take(4))
    }
}
