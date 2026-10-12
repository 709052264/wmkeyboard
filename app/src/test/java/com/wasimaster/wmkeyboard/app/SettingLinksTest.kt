package com.wasimaster.wmkeyboard.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The links a settings row copies of itself (#646). What matters is that each
 * one goes the whole way round: the web address reads back through
 * [WebOpenLink] and the app link through [SettingsDeepLink] to the same screen
 * and row it was copied from.
 */
class SettingLinksTest {

    @Test
    fun `a row on a plain screen names both`() {
        assertEquals(
            "wmkeyboard://settings/typing?setting=typing_autocorrect_title&since=0.6.1",
            SettingLinks.appLink("typing", "typing_autocorrect_title", "0.6.1"),
        )
    }

    @Test
    fun `a row whose screen is unknown goes through the setting host`() {
        assertEquals(
            "wmkeyboard://setting/typing_autocorrect_title?since=0.6.1",
            SettingLinks.appLink(null, "typing_autocorrect_title", "0.6.1"),
        )
    }

    @Test
    fun `an unfilled pattern is not a screen`() {
        assertEquals(
            "wmkeyboard://setting/x_title",
            SettingLinks.appLink("mode_edit/{modeId}", "x_title", "dev"),
        )
    }

    @Test
    fun `a version that is not a number is left out`() {
        assertEquals(
            "wmkeyboard://settings/typing?setting=typing_autocorrect_title",
            SettingLinks.appLink("typing", "typing_autocorrect_title", "0.6.1-debug"),
        )
    }

    @Test
    fun `the web link reads back to the same address`() {
        val app = SettingLinks.appLink("typing", "typing_autocorrect_title", "0.6.1")
        val web = SettingLinks.webLink(app)
        assertEquals("$DOCS_URL/open/#$app", web)
        assertEquals(app, WebOpenLink.appLink(web))
        val target = SettingsDeepLink.parse(app)!!
        assertEquals("typing", target.route)
        assertEquals("typing_autocorrect_title", target.setting)
        assertEquals("0.6.1", target.since)
    }

    @Test
    fun `arguments are filled in and encoded`() {
        assertEquals(
            "tool/CLIPBOARD",
            SettingLinks.concreteRoute("tool/{tool}") { if (it == "tool") "CLIPBOARD" else null },
        )
        assertEquals(
            "language/a%20b",
            SettingLinks.concreteRoute("language/{lang}") { "a b" },
        )
    }

    @Test
    fun `a missing argument names no route`() {
        assertNull(SettingLinks.concreteRoute("tool/{tool}") { null })
        assertNull(SettingLinks.concreteRoute(null) { "x" })
    }
}
