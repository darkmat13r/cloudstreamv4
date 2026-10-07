# KMP — module migration

Carving `:app` into modules, restructuring `:shared`, and keeping iOS possible.

The plan is [`docs/plans/kmp-migration.md`](../plans/kmp-migration.md). This file is the work list;
the plan is the reasoning.

**The constraint behind all of it:** `app/build/classes.jar` is a binary contract that third-party
extensions compile against. It is guarded by `app/api/classes-jar.api` and `:app:checkExtensionApi`.
"No android import" does not mean movable — see `KMP-09` for the trap that caught two attempts.

---

## Merged

| ID | What | PR |
|---|---|---|
| KMP-M1 | `:core:common`, `:core:network`, `:core:data`, `:core:model` (B1–B3) | #4–#6, #14, #16, #19 |
| KMP-M2 | `:core:strings` generated from Android res | #17, #18 |
| KMP-M3 | `:core:http` over `app.baseClient`; ktor 3.6.0 | #10, #11 |
| KMP-M4 | `:core:storage` ports, Android actuals, persistent JVM stores | #20, #22 |
| KMP-M5 | `UiString` + formatter + plural guard | #21, #23 |

---

## KMP-01 — B4: `DdosGuardKiller` → `:core:network`

**Status:** done · #30 · **Size:** one PR

Merged: `DdosGuardKiller` moved to `:core:network`. Added 1 sentinel to
`cloudstream.extension-api-jar.gradle.kts`; `app/api/classes-jar.api` md5 unchanged before and
after (`48709d3ac28143a2fe251ca28bcf14c1`). Local run, not CI (see `INF-01`). The checklist below is
the original plan, left as written; ticked items are the ones #30's description evidences.

Body-only change first: a private header merge replaces the call to `:app`'s
`RequestsHelperKt.getHeaders`. Adds `:library`, coroutines and `annotation` to `:core:network`.

- [ ] Run the `$stable` scan before moving (see `KMP-09`)
- [ ] Replace the `getHeaders` call with a private merge
- [x] Move, verify javap identical, `checkExtensionApi` dump unchanged (md5 above)

---

## KMP-02 — B5: `SSLTrustManager`, `DownloaderTestImpl` → `:core:network`

**Status:** blocked — needs a security review first

A **trust-all manager becomes reachable from a shared module**. That is the whole reason this is
not KMP-01's sibling. `DownloaderTestImpl` additionally needs `newpipeextractor` (already in the
catalog).

- [ ] Security review: who can reach `SSLTrustManager` after the move, and is it still only
      reachable behind the same debug/dev gate as today
- [ ] Only then: move, verify

Optional. If the review says no, close this and say why.

---

## KMP-03 — B6: `TestingUtils` (+8 nested) → `:core:model`

**Status:** done · #31 · **Size:** one PR

Merged: `TestingUtils` moved to `:core:model` as a pure rename — 0 lines changed, identical
sha256 on both sides. Added **9 sentinels** to `cloudstream.extension-api-jar.gradle.kts`: the
outer class plus its 8 declared nested classes (13 lines, 4 of them comment). A tenth binary name,
`TestingUtils$Logger$Message$WhenMappings`, is in the dump but deliberately **not** sentinelled —
it is a compiler-synthesised switch map for `when` over an enum, not a name an extension can
reference. `app/api/classes-jar.api` md5 unchanged (`48709d3a`) before and after `makeJar`.
Local run, not CI (see `INF-01`).

Needs `:library` and coroutines, so **not** `:core:common` (dependency rule 2). Straightforward;
it was originally bundled with B2/B3 and did not ship with them.

---

## KMP-04 — B7: `fcast/Packets.kt` → `:core:common`

**Status:** blocked by `DEC-07`

Whole file, jvmCommonMain, because `PlayMessage` uses Jackson. `:core:common` gains the
serialization plugin, kotlinx-serialization and Jackson annotations — it has no dependencies today.
No `PacketsKt` facade exists, so the eight pure classes could go to commonMain if split.

