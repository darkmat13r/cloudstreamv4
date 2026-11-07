# INF — build, CI and guards

---

## INF-01 — CI does not run · **closed: it is not going to**

**Status:** **closed by the owner's answer to `DEC-01`, 2026-10-15 — CI stays off.** Not a task any
more. It ran 2026-10-07 to 2026-10-15; the workflows are all `disabled_manually` by decision, and
**local four-gate runs are the agreed verification**, not a stopgap. What remains of this entry is the
record of what CI found while it ran, and `INF-08` below for the one step the runner kept killing —
parked, since it only bites when CI runs.

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

**Status:** blocked by `DEC-05`, and **moot while CI stays off** (`DEC-01`, answered 2026-10-15). The
canary runs locally in about four seconds and is one of the four local gates, which is the coverage
this was for.

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

- `:core:strings` has **no Android unit-test target** (adding one needs Robolectric, which the catalog
  does not carry), so its android actual was untested. **Closed 2026-10-15** by a device test instead
  — `androidDeviceTest`, via AGP's KMP `withDeviceTestBuilder`, the first device test in any `:core:*`
  module. It found a real divergence: Android's ICU locale data disagrees with the JDK's CLDR for
  `ar`, `fr` and `ru`. See `I18N-06`. Two notes it leaves behind: `androidx.test:runner` has no
  catalog alias (the source set leans on `espresso-core` to get it transitively — a catalog addition
  is the owner's call), and the test cannot run on CI, which has no emulator (`DEC-05`).
- There is **no iOS test target** anywhere; iOS is compile-checked only. **Target wired 2026-10-15**
  — `-Pcloudstream.iosTest=true` / `iosSimulatorTest`, see below. It runs **zero tests today**, and
  the reason is not the target: no module has a `commonTest` source set at all.
- `:app` has `junit` for unit tests and Espresso/instancio for instrumented tests. The formatting
  fixes (#25, #26) added the first real measurement harnesses against a device; both were written
  throwaway and deleted, except `SecondsToReadableTest`.
- Screenshot coverage is on a device only, not on iOS or desktop: see `INF-11`.

### The iOS test gate · `-Pcloudstream.iosTest=true` · wired 2026-10-15

`cloudstream.kmp.library` now carries a second gated, opt-in iOS block beside `iosCanary`. The
property declares `iosSimulatorArm64()` (and only that: `iosArm64` is a device target, has no
`KotlinNativeSimulatorTest` and cannot execute a test binary), links the simulator test binary and
registers a per-module `iosSimulatorTest` task:

    ./gradlew iosSimulatorTest -Pcloudstream.iosTest=true

With the property absent nothing changes — same targets, same tasks, no `~/.konan` download. The
two gates are independent and compose; `IOS_CANARY_EXEMPT` governs both and was **not** widened.

**What this machine already has — checked, not assumed.** Xcode 27.0 (`27A266a`) at
`/Applications/Xcode.app`, iOS Simulator SDK 27.0 in `xcodebuild -showsdks`, five iOS simulator
runtimes (26.3 → 27.0) with available devices in `xcrun simctl list devices available`, and
`kotlin-native-prebuilt-macos-aarch64-2.4.0` already in `~/.konan` from the canary. Nothing is
missing and no dependency is needed. Two things worth recording:

- **Kotlin/Native needs the locally installed Xcode for anything that links.** Every Apple entry in
  `~/.konan/.../konan/konan.properties` resolves to `remote:internal`
  (`target-sysroot-xcode_26.4_17E192-iphonesimulator.default = remote:internal`), i.e. JetBrains'
  bundled Apple toolchain is not publicly downloadable. `minimalXcodeVersion=12.5`, so 27.0 passes
  the version check — but note the pinned reference is Xcode **26.4**, and 27.0 is newer than the
  toolchain Kotlin 2.4.0 was built against. First run is the measurement.
- **No simulator id is pinned, on purpose.** KGP's `XcodeDefaultTestDevicesValueSource` shells out
  to `xcrun simctl list devices available`, takes a device UUID per OS family and sets it as the
  task's convention, so there is nothing to re-pin when Xcode's simulator list moves.
  `--device <udid>` overrides it per run.

**Why the task is wired per module rather than straight to KGP's.** Gradle 9.4.1's
`AbstractTestTask` conventions `failOnNoDiscoveredTests` to `true` — read out of
`gradle-testing-base-9.4.1.jar`, where the constructor calls
`getFailOnNoDiscoveredTests().convention(true)` and the action throws "the test task did not
discover any tests to execute" on an unfiltered run with zero discovered tests. A test task pointed
at an empty `commonTest` therefore reports a *misconfigured build*, not "0 tests". Wiring the gate
naively would have turned it red in all 14 canary modules on day one, red for "there is nothing
here" — the kind of red that gets silenced with a `= false`. So the plugin enables the link task and
depends on `iosSimulatorArm64Test` only for a module with a `.kt` file in one of `commonTest`,
`nativeTest`, `appleTest`, `iosTest`, `iosSimulatorArm64Test`; elsewhere `iosSimulatorTest` names the
module it skipped and why.

**The open half: there is no `commonTest` anywhere.** Measured two ways — a `find` for
`src/commonTest` directories, and a per-module listing of `src/*Test*`:

| where the tests are | count |
|---|---|
| `src/jvmTest` in the 16 modules on this plugin | 18 `.kt` files |
| `:core:strings`'s `jvmTestUiString` srcDir (an extra dir on the same `jvmTest`) | 9 `.kt` files |
| `src/androidDeviceTest` (`:core:strings`) | 1 source set |
| `src/commonTest` in any module on this plugin | **0** |

The only `commonTest` in the repository is `:library`'s, which does not apply this plugin and is not
iOS-compilable (Track L, `KMP-08`). `jvmTest` is a JVM source set: Kotlin/Native never sees it, so
no existing test can run on a simulator without being **moved**, and that is a per-module change
with a real cost, not something a convention plugin does.

- [x] **Done.** Move one suite from `jvmTest` to `commonTest` and let the gate run it — `:feature:search`
      first, then the four `:core:storage` contract suites (see the two dated sections below). The
      candidate list this item was written with, kept because it is still the shortlist for the rest
      — their imports are already `kotlin.test` + `kotlinx-coroutines-test` with **no `java.*` and
      no JVM-only API** (checked by reading every import of each file):
      `:media:model`'s `MediaModelTest` (333 lines, pure model assertions, module has no
      dependencies), `:feature:extensions`' `ExtensionContractsTest` (84 lines), and
      `:feature:search`'s `SearchContentTest` (48 lines). The cost of each: `commonTest` needs its
      own `implementation(libs.kotlin.test)` (the gate deliberately does not add it for every
      module, since `commonTest` is a parent of `jvmTest` *and* of `:core:strings`'s
      `androidDeviceTest`), and a `commonTest` suite then compiles twice instead of once.
      `:feature:search`'s `SearchViewModelTest` is the one to leave for last: it uses
      `Dispatchers.setMain` / `resetMain`, which are multiplatform in `kotlinx-coroutines-test`
      1.11.0 but are the one part of these suites whose Native behaviour has not been measured here.
- [ ] Run the gate once and record the numbers: whether Xcode 27.0 links against a 2.4.0 toolchain
      pinned to Xcode 26.4, and what a Compose module's simulator link costs in wall time (the
      canary's klib-only pass is ~4 s; linking pulls the cinterop-backed Compose UI klibs in).

---

### Verified 2026-10-15 — the gate runs, and three tests now run on iOS

The agent that wired this could not execute anything (Gradle was forbidden in its brief), so every
claim below was measured afterwards:

- `./gradlew iosSimulatorTest -Pcloudstream.iosTest=true --continue` → **exit 0, 14 modules**, each
  printing `iosSimulatorTest: skipped <module> — no test sources reach iOS`. Loud rather than
  vacuously green, which was the point.
- The plugin **did not compile** as written: `doLast { task -> task.logger… }` bound to `doLast`'s
  Groovy `Closure` overload instead of the `Action` one. Naming the lambda parameter is what does
  it; `doLast { logger.lifecycle(…) }` compiles. A by-inspection review missed this, twice.
- **`:feature:search`'s `SearchContentTest` moved from `jvmTest` to `commonTest`**, which is what
  gives the gate a payload. `./gradlew :feature:search:iosSimulatorTest -Pcloudstream.iosTest=true`
  → `> Task :feature:search:iosSimulatorArm64Test`, **3 tests, 0 failures**, on an
  `iosSimulatorArm64` simulator. First tests ever executed on iOS in this project.
- **Kotlin/Native rejects a comma in a backticked test name** — `Name contains illegal characters:
  ","`. Both multi-clause names in that suite had one, and the JVM compiler accepts them silently,
  so the first thing that breaks when a suite moves to `commonTest` is its own name. Recorded in
  the suite's KDoc.

Still open: 13 of the 14 canary modules have no `commonTest` at all, and `SearchViewModelTest` stays
JVM-only because it drives `Dispatchers.setMain` and waits on real time — moving it is its own
measurement. The gate names every one of those modules on each run, so the backlog is self-reporting.

That 13 is now 12: the `:core:*` sweep below moves `:core:storage`.

---

### 2026-10-15 — the `:core:*` sweep: `:core:storage` moves, the rest cannot

Every test source of every `:core:*` module was listed and read (not just its imports — the bodies
too, because a `String.format` in a helper is as fatal as an import). Gradle was forbidden in this
agent's brief, so **nothing below is a run**; the moves are a source change and the numbers are
file counts and `@Test` counts, not test results.

**Moved — `:core:storage`, 4 suites, 48 `@Test` functions, `jvmTest` → `commonTest`:**

| suite | `@Test` | what it pins |
|---|---|---|
| `KeyValueJsonStoreTest` | 15 | the `KeyValueJsonStore` contract, `keysIn` / `removeIn` interface defaults, the folder-key algebra |
| `PreferenceStoreTest` | 18 | the `PreferenceStore` contract, object codecs, the `__PRIVATE_` / `__APP_STATE_` prefixes |
| `SecretStoreTest` | 10 | the `SecretStore` contract and where `KeyValueJsonSecretStore` puts the bytes |
| `JsonRecordEncodingTest` | 5 | that the store is a verbatim carrier for `:app`'s `toJsonLiteral` records |

Why these four and nothing else in the module: all three ports, their in-memory implementations and
the key algebra are `commonMain` (`core/storage/src/commonMain/kotlin/.../` — eight files, grepped
clean of `java.*`, `android.*`, `String.format`, `System.`, `@Jvm*`), and the suites reach nothing
else. Their only non-`kotlin.test` need is `runTest`, used once, in
`PreferenceStoreTest.changes emits the current value before anything happens`, which collects the
first value of a `MutableSharedFlow`-backed `changes()` — no `Dispatchers.setMain`, no real-time
wait, so it avoids the part of `kotlinx-coroutines-test` that `SearchViewModelTest` is parked on.

`core/storage/build.gradle.kts` moves `libs.kotlin.test` **and** `libs.kotlinx.coroutines.test` from
`jvmTest.dependencies` to `commonTest.dependencies` rather than duplicating them: `commonTest` is a
parent source set of `jvmTest`, so the three suites that stay behind keep both. Neither is a new
catalog entry (`gradle/libs.versions.toml:107,111`).

**The file-backed suites keep working, and that is the point.** `FileStoreContractTest` subclasses
all four moved suites with one method overridden, so every case still runs a second time against
`FileKeyValueJsonStore` / `FilePreferenceStore` on the JVM. The contract now runs on three targets
(JVM in-memory, JVM file-backed, iOS simulator in-memory) from one copy of the assertions.

**Seven test names were reworded** for the Kotlin/Native comma rule — three in
`KeyValueJsonStoreTest`, two in `SecretStoreTest`, one each in the other two. Each
rewording drops a comma and keeps the clause (`removeIn commits once, whatever the folder holds` →
`removeIn commits once whatever the folder holds`). Non-ASCII was avoided in the new names: the
illegal-character set Kotlin/Native enforces is only known here to include `,`, and an em dash is
not worth finding out about on a gate run.

**Could not move, with the one blocking reason each.** This is the useful half of the list: it says
where the real work is.

| module | suite (lines) | the blocker |
|---|---|---|
| `:core:storage` | `FileStorageTest` (448) | `java.nio.file.Files`, `PosixFileAttributeView`, `StandardCharsets` — it tests persistence, atomic replace, corruption quarantine and file permissions of a `jvmMain` implementation. There is nothing to move: the subject is the JVM. |
| `:core:storage` | `FileStoreContractTest` (129) | `Files.createTempDirectory` in its `TemporaryStorageDirectory`. Moving it is also pointless now — it subclasses the four suites that did move, so its contract already reaches iOS through them. |
| `:core:storage` | `JvmStorageTest` (168) | `java.nio.file` plus the `APPDATA` / `XDG_*` environment algebra of `jvmMain`'s `JvmStorageDirectory`. Same reason as `FileStorageTest`. |
| `:core:http` | `OkHttpClientFactoryTest` (122) | the subject is `jvmCommonMain`'s OkHttp actual: `okhttp3.*`, `java.util.concurrent.Executors` / `CompletableFuture`, `java.io.IOException`. A Native equivalent would be a different test against a Darwin engine, not this one moved. |
| `:core:strings` | `GeneratedResourcesParityTest` (74), `LocaleLookupParityTest` (95) | both read the generator's aapt2-decoded TSV oracles through `StringsOracle`, which is `java.io.File` + `System.getProperty("strings.oracles")` (`core/strings/src/jvmTest/.../StringsOracle.kt:23-24`, fed by `StringsOracleArgument` in `cloudstream.kmp.strings`). `GeneratedResourcesParityTest` adds `java.util.Base64`. |
| `:core:strings` (`jvmTestUiString`) | `FormatterParityTest` (233), `LocaleNumberParityTest` (219) | `java.util.Locale` and `java.util.Formatter` **are the oracle under test** — the suite exists to compare `UiString` with the JDK. |
| `:core:strings` (`jvmTestUiString`) | `OracleBaselineTest` (102) | `java.io.File` over the committed `oracle-baseline/`, located by the `jvmArgumentProviders` block in `core/strings/build.gradle.kts`. |
| `:core:strings` (`jvmTestUiString`) | `UiStringTest` (156), `UiStringResolveTest` (134) | `StringsOracle` again, and `HeadlessComposition`'s `java.lang.reflect.Proxy`. |
| `:core:strings` (`jvmTestUiString`) | `CmpPluralGuardCanaryTest` (65) | **the nearest miss.** Its own imports are clean — `kotlin.test`, `runBlocking`, `getPluralString`. It is blocked on its helper: `ComposeRuntime` reaches `ResourceEnvironment`'s constructor and three internal functions by `Class.forName` / `declaredConstructors` / `isAccessible`, which is JVM reflection with no Native equivalent. Making it portable means a public way to build a `ResourceEnvironment`, not a file move. |
| `:core:strings:fixtures` | `FixtureGoldenDecodeTest` (28), `FixtureLocaleCopyTest` (31) | `StringsOracle` once more. |

Two traps `:core:strings` carries for anyone who tries again:

- `core/strings/fixtures/build.gradle.kts:28-30` attaches **`:core:strings`'s own `src/jvmTest/kotlin`**
  via `kotlin.srcDir("../src/jvmTest/kotlin")`, so moving a file out of that directory silently
  removes it from the fixtures module's compilation as well.
- `commonTest` in `:core:strings` is a parent of its `androidDeviceTest` too (`withDeviceTestBuilder
  { sourceSetTreeName = "test" }`), so anything moved there starts running inside the instrumented
  APK. That is a second gate to re-measure, not a free move.

**Out of this agent's scope, and still the biggest single win:** `:media:model`'s `MediaModelTest`
(333 lines, pure model assertions, module has no dependencies) and `:feature:extensions`'
`ExtensionContractsTest` (84 lines). The shortlist above has said so since the gate was wired; this
brief was `:core:*` only.

- [ ] Run `./gradlew :core:storage:iosSimulatorTest -Pcloudstream.iosTest=true` and record: whether
      all 48 cases compile and pass on Native, and whether anything beyond `,` is an illegal
      character in a backticked name (seven names changed; hyphens and apostrophes were kept,
      unmeasured)
- [ ] Run gate 2 (`check`) and confirm the JVM side is unchanged — the same 48 cases plus the
      file-backed subclasses, now from `commonTest`


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
- [x] **Done 2026-10-14.** The two KDoc claims that rested on the false premise are corrected.
      `NumberSymbols.kt` no longer cites `jdkToolchain = "17"` as the reason its table is what it is;
      it now says in as many words that **no** `jvmToolchain(…)` exists in `build-logic/convention/`,
      that the module runs on whatever JVM started the daemon, and that `JAVA_HOME` is the only pin.
      `LocaleNumberParityTest` says the same where it pins the symbol table, and names the JDK 24 /
      CLDR 46 `ar` change as a platform move rather than a regression. This is the half of `INF-06`
      that needed no decision
- [ ] **The actual fix is blocked on a dependency decision, which is why this entry stays open.**
      Pinning the test toolchain means `jvmToolchain(17)` in `cloudstream.kmp.base`, and Gradle can
      only satisfy that by auto-detecting a local JDK 17 or **downloading one** — and auto-download
      needs the `foojay-resolver-convention` plugin in `settings.gradle.kts`, which this repository
      does not have and which is a new dependency (`.claude/rules/dependency-security.md`: ask
      first). Measured: no `foojay`, no `toolchainManagement`, no `jvmToolchain` anywhere in
      `settings.gradle.kts`, `build-logic/` or `gradle/libs.versions.toml`. Without the resolver the
      pin works on this machine and on CI (both have a 17) and **fails for anyone else** with "No
      matching toolchain, auto-detection enabled, auto-download disabled". So: adding the resolver is
      the owner's call
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

**Worktrees.** One per PR, beside the primary checkout, with `local.properties`
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

**Status:** **parked 2026-10-15** — it only bites when CI runs, and `DEC-01` settled that CI stays
off. The measurements below are kept intact for whenever that is reversed; nothing here needs doing
while the workflows are disabled.

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
| 38616558027 | **#62** `dbbeae37` | `The operation was canceled`. **docs-only** — `docs/tasks/decisions.md`. **Died again on attempt 2** |
| 38617261395 | **#63** `87150022` | `The operation was canceled`. **docs-only** — two `docs/tasks/` files |

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

**The full picture, from twelve PRs run on 2026-10-14** — same fleet, same day, same workflow, and
the largest sample this failure has ever had.

> **Corrected 2026-10-15.** An earlier version of this entry claimed the step "split perfectly" by
> content — *five of five trivial changes died; seven of seven real ones passed* — and concluded that
> this **inverted** the content hypothesis. **That was a sampling artefact and it is wrong.** It was
> built from one run per PR, and `gh api .../runs/<id>/jobs` returns only the **latest attempt**
> (see the traps below), so a re-run PR looked as though it had never failed and an earlier failing
> run on a different commit was missed entirely. Re-read per attempt, the content line does not
> exist. The paragraphs below replace that claim.

**Measured per attempt**, across every `pull_request.yml` run in the 2026-10-14 fleet — 19 runs, 22
attempts of the `Check classes.jar ABI` step:

| Attempt-1 outcome | Count |
|---|---|
| success | 10 |
| `cancelled` or `failure` | 8 |

**The content hypothesis is dead in both directions**, which is a stronger and simpler statement than
the inversion the entry used to claim:

- A **real-code** PR died. Run `38615342081` on `claude/bug-08-addDate-device-zone` — the `:library`
  behaviour change with 14 new tests — was **`cancelled` at 7m49s** on its first attempt.
- A **docs-only** PR passed first time. Run `38615910413` on `claude/docs-settings-walkthrough`
  succeeded on attempt 1, in 13m26s.

So the step's outcome is **independent of what the PR contains.** Combined with the fact that
re-running an unchanged tree flips the result — #54, #56, #60 and #63 all went green on attempt 2 —
everything about the tree is ruled out and the runner is what is left.

**Re-running is still not a remedy.** `claude/dec-12-keep-both` (#62, docs-only) was `cancelled` on
**both** attempts, at 11m56s and then 5m47s.

**Where the time goes, and why it is always this step.** From the GitHub API, the three Gradle steps
in a green run:

| Step | Duration |
|---|---|
| `Ensure binary compatibility` (`checkKotlinAbi`) | 31–34 s |
| `Run Gradle` (`assemblePrereleaseDebug lint check`) | 8m49s – 9m48s |
| **`Check classes.jar ABI`** (`:app:checkExtensionApi`) | **8m40s – 13m26s** |

This is not a cheap tail check — it is about half the job's wall-clock, and it is the only step that
has ever died. It builds the **stable release** variant, which `Run Gradle` never built, which is why
`dexBuilderStableRelease` appears in one of the failure modes.

**Termination times do not look like a timeout.** Successful attempts took 8m40s–13m26s; terminated
ones died at 2m46s, 5m14s, 5m47s, 7m03s, 7m49s, 8m22s, 10m55s and 11m56s. The ranges overlap, so
there is no limit being hit — the step is simply long, and termination strikes at an arbitrary point
inside it.

**Two leads chased and disproved on 2026-10-15** — recorded so nobody spends the time again:

- **Not the Gradle heap, on this evidence.** CI inherits `org.gradle.jvmargs=-Xmx4G` from
  `gradle.properties`, and `CLAUDE.md` does say 4G OOMs D8 in a full run locally, so this looks
  compelling. But the one memory message in a green run's log — *"The current JVM process isn't
  compatible with build requirement. The maximum heap size is insufficient."* — comes from
  `setup-gradle`'s **post-job cache cleanup** (`Executing Gradle to clean up caches`, `--no-daemon`,
  512m), not from any build step. It is benign and proves nothing about the ABI step. The heap
  hypothesis is still open; this log line is not evidence for it.
- **Not disk exhaustion, on this evidence.** No `no space left on device` anywhere, and the restored
  Gradle caches total roughly 1.4 GB across eight entries.

**Two more diagnostic traps, both of which make a failure look like something else:**

- **`gh api .../actions/runs/<id>/jobs` returns only the latest attempt.** A PR that was re-run shows
  its *green* attempt and no sign it ever failed — which is how the retracted content claim above was
  produced. Use `.../runs/<id>/attempts/<n>/jobs`, and read `.run_attempt` first to know how many
  there are.
- **The killed job's log is not retained at all.** `gh run view 38619491792 --log` answers
  `log not found: 116042985447`. So `--log-failed` printing nothing is not a quirk of the cancelled
  variant — for those runs *there is no log to read*, and the cause cannot be recovered from CI after
  the fact. Capture what you need from the step conclusions while the run is fresh.

It fails **four** distinct ways — `exit 143` after Kotlin compilation, `exit 143` during
`dexBuilderStableRelease`, a bare `cancelled`, and `The operation was canceled` — which is what
resource exhaustion looks like, not a deterministic task failure.

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

---

## INF-10 — Android UI automation: the device-test harness · **measured 2026-11-04**

**Status:** in pr (the PR that adds this entry) · the owner approved these test-scope dependencies:
`androidx.compose.ui:ui-test-junit4` and `ui-test-manifest` 1.12.0, `androidx.test.uiautomator:uiautomator`
2.4.0, `androidx.test:runner` and `rules` 1.7.0 (latest stable).

**Where a test lives.** `androidDeviceTest` in the feature's own module, through
`cloudstream.kmp.device-test`. The TV screens are in `:feature:*` `commonMain` and most have no Android
host, so `:app`'s `androidTest` could only reach them through the whole app, with extension loading and
providers behind it. In the feature, the test APK holds that module and its dependencies: a fake at the
module's seam (`SearchSource`), the real ViewModel, no network. `:app`'s `androidTest` is kept for what
only the real app can show, which is navigation. `SearchScreenTvDeviceTest` is the template.

**What it shares.** `:core:devicetest`: `DpadDriver` presses keys and reads focus, `walk()` maps a
screen, and `FocusGraph.unreachable` / `deadEnds` / `traps` answer the three clauses of "every focusable
reachable, no dead ends, no traps".

**Measured, each one a wrong turn first:**

- `androidxComposeUi` says 1.11.0 and is stale. `:feature:search`'s `androidDeviceTestRuntimeClasspath`
  resolves `androidx.compose.ui:ui`, `runtime` and `foundation` to **1.12.0**, so the test libraries are
  pinned to 1.12.0 in their own version key. `androidxComposeUi` still serves `:core:screenshot` and was
  left alone.
- Compose's resource plugin fails the test APK in a module without `androidResources`
  (`copyAndroidDeviceTestComposeResourcesToAndroidAssets` has no output directory). The convention
  gives it one. Strings from `:core:strings` load in the test APK with no further setup.
- **`UiDevice.pressDPadDown()` makes a text field look like a trap.** All injected keys arrive as device
  -1 (`input dpad keyevent` too; `sendevent` is denied by SELinux on the android-tv image), and Compose's
  field moves focus for a virtual device only when the source is `SOURCE_HDMI`. `DpadDriver` sends that.
- The open keyboard takes the D-pad, which is correct on a TV, so a test closes it first.
- The accessibility tree trails the screen by up to 2 s here: straight after a press it still names the
  old stop, which reads as a stuck field. A screen test reads focus from semantics (`rule.focusedLabel()`);
  the real app has no rule, so the default reader waits.
- `pm uninstall` straight after `sys.boot_completed` fails with `DELETE_FAILED_INTERNAL_ERROR` on two cores,
  so `scripts/device-tests.sh` waits 20 s past boot.

**What the first run found on `:feature:search` (`Television_720p`, API 36), reported rather than changed:**

- **Landing on the query field with the D-pad opens the keyboard**, and the keyboard then takes the D-pad until
  Back closes it. Moving Up from a card and then Right to `Sources` needs a Back in between.
  `FocusGraph.keyboardOpenedOn` records it. Fixed by `BUG-30`, and the test now asserts the keyboard does not open.
- **Down from the field lands on the first rail's third card**, the nearest to the field's centre, not its first.
- **Right from the last card of a rail goes to `Sources`**, above it, instead of staying on the rail.

**A red JVM test can come back green from the build cache, measured on `HostLimitsTest`.** On #91's tree the
XML read `failures="1"` while `:feature:extensions:sandbox:jvmTest` was `FROM-CACHE` and `allTests` and `check` both
exited 0. `allTests --rerun` does not help, because it reruns the aggregate only. To be sure of a suite, run
`<module>:jvmTest --rerun` and read the XML. Not fixed here: it is existing build behaviour, and it needs its own
decision.

**Follow-up found by `KMP-38`: `DpadDriver` has no public BACK.** `closeKeyboard()` sends one, but only while
the keyboard is up, so a test that dismisses a panel, a dialog or a destination has to reimplement the event by
hand — both phases, `KeyCharacterMap.VIRTUAL_KEYBOARD`, `InputDevice.SOURCE_HDMI`, through
`uiAutomation.injectInputEvent`. `DetailScreenTvDeviceTest` does exactly that. **`UiDevice.pressBack()` is not a
substitute**: it goes past a focused Compose panel to the activity and finishes it, which is how that test first
failed with *"No compose hierarchies found in the app"*. A `back()` on the driver beside `center()` and
`enterKey()`, with `closeKeyboard()` using it, would stop every TV screen test reinventing it.

**The gate.** The sixth, device-only, not part of `check`: `docs/HANDOVER.md`. `Television_720p` is
1280x720 at 213 dpi, about 961.5x540.8 dp rather than the artboard's 960x540, so no test asserts full-screen
geometry against the artboard.

## INF-11 — Compose screenshot tests on the emulator · **measured 2026-11-05**

**Status:** in pr #151 · answers `DEC-10`, unblocks `DS-04` · no new dependency.

`assertScreenshot` in `:core:devicetest` captures the window with `captureToImage` and compares every pixel with a committed PNG. How to use
it is in `docs/HANDOVER.md`, what it cannot catch is in `.claude/rules/screenshot-testing.md`.

**Measured, each one a wrong turn first:**

- **`src/androidDeviceTest/assets` is not packaged.** A probe file there was missing from the test APK, while one in
  `src/androidDeviceTest/resources` was present, so references are Java resources, read with the class loader.
- **What the app writes does not survive the run.** Gradle uninstalls the test APK at the end, so images are copied by a shell command to
  `/data/local/tmp/cloudstream-shots`, and `scripts/device-tests.sh` pulls them before it stops the emulator.
- **A node's capture loses the ring.** `node.captureToImage()` holds no ring pixel, so `assertScreenshot(name, node)` crops the window to the node
  plus `FocusRingBleed`. `screenshotCaptureHoldsTheRingOutsideTheCardAndTheLiveColour` checks both, and that `live` arrives exact.
- **No tolerance.** See below.

**Stability, `comparisons.tsv` on `Television_720p`:** references recorded on two boots and compared on later boots: 0 pixels
different and largest delta 0, in all 6 cases, each time.

**The failure run,** with temporary changes, all reverted:

| Change | Case | Result |
|---|---|---|
| `Live` `0xCD3D4B` to `0xCD3D4C`, one step of one channel | `search-results-focus-card` | 215 pixels, largest delta 1 |
| The same | `search-partial-failure-focus-retry` | 316 pixels, largest delta 1 |
| `ContentInsetBottom` + 4 dp | `search-history-focus-row` | 1674 pixels, largest delta 255; the diff shows `Clear all searches` moved |
| Reference shrunk to 160x90 | `search-no-results-focus-field` | Fails, naming both sizes |
| Reference deleted | `nav-rail-collapsed` | Fails, "A missing reference fails" |
| Nothing | `nav-rail-expanded-focus-current` | Passes |

**What the first run found, reported rather than changed:** on `search-history-focus-row` the top of the ring is cut off, filed as `BUG-37`. The
reference holds that state and stays as it is; the fix will re-record it.

**After `#150` merged** (the chip and segment ring on the visual), a compare run on the merged tree differed in 0 pixels in all six cases. None of them
focuses a chip or a segment, and an unfocused chip draws no ring, so nothing was re-recorded.

## INF-12 — The public repo: no designs, no project or session links, no local paths · **done 2026-11-07**

The owner's rulings of 2026-11-07, after `#204` made the repo public:
- **Designs stay out of the repo.** The 16 tracked files under `design/` are untracked with `git rm --cached -r design`.
  `#204` already ignores `/design/`. The files stay on the owner's disk and in the Claude Design project. A
  `design/X.dc.html` in these docs now means that board file in the owner's Claude Design project, as
  `docs/HANDOVER.md` and `screen-implementation-handover.md` §2 say.
- **No Claude Design project links.** The two project URLs in each of `docs/HANDOVER.md` and
  `docs/plans/screen-implementation-handover.md` are now "the owner's Claude Design project". No `claude.ai/code` link was
  tracked.
- **No local paths in docs or comments:**
  - The shared review folder is "the shared local review folder" (`HANDOVER.md`, `design-system.md`, the comment in
    `scripts/device-tests.sh`).
  - The claim script is "the shared claim script".
  - The checkout and the worktrees are named by role (`HANDOVER.md`, `infrastructure.md`, two plans).
- **Left, on purpose:**
  - `docs/tasks/defects.md:1239` and `:2047–2060`, plus the redaction and sandbox tests: they are the paths a sandbox
    or a redaction must handle, not this machine's.
  - `scripts/device-tests.sh:91`, `review=/private/tmp/…`: code, not a comment. Moving the folder is a behaviour change
    for every session that reads it, so it is not in this PR.
- Pushed history keeps its commit footers (owner: leave history). New commits carry only the `Co-Authored-By` line, and
  PR bodies carry no local paths or session links.
