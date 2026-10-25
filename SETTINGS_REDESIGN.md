# Settings redesign

Why the settings app feels like too much, what to build so a Gboard or SwiftKey
user feels at home, and how to do it without dropping a single setting.
Numbers are from the source at `e31f6f977` (2026-10-26); the five screenshots
came from the build on the phone (0.6.3, versionCode 382).

## What the code says

The app draws **2,062 rows** behind **123 routes**: 671 switches, 220 sliders
and steppers, 214 choice rows, the rest navigation, actions and lists. Only
**35 groups fold** (16 of them in the theme editor, 8 on the Tools list), so
nearly everything is open all the time.

The pages people actually land on are the long ones:

| Screen | Rows | Groups | Folds |
|---|---:|---:|---:|
| Typing › Gestures | 69 | 4 | 1 |
| Clipboard | 51 | 4 | 0 |
| Typing › Automatic corrections | 42 | 1 | 0 |
| Typing › Suggestions | 36 | 1 | 0 |
| Layout › Size & position | 31 | 2 | 0 |
| Key press (hub) | 28 | 3 | 0 |
| Floating button | 28 | 5 | 0 |
| Layout & size (hub) | 25 | 6 | 0 |
| Emoji | 25 | 5 | 0 |
| Accessibility | 23 | 5 | 0 |
| Theme editor | 137 | 17 | 16 |
| One tool's page (70 of them) | 3 to 25 | | |

Gboard has about 65 settings in total. SwiftKey about 80. A WM Keyboard user
who opens Typing › Gestures meets 69 on one page, most of them timings and
thresholds with a `?` beside each.

The chrome costs as much as the rows. On the phone (800 dp tall), the expanded
bar is 152 dp, the path strip under it 44 dp, and the top gap 12 dp: 208 dp of
heading before the first row, a quarter of the screen. Rows run 72 to 110 dp,
so four or five fit. The path strip also draws on every
second-level page ("Home › Typing"), where it repeats the title sitting right
above it; it was designed to start one level down (`MinCrumbDepth = 1`), and
that is worth revisiting now that the title is so large.

One layout bug was visible on the first screens opened. On **Appearance ›
Keyboard themes** the current value ("Default") sits in the row's trailing
slot, so `ListItem` gives it a column for the row's whole height and squeezes
the subtitle into seven lines of three words each. `NavRow` now puts a value
that fits on the title line, where the subtitle keeps the row's width.

Every switch row carries up to three extra targets (`?`, link, reset) stacked
two high beside the switch. With Setting links on, every row has a chain icon.
That is a lot of furniture for someone who wants to turn off vibration.

What already exists and is under-used:

- The onboarding quiz asks **"What kind of keyboard do you want?"** with
  "Keep it simple / A good middle / Show me everything" (`PersonaDepth`).
  Today the answer only picks the starter tool set, the theme gallery style
  and how many discovery cards to show. It never touches the settings app.
- `SettingsGroup(foldKey = …)` already folds a group closed, remembers the
  state per screen in `AppUiSettings.advancedOpen`, and opens itself when a
  search result lands inside. The machinery is there; it is used 35 times.
- Search indexes every row, favourites pin any row, the keyboard test button
  shows the real keyboard under any screen, and `MiniKeyboardPreview` draws a
  live mini keyboard from the settings (onboarding and the Layout hub use it).
- `SETTINGS_AUDIT.md` already sorted 828 settings into keep / demote / merge /
  automate / remove, screen by screen. Its 135 demotes are the first list of
  rows to hide.

## The design

One dial, three levels, and every row knows which level it belongs to. The
dial is **Settings depth**: **Simple**, **Standard**, **Everything**. Nothing is
removed at any depth; a row that is below the current depth is folded into a
"N more settings" row at the end of its group, one tap away, and search still
finds it and opens the fold.

### 1. Settings depth

