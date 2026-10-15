# Handover — 2026-10-14

This file orients a new session. It records what is done, what is open, what waits on the owner, and
the local facts that cost an hour each when someone rediscovers them.

`docs/tasks/` is the live task list and stays authoritative. This file is the orientation.

---

## Before you do anything: check the remote

**Run `git fetch` and `gh pr list --state all` first.** Cloud sessions push branches and open PRs that
a local checkout, or a handover pasted into a prompt, does not know about. On 2026-10-14 a pasted
brief said master was `f29ff63a` and that `KMP-06b` was next. In fact `06c` had already merged, and
`06b` and its follow-up were open PRs. Two parallel agents were started on work that already existed.

---

## Where things stand

Master is at `493e1273` (#47). #45 and #46 finished `KMP-06d`, so `:shared` no longer exists; #47
recorded `INF-09`.

**Three pull requests are open, in one stack:** #48 (`KMP-07`, `:media:model`) ← #49 (`KMP-10`
step 1, `:core:ui`'s iOS actual) ← #50 (`KMP-10` step 2, `:core:mihon`'s `String.format`). Merging
#50 takes all three; review from the bottom.

**The iOS canary is green for the first time, 12 of 12 modules**, as of #50. It had been red since
#41 and nobody noticed, because `INF-02` records that it "is run by hand" and by hand nobody ran
it. It takes about 4 seconds warm, which is the best argument yet for giving it a CI job:

    ./gradlew iosCanary -Pcloudstream.iosCanary=true --continue

**Neither #45 nor #46 has a green CI run**, and that is worth knowing before you read the ticks.
#46's `Artifact Build` was cancelled by hand at the `Check classes.jar ABI` step; on #47, the run
that *did* get that far passed `Ensure binary compatibility` and `Run Gradle` before being
cancelled at the same step. Every result quoted in those PRs is a **local** run of the three
commands below (`INF-01`, and `INF-08` for why that step is the one people cancel).

The `:shared` restructure (`KMP-06`) is two-thirds done:

| | What | PR | Verified |
|---|---|---|---|
| `KMP-06a` | Mihon attribution: one `NOTICE`, per-file upstream mapping | #39 | — |
| `KMP-06c` | `:core:ui`: first-party Compose layer, `Res`, the `com.lagradost.cloudstream4` namespace | #41 | Owner, locally (three commands, runtime resources). `$stable` scan on 2026-10-14: 9 of 9 kept, no `getstatic` owner moved |
| `INF-07` | `:app:checkExtensionApi` red on master since #38; `RunTimeSecondsKt` added to the dump | #43 | Locally; the regenerated dump is byte-identical |
| `KMP-06b` | `:core:mihon`: the vendored tree, 30 files, 100% renames | #42 | Locally (three commands, `:desktopApp`, `$stable` 18→18 jvm, 29→29 android, `:app` 885→885). **CI green.** Settings screens **opened and verified on an emulator**, 2026-10-14 — see `docs/tasks/kmp.md` |
| `KMP-06d` | step 1: `:desktopApp` → `:core:mihon`; `:shared` left with one dependent | #45 | Locally (three commands), `:desktopApp` bytecode byte-identical. Found: 7 libraries resolve lower on `:desktopApp:jvmRuntimeClasspath` once `:shared`'s `:library` edge goes; recorded, not changed |
| `KMP-06d` | step 2: `:core:settings` takes the last five files; **`:shared` deleted** | #46 | Locally (three commands), dump byte-identical, `$stable` scan clean (below). Settings screens **opened and verified on an emulator**, 2026-10-14 |

**How #43 landed:** #43's own CI never went green. In every run, the runner killed the
`Check classes.jar ABI` step partway through the release dex build: cancelled, or exit 143 (SIGTERM).
No check ever reported. #42 contained #43's head commit and passed every step on that tree, so #42
was merged, which merged #43 with it. The evidence is in a comment on #42.

