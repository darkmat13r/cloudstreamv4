# DS — design system

`:core:theme` and `:core:designsystem` adoption, and what has to exist before the seven shipping
settings screens can move onto them.

**Decided 2026-09-18:** new screens use the new design system and `:core:theme`, adopted **per
screen**. A light palette is added if required, and the light-scheme colour decisions go through
the **Claude Design project** rather than being derived in code.

---

## The gap, measured

`:core:theme` has **one fixed dark scheme**, ten colours (the tenth, `sidebar`, since `KMP-111`), no accent role, no dynamic colour.
`cloudStreamDarkColors()` exists; `cloudStreamLightColors()` does not. Its KDoc is explicit that a
straight role inversion produces *"creams that nobody designed"* and that deriving a light scheme
means deciding whether hue is preserved and what `live` and `focus` become on white — **designer
calls, not module calls**.

`:shared`'s theme, which the seven shipping settings screens use today, has:

| | `:shared` today | `:core:theme` today |
|---|---|---|
| Theme modes | `Dark`, `Amoled`, `AmoledLight`, `Light`, `FollowSystem` | one, dark |
| User primary colour | yes (`primary_color_key`) | no accent role at all |
| Material You | yes, API 31+ (`Monet2` → `DYNAMIC_TWO`) | no |
| Light scheme | yes | no |

Adopting `:core:theme` on those screens **before** the gap is closed would delete four shipping
features, and the settings screens are the very place users change them. Hence the ordering below.

Two of those four rows are now settled rather than open: the user primary colour and Material You
are **deliberately not carried forward** — `DEC-13`, decided 2026-10-15. They are a cost `DS-05`
states per screen, not a gap `DS-03` has to close.

---

## DS-01 — Light scheme, via Claude Design

**Status:** open · **Blocks:** `DS-05` · **Owner:** design, not code

Take the light scheme through the Claude Design project against the existing redesign files, so it
is drawn and reviewed as a design artefact.

- [ ] Decide: is hue preserved on light grounds
- [ ] Decide: what `live` (`#CD3D4B`) and `focus` (white today) become on white
- [ ] Every resulting pair measured against the WCAG floors, the way `Muted` was — see `DS-02`
- [ ] Land as `cloudStreamLightColors()`; the `LocalCloudStreamColors` plumbing already supports two schemes

---

## DS-02 — `Muted` contrast fix

**Status:** done · #27 (merged 2026-09-18)

`#686868` measured **3.77 / 3.43 / 3.17** against Black, InsetSurface and RaisedSurface — below the
4.5:1 AA floor on all three, in a role carrying 10–12sp secondary text. `#858585` measures
**5.69 / 5.18 / 4.78** and is the smallest value clearing the floor everywhere: `#808080` misses on
RaisedSurface by 0.03.

Kept here as the worked example of what `DS-01` has to do for every pair.

---

## DS-03 — Accent role and dynamic-colour hook in `:core:theme`

**Status:** **done 2026-10-15 — decided: no accent role, no dynamic-colour hook.** The decision and
its evidence are `DEC-13`. **No longer blocks `DS-05`.**

The question was whether `:core:theme` should grow a tenth role to carry `:core:ui`'s
user-selectable primary colour and Material You, since adopting `:core:theme` otherwise drops both.

**Answer: it should not.** No code was added — the deliverable of this task is `DEC-13` plus this
line. The three things that settled it, in the order they were checked:

1. **The drawn system has no accent, and says so on purpose.** `design/Design System v2.dc.html`
   §04: *"The palette has nine role tokens and exactly one hue. The specs kept reaching for a
   tenth … Each of those has an achromatic answer, and they are now mandatory."*
2. **Confirmed against the artboards, not just the spec.** A hex census of the eight live design
   files returns the nine role values plus `#CD3D4B` and page chrome — zero chromatic value
   outside `live`. The brand hues (`#F15A55`, `#C1FD20`, `#06C149`) live only in
   `TV Directions.dc.html`, which is superseded exploration.
3. **The role would have no permitted call site.** Primary button is `ink` on `bg`, focus is the
   white ring, selection is a raised fill, both progress roles are `live` and `ink`, and source
   status is a filled / hollow / dim glyph. Every slot a `primary` would occupy is already
   assigned by §02 and §04.

And the measurement that makes it more than a preference: of the 20 fixed `CloudStreamPrimaryColor`
entries, **11 fall below the 4.5:1 text floor** on at least one of the three grounds and **6 fall
below even 3:1** (`NAVY_BLUE` 1.31, `MAROON` 1.33 on black). The default `#3D50FA` measures
3.73 / 3.39 / 3.13. §08 audits all nine role values; a user-chosen hue cannot be audited at all.
Full table, cross-check method and the cost list are in `DEC-13`.

**Not changed:** `:core:ui`'s theme — `CloudStreamPrimaryColor`, `CloudStreamThemeMode`,
`DeviceTheme` — is untouched and keeps working for every unadopted screen. The loss is realised
per screen in `DS-05`, whose PRs already have to state which theme modes survive on the screen
they touch.

---

## DS-04 — Screenshot coverage

**Status:** unblocked 2026-11-05 by `DEC-10`. The harness is `INF-11`, and it holds `NavRail` and the TV search screen.

Left: the rest of the design system and screens, one PR at a time, as each one is touched. The cases in `:core:screenshot` render nothing and
stay as they are. New ones go in `androidDeviceTest` (`.claude/rules/screenshot-testing.md`).

**Consequence for this track:** `DS-01`, `DS-03` and `DS-05` can now be guarded on TV screens that have cases. The settings screens have none,
so say so in those PRs.

---

## DS-05 — Adopt `:core:theme` on the settings screens, per screen

**Status:** blocked by `DS-01` · `DS-03` is decided, see `DEC-13`

Seven live nav-graph nodes: `SettingsPlayer2`, `SettingsUI2`, `SettingsGeneral2`,
`SettingsProviders2`, `SettingsUpdates2`, `SettingsAccount2`, `SettingsFragment2`.

Per screen, not in one PR. Each PR:

- [ ] states which theme modes still work on that screen after the change
- [ ] states that it is unguarded by screenshots, while `DEC-10` is open
- [ ] does not change `SearchableSettings` or `Screen` FQNs — they are frozen into the extension ABI
      (see `KMP-06`)

---

## DS-06 — Icon pack

**Status:** unblocked 2026-11-02 · the pack is chosen (`DEC-11`) and the desktop screens use it

`CloudStreamIcons` and `CloudStreamIcon` are in `:core:designsystem`. Left: the TV and Android screens, the 80
`ic_baseline_*` drawables, and `MediaCardDefaults`' drawn chevron. Do **not** add a new `ic_*.xml` and do not
draw a glyph in Compose — *"temporary glyphs are permanent; that is how the 80 accumulated."*

---

## DS-07 — Off-grid dimensions in the vendored Mihon tree · **won't fix**

**Status:** closed, won't fix · censused 2026-10-15 · related to `KMP-06b`

The earlier entry listed seven sites "for visibility only" and asserted *"they are upstream's."*
Censused and checked against upstream: the list is right, the reason is **wrong for two of them**.
The decision does not change, but it now rests on measurement rather than on that claim.

### Census — every `.dp` literal in `core/mihon/`

| | |
|---|---|
| Kotlin files in the module | 29 |
| …containing at least one `N.dp` literal | 14 |
| `N.dp` literals | **36**, on 32 lines (four lines carry two: `PreferenceGroupHeader.kt:19`, `Pill.kt:34`, `Slider.kt:33`, `AppBar.kt:125`) |
| …in live code / inside a commented-out block | 35 / 1 (`ListPreferenceWidget.kt:106`, `24.dp`, inside the `/* … */` that closes the file) |
| on the 4 dp step (counting the four `0.dp`) | **28 of 36** |
| **off the step** | **8 literals, at 7 sites, in 5 files** |

Counted two ways — `grep -rhoE '[0-9]+(\.[0-9]+)?\.dp' | sort \| uniq -c`, then a script that
blanks comments and string literals before matching. Both give 36 / 8; the comment-aware pass is
the one that found the dead `24.dp`. Distinct values present: 0, 1, 2, 3, 4, 4.0, 6, 8, 10, 12, 14,
16, 20, 24, 32, 34.0, 48, 56, 128.

### The eight, classified

"Upstream?" was checked by fetching the mapped upstream file from `mihonapp/mihon@main` on
2026-10-15 and diffing. "Reachable?" means: evaluated when one of the seven shipping Compose
settings screens renders.

| Site | Value | What it is | Verbatim upstream? | Reachable? |
|---|---|---|---|---|
| `PreferenceGroupHeader.kt:19` | `top = 14.dp` | group-header top padding | yes (`A`…`widget/PreferenceGroupHeader.kt:20`) | **yes — every group header, all 7 screens** |
| `Pill.kt:34` | `6.dp` | pill inner padding, horizontal | yes (`P`…`components/Pill.kt:34`) | **yes — slider value pill, 3 screens** |
| `Pill.kt:34` | `1.dp` | pill inner padding, vertical | yes, same line | **yes** |
| `SettingsItems.kt:221` | `spacedBy(2.dp)` | gap inside `BaseSliderItem` | yes (`P`…`components/SettingsItems.kt:222`) | **yes — 3 screens** |
| `Slider.kt:33` | `34.0.dp` | slider thumb height (width `4.0` is on step) | **no — ours** | **yes — 3 screens** |
| `Slider.kt:38` | `height(10.dp)` | slider track thickness | **no — ours** | **yes — 3 screens** |
| `SettingsItems.kt:64` | `Vertical = 10.dp` | `SettingsItemsPaddings.Vertical` | yes (`P`…`SettingsItems.kt:67`) | no — see below |
| `AppBar.kt:125` | `elevation = 3.dp` | tonal elevation, action-mode branch | yes (`A`…`components/AppBar.kt:158`) | no — see below |

**Two are ours, not upstream's.** `core/mihon/.../material/Slider.kt` has no `.dp` literal upstream
at all — not on `main`, and not at `v0.17.0`, `v0.18.0` or `v0.19.0` either (fetched and grepped;
`v0.16.5` 404s). `thumbSize = DpSize(4.0.dp, 34.0.dp)` and `Modifier.height(10.dp)` are a
CloudStream-side edit to a vendored file, inside a block that already diverges (upstream `main` now
drives the slider through `rememberSliderState`; we still pass `value`/`valueRange`/`steps`). The
Apache §4(b) argument therefore does **not** cover these two.

**Two are not reachable.**

- `SettingsItemsPaddings.Vertical` is used only by `SettingsItems.kt`'s own item composables, and
  **none of them has a caller outside that file** — checked for all thirteen (`HeadingItem`,
  `IconItem`, `SortItem`, `BaseSortItem`, `CheckboxItem`, `RadioItem`, `SliderItem`, `SelectItem`,
  `TriStateItem`, `TextItem`, `SettingsChipRow`, `SettingsIconGrid`, `BaseSettingsItem`: zero hits
  across `core/` and `app/src`). The one entry point the screens do reach is `BaseSliderItem`, and
  `PreferenceItem.kt:95-98` passes it `PrefsHorizontalPadding`/`PrefsVerticalPadding` (16/16),
  not `SettingsItemsPaddings`. The `10.dp` never renders today.
