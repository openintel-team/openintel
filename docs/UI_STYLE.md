# OpenIntel UI style guide

Every OpenIntel screen follows one visual language, modelled on the Plugin
Settings screen of the Jade mod: flat, dark, quiet and dense. This guide
explains that language and how to build screens that stay faithful to it.

The code is the source of truth:

| File (`mod/src/main/java/dev/openintel/gui/`) | Role |
|---|---|
| `OiStyle.java` | Colors, layout constants and drawing helpers. Never hard-code a color that exists here. |
| `OiWidgets.java` | Flat controls: `Toggle`, `Slider`, `Picker`, `Flat` button, and `box()` for fields. |
| `FeaturesScreen.java` | Main settings screen. The reference for the whole style. |
| `ClickGuiScreen.java` + `ClickGui.java` | A single feature's settings page (option tiles). |
| `RelaySettingsScreen.java` | The reference for a list + form editor. |
| `VisualizationsScreen.java` | A larger list + form editor with dropdown pop-ups. |
| `FeatureListModel.java`, `FeatureSettings.java`, `FeatureIcons.java` | Grid model, tile → page mapping, tile icons. |

## Principles

1. **Flat, not vanilla.** No grey Minecraft buttons, bordered vanilla text
   fields or beveled sliders. Use `OiWidgets` and `OiStyle`.
2. **The game stays visible.** Draw with `renderTransparentBackground`, never
   `renderBackground`: the blur pass can run only once per frame, and the
   screens are meant to sit over the world.
3. **Black chrome, dark content.** A black sidebar on the left and a black
   bottom bar frame the screen. Content in between is dark bordered tiles on
   the dimmed world.
4. **White text, grey for everything secondary.** Use the `TEXT` ladder:
   `TEXT` → `TEXT_SUB` → `TEXT_DIM` → `TEXT_FAINT`. Blue is only an accent:
   the tile wash, the dropdown panel border, and slider fill.
5. **Controls show values, not labels.** The tile already names the setting,
   so the control shows only "50%", "‹ Everyone ›", a switch or "#80FFFFFF".
   Never "North up: OFF" next to "North up".
6. **Nothing takes effect until the user says so** on screens that edit
   feature switches (Save & Quit / Cancel), and closing any other way cancels.
7. **Keep it calm.** No animations, no drop shadows on text, no gradients other
   than the tile wash, no colored buttons.

## Screen anatomy

```
┌──────────────┬──────────────────────────────────────────────┐
│ Search…      │                Section title                 │
│              │  ┌────────────┐ ┌────────────┐ ┌───────────┐ │
│▌Current      │  │ icon Name ▭│ │ icon Name ▭│ │ icon Name▭│ │
│ Entry        │  └────────────┘ └─────▾──────┘ └───────────┘ │
│ Entry        │                Section title                 │
│              │  …                                           │
├──────────────┴──────────────────────────────────────────────┤
│ [Secondary]              Page name            [Cancel][Done]│
└─────────────────────────────────────────────────────────────┘
```

- **Sidebar** — `OiStyle.sidebar(ctx, w, barTop)`, width
  `clamp(width / 7, 96, 132)` on navigation screens, wider (up to ~360) when it
  holds a data list (Relay profiles, coverage entries).
  - The search box sits at the very top: borderless `EditBox` at `(8, 7)`,
    height 12, hint "Search…" in `ChatFormatting.DARK_GRAY`.
  - Entries start at y = 26, `SIDE_LINE_H` (18) apart, drawn with
    `OiStyle.sidebarEntry`. The current one gets a 2 px white bar at x = 2;
    hover turns text `TEXT_HOVER`. No boxes or backgrounds around entries.
  - Two-line data rows (name + grey status) use `SELECTED_BG` plus the white
    bar for the selection and `ROW_HOVER` for hover.
  - Sidebar entries jump or select; they never open other screens.