**Left as is, by the owner's choice:** `:core:mihon` compiles against `androidx.core:core-ktx` 1.2.0,
pulled in through `preference-ktx`, where `:shared` resolved 1.18.0. Two extra null-check intrinsics
are inlined, which is harmless at runtime because the app ships 1.18.0. The detail is in
`docs/tasks/kmp.md`, under `KMP-06b`.

**Two findings from the 06b scan, both recorded in `kmp.md`:**

- `app/build/classes.jar` contains **no** `com/mihon/**` class. The 17 `SearchableSettings` lines in
  `classes-jar.api` are references from `:app`'s settings classes (supertypes, signatures), not
  the class itself. `makeJar` merges only modules with a `src/*/kotlin/com/lagradost/cloudstream3`
  directory, so the FQN must stay stable, but `:core:mihon` needs no sentinels.
- Compose mangles internal-member and `ComposableSingletons` accessor names with the Kotlin module
  name (`$CloudStream_shared` → `$CloudStream_core_mihon`). Every module move renames them. None of
  them is in `classes-jar.api`, so expect it and don't report it as a finding.

### CI: runs again, with one unreliable step

GitHub Actions has run on pull requests since 2026-10-07 (`DEC-01`, `INF-01`). The PR workflow
(`.github/workflows/pull_request.yml`) mirrors the three local commands.

**The `Check classes.jar ABI` step sometimes dies on the runner.** It builds every release variant,
and the release dex is where the runner kills it, with no Gradle error. **Read a red run's log before
you believe it**: `gh run view <id> --log-failed`. If the last line is `The operation was canceled`
or `exit code 143` and no `What went wrong:` precedes it, no check ran. It is recorded as `INF-08`, which
lists the runs and the options. The fix is the owner's call, because it changes a CI gate.

Before the 2026-10-07 change, 38 PRs merged with no CI at all. Treat those PRs' "green" as local only.

**Master's commit status is permanently red, and it is not about the code** (`INF-09`, measured
2026-10-14). `Pre-release`, `Archive build` and `Dokka` fail on every push to master — 14 of 14 runs
since 2026-10-03, 0 successful — in 10–15 seconds, at their first step: each mints a token for an
upstream repository through `tibdex/github-app-token` with `app_id: ${{ secrets.GH_APP_ID }}`, which
this fork does not set (`Error: Input required and not supplied: app_id`). `Artifact Build`, the
workflow that gates merges, mints no token and is unaffected. The problem is the masking: a
genuinely broken master would look exactly like today. Fixing it changes CI gates, so it is the
owner's call.

---

## If you edit a string, read this first

`app/src/main/res/values*/strings.xml` is **generated**. Do not hand-edit it. The authored source is
`core/strings/src/commonMain/composeResources/values*/strings.xml`.

    ./gradlew :core:strings:writeAndroidStrings    # regenerate the res, then review the diff

`:core:strings:checkAndroidStrings` runs in `check` and fails if the two disagree.

Three mechanisms are easy to trip over:

- **`core/strings/authored-aliases.tsv`** — 67 places where the res holds an `@string/…` reference
  rather than literal text. Android re-resolves each **in the requesting locale**, so
  `values/duplicate_cancel = @string/cancel` gives a Hungarian user Hungarian. Flatten one and every
  locale that does not define the key itself drops to English. The file feeds both the res writer
  and the Compose materialiser.
- **The authored tree is lean.** It holds only what each directory declares. The ~672 fallback
  copies that make Compose's lookup match Android's are derived at build time by
  `materialiseComposeStrings`, never committed. Writing them into the res made 12 locale files
  declare plurals with only their *source's* categories, and lint went from 0 errors to 25.
- **`core/strings/oracle-baseline/`** is a frozen six-file oracle. `lookup.tsv` is the user-visible
  one; if a change moves it, that is a real behaviour change and wants explaining, not a blind
  baseline update.

---

