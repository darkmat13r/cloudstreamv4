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

**Status:** done · #41 (merged at `d646470`, before this verification record was written) · built
and run locally by the owner on 2026-10-07 at master `0bc8709`. All checks closed — see below.

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
- [x] `:app:checkExtensionApi` → first **exit 1, pre-existing and not this change**: 0 removed, 0 changed,
      and the 1 addition is `RunTimeSecondsKt`, from #38 (`INF-07`). The thing this PR risked — the
      `com/lagradost/cloudstream4/R` line — did **not** move: `:core:ui`'s `R` took `:shared`'s place
      exactly. With `INF-07`'s block corrected, the owner measured exit 0.
- [x] `$stable` scan, 2026-10-14, local (Temurin 17, `TZ=UTC`), baseline `f29ff63a` (master before #41)
      vs `0bc8709` + #43. Classes compiled from the 13 moved `.kt` (`git mv`'d in `f2e0e435`, not
      `bdd98926`): 30 android, 29 jvm. `public static final int $stable` on **9 → 9** per target,
      0 dropped (`Colors`, `AppFont`, `CloudStreamColorScheme`, `CloudStreamPalette`, `DeviceTheme`,
      `DebounceQuery`, `SingleActiveQuery`, `DefaultStateContainer`, `DefaultEffectContainer`).
      Counted twice: grep over `javap -p -c` text, and a direct class-file parser (field flags +
      `SourceFile`). `javap -p -c` of all 338 classes on each side (`:shared`/`:core:ui` android
      and jvm, plus every `:app` `prereleaseDebug` and `:desktopApp` class referencing
      `cloudstream4/`): 333 identical, 5 not. 3,362 `getstatic` targets per side, 94 of them on a
      `$stable` (none owned by a moved class), no owner changed; the only `getstatic` difference
      is `DialogKt`'s intended edit (`MaterialTheme.padding.medium` → `ActionDialogIconTitleSpacing`). The 5: `DialogKt` and
      `ComposableSingletons$DialogKt` on each target — that edit, plus one accessor renamed by
      module-name mangling, `getLambda$-338782920$CloudStream_shared` → `…$CloudStream_core_ui`,
      called only by `DialogKt` and absent from `classes-jar.api` — and `:app`'s
      `SettingsFragmentScreen`, which differs only by an inlined build timestamp.
      `com/lagradost/cloudstream4/R` is still in `app/build/classes.jar`.
- [x] Runtime: all 23 resources in the APK under one `com.lagradost.cloudstream4.generated.resources`
      package (12 Product Sans faces, 10 drawables, strings), no `Res` collision; Settings → General,
      Player, Layout render with Product Sans, not the Roboto fallback; a list-preference dialog
      draws `Res.drawable.check` on the selected row; settings search shows "No results found"; the
      desktop window is titled "CloudStream" and the `preview` drawable renders. No
      `MissingResourceException` in logcat.

### KMP-06b — `:core:mihon`, the vendored tree in its own module

**Status:** done · #42 · written without an Android SDK; built and checked locally on 2026-10-14
(below), and CI green on `bce90a8f`. Runtime UI still unverified: the settings screens have not
been opened on a device. The `core-ktx` 1.2.0 compile-classpath note below was left as is by the
owner's choice (2026-10-14).

Moved by `git mv`, every file's contents unchanged: all 29 `.kt` under `com/mihon/**` (27
commonMain, 2 androidMain), plus `LICENSE` and `readme.md`, at the same relative paths under
`core/mihon/src/`. Packages unchanged, so the `com/mihon/presentation/settings/SearchableSettings`
FQN that `classes-jar.api` names 17 times does not move.

- `:core:mihon` is `cloudstream.kmp.compose` with `api(:core:ui)`, plus `kotlinx-collections-immutable`
  and (androidMain) `preference-ktx` — what `:shared` supplied, nothing new.
- `:shared` gains `api(:core:mihon)`, so `:app` and `:desktopApp` need no build-file change.
- `NOTICE` and the vendored `readme.md` point at the new paths.
- Checked statically before the move: none of the tree's 12 `internal` declarations is used outside
  it; no `expect`/`actual`; its only first-party imports are `:core:ui`'s.
- [x] `com/mihon/common/preference/**` **stays** in `:core:mihon`, as recommended: one licence under
      one root. Moving it to `:core:storage` is a separate decision for whenever `DevicePreferenceStore`
      moves (06d).

**Verification** — local runs, 2026-10-14, Temurin 17.0.20.1, `TZ=UTC`, `-Xmx12G`, branch head
`bce90a8f`; not CI (`DEC-01`):

- [x] Move re-measured two ways: `git diff -M100%` against #43's head `a3314cc8` shows 30 `R100`
      renames (29 `.kt` + `LICENSE`); a blob-hash comparison of `shared/src/**/com/mihon` there vs
      `core/mihon/src` here matches 30 of 31 blobs. The 31st is `readme.md`, whose only change is the
      `shared/src/androidMain` → `core/mihon/src/androidMain` path on line 3. The `com/mihon` blobs at
      `0bc8709` and `a3314cc8` are identical.
- [x] `checkKotlinAbi` → exit 0.
- [x] `assemblePrereleaseDebug lint check -Pcloudstream.strings.guards=fail` → exit 0. `:core:mihon`
      compiled for jvm and android, and its lint ran.
