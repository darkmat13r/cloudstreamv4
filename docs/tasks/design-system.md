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

**Status:** in pr #27

`#686868` measured **3.77 / 3.43 / 3.17** against Black, InsetSurface and RaisedSurface — below the
4.5:1 AA floor on all three, in a role carrying 10–12sp secondary text. `#858585` measures
**5.69 / 5.18 / 4.78** and is the smallest value clearing the floor everywhere: `#808080` misses on
RaisedSurface by 0.03.

Kept here as the worked example of what `DS-01` has to do for every pair.

---

## DS-03 — Accent role and dynamic-colour hook in `:core:theme`

**Status:** open · **Blocks:** `DS-05`

`:core:theme` has no accent role: its KDoc says the primary button is an `ink` fill with a `bg`
label, *"never a generic accent"*. But `:shared` exposes a user-selectable primary colour and
Material You. Something has to carry those, or adopting `:core:theme` drops them.

- [ ] Decide whether the new system keeps a user-selectable accent at all — it may be a deliberate
      simplification rather than a gap
- [ ] If it does: add the role, and a dynamic-colour hook that is a no-op off Android
- [ ] If it does not: that is a product decision and belongs in `decisions.md`, not here

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

**Status:** blocked by `DS-01` and `DS-03`

Seven live nav-graph nodes: `SettingsPlayer2`, `SettingsUI2`, `SettingsGeneral2`,
`SettingsProviders2`, `SettingsUpdates2`, `SettingsAccount2`, `SettingsFragment2`.

Per screen, not in one PR. Each PR:

- [ ] states which theme modes still work on that screen after the change
- [ ] states that it is unguarded by screenshots, while `DEC-10` is open
- [ ] does not change `SearchableSettings` or `Screen` FQNs — they are frozen into the extension ABI
      (see `KMP-06`)

---

## DS-06 — Icon pack

**Status:** blocked by `DEC-11`

Until a pack is chosen: reuse an existing drawable, or state in the PR that the icon is blocked.
Do **not** add a new `ic_*.xml` and do not draw a glyph in Compose — *"temporary glyphs are
permanent; that is how the 80 accumulated."*

---

## DS-07 — Off-grid dimensions in the vendored Mihon tree

**Status:** open, low priority · related to `KMP-06b`

Seven layout `Dp` literals off the 4dp step, measured:

| File | Value |
|---|---|
| `PreferenceGroupHeader.kt:19` | `top = 14.dp` |
| `Pill.kt:34` | `.padding(6.dp, 1.dp)` |
| `SettingsItems.kt:64` | `Vertical = 10.dp` |
| `SettingsItems.kt:221` | `spacedBy(2.dp)` |
| `Slider.kt:33` | `DpSize(4.0.dp, 34.0.dp)` |
| `Slider.kt:38` | `height(10.dp)` |
| `AppBar.kt:125` | `elevation = 3.dp` |

Plus three off-ramp `sp` values (`AppBar.kt:283,306` 18sp; `BasePreferenceWidget.kt:127` 16sp).

**Do not fix these as part of a move.** They are upstream's, editing them grows the Apache §4(b)
modification surface, and `KMP-06b` keeps them in their own module precisely so a different
standard can apply there. Listed for visibility only.
