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

**Status:** done · #41, #42, #45, #46 · order was 06c → 06b → 06d. `:shared` no longer exists.
`DEC-09` (`BaseComposeFragment`) was never on the critical path: that class is `:app`'s.

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
      dialog, and the desktop app's `SwitchPreferenceWidget` row renders. **The desktop half is now
      verified** — see 06d below; the Android half is not. The
      prerelease debug APK installed on a Pixel 9a (API 37), and a launch started the process with no
      `FATAL`, `NoClassDefFound`, `NoSuchField`, `NoSuchMethod` or `MissingResource` in logcat. But
      the device was behind a secure keyguard, so no screen rendered. Statically, the APK has all 23
      Compose resources under the one `com.lagradost.cloudstream4.generated.resources` package (as on
      06c), and all 113 `com/mihon` classes plus 2 desugaring `$-CC` companions are in its dex. The
      desktop app was compiled, not run.

### KMP-06d — retire `:shared`

**Status:** done · #45 (step 1) and #46 (step 2) · 06c (#41) and 06b (#42) landed before them ·
step 2 took **option A**, which is reversible; the options are below, and `DEC-12` is what it
deliberately left open

What is left after 06b: five files, 333 lines, two dependents.

| File | Declares | Used from outside `:shared` |
|---|---|---|
| `commonMain/…/DevicePreferenceStore.kt` (201) | `expect rememberAppSettings`, `AppSettings`, 8 `*Preferences` classes | `rememberAppSettings` in 6 `:app` settings screens; `AppSettings` in `SettingsGeneralScreen`, `SettingsAccountScreen`, `SettingsUpdatesScreen` and `AppContextUtils.kt:485,504`. The `*Preferences` classes are reached only through `AppSettings` |
| `androidMain/…/DevicePreferenceStore.android.kt` (19), `jvmMain/…/DevicePreferenceStore.jvm.kt` (16) | the `actual`s, plus `AppSettings(context)` / `AppSettings()` | `:app` uses the Android one. **Nothing uses the jvm one** — see step 1 |
| `androidMain/…/compose/ComposeFragment.kt` (50) | `Screen.createComposeView(…)` | one caller: `:app`'s `BaseComposeFragment.kt:22` |
| `androidMain/…/compose/Layout.kt` (47) | `DeviceLayout`, `PHONE`/`TV`/`EMULATOR`, `isLayout`, `internal layoutToFlag` | `isLayout` and the flags in 6 `:app` settings screens; `DeviceLayout.Companion.isAutoTv` in `Globals.kt:8`. `ComposeFragment.kt:33` is the only caller of the `internal` `layoutToFlag` |

**Step 1 — `:desktopApp` off `:shared`** · #45. Measured: `desktopApp/src/jvmMain/.../Main.kt`
imports `:core:ui` (`compose.BlackButton`/`WhiteButton`/`LocalFocusOutlineDefault`, `theme.*`, `Res`)
and `:core:mihon` (`SwitchPreferenceWidget`) and **nothing `:shared` declares** — not
`rememberAppSettings`, not `AppSettings`, not `DeviceLayout`. So it depends on `:core:mihon`
directly, and `:shared` is left with one dependent, `:app`. See the PR for the classpath drift this
exposed.

#### The jar is not a constraint here — but `:app` is

`grep cloudstream4 app/api/classes-jar.api` returns **4 lines, none of them these five files'**:
`com/lagradost/cloudstream4/compose/Screen` ×3 as a supertype of `:app`'s `BaseComposeFragment`,
`SettingsFragment2` and `SettingsFragmentScreen`, and `com/lagradost/cloudstream4/R` — both
`:core:ui`'s, both staying put. `:app`'s `Globals` declares its **own** `Int`-based `PHONE`/`TV`/
`EMULATOR`/`isLayout` (`Globals.kt:13-15,45`); `DeviceLayout` is not in the jar, and `Globals`
imports only `DeviceLayout.Companion.isAutoTv`.

**But `extensionApiDumpPackages` is `com/lagradost/` and `:app`'s own classes jar is merged whole**,
so moving any of the five **into `:app`** adds its classes to the published dump: ~15 public classes
(`AppSettings`, the 8 `*Preferences` + `ProviderPreferences$Companion`, the `DevicePreferenceStore*Kt`
facades, `DeviceLayout` + `$Companion`, `LayoutKt`, `ComposeFragmentKt`). Additions are
source-compatible, but they are a published contract afterwards.

