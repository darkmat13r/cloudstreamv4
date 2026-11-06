# TV detail board spec — 7a, 7b, 7a-ii

Read-only measurement of `design/TV Detail Player.dc.html`, boards **7a · TV show detail**,
**7b · Movie detail** and **7a-ii · TV show detail — scrolled**. Boards 7c/7d/7e are out of scope and
appear here only where a shared CSS class forced a read.

Everything below was read from the CSS rules and the inline `style` attributes, plus the `renderVals()`
data script at the end of the file (lines 783–953) which is where the cast and poster `left`/`top`
values actually come from. Nothing is estimated from the prose.

**Conversion:** artboard is 1920×1080; `artboard px / 2 = dp`. Every px value is halved. A halved value
must be a multiple of 4 dp (`.claude/rules/dimension-scale.md`); stroke *weights* and the focus-ring
offset are exempt **in their own axis only**.

---

## 0. Headline findings

| | |
|---|---|
| Off-grid layout values on these three boards | **1 distinct value**, on 4 drawn element classes — `border-radius: 12px → 6 dp` |
| Off-step stroke/offset values (exempt, tokens exist) | 3 — `1px` hairline, `4px` ring stroke, `4px` ring offset |
| Off-step **type** values | 1 — the `20px → 10 sp` caption floor (published in the ramp; not a layout dimension) |
| Every inline `left/top/width/height` on 7a, 7b, 7a-ii | divisible by 8 px, so **all positions and boxes land on the 4 dp step** |
| `--live` / `--acc` spends on 7a, 7b, 7a-ii | **zero**, verified against the CSS — see §7 |
| Entry focus, 7a and 7b | **Resume** — the existing `DetailScreenTv` KDoc is **correct** |
| Focusables, 7a and 7b | 21 each, stated by the board and confirmed by counting |

### The exhaustive off-step scan

Script over every CSS rule these boards use plus every inline style in the 7a/7b/7a-ii markup:

| px | ÷2 | verdict |
|---|---|---|
| 1 | 0.5 | hairline **weight** — exempt. **Do not halve it.** Use `CloudStreamDimens.Hairline` = 1 dp. |
| 2 | 1 | not a dimension: the `.art::after` texture gradient stop, and `.crs`'s radius (7c only) |
| 4 | 2 | ring stroke, ring offset, `.bbtn.ghost`'s inset ring — all weights/offsets. Tokens: `FocusRingStroke` 2 dp, `FocusRingOffset` 2 dp. |
| **12** | **6** | **OFF-GRID — `border-radius`.** See below. |
| 20 | 10 | font size only (`.cap`, `.crl`, `.cy`). The ramp publishes `caption` = 10 dp as the floor. Not a layout dimension. |
| 28 | 14 | font size only (`.plot`). The ramp publishes `body` = 14 dp. |
| 8, 16, 24, 32, 40, 48, 56, 64, 80, 88, 96, 152, 160, 176, 192, 216, 240, 1080, 1920 | 4, 8, 12, 16, 20, 24, 28, 32, 40, 44, 48, 76, 80, 88, 96, 108, 120, 540, 960 | all on the step |

### The one off-grid value, loudly

> **`border-radius: 12px` → `6 dp`, which is NOT a multiple of 4.**
>
> - **Raw px:** 12
> - **Halved:** 6 dp
> - **Nearest 4 dp that preserves the intent:** **8 dp** (`CloudStreamTheme.shapes.medium`). 4 dp
>   (`shapes.small`) reads as a near-square corner at 3 m and loses the intent; 8 dp keeps it.
> - **Where it occurs on these boards:** `.lgm` (rail brand mark), `.nic` (rail nav cell), `.ibx`
>   (chrome icon cell), `.card` (rail cards, incl. `.seeall`). Also on `.btncell.foc` and
>   `.chipcell.foc`, neither of which is drawn on 7a/7b/7a-ii.
> - **Note the scale is already diverged:** `CloudStreamShapes` is `4 / 8 / 12 / 16 dp`. Those are
>   the artboard's **px** values 4/8/12/16 transcribed, not halved. Halving the artboard's radii
>   gives 2/4/6/8 dp. So the radius axis of the design system does **not** obey px/2 today, and
>   `shapes.medium` (8 dp) is what the existing `TextButton` and `IconCell` already draw. **An
>   implementer should use `shapes.medium` for the 12px-radius elements and `shapes.small` for the
>   8px-radius ones, and raise the inconsistency rather than inventing `6.dp`.**

### Which late corrections this file has, and which it still owes

| Correction | Applied in this file? | Evidence |
|---|---|---|
| `.railc` cell gap 8 → 24 px | **Applied** | `.railc { … gap:24px … }`, with the comment block above it. The brand mark's bottom margin is now 0 (`.lgm { margin:0 24px 0 }`) to pay for it; cells land at y128/248/368/488/608. |
| `.rhd` `baseline` → `flex-end` | **Applied** | `.rhd { display:flex; align-items:flex-end; … }`, with the comment "flex-end, not baseline: baseline put the 24/32 count at y659 inside the 56 header box." |
| `.chipn` 20/24 → 24/32 | **Still owed** | `.chipn { font:500 20px/24px "JetBrains Mono" … }` is unchanged. **It does not affect 7a/7b/7a-ii** — no chip is drawn on any of the three boards. The correction is asserted in `TV Onboarding Extensions.dc.html` (its closing note), not in this file's notes, and `docs/plans/tv-screen-audit.md` §3 ("Owed on the boards") records it as outstanding. |
| `.lgm` `--acc` → `--ink` | **Applied** | `.lgm { … background:var(--ink) … }` |
| `--mut` `#686868` → `#858585` | **Applied** | `.tv { … --mut:#858585 … }`; matches `CloudStreamColors.muted`. |

---

## 1. Shared chassis (all three boards)

`.tv` is the artboard root: `1920×1080` → **960×540 dp**, `overflow:hidden`, background `--bg`.
All children are `position:absolute` (`.abs`), so document order **is** paint order; nothing on these
three boards sets `z-index` except the focused poster's title overlay on 7a-ii (`z-index:2`).

### Chassis tokens, as the CSS names them

The CSS variable names differ from the design-system names. Mapping, with the values on `.tv`:

| CSS var | DS / `CloudStreamColors` name | Value | Contrast on bg |
|---|---|---|---|
| `--bg` | `bg` | `#000000` | — |
| `--surf` | `surface` | `#0A1016` | — |
| `--raised` | `raised` | `#15191E` | — |
| `--line` | `line` | `#242A31` | 1.45:1 — hairline only, never text |
| `--ink` | `ink` / `focus` | `#FFFFFF` | 21:1 |
| `--ink2` | `ink2` | `#CCCCCC` | 13.08:1 |
| `--mut` | `muted` | `#858585` | 5.69 / 5.18 / 4.78 on bg / surface / raised |
| `--acc` | **`live`** | `#CD3D4B` | 4.36:1 — graphics only, never text |

All eight match `cloudStreamDarkColors()` exactly. **`--acc` is the CSS name for what the design
system and the prose call `live`.**

### The band

Focus band `x 56..1864`, `y 56..1024` px → **x 28..932 dp, y 28..512 dp**.
Tokens: `FocusBandInset` 28 dp, `ContentEndX` 932 dp. The 8 px (4 dp) ring bleed lives in the 48..56
reserve; `FocusRingBleed` is 4 dp.

### Nav rail — `.railc`

| | px | dp | token |
|---|---|---|---|
| rail box | `0,0,152,1080` | `0,0,76,540` | `NavRailWidth` 76 |
| padding | `56 0 0 56` | `28 0 0 28` | `NavRailIconInset` 28 |
| flex gap | `24` | `12` | `MinFocusGap` 12 |
| `.lgm` brand mark | `80,56,48,48`, r12 | `40,28,24,24`, r**6** → use 8 | `NavRailDefaults.LogoSize` 24 ✓ , `LogoInset` 12 ✓ |
| `.nic` ×5 | `56, y128/248/368/488/608, 96×96`, r12 | `28, y64/124/184/244/304, 48×48`, r**6** → use 8 | `NavRailIconCell` 48 |
| `.nic .gl` glyph | `32×32`, r8 | `16×16`, r4 | `IconCellDefaults.GlyphSize` 16 ✓ |
| rail right edge | `inset -1px 0 0 var(--line)` | hairline 1 dp | `Hairline` |

Cell pitch is 120 px = **60 dp**. Cell 1 is `y128..224` px (`64..112` dp), which overlaps the chrome
row's `y56..152` px (`28..76` dp) by 24 px / 12 dp — that overlap is deliberate, so LEFT from the
chrome row has a target. No cell carries `.cur` on a detail page ("none marked current").

> **Trip hazard.** `NavRailDefaults` in `:core:designsystem` is still the **pre-correction** chassis:
> `ItemGap = 4.dp` (8 px) and `LogoGap = 16.dp` (32 px below the mark). These boards use a 12 dp gap
> and a 0 bottom margin. Using `NavRail` as-is puts the rail's cells at the wrong y on every one of
> these three boards. This is the `.railc` fork the closing notes say is "owed back to TV Search and
> TV Library".

### Chrome row — `y56..152` px, **`y28..76` dp**, height 96 px / **48 dp** (`ChromeRowHeight`)

