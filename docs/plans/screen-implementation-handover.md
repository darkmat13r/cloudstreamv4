# CloudStream 4 — screen implementation handover

**This document is a prompt.** Read it start to finish before touching anything, then work the
phases in order. It is written for a session with no prior context.

Your job, in one sentence: **migrate the existing code into the module graph, design every screen
that is not yet designed, implement all of them pixel-perfect for TV, mobile and desktop, and
cover each with Android UI automation.**

---

## 1. What you are inheriting

| | Status |
|---|---|
| `docs/plans/kmp-migration.md` | The module plan. **Phase 0 and half of Phase 1 are built.** |
| `:core:theme`, `:core:designsystem` | **Merged.** 44 files, compile for jvm + android. |
| `build-logic/` | **Merged.** `cloudstream.kmp.library`, `cloudstream.kmp.compose`. |
| `:core:screenshot` | Open in PR #2. Plugin does not render — see §9. |
| `.claude/rules/` | 13 rules, rewritten against this codebase. Read them. They bind you. |
| TV designs | **28 artboards across 6 files.** See §7. |
| Mobile designs | **None. Zero.** |
| Desktop designs | **None. Zero.** |
| Android UI tests | 4 files total, none of them UI. No Compose test or UIAutomator dependency exists. |

The app itself is unchanged: `:app` is still a single 67k-line module with 145 XML layouts and a
hand-rolled TV focus engine. Nothing has been migrated out of it yet.

---

## 2. The three sources of truth

**1. The designs** — Claude Design project *CloudStream 4 — Redesign (TV / Mobile / Desktop)*:

**Destination: `design/` at the repo root.** Every file below lives there, exported from the
project on 2026-10-14. Read and update the `.dc.html` files in `design/`; they open in a browser,
with `support.js` beside them.

The origin is the Claude Design project,
https://claude.ai/design/p/a9ab3c83-bcc5-4ef4-854b-846c95da8438. Per file, append `?file=<name>`,
e.g. `?file=TV+Home.dc.html`. A change made only in the project is not in `design/` until someone
re-exports it.
A second Claude Design project, *Project file storage*,
https://claude.ai/design/p/c5fd7e44-70f5-41b6-8a48-e850eb88334b, holds the same 13 files as
`design/` (12 `.dc.html` + `support.js`, every size identical, checked 2026-10-15); it is a copy, not a newer version.

| File | Contents |
|---|---|
| `Design System v2.dc.html` | **Authoritative.** Tokens, type ramp, card ladder, state contract. |
| `Components.dc.html` | Shared kit at TV scale + the engineering-prerequisite register. |
| `TV Home.dc.html` | Shell focus contract, rail focused, hero, scrolled |
| `TV Search.dc.html` | Results, partial failure, history, source picker |
| `TV Search Suggestions.dc.html` | Suggestions while typing, nothing matches, no results, Quick Search answering / empty |
| `TV Library.dc.html` | Grid, provider unreachable, empty page, sort panel, provider panel |
| `TV Episodes Downloads.dc.html` | Episode panel, episode-row board, downloads ×3 |
| `Desktop Downloads.dc.html` | `DS-18`. Downloads on the Mac: titles, multi-select and menu, confirm, one series, empty/offline/full, minimum window. 1 px = 1 dp |
| `Phone Downloads.dc.html` | `DS-18`. Downloads on phone and tablet: titles, swipe, choosing, one series, the verb sheet, empty/offline/full, two-pane tablet. 1 px = 1 dp |
| `Desktop Search.dc.html` | `DS-22`. Search on the Mac: recent searches, results by source, a partial search, the sources panel, suggestions while typing, no results, minimum window. 1 px = 1 dp |
| `Desktop Library.dc.html` | `DS-22`. Library on the Mac: a list, a sync that failed, an empty list, Sort, the library's source, moving a card, minimum window. 1 px = 1 dp |
| `Phone Search Library.dc.html` | `DS-22`. Search and Library on phone (sheets, chips, three-column grids) and tablet (rail, six columns). 1 px = 1 dp |
| `TV Detail Player.dc.html` | Show detail, movie detail, link loading, player HUD, selectors |
| `TV Onboarding Extensions.dc.html` | First run, first run offline, extensions ×2, account select |
| `Design System.dc.html`, `TV Directions.dc.html`, `TV Screens.dc.html` | **Superseded.** Do not build from these. `TV Screens` still contains the banned 320×180 rung. |

**2. `:core:theme` and `:core:designsystem`** — the design expressed as code. If a design file and
a token disagree, the token wins and the design file gets corrected.