#### `:core:ui` cannot take `ComposeFragment.kt` — measured, not argued

`:core:mihon` is `api(project(":core:ui"))`, so an import of `com.mihon` from `:core:ui` is a cycle.
`ComposeFragment.kt` has three such edges, and none of them can be cut inside `:core:ui`:

- `com.mihon.presentation.LocalBackPress` — `SearchableSettings.kt:23` **reads** that exact
  `staticCompositionLocalOf`, so a re-declaration in `:core:ui` would provide a different local and
  the back handler would silently stop working.
- `com.mihon.presentation.settings.collectAsState` (`Preference.kt:195`) — an extension on Mihon's
  `PreferenceData`.
- `rememberAppSettings()`, whose `AppSettings` takes `com.mihon.common.preference.PreferenceStore`.

So whatever hosts `ComposeFragment.kt` sits **above** `:core:mihon`, or is `:app`.

#### Step 2: where `DevicePreferenceStore` and the Android pair go

Not settled. The owner decides; each option's cost is measured.

**A — a new module above `:core:mihon`** (e.g. `:core:settings`), `cloudstream.kmp.compose`,
`api(:core:mihon)` + `:library`, android + jvm. Takes all five files **verbatim**: no content edit,
no visibility widening, nothing added to the jar, `:shared` deleted. Honest cost: a module replaces
a module, so the "retirement" is a rename to a scoped name plus a build file that declares what the
five files actually need (activity-compose, preference-ktx, `:library`) instead of `:shared`'s
catch-all (which also carries `libs.bundles.compose` and `kotlinx-collections-immutable`).

**B — fold all five into `:app`.** `:shared` disappears and nothing replaces it; `expect`/`actual`
collapses to one Android function, since nothing uses the jvm `actual` after step 1. Cost: ~290
lines of first-party Compose code move **back** into `:app`, against the migration's direction, and
~15 classes join the published `classes.jar` ABI permanently.

**C — `:core:storage`, as `docs/plans/kmp-migration.md` §7 Phase 5 says.** That line predates
`:core:mihon` existing. It now costs three things, all measured:

1. **`:core:storage` would depend on `:core:mihon`.** The 9 classes take
   `com.mihon.common.preference.PreferenceStore` and expose `PreferenceData<T>` from the same
   package. The alternative — porting onto `:core:storage`'s own, deliberately identical-shaped
   `PreferenceStore`/`PreferenceData` — retypes **62 `preference = settings.…` arguments** across 6
   `:app` settings screens plus 13 `collectAsState()` calls, because the receiving fields are
   declared `PreferenceData<…>` in **vendored Mihon code** (`Preference.kt:41,69,112,142`). That is
   a retype of the Mihon widget API or a new adapter layer, not a move.
2. **`:core:storage` would depend on `:library`.** `DevicePreferenceStore.kt` imports
   `AllLanguagesName`, `DubStatus`, `SearchQuality`, `TvType`. `core/storage/build.gradle.kts`'s
   KDoc states the module takes layer 0 only and **not** `:library`, "so plan §4.1 rule 4 does not
   apply and this module is not iOS-canary exempt". Adding it reverses a documented property.
3. **`:core:storage` would need the Compose compiler** for `@Composable expect fun
   rememberAppSettings()`. It is `cloudstream.kmp.library`, not `cloudstream.kmp.compose`.

**D — put them in `:core:mihon`.** One module, no cycle, nothing added to the jar — and first-party
code under the Apache-2.0 vendored root, which is the boundary 06b exists to draw
(`core/mihon/build.gradle.kts`: "no first-party code belongs here").

**Sub-option, independent of A–D:** `Layout.kt` imports nothing from Mihon, so it could go to
`:core:ui/androidMain` on its own — at the cost of widening `DeviceLayout.Companion.layoutToFlag`
from `internal` to public, because `ComposeFragment.kt:33` is its only caller. Keeping the pair
together avoids the widening.

#### Step 2 — `:core:settings`, option A · #46

Taken because A is the only option that moves the five files **verbatim**, and because it is
reversible: B, C and D are a rename and a build-file edit away. `DEC-12` records the one thing it
deliberately does not settle, the two `PreferenceStore` declarations.