| slot x (px) | x (dp) | occupant | focusable |
|---|---|---|---|
| 200 | 100 | `.ibx` back | yes |
| 320 (not a 96 slot) | 160 | `.meta` provider label, `600×32` px → `300×16` dp, `top:88` → `44` dp | no |
| **1528** | **764** | **FREE 96×96 px / 48×48 dp slot** | — |
| 1648 | 824 | `.ibx` bookmark | yes |
| 1768 | 884 | `.ibx` favourite | yes |

**The free slot at x1528 is confirmed.** `1528 + 96 = 1624`, `1624 + 24 = 1648` — exactly the 24 px
(12 dp) minimum gap to the bookmark cell, and the row still ends at `1768 + 96 = 1864`, the band's
right edge. In dp: **x 764, y 28, 48×48**. The closing notes reserve it for Subscribe, which is not
drawn because it is emulator-only in the tree today (`ResultFragmentTv.kt:629`).

The provider label's vertical centring: line box 32 px inside a 96 px row, `top:88`, row top 56 →
`88 + 16 = 104` = the row's centre. Correct.

`.ibx` internals: `96×96` cell (r12 → use `shapes.medium`), containing a `.ivis` `48×48` r8 plate on
`--raised` with a 1 px `--line` inset, containing a `.gl2` `32×32` r8 `--mut` glyph.
In dp: `48×48` cell → `24×24` plate (r4) → `16×16` glyph (r4). Matches `IconCell` (48 dp cell,
16 dp glyph) except that `IconCell` has no `--raised` plate between the cell and the glyph.

---

## 2. Board 7a · TV show detail

### 2.1 Every element, in z-order (= document order), then top-to-bottom

| # | class | px box (x, y, w, h) | dp box (x, y, w, h) | notes |
|---|---|---|---|---|
| 1 | `.abs .art` (hero) | 0, 0, 1920, 600 | 0, 0, 960, 300 | `filter:brightness(1.45) saturate(1.1)` — mockup artwork stand-in |
| 2 | `.abs .heroscrim` | 0, 0, 1920, 600 | 0, 0, 960, 300 | two gradients, §2.6 |
| 3 | `.abs .railc` | 0, 0, 152, 1080 | 0, 0, 76, 540 | §1 |
| 3a | `.lgm` | 80, 56, 48, 48 | 40, 28, 24, 24 | r 6 dp → use 8 |
| 3b–3f | `.nic` ×5 | 56, 128/248/368/488/608, 96, 96 | 28, 64/124/184/244/304, 48, 48 | r 6 dp → use 8 |
| 4 | `.abs .ibx` back | 200, 56, 96, 96 | 100, 28, 48, 48 | |
| 5 | `.abs .meta` provider | 320, 88, 600, 32 | 160, 44, 300, 16 | inline `color:#858585` = `--mut` |
| 6 | `.abs .ibx` bookmark | 1648, 56, 96, 96 | 824, 28, 48, 48 | |
| 7 | `.abs .ibx` favourite | 1768, 56, 96, 96 | 884, 28, 48, 48 | |
| 8 | `.abs .h1` title | 200, 176, 1200, 88 | 100, 88, 600, 44 | 1 line, nowrap, ellipsis |
| 9 | `.abs .meta` | 200, 272, 1200, 32 | 100, 136, 600, 16 | 1 line, nowrap |
| 10 | `.abs .plot` | 200, 328, 1064, 80 | 100, 164, 532, 40 | `-webkit-line-clamp:2`, exactly 2 × 40 px |
| 11 | `.abs .cap` | 200, 432, 440, 48 | 100, 216, 220, 24 | **two** mono lines, 2 × 24 px |
| 12 | `.abs .bbtn.rsm.foc` Resume | 200, 512, 440, 64 | 100, 256, 220, 32 | **focused** — ring on the button |
| 12a | ⌞ `.pbar` track | 200, 568, 440, 8 | 100, 284, 220, 4 | `rgba(0,0,0,0.24)`, flush to the button's bottom edge |
| 12b | ⌞ `.pbar i` fill | 200, 568, 272, 8 | 100, 284, 136, 4 | `#000000`. 272/440 = **61.8 %** |
| 13 | `.abs .bbtn.ghost` Trailer | 664, 512, 240, 64 | 332, 256, 120, 32 | |
| 14 | `.abs .bbtn.ghost` Season 1 | 928, 512, 240, 64 | 464, 256, 120, 32 | |
| 15 | `.abs .bbtn.ghost` Episodes · 10 | 1192, 512, 280, 64 | 596, 256, 140, 32 | |
| 16 | `.abs .bbtn.ghost` Full synopsis | 1496, 512, 272, 64 | 748, 256, 136, 32 | row ends x1768 / 884 dp |
| 17 | `.abs .rhd` cast header | 200, 640, 1664, 56 | 100, 320, 832, 28 | `align-items:flex-end`, gap 16 px / 8 dp |
| 17a | ⌞ `.rt` "Cast" | — | — | 40/56 px → 20/28 dp |
| 17b | ⌞ `.rm` "38 · TMDB" | bottom-aligned, 664..696 px | 332..348 dp | 24/32 px → 12/16 dp |
| 18–24 | `.abs .cast` ×7 | 200 + k·216, 728, 192, 240 | 100 + k·108, 364, 96, 120 | k = 0..6; §2.7 |
| 25 | `.abs .card.seeall` Full cast | 1712, 728, 152, 240 | 856, 364, 76, 120 | |

Cast / See-all x values: px **200, 416, 632, 848, 1064, 1280, 1496, 1712** → dp **100, 208, 316, 424,
532, 640, 748, 856**. Pitch 216 px = **108 dp** (192 block + 24 gap). Last pixel of the rail is
`1712 + 152 = 1864` px = 932 dp — exactly the band's right edge. Cast row bottom is
`728 + 240 = 968` px = **484 dp**, leaving 56 px / 28 dp inside the band.

Action-row gaps are all exactly 24 px / 12 dp: 640→664, 904→928, 1168→1192, 1472→1496.

### 2.2 The vertical ladder, in dp

This is the complete rhythm. Reproduce it from this table and never re-read the HTML.

| from → to | px gap | **dp gap** | existing `DetailScreenTvDefaults` name |
|---|---|---|---|
| top of screen → chrome row top (y56) | 56 | **28** | `ChromeTop` (`= ChromeRowTop`) |
| chrome row | h 96 | h **48** | `CloudStreamDimens.ChromeRowHeight` |
| back cell right edge (x296) → provider label (x320) | 24 | **12** *(horizontal)* | `ChromeGap` — **currently 8 dp, mismatch** |
| chrome bottom (y152) → title top (y176) | 24 | **12** | `ChromeToTitle` ✓ |
| title line box | h 88 | h **44** | `typography.h1` lineHeight 44 sp |
| title bottom (y264) → meta top (y272) | 8 | **4** | `TitleToMeta` ✓ |
| meta line box | h 32 | h **16** | `typography.label` lineHeight 16 sp |
| meta bottom (y304) → plot top (y328) | 24 | **12** | `MetaToPlot` ✓ |
| plot box (2 × 40 px) | h 80 | h **40** | `PlotHeight` ✓ |
| plot bottom (y408) → **caption top (y432)** | 24 | **12** | **no constant — the caption is not implemented** |
| caption box (2 × 24 px) | h 48 | h **24** | **no constant** |
| caption bottom (y480) → action row top (y512) | 32 | **16** | **no constant** |
| *(code collapses the three rows above into one spacer)* | 104 | **52** | `PlotToActions` = 52 dp ✓ arithmetically, but it omits the caption |
| action row | h 64 | h **32** | `CloudStreamDimens.TextButtonHeight` |
| action bottom (y576) → cast header top (y640) | 64 | **32** | `ActionsToList` ✓ |
| cast header | h 56 | h **28** | `RailHeaderDefaults.Height` ✓ |
| cast header bottom (y696) → cast row top (y728) | 32 | **16** | `CloudStreamDimens.LiftedRowClearance` ✓ |
| cast block | h 240 | h **120** | **no token** |
| cast bottom (y968) → band bottom (y1024) | 56 | **28** | `FocusBandInset` |
| hero height | 600 | **300** | `HeroHeight` ✓ |

The hero's 600 px / 300 dp floor sits **below** the action row (y512..576) and **above** the cast
header (y640): "the hero is 600 tall because that is the tallest hero that still fits a 56 header plus
a 288 poster row inside the band."

### 2.3 Every piece of text, verbatim, with its binding

Classification: **(a)** real copy that must come from `:core:strings`, **(b)** a number or
provider string bound to data, **(c)** placeholder/lorem.