- [x] `:app:checkExtensionApi` → exit 0. `:app:dumpExtensionApi` → exit 0, and `git status` is clean
      afterwards, so the dump is byte-identical to `app/api/classes-jar.api`.
- [x] `classes.jar` holds **no** `com/mihon/` entry, and it held none before this PR either. The 17
      `SearchableSettings` occurrences in the dump are references from `:app`'s settings classes
      (supertypes, descriptors), not the class itself. `makeJar` merges a module only when it has
      `src/*/kotlin/com/lagradost/cloudstream3`, and neither `:shared` (at `0bc8709`) nor `:core:mihon`
      does. So `:core:mihon` needs no sentinel: there is nothing of it in the jar to guard. Those
      references resolve at runtime from the APK: all 113 compiled `com/mihon` classes are in its dex.
- [x] `$stable` scan: baseline `:shared` at `0bc8709` (throwaway worktree) vs `:core:mihon` here, jvm
      and android outputs, plus `:shared`'s remaining classes, `:app` (`prereleaseDebug`) and
      `:desktopApp`. Done two ways: `javap -p -c` per class, then `javap -p` / `javap -v`
      constant-pool `Fieldref …$stable:I` sets.
      - `$stable` fields: mihon jvm 18 → 18, android 29 → 29; `:shared` rest 9 → 9; `:app` 885 → 885.
        Declaring-class sets identical. **None dropped.**
      - `getstatic` owners: mihon jvm/android 37 → 37 each, `:app` 98 → 98, desktop 3 → 3. Fieldref
        owner sets (40 / 51 / 456) identical. **None changed.** `:app` has no `$stable` Fieldref to a
        `com/mihon` owner, before or after.
      - Class sets: 91/91 (jvm) and 113/113 (android). After normalising constant-pool indices and
        the module-name suffix, every moved class is identical except `AndroidPreference` (below).
        `:shared`'s rest and `:desktopApp`: 0 classes differ. `:app`: 2 classes differ, both only in
        the inlined `BuildConfig` build-date constant.
      - Expected renames: 19 `ComposableSingletons` `getLambda$…` accessors, `$CloudStream_shared` →
        `$CloudStream_core_mihon`. Also, **not foreseen in the PR**, 7 `internal` member declarations
        (4 names: `internalSet`, `internalOnValueChanged`, `internalSubtitleProvider`,
        `InternalIconProvider`) on `Preference$PreferenceItem` subclasses. Nothing outside the module
        calls them (`:app` and `:shared` bytecode is unchanged), and none of these names is in
        `classes-jar.api` (0 matches for either suffix).
      - **Finding: `AndroidPreference` (android only)** gains two
        `Intrinsics.checkExpressionValueIsNotNull(editor, "editor")` calls. They come from the inlined
        `SharedPreferences.edit {}`. `:core:mihon`'s `androidCompileClasspath` resolves
        `androidx.core:core-ktx` **1.2.0** (via `preference-ktx` 1.2.1) and `core` 1.8.0, where
        `:shared`'s resolved 1.18.0 through `activity-compose`. It is harmless at runtime: the call
        exists in kotlin-stdlib 2.4.0, and `edit()` never returns null. But "what `:shared` supplied"
        is not quite true for the transitive compile classpath. Aligning it would add a dependency edge
        to `:core:mihon`, which is the owner's call, so it is not changed here.
- [x] `:desktopApp:compileKotlinJvm` / `jvmJar` → exit 0.
- How the `$stable` scan was set up (the brief it was run against): `javap -p -c` of the moved
      classes, `:shared`'s jvm jar on #41's head vs `:core:mihon`'s here. Compose module → Compose
      module, FQNs unchanged. Two things to know before reading the result:
      - **Expected, not a finding:** `ComposableSingletons$…Kt` accessors rename from
        `getLambda$…$CloudStream_shared` to `…$CloudStream_core_mihon`. The Compose compiler mangles
        them with the Kotlin module name; they are absent from `classes-jar.api`. The owner saw the
        same rename on 06c.
      - **Baseline is the pre-carve-out parent (#41's head), never `master`.** Once this merges,
        `master` *is* this branch and the diff comes back vacuously empty, which reads as clean.
- [ ] Runtime: the seven settings screens and settings search render, a list preference opens its
      dialog, and the desktop app's `SwitchPreferenceWidget` row renders. **Not verified.** The
      prerelease debug APK installed on a Pixel 9a (API 37), and a launch started the process with no
      `FATAL`, `NoClassDefFound`, `NoSuchField`, `NoSuchMethod` or `MissingResource` in logcat. But
      the device was behind a secure keyguard, so no screen rendered. Statically, the APK has all 23
      Compose resources under the one `com.lagradost.cloudstream4.generated.resources` package (as on
      06c), and all 113 `com/mihon` classes plus 2 desugaring `$-CC` companions are in its dex. The
      desktop app was compiled, not run.

### KMP-06d — retire `:shared`

**Status:** open · 06c (#41) and 06b (#42) have landed

What is left after 06b: `ComposeFragment.kt`, `compose/Layout.kt` and `DevicePreferenceStore` ×3.
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
