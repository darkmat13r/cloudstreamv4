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
| 11 `CloudStreamIcons` entries | Tabler only | `icon/CloudStreamIcons.kt` |

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
- **Corners follow the tokens:** square art as on `MediaCard`, and `shapes.medium` on the segmented control.
- **Below-art cards do not lift.** The boards draw no lift on them, unlike `BackdropCard`.

### Open, and not settled here

- **`ProgressRule` 2dp or 4dp** (handover §8.4). The cards use `MediaCardDefaults.ProgressRuleHeight`, 4dp, as
  asked. 3c agrees, and `Components` draws 4px, which is 2dp. The download row uses `ProgressRule`'s own 2dp, as 6c
  and 6d draw it.
- **`SegmentedControl` is 28dp tall with a ring on each option.** That is what `Components` draws, but its own lede
  asks for a 48dp minimum target. No board uses the control yet to settle it.
- **`Components` draws the selection marker `muted` and "never a white bar"**, and `SelectionMarker` defaults to
  `ink`. It is white on a selected `DownloadProgressRow`, through `ListRow`. Not changed here.
- **`LiveTv`, `Movies` and `Tv` have no user yet.** `DEC-17` took them off the rail; they were requested for filters.

`:core:designsystem` has no `abiValidation`, so it has no `checkKotlinAbi` or dump to update. Measured from
`:core:designsystem:tasks --all`; the plugin is only in `cloudstream.kmp.extension-api`.

Screenshot cases are in `core/screenshot/.../TvComponentScreenshots.kt`. They compile, and render nothing until
`DS-04` is unblocked. `TvComponentsTest` holds the bounds and states, drawn offscreen.