---

## KMP-05 — B8: `APIRepository` → `:core:data`

**Status:** blocked by `DEC-08`

After `KMP-01`. Inverts `MainActivity.afterPluginsLoadedEvent` in the same PR, which **removes a
public synthetic accessor from the published jar**.

- [ ] Confirm the exact removed entries and list them in the PR body
- [ ] Get sign-off in that PR, not before

---

## KMP-06 — Restructure `:shared`

**Status:** open · **Size:** several PRs · **Partly blocked by** `DEC-09` · order is now 06c → 06b → 06d

The plan's "split `:shared` into `:core:ui` + `:core:designsystem`" is **stale**.
`:core:designsystem` already exists with the redesign system; merging the vendored Mihon tree into
it would collide on the name `CloudStreamTheme` — `:shared`'s installs `MaterialTheme`,
`:core:theme`'s deliberately does not.

**Measured facts this rests on:**

- 47 Kotlin files. Consumers: `:app` and `:desktopApp` only. `:core:theme`, `:core:designsystem`,
  `:core:screenshot` and `:library` reference it **zero** times in either direction.
- 83 import lines, 36 distinct symbols, 19 files. Top: `SearchableSettings` ×13, `Preference` ×7.
- All seven Compose settings screens are live nav-graph nodes
  (`app/src/main/res/navigation/mobile_navigation.xml`). **This module ships to users.**
- `:desktopApp` is one 60-line `Main.kt` rendering "Hello, World!", two buttons and a switch.
- The Mihon tree carries **seven off-4dp-step `Dp` literals** and ten hand-exported vectors, which
  would be instant violations of `dimension-scale.md` and `icons.md` in code nobody here wrote.

**Three `:shared` FQNs are frozen into the published extension ABI** — so package names must not
change through any move, and `:app:checkExtensionApi` enforces it:

| FQN | occurrences in `app/api/classes-jar.api` |
|---|---|
| `com/mihon/presentation/settings/SearchableSettings` | 17 |
| `com/lagradost/cloudstream4/compose/Screen` | 3 |
| `com/lagradost/cloudstream4/R` | 1 |

### KMP-06a — attribution, before anything moves

**Status:** done · decided 2026-09-18 (see `DEC-03`)

- [x] One `NOTICE` at the repo root plus a rewritten module README, naming Mihon, the URL,
      Apache-2.0, both copyright lines and that the files are modified
- [x] Per-file upstream mapping for all 29 files, measured by fetching each upstream file and
      diffing it
- [x] Not 29 file headers — one notice, as decided

**What the measurement changed.** All 29 files were fetched from Mihon `main` on 2026-10-07 and
diffed. Two claims in the original entry were wrong:

- *"`AppBar.kt` differs by 157 lines of 405"* — 157 is the **sum of both directions**. Ours is 405
  lines against upstream's 449: 100 upstream-only, 57 ours-only.
- *"All 16 vendored files checked differ"* — true of all 29, but **three differ in the `package`
  line and nothing else**: `material/Constants.kt`, `material/Pill.kt`, `material/Typography.kt`.
  Verified by diff: one line out, one line in. The NOTICE says so rather than implying more.

`material/SettingsItems.kt` is the honest example of real porting: `ImageVector` → Compose
`Painter`, Moko `StringResource` → Compose Resources, `Preference<T>` → `PreferenceData<T>`; 30
upstream lines removed, 46 added.

Three files are not same-name copies, which a reviewer would otherwise waste time on:
`PreferenceData.kt` ← upstream `Preference.kt` (rename), `presentation/Navigation.kt` ← upstream
`Navigator.kt` (one declaration extracted), `settings/Search.kt` ← upstream
`SettingsSearchScreen.kt` (substantial rework).

