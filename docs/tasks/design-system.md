# DS — design system

`:core:theme` and `:core:designsystem` adoption, and what has to exist before the seven shipping
settings screens can move onto them.

**Decided 2026-09-18:** new screens use the new design system and `:core:theme`, adopted **per
screen**. A light palette is added if required, and the light-scheme colour decisions go through
the **Claude Design project** rather than being derived in code.

---

## The gap, measured

`:core:theme` has **one fixed dark scheme**, nine colours, no accent role, no dynamic colour.
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

**Status:** blocked by `DEC-10`

The suite in `:core:screenshot` is wired correctly and renders nothing —
`com.android.compose.screenshot:0.0.1-alpha16` does not work on AGP 9.1.1 / Gradle 9.4.1.

**Consequence for this track:** `DS-01`, `DS-03` and `DS-05` are all changes to what things look
like, with **no automated check that catches what moved**. That is the single biggest risk in this
file. Say so in each of those PRs rather than implying the usual guard applies.

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
`None` alone remains, while the Random button stays, always shown; search is in the top bar only; a failed reload shows the
error page (3m); Subscribe is in the panel; and the token wins for list rows and rings. The primary button stays white at rest, as `Components` defines it. Its
closing note gives each board's focus order, states and prerequisites by file and line. 429 boxes: 0 off-grid, 0 band
violations, 0 clipped, 0 collisions, 9 rings at 4px gap and 4px stroke on all four sides, from `getBoundingClientRect` and
again from the pixels.

---
