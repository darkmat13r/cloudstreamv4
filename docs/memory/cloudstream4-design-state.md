---
name: cloudstream4-design-state
description: Where the CloudStream 4 design lives, what is drawn, and the rules any new screen must satisfy
metadata:
  type: project
---

Claude Design project **"CloudStream 4 — Redesign (TV / Mobile / Desktop)"**
`https://claude.ai/design/p/a9ab3c83-bcc5-4ef4-854b-846c95da8438`

Files, in the order they should be read:
- `Design System v2.dc.html` — **authoritative**. Geometry contract, 9 colour tokens, three type
  ramps, corrected card ladder, StateView contract. Supersedes `Design System.dc.html` (v0.1, kept
  for history; its 16:9 ladder is wrong).
- `Components.dc.html` — shared kit drawn at TV scale + the 8 engineering prerequisites table.
- `TV Home.dc.html` — **drawn and verified**: shell focus contract, shell with rail focused,
  Home hero, Home scrolled.
- `TV Search.dc.html` — **drawn and verified**: Search results, Search partial, Search history,
  Source picker side panel. Defines the shared chassis the rest of the P0 clusters reuse:
  chrome row 56..152 (field 200..1296 · voice 1320..1416 · Sources 1440..1864), chip row 176..272,
  rail pitch 360 (header 296..352, cards 392..632, next header 656..712, its cards 752..992),
  the 96-tall list row, and the 560-wide right panel.
- `TV Library.dc.html` — **drawn and verified**: grid, provider-unreachable, empty page, Sort side
  panel, Provider side panel. Grid is the 192x288 rung on a 240 x 328 pitch, 7 columns from x200
  (ends 1832), rows at 312 and 640 with a third at 968 below the fold. Built on the real model:
  `SyncAPI` providers, `WatchType` tabs, and `ListSorting`'s eight entries regrouped as four
  criteria x two directions (a flat eight does not fit a 560 panel and reads worse).
- `TV Episodes Downloads.dc.html` — **drawn and measured clean**: episode list panel, episode-row
  component board at both 472 and 1664 widths, downloads titles, one series, empty + offline +
  storage-full.
- `TV Detail Player.dc.html` — **drawn and measured clean**: TV show detail, movie detail, link
  loading, player HUD, playback selectors. Each artboard carries a six-row state strip beneath it
  (FOCUS / LOADING / EMPTY / ERROR / OFFLINE / PARTIAL) naming geometry for the states not drawn.
- `TV Onboarding Extensions.dc.html` — **drawn and measured clean**: first run, first run offline,
  extensions installed, browse a repository, account select.
- `TV Directions.dc.html`, `TV Screens.dc.html` — earlier exploration. Superseded; `TV Screens`
  still contains the banned 320×180 rung.

**Non-negotiables for any new screen** (all verified programmatically, never by eye):
- Focus band **x 56–1864, y 56–1024**. A ring is 4px at 4px offset = **8px outside** its target, so
  nothing focusable may originate outside that box. This is why content origin is 200, not 144.
- Every fixed dimension a multiple of **8 artboard px** so the dp lands on the repo's 4dp step.
- Gaps between focusables **≥ 24px**; minimum target **96px TV / 48dp touch**, ring on the hit box.
- TV type floor **20px**; sizes only from {88, 64, 40, 28, 24, 20}.
- `live #CD3D4B` is playback state only. Primary button is **white fill, black label**.
- Every surface owes five states; offline and partial are **banners over content**, not replacements.
- A StateView's action must sit **in the screen's focus order** — 18 of 20 audited screens mounted a
  Retry no D-pad move could reach. Prefer hanging it off a row that already exists (Search puts
  "Retry failed" at the right end of the chip row) over a band of its own: a full-width banner costs
  120px and pushed Search's second rail 56px past the band.
- **Three target classes, not one.** Icon-only = a 96×96 cell. Text button = 64 tall × ≥240 wide.
  Chip = a 40-tall visual centred in a 96-tall focus cell. The ring always draws on the cell.
- The focus band is measured on the **element box**; the ring's 8px bleed lives in the 48..56
  reserve, which is why the outer margin is 56 and not 48.
