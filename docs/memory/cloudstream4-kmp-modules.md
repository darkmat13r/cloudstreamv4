---
name: cloudstream4-kmp-modules
description: How the CloudStream 4 design system maps onto the KMP module plan, and the constraints that bind any code built from it
metadata:
  type: project
---

The migration plan is `docs/plans/kmp-migration.md` (387 lines). **Phase 0 and the :core:theme /
:core:designsystem half of Phase 1 are now BUILT** (2026-09-12): `build-logic/` with
`cloudstream.kmp.library` + `cloudstream.kmp.compose` convention plugins, and 44 Kotlin files /
~5,000 LOC across the two modules. Verified by me, not just claimed: `:core:theme` and
`:core:designsystem` compile for **both** jvm and android, and `:app:compileStableDebugKotlin`
still succeeds. `:shared` is untouched and still builds. The design system lands in `:core:theme` + `:core:designsystem`, which are
Phase 1 of that plan — carved out of the existing `:shared` module.

**The conversion that matters: artboard px / 2 = dp.** The 1920x1080 artboard is 960x540 dp at
xhdpi. Every artboard dimension is a multiple of 8px precisely so the dp lands on the repo's 4dp
step. Never transcribe an artboard px as a dp — halve it. So: list row 96px = 48dp, chip visual
48px = 24dp, glyph 32px = 16dp, focus band inset 56px = 28dp, lift 8px = 4dp, side panel 560px =
280dp, poster 160x240 = 80x120dp.

**Open question, deliberately not decided:** the 20px caption is **10sp**, below Android TV's
12sp legibility minimum. The type ramp in sp is 88->44 (exactly leanback's browse title),
64->32, 40->20, 28->14, 24->12, 20->10. Raising the caption to 24px/12sp would touch every drawn
file. Flagged to the user 2026-09-12; awaiting their call.

**Four constraints from the plan that bind any module work:**
1. `:app` cannot become KMP — AGP ships no multiplatform *application* plugin. `:app` stays
   `com.android.application` and shrinks to a shell.
2. `makeJar` is a **published API contract**: third-party extensions compile against the merged
   jar, so every moved file keeps its fully-qualified `com.lagradost.cloudstream3.*` name and
   every new module must be folded into `copyJar` programmatically.
3. `R` is invisible from a library module's `commonMain`, so strings gate ViewModel extraction.
   The `UiText` bridge (`Raw` / `Resource(id, args)`) decouples this; migrating the 70+ Weblate
   locales into `composeResources` is deferred to Phase N.
4. **Every `:core:*` and `:feature:*` declares a `jvm()` target as a purity canary.** It ships
   nothing and nobody consumes it; it exists to fail the build when `android.*` reaches
   `commonMain`. Without it the migration is cosmetic.

**No new third-party dependencies are needed for Phases 0-4** — so no `libs.versions.toml`
change, which would require the user's approval anyway.

**The maintainer position to respect before any upstream PR** — `shared/readme.md`, quoted in
the plan: *"I will not accept or allow any pull requests concerning the Compose rewrite before I
put the core structures in place… in no part do I want AI to lay the groundwork for something
this important."* The plan itself says to coordinate before executing Phase 1 onward if the
target is upstream merge rather than a fork. Raised with the user 2026-09-12.

What already exists in `:shared` and must be reused rather than rewritten: `com.mihon.material.*`
(AppBar, Pill, Slider, SettingsItems, Scaffold, Typography), `com.mihon.common.preference.*`
(PreferenceStore with an expect/actual split and an in-memory impl), and
`com.lagradost.cloudstream4.compose.*` (Screen, Viewmodel, ComposeFragment, Dialog, Modifiers)
plus `theme/*` (Theme, Scheme, Palette, Font, DeviceTheme). The gap `:core:theme` fills is that
**there is no dimension system at all**.

**Task naming gotcha:** these modules use the AGP-9 KMP plugin, so the Android compile task is
`compileAndroidMain`, NOT `compileKotlinAndroid` (which does not exist in this build).

**The canary was proved, not assumed** — an `import android.os.Build` dropped into
`:core:theme/commonMain` failed `compileKotlinJvm` with "Unresolved reference 'android'".

**An agent added `org.gradle.toolchains.foojay-resolver-convention` to `settings.gradle.kts`
without approval. I removed it and confirmed both modules still build without it.** Watch for this
class of drive-by dependency in any future module work — the plan is explicit that Phases 0-4 need
no new third-party dependencies.

**Design decisions the four parallel component agents left open** (each would delete a public
symbol, so none should be resolved silently):
1. Two text buttons — `target/TextButton.kt` (`GhostTextButton`, transparent + a `line` hairline)
   vs `state/StateActionButton.kt` (`Secondary`, a `raised` fill). Repointing changes the render.
2. Two progress rules with **different geometry**: `primitive/ProgressRule.kt` is 2dp on a bg@0.6
   track; `card/MediaCardOverlay.kt` is 4dp on a `line` track. One of those numbers is wrong, and
   cards do not use the primitive.
3. Two status dots — `primitive/StatusDot.kt` is a square, `state/FailureBanner.kt` hand-rolls the
   same 8dp `live` marker as a pill.
4. Four identical preview scaffolds differing only in `contentAlignment`.
5. `CloudStreamDimens.ChipGlyphSize` is now referenced by no code at all — `IconCell` declared its
   own 16dp because the token's name says "chip".
6. Three state files use `androidx.compose.material3.Text` while the rest use `BasicText` — the one
   place the deliberate no-Material stance is broken.

Part of [[cloudstream4-redesign]]. Geometry rules live in [[cloudstream4-design-state]].

**Phase 0 was NOT actually complete when the handover was written (found 2026-09-17).** No
`:core:common`, `makeJar` hand-listed, FQN rule unwritten. Done in **PR #4** (`feat/core-common`, darkmat13r/cloudstreamv4 — a fork with no GitHub parent;
worktree `/Volumes/Projects/CloudStream/app-core-common`), 2026-09-17: `:core:common` with
`commonMain` + a `jvmCommonMain` intermediate set (the `:library` layout); `copyJar`/`makeJar` live in
the build-logic plugin `cloudstream.extension-api-jar` (user chose build-logic over inline :app
Gradle). **Moving an extension-facing class out of :app means adding it to that plugin's
`sentinels` map.** `makeJar` derives merged modules from :app's resolved prereleaseDebug classpath (any dep with
a `src/*/{kotlin,java}/com/lagradost/cloudstream3` dir, merged as its jvm jar) and fails on missing
sentinel classes. Verified by me: .class set identical to baseline (4264), one added
`.kotlin_module`, javap signatures unchanged, bytecode 55 like :app.
- **`Event.kt` is JVM-bound** (`synchronized`, `Runnable` in its public ABI) — it cannot go to
  `commonMain`; plan §3/§4 were wrong, as was listing `TestingUtils` (needs :library) and fcast
  `Packets.kt` (jackson; belongs to `:media:cast`) under `:core:common`.