| verbatim | class | kind | binding / string |
|---|---|---|---|
| `HDRezka  ·  TV series` | `.meta` | (b) + (a) | `apiName` + a localised type word. `DetailCopy.typeLabel(typeName)` already does the second half. Separator is `&nbsp; ·&nbsp; ` — in code `" · "`. |
| `The Last Signal` | `.h1` | (c) | `TitleDetail.title` |
| `8.4/10.0  ·  Drama · Thriller · Mystery  ·  2019  ·  TV-MA  ·  Ongoing` | `.meta` | (b) | six legs: rating (`rating_format` = `%s/10.0`), tags joined by `" · "`, year, **certification**, **status** (`status_ongoing` = "Ongoing"). **Rating, tags, certification and status are not on `TitleDetail` today** — only `year` is. |
| `Six months after the broadcast went dark, a salvage crew picks up a repeating signal from a station decommissioned forty years ago…` | `.plot` | (c) | `TitleDetail.plot`. Note the trailing U+2026 ellipsis is **typed into the mockup**; in code the 2-line clamp supplies it. |
| `24m` / `remaining` (two lines) | `.cap` | (a) + (b) | **`resume_time_left` = `%dm\nremaining`** (`strings.xml:581`). `resume_remaining` = `%s\nremaining` is the `%s` variant. The two-line box exists *because* the resource embeds `\n`. |
| `Resume` | `.bbtn.rsm` | (a) | `resume` (`strings.xml:579`) |
| `S1:E4 · Dead Air` | same button, after two `&nbsp;` | (b) | season/episode code + episode name. **Today `ResultFragmentTv.kt:516-527` sets only `S1:E4`; the name is on a different view nested inside `resume.progress?.let {}` (`:490-498`).** |
| `Trailer` | `.bbtn.ghost` | (a) | `trailer` (`strings.xml:778`) ✓ exists |
| `Season 1` | `.bbtn.ghost` | (a) + (b) | `season` (`:617`) + `SeasonData.season` **as an integer, never the provider's free-text season name**. Not on `ResultData` → degrades to "Seasons". |
| `Episodes · 10` | `.bbtn.ghost` | (a) + (b) | `episodes` plural (`:8`) + a **per-season** count. Not on `ResultData` → degrades to "Episodes". |
| `Full synopsis` | `.bbtn.ghost` | (a) | **No string exists.** `synopsis` = "Synopsis" (`:754`) is the closest. New string needed. |
| `Cast` | `.rt` | (a) | **No string exists.** `cast_format` = "Cast: %s" (`:125`) is the phone text-mode line, not a header. New string needed. |
| `38 · TMDB` | `.rm` | (b) | count = `ActorData` list size; `TMDB` = the metadata provider that supplied the cast. **No field in the tree carries that provenance — could not establish a binding.** |
| `Ines Marlowe`, `Tobias Kerr`, `Amara Osei`, `Jonah Wilde`, `Ren Takeda`, `Saoirse Doyle`, `Maya Lindqvist` | `.cnm2` | (c) | `ActorData.actor.name`. **On AniList this is the *character*, not the performer** (`AniListApi.kt:119-140`) — correct, not a bug. |
| `Cdr. Rhea Voss`, `Idris Pell`, `Dr. Lune Hart`, `Casimir`, `Ohara`, `The Signal`, `Kestrel` | `.crl` | (c) | `ActorAdaptor.kt:122-146` precedence: localised `ActorRole` → `roleString` → **remove the line**. |
| `RT` (cast item 5) | `.cini` | (c) | initials, derived by the caller. Drawn because that person has no image. |
| `Full cast` | `.ct` (in `.seeall`) | (a) | **No string exists.** New string needed. |
| `38` | `.cy` (in `.seeall`) | (b) | same count as the header |

### 2.4 Type

| element | CSS | px size / line box | **dp / sp** | ramp rung | line box on step? |
|---|---|---|---|---|---|
| `.h1` | `700 64px/88px Manrope`, `-0.02em` | 64 / 88 | **32 / 44** | `h1` | 44 ✓ |
| `.meta` | `400 24px/32px Manrope` | 24 / 32 | **12 / 16** | `label` | 16 ✓ |
| `.plot` | `400 28px/40px Manrope` | 28 / 40 | **14 / 20** | `body` | 20 ✓ |
| `.cap` | `500 20px/24px JetBrains Mono` | 20 / 24 | **10 / 12** | `captionMono` (mono FLOOR) | 12 ✓ (mono inks 23.64 at 20 px, so 24 fits) |
| `.bbtn` | `800 24px/32px Manrope` | 24 / 32 | **12 / 16** | `label` at w800 | 16 ✓ |
| `.rt` | `700 40px/56px Manrope`, `-0.02em` | 40 / 56 | **20 / 28** | `railTitle` | 28 ✓ |
| `.rm` | `500 24px/32px JetBrains Mono` | 24 / 32 | **12 / 16** | `labelMono` | 16 ✓ |
| `.cnm2` | `600 24px/32px Manrope` | 24 / 32 | **12 / 16** | `label` | 16 ✓ |
| `.crl` | `400 20px/24px JetBrains Mono` | 20 / 24 | **10 / 12** | `captionMono` | 12 ✓ |
| `.cini` | `700 40px/160px Manrope` | 40 / **160** | **20 / 80** | `railTitle` with an overridden line box | 80 ✓ — the line box *is* the disc height, deliberately, so the initials are not centred off the step. `AvatarDefaults.InitialsLineHeight = 80.sp` ✓ |
| `.ct` (see-all) | `600 24px/32px` + inline `font-size:24px` | 24 / 32 | **12 / 16** | `label` | 16 ✓ |
| `.cy` (see-all) | `500 20px/24px JetBrains Mono`, `margin-top:0` | 20 / 24 | **10 / 12** | `captionMono` | 12 ✓ |

Every line box lands on the 4 dp step. The only off-step **type** number is the 10 sp font size, which
is the ramp's published floor for both caption rungs.

Note the `.seeall .cy { margin-top:0 }` override and the comment above it: with the poster caption's
8 px `margin-top` left in, the flex column's own 8 px gap doubled, the stack became 72 and centred at
y836; zeroed, the stack is `32 + 8 + 24 = 64` and centres at y840. **In dp: stack 16 + 4 + 12 = 32,
centred in 120 at +44.** Carry this: the see-all card's text stack uses `Arrangement.spacedBy(StackGap)`
and the label must not add its own top margin.

### 2.5 Focus

**Entry focus: Resume.** Stated verbatim in the 7a state strip: *"Entry focus: Resume. 21 focusables,
all of them in this order."* The existing `DetailScreenTv` KDoc is **confirmed, not corrected**.

21 focusables = 5 nav cells + 3 chrome cells + 5 action buttons + 7 cast items + 1 Full cast.

D-pad order, verbatim from the board:

- **Nav rail** cells 1–5 at x56 (28 dp), y128/248/368/488/608 (64/124/184/244/304 dp). RIGHT from a
  cell enters whatever band shares its y. Cell 1 overlaps the chrome row.
- **Chrome:** back (200,56) → bookmark (1648) → favourite (1768). **DOWN from back → Resume.**
- **Action row L→R:** Resume → Trailer → Season 1 → Episodes · 10 → Full synopsis.
- **DOWN from any action button** → the cast item whose x overlaps it. Then cast 1–7 → Full cast.
  **UP** from a cast item returns to the button above it.
- **DOWN from the cast row scrolls to 7a-ii.**
- **LEFT** from any leftmost cell → the nav rail.

Ring geometry: `outline: 4px solid var(--ink); outline-offset: 4px` = **2 dp stroke at 2 dp offset,
4 dp bleed** — `FocusRingStroke` / `FocusRingOffset` / `FocusRingBleed`. The ring is drawn:

| target | ring on | offset |
|---|---|---|
| `.ibx` chrome cell | the 48 dp cell | 2 dp |
| `.bbtn` text button | the button itself | 2 dp |
| `.cast` | the **avatar circle** (`.cast.foc .cav2, .cast.foc .cini`) | 2 dp |
| `.card` / `.seeall` | the card | **0** (`outline-offset:0`) |

Lifts: `.card.foc { transform:translateY(-8px) }` and `.cast.foc { transform:translateY(-8px) }` →
**4 dp**, `CloudStreamDimens.FocusLift` ✓. The cast lift moves the whole 96×120 block (avatar, name
and role together), which is why the clearance above the row is 32 px / **16 dp**
(`LiftedRowClearance`) rather than 24 px / 12 dp.

### 2.6 Colour, per element

| element | fill | text | border |
|---|---|---|---|
| `.tv` | `bg` | `ink` | — |
| `.railc` | `#000000` (= `bg`, literal) | — | `inset -1px 0 0 line` |
| `.lgm` | `ink` | — | — |
| `.nic .gl` | `muted` | — | — |
| `.ibx` | none | — | — |
| `.ivis` | `raised` | — | `inset 1px line` |
| `.gl2` | `muted` | — | — |
| `.meta` (chrome) | — | **`#858585` inline = `muted`** | — |
| `.h1` | — | `ink` | — |
| `.meta` (hero) | — | `ink2` | — |
| `.plot` | — | `ink2` | — |
| `.cap` | — | `muted` | — |
| `.bbtn` (primary) | `ink` | `#000000` (= `bg`) | — |
| `.pbar` track | `rgba(0,0,0,0.24)` | — | — |
| `.pbar i` fill | **`#000000`** | — | — |
| `.bbtn.ghost` | `transparent` | `ink` | `inset 0 0 0 4px line` → **2 dp** ring of `line` |
| `.rt` / `.rm` | — | `ink` / `muted` | — |
| `.cav2` | artwork | — | — |
| `.cini` | `raised` | `ink2` | `inset 1px line` |
| `.cnm2` / `.crl` | — | `ink` / `muted` | — |
| `.seeall` | `raised` | `ct` `ink`, `cy` `ink2` | `inset 1px line` |
| `.heroscrim` | `linear-gradient(90deg, rgba(0,0,0,.92) 0%, rgba(0,0,0,.72) 66%, transparent 96%)` **+** `linear-gradient(0deg, #000 0%, transparent 34%)` | — | — |
| ring (`.foc`) | — | — | `ink` |