- `AppBar.kt:125` is the `isActionMode` arm, and `actionModeCounter` is never passed by any caller
  (default `0`; the only other mention is the `isActionMode = false` preview at `:371`), so the
  expression always yields `0.dp`. It is also a **tonal elevation** fed to
  `surfaceColorAtElevation` — a colour input on the z axis, not a width, height, padding, gap,
  radius or icon size. `dimension-scale.md` governs *"everything that occupies space"*; this does
  not occupy any.

The three `sp` values the old entry listed (`AppBar.kt:283,306` 18sp, `BasePreferenceWidget.kt:127`
16sp) are all verbatim upstream, and `sp` is outside this rule — the 4 dp step is a layout grid, not
a type scale. Not in scope; kept here only so nobody re-files them.

### Decision: won't fix, all eight

Three independent reasons, in order of weight:

1. **`CloudStreamDimens` is not on this module's classpath.** `core/mihon/build.gradle.kts` depends
   on `:core:ui` and `kotlinx.collections.immutable`; `:core:ui`'s build file names no `:core:theme`
   either, and nothing under `core/mihon/src` or `core/ui/src` imports `CloudStreamDimens`. The
   order of preference in `dimension-scale.md` opens with *"use the token"*, and step 1 is not
   available here. Reaching it means a new dependency edge from the Apache-2.0 module onto the
   first-party theme, which is the inverse of the boundary `core/mihon/build.gradle.kts` and the
   tree's `readme.md` both exist to hold (*"no first-party code belongs here"*). A fix whose first
   step is to weaken the licence boundary is not a cheap fix.
2. **The audit cost is concentrated in the worst possible file.** `Pill.kt` is one of only three
   files in the tree that `readme.md`'s mapping records as **"package line only"** — 61 lines ours,
   61 upstream, one line different. Editing `6.dp, 1.dp` converts the cheapest row in that table
   into a content diff, and there is no SHA baseline to diff against (`readme.md`, "The baseline
   problem"), so the next person re-measures by hand against a moving `main`. Two dp of pill
   padding is not worth that.
3. **These widgets are on the way out, and the rule already has a precedent for exactly that.**
   `dimension-scale.md` rules the legacy `:app` XML dimensions *"not precedent, and not a cleanup
   task either — they will leave with the views they belong to as the Compose migration
   proceeds."* The Mihon settings widgets are in the same position one layer up: `DS-03` → `DS-05`
   replaces these seven screens with `:core:designsystem` ones per screen, and the widget tree
   leaves with them. The same clause's warning applies too — *"a dimension change in a legacy
   layout reflows a screen nobody is looking at"* — except here the screens **are** shipping, so a
   reflow lands on users, and `DEC-10` means no screenshot test would catch it.

The visible error is also small enough not to force the issue: the whole reachable set is
`14 → 16` on a group header's top padding, `2 → 4` on one gap inside the slider row, and `6/1` of
pill padding. Nothing here is a geometry contract of the kind `cloudstream4-type-metrics` describes;
no focus ring, row pitch or line box depends on any of them.

### What would reopen this

- **The two Slider.kt values are the only ones with a live case**, because they are already ours and
  reason 1 is the only one that applies to them. They are a real visual change to seven slider
  preferences on three shipping screens (`SettingsPlayerScreen.kt:225,239,248`,
  `SettingsGeneralScreen.kt:164,172`, `SettingsUIScreen.kt:160,260`), unguarded while `DEC-10` is
  open, so they are the owner's call and not a drive-by. If taken: `34.0 → 32` or `36`, and
  `10 → 8` or `12` — and the thumb/track pair has to be chosen together, since the thumb is
  deliberately taller than the track.
- A `:core:mihon` → `:core:theme` edge being added for some other reason would remove reason 1.
- Any of these files being re-synced from upstream — record the SHA in `readme.md` at that point,
  which is what makes a later divergence auditable at all.

`NOTICE` needs no change: no file was edited, so the modification surface is unchanged.

---

## DS-08 — TV component batch B

**Status:** built, PR open · owner rulings it follows: `DEC-17`

New in `:core:designsystem`, from `design/Components.dc.html` and the boards that use each piece. No screen uses
them yet.

| Component | Where it comes from | File |
|---|---|---|
| `StatusPill` | `Components` §01 | `primitive/StatusPill.kt` |
| `SegmentedControl` | `Components` §01 | `target/SegmentedControl.kt` |
| `EpisodeCard`, `ContinueCard` | `Components` §02, `TV Home` 3c | `card/BelowArtCard.kt` |
| `Avatar` | `TV Detail Player` 7a | `primitive/Avatar.kt` |
| `StorageMeter` | `TV Episodes Downloads` 6c, 6e | `primitive/StorageMeter.kt` |
| `DownloadProgressRow` | `TV Episodes Downloads` 6c, 6d | `rows/DownloadProgressRow.kt` |
| 8 `CloudStreamIcons` entries | Tabler only | `icon/CloudStreamIcons.kt` |

**One existing component changed:** `StatusDot.Failed` went from a filled `live` square to a hollow `muted` one
(`DEC-17`). It had no caller outside its own file, measured by grep across `core`, `feature`, `desktopApp` and
`app/src`.

### Where the code departs from a board

- **A failed download row has no `live` rule.** 6a to 6d draw one on the leading edge, and `DEC-17` is newer. It has
  a hollow `StatusDot` beside the value and a `muted` bar. Those boards still need redrawing.
- **`PLAYING` is the pack's play glyph at 16dp**, not the board's 8dp CSS triangle (`icons.md`; 16dp is the smallest
  `CloudStreamIconSize`).
- **The pill label is `labelMono`, not the board's 20px.** A 12dp line box centres in the 24dp pill at +6dp, off the
  step. `labelMono`'s 16dp box lands at +4dp.
- **`SegmentedControl` is a 24dp visual in a 48dp cell, as chips are.** `Components` draws 28dp, which centres at +10dp,
  off the step. 24dp lands at +12dp. No board uses the control yet, so nothing has to be redrawn.
- **Corners follow the tokens:** square art as on `MediaCard`, and `shapes.medium` on the segmented control.
- **Below-art cards do not lift.** The boards draw no lift on them, unlike `BackdropCard`.

`LiveTv`, `Movies` and `Tv` icons are not added: `DEC-17` took them off the rail, so nothing uses them. They can
return with their filters.

### Open, and not settled here

- **`ProgressRule` 2dp or 4dp** (handover §8.4). The cards use `MediaCardDefaults.ProgressRuleHeight`, 4dp, as
  asked. 3c agrees, and `Components` draws 4px, which is 2dp. The download row uses `ProgressRule`'s own 2dp, as 6c
  and 6d draw it.
- **`Components` draws the selection marker `muted` and "never a white bar"**, and `SelectionMarker` defaults to
  `ink`. It is white on a selected `DownloadProgressRow`, through `ListRow`. Not changed here.

`:core:designsystem` has no `abiValidation`, so it has no `checkKotlinAbi` or dump to update. Measured from
`:core:designsystem:tasks --all`; the plugin is only in `cloudstream.kmp.extension-api`.

`SegmentedControlDeviceTest` runs on `Television_720p`: real D-pad keys through the input dispatcher, the ring read from
the screen, and centre choosing. The other components have JVM tests only, until a screen uses them.

Screenshot cases are in `core/screenshot/.../TvComponentScreenshots.kt`. They compile, and render nothing until
`DS-04` is unblocked. `TvComponentsTest` holds the bounds and states, drawn offscreen.

## DS-09 — `NavRail`

**Status:** built, not wired into `:app` · owner ruling it follows: `DEC-17` · stacked on `DS-08`

`rail/NavRail.kt` in `:core:designsystem`, from `TV Home` 3a and 3b. The items are parameters; `DEC-17` rules what they
are. `KMP-33` replaced the XML rail with it on Android TV.

It always measures `NavRailWidth`, 76dp. It is expanded exactly while focus is inside it, and then paints to 188dp over
its neighbour. The items' nodes change width and are never swapped, because removing the focused node loses focus.
`NavRailTest` holds this. The code was broken on purpose in ten ways, and each one turned a test red.

### Where the code departs from a board

- **`Components` §06 is not followed.** It draws 96 and 320 wide, 12px corners and a ring on the focused row. `DEC-17`
  chose `TV Home`'s style, which is 152 and 376 and an `ink` fill.
- **An exception to `ui-components.md`, which lists `tvFocusRing` for any TV focusable.** The rail's focus treatment is
  the `ink` fill instead. Ruled: it is right, since white means focus. The rule file is not edited.
- **Corners follow the token,** `shapes.medium`, 8dp, the role for rows and buttons. `TV Home` draws 8px, which is 4dp.
- **No ring on a focused collapsed cell** (`.nic.foc`, `Components` §06, `Design System`). Focus expands the rail, and
  the first frame after focus arrives is already wide and filled, so that state has no frame to draw on. No code for it.
- **The logo's space stays when expanded.** 3b draws no logo and its first item at y 56px. Following it would move the
  focused item 88px, 44dp, up as the rail opened. The first item is at 144px, 72dp, in both states.
- **The content does not move.** 3b draws it at x 456 while its own caption and 3a say it never reflows.
- **The focused label is not heavier.** `.nie.foc` is weight 800 over 600. Ruled: the typography tokens win, and
  `CloudStreamTypography` carries no weights.
- **Glyphs are the pack's**, not the boards' 32px rounded squares (`icons.md`).

### Rulings

- **Focus entering the rail lands on the current page's item,** so the current page is what someone sees first.
- **3b's 46% dim and `.raile`'s 68px shadow belong to the screen hosting the rail,** because the dim covers content and
  the rail cannot own it. `KMP-33` built them, as `NavRailHost`. They are not in this component.

`NavRailDeviceTest` runs on `Television_720p`: real D-pad keys through the input dispatcher, with a focusable screen beside
the rail. Removing focus entry there sends LEFT to `Downloads` instead of `Library`, which is why it is ruled. `NavRailTest`
holds the geometry on the desktop scene. Returning focus on RIGHT is tested on the real app, in `TvRailNavigationTest` (`KMP-33`). The
screenshot cases are in `core/screenshot/.../NavRailScreenshots.kt`. They compile, and render nothing until `DS-04` is
unblocked. Whether the expanded case captures after its focus effect runs cannot be known until then.

---

## DS-10 — Batch A of the TV kit: panel motion, two focusable-when-disabled targets, the trailing focus stop, and the text field

**Status:** done · **Scope:** `:core:designsystem`, plus the one call site in `:feature:search`

Five gaps from `docs/plans/tv-screen-audit.md` §3's "Missing from `Components.dc.html`" table, which
is also the queue's `Q3` — the batch every panel board (4d, 5d, 5e, 7e) and every row-with-verbs
board (6d, 7c, 8b, 8c, 4c) waits on.

| What | Where | Board |
|---|---|---|
| Panel slide, 240ms ease-out, interruptible | `panel/SidePanelHost.kt` | `Components` §05 |
| Disabled-but-focusable text button | `target/TextButton.kt` | `Components` §01 |
| `ListRow` trailing focus stops | `rows/ListRow.kt` | `Components` §03, `TV Episodes Downloads` 6d |
| Disabled row that is still a stop | `rows/ListRowState.kt` | `TV Library` 5d |
| A public text field | `target/TextField.kt` | `TV Search` 4a–4c |
| Selected marker is `muted`, not `ink` | `primitive/SelectionMarker.kt` | `Components` §03 |

Each new behaviour is **opt-in**: `focusableWhenDisabled` on the two buttons and on `ListRowState`,
`actions` on `ListRow`. Every caller that predates this task renders and behaves exactly as it did,
which is why the defaults are the old behaviour and not the board's.

### `DEC-17` is implemented here too

The ruling arrived mid-task: a single-choice panel (subtitle, audio, sort) applies **at once**, a
multi-select panel (sources) applies on **Done**, BACK closes either, and there is no Apply/Cancel.
`SidePanel`'s KDoc said the opposite — it required both buttons and told callers to hold a draft —
so it was rewritten, and `commit` became nullable so a panel that needs no bottom row collapses the
region to the 28dp focus band instead of reserving 76dp for nothing. The previews were drawing the
retired Apply/Cancel pair and now draw 5d's `Done` row.

### The selected marker was white, and the boards forbid that

`SelectionMarker`'s `color` defaulted to `ink`, so a selected row drew a white bar. `Components`
§03 states the prohibition directly — *"raised fill plus a muted 4px marker, **never a white
bar**"* — and all five TV boards encode it in one line of CSS, `.lrow.sel .lmark { background:
var(--mut) }` (`Components:148`, `TV Library:149`, `TV Detail Player:108`,
`TV Episodes Downloads:97`, `TV Search Suggestions:123`). White means focus; a white marker says
"you are here" about a row the user is not on.

The default is now `muted`. **Nothing else on screen changed**, which was checked before the edit
rather than after: `SelectionMarker`'s only caller in the repo is `ListRow`, and of `ListRow`'s
four callers outside this module — `SearchRecentColumn.kt:73,82`, `DetailScreenTv.kt:231,267` and
`DesignSystemScreenshots.kt` — only the screenshot suite ever passes `selected = true`, and that
suite has no committed reference images to invalidate because the plugin does not render on this
toolchain (`DEC-10`, `.claude/rules/screenshot-testing.md`).

**Still open, and deliberately not done here:** the same sentence also gives a selected row a
**`raised` fill**, and `ListRow` paints no background at all. That is a visible change to every
selected row rather than a correction to one token, so it wants its own change and its own look at
the diff.

### What the tests found that reading would not have

Five defects, four of them in `SidePanelHost`'s pre-existing focus handling and one in this task's
own new code. None was visible in a diff; each came off a failing assertion.

1. **The focus restore never worked.** `SidePanelHost`'s KDoc promised that closing a panel returns
   focus to the control that opened it. It did not: the trap, `onExit = { cancelFocusChange() }`,
   also cancels the host's own `contentFocus.requestFocus()`, because that has to move focus out of
   a row *inside* the trapped group. The close cleared focus and never re-placed it, leaving the
   screen with **nothing focused** — the dead TV screen `focusableWhenDisabled` exists to prevent,
   one layer up. The cancellation is now gated on the panel being open, read live inside the
   callback. `SidePanel` has no callers, so this had never run anywhere but in a test.
2. **Why nobody had seen it.** A `focusProperties { canFocus = … }` sat *before* `focusGroup()`,
   and `focusGroup()` is itself `focusProperties { canFocus = false }.focusTarget()`, so it
   overwrote the line and left the group focusable. Focus landed on the **group**, never on a row,
   so moving it away was never an exit for the trap to block. Removing the dead line put focus on
   the first row — where the design wants it — and that is what exposed 1.
3. **A disabled-but-focusable control had no accessible name.** This task's own new code.
   `clickable` and `selectable` both apply `semantics(mergeDescendants = true)`, which is what
   lifts a row's title off its child `BasicText` onto the focused node; the new
   `focusableWhenDisabled` path used a plain `semantics {}`, which merges nothing. The device test
   read the focused stops as `node#150` and `node#159`. So 5d's row would have been reachable and
   **anonymous**, and the reason it exists to show would not have been announced — the feature
   exactly inverted. Both paths now merge.
