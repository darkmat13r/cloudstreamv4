---
paths:
  - "core/designsystem/**/*.kt"
  - "core/theme/**/*.kt"
  - "feature/**/*.kt"
---
# UI Component Rules

## Use the design system, never raw Material

`:core:designsystem` exists so that a screen names a *meaning* and the system decides how it
looks. Reaching past it re-decides a design question that was already settled, one call site at a
time.

| Need | Use | Not |
|---|---|---|
| Primary action | `PrimaryTextButton` | `Button` |
| Secondary action | `GhostTextButton` | `OutlinedButton`, `TextButton` |
| Icon-only target: a toggle, overflow or search | `IconOnlyToggle`, `IconOnlyButton` | a bare `IconButton` |
| The chrome's Back cell, player transport controls | `IconCell` | a bare `IconButton` |
| Filter / tab | `Chip`, `ChipRow` | `FilterChip` |
| A row in a list | `ListRow` + `ListRowState` | a hand-built `Row` |
| A poster or backdrop | `PosterCard`, `BackdropCard` | `Card`, `Surface` |
| A rail's overflow | `SeeAllCard` | a header button |
| Full-surface state | `StateView` + `StatePrompt` | an ad-hoc `Column` |
| Partial / offline | `FailureBanner` | a `Toast` |
| Loading | `LoadingSkeleton` | a bare `CircularProgressIndicator` |
| Rail heading | `RailHeader` | a styled `Text` |
| Any focusable on TV | `Modifier.tvFocusRing(...)` | `Modifier.border(...)` |

There is **no top app bar**. TV screens have a chrome row at the top of the content band — see
`CloudStreamDimens.ChromeRowTop` / `ChromeRowBottom` — and a nav rail at the left. A
`CenterAlignedTopAppBar` on a 10-foot screen is a phone habit.

There is **no `Surface`**. Backgrounds come from `CloudStreamTheme.colors.bg` / `surface` /
`raised`, which are three named elevations, not a shadow system.

Dialogs and bottom sheets: **TV never uses a bottom sheet**. Use `SidePanel` — 280 dp from the
right edge, content behind scrimmed but still mounted, focus trapped, BACK returning focus to the
control that opened it.

**Every `SidePanelHost` caller on Android also registers a platform BACK callback**, enabled while
the panel is open, that closes it (`DetailTvFragment.wirePanelBack`, `LibraryTvFragment`).
`SidePanelHost` only sees BACK as a key event; without the callback the window's back dispatcher
takes it and BACK leaves the screen with the panel open (`KMP-49`).

**Every `SidePanelHost` caller also hands the control that opened the panel in as `opener`**,
unless that control is a direct child of the content: the host's own restore reaches only a direct
child, and anything in a `Row` or a lazy list is not one (`BUG-36`). The one exception is a screen
that already sends focus back to its last stop itself, as Home's `homeStopsGroup` does: handing the
opener in as well fights that, measured.

## Tokens, never literals

- Dimensions: `CloudStreamDimens.*` — named by role (`ListRowHeight`, `ChipFocusCell`,
  `SidePanelWidth`), not by t-shirt size. Never an inline `.dp`. See `dimension-scale.md`.
- Colours: `CloudStreamTheme.colors.*` — nine roles, no others. Never `Color(0xFF…)`.
  `live` is playback state only; it is never a button.
- Shapes: `CloudStreamTheme.shapes.*`. Never `RoundedCornerShape(…)` at a call site.
- Type: `CloudStreamTheme.typography.*`. Never a raw `.sp`.
- Card sizes: `CloudStreamCardSizes.*`. `CardSize` has an `internal` constructor precisely so a
  size off the media ladder cannot be expressed.

Icons: see `icons.md`. The pack is Tabler, through `CloudStreamIcons` (`DEC-11`); do not add an
`ic_*.xml` and do not draw one.

## Buttons: one primary, glyphs on common actions, icon-only only for toggles and overflow

The owner, 2026-11-05 (`DEC-17`). The basis is Android TV's button guidance: the label carries the
meaning, icon buttons are for toggles and overflow, and a screen has one primary action, first.

1. **Exactly one primary, first.** `PrimaryTextButton`, with a glyph when its action has one in the
   table below. Play and Resume are the primary on a title. **In a destructive confirmation the
   primary is Cancel** (the owner, 2026-11-06, `DEC-19`; board D3 in `design/Desktop Downloads.dc.html`):
   the safe choice is the filled default, and the destructive one is a secondary named for what it
   does (*Delete*). Return and Escape both land on Cancel.
2. **Secondary common actions are outlined, with a glyph and a label.** `GhostTextButton(icon = …)`.
3. **A button whose meaning no glyph conveys stays text-only.** A glyph that has to be explained is
   worse than none. The ones decided so far are listed under the table.
