---
name: cloudstream4-decisions
description: Product decisions locked for the CloudStream 4 redesign, and the geometry contract they sit on
metadata:
  type: project
---

Decisions the user made on 2026-09-12 after the P0 spec round. Treat as settled.

1. **Missing signals: design the states, ship a prerequisite register.** States are drawn even where
   the app emits no signal yet; each is listed as a named engineering change (typed `loadLinks`
   failure + per-provider reason, `Resource.Failure` on `_episodes`, per-episode download presence,
   `bytesPerSecond`, per-repo plugin counts, single-repo `SyncRepo` commit, `ConnectivityFlow`,
   `ResultEpisode.resolveError`). Design leads, engineering follows.
2. **Bottom sheets: mobile yes, TV never.** TV uses an **animated side panel** — 560px, slides in
   from the right, 240ms, content behind scrimmed but still mounted, focus trapped, BACK returns
   focus to the control that opened it.
3. **Mobile puts the episode list on the title detail page**; TV keeps it behind a separate
   selector. This is a deliberate IA divergence and must be documented as such, not hidden.
4. **TV home shrinks the hero** (760 → ~600px) to keep three rails plus a peek once the rail header
   grows to hold a 96px "See all" target.

**The geometry contract that most defects traced back to** — a focus ring is a 4px stroke at 4px
offset, so it occupies **8px outside** its target. Usable focus band on a 1920×1080 TV artboard is
therefore **x 56–1864, y 56–1024**, not 48. Gaps between focusables ≥ 24px. Minimum target 96px TV /
48dp touch, with the ring drawn on the hit box, not the visual.

**Corrected:** `320×180 px` is banned — it is `160×90 dp` and 90 is off the 4dp step. Valid 16:9
rungs are only `384×216`, `256×144`, `128×72`. 2:3 rungs `192×288 / 160×240 / 128×192` all pass.
Rule: artboard px must be a multiple of **8** so dp lands on 4.

Part of [[cloudstream4-redesign]]. See [[cloudstream4-inventory]] for the surface list.

**Two further decisions made while drawing Home (2026-09-12):**
5. **Poster rails are art-only; the title reveals on the focused card** as an overlay that does not
   move the layout. A rail with a persistent title is 350px, so three plus gaps is 1098 against a
   968px budget. Art-only is 304 and three fit at 960. Reversible — the cost is one rail.
6. **"See all" is the trailing card of each rail, not a header button.** A 96px See-all target in the
   header is what blew the vertical budget; as a trailing card, D-pad RIGHT reaches it naturally and
   the header stays non-focusable. Also fixes a real bug: See-all is currently wired for phone only,
   so TV and the emulator draw a live-looking arrow that does nothing.

**Ownership and licensing, settled 2026-09-18:**
7. **This repo is a separate private personal project, not a fork.** The base code came from
   CloudStream, but there is no intention to contribute back upstream. Consequence:
   `shared/readme.md`'s standing instruction from LagradOst ("in no part do I want AI to lay the
   groundwork" for the Compose rewrite) does **not** bind this work. Note that GPL-3.0 §5 and
   Apache-2.0 §4 obligations trigger on *distribution*, so while this stays private they largely
   do not fire — but they return the moment it is distributed. This partially settles plan
   decision O1.
8. **The vendored `com.mihon` tree gets attribution fixed in its first PR**, at the module
   boundary: one `NOTICE` plus a module README naming Mihon, the URL, the Apache-2.0 licence, that
   the files are modified, and the upstream commit if it can be reconstructed. Not 29 file headers.
   Measured: all 16 files checked differ from upstream (`AppBar.kt` by 157 lines of 405), none
   carries a change notice, the vendored LICENSE names no copyright holder, and there is no NOTICE
   anywhere in the repo. **Still unanswered: the 12 committed `productsans_*.ttf` faces**, whose
   provenance nobody has established and which `theme/Font.kt` builds all 15 Material3 roles from.
9. **`:shared` does not merge into `:core:designsystem`.** The Mihon tree gets its own module.
   `CloudStreamTheme` already exists in both and resolves to two different functions — `:shared`'s
   installs `MaterialTheme`, `:core:theme`'s deliberately does not. Package names stay **byte
   identical** through any move, because three `:shared` FQNs are frozen into the published
   extension ABI: `com/mihon/presentation/settings/SearchableSettings` (17 occurrences in
   `app/api/classes-jar.api`), `com/lagradost/cloudstream4/compose/Screen` (3) and
   `com/lagradost/cloudstream4/R` (1). `:app:checkExtensionApi` enforces it.
10. **New screens use the new design system and `:core:theme`, adopted per screen.** A light
    palette is added if required, and the light-scheme colour decisions go through the **Claude
    Design project**, not derived in code — `:core:theme` today has one fixed dark scheme, while
    `:shared` ships five theme modes (`Dark`/`Amoled`/`AmoledLight`/`Light`/`FollowSystem`), a
    user-selectable primary colour and Material You on API 31+.
11. **Strings flip to Compose-resources-as-source, 2026-09-18.** Reverses S1/S2/D4 in
    `docs/plans/kmp-migration.md` §3.4 ("Android res + Weblate stay the source"). Compose resources
    become authored and committed; Android res becomes the generated side, because `:app` still has
    ~145 XML layouts that structurally cannot read CMP resources.
    **Why the reversal is now sound:** the plan's stated reason for not flipping was that a first
    Weblate rescan would corrupt 61 values with a literal `"` across 11 locales, plus every
    leading-`@` value. That is a property of **translate-toolkit** — Weblate's reader, whose
    `CMPResourceUnit` overrides only writing while reading keeps Android semantics. It is *not* a
    property of the CMP format. This repo is not connected to upstream's Weblate (see decision 7),
    so the constraint no longer applies. Do not cite the 61-value corruption as a blocker again
    without checking whether Weblate is back in the loop.
    **Scale, measured:** 61 locale dirs, 734 default strings, 32,393 `<string>` elements, 73
    `<plurals>` / 183 `<item>`, 34 values containing `<b>`, 57 containing `@string/`, 3 `&lt;`,
    zero `<string-array>`, zero `translatable="false"`, zero CDATA.
    The `<b>` and `@string/` values are the ones that may not survive a round trip — they are an
    owner decision per value, not an average.