**3. `.claude/rules/`** — 13 rules. `dimension-scale.md`, `ui-components.md`,
`kotlin-compose-quality.md`, `screenshot-testing.md` and `icons.md` are the ones you will hit
hourly.

There is also a fourth, worth reading once: the four cluster workflows dumped their source
evidence — every claim with `file:line` — into task outputs indexed in the project memory file
`cloudstream4-cluster-specs.md`. When you need to know what the app actually does today, read
those before re-deriving it.

---

## 3. Order of work — do not reorder this

### Phase A — migrate code into modules

Follow `docs/plans/kmp-migration.md` phases 2–4. Do **not** start screens first. Until
`:core:data` exists, every migrated ViewModel still needs a `Context` and `commonMain` is fiction.

Order: `:core:common` → `:core:model` → `UiText` bridge → `:core:network` → `:core:data` →
`:core:notification` → the media stack (`:media:model` first, `:media:video` last).

Two rules from the plan that are not negotiable:

- **Every moved file keeps its fully-qualified name.** `makeJar` is a published API contract;
  third-party extensions compile against it. A package rename breaks every published extension.
- **Every `:core:*` and `:feature:*` declares a `jvm()` target.** It ships nothing. It exists to
  fail the build when `android.*` reaches `commonMain`. Without it the migration is cosmetic.

### Phase B — design what is not designed

**Before implementing any screen, its design must exist and must have been measured.**

- **Mobile: nothing exists.** Every mobile screen must be designed first.
- **Desktop: no artboards exist.** The owner chose Apple's principles over the design system's tokens
  instead of drawing them first: see `docs/plans/desktop-design.md` (2026-11-02).
- **TV: 28 artboards exist**, but not every surface. The inventory found 67 surfaces worth
  designing; 28 are drawn. If the screen you are about to build has no artboard, **draw it
  first** — do not improvise it in Compose.

Mobile and desktop are not TV rescaled. TV is 10-foot D-pad; mobile is touch; desktop is pointer
and keyboard with a resizable window. The card ladder, the target classes and the focus model all
change. Derive each from `Design System v2` deliberately and write down what changed and why.

Two IA divergences are already decided and must be honoured: **mobile puts the episode list on
the title detail page** while TV keeps it behind a selector, and **TV never uses a bottom sheet**
— it uses the 280 dp side panel.

### Phase C — implement screens

One vertical per PR, using the recipe the seven migrated settings screens already prove:

1. `State` / `Action` / `Effect` + ViewModel → `feature/x/commonMain`, using
   `StateContainer` / `EffectContainer` / `ActionHandler` by delegation.
2. Screen composable → `feature/x/androidMain`. Reusable pieces go to `:core:designsystem`.
3. Glue: `class XFragment2 : BaseComposeFragment(), Screen by XScreen`.
4. Repoint **one node** in `res/navigation/mobile_navigation.xml`.
5. **The old fragment stays.** That is the rollback lever. Delete it one release later.

Suggested vertical order, cheapest blockers first — search (`SearchViewModel` has 4 android
imports and **zero** `R.string`), then home (5 imports, zero `R.string`), then library, downloads,
account + setup, extensions, result, player.

### Phase D — Android UI automation

Every screen ships tests before its PR merges. Three categories, all required:

- **Happy path** — the screen does its job: content loads, the primary action works, navigation
  lands where it should.
- **Unhappy path** — every state the surface owes. `UiState` has six for a reason: `Loading`,
  `Content`, `Empty`, `Error`, `Offline`, `Partial`. A screen with an untested `Error` is not
  done. Assert the state's **action is reachable**, not merely that it rendered.
- **Edge cases** — the ones this app actually hits: a provider that times out mid-list, a title
  with no episodes, a download that dies past 50 MB, a resume position of 0, a season with one
  episode, a 40-episode list, text long enough to ellipsize, and **D-pad traversal on TV** —
  every focusable reachable, no dead ends, no traps.

The infrastructure is `INF-10`: `cloudstream.kmp.device-test`, `:core:devicetest`, and
`SearchScreenTvDeviceTest` as the template. The gate is in `docs/HANDOVER.md`.

---

## 4. Workflow discipline

**Break every big task into small tasks, and give each small task its own workflow that takes it
end to end.** Do not run one workflow for a whole phase.

A task is the right size when one workflow can carry it from reading the existing code to a
verified, building result. "Migrate `:core:network`" is a task. "Migrate the media stack" is not
— it is six.

Shape each workflow as: **read the real code → design or solve → implement → adversarially
verify**. The verify stage is not optional and must be able to reject the implement stage. When a
stage produces findings, feed the findings forward, not the assumptions.