## Local environment — three traps, all pre-existing

None of these are caused by recent work; all three cost real time to find.

**1. Pin the JDK.** `INF-06`. `jdkToolchain = "17"` is consumed only by `:app`, so every `:core:*`
module compiles and tests on whatever JVM started the Gradle daemon. On a JDK 24+ daemon, two
`LocaleNumberParityTest` cases fail on clean master, because JDK 24's CLDR 46 moved bare `ar` from
Arabic-Indic digits to ASCII. **It also guards the ABI baselines:** on JDK 21+ `checkKotlinAbi` fails
on `SequencedCollection`'s `addFirst`/`addLast`/`removeFirst`/`removeLast`, inherited by every `List`
implementor; the committed dumps were written on 17. Never run `updateKotlinAbi` on 21+.

    JAVA_HOME=/path/to/temurin-17.0.20.1

On the owner's Mac, Gradle has already provisioned one at
`~/.gradle/jdks/eclipse_adoptium-17-aarch64-os_x.2/jdk-17.0.20.1+1/Contents/Home`.

**2. The timezone no longer has to be pinned.** `BUG-08`, **fixed**. It used to: `:library`'s
`EpisodeDateTest` failed on any machine not on UTC, because `addDate`'s documented device-timezone
fallback was dead under kotlinx-datetime 0.8.0 — `toInstantUsingOffset()` does not throw on an
offset-less input, it returns a UTC reading, so extension-supplied dates with no offset were read as
UTC. `addDate` now branches on what the string actually carried, and the suite passes in any zone.

Measured when the fix landed: the full four-gate run below, with **no `TZ`** set (machine
`Asia/Kolkata`) and the test JVMs forced to `Asia/Kolkata`, all exit 0 with 678 of 678 tasks executed
under `--rerun-tasks`; `:library:jvmTest` is 562 tests, 0 failures, also green under
`America/New_York` and `Pacific/Kiritimati`. The control — the same forced-zone run against the
pre-fix tree — fails exactly the two cases `BUG-08` names.

**Setting `TZ` on a Gradle command does not reach the test JVM anyway.** A reused Gradle daemon keeps
the `TZ` it was started with, so a client-side `TZ=…` can be silently ignored. If you ever need to
pin a test JVM's zone, do it on the task:

    // init script, applied with -I
    allprojects { tasks.withType<Test>().configureEach { systemProperty("user.timezone", zone); environment("TZ", zone) } }

**3. Raise the heap.** `gradle.properties` sets `-Xmx4G`. A full `assemblePrereleaseDebug lint check`
OOMs in D8 on a 48 GB machine. Pass it on the command line rather than editing the file:

    -Dorg.gradle.jvmargs="-Xmx14G -Dfile.encoding=UTF-8"

**And in a worktree:** `-x :app:generateGitHash`, because `.git` is a file there, not a directory.

Putting it together, the full local equivalent of CI:

    T17=/path/to/temurin-17.0.20.1
    JAVA_HOME="$T17" ./gradlew checkKotlinAbi -x :app:generateGitHash \
      -Dorg.gradle.jvmargs="-Xmx14G -Dfile.encoding=UTF-8"
    JAVA_HOME="$T17" ./gradlew assemblePrereleaseDebug lint check \
      -Pcloudstream.strings.guards=fail -x :app:generateGitHash \
      -Dorg.gradle.jvmargs="-Xmx14G -Dfile.encoding=UTF-8"
    JAVA_HOME="$T17" ./gradlew :app:checkExtensionApi -x :app:generateGitHash \
      -Dorg.gradle.jvmargs="-Xmx14G -Dfile.encoding=UTF-8"

**A piped Gradle run reports the pipe's exit code, not Gradle's.** `./gradlew … | tail` exits 0 on a
failed build. This produced a false "green" in this session. Redirect to a file and capture `$?`.

---

## Waiting on the owner

Nothing below can be settled by reading the code.

