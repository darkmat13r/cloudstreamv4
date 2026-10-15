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

**Status:** **answered by measurement 2026-10-15 — the question dissolves.** Blocks nothing.

The question was whether `:core:common` should gain the serialization plugin, kotlinx-serialization
and Jackson annotations in order to take `Packets.kt` whole, since `PlayMessage` uses Jackson.

**It does not have to, because `PlayMessage` cannot move anyway.** `@Serializable` makes the compiler
emit `write$Self$app`, whose `$app` is `:app`'s Kotlin module name, and that member is published
(`app/api/classes-jar.api:5293`). Moving the class renames something extensions link against — a
`DEC-08`-class ABI delta rather than a file move.

So `KMP-04` split the file instead: the eight classes with no module-suffixed member went to
`:core:common` **`commonMain`**, and `PlayMessage` stayed in `:app`. **`:core:common` gains no
dependencies** — it is still dependency-free — and the eight landed in `commonMain` rather than
`jvmCommonMain`, which is better for iOS than the default the audit proposed. Nothing was traded away
to get that; the ABI constraint forced it.

What remains, if anyone wants `PlayMessage` shared later: it needs an accepted ABI delta, which is the
same decision shape as `DEC-08`. Not needed for anything today.

---

## DEC-08 — `APIRepository` removes a public synthetic accessor

**Status:** open — **still your sign-off, but the delta is measured now and it is one line, not the
line this entry named.** The work is done in `KMP-05`.

This entry said the move "removes `access$getAfterPluginsLoadedEvent$cp` from the published extension
jar". **Measured: that entry is not removed.** It survives, because
`MainActivity.Companion.getAfterPluginsLoadedEvent()` still reads the outer class's static backing
field and nine other `:app` files still use the event. The move never touched `MainActivity`.

The whole delta in `app/api/classes-jar.api`:

```
- public static final synthetic fun access$afterPluginsLoaded (Lcom/lagradost/cloudstream3/ui/APIRepository;Z)V
+ public final fun onPluginsLoaded (Z)V
```

**What is being asked for sign-off:** deleting `access$afterPluginsLoaded`. It is a compiler-generated
accessor that existed only so `APIRepository`'s own `init` block could take a callable reference to a
**private instance method**. Kotlin cannot reference an `access$…` synthetic — it is not in the
Kotlin-visible surface — so only hand-written bytecode or reflection could have called it, and that
would have been invoking a private method deliberately. No extension can lose a call it could
reasonably have written.

That is a weaker deletion than this entry assumed. It is still a deletion from a published jar, so the
call stays yours.

Two things the entry did not cost, both in `KMP-05`:

- **The placement constraint that actually bound the design.** `commonMain` would have added
  `:library` to `:core:data`'s commonMain and forced the module into `IOS_CANARY_EXEMPT`, a list that
  "may only shrink", dropping the iOS canary from 12 modules to 11. `jvmCommonMain` avoids it
  entirely, following `:core:network`'s precedent.
- **An unbounded listener leak**, fixed as a side effect: the old code subscribed one listener per
  `APIRepository` instance and never removed any, all clearing the same companion-level cache.

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

---

## DEC-12 — Consolidating the two `PreferenceStore` declarations

**Status:** **decided 2026-10-14 — keep both, add no alias** · blocks nothing · raised by `KMP-06d`

### The decision, and why

**Leave the two declarations as they are, and do not add the `typealias`.** The third option below
looked like the cheap one, and it is the one not to take:

- A `typealias` in `:core:settings` makes the two types *interchangeable at the point where the plan
  says one of them should eventually win*. It encodes the duplication as intended rather than
  temporary, and it is the kind of thing that is still there in a year.
- It would also have **no callers**. Nothing consumes `:core:storage`'s declaration today, so the
  alias would be dead weight added to argue a point rather than to be used.
- The cost of waiting is bounded and already being paid: `:core:storage`'s Android and JVM
  implementations stay unused, and its contract tests guard code nobody executes. That is a cost in
  confidence, not in correctness, and it does not grow.
- The cost of choosing wrong **does** grow: a fork edit to vendored Apache-2.0 code, or 62
  `preference = settings.…` call sites, neither of which gets cheaper by being done sooner.

**The trigger to revisit:** the first time something in `:app` actually consumes `:core:storage`.
That consumer decides which declaration it wants, and the retyping cost stops being hypothetical.
Until then the duplication is visible here, which is what this entry is for.

**Unchanged and not open:** the two signatures stay identical, and both KDocs already say so. Worth
knowing that nothing *enforces* it — a shape-comparison test could live in `:core:settings`, which
already depends on both, and would turn "keep them identical" from aspiration into a build failure.
Not written here, because adding a test to pin a duplication this entry exists to remove is a
judgement call the trigger above should settle first.

### The original write-up, kept

Two interfaces of the **same shape** exist, both live:

| | Module | Who uses it |
|---|---|---|
| `com.mihon.common.preference.PreferenceStore` / `PreferenceData` | `:core:mihon` (vendored, Apache-2.0) | `AppSettings` in `:core:settings`, and every Mihon settings widget — `Preference.PreferenceItem`'s fields are declared `PreferenceData<…>` (`Preference.kt:41,69,112,142`) |
| `com.lagradost.cloudstream4.core.storage.PreferenceStore` / `PreferenceData` | `:core:storage` (first-party, tested) | nothing in `:app` yet — the module's contract tests only |

`core/storage/.../PreferenceStore.kt`'s KDoc says Phase 5 would merge them by moving the Mihon
declaration into `:core:storage` keeping its `com.mihon.*` package. `KMP-06d` makes that
cheaper to see and more awkward to do:

- Moving `com/mihon/common/preference/**` into `:core:storage` puts vendored Apache-2.0 code under a
  first-party root, which is the boundary `KMP-06b` drew a module to express
  (`core/mihon/build.gradle.kts`: "no first-party code belongs here"), and it would hand
  `:core:storage` a second `NOTICE` obligation.
- Going the other way — `AppSettings` adopting `:core:storage`'s declaration — retypes **62
  `preference = settings.…` arguments** across 6 `:app` settings screens plus 13 `collectAsState()`
  calls, because the receiving fields are in vendored code. Either the vendored fields change type
  (a fork edit) or an adapter wraps every `PreferenceData`.
- A third option: `typealias` one to the other in whichever module may depend on the other.
  `:core:settings` already depends on both, so the alias could live there and neither vendored nor
  `:core:storage` code changes. That makes the duplication explicit without moving anything.

**Why it can wait:** nothing is broken and nothing is blocked. The cost of leaving it is that
`:core:storage`'s Android and JVM implementations stay unused while the app runs on Mihon's, so the
module's tests guard code nobody executes. The cost of choosing wrong is a fork edit or 62 call
sites.

**The decision also needs the signatures kept identical in the meantime** — that part is not open,
and both KDocs say so.

