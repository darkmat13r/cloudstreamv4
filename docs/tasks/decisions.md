# DEC — owner decisions

Questions only the owner can answer. The ones marked **blocking** are holding up named work.

---

## DEC-01 — GitHub Actions billing · **blocking everything**

**Status:** open · **Blocks:** `INF-01`, and the confidence level of every merged PR

Actions fails account-wide in ~2s: *"recent account payments have failed or your spending limit
needs to be increased"*. Twenty-plus PRs have merged on local verification alone.

This is not a task anyone but the account owner can do. Until it is fixed, the repo has no
independent check that anything works on a machine other than this one.

---

## DEC-02 — Product Sans provenance · **blocking `DS-05`**

**Status:** open

`shared/src/commonMain/composeResources/font/` contains **12 committed `productsans_*.ttf` files**,
and `shared/.../theme/Font.kt` builds all fifteen Material3 type roles from them. Product Sans is
not, as far as anyone here can establish, an openly licensed typeface, and nobody has established
where these binaries came from.

While the project stays private this is dormant — see DEC-03. It wakes up on first distribution.

**Options:** establish provenance and keep · replace with a licensed face (Manrope is already the
redesign's measured type — see the type-metrics work) · leave and accept the risk knowingly.

---

## DEC-03 — Distribution · partially answered

**Status:** answered 2026-09-18, with a live remainder

**Answer:** this is a separate private personal project. The base code came from CloudStream, but
there is no intention to contribute upstream.

**Why it matters:** GPL-3.0 §5 and Apache-2.0 §4 obligations trigger on *distribution*, not on
private use. While nothing ships, the attribution work in `KMP-06` is good hygiene rather than
compliance, and DEC-02 is dormant.

**What is still open:** whether this is ever distributed. The moment an APK reaches anyone else,
DEC-02 and the whole of `KMP-06` become live obligations rather than tidiness. Say so before that
happens rather than after.

---

## DEC-04 — iOS extension strategy

**Status:** open · **Blocks:** `KMP-08` (Track L)

Extensions cannot run on iOS as built: App Store rule 2.5.2 and the absence of Kotlin/Native
dynamic loading. `:library` is JVM-bound today.

**Options:** no extensions on iOS (a different product) · a server-side extension host · a
curated, compiled-in provider set. This decides how much of `:library` is worth making
iOS-compilable at all, so `KMP-08` cannot be scoped until it is answered.

---

## DEC-05 — CI runner for the iOS canary, and minimum iOS version

**Status:** open · **Blocks:** `INF-02`

The canary exists and is green locally (`-Pcloudstream.iosCanary=true`). The CI job that runs it
was never built, because nothing decides which runner pays for macOS time.

**Options:** a macOS runner · split the workflow so only iOS jobs need macOS · keep it local-only
and accept that iOS purity regressions are found late.

---

## DEC-06 — Apple TV

**Status:** open · not blocking anything today

Whether tvOS is a target at all. It changes what "TV-first" means in the design system, which is
currently Android TV.

---

## DEC-07 — `fcast/Packets.kt` placement

**Status:** open · **Blocks:** `KMP-04`

The ABI audit left this open. Default placement is `:core:common` jvmCommonMain as a whole file,
because `PlayMessage` uses Jackson and the planned android-only `:media:cast` cannot publish it.
That means `:core:common` — which has no dependencies today — gains the serialization plugin,
kotlinx-serialization and Jackson annotations.

**Confirm in the PR**, or name a different home.

---

## DEC-08 — `APIRepository` removes a public synthetic accessor

**Status:** open · **Blocks:** `KMP-05`

Moving `APIRepository` to `:core:data` requires inverting `MainActivity.afterPluginsLoadedEvent` in
the same PR, which removes `access$getAfterPluginsLoadedEvent$cp` from the published extension jar.
That is a real ABI deletion, not a rename.

**Needs explicit sign-off in that PR.**

---

## DEC-09 — `BaseComposeFragment` leaving `classes.jar`

**Status:** open · **Blocks:** part of `KMP-06`

If `BaseComposeFragment` moves out of `:app` it disappears from the published extension jar. It is
not in the `sentinels` map; its only in-repo users are the seven settings fragments.

**Recommended:** sign off — it is Compose glue no extension has a plausible reason to extend.
**Cheaper alternative:** leave a `@Deprecated` `typealias` in `:app` for one release. Reversible in
a way the deletion is not.

---

## DEC-10 — Screenshot testing: wait, or switch to Roborazzi

**Status:** open · **Blocks:** `DS-04`

`com.android.compose.screenshot:0.0.1-alpha16` does not render on this toolchain (AGP 9.1.1,
Gradle 9.4.1). The wiring is correct and complete — tasks register, previews compile, the engine
reports "1 test, 0 failures" — and it writes no images. A preview containing only
`BasicText("hello")` renders nothing either, so it is the plugin, not the components.

This is why `DS-01` and `DS-05` are unguarded: there is no suite to catch what they move.

**Options:** wait for a working plugin version · switch to Roborazzi (a real dependency decision
under `dependency-security.md`, not something to slip into a feature PR) · accept no screenshot
coverage and rely on review.

---

## DEC-11 — Icon pack

**Status:** open · not blocking, but accruing cost

There is no icon pack in `gradle/libs.versions.toml`. Meanwhile `app/src/main/res/drawable/`
holds **80 hand-exported `ic_baseline_*.xml` drawables**, and `:core:designsystem` draws its own
chevron (`MediaCardDefaults.SeeAllGlyphSize`) because a "see all" arrow was drawn rather than
referenced.

Every feature that needs a glyph either reuses an existing drawable or is blocked. The deciding
question is which pack covers playback, download state, source status and repository management
**without gaps** — one that covers 90% and leaves you drawing the rest has not solved the problem.

**Candidates:** `androidx.compose.material:material-icons-extended` (complete, familiar, large, no
longer actively expanded) · a Compose port of Lucide or Phosphor.
