package com.wasimaster.wmkeyboard.core.plugins

/**
 * The Lua that runs before every plugin script.
 *
 * It is pure Lua and does nothing but build tables — `ui.button{...}` returns
 * `{type = "button", ...}` and that is the whole trick. The widget format is
 * therefore plain data that a plugin could write out by hand, and the `ui.*`
 * helpers exist so nobody has to.
 *
 * Kept as a Kotlin constant rather than an asset on purpose: `core/` has no
 * `Context`, which is what lets the sandbox, the codec and the runtime all be
 * unit-tested on a plain JVM with no Android at all. It is small enough that
 * the cost is a long string literal and the benefit is a testable core.
 *
 * This exact text appears in the plugin API reference, so keep the two in step.
 */
object PluginPrelude {

    const val CHUNK_NAME = "@prelude.lua"

    val SOURCE = """
        -- WM Keyboard plugin UI helpers.
        --
        -- Every one of these returns a plain table. You can build the same
        -- tables by hand if you prefer; nothing here is magic.
        ui = {}

        -- Layout. Pass the children in the array part:
        --   ui.column { ui.label{text="hi"}, ui.button{id="go", text="Go"} }
        function ui.column(t) return { type = "column", children = t } end
        function ui.row(t) return { type = "row", children = t } end

        -- Text. style is "title", "body" (the default) or "caption".
        function ui.label(t) return { type = "label", text = t.text, style = t.style } end

        -- A result block. insertable adds the keyboard's own Insert button,
        -- which is how your output reaches whatever the user is typing in;
        -- copyable adds a Copy button. Both are on unless you say otherwise.
        function ui.output(t)
          return {
            type = "output", id = t.id, text = t.text, mono = t.mono,
            insertable = t.insertable, copyable = t.copyable,
          }
        end

        -- Controls. Each needs an id, which is what you get back in on_event.
        function ui.button(t)
          return { type = "button", id = t.id, text = t.text, style = t.style, enabled = t.enabled }
        end

        function ui.toggle(t)
          return { type = "toggle", id = t.id, label = t.label, checked = t.checked }
        end

        -- A text box. The keyboard owns what is in it: the user types or pastes,
        -- and you are told the new contents through on_event. Use
        -- wm.ui.set_input(id, text) to write to one yourself.
        function ui.input(t)
          return { type = "input", id = t.id, label = t.label, placeholder = t.placeholder }
        end

        -- A date the user picks with the keyboard's own buttons. value is
        -- seconds since the epoch, as os.time() gives, or nil for none yet.
        -- time = true picks a time of day as well; step is how many minutes
        -- the smaller time buttons move. You are told each change through
        -- on_event as date_changed, and draw whatever you kept.
        function ui.date(t)
          return { type = "date", id = t.id, label = t.label, value = t.value, time = t.time, step = t.step }
        end

        function ui.spacer(t) return { type = "spacer", height = t and t.height } end
        function ui.divider() return { type = "divider" } end
        function ui.progress() return { type = "progress" } end

        -- Tabs. Pass pages in the array part:
        --   ui.tabs { id = "modes", ui.page{ title = "One", ui.label{text="..."} } }
        function ui.tabs(t) return { type = "tabs", id = t.id, pages = t } end
        function ui.page(t) return { title = t.title, children = t } end
    """.trimIndent()
}
