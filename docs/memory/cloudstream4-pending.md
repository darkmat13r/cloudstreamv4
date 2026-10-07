---
name: cloudstream4-pending
description: "Measured state of every CloudStream 4 design file, the open chassis decisions, and the draw queue that remains"
metadata: 
  node_type: memory
  type: project
  originSessionId: 5b61a396-8d1c-42f6-9a0a-84375830036f
  modified: 2026-09-13T03:47:04.794Z
---

## Every current file now measures clean

Strict scanner, computed boxes, ink-vs-line-box clipping, element-box focus band:

| file | boxes | off-grid | clipped | band | unbound |
|---|---|---|---|---|---|
| `TV Home` | 262 | 0 | 0 | 0 | 0 |
| `TV Search` | 407 | 0 | 0 | 0 | 0 |
| `TV Library` | 629 | 0 | 0 | 0 | 0 |
| `TV Episodes Downloads` | 521 | 0 | 0 | 0 | 0 |
| `TV Detail Player` | 448 | 0 | 0 | 0 | 0 |
| `TV Onboarding Extensions` | 311 | 0 | 0 | 0 | 0 |
| `TV Search Suggestions` | 312 | 0 | 0 | 0 | 0 |

It took three scanners to get here. The first read declared CSS and passed files holding 50
off-grid boxes. The second measured computed boxes but tested the ring against the focus band
(16 false violations), used the content box for clipping (over-reports), and guessed at intrinsic
widths with heuristics that flex blockification defeats (~90 false positives). The third is the
one whose rules are written down in [[cloudstream4-type-metrics]].

## What the re-cuts actually fixed

Nearly everything reduced to **a small box centred in a bigger one** — the tab count, the badge,
the sort-direction label, the pager, the rail header's baseline alignment, the separator dot. The
fix is never to nudge the offset; it is to make the child's box equal the parent's. Plus: a 4px
progress rule inside a card stack is 8 (a hairline is exempt in its own axis, not in where its
edges land); Manrope 20/24 clips and is 20/32; and a rotated box can never land on the grid, so
the rotation goes on a pseudo-element.

## Owed to `:core:theme` — DONE

`CloudStreamTypography.caption` was `10.sp / 12.sp` = Manrope 20/24, which clips (ink 26.0).
`TypeScale.CaptionLineHeight` was shared by `caption` and `captionMono` so they could not diverge.
Split into `CaptionLineHeight` 16.sp (sans, = 20/32) and `CaptionMonoLineHeight` 12.sp (mono,
= 20/24); KDoc now cites the measured table instead of the wrong "~1.3x" rule. Compiles clean
(`:core:theme:compileKotlinJvm`). Every other rung in the ramp verified against measured ink.

## THE OPEN CHASSIS DIVERGENCES — decide, do not resolve silently

**1. Nav rail cell gap.** Three spellings across the drawn files:

| files | `.railc` gap | `.lgm` margin | cells land at |
|---|---|---|---|
| Search, Library, Home, Onboarding | 8 | `0 24px 32px` | 144, 248, 352, 456, 560 |
| Episodes Downloads | 24 | `0 24px 32px` | 160, 280, 400, 520, 640 |
| Detail Player | 24 | `0 24px 0` | 128, 248, 368, 488, 608 |

All on the 8px step, so none is a defect — they are different chassis. The argument for 24 is
stronger: **nav cells are focusables and the >=24px gap minimum has no exemption for them**; an
8px gap is the only pair of focusables in the drawn set closer than 24. Recommendation: adopt
Detail Player's spelling everywhere; cost is a re-cut of four files.

**2. Rail header alignment.** Two spellings, both measuring clean:
- `align-items: center` with **both children on the 56 line box** — Home, Search. This is what
  Compose's `CenterVertically` does, so it is the one that survives implementation unchanged.
- `align-items: flex-end` with the count on its natural 32 box — Detail Player, Episodes.