Between workflows, read the result and decide the next one yourself. Do not chain a whole phase
unattended.

---

## 5. The geometry contract

The TV artboard is 1920×1080. **Artboard px ÷ 2 = dp** (960×540 dp at xhdpi). Every artboard
dimension is a multiple of 8 precisely so the dp lands on the repo's 4 dp step. **Halve it; never
transcribe it.**

Six rules, every one of which was found by measuring rendered output rather than by reading the
source. Each is already encoded in `CloudStreamDimens`:

1. **A hairline never consumes layout.** A 1px border shifts the padding box by 1 and puts
   everything inside off the step. Draw it; do not pad with it.
2. **A focus ring is not a border.** 4 dp stroke at 4 dp offset, drawn *outside* the box, and it
   must not change the measured size. `Modifier.tvFocusRing` is draw-only for this reason — and
   **no ancestor may clip**, or the ring is silently cut off.
3. **The focus lift is 4 dp** (8 artboard px), not 6. A lifting row sits 16 dp below what
   precedes it: 12 of clearance plus the 4 it rises.
4. **Centring an odd size in an even cell lands off the step.** Chip visuals are 24 dp and glyphs
   16 dp so they centre cleanly.
5. **A text stack must total a multiple of 8 dp to centre in a 48 dp row.** Title 16 + gap 4 +
   subtitle 12 = 32, centring at +8. A 2 dp gap makes it 30 and centres at +9.
6. **Intrinsic widths are exempt.** A chip's width comes from its string — `wrapContentWidth` in a
   `Row`. The step governs what a person writes, not text measurement.

Other standing constraints: focus band 28 dp inset on TV; ≥12 dp between focusables; three target
classes (icon 48 dp cell, text button 32 dp × ≥120 dp, chip 24 dp visual in a 48 dp cell); the
media ladder is closed and `CardSize` has an `internal` constructor so an invalid size cannot be
expressed; `live` `#CD3D4B` is playback state only and is **never** a button.

---

## 6. Verification — measure, do not look

This is the most expensive lesson in this project. Read it twice.

A scanner that checked every px value **declared in CSS** passed three design files that between
them contained **50 off-grid boxes**. The values were right; where they *landed* was not, because
borders, transforms and centring move things. A second pass that measured `getBoundingClientRect`
on every painted element found all 50. A third pass extended it to *text* boxes and found 15 more.

So:

- **For designs**: publish, render, and measure computed boxes — off-grid, focus band, clipped
  text (`scrollWidth` vs `clientWidth`), collisions. Not screenshots. Not eyeballs.
- **For Compose**: pixel-perfect means asserted, not assumed. Use screenshot tests where they
  work, and `SemanticsNodeInteraction` bounds assertions where they do not.
- **Never mask a failing check to make it green.** `failOnNoDiscoveredTests = false` makes
  `:core:screenshot` pass by rendering nothing. A green check that proves nothing is worse than a
  red one that tells the truth.

Same discipline for claims: when a subagent reports "compiles" or "renders", run it yourself
before repeating it. Three separate agent claims in this project were wrong.

---

## 7. What is drawn, and what is not

**TV — 34 artboards drawn and measured clean** (0 off-grid, 0 band violations, 0 clipped text).
Counted from the `shotn` labels in each file on 2026-10-15:

Home ×4 · Search ×4 · **Suggestions/Quick Search ×5** · Library ×5 · Episodes/Downloads ×5 ·
Detail/Player ×6 · Onboarding/Extensions ×5

Two of those are variants rather than new screens — `7a-ii` is `7a` scrolled, and `8a-ii` is `8a`
offline — so 32 distinct screens. The old count of 28 is this list without the
five Suggestions boards and without `7a-ii`. `TV Screens.dc.html`
holds five more (`2a`–`2e`) and is **superseded**: §2 lists it, and it still contains the banned
320×180 rung.

> **Two clusters are both numbered 8.** `design/TV Search Suggestions.dc.html` uses `8a`–`8e` for
> suggestions, no-results and Quick Search; `design/TV Onboarding Extensions.dc.html` uses `8a`–`8d`
> for first run, extensions and account select. **Always name the file with the id.** An unqualified
> "artboard 8c" means "Search — no results" in one file and "Extensions — browse a repo" in the
> other.