4. **Icon-only is allowed only for toggles (bookmark, watchlist, favourite) and for overflow or
   search**, and it shows its label when focused and carries it as a content description:
   `IconOnlyToggle`, `IconOnlyButton`. **Colour says focus, shape says state** (the owner,
   2026-11-06, `DS-17`): an icon-only glyph is `muted` at rest and `ink` on focus, so a toggle shows
   on or off by changing glyph, never by colour. Tabler 1.1.1 has no filled bookmark or heart, so a
   toggle swaps to a different glyph: the watchlist is `Add` (Plus) off and `Done` (Check) on, the
   same check that marks a chosen subtitle (one glyph, one meaning: "this is on"). A toggle whose
   pair is named draws no `raised` fill, so state is said once, by shape. A toggle with no pair yet
   keeps today's `raised` fill for on until its pair is named here.
   **Three named exceptions**, which are standard TV conventions: the chrome's Back cell, the
   player's transport controls (board 7d) and the nav rail are icon-only without showing a label on
   focus. Each still carries a content description, and the rail shows its labels when expanded.
   **Three ruled exceptions**, which are not conventions but the owner's calls (2026-11-06, `DEC-17`).
   All three show each label on focus, as rule 4's toggles do:
   - **The title detail action row.** After the primary Play or Resume, its secondaries (Start over,
     Trailer, Download, Mark watched) are icon-only cells. Download and Mark watched appear for films
     only; a series downloads and marks from its episode list.
   - **Trailing actions on a list row** (board 6d's Play, state verb and Delete beside each episode)
     are `IconOnlyButton`s, each naming itself under its cell.
     **On the Mac and the tablet too** (the owner, 2026-11-06, `DEC-19`; boards D1, D4 and T1 in
     `design/Desktop Downloads.dc.html` and `design/Phone Downloads.dc.html`). The label moves into a
     tooltip, because neither has focus to show it under: on the Mac a row's verbs appear on hover in
     24 dp cells (`DesktopDimens.FieldActionSize`) holding a 16 dp glyph, and the tooltip shows on
     hover; on the tablet they are 48 dp cells (`IconTargetCell`) holding a 24 dp glyph, and the
     tooltip shows on long-press. Each carries its label as a content description. On the Mac the
     same verbs are also named in words in the context menu and the menu bar. A phone row has no
     trailing verbs: it swipes and opens a sheet (boards P2 and P5).
   - **Home top bar: Reload, Random** (`TV Home Extras` 3j). Icon-only cells beside the source control
     and Search.
   Anywhere else on TV, a glyph never replaces a label.
5. **Targets keep the three settled classes** (`screen-implementation-handover.md` §5,
   `CloudStreamDimens`): an icon in a 48 dp cell (`IconTargetCell`), so `IconOnlyButton` is one; a
   text button 32 dp tall and at least 120 dp wide (`TextButtonHeight`, `TextButtonMinWidth`); a
   chip with a 24 dp visual in a 48 dp cell. A glyph changes none of them.

**One glyph per meaning** (`icons.md`), so a meaning maps to exactly one entry:

| Action, as labelled | `CloudStreamIcons` | Tabler |
|---|---|---|
| Play, Resume (the primary) | `Play` | `PlayerPlay` |
| Start over | `StartOver` | `Rotate` |
| Trailer, Watch trailer | `Trailer` | `Movie` |
| Download | `Download` | `Download` |
| Mark watched (the detail row, films) | `MarkWatched` | `EyeCheck` |
| Retry, Try again, Retry failed, Retry N sources, Retry sync, Reload (Home's chrome, `TV Home Extras` 3j) | `Retry` | `Refresh` |
| Search, Search all sources, Search everything | `Search` | `Search` |
| Settings, Open network settings | `Settings` | `Settings` |
| Add, Add repository | `Add` | `Plus` |
| Delete, Delete all | `Delete` | `Trash` |
| Play next | `NextEpisode` | `PlayerSkipForward` |
| Episodes | `Episodes` | `List` |
| Browse library | `Library` | `Book` |
| Browse home | `Home` | `Home` |
| Back: the chrome's icon cell (`IconCell`), an exception to rule 4; a state's "Go back" action is a text button with this glyph | `Back` | `ArrowLeft` |
| Clear (a filter) | `Clear` | `CircleX` |
| Extensions, Repositories | `Repository` | `Database` |
| Manage profiles | `Profile` | `User` |
| Sources | `Source` | `Server` |
| Random (Home's chrome: opens one loaded title, `TV Home Extras` 3j) | `Random` | `Dice` |
| Share | `Share` | `Share` |
| Open in browser | `OpenExternal` | `ExternalLink` |
| Show in Finder (desktop: a download's file on disk; `DEC-19`, first used by `KMP-54`) | `Folder` | `Folder` |
| Play from link | `Link` | `Link` |
| Stop and go back, Cancel | `Close` | `X` |
| Bookmark, Watchlist, Library (add to library): an icon-only toggle, or outlined with its label | `Bookmark` | `Bookmark` |
| The detail row's watchlist toggle: off, then on (rule 4, colour says focus, shape says state) | `Add`, then `Done` | `Plus`, then `Check` |
| Favourite (icon-only toggle) | `Favorite` | `Heart` |
| More (icon-only overflow) | `More` | `Dots` |
| A pop-up button's mark: pressing it lists the choices (the Mac's Settings, `DS-24`; first used by `KMP-90`) | `Selector` | `Selector` |
| Use Touch ID: the 48 dp illustration in the Mac's Touch ID sheet (`DS-25` A3t); never on "Use PIN instead", which stays text-only | `TouchId` | `Fingerprint` |
| More info (opens a title's page from Home's hero, which does not play: rule 1's Play or Resume when it can, `KMP-41`) | `Info` | `InfoCircle` |

**Text-only, and why:** *Skip for now* and *Go to Watching* (navigation with no object a glyph
names); *Season N* (a choice, not an action); *Queue*, *Free up space* (no common glyph, and
`Download` or `Delete` would claim the wrong action); *Update* and *Install* (the pack's candidates
are `Download` and `Refresh`, which already mean other things); *Skip intro* (`SeekForward` and
`NextEpisode` sit beside it in the HUD and mean other things). A new action not in the table is
added here, by name, before it gets a glyph.

**Geometry and colour, from tokens:**

| | Value | Source |
|---|---|---|
| Glyph | 16 dp (32 px) | `CloudStreamIconSize.Small`, which is `CloudStreamDimens.ChipGlyphSize` |
| Glyph to label | 8 dp (16 px) | `TextButtonDefaults.IconGap` |
| Glyph tint | the label's colour: `bg` (black) on the white primary, `ink` on a ghost, `muted` disabled | the button's own skin; never a parameter |
| Width | content-sized; the 120 dp (240 px) minimum still holds | `CloudStreamDimens.TextButtonMinWidth` |
| Icon-only cell, tile | 48 dp cell, 32 dp tile, ring on the tile | `CloudStreamDimens.IconTargetCell`, `IconOnlyButtonDefaults.TileSize` |
| Icon-only label | `typography.label`, `ink`, under the cell, focused only, drawn not laid out | leave `IconOnlyButtonDefaults.LabelReserve` (16 dp) unclipped below |

`StateAction(icon = …)` gives `StateView` and `FailureBanner`'s button the same glyph.

**Not TV — the planned direction for tablet and phone, no boards yet.** Phone stacks a full-width
Play or Resume with the time left under it, then Start over, then a row of glyphs with their labels
underneath (Watchlist, Trailer, Download, Share, More). Tablet keeps that order in one row, with
labels always visible.

## Strings

- No hardcoded user-facing text. In `:app` that is `getString(R.string.x)`; in a module that
  cannot see `R`, it is the `UiText` bridge (`UiText.Resource(id, args)`) that the migration plan
  describes — `Int` is not an Android type, so a ViewModel carrying one still compiles in
  `commonMain`.
- Every string's source is `core/strings/src/commonMain/composeResources/values*/strings.xml`,
  **62 locale directories**. `app/src/main/res/values*/strings.xml` is generated from it: never
  hand-edit it; regenerate with `./gradlew :core:strings:writeAndroidStrings`. Read
  `docs/HANDOVER.md` before touching a string.
- Weblate is retired (`I18N-07`; see the top of `docs/tasks/i18n.md`). A new English string is
  machine-translated into every locale in the same PR and checked by script: every key present,
  placeholders identical to English, and each locale's own plural categories. The task entry in
  `docs/tasks/i18n.md` records that, and the key goes into its "Waiting on a native speaker" table.
- One meaning, one key. Reuse an existing key only when the meaning is the same; a reworded
  string is a new key.
- A plural's forms agree with the number; they never change wording. In many locales `one`
  also covers 21, 31… (`settings_extensions_all`, `I18N-20`). A form may drop its `%d` only
  where the language says the number itself (the Arabic dual) and the strings guards accept it.

## Previews and screenshots

Add `@Preview` for any component with more than one state. Add a screenshot case in
`:core:screenshot` for the states that are load-bearing — see `screenshot-testing.md` for which
ones earn a case and which are permutation filler.