**Also established:** Mihon's `LICENSE` is the bare Apache-2.0 text naming no holder, exactly as our
vendored copy is — so that copy is faithful, not truncated. The copyright lines come from Mihon's
README: *Copyright © 2015 Javier Tomás* and *Copyright © 2024 Mihon Open Source Project*. Mihon
ships **no NOTICE file**, so §4(d) does not apply; and its sources carry **no per-file licence
headers** in any file sampled.

**Still unrecoverable:** the upstream commit the tree was taken from. It was not recorded when
vendored, and the comparison above is against a moving `main`. The module README says so and asks
whoever next updates a file to record the commit.

### The order changed: 06c before 06b

The original sizing (29 files, consumed from 23 files via 30 symbols) measured only **who imports
the Mihon tree**. Measured on 2026-10-07, the Mihon tree also imports first-party `:shared` code, in
**12 of its 29 files** — 8 import `compose`/`theme` symbols, 10 import `Res`, overlapping — so moving
it out first would have created a module cycle:

| Mihon file(s) | first-party symbol |
|---|---|
| `SearchableSettings.kt` | `compose.Screen` — its supertype, and both FQNs are in `app/api/classes-jar.api` |
| `ListPreferenceWidget.kt`, `MultiSelectListPreferenceWidget.kt` | `SingleSelectDialog`, `MultiSelectDialog` |
| `BasePreferenceWidget.kt`, `material/SettingsItems.kt` | `focusOutline`, `whiteOutline` |
| 4 widget/item previews | `theme.CloudStreamPreviewTheme` — and with it all of `theme/**`, Product Sans included |
| 10 files | `:shared`'s `Res`: 8 drawables and 6 strings used, plus an unused `lorem_title` import |

The other direction is 5 files (4 by `import com.mihon`, plus `Dialog.kt`, whose import came out in 06c): `ComposeFragment.kt` (`LocalBackPress`, `collectAsState`),
`DevicePreferenceStore` ×3 (`PreferenceStore` and its stores) and `Dialog.kt` (one
`MaterialTheme.padding.medium`). `Screen` alone forces the first-party layer below the Mihon tree,
so `:core:ui` comes first (owner's choice, 2026-10-07).

### KMP-06c — `:core:ui` for the first-party Compose layer

**Status:** in pr #41 · built and run locally by the owner on 2026-10-07 (the session that wrote it
had no Android SDK or Maven access). One check still open — see the list below.

Moved by `git mv`, every file's contents unchanged except `Dialog.kt`'s (below):

- `compose/`: `Colors`, `Dialog`, `Modifiers`, `Screen`, `Viewmodel`; `theme/**` in all three source
  sets; the whole `composeResources` tree (strings, 9 vector drawables, `default_icon.png`, the 12 Product
  Sans fonts, unchanged — `DEC-02` is unaffected by where they sit).