`:core:settings` is `cloudstream.kmp.compose`, `api(:core:ui)` + `api(:core:mihon)` +
`implementation(:library)`, android + jvm. Two of `:shared`'s dependencies are **not** carried over
because no file here uses them: `kotlinx-collections-immutable` (the Mihon tree's) and
`preference-ktx` (`DevicePreferenceStore.android.kt` reaches `SharedPreferences` through Mihon's
`AndroidPreferenceStore`, which declares it itself). `libs.bundles.compose` is not declared either —
`cloudstream.kmp.compose` supplies it as `api`.

**`:core:settings` is iOS-canary exempt**, the second entry in `IOS_CANARY_EXEMPT` after
`:core:model`, because `DevicePreferenceStore.kt` imports `AllLanguagesName`, `DubStatus`,
`SearchQuality` and `TvType` (plan §4.1 rule 4). The list "may only shrink", so: this is not a new
`:library` dependency — `:shared` declared the same edge and had no iOS target at all — it is the
same code compiling under a convention plugin for the first time. It lifts with `:core:model`'s.
Measured both ways: with the exemption, `:core:settings` has no `iosCanary` task and `:core:ui`
still has one; with it removed, `:core:settings:dependencies --configuration
iosArm64CompileKlibraries -Pcloudstream.iosCanary=true` prints `project :library FAILED`.

**Verification** — local runs, 2026-10-14, Temurin 17.0.20.1, `TZ=UTC`, `-Xmx14G`, each command
redirected to a file with `$?` captured:

- [x] `checkKotlinAbi` → exit 0.
- [x] `assemblePrereleaseDebug lint check -Pcloudstream.strings.guards=fail` → exit 0.
- [x] `:app:checkExtensionApi` → exit 0. `:app:dumpExtensionApi` → exit 0 and `git status` clean
      afterwards, so `app/api/classes-jar.api` is byte-identical. `com/lagradost/cloudstream4/R` is
      still in `app/build/classes.jar`, and no `com/lagradost/cloudstream4/compose/*` entered it:
      `:core:settings` declares no `src/*/kotlin/com/lagradost/cloudstream3`, so it is not merged.
- [x] Move re-measured: `git diff -M100%` shows **5 `R100` renames** for the sources plus one for
      `shared/readme.md` → `docs/compose-rewrite.md`. No content edit in any of the five.
- [x] `$stable` scan, baseline **#45's head `5b2c216a`** in a throwaway worktree (never master
      after the merge, or the diff is vacuously empty). Counted two ways on every side: a regex over
      `javap -p -c`, and a direct class-file parser (field access flags plus constant-pool
      `Fieldref` owners).
      - `:app` `prereleaseDebug`: **2202 classes, 888 `$stable` declarers, 2407 `getstatic`
        targets, 9276 `Fieldref` owners — identical sets**, not merely identical counts. A
        byte-level `sha256` comparison of the same 2202 class files adds the one caveat: **2 differ**,
        `SettingsFragment` and `SettingsFragmentScreen`, and in both the entire diff is one
        `ldc2_w` operand, `1791992530844` → `1791992051696` — two epoch milliseconds 8 minutes
        apart, i.e. `BuildConfig.BUILD_DATE` inlined at `SettingsFragment.kt:255`. The same two
        classes differed for the same reason in 06b. (The set comparison cannot see this: the dump
        deliberately does not guard constant *values*, and nor does a `$stable`/`getstatic` set.)
      - `:desktopApp`: 3 classes, 0 `$stable`, 19 `getstatic`, 21 `Fieldref` — identical sets.
      - The moved files: `:shared` 13 jvm / 18 android → `:core:settings` 13 jvm / 18 android, same
        class names, **9 → 9 `$stable`** per target with identical declaring-class sets, and
        identical `Fieldref` owner sets (87 jvm, 99 android). Per-class `javap -p -c` text is
        **identical for all 31** after normalising constant-pool indices and the module-name suffix.
      - **Expected, not a finding:** 2 `internal` members rename with the Kotlin module name —
        `DeviceLayout.and-sihxV88` and `DeviceLayout$Companion.layoutToFlag-PtjY-Ko`,
        `$CloudStream_shared` → `$CloudStream_core_settings`. Both are 0 matches in
        `classes-jar.api`. 06b saw 26 such renames, 06c saw 1.
      - **Expected, not a finding:** `:core:settings` gains 7 classes per target that `:shared` did
        not have — an empty generated `Res` (`Res`, `Res$array/$drawable/$font/$plurals/$string`,
        `ActualResourceCollectorsKt`) at `com.lagradost.cloudstream4.core.settings.generated.resources`,
        carrying 6 of the `$stable` fields and 5 `getstatic`s. `:shared` set `generateResClass =
        never` to avoid colliding with `:core:ui`'s public `Res`; `cloudstream.kmp.compose`'s
        default package is per-module, so there is no collision, and `:core:mihon`, `:core:theme`
        and `:core:designsystem` each carry the identical 7. `publicResClass = false`, so none of
        them is public API.