4. **A D-pad press during the exit drops focus.** Three frames into the 240ms slide-out, a RIGHT
   move does not re-enter the leaving panel but does not stay on the opener either: nothing is
   focused until the panel unmounts. **Not fixed** — the remaining fix is for the host's *content*
   group to refuse to yield focus while a panel leaves, which is a behaviour change to pre-existing
   code and wants its own diff. The JVM test asserts today's behaviour on purpose, so closing the
   gap fails that test and points at the KDoc to update.
5. **One of the tests was vacuous**, which is how the harness got written down. Arrow keys move no
   focus in an offscreen `ImageComposeScene` — not even for an ordinary enabled button — so the
   first "a leaving panel does not take focus back" passed while moving nothing at all. The D-pad
   is now driven through `FocusManager.moveFocus`, which is what an Android press ends up calling.
   Separately, an animation's first frame after launch is a *seed*: `animateTo` suspends on
   `withFrameNanos` to learn its start time, so a single frame rendered 120ms into a 240ms slide
   finds the panel at zero.

### Tests

| Suite | Count | Where |
|---|---|---|
| JVM, `:core:designsystem:jvmTest` | 24 new (31 in the module) | `SidePanelMotionTest` 8, `ListRowStopsTest` 6, `TextButtonFocusTest` 5, `TextFieldTest` 5 |
| Device, `connectedAndroidDeviceTest` | 5 | `FocusStopsDeviceTest`, on `Television_720p` |

The JVM suite drives focus with `FocusManager.moveFocus` and reads geometry off measured bounds and
rendered pixels; `core/designsystem/src/jvmTest/.../harness/SceneHarness.kt` holds the scene, the
D-pad and the pixel probe, with the two traps above documented on the methods that exist because of
them.

The device suite is for the half the JVM cannot reach: Android's two-dimensional focus search picks
a candidate by **geometry**, so whether a 48dp cell 12dp to the right of a row is what RIGHT finds
— board 6d's arithmetic — is not demonstrable on the desktop focus owner at any effort. It is now
measured: `Ozymandias → Play → Delete` and back by real D-pad, no dead ends, no traps. It also names
each stop by its accessible label, which is what caught 3.

### Three things found while checking the audit against the boards and the code

1. **8c does not need a disabled row.** §3 lists "Disabled row with a reason, still a stop" as
   needed by "5d, 8c row 5". On 8c row 5 is `dune messiah` with an honest `0 SOURCES`, and its
   markup carries `class="zero"` on the *count* — `.trail .lt.zero { color: var(--mut); }` — not
   `off` on the row. It is a normal, reachable row with a muted count. **5d is the only board that
   needs this.**
2. **`:core:designsystem` has no ABI dump to update.** `abiValidation` is switched on by
   `cloudstream.kmp.extension-api` only (`build-logic/convention/src/main/kotlin/cloudstream.kmp.extension-api.gradle.kts:58`);
   this module applies `cloudstream.kmp.compose`, which does not. The six `api/*.api` files in the
   tree are `:app`, `:library`, `:core:model`, `:core:data`, `:core:common` and `:core:network`.
   A public API change here is reviewed from the diff, not from a dump.
3. **The text field's hairline consumes a dp that the board's does not.** `TV Search` draws the
   field's edge as `box-shadow: inset 0 0 0 1px var(--line)`, which costs no layout, so text starts
   at exactly the 12dp padding. The code uses `Modifier.border`, which insets the content box, so
   text starts at 13dp — one dp off the step, for exactly the reason `GhostTextButton`'s KDoc gives
   and solves with a draw-only `hairlineOutline`. **Carried over unchanged**, because this task
   promised the search screen would render identically; it wants its own one-line change and its own
   look at the diff.

### Promotions this leaves for `:core:theme`

`TextFieldDefaults.Height` (48dp) and `TextFieldDefaults.HorizontalPadding` (12dp), and
`SidePanelDefaults.BandOnlyHeight`. The existing list in `ListRowDefaults`, `SidePanelDefaults`,
`TextButtonDefaults`, `ChipDefaults` and `IconCellDefaults` is unchanged.

---

## DS-11 — Boards: the text button's ring goes on the button

**Status:** done · #146 · boards only, no Kotlin

`.btncell.foc` drew the ring on the 96px focus cell, so it sat 4px from a 64px button's sides and 20px from its top and
bottom. `.btncell.foc .bbtn` now draws it on the button, in `TV Search`, `TV Search Suggestions`, `TV Library`,
`TV Episodes Downloads`, `TV Detail Player` and `TV Onboarding Extensions`. Three boards have a live focused cell: 8a-ii,
Search partial and Search no results, now 4px on all four sides, from `getBoundingClientRect` and again from the pixels.
Every element box in the eight boards and `Design System v2` measured identical before and after.

**Not changed, on purpose.** `.chipcell.foc` and `.tabcell.foc` still ring the cell. `Chip` does too, and says why in its
KDoc, so that gap is the rule rather than this defect. `PrimaryTextButton` and `GhostTextButton` already ring the button.

---

## DS-12 — The ring goes on the visible control: chips, tabs and segmented options

**Status:** in review · #150 · `DEC-17`, 2026-11-05: *"make chips even like buttons"*

`Chip` and `SegmentedControl` ringed the 48 dp cell, leaving 2 dp at the sides and 14 dp above and below. The ring is now on
the 24 dp visual: `FocusRingOffset` 2 dp clear and `FocusRingStroke` 2 dp thick on all four sides, with the visual's own
corners. A chip keeps its stadium. A segmented option is rounded on the control's outer edge and square at a neighbour,
because a fully rounded ring cuts across a square fill's corner. The cell is still the focus and hit target, and it still
sets every position and measured size. A focused chip now draws above its neighbours, as a segmented option already did.

**Boards.** `.chipcell.foc .chip` and `.tabcell.foc .tab` carry the ring in `Components`, `TV Search`,
`TV Search Suggestions`, `TV Library`, `TV Episodes Downloads`, `TV Detail Player` and `TV Onboarding Extensions`.
`Design System v2`'s rule says the same. The text button's corner is 16 px, which is `shapes.medium`, on `.bbtn` in those
boards and `.btn` in `Components` and `TV Home`; Compose already drew that. `Components` draws its chips 48 px tall, not 40,
which also puts them at +24 in the cell where they sat off the step at +28.

**What was measured.** Only `Components` has a live focused chip. On the others the `foc` class was added in the browser to
every chip and tab cell. Every one went from 28/4/28/4 px (`Components` 32/4/32/4) to 4/4/4/4 px, and pixels agree on the
three checked. Elements moved: none, apart from the four `Components` chips' own height and one text span's width in
`Design System v2`. The one board that moved at first was `Design System v2`, where a longer sentence wrapped a table row and
shifted 635 boxes, so that sentence is deliberately short.