- **Worktree gotcha:** `:app:generateGitHash` reads `../.git/HEAD` as a directory, so any gradle run
  reaching :app in a git worktree needs `-x :app:generateGitHash`. `copyJar` depends on `build`, and
  `:app:packageStableDebug` flaked once (IncrementalSplitterRunnable) — retry before believing it.

**iOS is a future target (user, 2026-09-17).** Every migration step must be designed so an iOS target
can be added later; the plan (`docs/plans/kmp-migration.md`) is to be updated for it, and where the
current implementation makes iOS impossible, find alternatives and DISCUSS with the user before
choosing. Also approved: a Ktor client proposal for new first-party networking in :core:network
commonMain (OkHttp API for extensions stays in jvmCommonMain), and Kotlin's built-in `abiValidation`
for extension-API modules (`:library` already uses it: dumps in `library/api/jvm`, CI runs
`library:checkKotlinAbi`). Host has Xcode 27 + Kotlin/Native, so an iOS compile canary is possible.
PR #6 (:core:network) merged.

**iOS readiness analysis (workflow wf_d3ba5975-7b0, task wkmwpkmik, 2026-09-17)** — synthesis has
readinessMatrix, impossibleAsDesigned (extensions on iOS, GPL/App Store distribution, :library JVM API,
Plugin Context/Resources, playback headers/interceptors/MKV, WebViewResolver capture, background HLS
downloads, self-update, tvOS Compose), planEdits (19), conventionChanges, ktorProposal, nextPrs; critique
confirmed 35/43 and lists requiredCorrections (notably: jvmCommonMain is invisible only to the iOS canary;
inline getKey binds to SharedPreferences interface not the XML file; Guideline 4.7 permits JS plug-ins;
iOS canary for Compose modules needs a macOS runner; ktor-http 3.5.0 has no reachable KTOR-9607 path).

**Owner decisions 2026-09-17 (settled):**
1. **Make :library iOS-compilable** by additive source-set surgery (JVM-typed decls → jvmCommonMain with
   facade names kept via @JvmMultifileClass/@JvmName, expect/typealias), each move proven with
   abiValidation + javap. No extension ABI break.
2. **New first-party code goes in NON-merged modules** (no com/lagradost/cloudstream3 dir): `:core:strings`
   (text type + generated compose strings), `:core:http` (Ktor client). :core:common/:core:network hold
   only frozen extension API.
3. **Ktor 3.6.0 approved** (catalog: bump `ktor` ref 3.5.0→3.6.0, add ktor-client-core commonMain +
   ktor-client-okhttp jvmCommonMain, darwin deferred). Accepts OkHttp 5.5.0 / okio 3.18.2 at runtime for
   extensions. OkHttp engine `preconfigured = app.baseClient`, never closed.
4. **Screens per form factor in commonMain** (TV / touch / desktop), no android.*; iOS touch reuses mobile.
   Reinterprets shared/readme.md "Avoid: Entire Screens".
Still open (non-blocking): iOS distribution (GPL vs App Store), iOS extension strategy (a–e), Apple TV
scope, CI macOS runner + min iOS version.
**Ktor runtime behaviour (found 2026-09-17, owner chose 3.6.0 anyway, documented):** only one ktor-http can
be on :app's classpath and extensions use it at runtime, so the Ktor version = URL behaviour for all
extensions. 3.6.0 (KTOR-8755): Url.encodedPath / MainAPI.updateUrl percent-encode paths (`a b`→`a%20b`,
non-ASCII encoded); bare `%` now parses. 3.5.2 (KTOR-9759): whitespace in host throws URLParserException
(~10 built-in extractors call Url() without catch). 3.5.1 had no URL change. Also slf4j-api 2.0.18→2.0.19.
Ktor bump PR must carry :library tests pinning the new behaviour + an extension-author note.
**:core:http (PR #11, stacked on #10).** Ktor OkHttp-engine trap (3.6.0 source): `OkHttpEngine.close()`
evicts the pool and SHUTS DOWN the dispatcher executor of clients derived via `preconfigured.newBuilder()`,
which share them with app.baseClient → `HttpClient(OkHttp){engine{preconfigured=...}}.close()` breaks all
later async calls on the app client. Factory builds the engine separately and passes it to
`HttpClient(engine)` (unmanaged). First :app consumer must align okhttp-dnsoverhttps (5.3.2) with okhttp
5.5.0 and note Ktor HttpRedirect refuses HTTPS→HTTP downgrades.