- [ ] Runtime: the seven settings screens, settings search and a list-preference dialog. **Still
      not verified** — same gap as 06b, and the same cause: the attached Pixel 9a was behind a
      secure keyguard (`mDreamingLockscreen=true`), so nothing could be driven on screen.
      What *is* verified statically, in the `prereleaseDebug` APK this branch builds: all 9
      `AppSettings`/`*Preferences` classes, `DevicePreferenceStore_androidKt`, `DeviceLayout`
      (+ `$Companion`), `LayoutKt`, `ComposeFragmentKt`, `compose/Screen`,
      `com/lagradost/cloudstream4/R`, `SearchableSettings`, `AndroidPreferenceStore`,
      `BaseComposeFragment`, `Globals` and all 7 `Settings*Screen` classes are in its dex —
      27 of 27 present, 0 missing, out of 49,542 classes. So the module move cannot have produced a
      `NoClassDefFoundError`; what remains unproven is what the Android screens look like.
- [x] Runtime, desktop: `:desktopApp:run` on this branch opens the window and it renders — the
      title "CloudStream" from `stringResource(Res.string.app_name)`, `CloudStreamTheme`'s dark
      surface, `:core:ui`'s `WhiteButton`/`BlackButton`, and **`:core:mihon`'s
      `SwitchPreferenceWidget`** with its icon (`Res.drawable.preview`), title and subtitle. That is
      the item 06b left open ("the desktop app was compiled, not run"), and it exercises what a
      module move most endangers: Compose resources resolving from `:core:ui`'s `Res` across a
      module boundary, now two moves from where they started. The window still reads "Hello,
      World!" because `desktopApp/src/jvmMain/.../Main.kt` is a 62-line placeholder that `KMP-06d`
      does not touch.

#### Also in step 2

- `settings.gradle.kts:23`, `app/build.gradle.kts:300` lose `:shared`.
- **`NOTICE` is already clean** — `grep -i shared NOTICE` is 0 matches; 06b repointed it.
  `docs/HANDOVER.md` says otherwise and is wrong on that point.
- Two readmes outlive the module and need a decision: `shared/readme.md` is LagradOst's 100-line
  rationale for the whole Compose rewrite (file structure, MVI, resources, "why compose", the AI
  usage policy) and is not about this module's mechanics — it belongs in `docs/`.
  `shared/src/readme.md` is 9 lines of the Compose-Multiplatform template's source-set blurb.
- `:shared`'s Android namespace `com.lagradost.cloudstream4.shared` generates no `R`, so deleting
  the module removes nothing from the jar — confirm with `:app:checkExtensionApi`.

---

## KMP-07 — `:media:model` skeleton

**Status:** in pr #48 · contracts only, no implementation

`:media:model` exists: `cloudstream.kmp.library`, android + jvm, **no internal dependencies at
all**, iOS canary on. Eight files, 39 tests.

### Why it depends on nothing

Plan §4.2 lists this module's canary as plain "yes", not "subject to rule 4" — unlike `:core:model`
and `:core:settings`, which are exempt because their commonMain takes `:library`. Keeping it that
way is the whole design constraint: **every shape here is a restatement, not a move**, and `:app`
owns the adapters. That is not a workaround, it is the point — the contracts have to outlive
`:library`'s Android-bound declarations for an iOS player sibling to exist (plan §4.3).

It also means no class here is in any ABI dump, nothing is merged into `classes.jar`, and `KMP-09`
does not apply: there is nothing to scan, because nothing moved.

**`:library` is itself not Kotlin/Native-compilable today**, which settles the "just depend on it"
shortcut: `ExtractorApi.kt` has `java.util.UUID` in public signatures at `:531` (`newDrmExtractorLink`),
`:679` (`setUuid`), `:684` (`getUuid`), plus `CLEARKEY_UUID`/`WIDEVINE_UUID`/`PLAYREADY_UUID` built
with `.toJavaUuid()` at `:492`/`:495`/`:498`. That is `KMP-08`/Track L's problem.

### What is in it

