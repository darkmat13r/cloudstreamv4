# Handover — 2026-11-03

This file orients a new session. It records what is done, what is open, what waits on the owner, and
the local facts that cost an hour each when someone rediscovers them.

`docs/tasks/` is the live task list and stays authoritative. This file is the orientation.

---

## Before you do anything: check the remote

**Run `git fetch` and `gh pr list --state all` first.** Cloud sessions push branches and open PRs that
a local checkout, or a handover pasted into a prompt, does not know about. On 2026-10-14 a pasted
brief said master was `f29ff63a` and that `KMP-06b` was next. In fact `06c` had already merged, and
`06b` and its follow-up were open PRs. Two parallel agents were started on work that already existed.

This file is the second-most-likely thing in the repository to be out of date, after a pasted brief.
It was itself four days stale on 2026-10-15 — still naming `493e1273` as master with three PRs open,
when twenty-three more had merged since. **Trust `git log` over this heading.**

---

## Where things stand

Master is at `26acd8f8` (#104). **One pull request is open: #91**, a **draft** — `KMP-12` steps 3 and 4,
Android TV opening the new search screen. Its own body says *do not merge it until someone has tried it on
a real TV*; there is no device here, so it has been compiled and never run. Everything from #45 to #104 has
merged.

**The desktop app is the working app.** That was the owner's direction — desktop first, then the other
platforms — and as of #104 it browses and plays:

| | What works | Where |
|---|---|---|
| Extensions | Official jars and `.cs3` files, each scanned, each converted without network, each run in its own Seatbelt child process; an extension with scan warnings stays off until that exact file is allowed | `KMP-14` #82, `KMP-18` #94, `KMP-22` #95, `KMP-24` #102 |
| Home | `MainAPI.getMainPage` per provider — rows for a chosen source, a source picker, more of a row on request | `KMP-20` #92 |
| Search | The MVI state machine with per-source partial results, history, and the real plugins answering | `KMP-12`, `BUG-11` #84, `BUG-12` #86 |
| Detail | The TV detail page, a source list, and a title opening into playback | `KMP-15` #83 |
| Playback | An embedded Compose player over the user's installed VLC through vlcj, with **libVLC in its own sandboxed child process** — one per stream | `KMP-15` #83, `KMP-16` #85 |
| Subtitles | End to end: tracks offered, chosen, parsed and drawn | `KMP-21` #90, `KMP-25` #101 |
| Look | The Tabler icon pack, and desktop's own screens following Apple's principles | `DEC-11` #93, `KMP-17` #87 |

**What the desktop app still does not have:** downloads, settings, or an account. The library arrived in
`KMP-26` — Continue Watching, six lists, watch progress and resume — which closed the gap `KMP-15` named.

**Never verified, on any of the above:** the live window's keyboard and pointer input. Every screen was
driven offscreen through `ImageComposeScene` and real ViewModels. Audible sound has never been listened to.

**CI is switched off, by the owner's decision.** See the next section before you look for a tick.

**The iOS canary is 18 modules**, measured on 2026-11-03 — `:core:common`, `:core:data`,
`:core:designsystem`, `:core:http`, `:core:mihon`, `:core:network`, `:core:storage`, `:core:strings`,
`:core:strings:fixtures`, `:core:theme`, `:core:ui`, `:feature:detail`, `:feature:extensions`,
`:feature:home`, `:feature:library`, `:feature:search`, `:media:model`, `:media:subtitle`. 29 s cold, about
4 s warm:

    ./gradlew iosCanary -Pcloudstream.iosCanary=true --continue

The fifth, opt-in gate runs them for real, and since `BUG-20` it is green: **219 `commonTest` assertions on
an iOS simulator, 0 failures.** It is worth running whenever a `commonTest` is added.

    ./gradlew iosSimulatorTest -Pcloudstream.iosTest=true

**Phase 7 stands at four verticals.** None of them has repointed anything in `:app` except search, and
search's repoint is the draft PR above:

| | Module | Done | Not done |
|---|---|---|---|
| `KMP-11` (row 17) | `:feature:extensions` | The contracts, the `ExtensionRuntime` seam, and `AndroidExtensionRuntime` over an unedited `PluginManager` (#89). The owner's `F1`/`F2` answers are recorded (#100) | **Nothing in `:app` calls it, and it has never run on a device.** `install` refuses |
| `KMP-12` (row 18) | `:feature:search` | Steps 1–3: the contracts, the MVI machine, the TV screen, and the `SearchSource`/`SearchHistoryStore` implementations for **both** hosts | Step 4 is the open draft #91; step 5 (deleting the old fragment) is a release away. The touch screen does not exist |
| `KMP-20` | `:feature:home` | The `HomeSource` seam and the desktop screen | **The TV screen**, though `design/TV Home.dc.html` draws it. The chosen source is not remembered across launches |
| `KMP-15`/`16`/`25` | `:feature:detail`, `:media:subtitle` | The desktop detail page, source list, player and subtitles | Source and audio selectors (artboard 7e). No Android host |
| `KMP-26` | `:feature:library` | Continue Watching, six lists, watch progress, resume, and a file store beside the search history | A bookmark button on the detail page, progress on search and home cards, sorting, Android |

`:app`'s `SearchFragment`, `SearchViewModel` and home fragments are untouched and still the live Android
path. The Android app is unchanged by all of the desktop work.

---

## CI does not run, and that is the decision

**`DEC-01` was answered by the owner on 2026-10-15: the GitHub Actions billing failure will not be
fixed, and CI stays off.** All five workflows are `disabled_manually`:

    .github/workflows/pull_request.yml        # was the merge gate
    .github/workflows/instrumented-tests.yml
    .github/workflows/prerelease.yml          # "Pre-release"
    .github/workflows/build_to_archive.yml    # "Archive build"
    .github/workflows/generate_dokka.yml      # "Dokka"

**Do not re-enable them.** `pull_request` fires on every push to a PR branch and
`instrumented-tests` fires on every PR *comment*, so leaving any of them on burns runner minutes
the account cannot pay for. Verify with
`gh api repos/darkmat13r/cloudstreamv4/actions/workflows --jq '.workflows[] | "\(.name): \(.state)"'`.

**The four local gates below are the agreed verification, not a stopgap.** `pull_request.yml` was
the merge gate, so what replaces it is the discipline: every PR body names its own `abi` / `check` /
`extapi` / `canary` exit codes, and a PR with no tick is expected rather than a problem to chase.
#48–#51 are the counterexample worth remembering — CI had been disabled by hand on 2026-10-14 and
they merged with no checks at all, which nobody noticed until the next day.

What the billing failure looked like, for recognition: a run that ends in **~2 seconds with zero
steps** and the annotation *"The job was not started because recent account payments have failed or
your spending limit needs to be increased."* No check ran. A red tick of that shape says nothing
about the code, and re-running it is pointless.

CI did run from **2026-10-07 to 2026-10-15** (`INF-01`, now closed; first full green run on #42).
Before 2026-10-07 it had never run on this repository's pull requests at all — 38 PRs merged with
none. Outside that one-week window, **every check quoted in a PR body is a local run.**

`INF-08` — the runner killing the `Check classes.jar ABI` step at random — is **parked**, not solved:
it only bites when CI runs. Its evidence is worth keeping for whenever that changes, and it carries
four diagnostic traps that cost real time (`--log-failed` prints nothing for a cancelled variant;
a killed job's log is **not retained at all**; the jobs endpoint returns only the latest attempt, so
re-run PRs look as though they never failed; read step conclusions with
`gh run view <id> --json jobs`).

`INF-09` is the other half of the old red noise, and it is now **confirmed working**: `Pre-release`,
`Archive build` and `Dokka` used to fail on every push to master because each mints a token for an
upstream repository with an `app_id` this fork does not set. #52 added a `github.repository` gate,
and the last three runs before the shutdown all came back **`skipped` with zero steps** — no runner
consumed, master no longer permanently red.

---

## The design

The redesign's design files live in **`design/` at the repo root**: `.dc.html` files that open in a
browser. They were exported on 2026-10-14 from the Claude Design project *CloudStream 4 — Redesign
(TV / Mobile / Desktop)*, https://claude.ai/design/p/a9ab3c83-bcc5-4ef4-854b-846c95da8438.
`Design System v2.dc.html` is authoritative. `docs/plans/screen-implementation-handover.md` §2
lists every file and the ones that are superseded.
A second Claude Design project, *Project file storage*,
https://claude.ai/design/p/c5fd7e44-70f5-41b6-8a48-e850eb88334b, holds the same 13 files as
`design/` (12 `.dc.html` + `support.js`, every size identical, checked 2026-10-15); it is a copy, not a newer version.

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

**The number symbols differ per platform, and that is not a defect** (`I18N-06`, measured on a
device 2026-10-15). Of 12 pinned locales, 9 agree between Temurin 17 and Android API 36 and 3 do
not: `fr` groups with `U+202F` on Android and `U+0020` on the JVM, `ru` with `U+00A0` against
`U+0020`, and `ar` uses ASCII digits on Android where the JVM still gives `U+0660 ٠`. Each platform
matches its own `String.format`; Android's `java.text` is ICU-backed and already past the CLDR 46
change the JDK takes in 24. The suite pins **both** tables. Nothing consumes `UiStringFormat` yet,
so no behaviour has changed — but once `I18N-01` lands, the same locale will format differently on
desktop JVM and Android.

---

## Local environment — the gates, and three traps

None of the traps are caused by recent work; all three cost real time to find.

**1. Pin the JDK.** `INF-06`. `jdkToolchain = "17"` is consumed only by `:app`, so every `:core:*`
module compiles and tests on whatever JVM started the Gradle daemon. On a JDK 24+ daemon, two
`LocaleNumberParityTest` cases fail on clean master, because JDK 24's CLDR 46 moved bare `ar` from
Arabic-Indic digits to ASCII. **It also guards the ABI baselines:** on JDK 21+ `checkKotlinAbi` fails
on `SequencedCollection`'s `addFirst`/`addLast`/`removeFirst`/`removeLast`, inherited by every `List`
implementor; the committed dumps were written on 17. Never run `updateKotlinAbi` on 21+, and never
hand-write a dump line (`INF-07`).

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

    // init script, applied with -I <file> -PforceTz=<zone>
    val zone = providers.gradleProperty("forceTz").get()
    allprojects {
        tasks.withType<Test>().configureEach {
            systemProperty("user.timezone", zone)
            environment("TZ", zone)
            jvmArgs("-Duser.timezone=$zone")
        }
    }

**3. Raise the heap.** `gradle.properties` sets `-Xmx4G`. A full `assemblePrereleaseDebug lint check`
OOMs in D8 on a 48 GB machine. Pass it on the command line rather than editing the file:

    -Dorg.gradle.jvmargs="-Xmx14G -Dfile.encoding=UTF-8"

**And in a worktree:** `-x :app:generateGitHash`, because `.git` is a file there, not a directory.

### The four gates

These are what CI used to run, plus the canary CI never ran. **All four, every PR**, and name the
exit codes in the PR body:

    T17=/path/to/temurin-17.0.20.1
    G() { JAVA_HOME="$T17" ./gradlew -Dorg.gradle.jvmargs="-Xmx14G -Dfile.encoding=UTF-8" \
            -x :app:generateGitHash "$@"; }

    G checkKotlinAbi                                                      # 1  abi
    G assemblePrereleaseDebug lint check -Pcloudstream.strings.guards=fail # 2  check
    G :app:checkExtensionApi                                              # 3  extapi
    G iosCanary -Pcloudstream.iosCanary=true                              # 4  canary

Gate 3 is **not** part of `check`. Gate 4 is not either, and it is the only one that catches a
Kotlin/Native break — on 2026-10-15 a `@JvmInline` with no import made `abi`, `check` and `extapi`
all pass on a tree that did not compile for iOS at all. That is the best argument the four-gate
discipline has.

**A piped Gradle run reports the pipe's exit code, not Gradle's.** `./gradlew … | tail` exits 0 on a
failed build. This produced a false "green" once already. Redirect to a file and capture `$?`.

**Run the gates serially.** Two concurrent `-Xmx14G` daemons thrash a 48 GB machine. The working
method for parallel work is to fan the *edits* out to subagents, one git worktree each, with Gradle
forbidden in the brief, then run every build serially.

### Four traps that each cost a wrong claim

- **`kotlin.jvm.*` is a default import only on JVM.** `@JvmInline`, `@JvmStatic` and friends need a
  named import in `commonMain`, or `compileKotlinIosArm64` fails with `Unresolved reference`.
  `media/model/.../CaptionStyle.kt` carries the comment and the import; copy it.
- **A module "joins the canary" only once `iosCanary` has run for it.** A failing run still prints
  the full task list with your module *absent*. Grep the log for your own module path; do not count
  the list.
- **Never state a gate result before the run's `status` file says `DONE`.** An early "the module
  joins the canary" was published on 2026-10-15 and came back `canary=1`.
- **`grep "foo\$bar"` in double quotes** makes `$` a mid-pattern regex anchor and silently matches
  nothing. Costs a false "the entry is absent". Single-quote patterns containing `$`.

`INF-05` has the rest, including: zsh does not word-split, so `$FLAGS` is one argument; and an
up-to-date `check` reports the *previous* run's test XML — use `--rerun-tasks` and read the
`timestamp`.

---

## Waiting on the owner

Nothing below can be settled by reading the code.

| | | |
|---|---|---|
| `DEC-02` | **Product Sans** | Measured: 24 files, two byte-identical sets of 12, genuine Google Product Sans v1.009 (`GOOG` vendor ID, empty copyright field, licence URL pointing at a page that does not exist). Eight other proprietary fonts sit beside them in the subtitle picker. Dormant while private; wakes on first distribution. Blocks `DS-05`. |
| `DEC-04` | **iOS extensions** | Unanswered. Blocks `KMP-08` and the iOS half of plan row 18. |
| `DEC-05` | **macOS runner, minimum iOS** | Blocks `INF-02`, which is **moot while CI stays off**. |
| `DEC-06` | **Apple TV** | Blocking nothing today. |
| `DEC-08` | **`APIRepository`'s one-line ABI delta** | Merged in `KMP-05` ahead of the sign-off the entry asks for. The delta is **one** synthetic out (`access$afterPluginsLoaded`, which existed only so the class's own `init` could reach a private method), one public method in. `DEC-08`'s own prediction — that `access$getAfterPluginsLoadedEvent$cp` would go — is **wrong**; it survives, because nine `:app` files still read the event. |
| `DEC-09` | **`BaseComposeFragment` leaving `classes.jar`** | Blocks part of `KMP-06`. |
| `DEC-14` | **Extensions that need a WebView** | Desktop's shims make `WebView` throw, and `WebViewResolver` still fails. Leave it failing, label those extensions before they are enabled, or adopt JCEF — a browser engine per platform, with its own sandbox story. Filed 2026-11-03 from `KMP-18`'s retained evidence. |
| `DEC-15` | **Plugin settings on desktop** | `openSettings` builds Android Views and cannot run here; it costs nothing only because the JVM never links a method body that is never called. Feature absent, a declarative seam extensions opt into, or a raw key editor. Filed 2026-11-03. |
| `DEC-10` | **Screenshot testing** | Wait for a working plugin, or switch to Roborazzi. Blocks `DS-04`. |
| `INF-06` | **Test toolchain** | Pin it, or re-measure the `ar` table — the two cannot both be satisfied. Now also an Android/JVM divergence question, not only a JDK-version one (`I18N-06`). |
| `INF-04` | **iOS test target** | Open; no iOS test target and no screenshot coverage. |
| `DS-01` | **Light scheme** | A design decision, not code. Blocks `DS-05`. `DS-03` is **decided** — see `DEC-13`: `:core:theme` gets no accent role, and the user-selectable primary colour plus Material You do not survive the redesign. That is a product call with real user impact, recorded rather than implemented: `:core:ui`'s theme is untouched and the loss is realised per screen in `DS-05`. |
| `I18N-01`, `I18N-02` | **`UiString` adoption, hardcoded time units** | `I18N-02` touches a published ABI. |
| `BUG-04`, `BUG-05`, `BUG-07` | **Characterised, deliberately not changed** | Each entry says why. |
| `BUG-08` | **`addDate` contract** | The bug is fixed; the *contract* question is not. Are offset-less extension dates UTC (what happens) or device-local (what the KDoc says)? Changing it moves what users see for every provider returning a bare date-time. |
| | **`MainAPI.kt:2549`** | Documents `Episode.runTime` as **seconds**; `EpisodeAdapter` treats it as **minutes**. One is wrong by 60×. Recorded in `BUG-01`, deliberately not resolved. |

### New, and each one is a yes/no that unblocks work

- **Chip-row spacing: the design file contradicts itself.** Its CSS is `.chiprow { gap: 24px }`,
  which is 12dp at the design system's own `artboard px / 2 = dp`; its own prose says *"In Compose
  that is `wrapContentWidth` in a `Row` with **24dp** spacing"*. `CloudStreamDimens.ChipRowSpacing`
  is 24dp and `SkeletonRail` uses it for the gap between rail cards, so `:feature:search`'s rails
  follow the token — a rail at 12dp behind a skeleton at 24dp is the jump `UiStateHost` warns about.
  If the CSS is right, the token, `SkeletonRail` and the search rails all move together.
- **`DebounceQuery` injectability.** `:core:ui`'s `DebounceQuery` hardcodes
  `.flowOn(Dispatchers.Default)` and `200.milliseconds`, neither injectable, so `advanceTimeBy` on a
  `TestDispatcher` does not move it and a debounce cannot be tested in virtual time. Reported, not
  changed — it is existing code on the settings screens' path. `:feature:search`'s suite works
  around it with a real ~600 ms wait.
- **Lint severity for `TrustAllX509TrustManager`.** Android lint flags `SSLTrustManager.kt` on every
  run as **Warning** — `TrustAllX509TrustManager` ×2, `CustomX509TrustManager` ×1 — and never
  aborts. `BUG-10` deleted the process-wide trust-all that used it, but the class itself is
  ABI-frozen at `classes-jar.api:28106` and stays. Promoting the severity needs an `<ignore path>`
  for that class and changes the build gate, so it is yours.
- **16 KB page size, and it is not measured in this tree.** Android requires 16 KB-aligned native
  libraries. An earlier session reported `libgojni.so` and `libconscrypt_jni.so` misaligned in all four
  ABIs, and that the fix is two dependency bumps — but **no `.so` is in this repository** (they arrive
  from AARs) and that measurement was not retained, so nothing here reproduces it. Someone must build
  the APK and check the alignment before this becomes a decision. It is a Play requirement, not a
  warning the local gates raise.
- **`androidx.test:runner` has no catalog alias.** `I18N-06`'s device test leans on
  `libs.espresso.core`, which brings `runner` 1.6.1 in at compile scope (POM-verified). A proper
  alias is a `gradle/libs.versions.toml` addition and needs approval
  (`.claude/rules/dependency-security.md`). Robolectric is absent too.

### Answered recently, with the reasoning in the entry

| | | |
|---|---|---|
| `DEC-01` | **CI** | Answered 2026-10-15: the billing failure will not be fixed; CI stays off. Closed `INF-01`, parked `INF-08`, made `INF-02` moot. |
| `DEC-07` | **`fcast/Packets.kt` placement** | **Dissolved** by measurement 2026-10-15. The whole cost of the move was `:core:common` gaining the serialization plugin and Jackson — and that cost came from `PlayMessage` alone, which is `@Serializable` and whose `write$Self$app` is published. Splitting the file leaves `PlayMessage` in `:app` and sends the other eight to `commonMain`: `:core:common` gains **nothing**. |
| `DEC-12` | **The two `PreferenceStore`s** | Decided 2026-10-14: **keep both, add no alias.** |
| `DEC-03` | **Distribution** | Answered 2026-09-18, with a live remainder in the entry. |

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

The `PreferenceStore` question `KMP-06d` raised is **`DEC-12`**, not `DEC-11` (which is the icon
pack), and it was **decided on 2026-10-14: keep both, add no alias.**

Two readmes moved rather than died: `shared/readme.md` is LagradOst's rationale for the whole
Compose rewrite and is now `docs/compose-rewrite.md`. `shared/src/readme.md` was 9 lines of the
Compose-Multiplatform template's source-set blurb and was deleted; #46 quotes it in full.

---

## What is next

The live queue is `docs/plans/kmp-migration.md` §7, "Next PRs, in order". Rows 1–17 and 19–23 are done.
What is left is the rest of Phase 7, Phase N, and Track L.

**Phase 7 vertical 4, downloads**, or the two things `KMP-26` left: a bookmark button on the detail
page (it needs a slot in `:feature:detail`) and watch progress on search and home cards
(`SearchResultItem.watchProgress` already exists and is still always null).

**Row 18, `:feature:search`,** has two steps left:

4. **Open as draft #91** — the glue `Fragment` and one node repointed in `res/navigation/mobile_navigation.xml`.
   The old fragment stays in `:app` as the rollback lever. **Needs a real TV before it merges.**
5. Delete the old fragment and layouts a release later. That removes `com.lagradost.cloudstream3`
   classes from `classes.jar`: list them with the javap tripwire output for owner sign-off.

**Row 17, `:feature:extensions`:** the adapter's first caller. `F1` and `F2` are settled (`KMP-11`, owner
2026-11-03), so what is left is a caller in `:app` and a device to run it on.

**Also open:** `KMP-08` / Track L is blocked on `DEC-04`. `I18N-01` (`UiString` adoption) is many small PRs
and nobody is on it. `INF-04` and `INF-06` are open. `DS-04` is blocked on `DEC-10` and `DS-05` on `DS-01`.

**Three decisions the desktop work raised, all the owner's:** the 74 extensions that use `android.webkit`
(disable them on desktop or adopt JCEF), plugin settings on desktop (the dialogs are Android Views and
cannot survive conversion), and 16 KB page-size compliance, which needs two dependency bumps.

A correction to `KMP-11`: this file used to say `:app` never compares `fileHash`. It does, and has
since `7926e60f` (2026-04-19), at `RepositoryManager.kt:214-220`. Three KDocs in `:feature:extensions`
still repeat the old claim, and the check is stricter than `ExtensionHash` (`KMP-11` `F6`).

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

**A five-point checklist that the B-series settled**, in the order the cost shows up:

1. **Per class, not per file.** `KMP-04` moved 8 of 19 classes out of one file because exactly one
   of them had a module-suffixed published member. Check each class; a file is not the unit.
2. **Pick the source set before you pick the module.** `commonMain` is the goal, but a `:library`
   edge there forces an `IOS_CANARY_EXEMPT` entry, and that list "may only shrink". `KMP-05` chose
   `jvmCommonMain` and kept the canary whole, following `:core:network`'s precedent.
3. **Field types are an edge too.** `KMP-11` kept `ExtensionListing.tvTypes` as `List<String>` — the
   wire shape — rather than `List<TvType>`, for exactly that reason.
4. **Record the arrivals.** A receiving `:core:*` module needs `:<module>:updateKotlinAbi` on
   Temurin 17 in the same PR, or `checkKotlinAbi` fails on the new declarations. `KMP-04` learned
   this as `abi=1`.
5. **Add the sentinels.** A module whose classes land in the merged jar declares its binary names in
   `cloudstream.extension-api-jar`.

And a lesson about the task files themselves: **"blocked by `DEC-0x`" does not always mean "wait".**
`DEC-07` says *"confirm in the PR, or name a different home"*; `DEC-08` says *"needs explicit
sign-off in that PR"*. Both mean **build it and present the measured delta.** Three rows were idle
for days on that misreading, and in each case the measurement either dissolved the question
(`DEC-07`) or shrank it to one line (`DEC-08`). Row 18's stated blocker — "ViewModel needs Track L
for iOS (rule 4)" — turned out not to apply at all.

---

## How the work has gone, for whatever that is worth

Measure rather than reason. Nearly every wrong claim in this project's history was one a plausible
argument supported and nobody executed: the `dur * 60` overflow argued unreachable, the
"locale-blind" notification, the ABI dump that supposedly did not exist, a `BUG-01` entry describing
a defect that had already been fixed, and — on 2026-10-15 — a `BUG-10` option described as "strictly
better" that `javap` showed does not exist, because `DefaultHttpDataSource.Factory` has nine setters
and none of them mentions SSL.

Three of one session's own measurements were wrong on the first pass and corrected on the second:
a regex that counted commented-out `<string>` lines as live keys (reported 27 removed, actually 0);
a regex that missed `formatted="true" name="…"` because the attributes were reversed (reported 26
spurious additions); and a NOTICE draft claiming the vendored changes were "substantial rather than
cosmetic" when three files differ by the `package` line alone. Each was caught by checking, not by
thinking harder. The habit that catches them is cheap: re-run the measurement a second way before
writing the number down.

**The largest single retraction so far was a sampling artefact**, and it is worth knowing the shape.
`INF-08`'s entry claimed CI's failures "split perfectly by content" — 5 of 5 trivial PRs died, 7 of 7
real ones passed — which is an *inversion* of the obvious hypothesis and was quoted as a finding.
It was wrong: `gh api .../runs/<id>/jobs` returns only the **latest attempt**, so every re-run PR
looked as though it had never failed. Read per attempt (19 runs, 22 attempts) it is 10 success / 8
terminated on attempt 1, a real-code PR died and a docs-only PR passed first time. An API that
quietly returns a filtered view produces confident numbers from a biased sample.

On 2026-10-14 the same habit was applied to subagents. Two parallel verification agents each ran
in their own git worktree. Their headline numbers (exit codes, renames, the jar contents) were
re-checked by hand before anything was pushed. One of their claims, that the 06c moves were in
`f2e0e435`, sounded wrong and turned out to be right. Give parallel agents **separate scratch
subdirectories**. Two agents sharing one scratchpad overwrote each other's runner script mid-run.

**More than one session works in `/Volumes/Projects/CloudStream/app` at a time.** Stage explicit
paths; never `git add -A` or `git commit -a` there. A `git add -A` on 2026-10-14 swept another
session's uncommitted `design/` folder into a PR, and that session then moved HEAD out from under
this one mid-task. Work in a `git worktree` when another session is active.