The hero scrim's two gradients match `DetailScreenTvDefaults`' five scrim constants exactly
(0.92 / 0.72 / 0.66 / 0.96, and the floor's 0.66 stop read from the top).

### 2.7 The cast rail

- **Items drawn: 7**, plus one `Full cast` card. Data: `showCast` in the script, 7 entries.
- **Block geometry:** `.cast { width:192px; height:240px }` → **96 × 120 dp**. **Not a `CardSize`
  rung** (`PortraitLarge` is 96×144; `PortraitMedium` is 80×120 — the block shares one dimension with
  each and is neither). The comment in the CSS states the intent: *"a person is not media, so this is a
  160 avatar, not a card-ladder rung."*
- **Internal stack** (px → dp): avatar `160` → **80**; gap `16` → **8**; name line box `32` → **16**;
  gap `8` → **4**; role line box `24` → **12**. Total `240` → **120**. Exact positions:

| child | px | dp |
|---|---|---|
| `.cav2` / `.cini` | `left:16, top:0, 160×160, r80` | `left:8, top:0, 80×80`, circle |
| `.cnm2` | `left:0, top:176, w192`, lh 32 | `left:0, top:88, w96`, lh 16 |
| `.crl` | `left:0, top:216, w192`, lh 24 | `left:0, top:108, w96`, lh 12 |

  The avatar is horizontally centred in the block: `16 + 160 + 16 = 192`. Name and role are
  `text-align:center` across the full 96 dp, one line each, `nowrap` + ellipsis.
- **Avatar size: 160 px → 80 dp**, circle (`border-radius:80px` → 40 dp = half the box).
  `AvatarDefaults.Size = 80.dp` ✓ exact match, and `Avatar`'s KDoc already quotes this board.
- **Role line:** mono 20/24 px → 10/12 dp, `muted`. Removed entirely when the adaptor has neither an
  `ActorRole` nor a `roleString` — the board does not draw a reduced-height variant, so **the block
  stays 120 dp and the role slot stays empty** (not re-centred).
- **No image → initials**, same 80 dp circle on `raised` with a `line` hairline and an `ink2` 20 sp
  label whose line box is the disc height. Cast item 5 (`Ren Takeda` / `RT`) is the drawn example.
  The closing notes make this a prerequisite: today `ResultViewModel2.kt:235, :297-300` decides
  image-or-text for the **whole list** from the first actor alone, which must become per-person.
- **`Full cast` does not open a page.** From the 7a-ii state strip: it *"Opens the 560 side panel, not
  a new page: one 96 list row per person — 48 disc, name 24/32, role mono 20/24 — on the 120 pitch,
  Done pinned at y928. Specified here, not yet drawn."* In dp: panel **280** (`SidePanelWidth` ✓), row
  **48** (`ListRowHeight` ✓), disc **24** (`ListRowDiscSize` ✓), name **12/16** (`label`), role
  **10/12** (`captionMono`), pitch **60** (`SidePanelRowPitch` ✓), Done pinned at **y464 dp**.
  On AniList titles the panel's name line is the character, the second line the role, and a trailing
  column names the voice actor.
- **A cast item has no press action worth the name.** There is no actor id (`MainAPI.kt:1524-1550`),
  so on TV it stays a focus stop *so the row scrolls and every name is reachable*; its only press
  action is the existing character / voice-actor swap (`ActorAdaptor.kt:98-101`) when a voice actor
  exists, and **nothing otherwise**. The phone's long-press web search has no TV equivalent and is not
  drawn.

### 2.8 Non-content states (from the 7a state strip, converted)

| state | geometry |
|---|---|
| LOADING | Shimmer occupies the resolved boxes exactly: title `200,176,720×88` → **100,88,360×44**; meta `200,272,560×32` → **100,136,280×16**; two plot bars at y328 and y368 → **164** and **184**; one `1568`-wide action bar at y512 → **784 dp wide at y256**; seven 160 px avatar circles on the 216 px pitch at y728 → **80 dp circles on the 108 dp pitch at y364**, each with a name bar at y904 → **452** and a role bar at y944 → **472**. Focus rests on the **back** cell and moves once, to Resume. |
| EMPTY (no episodes) | The caption slot at y432 → **y216 dp** carries the bound `no_episodes_found` ("No Episodes found", `strings.xml:393`); Resume/Season/Episodes drop out and the row **re-solves from x200 / 100 dp**. |
| EMPTY (no cast) | Header **and** row removed entirely; More like this moves up into the y640 → **320 dp** slot. "The band is never a header over nothing." |
| ERROR | Hero and rail replaced; nav rail and back cell stay live. State view centred on x1032 → **516 dp**: glyph `96×96 at 984,240` → **48×48 at 492,120**; headline 64/88 at y376 → **188**; body 28/40 at y488 → **244**; URL demoted to mono 20/24 at y584 → **292**; one `320×64` "Retry connection" at `872,640` → **160×32 at 436,320**, with an explicit `nextFocusUp` to the back cell and a LEFT into the nav rail. |
| OFFLINE | Banner over the chrome row, `320,56,1232×96` → **160,28,616×48**, replacing the provider label only. Its Retry is a `240×64` text button at `1288,72` → **120×32 at 644,36** — inside a row already in the focus order, so it costs zero vertical budget and the cast row stays at y968 / 484 dp. |
| PARTIAL | Same banner box, copy swaps to "Still loading details from AniList". Every box is fixed-size precisely so `applyMeta` can backfill score, tags, plot and recommendations **in place without moving focus** (`ResultViewModel2.kt:1676-1703`). |

> The closing notes flag that OFFLINE *has nothing to bind to*: `isNetworkError` is read in exactly one
> place in the whole app (`HomeFragment.kt:893-897`) and never on the result page. Without a
> connectivity source the OFFLINE strip silently degrades to ERROR.

---

## 3. Board 7b · Movie detail

**Every y-coordinate is reused from 7a.** The board states it and the markup confirms it: chrome
56..152, title 176..264, meta 272..304, synopsis 328..408, caption 432..480, action row 512..576, cast
header 640..696, cast 728..968. **§2.2's ladder applies unchanged.** Only two bands change content and
neither changes geometry.

### 3.1 What differs

| # | class | px box | dp box | notes |
|---|---|---|---|---|
| 1 | `.abs .art.v3` hero | 0,0,1920,600 | 0,0,960,300 | `filter:hue-rotate(28deg) brightness(1.35)` |
| 5 | `.meta` provider | 320,88,600,32 | 160,44,300,16 | `HDRezka  ·  Film` |
| 9 | `.meta` | 200,272,1200,32 | 100,136,600,16 | runtime instead of status/seasons |
| 12 | `.bbtn.rsm` Resume | 200,512,440,64 | 100,256,220,32 | **not focused on this board** (`.foc` is absent) |
| 12b | `.pbar i` | w 272 | w 136 | 61.8 %, same as 7a |
| 13 | `.bbtn.ghost` Start over | 664,512,**288**,64 | 332,256,**144**,32 | new control |
| 14 | `.bbtn.ghost` Trailer | **976**,512,240,64 | **488**,256,120,32 | |
| 15 | `.bbtn.ghost` Download | **1240**,512,**288**,64 | **620**,256,**144**,32 | new control |
| 16 | `.bbtn.ghost` Full synopsis | **1552**,512,272,64 | **776**,256,136,32 | row ends x1824 / **912 dp** |
| 17b | `.rm` | — | — | `24 · TMDB` |
| 18–24 | `.cast` ×7 | 200+k·216, 728 | 100+k·108, 364 | `filmCast`; **`.foc` is on item 2 (k=1, x416 / 208 dp)** |
| 25 | `.seeall` Full cast | 1712,728,152,240 | 856,364,76,120 | `.cy` = `24` |

Action-row gaps again all 24 px / 12 dp: 640→664, 952→976, 1216→1240, 1528→1552. The row ends 40 px /
20 dp short of the band edge (1824 vs 1864) — that is slack, not an error.

### 3.2 Text, verbatim

| verbatim | class | kind | binding |
|---|---|---|---|
| `HDRezka  ·  Film` | `.meta` | (b)+(a) | `apiName` + type word |
| `The Last Expedition` | `.h1` | (c) | `title` |
| `2024  ·  2h 16m  ·  8.4/10.0  ·  PG-13  ·  Science Fiction · Thriller` | `.meta` | (b) | year, **runtime formatted as `2h 16m`**, rating, certification, tags. `duration_format` = `%d min` (`:220`) does **not** produce `2h 16m`; a new formatter or string is needed. |
| `A salvage crew answers a distress call from a ship that vanished forty years ago, with one more name on its manifest than there should be…` | `.plot` | (c) | `plot` |
| `52m` / `remaining` | `.cap` | (a)+(b) | `resume_time_left` |
| `Resume from 1:24:07` | `.bbtn.rsm` | (a)+(b) | **No string exists.** The branch returns the bare word "Resume" (`ResultFragmentTv.kt:518`) and **no formatter in the tree turns `ResumeProgress.progress` into `1:24:07`** — `secondsToReadable` yields "1h 24m". Needs a new string `Resume from %s` and a h:mm:ss formatter. |
| `Start over` | `.bbtn.ghost` | (a) | **No string exists.** And it is **not** Resume with a different label: `loadPlayer` keeps `playbackPosition` whenever `sameEpisode` is true and `startPosition` is null (`CS3IPlayer.kt:274, :289-291`), so it must pass an explicit `startPosition = 0L`. |
| `Trailer` | `.bbtn.ghost` | (a) | `trailer` ✓ |
| `Download` | `.bbtn.ghost` | (a) | `download` (`:189`) ✓. **A new control, not a moved one** — the string "download" does not occur once in `fragment_result_tv.xml`'s 1110 lines. |
| `Full synopsis` | `.bbtn.ghost` | (a) | no string |
| `Cast` / `24 · TMDB` / `Full cast` / `24` | `.rt` / `.rm` / `.ct` / `.cy` | (a)/(b)/(a)/(b) | as 7a |
| `Callum Reyes`, `Noor Haddad`, `Elias Brandt`, `Priya Nair`, `Lucas Moreau`, `Hana Sato`, `Owen Pryce` | `.cnm2` | (c) | `ActorData` |
| `Capt. Anselm`, `Wren Okafor`, `The Pilot`, `Dr. Saoirse Vale`, `Quartermaster`, `Mara`, `Engineer Dahl` | `.crl` | (c) | role |

All `filmCast` entries carry an image (`v2`–`v5` or none), so **no initials variant is drawn on 7b**.

### 3.3 Focus

*"Entry focus is Resume — the ring is drawn on cast item 2 here only to show the 8 lift and the ring
following the avatar's circle; the name and role lift with it and stay readable."*

**So the drawn ring on 7b is a demonstration, not the entry focus.** An implementer must not read the
ring's position off 7b. Order is identical to 7a with Start over and Download standing in for Season
and Episodes: back → bookmark → favourite; Resume → Start over → Trailer → Download → Full synopsis;
DOWN into the cast item whose x overlaps, then Full cast; LEFT from any leftmost cell into the rail.

### 3.4 States that differ from 7a

| state | content |
|---|---|
| EMPTY | A film has no `noEpisodesFoundText` (non-null only for series and anime, `ResultViewModel2.kt:356-359`), so "no playable source" needs its own signal. **Until then the caption slot stays empty and Resume degrades to Play.** |
| ERROR | Same state view as 7a, centred on x1032 / 516 dp. Open-in-browser is deliberately absent on TV (commented out in both layout and fragment, `ResultFragmentTv.kt:961`). |
| OFFLINE | Same banner box; its action is "Go to Downloads", one LEFT into the rail's Downloads cell. |
| PARTIAL | Same banner box. **Download is the one control that changes label inside its own 288 px / 144 dp box** — Download → Queued → Downloading 42% (with a **4 px / 2 dp ink bar on the button's bottom edge, never live**) → Downloaded — so the row never reflows. Note this bar is 4 px / 2 dp, *not* the Resume `.pbar`'s 8 px / 4 dp. |

---

## 4. Board 7a-ii · TV show detail — scrolled

### 4.1 What is on the screen

Only four things: the nav rail, the cast row with its header and See-all, and the "More like this" row
with its header and See-all. **No hero, no chrome row, no back cell, no provider label, no bookmark or
favourite, no title, meta, synopsis, caption or action row.**

| # | class | px box | dp box | notes |
|---|---|---|---|---|
| 1 | `.abs .railc` | 0,0,152,1080 | 0,0,76,540 | unchanged; the rail does not scroll |
| 2 | `.abs .rhd` cast header | 200,56,1664,56 | 100,28,832,28 | `.rt` "Cast", `.rm` "38 · TMDB" |
| 3–9 | `.abs .cast` ×7 | 200+k·216, **144**, 192, 240 | 100+k·108, **72**, 96, 120 | `castS` = same `showCast`; **no item focused** |
| 10 | `.abs .card.seeall` Full cast | 1712,**144**,152,240 | 856,**72**,76,120 | `.cy` = `38` |
| 11 | `.abs .rhd` related header | 200,**448**,1664,56 | 100,**224**,832,28 | `.rt` "More like this", `.rm` "HDREZKA · 24" |
| 12–18 | `.abs .card.art` posters ×7 | 200+k·216, **536**, **192, 288** | 100+k·108, **268**, **96, 144** | `railS` = `buildRail(2, 536)` → **poster 3 (k=2) is focused** |
| 12a | ⌞ `.cardttl` on the focused poster only | `left:0,right:0,bottom:0, h96`, `padding:16` | `h48`, `padding 8` | `z-index:2`; **every other poster's title block is `display:none`** |
| 19 | `.abs .card.seeall` See all | 1712,**536**,152,**288** | 856,**268**,76,**144** | `.cy` = `24` |

### 4.2 The ladder, in dp

| from → to | px | **dp** | token |
|---|---|---|---|
| band top → cast header (y56) | 56 | **28** | `FocusBandInset` |
| cast header | h 56 | h **28** | `RailHeaderDefaults.Height` |
| header bottom (y112) → cast row (y144) | 32 | **16** | `LiftedRowClearance` |
| cast block | h 240 | h **120** | — |
| cast bottom (y384) → related header (y448) | **64** | **32** | the same section gap 7a leaves between its action row and its rail header |
| related header | h 56 | h **28** | `RailHeaderDefaults.Height` |
| header bottom (y504) → posters (y536) | 32 | **16** | `LiftedRowClearance` |
| poster row | h 288 | h **144** | `CloudStreamCardSizes.PortraitLarge.height` |
| poster bottom (y824) → band bottom (y1024) | 200 | **100** | slack |

*"both rows with 32 of lift clearance"* — 16 dp, confirmed by the two 32 px gaps above.

### 4.3 The "More like this" rail

- **Rung: `192 × 288 px` → `96 × 144 dp` = `CloudStreamCardSizes.PortraitLarge`** (`poster-lg`, 2:3,
  "Library grid, related rails"). This is a legal rung on the corrected ladder; the banned
  `320 × 180` rung is nowhere on these boards.
- **Pitch 216 px → 108 dp**, same as the cast rail (192 + 24 gap → 96 + 12).
- x values px **200, 416, 632, 848, 1064, 1280, 1496**, See all at **1712** → dp **100, 208, 316, 424,
  532, 640, 748, 856**. Last pixel at 1864 px / 932 dp = band edge.
- **The title overlay is focus-gated.** `ttl: on ? "z-index:2" : "display:none"` in `buildRail` — only
  the focused card shows its title and year. `.cardttl` is a `96 px → 48 dp` tall block on the card's
  bottom edge, `padding:16 → 8 dp`, filled with
  `linear-gradient(0deg, rgba(0,0,0,0.94) 12%, transparent 100%)`, holding `.ct` (12/16 dp `ink`) and
  `.cy` (10/12 dp `ink2`, `margin-top: 8px → 4 dp`). **This matches `MediaCard` exactly**, which only
  draws its reveal `if (state.focused && state.title != null)`.
- **Ring on the card, `outline-offset: 0`**, plus a 4 dp lift. Unlike every other target on these
  boards, a card's ring sits flush against its own edge.
- **See all is `152 × 288 px → 76 × 144 dp`.** It is *not* a `CardSize`: it shares `PortraitLarge`'s
  144 dp height but is 76 dp wide, not 96. Same for the cast rail's Full cast at `76 × 120`.

### 4.4 Text, verbatim

| verbatim | class | kind | binding |
|---|---|---|---|
| `Cast` / `38 · TMDB` / `Full cast` / `38` | — | as 7a | as 7a |
| `More like this` | `.rt` | (a) | **No string exists.** `recommended` = "Recommended" (`:551`) is the closest. New string needed. |
| `HDREZKA · 24` | `.rm` | (b) | `apiName` + recommendation count. **Note the mockup types the provider name in UPPERCASE here while 7a's chrome row types `HDRezka`.** `.rm` has no `text-transform`, so this is an inconsistency in the mockup copy, not a style rule. **Bind the provider name as the provider spells it.** |
| `Ashen Field` 2021, `Deep Signal` 2019, `Quiet Harbor` 2023, `Nine Lives` 2020, `Salt Road` 2022, `Hollow Crown` 2018, `Drift` 2024 | `.ct` / `.cy` | (c) | recommendation name + year. Only `Quiet Harbor` / `2023` is visible (poster 3 is focused). |
| `See all` | `.ct` | (a) | **No string exists**, though `SeeAllCard`'s KDoc example already writes `stringResource(Res.string.see_all)`. New string needed. |
| `24` | `.cy` | (b) | recommendation count |

### 4.5 Focus

- Cast row at y144 / **72 dp** ⇄ Full cast.
- **DOWN** → the poster whose x overlaps. Posters 1–7, then See all.
- **UP** from a poster returns to the cast item above it.
- **UP from the cast row scrolls back to 7a and lands on the action-row button above.**
- The ring is drawn on **poster 3**.
- The board states no entry focus and no total — this screen is reached by a scroll, so focus always
  arrives from 7a's cast row.

### 4.6 How 7a-ii relates to 7a — read this before building a scroll

7a's cast header is at y640 and its cast row at y728; 7a-ii's are at y56 and y144. **The delta is
exactly 584 px = 292 dp for both**, so the two boards are one surface at a single scroll offset of
**292 dp**. In 7a's unscrolled coordinates, 7a-ii's related header sits at y1032 px (516 dp) and its
posters at y1120..1408 px (560..704 dp) — i.e. just past the band's y1024 bottom, which is what
"below the fold" means here.

Two things the single-offset reading does **not** explain, and an implementer has to decide:

1. **The hero.** At a 584 px scroll the hero's bottom edge (y600) would still be 16 px (8 dp) on
   screen. 7a-ii draws **no hero at all**. So either the hero is clipped/removed at the top of the
   scroll rather than translated, or the offset is not exactly 584. **Could not establish which the
   design intends.**
2. **The chrome row.** 7a's back / bookmark / favourite cells and the provider label are simply gone
   on 7a-ii — neither pinned nor visibly scrolled away. That removes 3 of the 21 focusables. The board
   does not say whether BACK still works from 7a-ii or whether scrolling up is the only way back to
   the chrome row. **Could not establish.**

---

## 5. Cross-check: `DetailScreenTvDefaults`, constant by constant

File: `feature/detail/src/commonMain/kotlin/com/lagradost/cloudstream4/feature/detail/tv/DetailScreenTv.kt`

| constant | value | measured | verdict |
|---|---|---|---|
| `ContentStart` = `ContentOriginX` | 100 dp | x200 px → 100 dp | ✓ **matches** |
| `ContentEnd` | 48 dp | band ends x1864 px → right inset **28 dp** (`.rhd` is `w1664` from x200, i.e. 200..1864) | ✗ **mismatch — should be 28 dp (`FocusBandInset`)** |
| `ChromeTop` = `ChromeRowTop` | 28 dp | y56 px → 28 dp | ✓ **matches** |
| `ChromeGap` | 8 dp | back cell ends x296, label at x320 → 24 px → **12 dp** | ✗ **mismatch — should be 12 dp (`MinFocusGap`)** |
| `ChromeToTitle` | 12 dp | y152 → y176 = 24 px | ✓ **matches** |
| `TitleToMeta` | 4 dp | y264 → y272 = 8 px | ✓ **matches** |
| `MetaToPlot` | 12 dp | y304 → y328 = 24 px | ✓ **matches** |
| `PlotHeight` | 40 dp | 80 px, 2 × 40 px lines | ✓ **matches** |
| `PlotLines` | 2 | `-webkit-line-clamp:2` | ✓ **matches** |
| `PlotToActions` | 52 dp | y408 → y512 = 104 px → 52 dp | ✓ **arithmetically correct, but it is three gaps and a missing element**: 12 dp + the 24 dp caption + 16 dp. Splitting it is what adds the caption. |
| `ActionsToList` | 32 dp | y576 → y640 = 64 px | ✓ **matches** |
| `HeroHeight` | 300 dp | 600 px | ✓ **matches** |
| `TitleMaxWidth` | 600 dp | `.h1 width:1200px` | ✓ **matches** |
| `PlotMaxWidth` | 532 dp | `.plot width:1064px` | ✓ **matches** |
| `ScrimStartAlpha` 0.92 | — | `rgba(0,0,0,0.92) 0%` | ✓ |
| `ScrimMidAlpha` 0.72 | — | `rgba(0,0,0,0.72) 66%` | ✓ |
| `ScrimMidStop` 0.66 | — | `66%` | ✓ |
| `ScrimEndStop` 0.96 | — | `rgba(0,0,0,0) 96%` | ✓ |
| `FloorScrimStop` 0.66 | — | `linear-gradient(0deg, #000 0%, transparent 34%)` read from the top = 66% | ✓ |

### The action row's ceiling is five controls, not six — added by `KMP-38`

This spec's own binding lists imply six controls for a one-season show that has a stored position, a
trailer and a plot: Resume, start-over, Trailer, Season, Episodes, Synopsis. **Six does not fit.**
`ResumeWidth` 220 + five ghosts at the `TextButtonMinWidth` 120 dp floor + five `MinFocusGap` 12 dp gaps
= **880 dp**, against **833 dp** available on `Television_720p` (961.5 − `ContentStart` 100 −
`ContentEnd` 28). A `Row` sheds overflow from its last children, so Synopsis is silently crushed below
its own minimum, and because every ghost is already at the floor at 12 sp, a longer translation makes it
worse rather than better.

**Neither board draws six.** 7a has no Start over; 7b has no Season and no Episodes. So the fixed-y,
fixed-origin row the closing notes argue for holds only at five. `KMP-38` draws start-over for a playable
title only — 7b's case — which is also the honest reading, since a series' "start over" could only mean
its *first* episode, a different action from resuming S3:E4.

Neither this spec, the screen's implementation, nor a code review caught this. The device test that
measures the row against the band did.

**Missing from the object entirely** (needed for 7a/7b/7a-ii):

`PlotToCaption` 12, `CaptionHeight` 24, `CaptionToActions` 16, `ActionRowGap` 12, `ResumeWidth` 220,
`ResumeProgressHeight` 4, `HeaderToRail` 16, `SectionGap` 32, `CastBlockWidth` 96,
`CastBlockHeight` 120, `CastPitch` 108, `CastAvatarToName` 8, `CastNameToRole` 4,
`CastNameTop` 88, `CastRoleTop` 108, `SeeAllWidth` 76, `ChromeFreeSlotX` 764, `ScrollOffset` 292.

Also note the KDoc's departure list is now **partly stale**: it says *"Back is a text button. The
artboard draws an icon, the icon pack is undecided, and `.claude/rules/icons.md` forbids drawing one."*
`CloudStreamIcons` now exists and ships `Back`, `Bookmark`, `Favorite`, `Download`, `Disclosure`,
`Play` and ~40 more from Tabler. **The icon-pack blocker is gone.**

---

## 6. Cross-check: `CloudStreamDimens`

For every dp this spec reports: does a token already mean it?

### Covered by an existing token — use it, do not restate the number

| dp | token | used for |
|---|---|---|
| 1 | `Hairline` | every `inset 1px line` border |
| 2 | `FocusRingStroke`, `FocusRingOffset` | the ring; also `.bbtn.ghost`'s 4 px inset ring |
| 4 | `FocusRingBleed`, `StackGap`, `ListRowMarkerWidth` | ring bleed; cast name→role gap; Resume's progress bar height |
| 8 | `ListRowPadding` | rail-header title→count gap; `.cardttl` padding; avatar→name gap |
| 12 | `MinFocusGap` | every gap between focusables: action-row gaps, cast/poster gaps, chrome back→label |
| 16 | `LiftedRowClearance`, `SidePanelContentInset` | header→rail gap on both rails; the ghost button's horizontal padding (`TextButtonDefaults.HorizontalPadding`) |
| 24 | `ChipVisualHeight`, `ListRowDiscSize`, `StackSingleLineBox` | the caption's two-line box height; the Full-cast panel's 48 px disc |
| 28 | `FocusBandInset`, `ChromeRowTop`, `NavRailIconInset`, `RailHeaderDefaults.Height` | band inset; chrome top; rail header height |
| 32 | `TextButtonHeight` | every action-row button; also the 64 px section gap |
| 48 | `ChromeRowHeight`, `IconTargetCell`, `NavRailIconCell`, `ListRowHeight` | chrome row; chrome icon cells; nav cells; Full-cast panel rows |
| 60 | `SidePanelRowPitch` | nav-cell pitch (120 px); Full-cast panel pitch (120 px) |
| 76 | `NavRailWidth`, `ChromeRowBottom` | rail width; chrome bottom |
| 100 | `ContentOriginX` | x200 on every board |
| 120 | `TextButtonMinWidth` | the 240 px ghost buttons (Trailer, Season 1) are exactly the minimum |
| 280 | `SidePanelWidth` | the Full-cast panel |
| 932 | `ContentEndX` | band right edge; `.rhd` width 1664 from x100 |
| 4 (lift) | `FocusLift` | `translateY(-8px)` on cards and cast blocks |

### Needs a new `XxxDefaults` literal — no token means this

| dp | what it is | suggested home |
|---|---|---|
| 44 | `.h1` line box | already `typography.h1.lineHeight` = 44 sp |
| 20 | `.plot` line box | already `typography.body.lineHeight` = 20 sp |
| 300 | hero height | `DetailScreenTvDefaults.HeroHeight` ✓ exists |
| 600 | title max width | `DetailScreenTvDefaults.TitleMaxWidth` ✓ exists |
| 532 | plot max width | `DetailScreenTvDefaults.PlotMaxWidth` ✓ exists |
| 220 | Resume button width | **new** `DetailScreenTvDefaults.ResumeWidth` |
| 136 / 140 / 144 | the ghost buttons' drawn widths | **do not encode** — intrinsic width (label + 2 × 16 dp padding) is exempt from the step under geometry rule 6. Only `TextButtonMinWidth` 120 is authored. |
| 80 | avatar diameter | `AvatarDefaults.Size` ✓ exists |
| 96 × 120 | cast block | **new** — and explicitly *not* a `CardSize` |
| 108 | cast / poster pitch | **new** `CastPitch` / reuse for the related rail |
| 88 / 108 | cast name top / role top inside the block | **new**, or derive: `80 + 8` and `80 + 8 + 16 + 4` |
| 76 | See-all card width | **new**. Equals `NavRailWidth` numerically; that is a coincidence, do not express one in terms of the other. |
| 96 × 144 | related poster | `CloudStreamCardSizes.PortraitLarge` ✓ exists |
| 48 | `.cardttl` height | `IconTargetCell` numerically; meaning differs. Prefer a new `CardTitleBlockHeight`. |
| 316 / 364 / 320 / 72 / 224 / 268 | absolute row/header y positions | **new**, or better: do not encode absolute y at all — build the column from the gap ladder in §2.2 and §4.2. |
| 292 | 7a → 7a-ii scroll offset | **new** `ScrollOffset`, if the scroll is implemented as a fixed jump |
| 764 | chrome row's free Subscribe slot x | **new** `ChromeFreeSlotX` |
| 464 | Full-cast panel's pinned Done y | **new** |
| 6 (radius) | the off-grid radius | **do not create it.** Use `CloudStreamTheme.shapes.medium` (8 dp). |

---

## 7. `--live` / `--acc`: verified zero spends

**The closing note's claim is confirmed against the CSS.** `--acc: #CD3D4B` is declared on `.tv` and is
referenced by exactly two rules in the whole stylesheet:

- `.cardbar i { background: var(--acc) }` — `.cardbar` **is not used on 7a, 7b or 7a-ii.** No element
  on any of the three boards carries that class.
- `.playd { background: var(--acc) }` — 7d's played band only.

Therefore **`--live` is spent zero times on 7a, zero times on 7b and zero times on 7a-ii.**

**The resume progress is a black inversion, confirmed:**

```css
.pbar   { position:absolute; left:0; bottom:0; height:8px; width:100%; background:rgba(0,0,0,0.24); }
.pbar i { display:block; height:100%; background:#000000; }
```

with the comment above it: *"black, not `--acc`: the contract forbids live on a button, and this bar
lives inside the white primary. Progress reads as an ink inversion on the fill instead."* The bar sits
inside `.bbtn` whose background is `--ink` (`#FFFFFF`), so the track is 24 % black-on-white and the
fill is solid black. **8 px tall → 4 dp, flush to the button's bottom edge.**

Two consequences for the implementer:

1. **`ProgressRule` cannot draw this.** Its `color` defaults to `colors.live` and its KDoc says
   *"`live` is the only correct value in the design"*. The Resume bar needs `color = colors.bg` and
   `trackColor = colors.bg.copy(alpha = 0.24f)`.
2. **The thickness is wrong too.** `ProgressRuleDefaults.Thickness` is **2 dp** ("4px on the
   artboard"); this board's `.pbar` and `.cardbar` are **8 px → 4 dp**, which is what
   `MediaCardDefaults.ProgressRuleHeight` (4 dp) says. The audit records this as an open conflict
   (§3, "H §8.4, still open"). **For 7a/7b use 4 dp.**

`--live` is also explicitly banned on 7b's Download progress bar: *"a 4px ink bar on the button's
bottom edge, never live"* — and that one is 4 px → **2 dp**, half the Resume bar. The design-system
rule behind both: *"Download progress — NOT live #CD3D4B; ink #FFFFFF fill in the same 4px bar. A
download is not playback."*

---

## 8. Cross-check: `:core:designsystem` component coverage

Read from the code, which the audit says to trust over itself.

| drawn thing | existing component | fit |
|---|---|---|
| Nav rail, 5 cells, no current | `rail/NavRail.kt` + `NavRailHost.kt` | **exists but wrong geometry.** `NavRailDefaults.ItemGap = 4.dp` (8 px) and `LogoGap = 16.dp` (32 px) are the pre-correction chassis; these boards use 12 dp and 0. |
| Chrome icon cell (48 dp cell, 16 dp glyph) | `target/IconCell.kt` | **exists.** Cell 48 ✓, glyph 16 ✓, ring on the cell with `shapes.medium` ✓. **Missing the board's `--raised` 24 dp plate** between cell and glyph (`.ivis`). Decide whether to add it or drop it. |
| Back / bookmark / favourite glyphs | `icon/CloudStreamIcons.kt` | **exists** — `Back`, `Bookmark`, `Favorite`, and `Download` and `Disclosure` for 7b and the see-all chevron. |
| Resume (primary, with a progress bar inside) | `target/TextButton.kt` → `PrimaryTextButton` | **partial.** Height 32 ✓, min width 120 ✓, no colour parameter ✓, label `typography.label` ✓. **No progress-bar slot** — the board puts a 4 dp bar flush inside the button's bottom edge. **A new component or a `progress: Float?` parameter is needed.** Also the board's radius is 8 px → 4 dp (`shapes.small`); the implementation uses `shapes.medium` (8 dp). |
| Ghost buttons (Trailer, Season, Episodes, Full synopsis, Start over, Download) | `GhostTextButton` | **exists.** One divergence: the board's ghost ring is `inset 0 0 0 4px var(--line)` = **2 dp**; `GhostTextButton` strokes `CloudStreamDimens.Hairline` = 1 dp. Decide which; the board is 2 dp. |
| Rail header "Cast / 38 · TMDB" | `rows/RailHeader.kt` | **exists and matches exactly.** Height 28 ✓, title `railTitle` 20/28 ✓, count `labelMono` 12/16 `muted` ✓, gap 8 ✓, non-focusable ✓. **One difference:** the board bottom-aligns (`align-items:flex-end`); `RailHeader` uses `Alignment.CenterVertically`. With a 28 dp box and a 28 dp title line box the title fills the box either way, but the 16 dp count lands at +6 dp centred and +12 dp bottom-aligned. **Bottom-aligned is the corrected behaviour; centred puts the count off the step.** |
| Cast avatar + initials | `primitive/Avatar.kt` | **exists and matches exactly.** 80 dp ✓, circle ✓, ring on the disc at `FocusRingBleed` ✓, initials line box = disc height (80 sp) ✓, `raised` + `line` + `ink2` ✓, focus hoisted ✓. Its KDoc already cites this board. |
| Cast block (avatar + name + role, 96×120, lifts as one) | **none** | The block, its 108 dp pitch, its text stack and its lift are the caller's. `Avatar` deliberately does not own them. **New: a `CastItem` composable.** |
| Cast rail / related rail (LazyRow, 12 dp spacing, 4 dp content padding, 16 dp clearance above) | **none** | No rail/row container in `:core:designsystem`. Build with `LazyRow(horizontalArrangement = spacedBy(MinFocusGap), contentPadding = PaddingValues(FocusRingBleed))`. |
| Related poster 96×144 with focus-gated title reveal | `card/PosterCard.kt` (`PosterRung.Large`) | **exists and matches.** Rung ✓, reveal only when focused ✓, 4 dp lift ✓, ring at offset 0 ✓, `ContentPadding` 8 ✓, title `label` + meta `captionMono` with a 4 dp gap ✓. **One divergence:** `MediaCard` uses `shapes.none`; the board's `.card` has an 8 dp radius equivalent (12 px → 6 → use 8). |
| See all / Full cast trailing card | `card/SeeAllCard.kt` | **exists but cannot be sized.** It takes a `CardSize`, and the board's cards are **76 × 120** and **76 × 144** — neither is a rung, and `CardSize`'s constructor is `internal` to `:core:theme`, so a feature module **cannot construct one**. Either the board's 152 px width is wrong, or `SeeAllCard` needs a non-`CardSize` size, or a rung must be added. **This is a hard blocker, not a preference.** |
| Hero (artwork + two scrims, 960×300) | **none** (audit: "Hero block" missing) | `DetailScreenTv.Hero` is the only implementation and it is private to the feature. |
| Caption, `%s\nremaining` two-line mono block | **none** | A plain `BasicText` with `captionMono` and a fixed 24 dp box. |
| Full-cast side panel (280 dp, 48 dp rows on a 60 dp pitch, pinned Done) | `panel/SidePanel.kt` + `SidePanelHost.kt` + `rows/ListRow.kt` | **exists** (focus trap, restore, BACK, scrim), **no callers yet**, and **no slide animation** (audit §3). Also: `SidePanel`'s KDoc requires Apply and Cancel; this panel wants only Done. That is the unresolved conflict the audit flags. |
| Offline / partial banner over the chrome row (616 × 48 dp at 160, 28) | `state/FailureBanner.kt` | **exists but is sized for a chip row** (audit §3). A new placement is needed. |
| ERROR state view centred on x516 | `state/StateView.kt` + `StateActionButton.kt` | **exists.** The board's geometry (48 dp glyph at 492,120; 44 dp headline at 188; 20 dp body at 244; 12 dp mono URL at 292; 160×32 button at 436,320) needs checking against `StateView`'s own. |
| LOADING skeleton at exact final geometry | `state/LoadingSkeleton.kt` + `SkeletonPulse.kt` | **exists**; the per-box geometry in §2.8 is the caller's. |

### Components that do not exist yet

1. **Cast item block** (avatar + name + role, 96 × 120 dp, lifting as one unit on a 108 dp pitch).
2. **Primary button with an inline progress bar** — Resume. `PrimaryTextButton` has no slot for it.
3. **A rail/row container** for a horizontal focus row with the 12 dp gap, 4 dp bleed padding and
   16 dp clearance.
4. **Hero block** (artwork + the two-gradient scrim at 960 × 300 dp).
5. **Two-line mono caption** block (`%s\nremaining`).
6. **A see-all card at a non-ladder size** (76 × 120 and 76 × 144 dp) — `SeeAllCard` cannot express it.
7. **A banner placement over the chrome row** — `FailureBanner` is sized for a chip row.
8. **The Full-cast panel content** (a 48 dp list row with a 24 dp disc, a two-line stack and an
   optional trailing voice-actor column) — specified by the board, "not yet drawn".

---

## 9. Things the boards draw with no obvious data behind them — reduction candidates

| drawn | why it is a candidate |
|---|---|
| `TMDB` in `38 · TMDB` | **No field in the tree names which metadata provider supplied the cast.** `ActorData` carries name, image, character/role and voice actor (`MainAPI.kt:1524-1550`) and nothing about provenance. Drop the attribution leg or bind it to something real. |
| The whole cast rail's press behaviour | **There is no actor id**, so a cast item has nowhere to open. Its only press action is the existing character / voice-actor swap (`ActorAdaptor.kt:98-101`), and **nothing at all** when there is no voice actor. 7 of the board's 21 focus stops exist only so the row scrolls. |
| `TV-MA` / `PG-13` (certification) and `Ongoing` (status) in the meta run | `status_ongoing` exists as a string, but **neither certification nor status is on `TitleDetail`**. Two of the six meta legs may have to go. |
| `2h 16m` (7b runtime) | `duration_format` is `%d min`. No `h m` formatter exists. |
| `1:24:07` (7b resume) | **No formatter in the tree** produces it; `secondsToReadable` yields "1h 24m". |
| `Season 1` and `Episodes · 10` | Neither `SeasonData.season` as an integer nor a per-season count is on `ResultData` (`ResultViewModel2.kt:167-197`). **The board's own note says both degrade to "Seasons" and "Episodes"** if they are not lifted onto the detail state. |
| The 5 nav-rail cells on a detail page | Drawn as five identical generic cells with **no current marker and no labels** — `nav` in the script is `[{i:0}…{i:4}]`, pure placeholder. They add 5 of the 21 focusables and carry no page-specific meaning. The existing implementation already omits the rail. |
| The free 96 × 96 chrome slot at x1528 | Reserved for Subscribe, which is **emulator-only** today (`binding.resultSubscribe.isVisible = isSubscribed != null && isLayout(EMULATOR)`, `ResultFragmentTv.kt:629`). Ship the slot empty or not at all. |
| 7a's bookmark and favourite cells | Real features, but **drawn as undifferentiated 32 px `--mut` squares** (`.gl2`) — the board does not say which glyph is which, nor whether either shows a selected state. Needs a glyph and a state decision. |
| `.art` hue-rotate filters `v2`–`v5` on the avatars and posters | Pure mockup variety. No data. |
| The trailing `…` typed into both plot strings | A mockup artefact; the 2-line clamp supplies the ellipsis. |
| OFFLINE / PARTIAL on all three boards | **Nothing to bind to.** `isNetworkError` is read in exactly one place in the app and never on the result page. Without a connectivity source every OFFLINE strip degrades to ERROR. |

---

## 10. The closing notes, filtered to what constrains 7a / 7b / 7a-ii

Read from the `.note` block, lines 641–689 and 755–781.

1. **One action row at a fixed y512 and a fixed left origin** is the whole point. *"Nine independently
   `gone`/`visible` rail buttons are what make today's TV result page reshuffle under the user's
   thumb — the trailer column disappearing shifts every control to its right
   (`fragment_result_tv.xml:307-542`)."* The labels therefore have to be short and bounded; see §9 for
   the two data changes that forces.
2. **The caption is two mono lines on purpose**, because the bound resource is `%s\nremaining`
   (`strings.xml:253` in the app tree; `:580`/`:581` in `:core:strings`) **and a one-line box clips it.**
3. **`live` is spent zero times on 7a and 7b.** Verified in §7.
4. **Three chassis forks are owed back to TV Search and TV Library**, not kept local: `.railc`'s cell
   gap (8 → 24 px), `.railc`'s brand mark (`--acc` → `--ink`), and `.rhd`'s alignment
   (`baseline` → bottom). The first two are why `NavRailDefaults` in code is wrong for these boards.
5. **Subscribe is deliberately omitted**; the free slot is at **x1528** (764 dp).
6. **The 560 side panel replaces every dialog this cluster touches** — the plot `AlertDialog`
   (`ResultFragmentTv.kt:880-898`), the full-screen source picker and the 400 dp episode overlay.
   "Full synopsis" and "Full cast" both open it.
7. **`--mut` was raised from `#686868` to `#858585`** for AA contrast. On 7a and 7b it carries the
   provider name in the chrome row. *"Every board here passed 0 off-grid, 0 clipped and 0 band
   violations while `--mut` was `#686868` … A colour moves no boxes, which is exactly why it survived
   every scanner."*
8. **Cast is on the first screen because the shipping TV app already shows it**
   (`ResultFragmentTv.kt:449, :929-936`, `fragment_result_tv.xml:634-661`, behind
   `show_cast_in_details_key`, default on). It takes the rail because it is about *this* title;
   the 240 px block is shorter than the 288 px poster row, **so the swap costs no geometry.**
9. **Three prerequisites for the cast rail:** per-person image mode (currently per-list from the first
   actor, `ResultViewModel2.kt:235, :297-300`); no actor id, so no person page; nothing bounds the
   list, so the rail shows seven and Full cast opens the panel.
10. **The avatar is a new 80 dp `:core:designsystem` component, deliberately not a `CardSize` rung** —
    *"a person is not media, and adding a square to the media ladder would let a poster be drawn
    square."* Already built.
11. **A rotated box can never land on the grid.** The `.chk` glyph fix (rotate the pseudo-element, not
    the measured element) is 7c's, but it is a general rule and *"the second reason the icons rule
    gives for never hand-drawing a glyph in Compose."*

### Design System v2 values these boards reference

- **Focus:** ring = 4 px stroke + 4 px offset = 8 px bleed per side → **2 dp + 2 dp = 4 dp**. Band
  **x 56–1864, y 56–1024** → 28–932 / 28–512 dp. Gap between focusables **≥ 24 px / 12 dp**. Gap above
  a lifting row **32 px / 16 dp** (24 of clearance + the 8 it rises). Lift is **8 px / 4 dp, not 12**.
- **Three target classes:** icon-only = a 96 × 96 px (48 dp) cell; text button = 64 px (32 dp) tall ×
  ≥ 240 px (120 dp) wide; chip = a 48 px visual centred in a 96 px cell, ring on the cell.
- **Below-the-fold peek:** at most one row of cards may cross y1024 — which is how 7a-ii's related rail
  is justified.
- **Type ramp** (TV px / line box / TV dp): display 88/120/44 · h1 **64/88/32** · rail-title
  **40/56/20** · body **28/40/14** · label & card-title **24/32/12** · meta mono **24/32/12** ·
  caption sans 20/**32**/10 · caption mono 20/**24**/10. Ink is Manrope **1.30×**, JetBrains Mono
  **1.182×** — which is why the two caption rungs differ: at 20 px Manrope inks 26.0 and cannot live
  in 24, mono inks 23.64 and can.
  **Every text element on these three boards is on a published rung.**
- **Card ladder, 2:3:** poster-lg `192 × 288 px` = **96 × 144 dp**; poster-md `160 × 240` = 80 × 120;
  poster-sm `128 × 192` = 64 × 96. 7a-ii uses **poster-lg**. The banned `320 × 180` / 160 × 90 rung
  appears nowhere.
- **Semantic colour:** OK = filled ink glyph, no hue. Failed = hollow muted glyph + the reason in
  words. Download progress = **ink**, not live. Selected = `raised` fill + `ink` label + `muted`
  marker. Disabled = 40 % opacity — but *"per the project rule the token wins"*, so the kit uses
  `muted` / `raised`.

---

## 11. Could not establish

- Whether the hero is translated or removed between 7a and 7a-ii (§4.6).
- Whether 7a's chrome row is pinned, scrolled away or simply dropped on 7a-ii, and whether BACK works
  there (§4.6).
- What binds the `TMDB` leg of the cast header's count line (§9).
- Whether certification (`TV-MA`, `PG-13`) and status (`Ongoing`) have any field on the detail state.
- Which specific glyph belongs in each of 7a's three chrome cells, and whether bookmark/favourite show
  a selected state. The board draws undifferentiated `--mut` squares.
- The total focusable count on 7a-ii. The board states 21 for 7a and "identical to 7a" for 7b, but
  gives no total for 7a-ii. Counting the drawn elements gives 21 (5 nav + 7 cast + Full cast +
  7 posters + See all), but the board does not confirm it.
- Whether the see-all cards' 152 px width is intentional or a drawing slip, given that it is not a
  rung and `SeeAllCard` cannot express it (§8).