| File | Holds |
|---|---|
| `StreamRequest.kt` | `StreamSource` (`Remote`/`Local`), `StreamProtocol`, `AudioTrackSource`, `StreamRequest` |
| `PlaybackRequest.kt` | `PlaybackRequest` |
| `Drm.kt` | `DrmScheme`, `DrmConfiguration` |
| `EngineCapabilities.kt` | `HeaderSupport`, `EngineCapabilities` + `supports(StreamRequest)` |
| `Subtitle.kt` | `SubtitleCue`, `SubtitleSource`, `SubtitleTrack` |
| `CaptionStyle.kt` | `ArgbColor`, `CaptionEdge`, `CaptionFont`, `CaptionStyle` |
| `PlayerCommand.kt` | `CommandSource`, `PlayerCommand` (sealed, 15 cases) |
| `NowPlaying.kt` | `NowPlaying`, `PlaybackPosition`, `PlaybackState`, `PlaybackStatus` |
| `PipState.kt` | `AspectRatio`, `PipState` |
| `DownloadProgress.kt` | `DownloadState`, `DownloadProgress`, `SegmentProgress` |

### The decisions, and what each one costs

Each was taken against a measured shape; the survey behind them is in the PR.

- **No `TvType`, no `Score`, no `LoadResponse`.** `TvType` is the most pervasive `:library` leak
  across the shapes `NowPlaying` replaces (`ResultEpisode`, `ExtractorUri`, four download DTOs).
  It is left out rather than restated: no playback surface — notification, lock screen, cast
  receiver, PiP — branches on it. `NowPlaying.seriesTitle` being null is what separates a film from
  an episode, which is the only distinction those surfaces draw. **Cost:** anything that does need
  `TvType` keeps using `:app`'s types.
- **`StreamSource` is a sealed type, replacing two nullable parameters.** `loadPlayer(link, data, …)`
  takes `ExtractorLink?` and `ExtractorUri?` and throws `IllegalArgumentException("Requires link or
  uri")` when both are null — a sum type written as two nulls. Now the invalid case cannot be built.
- **`DrmScheme` is a new enum.** There is none today: `:library` has three `Uuid` constants and
  `:app` dispatches with a `when` over `uuid.toJavaUuid()` (`CS3IPlayer.kt:1282`, `:1304-1305`)
  that falls through to a logged error. A UUID cannot be exhaustively matched, so "this engine
  cannot play this scheme" is a runtime log instead of a compile-time case. The UUIDs stay; `:app`
  maps. **Cost:** a mapping table, and `UNKNOWN` for schemes this build has not heard of.
- **`HeaderSupport` exists because of iOS.** Media3 sets any header; AVPlayer has no public
  arbitrary-header API but does expose a user agent and cookies (plan §10 O6). Since extensions
  routinely pin a UA or pass a Cloudflare `cf_clearance` cookie, those two cases survive on an
  engine that can do nothing else, and `EngineCapabilities.supports()` says so rather than failing
  at play time.
- **`CaptionStyle` drops `elevation`, `edgeSize` and `backgroundRadius`** — dp, px and px, resolved
  only on Android through `TypedValue` and `.toPx`. A contract carrying a px value carries a screen
  with it. `textSizeSp` is kept: sp is a stated scale, and it is the one the user sets. It also
  drops `removeCaptions`, `removeBloat` and `upperCase`, which are text *filtering* and belong to
  `:media:subtitle`. **This is why the type is 10 fields where `SaveCaptionStyle` is 17.**
- **`CaptionEdge` is an enum because the int is in two incompatible namespaces** — Media3's
  `CaptionStyleCompat.EDGE_TYPE_*` for the player and Cast's `TextTrackStyle.EDGE_TYPE_*` for
  Chromecast. The same user setting is stored twice as two different integers today and nothing
  says so.
- **`CaptionFont.PlatformDefault` is not "no choice".** `SaveCaptionStyle.typeface` is an Android
  `@FontRes` id — a lookup into a resource table that exists only in an Android build. There is no
  portable value with that meaning, so a bundled font is named rather than numbered.
- **`PipState` models the decision, not the permission.** Whether PiP is possible depends on the
  SDK level, `PackageManager.FEATURE_PICTURE_IN_PICTURE` and an `AppOpsManager` check the user can
  revoke; none has a portable form, so `isSupported` is supplied by the platform. The three magic
  numbers for the aspect clamp (`0.41841f`, `2.39f`, accuracy `100000`) move from local `val`s
  inside the Android helper into named constants on `AspectRatio`, where they are testable.