**Tests.** `FocusRingGapTest` (JVM) counts the gap outward from the visual in rendered pixels, on all four sides of each
chip and option, and fails six ways on the old drawing, at 14 dp above and below. `ChipDeviceTest` presses real D-pad keys
along a `ChipRow` on `Television_720p`. Two tests pinned the old rule's sample site and now read it above the visual:
`TvComponentsTest`'s focus-stop case and `SegmentedControlDeviceTest.ringAbove`.

**Left as they were.**
- `IconCell`, and the boards' `.ibx.foc`: it has no fill in Compose, so the cell is the only box to ring. `Components` does
  draw a 48 px `.ivis` fill inside its 96 px cell, so there the gap is uneven in the same way. Not ruled on.
- `.seekcell.foc` in `TV Detail Player`: a 96 px cell around a thin rule. Not ruled on.
- `TextButton`, `StateActionButton`, `ListRow`, `TextField`, `Avatar`, the cards, and every desktop control, including
  `TopLevelTab` and `:feature:library`'s tab strip (`DesktopButton`): each rings its own filled box already, so none has a
  cell taller than its visual. No desktop screen uses `Chip` or `SegmentedControl`.
- `NavRail`: focus is a fill, with no ring (`DS-09`).

**Board values that disagree with a token, reported and not changed.** Corners, against `shapes.medium` at 16 px: `.field`,
`.ibx` and `.nic` (8 or 12 px depending on the board), the list row and `.seg` (8 px). `.srcbtn` in `TV Search` is 96 px tall at 8 px where
Compose draws that control as a `Chip`. `.cbtn` has no Compose control to compare yet. In `Components`: chip
padding 16 px against `ChipDefaults.VisualHorizontalPadding` 24 px, chip spacing 8 px against `ChipRowSpacing` 48 px, so a
ring there touches its neighbour, and `.seg` 56 px against the 48 px visual `SegmentedControl` documents.

---

## DS-13 — Boards: four old Home features, `TV Home Extras` 3e to 3m

**Status:** in review · #149 · boards only, no Kotlin

The owner asked on 2026-11-05 for the bookmarks row and its filter, a Random button and `None` as a source, a details panel
on a card, and a manual reload. No board drew them. `design/TV Home Extras.dc.html` holds nine artboards, numbered on from 3d,
redrawn the same day to the owner's rulings: hold OK plus the Menu key reach the panel; Random is not a Home source, and
`None` alone remains, while the Random button stays, always shown; search is in the top bar only; Subscribe is in the panel; the token wins for list rows and rings; a failed reload keeps Continue watching and Bookmarks above the error (3m); icon rings sit on the visible 48 px tile, and `IconCell` in Compose is to be changed to match. The primary button stays white at rest, as `Components` defines it. Its
closing note gives each board's focus order, states and prerequisites by file and line. 464 boxes: 0 off-grid, 0 band
violations, 0 clipped, 0 collisions, 9 rings at 4px gap and 4px stroke on all four sides, from `getBoundingClientRect` and
again from the pixels.

**Overridden on the Mac, 2026-11-08 (the owner, through the PM):** "the Random button stays, always shown" no longer holds
on the Mac. Settings' Appearance pane has a Random Button switch (`KMP-90g`), on by default, so Home's Random shows until
someone turns it off. The TV ruling is unchanged.

---

## DS-14 — Shared components to the boards, from the TV design review

**Status:** in review · `DEC-17` (with the 2026-11-05 rulings) · `:core:designsystem`, one line in the TV shell, boards

The design reviewer measured nine shared-component defects off emulator captures. Each was checked against the code and
the boards before it changed, and each is held by a JVM test that fails on the old drawing (control run: all ten new
cases red against the stashed old components, each for its own reason).

| # | Component | Was | Now | Board |
|---|---|---|---|---|
| 1 | `Badge`, card badge inset | pill corner, 8dp padding, 0.72 ground, 8dp inset | `shapes.small`, 4dp padding, 0.78 ground, 4dp inset | `.bdg`, `.badges`, `.tl` |
| 2 | Card reveal | scrim from 45% of the card down, 8dp inset | a scrim **block behind the text**: 16dp lead + text + 4dp inset, solid 0.92 over its bottom 30% | `.revt` in 4a and 3d |
| 3 | `SeeAllCard` | `raised` fill and a chevron drawn on a `Canvas` | `surface` at 0.6 (`#060A0D` over `bg`), a dashed `line` hairline, no glyph | `.seeall` |
| 4 | `StateView` body, `ListRow` subtitle | `ink2` | `muted` | `.svb`, `.ls` |
| 5 | `Chip` (tabs and filter chips) | selected was an `ink` fill with a `bg` label | both states `raised` with a hairline: unselected `muted` label on `line`, selected `ink` label on `muted`; count `muted` | `.chip.sel` in 4a; owner ruling for 5a |
| 6 | `FailureBanner` | `live` dot, `Secondary` retry | hollow `StatusDot.Failed`, `Primary` retry (`ink` fill) | 4b `.sg.fail`, `.bbtn` |
| 7 | `SidePanel`, `ListRow` | panel `raised`, header bottom-aligned ~60dp low, no selected fill | panel `surface`, header top-aligned at `ChromeRowTop`, a selected row is `raised` | 4d |
| 8 | Panel dim over the TV rail | the rail stayed bright | `SidePanelHost` publishes its scrim to `SidePanelOutsideDim`; `TvRail` draws it with `Modifier.sidePanelOutsideDim` | 4d |
| 9 | `IconCell` | ring on the 48dp cell, no visible tile | a 24dp `raised` tile with a `line` hairline, the ring on the tile, 2dp clear on all four sides | `.ivis`, `TV Home Extras` |

**Item 2, measured.** The boards do not disagree with each other: `.revt` is one rule, `padding: 32px 8px 8px` and
`linear-gradient(0deg, rgba(0,0,0,0.92) 30%, transparent)`, sized by its text. Measured in Chromium with fonts loaded:
104 of 240px (43.3%) on 4a, whose title takes two lines; 72 of 240px (30%) on 3d, one line. No single fraction of the
card reproduces both, so the scrim is the text's own background. `TV Library` 5a is the exception and is **not** followed:
its grid card uses `padding: 40px 16px 16px` and `0.94` at 34% (120 of 288px, 41.7%). Not ruled; reported.

