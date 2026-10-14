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

Master is at `d4a9a2c9`. **No pull requests are open.**

The `:shared` restructure (`KMP-06`) is two-thirds done:

| | What | PR | Verified |
|---|---|---|---|
| `KMP-06a` | Mihon attribution: one `NOTICE`, per-file upstream mapping | #39 | — |
| `KMP-06c` | `:core:ui`: first-party Compose layer, `Res`, the `com.lagradost.cloudstream4` namespace | #41 | Owner, locally (three commands, runtime resources). `$stable` scan on 2026-10-14: 9 of 9 kept, no `getstatic` owner moved |
| `INF-07` | `:app:checkExtensionApi` red on master since #38; `RunTimeSecondsKt` added to the dump | #43 | Locally; the regenerated dump is byte-identical |
| `KMP-06b` | `:core:mihon`: the vendored tree, 30 files, 100% renames | #42 | Locally (three commands, `:desktopApp`, `$stable` 18→18 jvm, 29→29 android, `:app` 885→885). **CI green.** Settings screens not opened on a device: the phone was locked |

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

**2. Pin the timezone.** `BUG-08`. `:library`'s `EpisodeDateTest` fails on any machine not on UTC.
This is not merely a missing test pin: `addDate`'s documented device-timezone fallback is **dead**
under kotlinx-datetime 0.8.0, because `toInstantUsingOffset()` does not throw on an offset-less
input — it returns a UTC reading. Extension-supplied dates with no offset are read as UTC. CI is
green only because GitHub runners default to UTC.

    TZ=UTC

**3. Raise the heap.** `gradle.properties` sets `-Xmx4G`. A full `assemblePrereleaseDebug lint check`
OOMs in D8 on a 48 GB machine. Pass it on the command line rather than editing the file:

    -Dorg.gradle.jvmargs="-Xmx14G -Dfile.encoding=UTF-8"

**And in a worktree:** `-x :app:generateGitHash`, because `.git` is a file there, not a directory.

Putting it together, the full local equivalent of CI:

    T17=/path/to/temurin-17.0.20.1
    TZ=UTC JAVA_HOME="$T17" ./gradlew checkKotlinAbi -x :app:generateGitHash \
      -Dorg.gradle.jvmargs="-Xmx14G -Dfile.encoding=UTF-8"
    TZ=UTC JAVA_HOME="$T17" ./gradlew assemblePrereleaseDebug lint check \
      -Pcloudstream.strings.guards=fail -x :app:generateGitHash \
      -Dorg.gradle.jvmargs="-Xmx14G -Dfile.encoding=UTF-8"
    TZ=UTC JAVA_HOME="$T17" ./gradlew :app:checkExtensionApi -x :app:generateGitHash \
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
| `DEC-04` | **iOS extensions** | Unanswered. |
| | **`MainAPI.kt:2549`** | Documents `Episode.runTime` as **seconds**; `EpisodeAdapter` treats it as **minutes**. One is wrong by 60×. Recorded in `BUG-01`, deliberately not resolved. |

---

## The next thread: `KMP-06d`, retire `:shared`

`:shared` now holds five files and 333 lines, and has two dependents, `:app` and `:desktopApp`.
Measured 2026-10-14:

| File | Declares | Used from outside |
|---|---|---|
| `commonMain/…/DevicePreferenceStore.kt` (201) | `expect rememberAppSettings`, `AppSettings`, 8 `*Preferences` classes | `rememberAppSettings` in 6 `:app` settings screens, `AppSettings` in 4 `:app` files (one is `AppContextUtils.kt`). The `*Preferences` classes are used only through `AppSettings` |
| `androidMain/…/DevicePreferenceStore.android.kt` (19), `jvmMain/…/DevicePreferenceStore.jvm.kt` (16) | the `actual`s, plus a public `AppSettings(context)` / `AppSettings()` factory | `:app`, and `:desktopApp`, which uses the jvm one |
| `androidMain/…/compose/ComposeFragment.kt` (50) | `Screen.createComposeView(…)` | **one** caller: `:app`'s `utils/BaseComposeFragment.kt`, and that class is in `classes.jar` (`DEC-09`) |
| `androidMain/…/compose/Layout.kt` (47) | `DeviceLayout`, `PHONE`/`TV`/`EMULATOR`, `isLayout`, `internal layoutToFlag` | `isLayout` and the flags in 6 `:app` settings screens; `DeviceLayout.isAutoTv` in `Globals.kt`. `ComposeFragment` calls the `internal` `layoutToFlag`, so these two files move together or the visibility widens |

**None of these five files has a class in `classes-jar.api`.** Grep the dump for
`cloudstream4/AppSettings`, `compose/LayoutKt`, `compose/DeviceLayout` and `ComposeFragmentKt`: 0
matches. A move is therefore less constrained than 06b was, but still run the `$stable` scan and
`:app:checkExtensionApi`. The one ABI-relevant edge is `BaseComposeFragment`, which is `:app`'s and
stays put.

Open design questions. These are not settled, so measure before choosing:

- **Where `DevicePreferenceStore` goes.** The plan says `:core:storage`. But these classes are built
  on **Mihon's** `PreferenceStore` (`com.mihon.common.preference`, now in `:core:mihon`), while
  `:core:storage` has its own `PreferenceStore`/`PreferenceData`. Moving to `:core:storage` would
  make it depend on `:core:mihon`, or would mean porting onto `:core:storage`'s types. Porting is a
  behaviour change, not a move. A cheaper first step is `:core:ui`, or a small `:core:settings`.
  Present the options to the owner.
- **Where `ComposeFragment.kt` and `Layout.kt` go.** They are Android-only and Compose, so
  `:core:ui`'s `androidMain` is the natural home. Check that it doesn't create a `:core:ui` →
  `:core:mihon` edge: `ComposeFragment` imports `LocalBackPress` and `collectAsState` from Mihon.
  If it does, it is a cycle, because `:core:mihon` depends on `:core:ui`.
- **The namespace.** `:shared`'s Android namespace is `com.lagradost.cloudstream4.shared`, and it
  generates no `R`. Deleting the module removes nothing from the jar, but confirm that with
  `checkExtensionApi`.
- `settings.gradle.kts`, `app/build.gradle.kts:300` and `desktopApp/build.gradle.kts:18` lose
  `:shared`. `:app` and `:desktopApp` then depend on the new homes directly.
- `NOTICE` and `shared/readme.md` mention `:shared`; update them.

It may be two PRs: one for the Android Compose pair, one for the preference store.

Other `KMP` work that is ready: `KMP-07`, the `:media:model` skeleton (contracts only, wants a
design first). `DS-07` (off-grid `Dp` in the Mihon tree) is unblocked by 06b, but it is
low-priority, and it means editing vendored code.

### Housekeeping the task files still owe

- `docs/tasks/i18n.md` says `I18N-03` is "in progress", but no open PR or branch was found for it.
  Ask before treating it as abandoned.
- The `kmp.md` text credits the 06c file moves to `bdd98926`. The 36 renames actually landed in
  `f2e0e435`, the "Recreate CLAUDE.md" commit, as `git show -M --name-status` shows. History can't
  change; only the wording can.

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
