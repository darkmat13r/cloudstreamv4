# INF — build, CI and guards

---

## INF-01 — CI does not run · **the single largest gap**

**Status:** blocked by `DEC-01`

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