- Cards that lift on focus (`translateY -12`) need **24px** above the row, not 8 — otherwise the
  focused ring cuts into the rail header.
- **Line boxes, measured not assumed — and never from a ratio.** See [[cloudstream4-type-metrics]]
  for the table, which is the source of truth. Two numbers matter and only one clips: measured
  **ink** (Manrope 1.30x at its heaviest weight, JetBrains Mono 1.182x) must fit the line box; the
  browser's **content box** is larger and a line box under it is harmless. Manrope **88/120, 64/88,
  40/56, 28/40, 24/32, and 20/32** — Manrope 20 inks 26.0 and has no valid 24 box. Mono **24/32 and
  20/24**, which is why the same size takes different boxes in the two families. Never
  `line-height: 1`.
- **The lift is 8, not 12**, so a focused card stays on the 8px step. A lifting row therefore sits
  **32px** below what precedes it (24 of clearance plus the 8 it rises). Search rail pitch **352**
  (header 296..352, cards 384..624, header 648..704, cards 736..976); Library grid pitch **320**
  (rows 304, 624, peek 944).

**The five systemic causes of off-grid geometry** — every one found by measuring computed boxes,
none visible in the declared CSS:
1. **A 1px border consumes layout.** `border: 1px` shifts the padding box by 1, so everything inside
   lands off-step and a 560 panel becomes 561 at x1359. Hairlines are `box-shadow: inset 0 0 0 1px`.
2. **A transparent border is not a focus ring.** `border: 4px solid transparent` on a card pushed its
   badge to x212 and its progress bar to x204. Rings are `outline`, so the art box stays exact.
3. **The lift must be on the step** — 8, not 12.
4. **Centring an odd size in an even cell.** A 40-tall chip in a 96 cell lands at y204; a 24px glyph
   in a 96 cell lands at x92. Chip visuals are **48**, glyphs are **32** — both centre onto the step
   and both read better at ten feet.
5. **A 4px selection marker** shifted the 48px leading disc to x236. The marker is an **8px cell**.

**What the grid does NOT govern** — a chip's width comes from its string, and so does the x of every
chip after it. In Compose that is `wrapContentWidth` in a `Row` with 24dp spacing: no engineer types
those numbers, so the 4dp rule does not reach them. The grid governs every dimension a person writes
and the origin and size of every fixed-size element, not text measurement. Hairline rules (a 4px
progress bar, a 2px caret) are exempt in the axis of their thickness; their far edge is still on-step.

**How to verify** — render via `render_preview`, then in the browser **measure computed boxes via
`getBoundingClientRect`, never the px values declared in CSS**; the declared-value scanner passed
three files holding 50 off-grid boxes. The scanner's own rules have to be right or it lies in both
directions — it once reported 99 defects of which 90 were its own:

- **Clipping** = canvas `measureText().actualBoundingBox` ink vs the line box. Not `scrollHeight`,
  and not the content box, which over-reports.
- **Focus band** = the **element box** against x56..1864 / y56..1024, never the ring. The ring's 8px
  bleed belongs in the 48..56 reserve, so a ring at 48 or 1872 is correct.
- **Width** is checked only when a `width` is *declared* (walk the CSSOM). `min-width` is not a
  width, and a flex item's `display` is blockified so `inline-flex` never survives to
  `getComputedStyle` — both obvious heuristics are wrong.
- **x** is checked only for absolutely positioned elements anchored by `left`. A right-anchored
  auto-width box derives its x from its content, exactly as an intrinsic width does.
- **Skip `display: inline` entirely** — those rects are font metrics, not layout boxes.
- Percentage-width progress fills are continuous values, not layout dimensions.
- Also check for unbound `{{ }}` left in rendered text, and pairwise overlap of focusables (skipping
  anything behind a `.scrim`, since a side panel legitimately covers what it opens over).

**Measure with the bindings live.** A literal string and a bound `{{ value }}` do not lay out the
same, and a mockup measured only with literal content has not been measured. See
[[cloudstream4-type-metrics]] and [[cloudstream4-decisions]].
