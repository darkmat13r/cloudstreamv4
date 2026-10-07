# DEC — owner decisions

Questions only the owner can answer. The ones marked **blocking** are holding up named work.

---

## DEC-01 — GitHub Actions billing · **blocking everything**

**Status:** resolved in practice — **Actions runs again, observed 2026-10-07**; the owner has not
recorded what changed · **Unblocked:** `INF-01`

On 2026-10-07 the `Artifact Build` workflow (`pull_request.yml`) ran on PRs #41, #42 and #43. The first
full green run is #42's head `bce90a8` (run `37647589593`, 42 min): `checkKotlinAbi`,
`assemblePrereleaseDebug lint check -Pcloudstream.strings.guards=fail` and `:app:checkExtensionApi`
all passed. The text below is the state before that, kept for the record.


Actions fails account-wide in ~2s: *"recent account payments have failed or your spending limit
needs to be increased"*. Twenty-plus PRs have merged on local verification alone.

This is not a task anyone but the account owner can do. Until it is fixed, the repo has no
independent check that anything works on a machine other than this one.

---

## DEC-02 — Product Sans provenance · **blocking `DS-05`**

**Status:** open · **provenance now measured, 2026-10-07** — the decision is still yours

### What the files actually are

Not 12 files but **24**: two byte-identical sets of 12 (`cmp` reports no difference).

| Location | Added |
|---|---|
| `app/src/main/res/font/productsans_*.ttf` | `1549f67c` "init", 2021-05-12 — the repo's first commit |
| `shared/src/commonMain/composeResources/font/productsans_*.ttf` — moved unchanged to `core/ui/` in KMP-06c | `92b3ea92` "Compose work (#3151)", 2026-09-08 |

Reading the embedded name table rather than trusting the filename, every file says:

| Field | Value |
|---|---|
| Unique ID (name 3) | `1.009;GOOG;ProductSans-<Style>` |
| PostScript (name 6) | `ProductSans-<Style>` |
| Version (name 5) | `Version 1.009` |
| **Copyright (name 0)** | **empty string** |
| Licence description (name 13) | **absent** |
| Licence URL (name 14) | `https://www.google.com/fonts/license/productsans` |
| OS/2 `achVendID` | `GOOG` |

There is also a glyph named `Google.logo`. For contrast, the other fonts in the same directory do
carry real licence text — `open_sans` says "Licensed under the Apache License, Version 2.0" and
`poppins_regular` says "SIL Open Font License, Version 1.1".

**This is genuine Google Product Sans**, a proprietary corporate typeface that has never been
released openly and is not on Google Fonts. The licence URL is a pointer to a page that does not
exist; it is not a grant. The empty copyright field means the metadata was stripped, or the files
came from a copy that already had been. No in-repo doc, commit message or licence file says
otherwise: there is no `OFL.txt`, no `FONTLOG`, and no font attribution anywhere.

`fsType` is 0 ("installable embedding"), which is a technical flag in the binary and **not** a
licence grant — worth saying because it is the thing most likely to be mistaken for one.

### Where they are used

`app/src/main/res/font/google_sans.xml` wraps all 12 by weight and style, and `google_sans` is the
`android:fontFamily` in several layouts including `fragment_home_tv.xml` and
`player_custom_layout_tv.xml`. `shared/.../theme/Font.kt` imports all 12 and builds the Compose
family that feeds the Material3 type roles. It is also offered in the subtitle font picker
(`SubtitlesFragment.kt:655`), labelled "Google Sans".

### The wider pattern, which the narrow question misses

`app/src/main/res/font/` also ships `consola`, `comic_sans`, `verdana` (Microsoft),
`times_new_roman` (Monotype), `lucida_grande` (Bigelow & Holmes), `gotham` (Hoefler),
`netflix_sans` (Netflix / Dalton Maag EULA) and `futura` (Bitstream). Each carries its own EULA
reference in its name table. They appear only in the subtitle font picker, not in the UI
typography, so only Product Sans blocks `DS-05` — but resolving this narrowly leaves the rest.

All of it was inherited: the `app/` set arrived in upstream CloudStream's initial commit.
**Inheriting it does not make it licensed.** While the project stays private this is dormant (see
`DEC-03`); it wakes on first distribution.

### What you need to decide

1. Is there any written permission from Google for these files? Nothing in them says so.
2. Scope: the `shared/` set that `DS-05` depends on, the `app/` legacy set that leaves with the
   views, or both? They are the same bytes, so both are affected.
3. The other eight proprietary fonts — same decision, or a separate one?

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
