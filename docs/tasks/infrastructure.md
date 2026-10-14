# INF — build, CI and guards

---

## INF-01 — CI does not run · **the single largest gap**

**Status:** unblocked 2026-10-07 — CI runs (see `DEC-01`). Open: make it reliable.

First runs, 2026-10-07, all on `pull_request.yml`'s single `build` job:

| PR / head | Result |
|---|---|
| #42 `b8c26d5` | died in 51 s resolving `build-logic`'s classpath: Maven Central "could not find" `gson:2.11.0`, `kotlinx-coroutines-core-jvm:1.8.0` and others that exist. Transient; not re-measured |
| #42 `bce90a8` | **green**, 42 min — the repo's first full CI pass |
| #43 `a3314cc` | **runner lost** after 36 min, during `:app:mergeDexStableRelease` ("The runner has received a shutdown signal"). Not a task failure. Suspected memory: the job runs at `gradle.properties`' `-Xmx4G`, which `docs/HANDOVER.md` already says OOMs D8 in a full run. Re-run once |

If runner loss recurs, the fix is a workflow change (a larger `-Xmx` via `GRADLE_OPTS` or
`-Dorg.gradle.jvmargs`, or `lint` in its own job), as its own PR.

The text below is the state before 2026-10-07.


GitHub Actions fails account-wide in ~2s with a billing error. **No pull request in this series has
ever been exercised by CI.** Everything claimed green was run locally, on one machine, with one JDK
(Temurin 17.0.20.1), against one or two attached Android devices.

What that means concretely, beyond the obvious:

- Nothing has been built on a clean checkout. Every "green" run reused a warm Gradle cache and a
  configuration-cache entry.
- `:app:checkExtensionApi`, `checkKotlinAbi` and the strings guards are all wired into
  `.github/workflows/pull_request.yml` and **none of them has ever gated a merge**.
- The one-JDK caveat is load-bearing for `:core:strings`: its formatter parity is measured against
  that JDK's CLDR data.

- [ ] When billing is fixed, re-run the full check on a clean checkout **before** trusting the
      backlog of merged work, and expect to find something

**Every green run also prints two red `##[error]` annotations, and they are expected.** Seen on every
`Artifact Build` run inspected on 2026-10-14, inside the **successful** `Run Gradle` step:

    ##[error]    |     values/strings.xml:4: error: unescaped apostrophe in string

That is `core/strings/fixtures/guard-cases/aapt2-unescaped-apostrophe/res/values/strings.xml:4` —
`<string name="apostrophe">Don't</string>` — a **deliberately invalid** fixture whose entire purpose is
to make aapt2 emit that error so the guard can be tested against it. aapt2 writes to stderr, GitHub
promotes stderr in a step to an error annotation, and the annotation survives even though the step
concludes `success`. Nothing is wrong, and nothing should be "fixed" in that fixture — escaping the
apostrophe would delete the case the guard exists for. Recorded because an error annotation on a green
run is exactly the kind of thing that costs someone an afternoon.

---

## INF-02 — G4: CI job for the iOS canary

**Status:** blocked by `DEC-05`

The canary works (`-Pcloudstream.iosCanary=true`, both klib targets) and is run by hand. The CI job
was never built because nothing decides which runner pays for macOS time.

Until then, an iOS purity regression — a `java.*` import, a `synchronized`, a `String.format` — is
caught only when somebody remembers to run the canary locally.

---

## INF-03 — Extension ABI guards · **working, worth knowing**

**Status:** done · reference

- `app/api/classes-jar.api` — committed javap-style dump, 2,812 classes, ~37k lines
- `:app:checkExtensionApi` — fails on any drift; `:app:dumpExtensionApi` re-records
- `abiValidation` (`checkKotlinAbi` / `updateKotlinAbi`) on extension-api modules

**Two traps already paid for:**

1. `abiValidation` **does not see AGP KMP android targets**. It covers the jvm dumps only, so it is
   not a substitute for the javap tripwire.
2. A check that must run before a jar task expands its inputs belongs in `doFirst`, not `doLast`. A
   `compileOnly` guard written in `doLast` never fired, because the jar had already expanded.