Prefer the matched-line-box spelling for exactly that reason. (`align-items: baseline` is banned
in both: it cannot land on the step, and Compose's `Alignment.Baseline` has the same defect.)

**3. Brand mark colour.** `--acc` in Search/Library, `--ink` in Home/Detail. `--ink` is correct:
live is playback state, and the rail was spending the board's one live before any content did.

**4. Chip count line box.** 20/24 in Search/Library/Detail, 24/32 in Episodes/Onboarding.
**32 is correct** — a 24 box centred in a 48 chip lands at +12, and Manrope 20/24 also clips.

Still open from earlier: the **10sp caption** (below Android TV's 12sp minimum — raising it
reflows every artboard), the **icon pack** choice, and six duplications inside
`:core:designsystem` (two progress rules with different geometry, two text buttons).

`TV Screens.dc.html` still contains the banned **320x180** rung. Re-cut or delete as superseded.

## The contrast defect — FIXED everywhere

`--mut` was `#686868`: **3.77:1 on `--bg`, 3.43 on `--surf`, 3.17 on `--raised`**, all below the
WCAG AA 4.5:1 floor for normal-size text — and the role is always 20–24 artboard px (10–12sp), so
the 3:1 large-text allowance never applied. It carried the secondary line of every list row, chrome
button, rail count and mono caption in all eight files.

Now **`#858585`**: 5.69 / 5.18 / 4.78. It is the smallest value clearing the floor on all three
grounds — `#808080` misses on `--raised` by 0.03 — and at 5.69 against ink2's 13.08 and ink's 21 the
three-step hierarchy still reads as three steps. Applied to all seven design files, to
`Design System v2` (now §08, the full nine-token audit), and to `CloudStreamPalette.Muted` in
`:core:theme`, whose KDoc carries the measured ratios. Compiles clean.

Two tokens are deliberately left below the line and are named as such: **`live` #CD3D4B at 4.36:1**
(graphics only — the 4px progress rule and the playing glyph, never text) and **`line` #242A31 at
1.45:1** (a hairline; where a boundary is the only thing distinguishing a control, that control also
carries a `raised` fill). Both become defects the moment someone sets a sentence in them.

**This is the one defect class every scanner in the project was blind to.** Seven files measured
0 off-grid, 0 clipped and 0 band violations while setting their secondary text below the legibility
minimum, because a colour moves no boxes. Geometry verification cannot find it; only a contrast
audit can.

## Draw queue

1. ~~Search suggestions, no-results and Quick Search~~ — **DRAWN** as
   `TV Search Suggestions.dc.html`, five artboards: suggestions-while-typing (partial),
   suggestions-nothing-matches, no-results, Quick Search answering, Quick Search empty.
   **The memory note that called this spec "solved and verified" was wrong** — it failed adversarial
   verification on all three screens with **28 violations** (`tasks/w1qls5rx7.output`), so it was
   re-solved, not transcribed. The ones worth remembering:
   - A state's action floated in its own band with 488px of void above it and **no other focusable
     in the whole region**; three of six chips had no geometric DOWN target. It now rides the chip
     row, which also fixed the separate hole where the chip row stopped at x1416 while the Sources
     button spans x1440–1864, leaving DOWN from Sources with nothing to land on.
   - The status line said "9 of 12 answered · 3 failed" (so nothing outstanding) while three rows
     said "checking". **Counts and status have to close arithmetically.**
   - Two columns of suggestions caused the un-deduped `dune` / `Dune` pair the prerequisites forbid.
     One ranked column of six.
   - The error state's only action retried providers when the *suggestion service* was what failed,
     and was hidden precisely in that case. One slot, label bound to what failed.
   - **A panel is its own coordinate space.** Panel children written at the artboard x (1392)
     rendered at x2752 — 832px off-screen — because `.panel` is their containing block. Only the
     focus-band check could catch this: every box was still the right size and still on the step.
2. ~~Cast on the detail page~~ — **DRAWN** 2026-09-13 in `TV Detail Player` (7a, 7b, new 7a-ii scrolled
   board). The redesign had **dropped a feature the shipping TV app already renders** (cast rail,
   `ResultFragmentTv.kt:929-936`, on by default via `show_cast_in_details_key`) — before drawing any
   screen, check the current screen's features, not just the data model. Cast = 160 circle avatar
   (not a CardSize rung) + name 24/32 + role mono 20/24, block 240 on the 216 pitch at y728; More like
   this moved to 7a-ii. Code facts: on AniList `actor` IS the character and the performer is
   `voiceActor`; adaptor shows ActorRole before roleString; image-vs-text is decided by the first
   actor only (`ResultViewModel2.kt:235`); no actor id, so a cast item has no destination. Full cast
   panel is specified, not drawn.
3. **Mobile and desktop** — not started. Every drawn file is TV.

Part of [[cloudstream4-redesign]]. Verification procedure: [[cloudstream4-design-state]].
