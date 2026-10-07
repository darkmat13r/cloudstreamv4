---
name: cloudstream4-migration-progress
description: "Running log of the CloudStream 4 KMP migration — merged PRs, in-flight work, the queue, worktrees, and what waits on the owner"
metadata: 
  node_type: memory
  type: project
  originSessionId: 5713e857-cb79-4a78-b9cb-f4b1d22e1202
  modified: 2026-09-18T08:10:13.056Z
---

Repo: `darkmat13r/cloudstreamv4` (a fork with no GitHub parent; PRs target its `master`). The plan
is `docs/plans/kmp-migration.md` (rewritten in #9: settled decisions D1–D4/S1–S2/I0–I4, §5.2
movable clusters B1–B8, §7 Next PRs, §10 open decisions O1–O10). Update this file after every
PR merge or new workflow.

**GitHub Actions is broken account-wide** — every run fails in ~2s with "recent account payments
have failed or your spending limit needs to be increased". No PR in this whole series has been
exercised by CI; every check claimed in a PR body was run locally. Say so in each PR body.

## Merged (as of 2026-09-18)
| PR | What |
|---|---|
| #4 | `:core:common` (Event, EmptyEvent, Vector2, IDisposable*) + `cloudstream.extension-api-jar` (derived merge, sentinels) |
| #5 | `cloudstream.kmp.extension-api` — keeps `$stable` on classes moved out of :app (fix #4 missed) |
| #6 | `:core:network` (DohProvidersKt) |
| #7 | `abiValidation` on extension-api modules; CI runs `checkKotlinAbi` everywhere |
| #8 | G1: `ui-tooling` out of commonMain in `cloudstream.kmp.compose` |
| #9 | Migration plan rewrite (iOS-readiness, decisions, audit corrections) |
| #10 | ktor 3.5.0 → 3.6.0 + `KtorUrlBehaviourTest` (URL parsing changed for extensions) |
| #11 | `:core:http` Ktor client factory over app.baseClient (unmanaged engine) |
| #12 | G2+G3: opt-in iOS klib canary `-Pcloudstream.iosCanary=true` |
| #13 | D3: android-only modules merged into classes.jar; `cloudstream.kmp.base` + `cloudstream.kmp.android-only` |
| #14 | B1: `:core:data` with DataStoreKt / DataStoreHelperKt consts |
| #15 | javap tripwire: `app/api/classes-jar.api` + `:app:checkExtensionApi` CI step |
| #16 | B2: `:core:model` (AbstractSubtitleEntities, SyncSearchViewModel, IGeneratorKt); Auth DTOs stayed in :app |
| #17 | `:core:strings`: Compose resources generated from Android res via aapt2 (Android-exact fallback, guards) |
| #18 | S-a step 5: generated `values-b+he` / `values-b+id` copies |
| #19 | B3: serializable Auth DTOs → `:core:model` (4 `write$Self` renames accepted) |
| #20 | `:core:storage` — interfaces, Android impls, persistent JVM stores |
| #21 | S-b `UiString` + own formatter + apc/arz/qt plural guard + `@Deprecated` on `utils.UiText` |

## Open PRs
- **#22** `refactor/storage-file-split` — splits the 385-line `TextRecordFile.kt` into format /
  corruption / file layers (owner approved the split). Awaiting merge.
- **#23** `feat/uistring-locale` — reverses UiStringFormat to follow the platform locale (see the
  decision below), plus the first test coverage of `UiString.resolve()` via a headless
  BroadcastFrameClock+Recomposer host with **no new dependency** (Compose's `ComposeEnvironment` is
  Kotlin-`internal`, so it is satisfied with a reflection `Proxy`). 35 jvm tests.

## Decisions taken 2026-09-18
- **UiStringFormat follows the platform locale**, reversing the locale-invariant design shipped in
  #21. Symbols are read once per `format()` call, never cached — an in-app language change moves
  `Locale.getDefault()` under a live process. Measured facts worth keeping: `DecimalFormat` has no
  secondary grouping, so `hi`/`en-IN` group `1,234,567` not `12,34,567` (the lakh worry is unfounded);
  `en-US-POSIX` reports no grouping at all; signs, `NaN` and `Infinity` stay ASCII even in `fa`,
  where the locale minus is U+2212, because `Formatter.leadingSign` appends a literal `'-'`.
- **HumanReadable (nl.jacobras) was DECLINED** after a measured study. It divides by 1024 but labels
  SI, so 20.44 GB would display as 19.0 GB; it emits exactly ONE unit, so `2h 22m` becomes
  `2 hours`; `ru` and `ar` measurably regress; ~39 locales lose translations they have today. The
  twelve existing call sites already use `android.text.format.Formatter`, which is better for the
  job. **Do not re-propose it.** Instead the owner approved fixing three in-house bugs the study
  surfaced (below).

## In flight
Workflow `wf_ceae1af6-72a` — two of the three approved formatting fixes, one worktree each,
read → design → implement → adversarial verify, measured on the attached Android device:
- **A `fix/dlsize`** (`app-fix-dlsize`): the download notification's hand-rolled `"%.1f MB"` at
  `utils/downloader/DownloadManager.kt:318` is the only by-hand byte formatting left; twelve call
  sites in nine other files use `Formatter.formatShortFileSize`. Traps: the HLS branch holds
  **segment counts, not bytes**; the speed line is a rate with no platform form; minSdk is 23.
- **B `fix/duration`** (`app-fix-duration`): `AccountManager.secondsToReadable` (`:147`) computes
  leftover seconds and discards them, so 45 s renders `0m` and the resume line says "0m remaining"
  for the last minute of every episode.
- **C, not yet started** — unify the duration format: home preview uses `R.string.duration_format`
  (`%d min` → `142 min`) while the result page uses `secondsToReadable` (`2h 22m`) for the same
  title. Stacks on B.
Also reported but NOT authorized to fix: `ResultViewModel2.kt:345` passes a hardcoded English
`"0 mins"`; the `d`/`h`/`m` unit letters are hardcoded English; `prettyCount` is dead (its only
caller is commented out) and `resume_time_left` is an unused string.

## Queue
6. Split `:shared` → `:core:ui` + `:core:designsystem`; `:media:model` skeleton
7. B4–B8
8. Track L `:library` iOS surgery (design workflow first; needs the tripwire)
Also owed: refresh the plan's "Next PRs" table (still shows #7–#12 open).

## Waiting on the owner
- **O4**: CI runner for the iOS canary (macOS / split / local-only) + minimum iOS version
- GitHub Actions billing (blocks all CI)
- In-PR decisions later: B7 fcast Packets placement, B8 synthetic accessor removal
- Non-blocking: O1 distribution (GPL), O2 iOS extension strategy, O3 Apple TV, O5–O10

## How work is run
One workflow per PR: implement → adversarial verify (can reject) → fix loop ≤3; then I reproduce
the key checks by hand before committing. Each PR in its own worktree under
`/Volumes/Projects/CloudStream/app-*`, `local.properties` copied, upstream unset. Gradle in a
worktree needs `-x :app:generateGitHash`. Shell gotcha: zsh does not word-split `$G="./gradlew …"`;
write multi-step checks as bash scripts. Jar tasks: checks that must precede zip expansion go in
`doFirst`. Verify stages must re-run with `--rerun-tasks` — an up-to-date `check` will otherwise
report a previous run's test XMLs as if they were fresh.

Related: [[cloudstream4-kmp-modules]], [[cloudstream4-abi-audit]].