- **`PlayerCommand` is sealed, and does not replace `CSPlayerEvent`.** Half the commands carry a
  value, which an enum forces into a side channel — which is how `IPlayer` ended up with two
  command surfaces, an 11-case enum plus ~20 methods that are commands in everything but name. The
  enum stays in `:app`: it is published ABI and it has a live `Intent` wire format (see `BUG-09`).
- **`DownloadProgress` prefers segments over bytes.** An HLS download's byte total only counts
  segments already seen, so a byte-progress bar grows and then jumps backwards. `:app` already
  carries `hlsProgress`/`hlsTotal` beside the byte counts for this reason.
- **Unknown totals return `null`, never `0f`.** `PlaybackPosition.fraction`,
  `DownloadProgress.fraction` and `SegmentProgress.fraction` all do this, so a progress bar stays
  indeterminate rather than snapping to empty.

### Deliberately not done

- **No kotlinx-serialization.** Nothing here crosses a process or disk boundary yet; `:app`'s
  persistence keeps operating on its own Jackson-annotated types. Adding the plugin later is
  additive. Keeping it out is what makes the module dependency-free.
- **No mime strings.** The engine boundary uses `androidx.media3.common.MimeTypes` on Android and
  would use `AVFileType` on Apple platforms, and the two disagree even on HLS — `ExtractorLinkType`
  says `application/x-mpegURL`, Media3 says `MimeTypes.APPLICATION_M3U8`. Declaring one here makes
  the module wrong for one platform.
- **No `:app` call site is changed.** This is a skeleton; nothing consumes it yet. The `:app`
  adapter (below) is the first test of the shapes; rewiring `CS3IPlayer` to use it is still to do.

### The `:app` adapter — the design's first test

`app/src/main/java/com/lagradost/cloudstream3/ui/player/StreamRequestAdapter.kt`, an `internal object`
beside `CS3IPlayer` and `ExtractorUri` (the two types it reads). One direction only
(`:library`/`:app` → `:media:model`), pure, no state: `fromLink(ExtractorLink)`, `fromUri(ExtractorUri)`,
`protocolOf(ExtractorLinkType)` and `schemeOf(Uuid)` / `schemeOf(java.util.UUID)`. `:app` gained
`implementation(project(":media:model"))`; no catalog entry. Tests:
`app/src/test/.../ui/player/StreamRequestAdapterTest.kt`. **No call site is changed** — `CS3IPlayer`
still takes `ExtractorLink` / `ExtractorUri`; rewiring it is its own task.

`fromUri` itself is not unit-tested: `ExtractorUri.uri` is an `android.net.Uri`, which a plain JVM
test cannot build (no Robolectric/Mockito in the catalog). Its Uri-free body, `localStream`, is.

**Where the design does not fit**, by field. (a) = deliberately dropped by the design; (b) = a gap in
`:media:model` needing a follow-up; (c) = the adapter derives it honestly.