- **Bottom bar** — `OiStyle.bottomBar(ctx, width, height)`, height `BAR_H` (30)
  with a faint top line. Buttons are 20 high at `OiStyle.barButtonY(height)`.
  - Left: secondary or destructive actions (Reset to defaults, Back, Add /
    Remove, New entry, Import…).
  - Right: the primary pair, `Cancel` then the confirming action at the far
    right (`Save & Quit`, `Done`).
  - Centre (optional): the page name in `TEXT`, only if it cannot collide with
    the buttons.
- **Content area** — from the sidebar edge (+6 px) to `width - 8`, from y = 0
  to the bar. Scroll it with the mouse wheel and draw `OiStyle.scrollbar` at
  the right edge of the screen. Clip it with a scissor.
- **Section titles** — `OiStyle.sectionTitle`: centered white text in a
  `TITLE_H` (26) band. No underlines, no colored headers. Each section title is
  also a sidebar entry.

## Tiles

`OiStyle.tile(ctx, x0, y0, x1, y1, hovered, tinted)` is the basic surface:
`TILE_BG` fill, 1 px `TILE_BORDER` outline, brighter fill and border on hover.

- `tinted = true` adds the blue wash over the lower half. Use it **only** for
  tiles that hold more (a dropdown of sub-options, a form card, a preview). It
  is the signal that there is more inside. The user explicitly prefers this
  wash to Jade's flat tiles — keep it.
- Tiles sit `GAP` (3 px) apart. Never add margins inside a grid.
- **Feature tile** (main screen): 36 px high; 16 px item icon at x + 8, name
  at x + 30 (or x + 8 without an icon), switch right-aligned 8 px in. A grey
  note may follow the name ("Not installed", "Needs relay", "› North up").
  Tiles with sub-options draw `OiStyle.arrow` centered at the bottom edge.
- **Link tile**: same, with `OiStyle.chevron` instead of a switch, for entries
  that open another screen.
- **Option tile** (settings pages): 30 px high, one per row, label at x + 8,
  control right-aligned 8 px from the edge.
- **Card** (forms): one tinted tile around a whole form. See below.

## Grids and columns

- **Main screen:** up to three columns of feature tiles,
  `cols = clamp((w + GAP) / (170 + GAP), 1, 3)`, every tile the same height.
- **Settings pages:** a **single centered column**, at most 430 px wide.
  Do not go back to two columns: a second column reads as a detail view, and
  the user found it confusing. Do not stretch rows to full width either.
- **Forms:** one centered card, at most ~420 px wide (Relay) or as narrow as a
  test requires (coverage editor, 266). Never stretch fields edge to edge.

## Controls (`OiWidgets`)

| Setting | Control | Notes |
|---|---|---|
| On/off | `Toggle` → `OiStyle.drawSwitch` | Jade's two-part switch, 24×12. Green with a light mark when on, grey with a hollow square when off. Takes only its own width. Inactive = 60% black over it. |
| Number | `Slider` | Thin 2 px track, blue fill to the knob, value text right-aligned (e.g. "64 blocks"). Snaps to whole steps. |
| One of a few | `Picker` | Dark box, "‹ value ›". Left click forward, right click back. |
| Action / opens a screen | `Flat` | Dark box, centered text; `.opens()` adds a chevron, `.swatch(color)` a color chip with a checkerboard. |
| Text | borderless `EditBox` inside `OiWidgets.box` | Draw the box yourself (18 high), inset the text 5 px. The box brightens on focus or hover. |
| Pick from a long list | `Flat` anchor + popup (`VisualizationsScreen.Dropdown`, `DropdownLayout`) | Popup uses `OiStyle.panel`, opens below its anchor unless there is more room above, and must never cover its anchor. Searchable over 8 choices. |

Controls are 18 px high inside tiles and cards, 20 px in the bottom bar. Mouse
buttons must use `InputConstants.MOUSE_BUTTON_*` (26.3 renumbered them; see
`mouseButtonConstantsTest`).

## Forms (list + editor)

Follow `RelaySettingsScreen` — the user called it exactly right.

