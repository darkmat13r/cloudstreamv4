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
