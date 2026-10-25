package com.wasimaster.wmkeyboard.app

import com.wasimaster.wmkeyboard.core.settings.AppUiSettings
import com.wasimaster.wmkeyboard.core.settings.Extension
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.settings.PersonaDepth
import com.wasimaster.wmkeyboard.core.settings.SettingsDepth
import com.wasimaster.wmkeyboard.core.settings.ToolbarTool
import com.wasimaster.wmkeyboard.core.settings.allows
import com.wasimaster.wmkeyboard.core.settings.extensionsResolved
import com.wasimaster.wmkeyboard.core.settings.settingsDepth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extensions (SETTINGS_REDESIGN.md §3): the feature bundles, what each owns,
 * the defaults a settings depth starts with, and the two filters the choke
 * points read. Off has to mean gone from the Tools list, the pickers, the
 * root list and the deep links, and nothing but a test holds the enum, the
 * route allowlist and the tool groups to one another.
 */
class ExtensionsTest {

    // ---- the defaults per depth ----------------------------------------------

    @Test
    fun `simple starts with a handful and everything with all`() {
        val simple = Extension.defaultsFor(SettingsDepth.SIMPLE)
        assertTrue("Simple should start with only a few bundles on", simple.size in 3..8)
        assertEquals(Extension.All, Extension.defaultsFor(SettingsDepth.EVERYTHING))
    }

    @Test
    fun `each depth adds to the one below it`() {
        val simple = Extension.defaultsFor(SettingsDepth.SIMPLE)
        val standard = Extension.defaultsFor(SettingsDepth.STANDARD)
        assertTrue(standard.containsAll(simple))
        assertTrue(standard.size > simple.size)
        assertTrue(Extension.All.containsAll(standard))
        assertTrue(Extension.All.size > standard.size)
    }

    @Test
    fun `auto resolves as standard`() {
        assertEquals(Extension.defaultsFor(SettingsDepth.STANDARD), Extension.defaultsFor(SettingsDepth.AUTO))
    }

    @Test
    fun `the simple set changes no keyboard default`() {
        // Every bundle with a master switch that is on by default is on at
        // Simple, so landing the defaults never has to move a master switch
        // (the plan: it does not change any default of the keyboard itself).
        val simple = Extension.defaultsFor(SettingsDepth.SIMPLE)
        for (ext in listOf(Extension.VOICE, Extension.CLIPBOARD, Extension.HARDWARE_KEYBOARD)) {
            assertTrue("$ext carries an on-by-default master switch and must be on at Simple", ext in simple)
        }
    }

    @Test
    fun `the onboarding answer maps to the depth the defaults are picked by`() {
        assertEquals(SettingsDepth.SIMPLE, PersonaDepth.MINIMAL.settingsDepth())
        assertEquals(SettingsDepth.STANDARD, PersonaDepth.BALANCED.settingsDepth())
        assertEquals(SettingsDepth.STANDARD, PersonaDepth.UNSET.settingsDepth())
        assertEquals(SettingsDepth.EVERYTHING, PersonaDepth.POWER.settingsDepth())
    }

    @Test
    fun `an install that never chose keeps everything on`() {
        assertEquals(Extension.All, KeyboardSettings().extensionsResolved())
        assertEquals(Extension.All, KeyboardSettings(appUi = AppUiSettings(extensions = null)).extensionsResolved())
        val chosen = setOf(Extension.VOICE)
        assertEquals(chosen, KeyboardSettings(appUi = AppUiSettings(extensions = chosen)).extensionsResolved())
    }

    @Test
    fun `stored names decode and unknown ones are dropped`() {
        assertEquals(
            setOf(Extension.AI, Extension.NOTES),
            Extension.decode(setOf("AI", "NOTES", "FROM_A_LATER_VERSION")),
        )
        assertEquals(emptySet<Extension>(), Extension.decode(emptySet()))
    }

    // ---- ownership -----------------------------------------------------------

