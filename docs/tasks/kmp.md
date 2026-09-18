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

**Status:** open · **Size:** one PR

Body-only change first: a private header merge replaces the call to `:app`'s
`RequestsHelperKt.getHeaders`. Adds `:library`, coroutines and `annotation` to `:core:network`.

- [ ] Run the `$stable` scan before moving (see `KMP-09`)
- [ ] Replace the `getHeaders` call with a private merge
- [ ] Move, verify javap identical, `checkExtensionApi` dump unchanged

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

**Status:** open · **Size:** one PR

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

**Status:** open · **Size:** several PRs · **Partly blocked by** `DEC-09`

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

**Status:** open · decided 2026-09-18 (see `DEC-03`)

All 16 vendored files checked differ from upstream (`AppBar.kt` by 157 lines of 405); none carries
a change notice; the vendored `LICENSE` **names no copyright holder**; there is no `NOTICE` file
anywhere in the repo; no upstream commit SHA is recorded, so nobody can re-diff against a baseline.

- [ ] One `NOTICE` plus a module README naming Mihon, the URL, Apache-2.0, and that files are modified
- [ ] Reconstruct the upstream commit if possible, so the fork has a baseline
- [ ] Not 29 file headers

### KMP-06b — `:core:mihon`, the vendored tree in its own module

**Status:** open · after `KMP-06a`

29 `.kt` under `com/mihon/**` moved as-is, packages unchanged, plus its `LICENSE` and `readme.md`.
One module, one licence, one `NOTICE`. Keeping it separate is what makes it obvious that a
different standard applies there — because a different licence does.

- [ ] Decide whether `com/mihon/common/preference/**` goes to `:core:storage` or stays.
      Recommended: **stays**, so one licence lives under one root. Those five files are Compose-free.

### KMP-06c — `:core:ui` for the first-party Compose layer

**Status:** open · after `KMP-06b`

`com/lagradost/cloudstream4/compose/**` and `theme/**`. Takes the `publicResClass` FQN
`com.lagradost.cloudstream4.generated.resources` — exactly one module may hold it, because two `Res`
classes at one FQN collide on the classpath (`cloudstream.kmp.compose.gradle.kts` exists to prevent
this).

- [ ] `Viewmodel.kt` moves rather than being deleted. It is unreferenced *by code* but designated
      foundation *by rule*: `.claude/rules/mvi-viewmodel.md` names it as the MVI primitives for every
      `:core:*` and `:feature:*` module not yet written.
- [ ] `DEC-09` decides whether `BaseComposeFragment` comes along.

### KMP-06d — retire `:shared`

**Status:** blocked until 06b and 06c land

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