| | | |
|---|---|---|
| `DEC-01` | **GitHub Actions billing** | **Resolved in practice: it has run since 2026-10-07.** The owner has not recorded what changed, so the entry stays open until they do. The ABI step is unreliable; see above. |
| `DEC-02` | **Product Sans** | Now measured: 24 files, two byte-identical sets of 12, genuine Google Product Sans v1.009 (`GOOG` vendor ID, empty copyright field, licence URL pointing at a page that does not exist). Eight other proprietary fonts sit beside them in the subtitle picker. Dormant while private; wakes on first distribution. Blocks `DS-05`. |
| `INF-06` | **Test toolchain** | Pin it, or re-measure the `ar` table — the two cannot both be satisfied, because CI's JDK 17 still says `٠`. |
| `BUG-08` | **`addDate` contract** | Are offset-less extension dates UTC (what happens) or device-local (what the KDoc says)? Changing it moves what users see for every provider returning a bare date-time. |
| `DEC-10` | **Screenshot testing** | Wait for a working plugin, or switch to Roborazzi. |
| `DEC-12` | **The two `PreferenceStore`s** | `:core:mihon`'s and `:core:storage`'s have the same shape and both are live. Raised by `KMP-06d`; `:core:storage`'s Android and JVM implementations are unused until it is settled. |
| `DEC-04` | **iOS extensions** | Unanswered. |
| `BUG-09` | **PiP buttons: `value` vs `ordinal`** | `PlayerPipHelper` writes `CSPlayerEvent.value` into the `Intent`; `PlayerView` reads it back as `entries[int]`, i.e. by ordinal. They agree only because every case happens to have `value == ordinal`. The fix is a choice between two conventions, both written up in the entry. |
| | **`MainAPI.kt:2549`** | Documents `Episode.runTime` as **seconds**; `EpisodeAdapter` treats it as **minutes**. One is wrong by 60×. Recorded in `BUG-01`, deliberately not resolved. |

---

## `KMP-06` is finished: `:shared` is gone

`KMP-06d` landed in two PRs. `:core:settings` (`cloudstream.kmp.compose`, android + jvm) holds the
five files that were left — `DevicePreferenceStore` ×3, `compose/ComposeFragment.kt`,
`compose/Layout.kt` — moved verbatim, 0 content edits, 31 classes bytecode-identical after
normalising constant-pool indices and the module-name suffix. `:app`'s 2202 `prereleaseDebug`
classes, 888 `$stable` declarers, 2407 `getstatic` targets and 9276 `Fieldref` owners are
**identical sets** on both sides, and `app/api/classes-jar.api` is byte-identical.

Three things that cost time to establish, so they are written down:

- **`:core:ui` cannot host `ComposeFragment.kt`.** `:core:mihon` is `api(:core:ui)`, and
  `ComposeFragment` has three edges into `com.mihon`: `LocalBackPress` (which `SearchableSettings`
  *reads*, so re-declaring it elsewhere silently breaks the settings back handler),
  `collectAsState`, and `rememberAppSettings`, whose `AppSettings` takes Mihon's `PreferenceStore`.
  Any one of them is a module cycle.
- **`Layout.kt` has to travel with it.** `DeviceLayout.and` and `Companion.layoutToFlag` are
  `internal`, so their binary names carry the Kotlin module name
  (`layoutToFlag-PtjY-Ko$CloudStream_core_settings`). Splitting the pair needs both a visibility
  widening and that rename; neither name is in `classes-jar.api`.
- **`:core:settings` is iOS-canary exempt**, like `:core:model`, because its commonMain depends on
  `:library` (plan §4.1 rule 4). Verified by removing the exemption: `:core:settings`'s
  `iosArm64CompileKlibraries` resolution reports `project :library FAILED`. The exemption list
  "may only shrink" — this entry exists because the code moved under a convention plugin for the
  first time, not because anything new depends on `:library`, and it lifts with `:core:model`'s on
  Track L.

