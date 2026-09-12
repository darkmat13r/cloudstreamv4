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
| Icon-only target | `IconCell` | a bare `IconButton` |
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

## Tokens, never literals

- Dimensions: `CloudStreamDimens.*` — named by role (`ListRowHeight`, `ChipFocusCell`,
  `SidePanelWidth`), not by t-shirt size. Never an inline `.dp`. See `dimension-scale.md`.
- Colours: `CloudStreamTheme.colors.*` — nine roles, no others. Never `Color(0xFF…)`.
  `live` is playback state only; it is never a button.
- Shapes: `CloudStreamTheme.shapes.*`. Never `RoundedCornerShape(…)` at a call site.
- Type: `CloudStreamTheme.typography.*`. Never a raw `.sp`.
- Card sizes: `CloudStreamCardSizes.*`. `CardSize` has an `internal` constructor precisely so a
  size off the media ladder cannot be expressed.

Icons: see `icons.md`. No pack is chosen yet, so do not add an `ic_*.xml` and do not draw one.

## Strings

- No hardcoded user-facing text. In `:app` that is `getString(R.string.x)`; in a module that
  cannot see `R`, it is the `UiText` bridge (`UiText.Resource(id, args)`) that the migration plan
  describes — `Int` is not an Android type, so a ViewModel carrying one still compiles in
  `commonMain`.
- Base strings live in `app/src/main/res/values/strings.xml`, with **62 locale directories**
  managed by Weblate.
- **Do not add strings to `composeResources`**, and do not migrate `res/values*` into it.
  `shared/readme.md` asks for that to wait until Weblate is repointed, and the plan defers it to
  the last phase. Adding one string there splits the translation pipeline in two.
- Never hand-translate. Add the English string; Weblate handles the rest.

## Previews and screenshots

Add `@Preview` for any component with more than one state. Add a screenshot case in
`:core:screenshot` for the states that are load-bearing — see `screenshot-testing.md` for which
ones earn a case and which are permutation filler.
