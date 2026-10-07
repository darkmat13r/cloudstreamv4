---
name: cloudstream4-type-metrics
description: "The measured Manrope / JetBrains Mono line boxes for CloudStream 4, why ink and not the content box is the clipping test, and the layout traps behind most off-grid geometry"
metadata: 
  node_type: memory
  type: project
  originSessionId: 5b61a396-8d1c-42f6-9a0a-84375830036f
  modified: 2026-09-13T03:29:12.367Z
---

## There are two heights, and only one of them clips

- **Ink** — `measureText().actualBoundingBoxAscent + ...Descent`, the glyph bounding box. **This is
  the clipping test.** Manrope **1.30x** the size at its heaviest weight; JetBrains Mono **1.182x**.
  Ink grows with font weight, so measure at the heaviest weight you ship.
- **Content box** — what an inline span's `getBoundingClientRect().height` returns, i.e. the
  browser's own line reserve. Larger: Manrope ~1.36–1.375x, mono ~1.29–1.30x. A line box smaller
  than the content box only produces **negative half-leading, which does not clip anything**.

Measuring the content box and calling it the clipping test is a real trap — I did it once and
published it. It over-reports, and acting on it would have forced Manrope 24 out of its 32 box into
40, adding 8px to every card title and list row in the app to fix a problem that does not exist.

## The ramp, measured (artboard px; dp = px / 2)

| family | size | line box | content box | worst ink | verdict |
|---|---|---|---|---|---|
| Manrope | 88 | 120 | 120 | 114.4 | ok |
| Manrope | 64 | 88 | 87 | 83.2 | ok |
| Manrope | 40 | 56 | 55 | 52.0 | ok |
| Manrope | 28 | 40 | 38 | 36.4 | ok |
| Manrope | 24 | 32 | **33** | 31.2 | **ok — content box overflows, ink fits. Do not "fix".** |
| **Manrope** | **20** | ~~24~~ **32** | 27 | **26.0** | **the one clipping rung — 20/24 is invalid** |
| Mono | 24 | 32 | 31 | 28.37 | ok |
| Mono | 20 | 24 | **26** | 23.64 | **ok — content box overflows, ink fits. Do not "fix".** |

**Manrope 20 has no valid 24 box; it is 20/32.** Mono 20/24 is fine. That is why the same size needs
two different boxes in the two families, and why the caption rung is two rows in the design system
and two constants (`CaptionLineHeight` 16.sp, `CaptionMonoLineHeight` 12.sp) in `:core:theme`.
Never derive a box from a ratio — read it from this table.

## Two traps that produce most off-grid geometry

**1. Centring.** A small box centred in a bigger one is how an on-grid value yields an off-grid
position. A 24px line box in a 32px chip sits at +4. An 8px dot in a 32px row sits at +12. A 40px
title baseline-aligned beside a 24px count puts the count at a fraction. The fix is never to nudge
the offset — it is to make the child's box equal the parent's, so there is no offset: a chip's line
box equals its own height, a pager row's height equals its label's line box, a rail header's two
children both carry the 56 box (which is what Compose's `CenterVertically` does; `Alignment.Baseline`
has the same defect as CSS baseline).

**2. A bound value and a literal do not lay out the same.** In `.dc.html`, a literal string is an
inline text node whose rect is a font metric, not a layout box — nothing measures it and nothing can
put it off the step. An interpolated `{{ value }}` is wrapped in a **block**, which takes a real box
and gets centred. The same chip measured correct with the text typed in and wrong with it bound.
**A mockup measured only with literal content has not been measured.**

Two more, both confirmed: **a rotated box can never land on the grid** (a 20px square at 45deg
measures 28.28 at a fractional origin) — a second reason not to hand-draw glyphs, see `icons.md`;
and **a 4px rule breaks the 8px rhythm of everything beneath it** — the hairline exemption is for
the stroke's own axis, not for its contribution to a stack, so a progress rule inside a card stack
is 8, not 4.

## The scanner has to be checked too

Three rules must be right or it lies in both directions:
- **Clipping** = ink vs line box. Not `scrollHeight`, not the content box.
- **Focus band** = tested on the **element box** (56..1864 x 56..1024), never on the ring. The ring's
  8px bleed belongs in the 48..56 reserve, so a ring at x48 or x1872 is correct. Testing the ring
  flags every correctly placed edge element — it reported 16 false violations across five files.
- **Intrinsic width** is excluded by asking whether a `width` was **declared**. `min-width` is not a
  width (the label can be wider), and a flex item's `display` is blockified so `inline-flex` does
  not survive to `getComputedStyle`. Both obvious heuristics are wrong.
- Skip `display: inline` elements entirely — their rects are font metrics, not layout boxes.

A scanner that reports 99 defects of which 90 are its own is worse than none.

See [[cloudstream4-design-state]] for the full procedure and [[cloudstream4-pending]] for which
files still owe corrections.