Two readmes moved rather than died: `shared/readme.md` is LagradOst's rationale for the whole
Compose rewrite and is now `docs/compose-rewrite.md`. `shared/src/readme.md` was 9 lines of the
Compose-Multiplatform template's source-set blurb and was deleted; #46 quotes it in full.

### What is next

- **`DEC-11`** (new, below): consolidating the two `PreferenceStore` declarations. `:core:storage`
  and `:core:mihon` each declare one with an identical shape. This is the one thing `KMP-06d` did
  **not** settle, and it is an owner decision — `docs/tasks/kmp.md` `KMP-06d` costs each option.
- `KMP-07`, the `:media:model` skeleton (contracts only, wants a design first).
- `DS-07` (off-grid `Dp` in the Mihon tree) is unblocked by 06b, but it is low-priority and it
  means editing vendored code.
- `INF-08`, the runner killing CI's `Check classes.jar ABI` step, is still the owner's call.

### Housekeeping the task files still owe

- `docs/tasks/i18n.md` says `I18N-03` is "in progress", but no open PR or branch was found for it.
  Ask before treating it as abandoned.
- ~~The `kmp.md` text credits the 06c file moves to `bdd98926`.~~ Already fixed: `kmp.md` says
  "`git mv`'d in `f2e0e435`, not `bdd98926`". The 36 renames landed in `f2e0e435`, the "Recreate
  CLAUDE.md" commit, as `git show -M --name-status` shows.

There is also a kanban snapshot of every task. It is the owner's, private, and republished by hand:
https://claude.ai/artifact/WPYftYL5U9yTvprLfcfjdQ

---

## Two constraints on any module move

Both are documented at length in `docs/plans/kmp-migration.md`:

- **The `$stable` trap** (`KMP-09`). Moving a Compose-compiled class to a module without the Compose
  compiler silently drops its `public static final int $stable` field, which extensions read via
  `getstatic`. Cross-module moves also turn a constant into a `getstatic` against another class.
  That is a runtime change, and it is **invisible to ABI dumps**. Every module that receives classes
  from `:app` applies `cloudstream.kmp.extension-api`. For the scan, the baseline is the commit
  **before** the move, never master after it merges, or the diff comes back vacuously empty. Count
  two ways: a grep over `javap -p -c`, and a constant-pool `Fieldref` set.
- **`classes.jar` is a published contract.** `:app:checkExtensionApi` is the gate, and it is not part
  of `check`, so run it separately. After an intended change, `:app:dumpExtensionApi` regenerates the
  dump; never hand-write a line (`INF-07`).

---

## How the work has gone, for whatever that is worth

Measure rather than reason. Nearly every wrong claim in this project's history was one a plausible
argument supported and nobody executed: the `dur * 60` overflow argued unreachable, the
"locale-blind" notification, the ABI dump that supposedly did not exist, and in this session a
`BUG-01` entry describing a defect that had already been fixed.

Three of this session's own measurements were wrong on the first pass and corrected on the second:
a regex that counted commented-out `<string>` lines as live keys (reported 27 removed, actually 0);
a regex that missed `formatted="true" name="…"` because the attributes were reversed (reported 26
spurious additions); and a NOTICE draft claiming the vendored changes were "substantial rather than
cosmetic" when three files differ by the `package` line alone. Each was caught by checking, not by
thinking harder. The habit that catches them is cheap: re-run the measurement a second way before
writing the number down.

On 2026-10-14 the same habit was applied to subagents. Two parallel verification agents each ran
in their own git worktree. Their headline numbers (exit codes, renames, the jar contents) were
re-checked by hand before anything was pushed. One of their claims, that the 06c moves were in
`f2e0e435`, sounded wrong and turned out to be right. Give parallel agents **separate scratch
subdirectories**. Two agents sharing one scratchpad overwrote each other's runner script mid-run.