| Source field | Class | What happens |
|---|---|---|
| `ExtractorLinkPlayList` (`url = ""`, `playlist: List<PlayListItem>` with `durationUs`) | **(b)** | `StreamRequest` has no concatenated-slices shape, so `fromLink` returns `null`. `CS3IPlayer` plays it through `ConcatenatingMediaSource2`. Needs either a `StreamSource.Playlist` or a decision that playlists stay `:app`-only. |
| `ExtractorLink.referer` vs a `Referer` key in `headers` | **(b)**, docs only | `getAllHeaders()` lets an existing header win over the field. `StreamSource.Remote` keeps both and does not say which wins. The adapter passes both through unchanged and does not lift the header into `referer`. One sentence on `Remote` would settle it. |
| `StreamSource.Remote` KDoc: "fetched over HTTP(S)" | **(b)**, docs only | `TORRENT`/`MAGNET` links carry `magnet:` or `.torrent` urls. `protocol` already distinguishes them; the KDoc is wrong about the url. |
| `AudioTrackSource.languageTag` | **(b)**, docs only | `AudioFile` is `(url, headers?)` and has no language, so nothing can supply it today; the adapter leaves `null`. The KDoc says "where the extension supplied one", which no extension can. |
| `StreamRequest.protocol` for a local file | **(c)** | `ExtractorUri` carries nothing to derive it from. `PROGRESSIVE` is what the player already does (`loadOfflinePlayer` opens every local file as `MimeTypes.VIDEO_MP4`). A local HLS/DASH manifest would be mislabelled. |
| `ExtractorLinkType.MAGNET` | **(a)** | Folded into `TORRENT`; `StreamProtocol` says so. The url prefix keeps the distinction. |
| `ExtractorLink.quality` | **(a)** | Carried straight through, as designed. `quality` is `Int?` but a link always has one, so `null` is only ever produced by `fromUri`. The `Qualities.Unknown` sentinel (400) and `0` = Auto are carried as numbers, not mapped to `null`. |
| `ExtractorLink.extractorData`, `getVideoSize()` | **(a)** | Extension concerns; stay on the link (stated in the `StreamSource` KDoc). |
| `getVideoInterceptor(link)` (looked up by `link.source`) | **(a)** | A runtime object, not data. `providerName` is exactly the key needed to look it up, so the field earns its place. |
| `DrmExtractorLink.kty` | **(c)**, note | Defaults to `"oct"` for every scheme, Widevine included, though only the ClearKey branch reads it. Passed through; the adapter does not null it by scheme. |
| `DrmExtractorLink.uuid` not recognised | **(a)** | `DrmScheme.UNKNOWN`, as designed. Nothing is lost that exists today: `CS3IPlayer`'s fall-through logs `drm::class.simpleName`, not the uuid. |
| `DrmExtractorLink.keyRequestParameters` `HashMap` | **(a)** | Copied into a `Map` (shape change stated on `DrmConfiguration`). |
| `ExtractorUri.id`, `episode`, `season`, `headerName` | not `StreamRequest`'s | `NowPlaying` has homes for them (`id`, `episode`, `season`, `title`). A separate adapter. |
| `ExtractorUri.parentId` | **(b)** | No home in `StreamRequest` or `NowPlaying` (`NowPlaying.id` is the episode id; there is no series id). |
| `ExtractorUri.tvType` | **(a)** | `TvType` is deliberately not restated (see "No `TvType`" above). |

No field was found that `StreamRequest` demands and the source cannot supply, apart from
`protocol` for a local file (above). The shapes otherwise held.

**ABI.** `internal object` keeps the adapter off the source-level API, but a Kotlin `internal` class is
public in bytecode, so `classes.jar` gains `StreamRequestAdapter` (and it is not a top-level facade,
which is the `INF-07` trap). `:media:model` is not merged into the jar — its package is
`cloudstream4.media.model`, not `cloudstream3` — so the adapter's public descriptors name classes
that are not in `classes.jar`; harmless unless an extension calls it, which it has no reason to.
`:app:checkExtensionApi` is expected to report additions only, 0 removed and 0 changed, until
`:app:dumpExtensionApi` is run.

### Verification

Local, 2026-10-14, Temurin 17.0.20.1, `TZ=UTC`, `-Xmx14G`, each command redirected with `$?` captured.

- [x] `checkKotlinAbi`, `assemblePrereleaseDebug lint check -Pcloudstream.strings.guards=fail`,
      `:app:checkExtensionApi` → all exit 0. The ABI dump is unchanged, as it must be: nothing moved.
- [x] `:media:model:jvmTest` → **39 tests, 0 failures, 0 skipped**, counted from the JUnit XML
      rather than from the task's exit code (the first count was taken with a regex that matched
      no attribute order and reported 0 — re-measured by parsing the XML).
- [x] `:media:model:iosCanary -Pcloudstream.iosCanary=true` → exit 0, both `iosArm64` and
      `iosSimulatorArm64` klibs compiled.
- [x] **The canary is not decorative — it caught a real error on the first run.** `@JvmInline` on
      `ArgbColor` compiled for android and jvm and failed for Native with `Unresolved reference
      'JvmInline'`, because `kotlin.jvm.*` is a default import only on JVM. Fixed with an explicit
      `import kotlin.jvm.JvmInline`. This is the first module whose `commonMain` purity is enforced
      by a build rather than by reading — `:core:model`'s KDoc admits its own is "checked by
      reading, not by a build".

---


---

## KMP-08 — Track L: `:library` iOS surgery

**Status:** blocked by `DEC-04`

Needs a design workflow before any code. Cannot be scoped until the iOS extension strategy is
decided, because that decides how much of `:library` is worth making iOS-compilable.

---

## KMP-10 — the iOS canary is red · **measured 2026-10-14**

**Status:** step 1 in pr #49 (`:core:ui`), step 2 in pr #50 (`:core:mihon`) · **after both, the
iOS canary is green for the first time: 12 of 12 modules**

