# Icons — use the pack, never draw one

**Every icon comes from the project's icon pack. Never hand-draw an icon**, in any of the three
ways people reach for:

- a new `ic_*.xml` vector drawable pasted in as `pathData`
- a `Canvas` / `drawLine` / `drawPath` composable that approximates a glyph
- a box, a rotated rectangle, a bordered circle or a CSS pseudo-element standing in for one

An icon is a typeface, not a drawing. Drawn one at a time they drift in weight, optical size,
corner radius and alignment, and the drift is invisible until two of them sit next to each other
on a 10-foot screen.

## Where this repo already shows the cost

- **80 `ic_baseline_*.xml` drawables** in `app/src/main/res/drawable/` — Material Icons exported
  by hand, one file at a time, each a single `pathData`. Every one is a glyph that a dependency
  already contains, and each had to be found, exported, named and reviewed individually.
- `:core:designsystem` draws its own chevron: `MediaCardDefaults.SeeAllGlyphSize` and
  `SeeAllGlyphStroke` exist because a "see all" arrow was drawn rather than referenced.
- The TV design files hand-rolled roughly fourteen glyphs out of `::before` / `::after`
  rectangles and borders. That is defensible in a static mockup — it is not a licence to do it
  in Compose.

## The pack is not chosen yet

There is no icon pack in `gradle/libs.versions.toml` today, and **adding one is a dependency
decision that needs the user's approval** (see `dependency-security.md`). Until it is made:

- Do not add a new `ic_*.xml`. Reuse an existing drawable, or state in your PR that the icon is
  blocked on the pack decision.
- Do not "temporarily" draw one in Compose. Temporary glyphs are permanent; that is how the 80
  accumulated.
- If a feature is genuinely blocked, raise the pack choice as its own decision with the usual
  four points — what, why, alternatives, size — rather than folding it into a feature PR.

The realistic candidates, when the decision is taken: `androidx.compose.material:material-icons-extended`
(complete and familiar, but large and no longer actively expanded), or a Compose port of a
maintained open pack such as Lucide or Phosphor. The deciding question is which one covers
playback, download state, source status and repository management without gaps — a pack that
covers 90% and leaves you drawing the other 10% has not solved the problem this rule describes.

## Using the pack, once it exists

- Reference it through `:core:designsystem`, never the pack directly at a call site — the same
  rule that applies to colours, shapes and dimensions. A screen names the *meaning*
  (`CloudStreamIcons.Download`), not the pack's own symbol, so swapping packs is one edit.
- Size from a token: `CloudStreamDimens.ChipGlyphSize`, `IconTargetCell`. Never an inline `.dp`.
- Tint from a token. An icon is never a hardcoded colour.
- One glyph per meaning across the whole app. If play-in-a-list and play-in-the-HUD differ, that
  is a decision someone should make on purpose, in the design system, once.
- Which buttons carry a glyph, which glyph, and when a button may be icon-only: the table in
  `ui-components.md`, "Buttons" (`DEC-17`, 2026-11-05).

## Exceptions, and they are narrow

- **Brand marks** — provider logos, sync-service logos, the app icon. These are artwork, not
  icons, and they come from the brand owner.
- **A shape that is not a glyph** — a progress rule, a selection marker, a focus ring, a status
  dot. These are geometry the design system defines, and they belong in `:core:designsystem` as
  named components with tokens, not as icons.

If you think you have found a third exception, say so in the PR rather than drawing it.

## Review checklist

- Any new `ic_*.xml`? It needs a reason that survives the question "why is this not in the pack?"
- Any `Canvas`, `drawPath`, `drawLine`, or a `Box` shaped like a glyph?
- Is the icon referenced through the design system, or does the screen name the pack directly?
- Are its size and tint tokens, or literals?