- `Dialog.kt`: `MaterialTheme.padding.medium` (Mihon's `Padding`, `16.dp`) became a private
  `ActionDialogIconTitleSpacing = 16.dp`, and the `com.mihon.material.padding` import went. Same value;
  it is the only cycle edge that needed an edit.
- **Stayed in `:shared`:** `ComposeFragment.kt` and `DevicePreferenceStore` ×3, which import Mihon;
  and `compose/Layout.kt`, because `ComposeFragment` calls its `internal` `layoutToFlag`, and moving
  it would have meant widening a visibility. 06d places them.
- `Viewmodel.kt` moved, as `.claude/rules/mvi-viewmodel.md` requires.
- `DEC-09` (`BaseComposeFragment`) is untouched — that class is in `:app`, not `:shared`.

Two names move with it and must not change:

- **`Res`** at `com.lagradost.cloudstream4.generated.resources`, `publicResClass`. `:shared` now has
  `generateResClass = never`; its Mihon files import `:core:ui`'s `Res` unchanged.
- **Android namespace `com.lagradost.cloudstream4`**, whose empty `R` is in `classes-jar.api`. It
  moved to `:core:ui` with `androidResources`; `:shared` became `com.lagradost.cloudstream4.shared`
  without them. Otherwise retiring `:shared` would later drop that `R` from the jar.

`:shared` depends on `:core:ui` as `api` — `SearchableSettings : Screen` is public API — so `:app` and
`:desktopApp` needed no build-file change.

**Verification** — local runs by the owner, 2026-10-07, Temurin 17, `TZ=UTC`; not CI (`DEC-01`):

- [x] `checkKotlinAbi` → exit 0.
- [x] `assemblePrereleaseDebug lint check -Pcloudstream.strings.guards=fail` → exit 0.
- [ ] `:app:checkExtensionApi` → **exit 1, pre-existing and not this change**: 0 removed, 0 changed,
      and the 1 addition is `RunTimeSecondsKt`, from #38 (`INF-07`). The thing this PR risked — the
      `com/lagradost/cloudstream4/R` line — did **not** move: `:core:ui`'s `R` took `:shared`'s place
      exactly. Goes to exit 0 once `INF-07` is decided.
- [ ] `$stable` scan: `javap -p -c` of the moved classes, master vs this branch. **Not yet run.**
- [x] Runtime: all 23 resources in the APK under one `com.lagradost.cloudstream4.generated.resources`
      package (12 Product Sans faces, 10 drawables, strings), no `Res` collision; Settings → General,
      Player, Layout render with Product Sans, not the Roboto fallback; a list-preference dialog
      draws `Res.drawable.check` on the selected row; settings search shows "No results found"; the
      desktop window is titled "CloudStream" and the `preview` drawable renders. No
      `MissingResourceException` in logcat.

### KMP-06b — `:core:mihon`, the vendored tree in its own module

**Status:** open · after `KMP-06c`

29 `.kt` under `com/mihon/**` moved as-is, packages unchanged, plus its `LICENSE` and `readme.md`,
depending on `:core:ui`. After 06c the Mihon tree's only first-party imports are `:core:ui`'s, so no
Mihon file needs an edit. The `NOTICE` and the module README name `shared/src/…` paths and move with it.

- [ ] Decide whether `com/mihon/common/preference/**` goes to `:core:storage` or stays.
      Recommended: **stays**, so one licence lives under one root. Those five files are Compose-free.

### KMP-06d — retire `:shared`

**Status:** blocked until 06b and 06c land

What will be left after 06b: `ComposeFragment.kt`, `compose/Layout.kt` and `DevicePreferenceStore` ×3.
Each needs a home: `DevicePreferenceStore` → `:core:storage` per the plan, once `PreferenceStore`
has one.

---

## KMP-07 — `:media:model` skeleton

**Status:** open · contracts only, no implementation

---

## KMP-08 — Track L: `:library` iOS surgery

**Status:** blocked by `DEC-04`

Needs a design workflow before any code. Cannot be scoped until the iOS extension strategy is
decided, because that decides how much of `:library` is worth making iOS-compilable.

---

## KMP-09 — The `$stable` trap (reference, not a task)

Kept here because it has already cost two attempts.

The Compose compiler emits `public static final int $stable` on every class it processes. Moving a
class to a module **without** the Compose compiler silently drops that field — an ABI break that
reads as an innocent file move. Worse: when a class moves to a *different* Compose module, a
constant `$stable` becomes `getstatic Other.$stable`, which changes the runtime value while the ABI
dump looks identical.

- PR #4 dropped `$stable` on four classes and the PR body claimed signatures were unchanged. #5 fixed
  it with the `cloudstream.kmp.extension-api` convention plugin.
- B2 dropped three auth DTOs for exactly this reason; B3 proved them safe and moved them.

**Always run the `$stable` scan before claiming a move is signature-neutral.** An ABI dump diff
alone does not catch the cross-module case.