`./gradlew iosCanary -Pcloudstream.iosCanary=true --continue` is **red on master**, and has been
since `KMP-06c` (#41) created `:core:ui`. Twelve modules have an `iosCanary` task. Ten pass. One
fails:

    e: core/ui/src/commonMain/.../theme/DeviceTheme.kt:7:10
       Expected DeviceTheme has no actual declaration in module <commonMain> for Native

`internal expect object DeviceTheme` has an `androidMain` actual (Material You, via
`android.R.color.system_*`) and a `jvmMain` one, and no Native one. `:core:mihon` is the twelfth: it
cannot run at all, because it depends on `:core:ui`.

Plan §4.2 lists `:core:ui` as canary "yes, macOS runner", so this is a gap between the plan and the
tree, not a decision. Nobody noticed because `INF-02` records that the canary "is run by hand" and
has no CI job — and by hand, nobody ran it.

### Step 1 — `:core:ui` · #49

Done. `core/ui/src/iosMain/.../DeviceTheme.ios.kt` returns the static scheme and the normal primary
colour, which is what `jvmMain` already does.

**There was no design choice to make, though it looked like one.** Android's Material You exposes
the wallpaper palette through `android.R.color.system_*` and macOS exposes a chosen accent through
`NSColor.controlAccentColor`, but **iOS publishes no system accent or palette to apps**. There is
nothing to read. `CloudStreamThemeMode.Dynamic` also already documents itself as falling back "to
`Dark` on unsupported platforms", so the behaviour was specified before the source set existed.

### Step 2 — `:core:mihon`'s four `String.format` calls · #50

Fixing `:core:ui` let `:core:mihon` compile for the first time, and it fails — at **exactly the four
sites the plan already named** (§7 Phase 5: "Replace `String.format` as the Mihon files move
(`Preference.kt:74,100,124`, `EditTextPreferenceWidget.kt:41`)"):

    e: core/mihon/.../settings/Preference.kt:74:37   Unresolved reference 'format'.
    e: core/mihon/.../settings/Preference.kt:100:37  Unresolved reference 'format'.
    e: core/mihon/.../settings/Preference.kt:124:31  Unresolved reference 'format'.
    e: core/mihon/.../settings/widget/EditTextPreferenceWidget.kt:41:30  Unresolved reference 'format'.

All four are `subtitle?.format(…)` — `kotlin.text.format`, which is `java.lang.String.format` and
JVM-only. Each substitutes one value into a preference subtitle template.

Its own PR, because this is **vendored Apache-2.0 code** (`KMP-06a`, `NOTICE`), where every edit
widens the diff against upstream that 06a measured file by file. Four lines of first-party code
would not need their own review; four lines inside the Mihon tree do. `readme.md` now records the
edit, so the next person re-measuring against upstream expects it.

**The "how" was settled by measuring the corpus, not by choosing.** Every template that reaches
these calls is exactly one `%s` — the `"%s"` defaults on `ListPreference`, `BasicListPreference`
and `MultiSelectListPreference`, and four `"%s\n" + stringResource(…)` overrides in `:app`'s
player and updates screens. The one `"%s %d"` in the tree is in `:app`'s legacy
`SettingsAccount.kt`, a view-binding fragment that never reaches a preference subtitle. So a
one-placeholder substitution is faithful, and porting `String.format` properly would be inventing
capability nothing uses.

`withValue` is first-party and lives in `:core:ui` (`compose/Template.kt`), not in the Mihon tree,
so the licence boundary holds: the vendored files gain an import and four call sites, and no
first-party file joins them.

**One real difference, in the safe direction.** `String.format` throws
`UnknownFormatConversionException` on a stray `%`, and three of the four `:app` templates
concatenate a *translated* string — so a translator writing "50% off" would have crashed the
settings screen. `replaceFirst` cannot throw. That is a latent class of crash removed, **not a live
bug**: across all 49 locale files, none of `video_disk_description`, `video_ram_description` or
`automatic_plugin_download_summary` contains a `%` today.

**Reproduce:** `./gradlew iosCanary -Pcloudstream.iosCanary=true --continue`. Kotlin/Native 2.4.0
must be in `~/.konan`; it downloads on first run. On this machine the whole canary takes about
4 seconds warm — which is the strongest argument for `INF-02` finally giving it a CI job: the thing
nobody ran for five weeks costs seconds.

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