See also `KMP-09` for the `$stable` trap, which **neither** guard catches on its own.

---

## INF-04 — Test infrastructure gaps

**Status:** open

- `:core:strings` has **no Android unit-test target**, so its android actual is untested — see
  `I18N-06`.
- There is **no iOS test target** anywhere; iOS is compile-checked only.
- `:app` has `junit` for unit tests and Espresso/instancio for instrumented tests. The formatting
  fixes (#25, #26) added the first real measurement harnesses against a device; both were written
  throwaway and deleted, except `SecondsToReadableTest`.
- No screenshot coverage at all — see `DEC-10`.

---

## INF-06 — `jvmTest` runs on the daemon JVM, not the pinned toolchain · **measured 2026-10-02**

**Status:** open · needs a decision before it is fixed (a repo-wide convention change)

`gradle/libs.versions.toml:70` pins `jdkToolchain = "17"`, and exactly **one** place consumes it:
`app/build.gradle.kts:192-195`, for legacy `:app`'s javac. No `jvmToolchain(...)`, `javaLauncher`
or `kotlin.jvmToolchain` exists anywhere in `build-logic/convention/`, and there is no
`gradle/gradle-daemon-jvm.properties`. The conventions set `jvmTarget` — a bytecode level, not a
toolchain. So **every `:core:*` module compiles and tests on whatever JVM started the daemon.**

Proven by `--info`: the worker command line is
`…/corretto-24.0.2/Contents/Home/bin/java … GradleWorkerMain`, and it tracks `JAVA_HOME` exactly.

**What it already broke.** On this machine (`JAVA_HOME` = Corretto 24) two tests of
`LocaleNumberParityTest` fail on clean master; with `JAVA_HOME` set to the pinned Temurin
17.0.20.1 the same command is `BUILD SUCCESSFUL`, 6 tests, 0 failures. Same tree, same commit —
only the JVM changed.

The cause is real, not a flake: JDK 24 bumped the bundled CLDR to 46, which moved the default
numbering system for the **bare, region-less `ar`** from `arab` to `latn`
([JDK-8333582](https://bugs.openjdk.org/browse/JDK-8333582), citing CLDR-17553).

| locale | JDK 17 / 21 | JDK 24 / 25 |
|---|---|---|
| `ar` | `٠` `٫` `٬` | **`0` `.` `,`** |
| `ar-u-nu-arab`, `ar-EG`, `ar-SA` | `٠` `٫` `٬` | unchanged |
| the other 11 pinned locales | — | unchanged |

`localeNumberSymbols()` is **not** wrong on either JDK: the parity tests that compare it with
`java.util.Formatter` pass on both (`ar: 3513 values, 10263 agreed`). Only the two tests holding
hardcoded Arabic literals fail. This is a stale pinned expectation plus an unpinned JVM.

**Why nobody noticed:** all six workflows pin `temurin` / `java-version: 17`, so CI would be green
— if CI ran at all (`INF-01`, `DEC-01`). The divergence is local-only.

- [ ] Decide: pin the test toolchain in `cloudstream.kmp.base` (makes the suite match its KDoc and
      match CI; costs a second JVM launch; defers the `ar` question to the next bump), **or**
      re-measure the table (breaks CI, where the JVM still says `٠` — the two cannot both be
      satisfied by one set of literals, and re-recording is what that test exists to catch)
- [ ] Either way, fix two KDoc claims that rest on the false premise:
      `NumberSymbols.kt:9` and `LocaleNumberParityTest.kt:124` both cite `jdkToolchain = "17"` as
      if it governed this module
- [ ] Until then: run `:core:*` tests with `JAVA_HOME` set to the Temurin 17 the toolchain names,
      or local runs disagree with CI

Same gap, not failing today: `:core:storage`, `:core:http`, `:core:strings:fixtures` jvmTest, and
all Kotlin/Android compilation in every `:core:*` module.

**It also guards the committed ABI baselines, from JDK 21 on** · measured by the owner, 2026-10-07.
`checkKotlinAbi` run with Corretto 21 failed on four methods: `addFirst`, `addLast`, `removeFirst`
and `removeLast` — `java.util.SequencedCollection`, new in Java 21, inherited into the dump by every
`List` implementor. The committed `api/` dumps were written on 17. So the pin is not optional for
`checkKotlinAbi` either, and **`updateKotlinAbi` on a 21+ daemon would bake those inherited methods
into the baseline.**

---

## INF-05 — Local workflow notes

Reference, so the same half-hour is not lost twice.

**Worktrees.** One per PR, under `/Volumes/Projects/CloudStream/app-*`, with `local.properties`
copied. Gradle in a worktree needs `-x :app:generateGitHash`, because `.git` is a file there rather
than a directory.

**zsh does not word-split.** `G="./gradlew …"; $G build` exits 127 in zsh. This silently turned a
whole verification pass into a no-op once — every command "succeeded" by never running. Write
multi-step checks as bash scripts.

**An up-to-date `check` reports stale test results.** Gradle skips the task and the previous run's
XML files are still on disk, so a verification pass reads them as fresh. Use `--rerun-tasks` and
check the `timestamp` attribute when a test count is load-bearing.

**Verify by re-running, not by reading.** Every claim in this repo's task lists that turned out to
be false was one that a plausible argument supported and nobody had executed — the `dur * 60`
overflow, the "locale-blind" notification, the `Viewmodel.kt` "dead code", the missing ABI dump.

---

## INF-07 — `:app:checkExtensionApi` is red on master since #38 · **measured 2026-10-07**

**Status:** done · #43 (landed through #42's merge, 2026-10-14; see `INF-08`) · decided 2026-10-07:
accepted into the dump

Found while verifying `KMP-06c`, and reproduced as pre-existing there: on master the check reports
0 removed, 0 changed, 1 added:

    +public final class com/lagradost/cloudstream3/ui/result/RunTimeSecondsKt {
    +     public static final fun runTimeMinutesToSeconds (Ljava/lang/Integer;)Ljava/lang/Integer;
    +}

`app/src/main/java/com/lagradost/cloudstream3/ui/result/RunTimeSeconds.kt` arrived with `0e350324`
(#38, `BUG-01`) without a matching `app/api/classes-jar.api` update; the dump last moved in
`a39a56fa`. The check is not part of `check`, and CI has never run (`DEC-01`), so nothing caught it.

The function is declared `internal`, but a **top-level** `internal` function is not name-mangled, so
it reaches `classes.jar` as an ordinary `public static final` — a Kotlin-internal helper on the
extension-facing surface. Purely additive: no published extension breaks either way.

**Decision: accept it into the dump.** `@JvmSynthetic` was the first recommendation, and it was
wrong. Reading `ExtensionApiDump.kt` showed why: the dump records synthetic methods (with a
`synthetic` flag) rather than skipping them, and records every public class whatever its members.
So the annotation would only have changed the added line to `public static final synthetic fun …`;
the block would still be an addition. Accepting it matches how the dump already treats `:app`'s
internals: it holds 289 name-mangled `internal` members by design. And Kotlin extensions cannot call
an `internal` declaration from another module either way; the compiler enforces that from metadata.

- [x] The `RunTimeSecondsKt` block added to `app/api/classes-jar.api`. Meant to ride #41, which
      merged first, so it lands in its own follow-up PR. The first attempt was **written by hand
      and wrong**: it dropped the superclass, ` : java/lang/Object`, which the dump prints on every
      class. It was the only one of the dump's classes without a supertype. Caught when the owner built it (`checkExtensionApi`:
      1 changed); `:app:dumpExtensionApi` produces exactly the corrected line.
- [x] `:app:checkExtensionApi` exits 0 with the corrected line (owner, local, Temurin 17).

**Rule this taught: never hand-write a baseline line.** Regenerate it with `:app:dumpExtensionApi`
(or `updateKotlinAbi` on 17) and review the diff. A transcription from failure output loses exactly
the details the dump exists to keep; ` : java/lang/Object` has now been that trap twice.

---

## INF-08 — CI's `Check classes.jar ABI` step is killed by the runner · **measured 2026-10-14**

**Status:** open · needs a decision, because it changes a CI gate

`:app:checkExtensionApi` depends on `makeJar`, which depends on `build`, so the step builds every
release variant after `Run Gradle` has already run. On `ubuntu-latest` the runner kills it during
the release dex build, with no Gradle error:

| Run | Head | Ended |
|---|---|---|
| 37647544550 | #43 `a3314cc8` | runner shutdown signal, after `compilePrereleaseReleaseArtProfile` |
| 37654871353 | #43 `05502bb3` | runner shutdown signal, after `compileStableReleaseKotlin` |
| 38551943012 attempt 1 | #43 `c2ce96ca` | `The operation was canceled`, during `dexBuilderStableRelease` |
| 38551943012 attempt 2 | #43 `c2ce96ca` | `Process completed with exit code 143` (SIGTERM), during `dexBuilderPrereleaseRelease` |
| 38613588107 | **#54** `1202400d` | `Process completed with exit code 143` (SIGTERM) after `compileStableReleaseKotlin`, at 28m57s. **0** occurrences of `What went wrong:` in the whole log |
| 38613700278 | **#56** `83e87fa6` | step **`cancelled`**. **0** occurrences of `What went wrong:`. #56 is **docs-only** — one file, `docs/tasks/defects.md` |
| 38616851249 | **#60** `995c41e6` | `exit code 143` during `dexBuilderStableRelease`. **0** occurrences of `What went wrong:`. **docs-only** — `docs/HANDOVER.md` + `docs/tasks/kmp.md` |

The same step passed on #42 in runs 37647589593 (`bce90a8f`) and 38552020713 (`b8aeffd3`). The
second of those trees contains #43's head. The workflow sets no `timeout-minutes` and no
`concurrency`, so nothing in the repo cancels the step. Memory pressure on the runner is the
likely cause, but that is **not measured**.

**Until this is fixed:** read a red run's log before believing it (`gh run view <id> --log-failed`).
A run that ends in a cancel or exit 143, with no `What went wrong:` before it, never ran the check.

**2026-10-14: two occurrences outside #43, and they narrow the cause to the step.** Run 38613588107
is on **#54**, whose entire diff is `maxLines="1"` → `maxLines="2"` in one XML layout plus a docs
entry. Run 38613700278 is on **#56**, which is **docs-only** — a single `docs/tasks/defects.md` file,
not one byte of code, resource or build script. A diff that cannot affect the build at all produces
the same death, so the diff is not the variable. The two earlier steps passed on the same run
(`Ensure binary compatibility`, then `Run Gradle` with the full `assemblePrereleaseDebug lint check`),
so the job reached the third step healthy and died there. That rules out #43's *content* as the cause
and points at the **step**: it builds every release variant *after* `Run Gradle` has already run, in
the same job, on a runner at `gradle.properties`' `-Xmx4G` — which `docs/HANDOVER.md` already records
as OOMing D8 in a full run. Still a hypothesis: nothing has measured the runner's memory.

**The sharpest statement the data now supports.** Seven `Artifact Build` runs on 2026-10-14, same
runner fleet, same day, same workflow:

| | Runs | Diff |
|---|---|---|
| `Check classes.jar ABI` **passed** | #53, #55, #57, #58, #61 | real code changes, incl. a `:library` behaviour change with 14 new tests and an `:app` player edit |
| `Check classes.jar ABI` **died** | #54, #56, #60 | one XML attribute; one docs file; two docs files |

**The three that died had the smallest diffs, and two of them cannot affect the build at all.** The
outcome is inversely correlated with the work the diff implies, which is the opposite of what a
content-driven cause would produce. It is runner variance. It also fails three different ways —
`exit 143` after Kotlin compilation, `exit 143` during `dexBuilderStableRelease`, and a bare
`cancelled` — which is what resource exhaustion looks like, not a deterministic task failure.

Practical consequence while it is unfixed: a red tick on this repo means *read the log*. Two of the
three gates run **before** the fragile step, so a run that dies at `Check classes.jar ABI` has still
exercised `checkKotlinAbi` and the whole `lint check`.

**Diagnostic trap:** the step dies two ways, and only one of them leaves a log. On #54 it was
`exit code 143`, which `gh run view <id> --log-failed` shows. On #56 the step was **`cancelled`**, and
`--log-failed` printed **nothing at all** — an empty result reads like "no failure recorded" when it
actually means the step was killed before it could fail. Use
`gh run view <id> --json jobs --jq '.jobs[].steps[] | "\(.name): \(.conclusion)"'` to see the step
conclusions; that is what distinguishes a cancelled step from a green run.

Options, for the owner: make `makeJar` depend on what it actually reads instead of `build`;
build one variant in that step; raise Gradle/Kotlin daemon memory limits for CI; or use a larger
runner. Each one changes a gate, so each wants its own PR, with a run showing it stays green.

---

## INF-09 — every push to master fails three workflows · **measured 2026-10-14**

**Status:** done · first option taken on 2026-10-14 · **not caused by any merged PR**

`Pre-release`, `Archive build` and `Dokka` all fail on **every** push to `master`, in 10–15
seconds, at their first step. `gh run list` over the last 40 runs: **14 failed master push runs, 0
successful**, the oldest 2026-10-03 (the first merge after CI started working, `DEC-01`) and the
newest 2026-10-14 (#44's merge).

The cause is the same in all three and fully diagnosed:

    Error: Input required and not supplied: app_id
      at getInput (.../tibdex/github-app-token/v2/dist/main/index.js:1:3828)

Each one's first step mints a token for an **upstream** repository through
`tibdex/github-app-token@v2` with `app_id: ${{ secrets.GH_APP_ID }}`:

| Workflow | File | Target repository |
|---|---|---|
| `Pre-release` | `.github/workflows/prerelease.yml:26-28` | `recloudstream/secrets` |
| `Archive build` | `.github/workflows/build_to_archive.yml:27-37` | `recloudstream/secrets`, `recloudstream/cloudstream-archive` |
| `Dokka` | `.github/workflows/generate_dokka.yml:24-26` | `recloudstream/dokka` |

`secrets.GH_APP_ID` is not set in this fork, so the action gets an empty string and exits before
anything is built. The three workflows are publishing jobs that only the upstream repository can
run; there is nothing for them to do here even with a token.

**Why it matters, and why it is not urgent:** nothing it builds is used, and `Artifact Build`
(`pull_request.yml`), the workflow that actually gates merges, is unaffected — it mints no token.
The cost is that **master's commit status is permanently red**, so a genuinely broken master would
look exactly like today. It also charges three runner starts per merge.

Options, for the owner:

- gate each job on the repository, e.g. `if: github.repository == 'recloudstream/cloudstream'` —
  smallest change, keeps the files identical to upstream's otherwise, and they resume working if
  the fork is ever merged back;
- set `GH_APP_ID` and the private key as fork secrets and point the targets at fork repositories —
  only worth it if prereleases and Dokka are actually wanted from here;
- disable the three workflows in the Actions tab — leaves no trace in the repo, so the next person
  rediscovers this.

Each changes what CI does on master, so each wants its own PR with a green run behind it.

**Decision taken: option 1.** Each of the three publishing workflows now has a job-level
`if: github.repository == 'recloudstream/cloudstream'` as a sibling of `runs-on:` on its single
`build` job. Nothing else in the three files changed (`on:`, `permissions:`, `concurrency:`, action
versions and step contents are untouched). A job skipped by a job-level `if` never starts a runner,
so the gate costs nothing per merge. On this fork the three jobs are skipped, so master's commit
status no longer carries three permanent failures, and they resume unchanged if the fork is ever
merged back. Slug evidence: the Dokka job's own commit message names `recloudstream/cloudstream@`,
NOTICE says the vendored code came from `recloudstream/cloudstream`, and the README and issue
template link to `github.com/recloudstream/cloudstream`. The fork's `origin` is
`darkmat13r/cloudstreamv4`, which is not the upstream. Validation: all workflow files parse with
Ruby's YAML loader (PyYAML is not installed here); the gate was not run on GitHub.

**Also found 2026-10-14, 21:38 local:** all five workflows in this repository were disabled by hand
in the Actions tab, including `Artifact Build` (`pull_request.yml`). That one is the merge gate and
is **not** one of the three broken workflows. Gating the three in-file is what makes re-enabling
them safe: once they are skipped here, re-enabling them no longer turns master red. `Artifact
Build` is being re-enabled by the parent session.