    @Test
    fun `a tool belongs to at most one bundle`() {
        val owners = Extension.entries.flatMap { ext -> ext.tools.map { it to ext } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
        assertEquals("tools claimed by two bundles", emptyMap<ToolbarTool, List<Extension>>(), owners)
    }

    @Test
    fun `a route belongs to at most one bundle`() {
        val owners = Extension.entries.flatMap { ext -> ext.routes.map { it to ext } }
            .groupBy({ it.first }, { it.second })
            .filterValues { it.size > 1 }
        assertEquals("routes claimed by two bundles", emptyMap<String, List<Extension>>(), owners)
    }

    @Test
    fun `every route a bundle owns is an addressable screen`() {
        val unknown = Extension.entries.flatMap { it.routes }.filterNot { it in SettingsRoutes.all }
        assertEquals("extension routes with no entry in SettingsRoutes.all", emptyList<String>(), unknown)
    }

    @Test
    fun `the extensions screens themselves belong to no bundle`() {
        // Or switching one off would lock the way back.
        assertNull(Extension.owningRoute(ExtensionsRoute))
        assertNull(Extension.owningRoute(ExtensionRoutePattern))
        for (ext in Extension.entries) assertNull(extensionRedirect(extensionRoute(ext), emptySet()))
        assertNull(extensionRedirect(ExtensionsRoute, emptySet()))
    }

    @Test
    fun `every bundle has its own screen listed`() {
        for (ext in Extension.entries) {
            for ((route, _) in extensionScreens(ext)) {
                assertTrue("$ext lists $route, which it does not own", route in ext.routes)
            }
        }
    }

    // ---- off means not in ToolGroups -------------------------------------------

    @Test
    fun `off means not in ToolGroups`() {
        for (ext in Extension.entries) {
            if (ext.tools.isEmpty()) continue
            val without = ToolGroups.withExtensions(Extension.All - ext).flatMap { it.second }.toSet()
            val stray = without.filter { it in ext.tools }
            assertEquals("$ext is off and its tools are still listed", emptyList<ToolbarTool>(), stray)
            // And nothing else left with it.
            val before = ToolGroups.flatMap { it.second }.toSet()
            assertEquals(before - ext.tools, without)
        }
    }

    @Test
    fun `on restores the groups untouched`() {
        assertEquals(ToolGroups, ToolGroups.withExtensions(Extension.All))
    }

    @Test
    fun `a group emptied by its bundle goes with it`() {
        // Scanners is a whole group of the Tools list.
        val titles = ToolGroups.withExtensions(Extension.All - Extension.SCANNERS).map { it.first }
        assertFalse(R.string.tools_group_scanners_title in titles)
    }

    @Test
    fun `the enabled set filter answers for tools too`() {
        val on = Extension.All - Extension.AI
        assertFalse(on.allows(ToolbarTool.AI))
        assertFalse(on.allows(ToolbarTool.AI_REPLY))
        assertTrue(on.allows(ToolbarTool.EMOJI))
        assertTrue(on.allows(ToolbarTool.CLIPBOARD))
    }

    // ---- the root list and the deep links -------------------------------------

    @Test
    fun `off takes the home row with it`() {
        val routes = visibleRootEntries(Extension.All - Extension.VOICE - Extension.AI).map { it.route }
        assertFalse("voice" in routes)
        assertFalse("ai" in routes)
        assertTrue("clipboard" in routes)
        assertTrue("extensions" in routes)
        assertEquals(RootEntries.map { it.route }, visibleRootEntries(Extension.All).map { it.route })
    }

    @Test
    fun `a link to an owned screen is redirected while its bundle is off`() {
        val off = Extension.All - Extension.MODES - Extension.AI
        assertEquals(Extension.MODES, extensionRedirect("modes", off))
        assertEquals(Extension.MODES, extensionRedirect("mode_edit/mode_chat", off))
        assertEquals(Extension.AI, extensionRedirect("tool/${ToolbarTool.AI.name}", off))
        assertEquals(Extension.AI, extensionRedirect("ai_chat", off))
        assertNull(extensionRedirect("modes", Extension.All))
        assertNull(extensionRedirect("tool/${ToolbarTool.EMOJI.name}", off))
        assertNull(extensionRedirect("typing", off))
        assertNull(extensionRedirect("no/such/screen", off))
    }
}
