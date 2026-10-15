# Dimension Scale — every dp is a multiple of 4

**All dimension values must be a multiple of 4 dp.** No exceptions for "it looked right at 26".

This is the step `CloudStreamDimens` (`:core:theme`) is built on. 34 of its 37 tokens are multiples
of 4 — `ListRowHeight` 48, `ChipFocusCell` 48, `ChipVisualHeight` 24, `ChipGlyphSize` 16,
`SidePanelWidth` 280, `FocusBandInset` 28 — and the three that are not are all ring and stroke
geometry, covered below: `Hairline` 1, `FocusRingStroke` 2, `FocusRingOffset` 2. Anything else off
that step is drift, not a decision.

The numbers come from a 1920x1080 Android TV artboard where **artboard px / 2 = dp**, which is why
the artboard works in multiples of 8. Re-derive when moving from a mockup: halve it, never
transcribe it.

## The one real exception: hairlines

Border and stroke *weights* are not layout dimensions and do not belong on the grid — a 4 dp
border is a slab. Three tokens are off the step, and all three are the focus ring's own geometry:
`CloudStreamDimens.Hairline` (1), `FocusRingStroke` (**2**) and `FocusRingOffset` (**2**). Their sum
is `FocusRingBleed` (4), which *is* on the step and is how much room a focused element needs outside
its box. Use them; do not invent new ones.

The artboards agree with the tokens, at the usual `artboard px / 2 = dp`: the search cluster draws
`outline: 4px solid var(--ink); outline-offset: 4px` on a focused field, which is 2 dp of stroke at
2 dp of offset.

A stroke is exempt **only in its own axis**. A 4 dp progress rule may be 4 tall, but the edge it
sits on is still on the step.

Everything that occupies space — widths, heights, paddings, gaps, radii, icon and touch sizes —
is on the 4 dp step.

## Order of preference

1. **Use the token.** If `CloudStreamTheme.dimens` (or `CloudStreamTheme.dimens`) already has the value
   with the right meaning, reference it — do not restate the number.
2. **If you cannot reach the token, restate the number and name it.** A plain `object` has no
   composition to read `CloudStreamTheme.dimens` from, which is why `XxxDefaults` objects hold
   literals. That is fine, but the KDoc must say which token it mirrors, so the two stay traceable:

   ```kotlin
   object FullScreenEdgeDefaults {
       /** Visible height of the tab. Mirrors `dimens.largePadding`. */
       val TabHeight: Dp = 24.dp
   }
   ```
3. **Only then invent a value** — a multiple of 4, in an `XxxDefaults` object
   (see `compose-defaults-object-convention`), never inline at the call site.

## Where off-grid values come from

Almost always from a mockup. A design tool emits whatever pixel value someone dragged to, and the
number gets transcribed into Kotlin verbatim: `.tab { height: 26px }` becomes
`val TabHeight = 26.dp`. **Re-derive dimensions when moving from a mockup to code.** The mockup
decides proportion and hierarchy; the scale decides the number.

Round to the nearest multiple of 4 that preserves the intent:
- 26 → 24 (a box) — not 28, unless the content genuinely does not fit
- 18 → 16 or 20, depending on whether it sits beside 16s or 20s
- 3 → leave it, if it is a stroke weight. `CloudStreamDimens.Hairline`, `FocusRingStroke` and
  `FocusRingOffset` are the three the theme names; a fourth wants a reason.

## What the grid does not govern

Two things, and they are narrow:

- **Intrinsic text widths.** A chip's width comes from its string, as does the x of every chip
  after it — that is `wrapContentWidth` in a `Row` with `ChipRowSpacing`. Nobody types those
  numbers, so the rule does not reach them.
- **Stroke weights, in their own axis.** See above.

## Legacy XML dimensions in `:app`

`app/src/main/res/values/dimens.xml` and the 145 layouts predate this rule and are full of values
that break it. They are **not** precedent, and they are not a cleanup task either — they will
leave with the views they belong to as the Compose migration proceeds. Do not "fix" them
piecemeal; a dimension change in a legacy layout reflows a screen nobody is looking at.

## Review checklist

- Any new `Dp` literal — is it a multiple of 4 (or a border token)?
- Does a `dimens` token already mean this? Then use it.
- Is the literal in an `XxxDefaults` object rather than inline?
- Does its KDoc name the token it mirrors, if it mirrors one?
