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
