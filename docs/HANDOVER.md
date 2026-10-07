# Handover — 2026-10-07

Written to move this work from a local session to a cloud one. It records what is done, what is in
flight, what is waiting on the owner, and the handful of local facts that will waste an hour if you
rediscover them.

`docs/tasks/` is the live task list and stays authoritative. This file is the orientation.

---

## Where things stand

Master is at `722ae4be`. The strings flip (`I18N-07`) is **complete**: eight PRs, #32 through #37,
all merged. Compose resources under `core/strings/src/commonMain/composeResources` are now the
source of truth for every user-visible string, and `app/src/main/res` is generated from them.

The two numbers that justify it: `lookup.tsv` is **0 lines different** across 66,315 cells, and
`emitted.tsv` is **byte-identical to the baseline frozen before the flip began**. Nothing a user
sees changed.

### Open pull requests

| PR | What | State |
|---|---|---|
| #38 | `BUG-01` — clamp the episode runtime conversion | open, verified locally |

### Branches with work not yet pushed

Check these before assuming nothing is stranded:

| Branch | Worktree | What |
|---|---|---|
| `feat/mihon-attribution` | `app-mihon` | `KMP-06a` — this PR |
| `fix/broken-translations` | `app-i18n05` | `I18N-05` — was mid-flight when the session ended; **verify its state before reusing the branch name** |

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
| `DEC-01` | **GitHub Actions billing** | **Runs again since 2026-10-07** (first green run: #42); see `INF-01` for the one runner loss. Before that it blocked everything. **38 PRs have merged and not one has been verified by CI** — every "green" in every PR body is a local run. This is the largest single gap in the project. |
| `DEC-02` | **Product Sans** | Now measured: 24 files, two byte-identical sets of 12, genuine Google Product Sans v1.009 (`GOOG` vendor ID, empty copyright field, licence URL pointing at a page that does not exist). Eight other proprietary fonts sit beside them in the subtitle picker. Dormant while private; wakes on first distribution. Blocks `DS-05`. |
| `INF-06` | **Test toolchain** | Pin it, or re-measure the `ar` table — the two cannot both be satisfied, because CI's JDK 17 still says `٠`. |
| `BUG-08` | **`addDate` contract** | Are offset-less extension dates UTC (what happens) or device-local (what the KDoc says)? Changing it moves what users see for every provider returning a bare date-time. |
| `DEC-10` | **Screenshot testing** | Wait for a working plugin, or switch to Roborazzi. |
| `DEC-04` | **iOS extensions** | Unanswered. |
| | **`MainAPI.kt:2549`** | Documents `Episode.runTime` as **seconds**; `EpisodeAdapter` treats it as **minutes**. One is wrong by 60×. Recorded in `BUG-01`, deliberately not resolved. |

---

## The next thread

> **Update, 2026-10-07 (cloud session).** The order is now **06c → 06b → 06d**. The Mihon tree imports
> first-party `:shared` code in 12 of its 29 files — `SearchableSettings` extends `compose.Screen` — so
> it cannot leave first. `KMP-06c` (`:core:ui`) is implemented on `claude/festive-bardeen-3258ln` but
> **has never been built**: that container had no Android SDK and no network route to Maven, Google
> or Gradle. The owner has since built and run it locally: two of three commands green, the third red
> on a pre-existing failure (`INF-07`), runtime resources verified. The `$stable` scan is still open;
> `docs/tasks/kmp.md` has the detail. The text below predates this.


`KMP-06b` — move the vendored Mihon tree into its own `:core:mihon` module. This PR
(`KMP-06a`) is its precondition and is now done.

The sizing is already measured, so you do not need to redo it: 29 files, 3,323 lines, consumed from
23 files outside the tree via **30 distinct symbols**. The most-imported are
`SearchableSettings` (13 files), `Preference` (7) and `collectAsState` (6). Only `:app` and
`:desktopApp` depend on `:shared`. After `06b` comes `:core:ui` (`06c`), then retiring `:shared`
(`06d`).

Two things that constrain any module move, both documented at length in
`docs/plans/kmp-migration.md`:

- **The `$stable` trap.** Moving a Compose-compiled class to a module without the Compose compiler
  silently drops its `public static final int $stable` field, which extensions read via `getstatic`.
  Cross-module moves also turn a constant into a `getstatic` against another class — a runtime
  change that is **invisible to ABI dumps**. Every receiving module must apply
  `cloudstream.kmp.extension-api`.
- **`classes.jar` is a published contract.** `:app:checkExtensionApi` is the gate, and it is not
  part of `check` — run it separately.

---

## How this session worked, for whatever that is worth

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