**Item 5, boards.** 5a's `.tab.sel`, and `.chip.sel` in `TV Detail Player`, `TV Episodes Downloads` and
`TV Onboarding Extensions`, were white fills; they now match `TV Search`'s `.chip.sel`. `TV Home Extras` draws its selected
chip with an 8x16 `muted` mark inside the pill rather than a muted edge; Compose follows `TV Search`, since a mark cell
would widen every chip. **Item 9, boards.** `.ibx.foc` moved to `.ibx.foc .ivis` in `Components`, `TV Search`, `TV Home`,
`TV Detail Player`, `TV Episodes Downloads` and `TV Onboarding Extensions` (and `.avatar` in `TV Home`'s chrome), and
`Components`' icon-button note now says so. Every element box measured identical before and after in all seven boards,
apart from the one bold span in that note; the live focused tile on 6d measures a 4px gap and 4px stroke on all four
sides from the pixels.

**Token over board, reported.** The tile's corner is `shapes.medium` where the boards draw 8px. The see-all keeps the
cards' square corner (`shapes.none`) where the boards draw 8px.

**Screens whose look changed.** Android TV: Search (chips, the partial-failure banner, result badges and reveal, history
subtitles, no-results body) and Library (tabs, grid badges and reveal, empty-page body). Desktop: Search and Home (the
failure banner's dot and white retry), and the `StateView` body on Search, Home, Library, Detail and the player's error.
`DetailScreenTv`'s list subtitles change too, but nothing hosts it on master. Android TV Home (`#147`, merged
while this was open): its own failure row now takes the `Primary` retry too, and every one of its six references changed
(the account `IconCell`'s tile, badges, reveal, the see-all, the source panel's surface, header and raised selected row,
the empty-state body). `#153` brings the detail panels.

**Tests.** JVM: `DesignReviewFixesTest` (9) and one `FocusRingGapTest` case. Device: `TvRailNavigationTest.
aSidePanelOpenOnThePageDimsTheRailToo` opens a panel on the real Search page and measures the rail's brightness. Screenshot
cases: `cards-rail-end-poster-and-see-all` and `panel-open-over-rail` (new); every search and library reference was
re-recorded after its diff was read, and `library-grid-focus-tab` already covers a selected tab with another focused.

## DS-15 — Shared details from the second design review: marker, scrim, 5a's reveal, the state action's ring

**Status:** in review · follows `DS-14` · `:core:designsystem`, two test fixtures, one board

The design reviewer measured these off emulator captures after `#156`. Each was checked against the boards and the code
before it changed, and each is held by a test that fails on the old drawing.

| # | What | Was | Now | Source |
|---|---|---|---|---|
| 1 | `SelectionMarker` | a 2dp square-ended rule, the full 32dp of the row's content box | a 4dp by 24dp mark, `shapes.pill`, centred in the row | `.lmark { width: 8px; height: 48px; border-radius: 4px }` on 4d and every list-row board; `Design System v2`: *"The marker cell is 8 wide, not 4"* |
| 2 | `SidePanelHost` scrim | 0.72, under a KDoc calling it invented | 0.6, the KDoc corrected | 4d `.scrim { background: rgba(0,0,0,0.6) }`; `bg` is `#000000` |
| 3 | `TV Library` 5a `.revt` | `padding: 40px 16px 16px`, `0.94` at 34% | `padding: 32px 8px 8px`, `0.92` at 30%, the rule Search and Home use and the code draws | `MediaCardDefaults.RevealScrimLead`, `ContentPadding`, `SCRIM_END_ALPHA`, `SCRIM_SOLID_FRACTION` |
| 4 | `StateActionButton` | held focus with no ring when `StateView` focused it on arrival | the ring, as any other focus | reported by `cloudstream-worker-1` on the TV detail failure state |

**Item 1, callers.** `SelectionMarker` has one caller, `ListRow`, and a row draws a mark only when `selected`. On master
that is the Home source panel's current source (`HomeSourcePanelTv`), on Android TV and anywhere else that panel is
hosted, and `DownloadProgressRow` when a caller passes `selected`, which none does yet. Search's recent column and
Detail's episode and link lists never select a row; `:desktopApp` does not use `ListRow`. Measured on the device at
1.331 px/dp: the mark went from 3x42 px at x940 to 5x32 px at x939, rows 186 to 217 of a row at 170 to 234.

**Item 2, the rail.** `SidePanelOutsideDim` carries the same value, so the TV rail now dims to 40% of its light, not 28%:
the brightest rail pixel under an open panel is 102 (255 × 0.4), was 71 (255 × 0.28), on `panel-open-over-rail`.
`TvRailNavigationTest.MAX_PANEL_DIM_RATIO` was derived from 0.72 (28% left, bound 0.4); it moves with the scrim to 0.52,
the same 0.12 margin over 40%.

**Item 3, measured** in Chromium with the fonts loaded (all seven faces reported `loaded`): 5a's focused card's `.revt`
was 120 of 288px (41.7%) with the text at +16/+40; it is 104 of 288px (36.1%), 32 + 32 + 8 + 24 + 8, with the text at
+8/+32 and 176px wide. Every other element box on the board, 777 of them, hashed identically before and after. `DS-14`
reported 5a as unruled; the brief ruled it: one rule per component.

**Item 4, cause, measured.** `StateActionButton` read focus with `collectIsFocusedAsState`. `StateView` requests focus on it
in its first frame, before that collector subscribes, and an interaction source does not replay, so the `Focus` interaction
was lost. Focus that arrives later, by D-pad, was always drawn, which is why Home's, Search's and Library's references
show a ring. The button now reads `onFocusChanged`. The ring itself was already the text button's: `tvFocusRing` with
`FocusRingStroke` and `FocusRingOffset` on `shapes.medium`. Separately, a temporary probe showed that **in touch mode
`StateView`'s initial focus lands nowhere** (`clickable` is only focusable in non-touch mode): a test that composes a
failure state without a key press will see nothing focused, whatever the button does.

**Tests.** JVM: `SelectionMarkerAndScrimTest` (2) and `StateActionRingTest` (1); control run against the old components,
each red for its own reason (`the mark starts at the cell expected:<24> but was:<25>`, `published 0.72`,
`expected:<128> but was:<120>`). Device: `StateActionDeviceTest.screenshotStateViewFailureFocusOnItsAction` asserts the
even ring on all four sides on the emulator and records `state-view-failure-focus-action` (new).

**Fixtures.** `home-source-panel-focus-current` composed its rail without the dim the TV shell applies, so its reference
showed a bright rail beside an open panel; the test now applies `sidePanelOutsideDim` as `TvRail` does.
`panel-open-over-rail` showed a selected row with a failed marker beside an unselected healthy one; it now has 4d's five
providers in 4d's states.

**Re-recorded, each diff read:** `panel-open-over-rail` (marker, scrim, rows), `home-source-panel-focus-current` (rail
dimmed, page lighter under 0.6, the selected row's marker). New: `state-view-failure-focus-action`.

**Screens whose look changed.** Android TV Home's source panel: the rail dims with the page, the page behind is lighter
(0.6), and the current source's marker is the short rounded mark. On master that is the only real `SidePanelHost`; `#153`'s
detail panels take the same. The state action's ring: wherever a `StateView` focuses its action on arrival, which is
Android TV Home, Library, Search and Detail, and on desktop Home, Library, Search and the player. Every existing reference
compared 0 pixels different, so none of the recorded screens was in that state; the JVM harness is desktop Compose and
reproduced the missing ring, but `:desktopApp` was not run to look.

**Results, local** on `Television_720p` and this machine, 2026-11-05: device 157 tests (`:core:designsystem` 19,
`:core:connectivity` 2, `:core:strings` 6, `:feature:home` 31, `:feature:library` 13, `:feature:search` 15, `:app` 71),
0 failures, 1 skipped; all 19 screenshot cases `same`, 0 pixels. Gates on `3bc4f8dab`: `abi=0 check=0 xml=0 extapi=0
canary=0 dist=0`, 164 result files read.

**Board upload.** `TV Library.dc.html` to the Claude Design project: the project's copy matched master byte for byte
before the write (34,314 bytes, SHA-256 `8cb77682…`), and after it matches this branch's file byte for byte with the
served page's injected block removed (34,312 bytes, `01764163…`).

---

## DS-16 — Text buttons lead with a glyph; icon-only only for toggles and overflow

**Status:** in review · #159 · `DEC-17`, 2026-11-05: *"use icons here if possible or other places which are common, update the
design and instructions"*, and the owner's ruling the same day.

**The rule** is in `.claude/rules/ui-components.md`, "Buttons", with the glyph table: one white primary, first; secondary
common actions outlined with glyph and label; a meaning no glyph conveys stays text; icon-only only for toggles, overflow
and search, naming itself when focused; targets keep the three settled classes. `Design System v2` carries it as a law, with the
planned phone and tablet direction as another, and `Components`' button spec draws it.

**Compose.** `PrimaryTextButton`, `GhostTextButton` and `StateAction` take `icon: ImageVector? = null`: a 16 dp glyph
(`CloudStreamIconSize.Small`), 8 dp (`TextButtonDefaults.IconGap`) before the label, in the label's colour, the pair
centred. With null, the code path is the old one. `IconOnlyButton` and `IconOnlyToggle` are new: a 48 dp cell, a 32 dp
outlined tile with the ring on it, `raised` when checked, and the label drawn under the cell while focused without being
laid out. Five meanings added to `CloudStreamIcons`, all Tabler: `StartOver` (`Rotate`), `Trailer` (`Movie`),
`Episodes` (`List`), `OpenExternal` (`ExternalLink`), `Link` (`Link`).

**Boards.** 7a and 7b's row is redrawn to the ruling: Resume with a play glyph, the progress rule and time left under
it, Start over, Trailer, then a watchlist toggle and More icon-only. Season, Episodes and Full synopsis leave the row,
the chrome's bookmark moves into it, and 7b's one focus is the toggle, on, with its label. Every other common-action
text button on the authoritative boards has its glyph square, per the table. `TV Onboarding Extensions`' two widest
buttons grew by 64 to fit, on the step.

**Review of #159, the coordinator's rulings.** Rule 4 names three exceptions that stay icon-only without a label on
focus, each with a content description: the chrome's Back cell, the player's transport controls (7d) and the nav rail,
which shows its labels when expanded. Favourite is an `IconOnlyToggle` in the chrome on 7a and 7b, the same 64 tile as
the row's. A toggle that is off has a `muted` glyph, on an `ink` glyph on the `raised` fill, because the fill alone is
about 1.15:1 and Tabler has no filled bookmark or heart. The strip under Resume ends at 632, so the cast header moved to
696 to keep the 64 section gap, and the cast to 784..1024; the caption band above the row is unchanged. The notes the
redraw had made wrong (7a LOADING and EMPTY, 7b PARTIAL and its band list, the closing note) are corrected.

**Not done here.**
- No screen adopts the glyphs. The list per screen is in the PR.
- The episode list with season tabs under 7a's row is not drawn; board 6a has it.
- `TV Home`'s hero still has its pre-existing icon-only `.btn.ico`, which by the rule needs a meaning and a label on focus.

## DS-17 — Icon-only glyphs muted at rest, ink on focus; the watchlist toggle by shape

**Status:** open, decided 2026-11-06 by the owner (`DEC-17`). Not built. Recorded first in `.claude/rules/ui-components.md` rule 4.

- `IconOnlyButton` and `IconOnlyToggle` draw their glyph `muted` at rest and `ink` on focus, on every screen. Today it is `ink` at rest.
- A toggle shows on or off by glyph, not colour. The detail row's watchlist toggle is `Add` (Plus) off and `Done` (Check) on. `Done` already exists on Tabler `Check` (the subtitle picker's chosen mark); a second name for it, `Added`, fails `CloudStreamIconsTest`'s one-glyph-per-meaning check, so the toggle reuses `Done`. A toggle whose pair is named draws no `raised` fill for on: shape alone says state.
- Fold in the account cell on Home's top bar, which is still `IconCell` (a smaller filled tile with a muted glyph, ringing its cell) beside `IconOnlyButton`s, so one row shows two icon styles. Raised by the reviewer on #165.
- Every screenshot reference that shows an icon-only control at rest changes. Each diff is looked at, not re-recorded blind.

---

## DS-18 — Boards: downloads on desktop, phone and tablet

**Status:** approved by the owner 2026-11-06 (*"build them"*), PR open · boards only, no Kotlin · `DEC-19`

The owner asked on 2026-11-06 for every feature to be designed for TV, desktop and phone/tablet. Each platform gets the best
design for its own input; sharing comes second. Downloads is the pilot (`KMP-54` builds the desktop screen from it).
`design/Desktop Downloads.dc.html` holds D1 to D6: titles with a hovered row, multi-select and the context menu, the delete
confirmation, one series, empty + offline + disk full together, and the 800×520 minimum window. Downloads is the fourth
destination, so the desktop bar becomes a sidebar (`desktop-design.md`: "a sidebar is worth it at the fourth").
`design/Phone Downloads.dc.html` holds P1 to P6 and T1: titles, swipe to delete, choosing several, one series, an episode's
verbs as a bottom sheet, empty + offline + disk full, and the two-pane tablet at 840 dp and wider.

Both are drawn at 1 px = 1 dp, not the TV's px ÷ 2. Glyphs are the Tabler pack itself, loaded at 1.119.0 from jsdelivr, never
drawn. Measured from `getBoundingClientRect` in Chromium: desktop 343 boxes, 0 off the 4 dp step, 0 overflowing. Phone and
tablet 375 boxes; the only ones off the step are two families derived from the screen's width, the four `weight(1f)` cells of
the bottom navigation (103 at 412) and P4's two equal secondaries (186). Neither is a typed number. 0 overflowing, and all 19
glyph URLs answer 200. Renders: in the shared local review folder (not published).

Answered by the owner, 2026-11-06, relayed by the coordinator, and recorded in `DEC-19` and `.claude/rules/ui-components.md`:
- `Folder` is a new `CloudStreamIcons` entry (Tabler `Folder`) for Show in Finder. It is in the glyph table now; the code
  entry lands with `KMP-54`, its first user.
- Glyph-only trailing verbs on Mac and tablet rows, each naming itself in a tooltip: yes. Rule 4's ruled exception for
  list-row actions now covers them.
- Cancel is the filled default in the delete confirmation: yes, now in rule 1.

Still open, not the owner's: the new strings go through `:core:strings` with `KMP-54`, and the transfer rate in D4 waits on
`KMP-52` to report it.

## DS-20 — Board correction: 7i's "DOWN past the last row loads the next page" · **measured 2026-11-07**

**Status:** open · **Scope:** the board, not the code. `design/TV Detail Row.dc.html`, board 7i's FOCUS note.
**Found by:** `KMP-38`'s PR 2a, measuring 7i before building it.
**Numbered `DS-19` in `#181`'s first commit**, which collided with the component layer's `DS-19`
(already referenced at `decisions.md:737`). Renumbered here; IDs are now claimed through the shared
claim script, which reads every branch rather than this file.

7i's FOCUS note reads *"DOWN past the last row loads the next page of episodes."* There is no next page,
and there is nothing below the last row to take focus. Both halves are measured, not argued:

- **No pagination exists anywhere in the stack.** `DetailSource.details(id)` is a single `suspend` that
  returns the whole `TitleDetail` (`DetailContracts.kt:187`); `episodes` is one flat list; the screen
  draws all of it with `items(detail.episodes.size, …)` (`DetailScreenTv.kt:320`). No page size, cursor,
  token or `loadMore` exists on the interface — `links()` is its only `Flow` and it carries streams.
- **It cannot be faked from what is there.** `DetailSeason.episodeCount` is *derived from the same list
  it would page*: `toSeasons()` is `episodes.mapNotNull { it.season }.groupingBy { it }.eachCount()`
  (`AndroidDetailSource.kt:577-582`). A count can therefore never exceed what is already loaded, so
  nothing can even say that a further page exists.
- **DOWN at the last row does nothing, on a device.** `DetailScreenTvDeviceTest.downAtTheLastEpisodeRowStaysOnIt`
  presses DOWN three times *after* arriving on the last row and asserts focus has not moved. It passes.
  The existing forty-episode test could not show this: `pressUntil` stops at its first match, so it never
  presses past the row it was looking for.

### Second correction: the pill is drawn on a finished episode

7i draws `WATCHED` on episode 1 and `24 MIN LEFT` on episode 2, and puts the marker on **episode 1**
("Drawn on episode 1, the selected row"). So the board marks an episode the viewer has finished while
the next one is part-watched — and Resume on that page would start episode 2, not episode 1.

`#181` marks **what Play or Resume will start** instead, which on that fixture is episode 2. The
owner's call, via `PM` (2026-11-07): a pill a viewer can act on beats one that says where they have
been. `selectedEpisodeIndex`'s KDoc states the divergence rather than implying the board agrees.

**Suggested correction:** move 7i's marker to episode 2, or change episode 1's badge so the marked row
is the one Resume would start. Either keeps "the selected row" meaning one thing.

**One trap for whoever picks this up:** `DetailTvFragment.kt:194` reads
`RepoLinkGenerator(episodes, page = parts.response)`. That `page` is the extension API's `LoadResponse`
parameter name, **not** a pagination cursor. It greps as if paging existed.

**Suggested correction:** the note becomes "DOWN at the last row stays there — every episode is loaded in
one answer." If paging is actually wanted, it is a `DetailSource` contract change and belongs on the `KMP`
track, not in a board note; nothing in the current contract can express it.

PR 2a draws the rest of 7i and leaves this line alone, which is why it is written down here.

## DS-21 — Boards: Home and a title's page on desktop, phone and tablet

**Status:** drawn, PR open · boards only, no Kotlin · unblocks `KMP-55` (Home) and `KMP-58` (Detail) · `DEC-19`

The PM's call, 2026-11-07: the Mac and phone boards for Home, Search, Library and Detail, drawn from the TV boards as DS-18
was, Home and Detail first. Search and Library follow in their own task.

- `Desktop Home.dc.html` (a board file in the owner's Claude Design project), H1 to H6:
  - H1, Home at rest: the hero as a card in the page, Continue watching, and Bookmarks with status chips.
  - H2, scrolled: a provider row with Load more, and a hovered card with its context menu.
  - H3, the source panel with None.
  - H4, offline with a failed reload, the rows kept.
  - H5, no home source.
  - H6, the 800×520 minimum.

  From TV Home 3c to 3m and the built `HomeScreenDesktop`.
- `Desktop Detail.dc.html` (a board file in the owner's Claude Design project), E1 to E6:
  - E1, a series:
    - poster, title, meta and a synopsis that opens;
    - Resume with its rule and the time left;
    - Start over and Trailer outlined;
    - the watchlist toggle and More icon-only;
    - episodes on the page under season buttons.
  - E2, an episode's hover verbs with a tooltip.
  - E3, a film with the sources panel growing as links arrive.
  - E4, loading.
  - E5, couldn't open it.
  - E6, the minimum window.

  From TV Detail 7a to 7c and the built `DetailScreenDesktop`.
- `Phone Home Detail.dc.html` (a board file in the owner's Claude Design project):
  - P1 to P3: phone Home, with a source chip and sheet, rails, and offline.
  - PD1 and PD2: a phone title's page, in `ui-components.md`'s phone direction (Resume full width, Start over, the
    labelled glyph row), with a sources sheet.
  - TH1 and TD1: the tablet, with the navigation rail. Its title's page puts the art and actions in a left column and the
    episodes beside them.

Drawn at 1 px = 1 dp, with Tabler 1.119.0 glyphs as masks, never drawn by hand.

**Measured** in Chromium with `getBoundingClientRect`, on every artboard:
- Desktop Home: 302 boxes. Desktop Detail: 243 boxes. Both have 0 off the 4 dp step and 0 text overflowing its box.
- Phone and tablet: 315 boxes. The only ones off the step are the bottom navigation's `weight(1f)` cells (103 at 412),
  DS-18's recorded exemption.
- Horizontal rails run past the phone's edge on purpose; that is the peek.
- All 21 distinct glyph URLs answer 200, checked with curl.

Renders: in the shared local review folder (not published).

**Words with no key yet**, to add through an `I18N` task before `KMP-55` and `KMP-58` use them:
- "Home source" and its line, "Rows come from one extension at a time."
- "No rows; your library stays", and the empty prompt "No home source" with its line and "Choose source".
- "Couldn't reload %s. These rows are from %s ago."
- The time-left line "29 min left", which waits on `I18N-10`.

The rest reuses existing keys.

**Open for the owner:**
- Home's hero is a card inside the page, not full-bleed, on the Mac.
- A title's action row keeps its secondaries' words on the Mac. The TV-only ruled exception makes them icon-only;
  the window has the room.
- The phone's season is a chip that opens a sheet.

## DS-22 — Boards: Search and Library on desktop, phone and tablet

**Status:** drawn, PR open · boards only, no Kotlin · for `KMP-56` (Search) and `KMP-57` (Library) · `DEC-19`

This is DS-21's second half, the PM's call of 2026-11-07, drawn from the TV boards as DS-18 and DS-21 were.

- `Desktop Search.dc.html` (a board file in the owner's Claude Design project), S1 to S7. Drawn from TV Search 4a to 4d, Suggestions 8a to 8c and the built
  `SearchScreenDesktop`.
  - S1: recent searches before typing.
  - S2: results grouped by source, each group with its count.
  - S3: a partial search, with a banner that retries exactly what failed.
  - S4: the sources side panel.
  - S5: suggestions as a popover under the field.
  - S6: no results, naming the filter that narrowed the search.
  - S7: the 800×520 minimum window.
- `Desktop Library.dc.html` (a board file in the owner's Claude Design project), L1 to L7. Drawn from TV Library 5a to 5e and the built `LibraryScreenDesktop`.
  - L1: a list, with the source and its sync in the bar, filter, Sort, the lists as buttons, and a hovered card with Remove.
  - L2: a sync that failed, the cards kept and dimmed.
  - L3: an empty list.
  - L4: Sort, where an unsupported criterion stays greyed with its reason.
  - L5: the library's source.
  - L6: moving a card, using the built list picker.
  - L7: the minimum window.
- `Phone Search Library.dc.html` (a board file in the owner's Claude Design project):
  - PS1 to PS3: phone Search (recent searches, a three-column grid, the sources sheet).
  - PL1 to PL3: phone Library (chips, Sort sheet, failed sync).
  - TS1 and TL1: the tablet, with the navigation rail.

**Measured** in Chromium with `getBoundingClientRect`, on every artboard:
- Desktop Search (370 boxes) and Desktop Library (389 boxes): 0 off the 4 dp step and 0 overflowing text.
- Phone and tablet (396 boxes): off the step only in the bottom navigation's derived cells, DS-18's recorded exemption.
- All 34 glyph URLs answer 200, checked with curl.
- Poster heights are 2:3 rounded to the step (188→280, 164→248, 116→176), as DS-21's were.

Renders: in the shared local review folder (not published).

**Decisions taken here** (reported to the PM):
- Sources is a button that opens the side panel on every platform. The built screen's read-out stands only until
  the picker is built.
- Suggestions are a popover, not a page.
- Quick Search (8d, 8e) is not drawn. On a Mac, ⌘F brings the window to Search.

**Reused keys:**
- Search: `search_tv_field_placeholder`, `search_tv_recent_headline`, `search_tv_clear_all`, `search_tv_sources_label`, and
  the `search_state_no_results_*` set.
- Library: `library_source`, `library_source_note`, `library_not_connected`, `library_on_device`, `library_synced_format`.
- Shared: `sort`, `action_remove_from_bookmarks`, `continue_watching`, `type_*`.

**Words with no key yet**, for an `I18N` task before `KMP-56` and `KMP-57` use them:
- Search:
  - the partial banner ("%d sources didn't answer. %s timed out after %d s.") and "Retry %d sources";
  - the suggestion origins ("Suggested", "Recent · also suggested") and their "%d sources" count;
  - the popover's "%1$d of %2$d answered · %3$d failed" and "Return searches all";
  - the sources panel's sentence-case line. TV's `search_tv_sources_panel_meta` is in capitals.
- Library:
  - "Filter this list" and "Sort: %s";
  - "Last updated" and "Your rating", and the criteria's lines;
  - "%1$s supports %2$d of %3$d";
  - "unreachable · cached %s", "Couldn't sync %1$s. These are from %2$s ago." and "Retry sync";
  - each list's empty words ("Nothing on hold", "Titles you pause land here.", "Go to %s");
  - "Move to another list" and "%d titles".


## DS-23 — Boards: the player on the Mac, the phone and the tablet

**Status:** drawn, PR open · boards only, no Kotlin · `DEC-19` · the owner's priority, through the PM, 2026-11-08

Drawn from TV Detail 7d (the HUD) and 7e (the Source, Subtitles and Audio panels), and the Mac's built `PlayerScreen`,
with the desktop direction in `docs/plans/desktop-design.md`.

- `Desktop Player.dc.html` (a board file in the owner's Claude Design project), M1 to M15, at 1280×800 unless said:
  - M1: playing, with the HUD the pointer brought: Back and the title at the top, one glass surface at the foot with
    the transport over the selectors, as the built `Hud` has them, at most 720 wide.
  - M2: the HUD hidden after three seconds still, with a subtitle.
  - M3: hovering the timeline shows the time under the pointer; Skip Intro waits above the controls.
  - M4 to M7: the Source, Subtitles, Audio tracks and Picture size panels, from the right, 360 wide, no scrim. The
    controls recentre in the room left.
  - M8 loading, M9 buffering, M10 a source failed and the next is trying, M11 every source failed.
  - M12: offline, playing a downloaded file.
  - M13: Up next at the credits, counting down from 10 s, with Play now and Cancel.
  - M14: full screen, with the Playback menu listing every key.
  - M15: the 800×520 minimum window.
  - M16: Up next, cancelled.
- `Phone Player.dc.html` (a board file in the owner's Claude Design project):
  - LP1 to LP6, a phone held sideways (916×412): playing, a double tap seeking 10 s, Source as a bottom sheet, a
    failed source with the next trying, Skip Intro, Up next counting down.
  - P1 to P4, a phone held upright (412×916): the picture at the top in 16:9 under the status bar, then the title, the
    selectors as a row of chips that scrolls sideways, and the season's episodes with the one playing marked.
    - P1 the controls shown over the picture, P2 hidden, P3 Source as a full-width sheet, P4 Up next counting down in
      the title's place.
    - Turning the phone sideways is LP1; turning it back returns here.
  - T1 and T2, the tablet (1280×800): playing, and the sheet at 600 wide.

**Security.** No board draws an address, a header or a host. A source row is its name and its quality, as the built
`SourcePicker`'s are, and a failure is its code. Checked: the only addresses in either file are the Tabler glyph CDN and
Google Fonts.

**Measured** in Chromium with `getBoundingClientRect`, on every artboard:
- Desktop (468 boxes) and phone and tablet (268 boxes): off the 4 dp step only in the playhead, whose x is the
  position, and the drawn system pointer. Both are values, not layout.
- Upright, 412 has its middle at 206, off the step, so the board draws the transport at 204. The build centres it
  (`Alignment.CenterHorizontally`); 204 is where the board landed, not a number to copy.
- All 30 glyph URLs answer 200, checked with curl.

Renders: in the shared local review folder (not published).

**Decisions taken here** (reported to the PM):
- **Panels from the right without a scrim**, as the brief asks. The built pickers are popovers over their selector; the
  boards replace them. The picture keeps playing beside a panel, so it does not dim.
- **The hover preview is the time only.** A frame would need libVLC to decode the file a second time.
- **No Speed selector on the Mac yet.** The shared `PlayerHudState` already has `speed` and `OpenSpeed`, and TV draws
  the selector. What the Mac lacks is a rate setter in its libVLC host. The selector joins the row when that exists.
- **Up next counts down, then plays** (the owner's ruling, which replaced a no-countdown call):
  - **10 s.** The card has a two-line title and art to read, and a pointer or a thumb has to reach Cancel. Five is gone
    before the title is read; much longer is credits nobody chose.
  - The count holds while a panel is open or the pointer is on the card, and goes on when neither is.
  - Cancel, or Esc on the Mac, stops the count and keeps the card, with Play now and a close (M16). Play now does not wait.
  - A rule along the card's foot empties as it counts, with "Next episode in 8 s" in words.
  - **The user's setting wins** (the PM's call): with Android's existing `autoplay_next_settings` off, Up next is the
    card with no countdown, as M16 draws it, with Play now and a close; with it on, the 10 s countdown. The ruling
    sets how auto-play looks, not whether to override someone who turned it off.
  - **The Mac reads the same setting.** It has none yet: Android keeps it as `autoplay_next_key` (on unless turned off),
    and nothing in `:desktopApp` reads it. A Mac preference for it is part of the build.
- **Full screen is F**, as built, in the Playback menu. macOS's own ⌃⌘F can be added beside it.
- **Phones also play upright** (the owner's ruling, which replaced a sideways-only call): P1 to P4.

**Made-up names only.** The title is "Quiet Harbor" and the sources are "Source One" to "Source Four", so the
build's tests, screenshots and previews copy names that belong to no real title or site. The subtitle services named are
the app's own providers.

**Strings each board needs.** Most exist (`video_source`, `player_subtitles_settings`, `audio_tracks`,
`player_fit_*`, `loading`, `player_hud_buffering`, `source_error`, `pick_source`, `player_source_*`,
`player_load_subtitles*`, `subtitle_offset_title`, `no_subtitles`, `player_hud_offline_copy`, `player_hud_up_next`,
`next_episode`, `skip_type_format` with `skip_type_intro`, `pause`, `resume`, `seek_*_10_seconds`, `player_mute`,
`player_*_fullscreen`, `go_back`). New, as `I18N-19`, which the build waits for:
- the Playback menu's title, and Volume up and Volume down (M14);
- M11's two sentences, "Every source answered with an error." and "Choose a source below to try it again."
  (`player_sources_all_failed_detail`). Not `player_hud_failed_detail` ("Choose another source below."): with every
  source failed there is no other one, and "another" would contradict the line above it (the PM's call);
- the Source panel's count line, and each panel's second lines (decoded size, stereo). Not the kept position: the
  panel's header already says it with `player_source_resumes_at`, so the build drops the row's "keeps 42:13", which
  repeats TV 7e's row copy;
- the double tap's "10 seconds" (LP2); the old player's `go_back_30` is a bare "-30";
- Up next's "Next episode in %d s" and "Play now". Cancel is the existing `cancel`.

**Not drawn:** casting, picture in picture, and the subtitle style settings.

## DS-24 — Boards: Settings on the Mac, the phone and the tablet

**Status:** drawn, in pr #252 · boards only, no Kotlin · `DEC-19` · the PM's call, 2026-11-08 · strings are `I18N-20`

The Mac has no settings yet. The boards regroup Android's seven Settings entries (six XML screens and Extensions, about
95 rows) into ten panes that every platform shares. The Mac drops what it cannot do, and the phone keeps what only a
phone has.

**The Mac** opens a Settings window with ⌘,, not a fourth tab in the main bar. It is 760 wide at all times: a 216
sidebar and a 544 pane. It is 600 tall by default and can shrink to 480, in height only. The sidebar runs the full
height with the traffic lights in it and a search field at the top. The pane's name is the window's title, in a 52 bar.
Sheets attach under that bar, 440 wide and centred. `DS-25` draws its account sheets to this geometry.

The ten panes, in order:

| Pane | Holds | Dropped on the Mac, and why |
|---|---|---|
| General | App language, updates, run setup again, disclaimer | The four links move to the Help menu |
| Appearance | Poster size, title under poster, badges, hide quality; trailers, cast, filler episodes; the Random button, suggestions | Primary colour (`DEC-13`). Theme (`DS-01`). Layout: the Mac has one, the window. Overscan: a TV's. The clock: macOS shows one in the menu bar. Kitsu posters: Kitsu is off the Mac (see Accounts). Advanced search: the Mac always groups by source |
| Playback | Play the next episode, Skip intro prompts, seek amount, watch progress, source order; libVLC's network buffer, the disk cache, clearing it. The disk cache's file names and index are keyed by a hash, never by a stream URL: a URL reaching disk is a blocker | Preferred player (libVLC is the player), PiP (nothing builds it yet), rotation ×2, gestures ×5 and brightness (a phone's), the two TV seek amounts. Software decoding: the Mac leaves the hardware or software choice to libVLC's automatic default |
| Subtitles | The `CaptionStyle` editor with a live preview, the preferred language (the Mac's existing `SubtitlePreference`), OpenSubtitles and SubDL | Chromecast subtitles |
| Extensions | Repositories, what is installed with each one's scan state (`KMP-24`), languages, preferred media, dub and sub, adult content, auto-update, auto-install. Both automatic options are **off by default on the Mac**, and whatever they bring in passes `KMP-24`'s scan and review before it runs | Test all moves to Advanced |
| Downloads | The folder, by its name only; downloads at once; connections per download | Battery optimisation |
| Accounts and Privacy | Profiles, which open `DS-25`'s A6 (via A3/A4 when locked); Sync for the current profile: AniList, MyAnimeList, Simkl; skip choosing a profile; Lock with Touch ID, **approved by the owner (2026-11-08)**, with the profile's PIN as the fallback | Kitsu: it signs in with a password, and the Mac has no password sign-in for sync services (the owner's ruling, 2026-11-08; see `DS-25`) |
| Network | DNS over HTTPS, with no address drawn | Clone site and GitHub proxy, which send traffic through a third party (the PM's call) |
| Backup | Back up now, how often, the folder, restore. A Mac backup carries no sign-in tokens and no PINs, the rule `BUG-50` and `BUG-51` set for Android | |
| Advanced | Logs (Show in Finder only, never their contents), test all extensions, reset | |

Also dropped on the Mac: the APK installer and pre-release installs (distribution is `DEC-03`), confirm before exit
(⌘Q is the convention), and benene.

- `Desktop Settings.dc.html` (a board file in the owner's Claude Design project), at 1280×800:
  - ST1 General at rest.
  - ST2 Extensions: one repository unreachable with Retry, and one extension held by its scan with Review.
  - ST3a Subtitles: the style editor and its preview. ST3b, scrolled: preferred language and the subtitle services.
  - ST4 Accounts and Privacy.
  - ST5a a backup restore in progress. ST5b the restore failed, saying that nothing changed.
  - ST6 Downloads with the folder missing: Show in Finder disabled, Choose offered.
  - ST7 searching Settings with no results.
  - ST8 Network, with the DNS over HTTPS menu open.
  - ST9 Playback at the 760×480 minimum.
  - ST10 Advanced: the Reset sheet names what it erases and what it keeps. It erases settings, profiles and their PINs,
    the library and history, search history, and every sign-in in the credential store. Cancel is the primary and comes
    first (`ui-components.md` rule 1, as in `DS-18`).
  - ST11 Appearance.
- `Phone Settings.dc.html` (a board file in the owner's Claude Design project):
  - PS1 the Settings list: the same ten panes, each with what it holds, and search.
  - PS2 Playback on a phone, keeping gestures, PiP and rotation in the new panes.
  - PS3 Extensions offline, as a banner over lists that still work.
  - PS4 search with no results.
  - PS5 Reset as a bottom sheet, with ST10's words. On a phone, logs are shared as a file.
  - TS1 the tablet: the list (360) and the pane side by side.

Drawn at 1 px = 1 dp, with Tabler 1.119.0 glyphs as masks, never drawn by hand.

**Sign-ins are `DS-25`'s.** A service row is its name, presence only (never an account name or id, as `KMP-73` keeps
it), and one button. The words reuse `I18N-16`'s keys: `account_sync_connected` ("Connected · signed in on this
device"), `library_not_connected`, and `account_sync_remove` ("Remove from this device"); Sign in is new. Nothing after
the press is drawn here. AniList and MyAnimeList sign in through the browser and Simkl with a code. OpenSubtitles and SubDL sign in with
an email and password inside the app. That is the owner's exception, 2026-11-08, and `DS-25`'s A16 draws it.

**Measured** in headless Chrome with `getBoundingClientRect`, on every artboard:
- Mac: 796 boxes. Phone and tablet: 305 boxes. Both have 0 off the 4 dp step and 0 text overflowing its box.
- x and width are checked on structure only. A button's or a menu's width comes from its words, and so does the x of
  what follows it in a row (`dimension-scale.md`, "What the grid does not govern").
- Words centred in a 36-tall `DesktopButton` sit at 6, which is the button's own geometry.
- All 16 glyph URLs answer 200, checked with curl.

**Security.** No board draws an address, a path, a token or an account name. A repository, a backup and a download
folder are their names. Checked: the only addresses in either file are the Tabler glyph CDN and Google Fonts.

**Made-up names only:** the profiles Alex and Sam, "Example Repository", "Harbor Mirror", "Source One" and "Source Five".

Renders: in the shared local review folder (not published).

**Decisions taken here** (the PM's calls, 2026-11-08):
- **Row controls are 32 tall**, a compact `DesktopButton`, so that they centre in 48 and 64 rows. The build adds it to
  `DesktopDimens` with a KDoc that says why. On a phone and a tablet the 32 control keeps a 48 touch cell, as a chip's
  24 visual sits in its 48 cell.
- **The pop-up button's glyph is Tabler `selector`**, a new `CloudStreamIcons` meaning. The build adds it to
  `ui-components.md`'s table.
- **The panes are words only.** A glyph per pane would be ten new meanings, and the main window's tabs are words too.
- **No trailing ellipsis on buttons**, so the keys these boards reuse (`add_repository`, `account_add_profile`, `edit`,
  `reset_btn`) read the same as the new ones beside them.

- **"Each update is scanned before it runs" is a promise to the user** (ST2). The build must confirm that `KMP-24`'s
  scan runs on an automatic update, not only on first install, and add a test for it. If it does not, the line changes.

**Open:** ST10's button order against board `D3` in `design/Desktop Downloads.dc.html`.

**Follow-up:** DNS over HTTPS does not reach playback on the Mac yet, so ST8 says so in words. That is `KMP-88`.

## DS-25 — Boards: account and profiles on the Mac, the phone and the tablet

**Status:** drawn, in pr #254 · boards only, no Kotlin · `DEC-19` · the PM's task, 2026-11-08

Drawn from TV board 8d (`KMP-73`'s `AccountSelectScreenTv` and `PairingPanelTv`), the old account code
(`AccountSelectActivity`, `AccountHelper`, `AccountViewModel`, `ProfileSignIns`) and `BUG-55`'s `PinAttempts`.
The board files were not on the drawing Mac, so every number comes from the code instead:
- `CloudStreamColors`, `CloudStreamTypography`, `CloudStreamShapes` and `DesktopDimens`;
- the sidebar from `DesktopShellDefaults`.

- `Desktop Account.dc.html` (a board file in the owner's Claude Design project), A1 to A16b, at 1280×800 unless said:
  - Choosing a profile:
    - A1, the launch picker: 8d's cards at a pointer's size. It shows from two profiles, the default counted (`KMP-73`).
    - A2, a profile button at the foot of the sidebar (`KMP-77`, #247), opening a popover: the profiles, "Manage
      profiles", "Sync services".
    - A5, Manage mode.
    - A15, the 800×520 minimum.
  - Unlocking a locked profile:
    - A3t, Touch ID first: "Use Touch ID to open <profile>", with Use PIN instead. macOS draws its own prompt over the
      sheet.
    - A3, the PIN: four masked cells, and the fourth digit submits. It is the fallback after Use PIN instead or a
      failed Touch ID, and the first step on a Mac with no enrolled finger.
    - A3b, a wrong PIN, with no count of tries left.
    - A4, `BUG-55`'s wait ("Too many wrong PINs. Try again in 00:30"), with the cells off.
    - A4b, the wait at its one-hour cap.
  - Profiles:
    - A6, the editor: picture, name, the PIN row, and Delete apart at the lead.
    - A6b, adding a profile.
    - A6c, the first profile, whose PIN row says it can't be locked. Android hides the toggle; the board keeps the row
      because the first profile is the way back in when a PIN is forgotten.
    - A6d, a locked profile.
    - A7 to A7c, setting, confirming and changing a PIN. The current PIN comes first, under the same limit.
    - A8, deleting a profile. Cancel is the primary (`DEC-19`), and the words say its sign-ins leave the Mac with it.
  - Sync services, for the current profile only:
    - A9, "Sync for <profile>": Connected or Not connected, read by presence. Never an account name or an id.
    - A10, AniList and MyAnimeList through the browser, with no password field.
    - A11, Simkl with a code and a QR. A11b, the code expired.
    - A12, cancelled: nothing is shown as an error, and focus returns to the row.
    - A13, failed. A13b, offline.
    - A14, removing a sign-in from this device. Cancel is the primary.
  - Subtitle services: A16, the OpenSubtitles and SubDL sign-in. A16b, its failure.
- `Phone Account.dc.html` (a board file in the owner's Claude Design project), at 412×916:
  - PA1, the picker, two cards a row.
  - PA2, the PIN in a bottom sheet over the number keyboard. PA3, the wait.
  - PA4, the editor, full screen.
  - PA5, sync services.
  - PA6, MyAnimeList through a Custom Tab.
  - PA7, Simkl through the browser.
  - TA1, the tablet picker at 1280×800. The tablet's other flows are the phone's sheets at 600 wide.

**Rulings applied** (the owner, through the PM, 2026-11-08):
- **The Mac signs in through the browser, and the app never sees a password.** Tokens are stored as Android's are:
  never backed up, never logged. This replaces `desktop-design.md`'s "no service sign-in on desktop".
- **Simkl pairs with a code on the Mac (A11).** The person approves it in any browser, and it needs no redirect.
- **Cancel ends the wait at once (A12).** No poll keeps running, and a redirect that arrives late is ignored. `BUG-49`
  is the precedent.
- **Kitsu is not on the Mac.** It signs in only with an email and password, so it is shown nowhere, not even as a
  disabled row. The phone keeps Android's four services (PA5), because the ruling is the Mac's.
- **OpenSubtitles and SubDL are the one exception (A16).** They may sign in with an email and password inside the Mac
  app, these two only:
  - the password is masked, with Show;
  - it is never prefilled;
  - nothing is logged;
  - it is kept with the sign-ins, out of every backup.
- **Touch ID first, the PIN as the fallback** (the owner, 2026-11-08; A3t). `BUG-55`'s wait still applies to the PIN.
  `DS-24`'s Settings pane holds the switch. **An accepted trade-off** (the owner, 2026-11-08): Touch ID proves who is
  signed in to the Mac, not who owns the profile, so anyone whose finger is enrolled in that macOS account opens every
  locked profile.
- **`DS-24` hands off to these boards.** Settings lists the services and profiles; its Sign in, Edit and Add
  profile open A10, A11, A16, A6 and A6b. Edit on a locked profile goes through A3t (or A3) first. The sheets are 440 wide,
  under the window's title bar.

**Before the build** (the boards do not wait on these):
- **A10 needs the Mac to receive the redirect.** AniList's and MyAnimeList's sign-ins return to
  `cloudstreamapp://anilistlogin` and `cloudstreamapp://mallogin` (`AccountManager.APP_STRING`). Nothing in
  `:desktopApp` handles a URL scheme today; both are absent:
  - the scheme in the Mac app bundle;
  - an open-URI handler.
- **The redirect handler accepts only what this window asked for** (the reviewer and the owner). The build PR shows
  each of these with a test:
  - it acts only while a sign-in from this window is pending, and ignores any unsolicited `cloudstreamapp://` URL;
  - it checks an OAuth `state` it generated, and the `state` is random. Android's MAL request sends `state=RequestID<n>`,
    a counter, which the owner ruled not acceptable for the Mac (2026-11-08);
  - it uses PKCE where the provider supports it. MyAnimeList supports only the `plain` method, so the verifier is the
    challenge and travels in the authorize URL. So the verifier must be freshly random for every sign-in, 43 to 128
    characters from a secure RNG, and never reused. Android's `AuthAPI.generateCodeVerifier` was checked, not assumed:
    96 bytes from `SecureRandom`, base64url without padding, 128 characters. The Mac may reuse it only after the same
    check against the copy it ends up with;
  - it never logs or stores the incoming URL. Only the token taken from it reaches storage.
- **Tokens and the two subtitle credentials go in the macOS Keychain** (the owner, 2026-11-08). Reaching it from the
  JVM probably needs a library such as JNA. That is a dependency decision, so the build brings the four-point case
  (what, why, alternatives, size) before adding one. No secret is ever passed on a command line.
- **What each subtitle service keeps** (read from the Android providers):
  - **SubDL:** its login trades the password for a long-lived API key and keeps only that key and the email
    (`Subdl.kt`, `AuthToken(accessToken = apiResponse.apiKey, payload = email)`). The Mac stores the key and never the
    password.
  - **OpenSubtitles:** its token is a JWT that a code comment says lasts 24 hours (not measured), with no refresh token.
    Android re-logs in from the stored form, password included (`refreshToken` parses `token.payload`, which is
    `form.toJson()`). Re-login truly needs the password. **The owner's ruling (2026-11-08):** the Mac keeps it in the
    Keychain beside the token and renews the token automatically.
- **Every Keychain item stays on this Mac** (the reviewer of `#268`, 2026-11-08). A16's note says the password is "kept
  on this device only and never backed up" (`account_password_note`), and the sign-ins' tokens are held to the same
  promise. Each item this feature writes therefore:
  - uses a `ThisDeviceOnly` accessibility (`kSecAttrAccessibleWhenUnlockedThisDeviceOnly`, or the `AfterFirstUnlock`
    variant if renewal must run while the screen is locked);
  - sets `kSecAttrSynchronizable` to false, or iCloud Keychain syncs it to the user's other devices.

  Per Apple's documentation, the file-based login keychain ignores `kSecAttrAccessible`. The items should therefore go
  to the data protection keychain (`kSecUseDataProtectionKeychain`), which needs a signed app with a keychain access
  group. The build confirms this on a real Mac rather than trusting the documentation. **The build tests or asserts
  both attributes:** it reads each stored item back and checks its accessibility and that it is not synchronizable. A
  write path that cannot set them fails rather than falling back to a syncing item.
  An unsigned development build cannot use the data protection keychain, so the PR that builds this says how sign-in
  was tested: on a signed local build.
- **Touch ID needs a native bridge** (LocalAuthentication), under the same dependency rule as the Keychain.
- **Touch ID's glyph is not in `ui-components.md`'s table yet.** A3t draws Tabler's `Fingerprint`. A new meaning is
  added to the table before it gets a glyph, so a Touch ID row (`Fingerprint`) goes into `ui-components.md` before the
  build (the PM, 2026-11-08).
- **The Mac has no profiles, PINs or sign-ins yet.** `PinAttempts` is Android code (uptime from `SystemClock`, a
  preferences file). The Mac needs the same rule over its own clock and store.

**Security.**
- No board draws a token, a PIN, an account name or an id.
- PIN cells show dots, never digits.
- The Simkl code is the fixture `FAKE00` and the QR a checkerboard, as `KMP-73`'s screenshots have them.
- A16 is the only password field.
- All names are made up.

**Measured** in headless Chrome with `getBoundingClientRect`, on every artboard:
- 35 boards and 1,355 boxes: 0 off the 4 dp step, 0 overflowing text.
- 0 elements escaping a sheet or popover. That check was added after it caught A11's footer running out of its sheet.
- Exempt: macOS's own title bar, the drawn pointer, content centred inside a control, and text-sized widths, as
  `dimension-scale.md` allows.
- Hairlines are drawn, not laid out: the first pass had 25 boxes off the step because 1 px borders took room.
- All 13 Tabler 1.119.0 glyph URLs answer 200, checked with curl.

Renders: in the shared local review folder (not published).

**Decisions taken here** (reported to the PM):
- **Nothing sits raised on raised.** On a raised sheet or popover, an avatar or a secondary button steps up to `line`.
- **The Mac's picker has no sync list.** 8d puts one under the cards. On the Mac it moves to "Sync services…" (A9) and
  to Settings, so the launch screen is only about who is watching.
- **One meaning, one key, on every platform** (the PM, for `DS-24` and `DS-25` together):
  - "Remove from this device" and "Connected · signed in on this device" are `I18N-16`'s existing keys on the Mac too;
  - no button string ends in an ellipsis ("Sign in", "Set PIN", "Manage profiles"), so the Mac reuses the keys TV and
    Android have.

**Strings:** `I18N-21`.