- `AppUiSettings.settingsDepth: SettingsDepth { AUTO, SIMPLE, STANDARD,
  EVERYTHING }`, DataStore key `settings_depth`, default AUTO. AUTO resolves
  from the onboarding answer the same way the theme gallery style does: "Keep
  it simple" → Simple, "Show me everything" → Everything, anything else
  (including installs that never saw the quiz) → Standard. So existing users
  who said "keep it simple" get the simpler app on update, and everyone else
  sees no change until rows are tagged below Standard.
- Where to change it: a row at the top of Home ("Showing Simple settings ·
  tap to change"), a footer on any page that folded something ("12 settings
  on this page are hidden · Change"), and a choice row under Accessibility ›
  Settings app. All three open the same sheet with the three options and a
  line each on what they show.
- The onboarding option subtitles gain the words "and fewer settings" /
  "every setting", so the quiz is honest about what it decides.

### 2. Row levels

`SettingsGroupScope.item(level = SettingLevel.X)` with three values:

| Level | Shown at | Rule for tagging |
|---|---|---|
| `BASIC` (default) | every depth | Gboard, SwiftKey or Samsung have it; or it is a feature's master switch; or it is the list the page exists for (your languages, your snippets) |
| `STANDARD` | Standard, Everything | a choice a non-technical person could still want: a size, a style, a which-of-three |
| `ADVANCED` | Everything only | timings, thresholds, multipliers, per-variant overrides, anything that needs the engine explained, everything the audit demoted |

Untagged rows are `BASIC`, which is why the sweep is safe to do a screen at a
time: an untagged screen looks exactly as it does today at every depth.

`SettingsGroup(level = …)` tags a whole group; below its depth the group draws
as a closed fold with its row count, and opens like any fold.

Inside a group, the rows below the depth are moved to the end of its card
stack behind one row, **"N more settings"** with a chevron. Tapping it expands
them in place (the rows grow in under the row, same reveal as a conditional
row), the group's corners follow, and the open state is remembered per group
in `advancedOpen` so a power user who opened it once finds it open. While a
search highlight is pending the fold is drawn open, as the existing folds do.

Home's 17 rows get a level too. At Simple the list is Languages, Typing, Key
press, Appearance, Layout & size, Emoji, Voice typing, Clipboard, Tools,
Accessibility, Privacy, About, plus one closed "More" fold holding Rows &
bars, Text expansion, AI, Backup and Advanced.

Target sizes once the sweep is done: Simple about 120 rows across the whole
app, Standard about 600, Everything all 2,062.

### 3. Extensions

The second half of "too many settings" is features a user never asked for
showing up everywhere: a Home row, a Tools entry, a toolbar button, a search
result, a notification, background work. The tool list already has a switch
per tool, but a feature is bigger than a tool (AI is two tools, a chat, an
assistant, a memory screen, a provider page, and a FAB).

An **Extensions** screen lists feature bundles with one switch each and a line
on what the bundle adds. Off means gone: not on Home, not on Tools, not in the
toolbar or toolbox, not in search, not in the row counts, no notifications,
no background refresh. On restores all of it. First candidates, each already
behind some master flag today:

AI (tools, chat, assistant, memory, floating chat button) · Voice typing ·
Clipboard history · Text expansion · Vocabulary · Notes · To-do · Stickers,
GIFs and emoji mixes · Scanners (OCR, QR, documents) · Translate · Plugins
(Lua) · Add-on repositories · KDE Connect, Send files and Smart home ·
Keyboard modes · Selection actions · Floating button · Physical keyboard ·
Handwriting · Statistics.

Mechanically it is one `Extension` enum carrying the tools, routes and root
rows it owns, and a check at six choke points: `RootEntries`/`HomeScreen`,
`SettingsSearch`, `ToolGroups`, the keyboard's toolbox and toolbar, the
Advanced rows, and the deep-link allowlist. Depth sets the defaults: Simple
starts with a handful on, Everything with all on.

### 4. Splits and merges

Pages that stay long after tagging are split where the audit and the counts
agree:

- Typing › Gestures (69) → **Glide typing**, **Spacebar**, **Flicks and
  swipes**. The glide tuning fold stays with Glide.
- Clipboard (51) → **History**, **Paste chip and codes**, **Rules and privacy**
  (phone formats and copy rules already have pages).
- Automatic corrections (42) → **Autocorrect**, **Capitals, punctuation and
  spacing**.
- Suggestions (36) → keep one page; tag the learning and source rows Standard.

Merges the audit called for, each turning several rows into one control:
double space (period / tab / off) into one choice; the six press-and-hold
letter rows into one multi-choice; trail width, length and opacity into one
trail control; toolbar spread and scroll into one layout choice; popup min and
max duration into one slider with the max as a constant.

### 5. Chrome and layout

- `NavRow`: a value that fits goes on the title line, so the subtitle keeps
  its width (the Keyboard themes bug; done in phase 0).
- Path strip from the third level only. At the second level the title is
  already saying where you are.
- Sub-pages take a shorter expanded bar (112 dp instead of 152). Home keeps
  its badge.
- At Simple depth the `?` and link buttons leave the row; a long press on the
  row opens the hold menu, which already exists, with "What is this?" at the
  top. Reset stays, since it only appears on changed rows.
- Hub subtitles are cut to one line. "Autocorrect, capitals, punctuation and
  spacing" is a sentence, not a subtitle.
- Slider rows draw the slider on the title line's right when the name is
  short, which takes a 110 dp row down to 72.

### 6. Previews

A row that changes how the keyboard looks should show the change, not describe
it. `MiniKeyboardPreview` is already live-driven; pin it under the bar on
Layout & size, Size & position, Appearance (keys group), Key press › Key
popup, Rows & bars and Emoji (emoji row), the way the theme editor pins its
preview. Add to it:

- an animated stroke over the mini keyboard on Gestures (glide trail, spacebar
  swipe, backspace swipe), one loop per setting while its row is in view;
- a glyph per option on choice rows that have a shape (`ChoiceDetail` already
  carries one; the shape pickers use it, the rest do not);
- a swatch strip on the theme row and a glyph sample on the font row.

### 7. Motion

The "more settings" rows expand with the group's existing reveal, a depth
change cross-fades the rows that come and go, and Reduce motion keeps the
still versions, as every other motion in the app already does.

## Order of work

**Phase 0 (done 2026-10-26, uncompiled):** depth setting and resolver, row
levels and "more settings" folds, the picker in three places, Home's More
fold, the NavRow fix, docs, and 391 tags across Typing and its six sub-pages,
Key press and its three, Appearance, Layout & size and its two, Emoji and
the panel page, the Languages root, Clipboard, Voice and Privacy. Untagged
rows are basic, so every other screen is unchanged at every depth.

**Phase 1, one card per file:** tag the rest. Rows & bars, Accessibility,
Backup, the theme editor, Floating button, the AI pages, Dictionaries,
Snippets, Modes. Each card also applies the audit's merges on its screen, and
re-reads phase 0's tags on the way past: they were set from the row names and
the audit, not from use.

Done in phase 1 (2026-10-26, uncompiled): the tool pages, 273 tags across
`ToolDetailScreens.kt` and the per-tool files it calls into, plus the provider
pages and the Whisper and model lists they open. Of about 410 rows, 79 stay
basic, 192 are Standard and 139 Advanced; a page now shows its switch and one
or two everyday rows at Simple. The Toolbar and Keyword folds every page
carries are Standard, with icon colours and Match capitals Advanced.
`ProvidersGroup`, `serverItems` and `ServerFieldsGroup` take a `level`, and
the fold of how a provider list is used is Advanced everywhere. Not done
there: the AI tool's own page (with the AI pages), the audit's merges
(power-saving presets, camera haptics), and three controls drawn outside any
group, which cannot fold: the GIF and sticker content filter, QR error
correction and the typing test's default test.

**Phase 2:** Extensions.

**Phase 3:** the four page splits.

**Phase 4:** pinned previews and the animated gesture demo.

**Phase 5:** chrome: path strip depth, bar height, row furniture at Simple,
hub subtitles, slider-on-title-line.

## What this does not do

It does not remove, rename or move a route, so links, favourites, the search
index and the docs keep working. It does not change any default of the
keyboard itself. The 21 wrong defaults the audit listed are still the owner's
call and are not part of this.