- The list of things being edited lives in the sidebar.
- The form is one tinted card, centered in the space beside the sidebar, with
  the screen title (`sectionTitle`) above it.
- Every field has a `TEXT_DIM` label 10 px above it. Short windows may drop the
  labels (keep hints) so the card still fits.
- Toggle-style choices inside a form are `Flat` buttons side by side.
- The status line lives **inside** the card under a `DIVIDER` line, colored by
  meaning: `GOOD` (connected, saved, found), `BAD` (rejected, failed, not
  found), `TEXT_DIM`/`TEXT_SUB` otherwise.
- Dialogs (rename, import path) are a centered `OiStyle.panel` with the same
  fields and `Flat` buttons.

## Dropdown panels on the main screen

Behaviour the user asked for specifically:

- Hovering a tile with sub-options shows its panel; it stays while the pointer
  is on the tile or the panel. Clicking the tile opens its settings page;
  clicking the arrow strip pins the panel.
- Panels hang **over** the tiles below. They never change tile heights or
  push the grid, except to keep another open tile from being hidden.
- Several panels can be open (pinned, or opened by a search hit).
- Tiles under a panel must not react to the pointer.

## Search

- Searching the main screen also searches every settings page's option
  labels and tooltips. A tile found that way shows "› Setting name" in grey,
  and clicking it opens the page already filtered by the same text.
- Searches that hit a sub-option open its tile's panel.
- Empty results say so in `TEXT_DIM` ("No matching features").

## Scrolling

Every scrolling list eases like Jade's menus, through `SmoothScroll`:

- Keep the screen's own scroll offset as the **target**. Wheel notches, jumps
  and clamps change only the target, and notches add up into one glide.
- Each frame call `smooth.update(target)`, then draw **and hit-test** with
  `smooth.shownPx(target)` so clicks land on what is drawn mid-glide. The
  scrollbar uses `smooth.shown(target)`.
- `smooth.snap(0)` when the content is replaced (new search, new file, new
  category) and when the scrollbar thumb is dragged — those should not glide.
- The easing uses real elapsed time, so it feels the same at any frame rate,
  and matches Jade's glide (e^-0.6 of the gap left per tick). A notch moves
  two rows, so each notch is a long, visible glide.
- Large scrolling views draw at `smooth.shownFloor(target)` and translate the
  pose up by `smooth.fraction(target)`, so a glide moves by physical pixels
  rather than whole GUI pixels. Without this the slow end of a glide visibly
  steps at high GUI scales. Push/pop the pose inside the scissor.
- Wheel deltas can be fractional (smooth wheels, trackpads); round the
  product, never cast `vertical` to `int` first.
- Jade is CC BY-NC-SA; `SmoothScroll` is our own implementation of the same
  idea. Do not copy Jade code into OpenIntel.

## Text and copy

- All user-facing strings go through `en_us.json` (`options.openintel.*`,
  `openintel.features.*`). A few older sub-screen strings are still
  hard-coded; move them when touching those screens.
- Sentence case: "Show dropped items", not "Show Dropped Items".
- Labels name the thing; tooltips explain it in one or two plain sentences.
  Tooltips wrap at 220 px (`font.split(text, 220)`).
- Truncate with `font.plainSubstrByWidth` rather than letting text overlap.

## Checklist for a new or changed screen

- [ ] `renderTransparentBackground`, black sidebar and bottom bar.
- [ ] Colors and sizes from `OiStyle`; controls from `OiWidgets`. No
      `Button.builder` in OpenIntel screens.
- [ ] Primary action bottom-right, Cancel to its left, secondary actions
      bottom-left.
- [ ] Settings in a single centered column; forms in one centered card.
- [ ] Controls show values only; the tile shows the label.
- [ ] Tooltips wrap; strings are in `en_us.json`.
- [ ] Works at 320×270 GUI pixels without overlap (see
      `VisualizationsInteractionTest` for a layout check to copy).
- [ ] `./gradlew build` passes, and the screen has been looked at in game —
      headless tests do not show what it looks like.