**TV — drawn since this section was written.** Search suggestions, no-results and the Quick Search
panel were listed here as "specced but never drawn". They are drawn:
`design/TV Search Suggestions.dc.html` holds five artboards — 8a suggestions while typing, 8b
nothing matches, 8c search no-results, 8d Quick Search answering, 8e Quick Search empty — and
reports *"0 off-grid, 0 clipped, 0 overflowing, 0 band violations across 312 boxes"*. The file also
records that the spec they came from **failed adversarial verification on all three screens, with 28
violations**, so build from the artboards and not from the spec. Found while implementing 8c
(`KMP-12` step 2); this section said no when the answer was yes.

**TV — not started.** Settings (7 screens are already Compose and want re-skinning, not
redesigning), subtitle and audio panels as standalone boards, player quality profile, and the
long tail of the 67-surface inventory.

**Mobile — nothing. Desktop — nothing.**

Two design-file corrections are owed and recorded in memory: `TV Home` and `Design System v2`
have not had the six computed-box corrections applied, and `TV Screens` still contains the banned
320×180 rung.

---

## 8. Open decisions — do not resolve these silently

1. **The 10 sp caption.** The ramp is 88→44 sp, 64→32, 40→20, 28→14, 24→12, 20→**10**. 10 sp is
   below Android TV's 12 sp minimum. Raising it reflows every artboard. The KDoc carries the
   question.
2. **The nav rail is spelled three ways** across the six TV files — gap 8 vs 24 artboard px. All
   are on-grid, so none is a defect; they are different chassis. The argument for 24 is that nav
   cells are focusables and the ≥24 px minimum has no exemption. Adopting it re-cuts four files.
3. **The icon pack is not chosen.** 80 `ic_baseline_*.xml` were exported by hand and
   `:core:designsystem` draws its own chevron. `icons.md` forbids drawing more. Choosing a pack
   is a dependency decision.
4. **Six duplications inside `:core:designsystem`**, from four agents working blind. Two matter:
   two progress rules with *different geometry* (2 dp on a `bg`@0.6 track vs 4 dp on a `line`
   track, and cards do not use the primitive — one number is wrong), and two text buttons whose
   ghost/secondary variants render differently.

---

## 9. Blockers you will hit

**Compose preview screenshot testing does not render on this toolchain.**
`com.android.compose.screenshot:0.0.1-alpha16` + AGP 9.1.1 + Gradle 9.4.1: tasks register,
previews compile, `@Preview` survives into bytecode, the engine resolves, the task reports
"1 test, 0 failures" — and writes no images. A preview containing only `BasicText("hello")`
renders nothing either, so it is the plugin. It also **does not support KMP modules at all**
(applied to `:core:designsystem` it contributes zero tasks). Roborazzi is the realistic
alternative and supports KMP; it is a dependency decision.

**The app cannot do anything on a fresh install.** `PREBUILT_REPOSITORIES` is read in eight places
and written in none, and the add-repository button is commented out in four layouts. Any first-run
work is blocked on seeding or fetching an index.

**Many designed facts do not exist in the model yet.** Per-source resolution state, typed failure
reasons, download rate and ETA outside the notification layer, queue position, per-episode
download state, `lastSyncedUnixTime`, connectivity. Each drawn screen's closing note lists its own
prerequisites with `file:line`. Read the note before implementing the screen — some screens are
mostly blocked.

---

## 10. Standing rules worth repeating

- No unsolicited changes to existing code. Suggest, confirm, then change.
- Catalog and dependency additions need explicit approval, with what / why / alternatives / size.
- Kotlin file ≤ 300 lines, composable screen ≤ 250, ViewModel ≤ 200, function ≤ 40, params ≤ 5.
- No magic numbers or strings. Dimensions, colours, shapes and type come from the theme.
- User-facing text is a string resource. 62 Weblate locale dirs; **do not** migrate `res/values*`
  into `composeResources` — that is deferred until Weblate is repointed.
- If the target is an upstream PR rather than this fork, `shared/readme.md` carries an explicit
  maintainer position on AI groundwork for the Compose rewrite. Coordinate first.

---

## 11. Commands

```bash
# modules
./gradlew :core:theme:compileKotlinJvm :core:designsystem:compileKotlinJvm
./gradlew :core:theme:compileAndroidMain :core:designsystem:compileAndroidMain   # NOT compileKotlinAndroid
./gradlew :app:compileStableDebugKotlin

# screenshots (blocked — see §9)
./gradlew :core:screenshot:updateDebugScreenshotTest
./gradlew :core:screenshot:validateDebugScreenshotTest
```

Project memory lives in `~/.claude/projects/-Volumes-Projects-CloudStream-app/memory/` — start
with `MEMORY.md`, then `cloudstream4-design-state.md` (the geometry contract and how to verify),
`cloudstream4-cluster-specs.md` (where the source evidence is) and `cloudstream4-pending.md`
(what is owed).
