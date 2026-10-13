package com.wasimaster.wmkeyboard.ime.ui

import com.wasimaster.wmkeyboard.core.layout.Key
import com.wasimaster.wmkeyboard.core.layout.KeyboardLayout
import com.wasimaster.wmkeyboard.core.settings.KeyboardSettings
import com.wasimaster.wmkeyboard.core.tools.MediaCategory
import com.wasimaster.wmkeyboard.ime.KeyboardUiState
import com.wasimaster.wmkeyboard.ime.LayoutSet
import com.wasimaster.wmkeyboard.ime.PanelMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The category row is a browsing aid in a panel whose whole problem is height,
 * so when it draws is as much a design decision as how it looks. These pin
 * every rule; the row itself is then the only part that needs a device.
 */
class MediaCategoryRowTest {

    private fun grid(rows: Int) = KeyboardLayout(
        name = "test",
        rows = List(rows) { listOf(Key("a"), Key("b")) },
    )

    /** Four rows at the default key height, which clears the height floor. */
    private val tall = LayoutSet.Default

    /** Three rows, which does not. */
    private val short = LayoutSet(grid(3), grid(3), grid(3))

    private fun state(
        set: LayoutSet = tall,
        categories: List<MediaCategory> = listOf(MediaCategory(term = "love", label = "Love")),
        query: String = "",
        category: String? = null,
        searchActive: Boolean = false,
    ) = KeyboardUiState(
        // The number row (default on) adds its own height, which would lift
        // even the three-row grid over the floor; pinned off so the fixtures
        // measure just their key rows.
        settings = KeyboardSettings(numberRow = false),
        layouts = set,
        panel = PanelMode.GIF,
        mediaCategories = categories,
        mediaQuery = query,
        mediaCategory = category,
        mediaSearchActive = searchActive,
    )

    private fun shows(
        state: KeyboardUiState,
        localGrid: Boolean = false,
        fullBleed: Boolean = false,
        acceptsRichMedia: Boolean = true,
    ) = showMediaCategories(state, localGrid, fullBleed, acceptsRichMedia)

    @Test
    fun `shows in the default view of a tall enough panel`() {
        assertTrue(shows(state()))
    }

    @Test
    fun `hides while the search box is up`() {
        assertFalse(shows(state(searchActive = true)))
    }

    @Test
    fun `hides on the local sticker grid`() {
        // My stickers has pack chips in that space instead.
        assertFalse(shows(state(), localGrid = true))
    }

    @Test
    fun `hides with nothing to show`() {
        assertFalse(shows(state(categories = emptyList())))
    }

    @Test
    fun `shows for the favourites alone when the provider has no categories`() {
        assertTrue(shows(state(categories = emptyList()).copy(gifFavouriteIds = setOf("g1"))))
    }

    @Test
    fun `hides when the field takes no rich media`() {
        assertFalse(shows(state(), acceptsRichMedia = false))
    }

    @Test
    fun `hides once a query is typed`() {
        assertFalse(shows(state(query = "cats")))
    }

    @Test
    fun `survives a category tap, which fills the query itself`() {
        assertTrue(shows(state(query = "love", category = "love")))
    }

    @Test
    fun `hides on a short panel but not in full bleed`() {
        assertFalse(shows(state(set = short)))
        assertTrue(shows(state(set = short), fullBleed = true))
    }
}
