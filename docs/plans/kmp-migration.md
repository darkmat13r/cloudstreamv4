# CloudStream → Kotlin Multiplatform, Compose, multimodule

**Status (2026-09-17): Phase 0 done except D2 (#7 open). The module carve-out has started. Nothing ships for iOS.**

| Built | PR | State |
|---|---|---|
| `build-logic` conventions `cloudstream.kmp.library`, `cloudstream.kmp.compose` | earlier | merged |
| `:core:theme`, `:core:designsystem` (CloudStream 4 tokens and components), `:core:screenshot` | #3 and earlier | merged |
| `:core:common` (`IDisposable`, `Vector2`, `Event`, `EmptyEvent`) + `cloudstream.extension-api-jar` (derived merge, sentinels) | #4 | merged |
| `cloudstream.kmp.extension-api`: moved classes keep `$stable` (D1) | #5 | merged |
| `:core:network` (`DohProviders.kt`) | #6 | merged |
| `abiValidation` on extension-api modules, CI runs `checkKotlinAbi` everywhere (D2) | #7 | **open** |
| G1: `ui-tooling` out of `cloudstream.kmp.compose` commonMain (§6.3) | #8 | **open** |

**Targets:** Android ships. `jvm()` is a purity canary. **iOS (iPhone/iPad) is a declared future
target**: every step must keep it possible, and nothing is built for it yet (§2). Apple TV is an
open decision (§10).

Previous version's §2–§8 are now §3–§9; §2 (targets) and §10 (open decisions) are new.

## Settled decisions

All recorded 2026-09-17 by the owner. They are not options any more; re-open them only with the owner.

| ID | Decision | Applied in |
|---|---|---|
| D1 | Every module that receives classes from Compose-compiled `:app` uses `cloudstream.kmp.extension-api` (Compose compiler + `compose-runtime` only + `jvmCommonMain`), so `$stable` / `@StabilityInferred` survive the move. Merged (PR #5). | §3.2, §6.1 |
| D2 | Kotlin `abiValidation` on every extension-api module; CI runs `./gradlew checkKotlinAbi` across all projects. PR #7, open. | §6.2 |
| D3 | `cloudstream.extension-api-jar` derives merged modules (any `:app` dependency with `src/*/{kotlin,java}/com/lagradost/cloudstream3`) and checks sentinels (merged, PR #4). **It will be extended to merge the compiled Android classes of android-only modules** (for `:core:notification`, `:media:*`). | §3.3, §6.1, Phase 1 |
| D4 | Add a new commonMain text type **outside** `com.lagradost.cloudstream3`; mark `utils.UiText` `@Deprecated(level = WARNING)`; remove it later behind a versioned extension-API break. | §3.4, Phase 3 |
| S1 | Android `res/values*` + Weblate **remain the source of truth** for strings. `:core:strings` **generates** `composeResources` from them at build time, with aapt2 as the decoding oracle. Flip the direction only after translate-toolkit's cmp-resource read/write asymmetry is fixed upstream. Supersedes both the old "defer composeResources to Phase N" wording and the idea of making composeResources the source. | §3.4, Phase 3 |
| S2 | The new text type resolves through the **Compose resources engine on every platform**. Mitigations: format in our own common formatter (CMP only substitutes `%N$s` / `%N$d`); generate `b+he` / `b+id` copies of `b+iw` / `b+in`; guard plural lookups for `apc` / `qt` / `arz` until CMP ships the fix. | §3.4, Phase 3 |
| I0 | iOS is a declared **future** target. Every step keeps it possible; nothing ships for iOS now. | §2 |
| I1 | `:library` becomes iOS-compilable by **additive source-set surgery** (JVM-typed declarations to `jvmCommonMain` with facade names kept via `@file:JvmMultifileClass` + `@file:JvmName`, `expect` + JVM `actual typealias` where needed), each move proven with `abiValidation` + `javap`. No extension ABI break. Coordinated with upstream. | Track L |
| I2 | New first-party code lives in **non-merged** modules (no `com/lagradost/cloudstream3` directory): `:core:strings` (text type + generated strings), `:core:http` (Ktor client). `:core:common` and `:core:network` hold only frozen extension API. | §3.3, §4 |
| I3 | Ktor client approved at **3.6.0**: bump catalog `ktor` 3.5.0 → 3.6.0 (also moves `:library`'s `ktor-http`); add `ktor-client-core` (commonMain) and `ktor-client-okhttp` (jvmCommonMain), OkHttp engine preconfigured from `app.baseClient` and never closed; `ktor-client-darwin` deferred until an iOS target exists. Accepted: OkHttp 5.5.0 and okio 3.18.2 at runtime, shared with extensions. **Also accepted (documented):** only one `ktor-http` fits on the app classpath and extensions use it, so 3.6.0 changes URL behaviour for every extension — `Url.encodedPath` / `MainAPI.updateUrl` percent-encode paths (KTOR-8755), whitespace in a host throws `URLParserException` (KTOR-9759, since 3.5.2), a bare `%` in a path parses. Pinned by `:library` tests in the bump PR. | Phase 4, §8, §9 |
| I4 | Screens are **per form factor** (TV, touch, desktop) in commonMain with no `android.*`; platform glue (Fragments, Activities) in androidMain; an iOS touch app reuses the touch screens. This **reinterprets** `shared/readme.md` (see §4.4). | §4.4, Phase 7 |

---

## 1. Where the project stands

| Module | Plugin | Targets | Role |
|---|---|---|---|
| `:library` | KMP + `android.kotlin.multiplatform.library`, own build file | android, jvm (+ uncompiled `webMain`) | Published provider SDK (upstream-owned). commonMain depends on Jackson, jsoup, NiceHttp and Rhino (`library/build.gradle.kts:55-69`): it is JVM-common, not portable. Has `abiValidation` (`library/api/jvm/library.api`). |
| `:core:settings` | `cloudstream.kmp.compose` | android, jvm | `AppSettings` and its 8 `*Preferences` classes, plus the `ComposeFragment` host and `DeviceLayout` — the only first-party code above `:core:mihon`. commonMain depends on `:library`, so it is iOS-canary exempt. What was left of `:shared` (`KMP-06d`). |
| `:app` | `com.android.application` | android | CloudStream 3: 245 Kotlin files, 66,767 lines in `app/src/main`. |
| `:desktopApp` | KMP + Compose | jvm | Desktop shell over `:core:mihon` (`KMP-06d` step 1). |
| `:core:theme`, `:core:designsystem` | `cloudstream.kmp.compose` | android, jvm | CloudStream 4 tokens and components (`com.lagradost.cloudstream4.*`). Not merged into classes.jar. |
| `:core:common` | `cloudstream.kmp.extension-api` | android, jvm | Frozen extension API moved from `:app`. Merged. |
| `:core:network` | `cloudstream.kmp.extension-api` | android, jvm | `DohProviders.kt` in jvmCommonMain, `api(libs.nicehttp)`. Merged. |
| `:core:screenshot` | plain `com.android.library` | android | Screenshot tests. The plugin cannot see KMP modules and renders nothing on AGP 9.1.1 (see its build file). Never gets the iOS canary. |

The Compose bridge is proven on one vertical: 7 settings screens extend `BaseComposeFragment`
(`app/.../utils/BaseComposeFragment.kt`) and are reached from the XML nav graph.

`shared/readme.md` (LagradOst) is upstream's design doc. It says (`:103-107`) no pull requests
for the Compose rewrite will be accepted before the core structures are in place, and that AI
should not lay that groundwork. If the target is upstream merge rather than a fork,
coordinate before each phase.

**Code shape (recounted 2026-09-17):** `ui/player` 16,497 lines · `ui/result` 7,184 ·
`ui/settings` 6,714 · `ui/home` 2,902 · `ui/download` 2,829 · `ui/search` + `ui/quicksearch`
2,042 · `ui/account` 993 · `ui/library` 970 · `ui/setup` 417 · `utils` 9,793 · `syncproviders`
5,867 · `network` 282. Largest files: `ResultViewModel2.kt` 2,709 · `GeneratorPlayer.kt` 2,402 ·
`MainActivity.kt` 2,076 · `CS3IPlayer.kt` 2,023. About 1,200 `R.string.` references in
`app/src/main`; 145 layouts; 61 `values-b+*` locale dirs (the empty `values-arz` was deleted in `I18N-07` PR 5).

**"No android import" does not mean movable.** The javap audit found files with few or no
`android.*` imports (`AuthAPI.kt`, `AuthRepo.kt`, `SyncRepo.kt`, `LocalList.kt`, `APIRepository.kt`)
whose bytecode reads `:app`'s `R`, `CommonActivity` or `MainActivity`. §5 lists what is actually movable.

---

## 2. Target platforms and the iOS constraint

### 2.1 Targets

| Target | Status | Guard |
|---|---|---|
| Android phone + TV | ships | — |
| JVM | canary only; `:desktopApp` is the only JVM shell | `jvm()` in every KMP module |
| iOS (iPhone, iPad) | declared future target (I0); nothing ships | gated iOS klib canary (planned, §6.3) |
| Apple TV | open (§10 O3). Compose Multiplatform 1.12 UI has **no tvOS artifacts** | none |
| Web | not a target. `library/src/webMain` has 6 actual files but no `js`/`wasm` target compiles them. Do not delete them. | none |

### 2.2 What "iOS-ready" means for a step

A module, PR or phase is iOS-ready when all of these hold:

| Check | How it is enforced |
|---|---|
| commonMain compiles as an iOS klib (`iosArm64`, `iosSimulatorArm64`) | gated canary, §6.3 |
| commonMain has no `java.*`, `javax.*`, OkHttp/NiceHttp, Jackson, jsoup, Rhino, `synchronized`, `String.format` / `.format(...)`, `Runnable`, `android.*`, `R` | iOS canary + jvm canary |
| No commonMain dependency on a JVM-only artifact (NiceHttp publishes a jvm variant only; `ui-tooling` 1.12.0 has no iOS variant) | iOS canary (dependency resolution) |
| The commonMain public API names no JVM or Android type (`Uri`, `Bitmap`, `Context`, `Interceptor`, `Date`, `UUID`) | review; canary for the type itself |
| `jvmCommonMain` holds only code bound by the frozen extension ABI or by a JVM-only library | review; KDoc of `cloudstream.kmp.extension-api` |
| Platform capabilities sit behind interfaces (seams, §4.3), not `if (android)` | review |
| Model, data, storage, HTTP and media-contract modules depend on `compose-runtime` at most, never Compose UI (keeps a tvOS-over-shared-logic path open for them). **ViewModels are not covered:** they expose `UiString`, which resolves Compose UI and has no tvOS variant (cost of S2; rule 5, §10 O3) | review; module graph rule 5 |
| No extension-ABI break is made "for iOS" | §2.4 rule; `abiValidation`; javap tripwire |

### 2.3 What each canary catches

| | `jvm()` canary (exists) | iOS klib canary (planned) |
|---|---|---|
| Catches | `android.*` in commonMain **and** in jvmCommonMain (`jvmMain` depends on `jvmCommonMain`, `cloudstream.kmp.extension-api.gradle.kts:42-47`) | `java.*`, `synchronized`, `String.format`, JVM-only dependencies in commonMain |
| Misses | everything JVM-bound: `:library` commonMain compiles with Rhino, Jackson and NiceHttp; 54 of its 147 commonMain files import JVM-only packages | **jvmCommonMain is invisible to the iOS canary only.** Link-time gaps (it builds klibs, it does not link). |
| Cost | none | Kotlin/Native download (~217 MB cold); a macOS runner for Compose modules (§6.3) |

### 2.4 What iOS can never reuse as implemented, and the rule that protects extensions

Impossible on iOS as the code stands; each has options in §10:
dex extension loading (`PluginManager.kt:611` `PathClassLoader`) · `Plugin.load(Context)`,
`resources`, `openSettings` (`Plugin.kt:16,34,39`) · extension OkHttp interceptors for playback
(`getVideoInterceptor(extractorLink: ExtractorLink): Interceptor?`, `MainAPI.kt:702`) ·
`WebViewResolver` subresource capture · background downloads that run extension code · APK
self-update · Widevine/PlayReady · Compose UI on tvOS · `Event` (`synchronized` + `Runnable`).

**Rule: keeping iOS possible never justifies an extension-ABI break.** iOS never needs the JVM
extension surface, and every item above has an additive or no-break option. What iOS does need
is seams (§4.3), clean commonMain public APIs in new modules, and Track L for `:library`.

---

## 3. Constraints that decide the architecture

### 3.1 `:app` cannot become a KMP module
AGP 9.1.1 ships `com.android.kotlin.multiplatform.library` only; there is no multiplatform
application plugin. `:app` stays `com.android.application` and shrinks to a shell. Multimodule is
the only mechanism by which this codebase becomes multiplatform.

### 3.2 `classes.jar` is a binary contract, not an FQN list
Third-party extensions compile against `app/build/classes.jar` (`:app:makeJar`) and link against
the running app. The javap audits show the contract has more parts than the package name:

| Part of the contract | Example | Breaks when |
|---|---|---|
| Fully-qualified name | `com.lagradost.cloudstream3.utils.Event` | package rename |
| File-facade owner | `DohProvidersKt` | file renamed, or split across source sets without `@file:JvmMultifileClass` + `@file:JvmName("<Original>Kt")` on **every** part (all FQNs intact, extensions still break) |
| Nested-class FQN | `DataStoreHelper$Account` | nested class extracted to top level (a typealias is not binary-visible) |
| Class kind | `UiText` is a `sealed class` (`TextUtil.kt:12`) | becomes an interface → `IncompatibleClassChangeError` |
| Member signatures | `EmptyEvent.plusAssign(Runnable)` | parameter/return type change; new primary-constructor params on a public `data class` change `<init>`, `copy` and `componentN` |
| Compiler-emitted members | `public static final int $stable`, `@StabilityInferred` | class moves to a module without the Compose compiler. **Fixed by D1** for extension-api modules. Still to be accepted per PR: synthetic `write$Self$app` → `write$Self$<module>` renames and removed `access$…$cp` accessors. |
| `R` fields | `R$string`, `R$plurals`, `R$array` are `ACC_PUBLIC ACC_STATIC`, **not final** (javap); extensions `getstatic` them **by name** at runtime | a string key is renamed or deleted, or a `<string>` becomes `<plurals>`. Numeric ids already shift whenever a string is added. |
| Inlined bodies | `parseJson` inlines **both** kotlinx `json` and Jackson `mapper` (`:library` `utils/AppUtils.kt:60`); `Context.getKey` inlines a call to non-inline `DataStore.getSharedPrefs(): SharedPreferences` then `getString` (`DataStore.kt:107,221`) | the called function or either JSON runtime is removed. The storage contract is the `SharedPreferences` interface plus the JSON string encoding, **not** the XML file: a migration behind a `SharedPreferences` adapter is ABI-safe (not planned). |
| Class presence | anything in the jar, including `:app` classes in `com.lagradost.cloudstream4.*` | a class is deleted from `:app`. `:app` has no ABI dump; see the javap tripwire (§6.2). |

Every PR that moves a class lists its ABI delta (normally none) and adds a sentinel in the same change.

### 3.3 The jar merges whole modules
`cloudstream.extension-api-jar` merges, next to `:app`'s classes, every project on `:app`'s
`prereleaseDebug` classpaths (`:46-51`) that has a `src/*/{kotlin,java}/com/lagradost/cloudstream3`
directory (`:54-60`), as its **whole `jvm` jar** (`:67-78`). So `com.lagradost.cloudstream4.*` classes
in a merged module enter classes.jar too (as `com.lagradost.api.BuildConfig` from `:library` does).
`makeJar` fails when a left-out module declares the package, a merged module contributes no class in
it, an androidMain file in it has no jvmMain counterpart (`:126-137`), or a sentinel is missing (`:33-43`).

Consequences:
1. **I2.** New first-party code goes in a module without that directory, or it becomes permanent de
   facto extension API. `:core:common`/`:core:network` receive only classes moved from `:app`.
2. **Until D3 lands**, an extension-facing class whose body needs `android.*` cannot leave `:app`, and
   android-only modules can receive no `:app` class. After D3 their Android-variant classes are
   merged and the counterpart check applies only to modules that declare `jvm()`.
3. Old Fragments and ViewModels in `com.lagradost.cloudstream3` stay in `:app` until deleted (Phase 7).

### 3.4 Strings (S1, S2, D4)
- `R` is generated by the application plugin and is invisible from a library's commonMain.
  Per-ViewModel lines using `R.string` set the vertical order: `ResultViewModel2` 91 (93 references,
  81 distinct keys), `LibraryViewModel` 9, `HomeViewModel` 0, `SearchViewModel` 0.
- **Android res + Weblate stay the source (S1).** `:core:strings` generates Compose resources from
  `app/src/main/res` at build time; `R` ids, layouts, Weblate and `.github/locales.py` do not change.
- **Why not flip now.** translate-toolkit's `CMPResourceUnit` overrides only writing. Reading keeps
  Android semantics (`aresource.py` `process_string`/`strip_part`, lines 111-148: quote stripping,
  whitespace collapsing, trimming) and writing still backslash-prefixes a leading `@`/`?` (`:509-512`).
  A first rescan would change 61 values with a literal `"` in 11 locales (apc, ar, bg, el, hr, it,
  pt, pt-BR, ro, sv, vi), `sv` `dns_pref_summary` (one space) and every leading-`@` value.
- **Resolution (S2)** goes through the Compose resources engine on every platform; CMP 1.12.0 gaps
  and mitigations are in Phase 3. **`utils.UiText` (D4)** stays byte-identical (12 `:app` files use
  it besides its declaration), gains `@Deprecated(WARNING)`, and goes only in a versioned break.

---

## 4. Target module graph

Status: **built** = merged on master; everything else is planned. Module roles are in §4.3 and §5.

**Every module is exactly one of two kinds (follows from §3.3 and I2):**
- **frozen-API** (merged into classes.jar): receives only classes moved out of `:app` under their
  `com.lagradost.cloudstream3` FQNs. No new first-party code, whatever its package.
- **first-party** (not merged): new code in `com.lagradost.cloudstream4.*`; never gains a
  `com/lagradost/cloudstream3` directory.

Where one area needs both (e.g. moved `SubtitleData` next to a new cue model), it gets two modules.

### 4.1 Dependency rules

```
layer 4   :app
layer 3   :feature:*          :media:video/audio/cast/torrent/download (android-only)
layer 2   :core:ui   :core:data   :core:notification   :media:subtitle
layer 1   :core:designsystem   :core:model   :core:network   :core:strings   :core:http   :core:storage   :media:model
layer 0   :core:theme   :core:common   :library
```

1. **No `:feature:*` depends on another `:feature:*`.** Cross-feature navigation uses
   `@Serializable` route types declared in `:core:ui` and satisfied by `:app`.
2. **Dependencies point to lower layers only.** `:core:common` depends on nothing internal.
   `:library` depends on nothing internal (it is published).
3. `:core:common`, `:core:ui`, `:core:strings` and `:core:http` never depend on `:library`.
4. A module whose commonMain depends on `:library` (e.g. `:core:model`, `:core:storage`, ViewModels using
   `SearchResponse`) is correct in the target state (I1), but it cannot pass the iOS canary until
   Track L has made the declarations it uses iOS-compilable. Such modules sit on a canary
   exemption list in `build-logic` that may only shrink.
5. Model, data, storage, HTTP and media-contract modules (`:core:model`, `:core:data`, `:core:storage`,
   `:core:http`, `:media:model`, `:media:subtitle`) depend on `compose-runtime` at most, never Compose
   UI, and never on `:core:strings`. **This rule does not cover ViewModels, and that is a cost of S2.**
   `UiString` wraps `StringResource`/`PluralStringResource` from `components-resources`, whose 1.12.0
   module metadata lists `runtime`, `foundation` (→ `ui`) and coroutines as `api` dependencies and
   publishes no tvOS variant. So `:core:strings` is an explicit exception, and every module that exposes
   `UiString` (every `:feature:*` ViewModel, Phase 7 step 1) resolves Compose UI and cannot resolve for
   tvOS. `:feature:*` modules use `kmp.feature` and have Compose UI anyway. See §10 O3.

### 4.2 Modules, plugins and targets

| Module | State | Plugin | Targets | Merged | iOS canary |
|---|---|---|---|---|---|
| `:core:common` | built | `kmp.extension-api` (D1) | android, jvm | yes: frozen extension API only (I2) | yes |
| `:core:network` | built | `kmp.extension-api` | android, jvm | yes: frozen extension API only (I2) | yes |
| `:core:theme`, `:core:designsystem` | built | `kmp.compose` | android, jvm | no | yes, macOS runner (§6.3) |
| `:core:screenshot` | built | `com.android.library` | android | no | never |
| `:core:strings` | planned | `kmp.strings` (`kmp.library` + CMP Gradle plugin + Compose compiler); commonMain `api` is only `compose-runtime` + `compose-resources` (not `kmp.compose`, §7 Phase 3) | android, jvm | **no** (I2) | yes, by hand on macOS until the G4 job exists (`components-resources` → `foundation` → `ui`) |
| `:core:http` | planned | `kmp.library` + `jvmCommonMain` declared in its build file (§7 Phase 4) | android, jvm | **no** (I2) | yes |
| `:core:ui` | planned | `kmp.compose` | android, jvm | no | yes, macOS runner |
| `:core:model`, `:core:data` | planned | `kmp.extension-api` | android, jvm | yes: frozen-API (§5.2) | yes, subject to rule 4 |
| `:core:storage` (plan-level choice following I2, not an owner decision) | planned | `kmp.library` | android, jvm | **no**: `PreferenceStore`, `KeyValueJsonStore`, `SecretStore` | yes, subject to rule 4 |
| `:core:testing`, `:media:model`, `:media:subtitle` | planned | `kmp.library` | android, jvm | no | yes |
| `:feature:*` | planned | `kmp.feature` | android, jvm | no (old classes stay in `:app`) | yes, subject to rule 4 |
| `:core:notification`, `:media:video/audio/cast/torrent/download` | planned | `kmp.android-only` (Compose compiler per D1) | android | yes, via D3: frozen-API; their new first-party code needs non-merged siblings, named when Phase 6 starts | never |
| `:library` | exists | own build file | android, jvm | yes | no, until Track L |
| `:shared` | **deleted** (`KMP-06d`) → `:core:ui`, `:core:mihon`, `:core:settings` | — | — | no | — |
| `:core:settings` | exists | `kmp.compose` | android, jvm | no | no, until Track L (rule 4) |
| `:app` | exists | `com.android.application` | android | yes (its own classes) | never |

Android-only is irreducible for: Media3 and Cronet/nextlib (`:media:video`), Play Services Cast,
SAF, the gomobile TorrServer AAR, NotificationCompat. **Downloads run in a `dataSync` foreground
service** (`DownloadQueueService`, `AndroidManifest.xml:263-265`); **WorkManager is still used**
for backups and subscriptions (`services/BackupWorkManager.kt`, `services/SubscriptionWorkManager.kt`).
An iOS implementation is a sibling module over `:media:model`, never an `actual` inside these.
`shared/readme.md` agrees that a cross-platform player is out of scope.

### 4.3 iOS seams (no ABI cost, built as each area is carved)
The commonMain column is first-party code (non-merged modules: `:media:model`, `:media:subtitle`,
or a sibling named in Phase 6); the platform column is androidMain or an android-only module.

| Area | commonMain holds | Platform side |
|---|---|---|
| `:media:model` | new data-only contracts in a `cloudstream4` package: `StreamRequest`/`PlaybackRequest` (url, type, headers, referer, tracks, DRM data), `EngineCapabilities` (link types, containers, DRM schemes, header support), `CaptionStyle`, `SubtitleCue`, `PlayerCommand`, `NowPlaying`, `PipState`, `DownloadProgress` | legacy `IPlayer` (imports `Context`, `Bitmap`, `Rational`), `VideoGenerator`, `ExtractorUri` (`android.net.Uri`) stay in `:app` as Android adapters |
| `:media:subtitle` | cue model, `CaptionStyle`, offset logic, pure SRT/VTT parsers | Media3 decoder factory, spans, `SubtitleView` styling |
| `:media:download` | queue/state/persisted objects with `String` locations; byte engine (ranged HTTP, HLS, decrypt, concat) over an injected HTTP client and file sink; link resolution as a separate step before bytes | scheduling (foreground service) |
| `:media:cast` | `CastPayload` (url, content-type string, title, poster, subtitles), FCast protocol/session, discovery interface; no Media3 `MimeTypes` | Play Services Cast, `NsdManager` |
| `:media:torrent` | TorrServer HTTP client + `TorrentStatus` (kotlinx, not Jackson); `TorrentServerHost` interface (`start(dir): port`) | AAR wrapper; cache dir injected instead of `CommonActivity.activity` |
| `:media:audio` | "audio-only / background requested" as state | audio focus |
| `:core:http` | client factory, DoH provider table as data | OkHttp engine. On iOS, Darwin/URLSession DoH is process-wide; per-connection DoH needs an `NWConnection` transport. |
| `:core:ui` | form-factor type + `CompositionLocal` (today `shared/src/androidMain/.../compose/Layout.kt`), `@Serializable` routes with primitive/ID args | detection actual |
| `:core:storage` | `PreferenceStore`, `KeyValueJsonStore`, `SecretStore` interfaces; kotlinx.serialization only | Android actuals on today's two files |
| `:feature:account`/`setup` | `AccountManager.handleRedirect(url)`; `AuthBrowser`, `AppLock` interfaces | `MainActivity` delegates; biometric |
| `:feature:settings` | platform-only settings behind a capability object; locale as a BCP-47 tag | Monet, SAF pickers, self-update |
| `:feature:extensions` | `ExtensionRuntime` seam (install, load, unload, providers); repository/index/install state/verification | `PathClassLoader` runtime |

### 4.4 Screens per form factor (I4)
`shared/readme.md:15` says "we do not want a shared screen layout, because each platform has its
own design language", and `:23-24` lists *Entire Screens* under **Avoid**. **This plan reads
"platform" as "form factor"**: the TV, touch and desktop design languages differ; Android phone
and iPhone share one. So:
- screen composables live in `:feature:x` commonMain, one per form factor (TV, touch, desktop),
  with no `android.*`, `Context`, `Fragment`, `Bundle` or `R`, receiving everything through
  parameters and `CompositionLocal`s;
- Fragment/Activity glue lives in androidMain (new code in a `cloudstream4` package, §3.3);
- an iOS touch app would reuse the touch screens; `:desktopApp` uses the desktop ones.

This is a reinterpretation of an upstream maintainer's document; raise it with upstream before
screen PRs if upstream merge is the goal.

### 4.5 `:media:audio` is near-empty
Every audio concern today is a track inside the video player (`CS3IPlayer`, `PlayerGestureHelper`).
It starts as a seam for audio focus and background playback; an `audio` package in `:media:video` is the fallback.

---

## 5. Where today's code lands

Based on the javap movability audit of `:app`. Every "movable" row was checked against bytecode,
not imports.

### 5.1 Done

| Module | Moved | Source set | PR |
|---|---|---|---|
| `:core:common` | `IDisposable`, `IDisposableHelper`, `Vector2` | commonMain | #4 |
| `:core:common` | `Event`, `EmptyEvent` (`synchronized`, `Runnable`) | jvmCommonMain | #4, `$stable` restored in #5 |
| `:core:network` | `DohProviders.kt` → `DohProvidersKt` (8 `OkHttpClient.Builder` extensions) | jvmCommonMain | #6 |
| `:core:data` | B1: `DataStoreKt` (8 consts), `DataStoreHelperKt` (14 consts) | commonMain | #14 |
| `:core:model` | B2: `AbstractSubtitleEntities` (+nested), `SyncSearchViewModel` (+nested), `IGeneratorKt` (4 `LOADTYPE_*` vals); `:app`'s `IGenerator.kt` renamed `VideoGenerator.kt` | commonMain | #16 |
| `:core:model` | B3: `AuthToken`, `AuthUser`, `AuthData`, `AuthLoginResponse` (+`$Companion`, `$$serializer`, `annotationImpl`) | jvmCommonMain (`AuthDtos.kt`) | #19 |

**B3's accepted ABI delta.** Moving those four cost exactly four lines in `app/api/classes-jar.api`:
the synthetic `write$Self$app` methods became `write$Self$CloudStream_core_model`, because Kotlin
mangles `internal` members with the JVM module name and `:core:model`'s is `CloudStream.core:model`.
Nothing else changed — same 3763 `com/lagradost` jar entries, and a whole-jar scan of all 910
`$stable` initialisers reports zero differences, so this is **not** a repeat of the B2 hazard: no
class reads these DTOs' `$stable` with `getstatic`, before or after. The methods are `ACC_SYNTHETIC`
and their only callers are the co-located `$$serializer.serialize` bodies, which moved with them; the
same mangling is already in the committed dump as 122 `write$Self$CloudStream_library` entries next
to 236 `write$Self$app`. Two mitigations were measured and rejected: `@Immutable` on the DTOs hides
the rename from nothing and silently flips `AuthUser`/`AuthData` `$stable` from 8 to 0, and pinning
`:core:model`'s module name to `app` makes `makeJar` fail on a duplicate `META-INF/app.kotlin_module`.

**`AuthLoginPage`, `AuthPinData` and `AuthLoginRequirement` did not move with B2** and are not in §5.2:
`:app`'s Compose-compiled `AuthAPI` has an `AuthLoginRequirement` field, so across modules its `$stable`
initialiser becomes `getstatic AuthLoginRequirement.$stable` (0) instead of the constant 8, turning
`AuthAPI`, `SubtitleAPI`, `BackupAPI`, `AuthRepo` and `PlainAuthRepo` Compose-stable at runtime with no
signature change (they reach composables in `SettingsAccountScreen`). They move with `AuthAPI`, or with an
explicit decision to accept that.

**New modules that took no `:app` code.** `:core:strings` (#17, #18) generates Compose resources
from `app/src/main/res` rather than moving anything; `:core:http` (#11) wraps `app.baseClient`;
`:core:storage` (#20, #22) is new ports plus Android and JVM implementations, and nothing in `:app`
calls it yet. None of them appear in `classes.jar`, so none of them has an ABI delta.

### 5.2 Movable next (no open ABI decision)

All targets use `cloudstream.kmp.extension-api` (D1), so `$stable` is kept. Each row needs its
sentinels and a clean `checkKotlinAbi` / javap comparison.

| # | Members | Target | Prerequisites and notes |
|---|---|---|---|
| B4 | `DdosGuardKiller` | `:core:network` jvmCommonMain | Body-only change first: a private header merge replaces the call to `:app`'s `RequestsHelperKt.getHeaders`. Adds `:library`, coroutines, `annotation`. |
| B5 | `SSLTrustManager`, `DownloaderTestImpl` | — | **Closed, not moving** (`KMP-02`): the security review found there is no gate to carry over, and both classes are already published from `:app`'s jar. `BUG-10` came out of it — playback was disabling TLS verification process-wide — and is fixed separately. |
| B6 | `TestingUtils` (+8 nested) | `:core:model` commonMain | Needs `:library` + coroutines, so **not** `:core:common` (rule 2). |
| B7 | `fcast/Packets.kt` (`Opcode` + 8 message classes) | `:core:common` **commonMain**, split — **done** (`KMP-04`) | **Split, not moved whole.** `PlayMessage` stays in `:app`: `@Serializable` emits `write$Self$app`, carrying `:app`'s module name, and that member is published (`classes-jar.api:5293`), so moving the class is a `DEC-08`-class ABI delta. The other 8 have no module-suffixed member and went to `commonMain`. **`:core:common` therefore gains no dependencies** — no serialization plugin, no Jackson — which is what `DEC-07` was weighing, so that decision dissolved. Dump byte-identical; `$stable` kept on all 7 data classes. |
| B8 | `APIRepository` (+companion, `SavedLoadResponse`, anonymous `noneApi`/`randomApi`) | `:core:data` jvmCommonMain — **done** (`KMP-05`) | Inverted: `CloudStreamApp` subscribes `APIRepository.Companion::onPluginsLoaded` once, where every instance used to subscribe and never unsubscribe. **Measured delta: `access$afterPluginsLoaded` out, `onPluginsLoaded` in.** `access$getAfterPluginsLoadedEvent$cp` is **not** removed — `DEC-08` named the wrong entry. jvmCommonMain is load-bearing: commonMain would force `:core:data` into `IOS_CANARY_EXEMPT`. |

### 5.3 Stays in `:app`, and what would unblock it

| Class(es) | Why it cannot move today | Unblocked by |
|---|---|---|
| `RequestsHelperKt` | 6 of 7 public methods take `Context`; body reads `R.string.dns_key`, `PreferenceManager`, Conscrypt; `getHeaders` shares the facade | an additive Context-free builder in `:core:network` with `:app` shims (JVM Conscrypt would be a new dependency) |
| `CloudflareKiller` | body uses `android.webkit.CookieManager`, `android.util.Log`, `WebViewResolver.getWebViewUserAgent` (androidMain only) and `RequestsHelperKt.getHeaders` | D3 android-only merge, plus the `getHeaders` split |
| `DataStore`, `Editor` | 17 public signatures with `Context` / `SharedPreferences` / `Editor`; inlined into extensions | stays as an Android facade permanently |
| `PreferenceDelegate`, `UserPreferenceDelegate` | reach `CloudStreamApp` context and `DataStoreHelper.currentAccount` | the `KeyValueJsonStore` port + account-index extraction (Phase 4) |
| `DataStoreHelper` + nested models (`$Account`, `$PosDur`, `$BookmarkedData`, …) | `Context` in ABI; `$Account` exposes `UiImage` (`Bitmap`); models are nested, extraction changes FQNs | a versioned break, or keep nested |
| `UiText`, `TextUtilKt` | `asString(Context)` members; `txt()` shares a facade with `setText(TextView, UiText)` | stays (D4) until removal in a versioned break |
| `BackupUtils` | `Context`/`FragmentActivity`/`Activity`/`SafeFile` in ABI; depends on the download stack | stays |
| `AcraApplication` | deprecated binary-compat shim returning `Context` | stays |
| `AccountManager`, `AuthAPI`, `AuthRepo`, `PlainAuthRepo`, `BackupAPI`, `SyncRepo`, `LocalList` | composition root; ABI reaches `ListSorting` / `SyncWatchType` (R-derived enums), `CommonActivity.showToast`, `R$string` | ABI decisions on R-derived enums and `@WorkerThread` (§10.11) |
| `Addic7ed`, `SubDlApi`, `SubSourceApi`, `AnimeSkipAuth`, videoskip family (`SkipAPI` registry) | supertypes pinned to `:app`; `R$drawable` icons; `SkipType` `@StringRes`; `ResultEpisode` in ABI | same, plus an icon/string indirection (§10.11) |
| `VideoGenerator`, `NoVideoGenerator`, `ExtractorLinkGenerator` | `ExtractorUri(android.net.Uri)` in a third-party extension point | D3 (Android-variant merge) for an android-only home; `:media:model` never holds them |
| `Torrent` (+DTOs) | `CommonActivity.getActivity()`, gomobile AAR | D3 + injected cache dir |
| `UriSerializer` | `KSerializer<android.net.Uri>` (javap) | D3; never `:core:model` |
| `SearchSuggestionApi` | feature code; belongs to `:feature:search` (stays merged if it keeps its package). Hardcoded TMDB key ships in the jar wherever it lives. | Phase 7 search vertical |
| `MainActivity`, `CommonActivity`, `AppContextUtils`, `UIHelper`, Manifest, `res/`, nav graph | shell by definition | Phase N |

### 5.4 Later destinations (no LOC estimates until the audit reaches them)

| Target | Source |
|---|---|
| `:core:ui` | `:shared` `Screen`, `Viewmodel`, `com/mihon/presentation/*` (commonMain); `ComposeFragment` + `BaseComposeFragment` glue (androidMain) |
| `:core:designsystem` | `:shared` `Dialog`, `Modifiers`, `com/mihon/material/*`, settings widgets |
| `:core:storage` | `:shared` `PreferenceStore`, `DevicePreferenceStore` (imports `:library` types, rule 4) |
| `:media:video` | `CS3IPlayer`, `UpdatedMatroskaExtractor`, `UpdatedDefaultExtractorsFactory`, `PreviewGenerator`, `FixedNextRenderersFactory` (after D3). Move activity control (`moveTaskToBack`) and the torrent accept dialog out to `:feature:player`; make the global SSL bypass a per-request option. |
| `:media:subtitle` | `PlayerSubtitleHelper`, `CustomSubtitleDecoderFactory`, `CustomSubripParser` (uses `android.text.Html`), span renderers, `ui/subtitles/`, all rewritten as first-party code. `SubtitleData` is extension ABI (Jackson `@JsonIgnore`): it moves only into a frozen-API module, keeping its FQN, with a sentinel. |
| `:media:download`, `:feature:*` | per §4.3 and §7 Phase 7 |

---

## 6. Build infrastructure

### 6.1 Convention plugins

| Plugin | Provides | State |
|---|---|---|
| `cloudstream.kmp.library` | `android()` + `jvm()` canary, `jvmTarget`, SDK levels, `-Xexpect-actual-classes`, `:library` opt-ins applied only when `:library` is a dependency | built |
| `cloudstream.kmp.compose` | the above + CMP, Compose compiler, `compose` bundle as commonMain `api`, `ui-tooling` on `androidRuntimeClasspath`, per-module `Res` package | built |
| `cloudstream.kmp.extension-api` | `kmp.library` + Compose compiler + `compose-runtime` only + `applyDefaultHierarchyTemplate()` + `jvmCommonMain` (D1); `abiValidation` with `:library`'s `Prerelease`/`InternalAPI` excludes (D2) | built (#5); abiValidation in #7 |
| `cloudstream.extension-api-jar` | `copyJar`/`makeJar`: derived merge, sentinels, left-out-package and androidMain-counterpart checks | built (#4) |
| android-only merge in `cloudstream.extension-api-jar` | merge the Android-variant classes of modules without `jvm()`; counterpart check only for `jvm()` modules; `DuplicatesStrategy.FAIL` watches for R/BuildConfig collisions | planned (D3) |
| `cloudstream.kmp.android-only` | `android()` only + Compose compiler (D1); never the iOS canary | planned |
| `cloudstream.kmp.feature` | `kmp.compose` + `:core:ui`, `:core:designsystem`, `:core:strings` | planned |
| Compose string generation (`:core:strings`) | S1 generator + `b+he`/`b+id` copies, in `:core:strings`' own build file (`kmp.library` + CMP plugin + Compose compiler; commonMain `api` only `compose-runtime` + `compose-resources`), §7 Phase 3 | planned |

### 6.2 ABI guards

| Guard | Covers | Misses |
|---|---|---|
| `makeJar` sentinels | class presence for named classes | signatures, `$stable` |
| `abiValidation` (`api/jvm/*.api`; `:library` today, `:core:common` + `:core:network` in #7) | public signatures incl. `$stable` | `:app` classes; synthetic members |
| javap descriptor diff of classes.jar vs master (planned) | every class in the jar, `:app` included (e.g. a `SitePlugin` data-class change) | nothing structural |

The javap tripwire is required before Track L's first move and before any `:app` data-class edit.
It must not be wired into `check`: `copyJar` depends on `build`, which depends on `check`, so a
check-time task reading classes.jar forms a cycle. Run it as a separate CI step after `makeJar`,
or read the `compile_app_classes_jar` intermediate.

When the iOS canary becomes always-on, commit `<module>.klib.api` in the same PR. `api/jvm/*.api`
remains the extension contract; the klib dump only guards future Kotlin/Native consumers.

### 6.3 Planned convention changes (iOS guardrails)
Each is a no-op for Android artifacts and classes.jar. Rollout order G1 → G2 → G3+G4 → G5.

| Step | Change | Why |
|---|---|---|
| G1 | `cloudstream.kmp.compose`: add the `compose` bundle to commonMain **without** `compose-ui-tooling`; add `ui-tooling` to `jvmMain`; keep `androidRuntimeClasspath`. Fix the KDoc at `:11-12`. No catalog edit; `:shared`/`:desktopApp` unchanged. | `ui-tooling` 1.12.0 has no iOS variant (catalog bundle, `gradle/libs.versions.toml:183`). `@Preview` comes from `ui-tooling-preview`, which has one. |
| G2 | `applyDefaultHierarchyTemplate()` in `cloudstream.kmp.library` after `jvm()` | idempotent (KGP ignores a re-applied template); gives `iosMain`/`appleMain` to modules that add manual `dependsOn` |
| G3 | Gated canary in `cloudstream.kmp.library`: `-Pcloudstream.iosCanary=true` adds `iosArm64()` + `iosSimulatorArm64()`, registers `iosCanary` (depends on both `compileKotlinIos*`), disables `KotlinNativeSimulatorTest`. No `iosX64` (CMP 1.12 removed it). Honours the exemption list (§4.1 rule 4). | klibs only: nothing links, nothing ships |
| G4 | Separate non-blocking CI job (`continue-on-error: true`, `~/.konan` cache) running `./gradlew iosCanary -Pcloudstream.iosCanary=true`; the existing `build` job is untouched. **Compose modules need a macOS runner**: Kotlin/Native cross-compiles Apple klibs on Linux only when no dependency uses cinterop, and Compose UI iOS klibs are cinterop-backed. Non-UI modules may run on ubuntu. Verify once on the owner's Mac before merging. | proves the canary; runner choice is open (§10 O4) |
| G5 | Owner switches after ~two green weeks: make the job required; optionally default the property to true; optionally a scheduled macOS link + simulator-test job; a `tvosCanary` for non-UI modules only if Apple TV is chosen. | — |

Record in the KDoc: klib cross-compilation on Linux holds only while no library module uses
cinterop or CocoaPods.

### 6.4 Enforcing the graph, and the payoff
A ~30-line `build-logic` check fails when a `:feature:*` resolves another `:feature:*` (rule 1) or
a non-merged module gains a `com/lagradost/cloudstream3` directory. Payoff: the 67k-line `:app`
recompiles almost entirely on any change; splitting it is the main build-time lever.

---

## 7. Phases

Tracks run in parallel where the dependency column allows. Efforts are rough; Track L is not
sized (it depends on upstream), and no iOS app work is sized: the plan only keeps it possible.

### Next PRs, in order

Rows 1–13 and 16 of the original list are merged; see §5.1 and the table below for what they became.
Everything still open is listed here.

| # | PR | Depends on | State |
|---|---|---|---|
| 14 | Phase 5: split `:shared` | — | **done**, differently: `:core:ui` (#41), `:core:mihon` (#42), `:core:settings` (`KMP-06d`). Not `:core:designsystem`, which already existed — see `docs/tasks/kmp.md` `KMP-06`. |
| 15 | `:media:model` skeleton (contracts only) | — | **done** — #48 (`KMP-07`), plus the `StreamRequest` adapter in #59 |
| 19 | B4 `DdosGuardKiller` into `:core:network` | — | **done** (`KMP-01`) — it is in `core/network/src/jvmCommonMain` |
| 20 | B5 `SSLTrustManager`, `DownloaderTestImpl` | — | **closed, not moving** (`KMP-02`); the review produced `BUG-10` |
| 21 | B6 `TestingUtils` into `:core:model` | — | **done** (`KMP-03`) — it is in `core/model/src/commonMain` |
| 22 | B7 `fcast/Packets.kt` into `:core:common` | — | **done** (`KMP-04`), **split**: 8 classes to `commonMain`, `PlayMessage` stays in `:app` (`write$Self$app`). `DEC-07` dissolved — `:core:common` gains no dependencies |
| 23 | B8 `APIRepository` into `:core:data` | — | **done** (`KMP-05`), awaiting the `DEC-08` sign-off. Delta measured: **one** line out (`access$afterPluginsLoaded`, a synthetic for a private method), one in. `DEC-08`'s predicted entry (`access$getAfterPluginsLoadedEvent$cp`) is **not** removed. Placed in `jvmCommonMain` so `:core:data` stays out of `IOS_CANARY_EXEMPT` and the canary stays 12/12 |
| 17 | `:feature:extensions` prep: `ExtensionRuntime` seam, repository model in commonMain | 14 | **contracts half done** (`KMP-11`): the module exists, commonMain only, no `:app` change, no jar merge, and the canary **grew 12 → 13** because the module depends on nothing. The half that **adapts `:app`'s `PluginManager` to the seam still needs the owner's explicit go-ahead** |
| 18 | First vertical (search + quicksearch) | 14 | **steps 1 and 2 of 5 done** (`KMP-12`): contracts, provider seam, MVI state machine and the **TV screen**, 16 tests. **The Track L note does not apply** — `SearchResultItem` restates what the UI reads and `SearchSource` is the seam, so commonMain never touches `:library`, needs no `IOS_CANARY_EXEMPT` entry and joins the canary, taking it from 13 modules to **14**. Step 2 is **TV only**: artboards 4a–4c and 8c are built; **touch and desktop have no design at all** (`screen-implementation-handover.md` §7), and Phase B of that document forbids improvising one. Reading the design changed the state model — a blank query is recent-search *content*, not an empty prompt (4c), and results are grouped per provider (4a). Glue fragment and the nav repoint are next; `:app`'s search path is untouched |
| L | Track L: `:library` iOS surgery | — | design workflow first (§2.4); blocked on O2 |

**What landed in rows 1–13 and 16**

| Row | PR | Note |
|---|---|---|
| 2 | #7 | `abiValidation` on extension-api modules; CI runs `checkKotlinAbi` |
| 3 | #8 | G1 `ui-tooling` out of commonMain |
| 4 | #12 | G2 + G3 gated iOS klib canary. **G4's CI job is still not built** — it waits on §10 O4 (which runner), and no CI has ever run it |
| 5 | #13 | D3 android-only merge + `cloudstream.kmp.android-only` |
| 6 | #15 | javap tripwire: `app/api/classes-jar.api` + `:app:checkExtensionApi` |
| 7 | #14 | B1 `:core:data` |
| 8 | #16, #19 | B2 then B3. B6 did **not** ship with them and is row 21 above |
| 10 | #17, #18 | S-a `:core:strings` + generator; the `values-b+he` / `values-b+id` copies |
| 11 | #21, #23 | S-b `UiString`, formatter, plural guard, `UiText` deprecation; then the platform-locale reversal |
| 12, 13 | #10, #11 | ktor 3.6.0, then `:core:http` |
| 16 | #20, #22 | `:core:storage` interfaces + Android actuals + persistent JVM stores, then the file split |

**Row 9 was split.** It bundled B4, B5, B7 and B8 into one PR; they have different prerequisites
(B5 needs a security review, B7 needs a placement decision, B8 needs an accepted ABI delta), so
they are rows 19–23 above, one each.

> **CI does not run, by decision.** `DEC-01` was answered by the owner on **2026-10-15**: the
> GitHub Actions billing failure will not be fixed, and all five workflows are `disabled_manually`.
> **Do not re-enable them.** The four local gates — `checkKotlinAbi`, `assemblePrereleaseDebug lint
> check`, `:app:checkExtensionApi`, `iosCanary` — are the agreed verification, and every PR body
> names its own exit codes. `docs/HANDOVER.md` has the commands and the traps.
>
> CI did run on pull requests from **2026-10-07 to 2026-10-15** (`INF-01`, now closed). Outside that
> window — which is most of this series — every check quoted in a PR body is a local run, on one
> machine with one JDK. `INF-08`, the runner killing `Check classes.jar ABI` at random, is **parked**:
> it only bites when CI runs.

### Phase 0 — build-logic + jar safety · **done**

### Phase 1 — Guardrails · ~1 week
G1–G4 (§6.3), D3 android-only merge, javap tripwire.
**Exit:** `./gradlew iosCanary -Pcloudstream.iosCanary=true` green for `:core:common`,
`:core:network`, `:core:theme`, `:core:designsystem`; `:app:makeJar` green with one android-only
module merged; the javap diff reports empty on master.

### Phase 2 — Carve jvm-clean clusters out of `:app` · ~1–2 weeks
B1–B8 (§5.2), one PR each. Nothing in §5.3 moves in this phase.
**Exit per PR:** `:app:assembleStableDebug`, `:app:makeJar`, `checkKotlinAbi` green; javap of every
moved class identical before and after (or the accepted delta listed in the PR).

### Phase 3 — Strings pipeline and the new text type (S1, S2, D4) · ~2 weeks

**S-a. `:core:strings` generates Compose resources from Android res.** Built in one PR (module, generator, parity
tests, adversarial fixtures, CI guard wiring); corrections to the first draft are marked *(corrected)*.
- Module: **not** `cloudstream.kmp.compose`. `cloudstream.kmp.strings` (build-logic) applies `cloudstream.kmp.library`,
  the CMP Gradle plugin and the Compose compiler; commonMain `api` is only `compose-runtime` + `compose-resources`;
  `androidResources { enable = true }`; `Res` is public in `com.lagradost.cloudstream4.core.strings.generated.resources`,
  `generateResClass = always`. No `com/lagradost/cloudstream3` directory, so not merged. `:core:strings:fixtures`
  runs the same pipeline over adversarial inputs. No new dependency.
- Task `generateComposeStrings` (`strings.GenerateComposeStringsTask`: cacheable, relocatable, CC-safe):
  1. Inputs: `app/src/main/res/values*/strings.xml` read as files (`:app` is never configured), plus the names in
     `core/strings/visible-donottranslate.txt` (22) from `values/donottranslate-strings.xml`.
  2. *(corrected)* **Sanitise, then `aapt2 compile --legacy` only.** No `link --proto-format` (it needs `android.jar`
     and fails on library ids); `--legacy` matches AGP's in-process compiler. The sanitiser removes the 31 stray
     top-level `"` nodes by splicing the original text (no XML re-serialisation, so `&#13;`/CR never change), proves
     the result event-identical, and runs StAX with DTDs and external entities disabled: a DOCTYPE fails the build.
     The `.flat` table is read with a JDK-only protobuf wire reader, checked against the XML element count and, on
     the fixtures, against a golden decode made with `protobuf-java`. aapt2's decoded value is the oracle.
  3. *(corrected)* **Aliases resolve in the requesting locale** (`duplicate_cancel` = the locale's `cancel`), and
     values are **materialised** into locale directories wherever Compose's fallback would differ from Android's.
     **Owner decision: Compose matches Android's API 24+ fallback exactly** — AOSP `ResTable_config::match` /
     `isLocaleBetterThan` and `LocaleData.cpp` are ported (`tl`≡`fil`; `he`→`iw`, `id`→`in`; same-script regions:
     `pt`/`pt-PT` fall back to `b+pt+BR`; `zh-TW` never falls back to Hans `b+zh`, so its missing keys get the default).
     The 34 styled `<b>` values (1 in base) ship as plain text. Arrays and build-type/flavor overlays are not generated.
  4. *(corrected)* **No generator-side trim**: aapt2 already trims; trimming again breaks quoted and styled values.
  5. Written in CMP 1.12.0 escaping (every `\` doubled, `\n`/`\t`, entities, `\uXXXX` for controls), one
     `strings.xml` per directory, plus TSV oracles. **Done:** `values-b+he`/`values-b+id` are written as exact copies
     of the emitted `values-b+iw`/`values-b+in` (`localeCopies`); a real `b+he`/`b+id` source directory fails the build.
  6. Fed through `compose.resources.customDirectory("commonMain", task.flatMap { it.outputDir })`.
- *(corrected)* **Guards policy (owner decision).** Content guards (Unicode whitespace aapt2 and aaptcompiler treat
  differently, `&#13;`, unmodelled qualifier directories, unresolvable references, predicted parity gaps) follow
  `-Pcloudstream.strings.guards=fail|report`: `fail` in `check` on pull requests (`pull_request.yml` passes it); `report` (the default, so a translation on master never breaks local, IDE or release builds) in
  `.github/workflows/strings_guards.yml` on pushes to master (Weblate), as warning annotations. aapt2 failures,
  DOCTYPEs and duplicates fail in both modes, with aapt2's own messages.
- Tests: jvmTest reads the packaged output back through the real CMP runtime — every item vs the aapt2 oracle
  (runtime decoder and raw `.cvr`), and every request in `parity-requests.txt` plus each directory's locale
  (`pt-PT`, `fil`, `tl`, `zh-Hant-TW`, `nb`, `he`/`iw`, `id`/`in`, …) vs the Android model; CMP version pinned.
  Documented gaps: `zh-HK`/`zh-Hant-HK` (Android `b+zh+TW`, Compose `b+zh`); `he`/`he-IL`/`id`/`id-ID` match.
  The model itself was checked once on an API 37 emulator against the APK's real resource lookup; API 23 (pre-N
  matcher) is not modelled.
- **Unchanged:** `app/src/main/res`, `R` ids, Weblate, `.github/locales.py`, the APK and classes.jar. The APK gains
  only assets under `composeResources/com.lagradost.cloudstream4.core.strings…` once `:app` depends on it.

**S-b. The text type (working name `UiString`) in `:core:strings` commonMain.**
```kotlin
sealed interface UiString {
    data class Raw(val value: String) : UiString
    data class Resource(val res: StringResource, val args: List<Any> = emptyList()) : UiString
    data class Plural(val res: PluralStringResource, val quantity: Int, val args: List<Any> = listOf(quantity)) : UiString
}
@Composable fun UiString.resolve(): String   // stringResource(res) / pluralStringResource(res, q), then format
suspend fun UiString.load(): String          // getString / getPluralString, then format
```

**What S2 costs in the module graph.** `StringResource` and `PluralStringResource` are
`components-resources` types, and that artifact (1.12.0 module metadata) has `foundation`, and through
it `ui`, as `api` dependencies, with no tvOS variant. Every module that exposes `UiString` therefore
resolves Compose UI and cannot resolve for tvOS. The narrow `:core:strings` setup (S-a) keeps the cost
to that: no material3, coil, lifecycle or ui-tooling reach a consumer through it. This is the rule 5
exception (§4.1) and the reason §10 O3's SwiftUI option cannot reuse ViewModels as they are.

| CMP 1.12.0 gap | Mitigation |
|---|---|
| Argument overloads substitute only `%N$s` / `%N$d` | Always fetch the raw pattern, then format with **our commonMain formatter** covering the specifiers present in the strings (`%s`, `%d`, `%1$s`, `%1$d`, `%.1f`, `%.2f`, `%%`). Format **only when args are non-empty** (as `TextUtil.kt` does), so `100%` strings do not throw. A jvmTest runs the formatter over every value in every locale against `java.util.Formatter`. Feature code must not call Compose's argument overloads (rule; not compile-enforced). |
| Plural lookup throws `NoSuchElementException` for languages without CLDR rules (`apc`, `qt`, `arz`) | Guard: for those languages use the `other` item. A canary test asserts CMP still throws, so the guard is removed when a CMP release contains the fix. |
| `iw`/`in` vs `he`/`id` | generated `b+he`/`b+id` copies (done; `he`, `he-IL`, `id`, `id-ID` are `match` in the parity test) |
| Only the first locale in the list; `zh-HK`/`zh-Hant-HK` fall back to `b+zh` (Android: `b+zh+TW`) | accepted; a documented gap in `parity-requests.txt` |
| `Resource.equals` compares `"string:<name>"` with no module, so `:shared`'s `Res.string.ok` equals `:core:strings`' | never rely on `StringResource` equality across modules; consolidate `:shared`'s strings into `:core:strings` later |

- Android: locale follows `Locale.current` after `CommonActivity.setLocale` + `recreate()`.
  Outside composition `load()` uses the process default locale, so services, workers and
  notifications keep `context.getString(R.string.x)`.
- D4: add `@Deprecated(WARNING)` to `utils.UiText` and `txt`; verify with javap that only a
  `Deprecated` attribute is added. It surfaces warnings in extension builds. Adapters between
  `UiText` and `UiString` must live in `:app`, and every `:app` class is in classes.jar whatever
  its package; record that in the PR.

**Future flip (not scheduled, S1).** Only after hosted Weblate reads cmp-resource with CMP semantics.
Then also: keep the live mask shape (probably `values-b+*`), disable "add new translation" (it would
create build-breaking `b+pt-rBR` dirs), review `cleanup.blank`, turn repository hooks off during the
lock, and keep the real `action_open_play` translations in `ar`, `so`, `ur`, `pt`.

### Phase 4 — First-party HTTP (I3) and storage · ~2 weeks

**H-a. Catalog:** `ktor = "3.6.0"` (was 3.5.0, `libs.versions.toml:37`; also drives `ktor-http` at
`:116`); add `ktor-client-core` and `ktor-client-okhttp` (`version.ref = "ktor"`).

**H-b. `:core:http`** (`cloudstream.kmp.library`, package `com.lagradost.cloudstream4.core.http`,
not merged):
- Source sets: `cloudstream.kmp.library` creates no `jvmCommonMain`; only `kmp.extension-api` does
  (`cloudstream.kmp.extension-api.gradle.kts:42-47`). `:core:http`'s build file declares it the same way:
  `applyDefaultHierarchyTemplate()` (redundant after G2, harmless), `jvmCommonMain` depending on
  `commonMain`, `androidMain` and `jvmMain` depending on `jvmCommonMain`. Not `kmp.extension-api`: that
  adds the Compose compiler and marks a frozen-API module. If a second non-merged module needs
  `jvmCommonMain`, lift it into an opt-in of `kmp.library`.
- commonMain: a CloudStream-owned client factory; the DoH provider table as data. Engine types do
  not leak into the commonMain API.
- jvmCommonMain: OkHttp engine with `preconfigured = app.baseClient` (built in
  `RequestsHelper.kt:41-70`; `:app` passes it in, so `:core:http` needs no `:library` edge). It shares
  that client's DoH resolver, cache and interceptors.
- **Never call `close()`**: the engine then shuts down the shared dispatcher and evicts the shared
  connection pool. Hold the client for the process; rebuild it when the DNS setting changes (the
  engine snapshots the client). Never build it from `insecureApp`. If `ktor-client-logging` is ever
  added, sanitize `Authorization`, `Cookie`, `Set-Cookie`.
- `ktor-client-darwin` goes to `appleMain` when an iOS target exists.

**`:core:storage` stores.** There are two files: default `SharedPreferences` (settings, read by
`PreferenceStore`) and `rebuild_preference` (JSON records; `DataStore.kt:26,104`). Settings stay on
`PreferenceStore`; JSON records get a common `KeyValueJsonStore`; tokens and the PIN get a
`SecretStore` interface. Android actuals use today's files unchanged (cheapest; a migration behind
a `SharedPreferences` adapter would be ABI-safe but is not planned). commonMain JSON is
kotlinx.serialization only. `PreferenceDelegate`/`UserPreferenceDelegate` move only on top of
`KeyValueJsonStore`. OAuth redirect dispatch moves to `AccountManager.handleRedirect(url)` with
`MainActivity.handleAppIntentUrl` delegating.

### Phase 5 — Split `:shared` · done, not as written below
**What happened** (`docs/tasks/kmp.md` `KMP-06` has the reasoning and the measurements): `:core:ui`
took the first-party Compose layer (#41), `:core:mihon` the whole vendored tree (#42) rather than
splitting it across `:core:ui`/`:core:designsystem` — `:core:designsystem` already existed with the
redesign system and would have collided on `CloudStreamTheme` — and `:core:settings` took
`AppSettings` and the Android Compose host (`KMP-06d`), because all three of `ComposeFragment`'s
edges into `com.mihon` make `:core:ui` a cycle. `DevicePreferenceStore` did **not** go to
`:core:storage`; that paragraph is costed in `KMP-06d`. Neither new module is canary-on:
`:core:ui`/`:core:mihon` resolve Compose UI's cinterop-backed iOS klibs, and `:core:settings` has a
commonMain `:library` edge. The original plan follows, for the record.

Into `:core:ui` (Screen, MVI glue, Mihon presentation) and `:core:designsystem` (Mihon material,
widgets), both canary-on from the first commit. Replace `String.format` as the Mihon files move
(`Preference.kt:74,100,124`, `EditTextPreferenceWidget.kt:41`). Move the form-factor type and
`LocalLayout` to commonMain with Android detection as the actual. `DevicePreferenceStore` imports
`AllLanguagesName`, `DubStatus`, `SearchQuality`, `TvType` from `:library` → `:core:storage` under rule 4.
`PreferenceStore` goes to `:core:storage` too. Repoint `:app` and `:desktopApp`, delete `:shared`.
Keep `com.mihon.*` packages.
**Exit:** the 7 Compose settings screens render; desktop builds; canary green except listed exemptions.

### Phase 6 — Media stack · ~4–6 weeks
Order: `:media:model` (contracts, canary on) → `:media:subtitle` (split) → `:media:cast` →
`:media:torrent` → `:media:download` (three layers) → `:media:video` → `:media:audio`.
Android-only engines require D3. No UI rewriting. Engine abilities are declared as
`EngineCapabilities`, never assumed.

### Phase 7 — Feature verticals, one per PR
Recipe:
1. `State`/`Action`/`Effect` + ViewModel in `:feature:x` commonMain (`cloudstream4` package),
   exposing `UiString`, never `@StringRes Int`. This puts Compose UI on the ViewModel's classpath and
   rules out tvOS resolution (rule 5 exception, §10 O3).
2. Screen composables per form factor in commonMain (I4, §4.4); reusable parts → `:core:designsystem`.
3. Glue Fragment in androidMain (`cloudstream4` package); navigation through `:core:ui` routes.
4. Repoint one node in `res/navigation/mobile_navigation.xml`. The old fragment stays in `:app` as
   the rollback lever.
5. Delete the old fragment and layouts one release later. Deleting `com.lagradost.cloudstream3`
   classes removes them from classes.jar: list them in the PR (javap tripwire output) for owner sign-off.

| # | Vertical | Lines | Notes |
|---|---|---|---|
| 0 | settings | 6,714 | 7 screens already Compose (reference) |
| 1 | search + quicksearch | 2,042 | `SearchViewModel` 0 lines using `R.string`; consumes `SearchResponse` (rule 4) |
| 2 | home | 2,902 | `HomeViewModel` 0 lines using `R.string` |
| 3 | library | 970 | `LibraryViewModel` 9 lines using `R.string` |
| 4 | downloads | 2,829 | UI only; consumes `DownloadProgress` and a picked-file abstraction |
| 5 | account + setup | 1,410 | `AppLock`, `AuthBrowser` interfaces; self-update behind a platform capability |
| 6 | extensions | — | repository/index/install state/verification in commonMain; `PathClassLoader` runtime in androidMain; iOS runtime open (§10 O2) |
| 7 | result | 7,184 | `ResultViewModel2` 91 lines using `R.string` (81 distinct keys); share and csredirect as an `Effect` carrying a string |
| 8 | player | from 16,497 total | UI only; engine in `:media:video`; `PlayerCommand`/`NowPlaying`/`PipState` from `:media:model` |

### Phase N — Retire the shell · ~2–3 weeks
Drop `viewBinding`, delete `res/layout` (145 files), replace the XML nav graph with the Navigation 3
**Compose Multiplatform** artifact (`org.jetbrains.androidx.navigation3:navigation3-ui`) using
`rememberNavBackStack(SavedStateConfiguration)` with polymorphically registered `NavKey`s
(reflection-based serialization is JVM-only), and replace `MainActivity` with a single Compose activity.

### Track L — `:library` iOS surgery (I1) · parallel, upstream-coordinated

| Item | Detail |
|---|---|
| Goal | `:library` commonMain compiles for iOS; `library/api/jvm/library.api` unchanged |
| Scope | 54 of 147 commonMain files import JVM-only packages; ~20 of the 8,982 lines in `library.api` name okhttp3, Rhino, UUID, Date, Jackson, jsoup or NiceHttp. NiceHttp's API, reached through `var app = Requests(...)` (`MainActivity.kt:30`), is the de facto extension API. |
| Techniques | JVM-typed top-level declarations → jvmCommonMain with `@file:JvmMultifileClass` + `@file:JvmName("<Original>Kt")` on every part; member types → `expect` + JVM `actual typealias` (`okhttp3.Interceptor`, `java.util.UUID`); internal extractors → a common facade (Ktor/Ksoup/kotlinx) |
| Must stay on JVM | inline `parseJson` needs both kotlinx and Jackson runtimes; `expect class WebViewResolver : Interceptor` keeps its JVM shape |
| Cheaper than it looks | `getRhinoContext` (`MainAPI.kt:848-852`) and `Episode.addDate(java.util.Date)` (`MainAPI.kt:2616-2620`) are already `@Deprecated(WARNING)` upstream |
| Proof per move | `abiValidation` dump unchanged + javap diff of classes.jar empty + on-device load of real `.cs3` extensions |
| Risks | expect/actual classes are Beta; a split without `@JvmMultifileClass` breaks extensions with every FQN intact; upstream owns the module |
| Unblocks | rule-4 exemptions; extension options (a), (a′), (a″) and (d) in §10 O2 |

---

## 8. Risks

| Risk | Impact | Mitigation |
|---|---|---|
| Merged module dropped from `makeJar`; package move; facade rename or split without `@JvmMultifileClass`; new constructor params on a public data class (e.g. `SitePlugin`) | **Critical**: extensions fail to load or link, some with every FQN intact | derived merge + sentinels; §3.2 table; `abiValidation`; javap tripwire; keep old constructor + `copy` overloads |
| First-party class lands in a merged (frozen-API) module | accidental permanent extension API | I2; two module kinds (§4); §6.4 check |
| String key renamed/deleted, or `<string>` → `<plurals>` | `NoSuchFieldError` in extensions (`R` fields link by name) | review rule; S1 leaves `res/` untouched |
| commonMain becomes Android-only **or JVM-only** | next target costs a rewrite | jvm canary + gated iOS canary; jvmCommonMain rule (§2.2) |
| "Keep iOS possible" used to justify an ABI change | every extension breaks | §2.4 rule |
| Module depends on `:library` from commonMain | iOS closes for that module until Track L | rule 4 exemption list that only shrinks |
| iOS canary green on Linux but Compose klibs need a Mac | false confidence | macOS runner for Compose modules (G4) |
| Strings generator decodes or falls back differently from aapt2/Android/CMP | wrong text on Compose screens | aapt2 as oracle; ported `ResTable_config` fallback; tests against the plugin's real output; golden decode; content guards (fail on PRs, report on master) |
| Weblate source flipped before translate-toolkit is fixed | 61 quoted values, `sv` blank, leading-`@` values silently change | S1: no flip until fixed upstream |
| `UiString` (S2) puts Compose UI (`components-resources` → `foundation` → `ui`) on every ViewModel's classpath | a tvOS app over shared Kotlin cannot reuse ViewModels | recorded as the rule 5 exception; `:core:strings` exports only runtime + resources; §10 O3 stays open |
| CMP plural crash (`apc`, `qt`, `arz`); Compose argument overloads used directly | crash; `%s` / `%.2f` rendered literally | plural guard + canary test; common formatter via `UiString`; review rule |
| AGP or CMP bump changes aapt2 output, the `.flat` format or runtime internals the tests reflect on | generator or string tests break | CMP version pinned in the test; fixtures + golden decode rerun on every bump |
| Ktor 3.6.0: OkHttp 5.3.2 → 5.5.0, okio → 3.18.2 at runtime | every loaded extension runs on the newer OkHttp (NiceHttp is compiled against 5.3.2) | accepted (I3); smoke-test real extensions in the catalog PR |
| Ktor 3.6.0 URL parsing (`ktor-http` is shared with extensions at runtime) | `updateUrl` / `Url.encodedPath` return percent-encoded paths; `Url(...)` throws on whitespace in the host where ~10 built-in extractors have no local catch | accepted (I3); `:library` tests pin the behaviour; extension-author note in the bump PR |
| Ktor client `close()` on the shared client | kills `app.baseClient`'s dispatcher and pool app-wide | never close (Phase 4) |
| Track L surgery | per-class ABI risk; large; Beta language feature; upstream conflict | per-move proof; small PRs; upstream coordination |
| Two preference systems diverge; big-bang Result/Player rewrite | settings split; unmergeable branch | Phase 4 `:core:storage` before verticals; one vertical per PR, old fragment retained |
| Deleting old `cloudstream3` classes | classes vanish from classes.jar | javap output + owner sign-off (Phase 7 step 5) |
| `minSdk 23` / `jvmTarget 11` | silent runtime crashes on TV sticks | pins for `jackson` 2.13.1, `rhino` 1.8.1, `conscrypt` 2.5.2 stay; Ktor ≥ 3.5.1 required (KTOR-9607 `SecureRandom.getInstanceStrong` crash below API 26 in 3.5.0) |

---

## 9. Dependencies

| Dependency | Status | Where |
|---|---|---|
| `io.ktor:ktor-client-core` 3.6.0 | **approved (I3)** | `:core:http` commonMain |
| `io.ktor:ktor-client-okhttp` 3.6.0 | **approved (I3)** | `:core:http` jvmCommonMain (Ktor publishes a jvm variant only) |
| `ktor` ref 3.5.0 → 3.6.0 | **approved (I3)** | also `:library`'s `ktor-http` |
| `io.ktor:ktor-client-darwin`, `coil-network-ktor3` | deferred until an iOS target exists | `appleMain` |
| `aapt2-proto` (explicit) | **not needed**: S-a reads the `.flat` table with a JDK-only wire reader | build-logic |
| skiko / `compose.desktop.currentOs` (test-only) | **not needed**: the jvmTest builds `ResourceEnvironment` headless | `:core:strings` jvmTest |
| `org.jetbrains.androidx.navigation3:navigation3-ui` | to be proposed at Phase N | `:app`, `:core:ui` |

**Ktor record (dependency-security.md; approved as I3).**
- *Why:* first-party networking (sync providers, repository fetch, subtitles, TorrServer control,
  download byte engine) must compile in commonMain; NiceHttp publishes a jvm variant only and OkHttp 5
  dropped multiplatform. *Rejected:* a hand-written transport per platform (~300–500 lines each);
  NiceHttp everywhere; Ktor's Android/CIO engines (lose the shared OkHttp DoH, cache, interceptors).
- *Size:* ~1.58 MB of new compressed jars measured at 3.5.2 (`ktor-client-core-jvm` 941,648 bytes);
  `:app` is not minified (`app/build.gradle.kts:150`); re-measure at 3.6.0 in the catalog PR.
- *Security:* Apache-2.0, JetBrains first-party (`io.ktor`). OSV (queried 2026-09-17) reports no
  advisories for the `io.ktor` client, http, utils, io, network and websockets artifacts at 3.5.0,
  3.5.2 **or 3.6.0** (published 2026-09-16); the result is a snapshot. 3.6.0 fixes KTOR-9834 (eager
  SLF4J init on Android startup, an ANR; 3.5.2 is affected) and KTOR-9773 (`NetworkOnMainThreadException`
  when cancelling from main). KTOR-9607 (API < 26 crash) was fixed in 3.5.1. Maven Central has no download counts.
- *Accepted runtime consequence:* OkHttp 5.5.0 and okio 3.18.2, shared with extensions.

---

## 10. Open decisions

None of these block an Android PR as long as the seams in §4.3 land first.

### O1. iOS distribution, given GPL-3.0 (`LICENSE`)
**Blocks:** whether GPL engines (mpv GPL build, TorrServer) are acceptable; whether torrent,
downloads and DRM links become per-platform feature flags.\
Facts: the FSF holds that GPL conflicts with App Store terms (VLC was removed in 2011). Guideline
5.2.3 targets torrent streaming and third-party media downloading. TestFlight/Ad Hoc fall under the
same agreement. EU/Japan notarization still forbids downloaded executable code. LGPL engines (MPVKit
default build, VLCKit LGPL-2.1) avoid GPL for the player whatever is chosen.

| Option | Pros | Cons | Cost | Extension ABI |
|---|---|---|---|---|
| App Store | reach, normal updates | needs relicensing (every contributor) and dropping TorrServer/GPL engine builds; 5.2.3 risk | very high | none |
| App Store via a GPLv3 §7 additional permission | smaller ask than relicensing; GPL stays for other channels | still needs contributor consent; 5.2.3 risk remains | high | none |
| EU/Japan alternative marketplace | GPL engines plausible | EU/JP only; eligibility criteria; still no downloaded code | low engineering | none |
| Sideload only | no review | re-signing, revocation, tiny audience | low | none |
| Undecided / no iOS release | zero cost | engine and extension choices stay open | none | none |

### O2. iOS extension strategy
**Blocks:** nothing now if the `ExtensionRuntime` seam lands first. (a), (a′), (a″) and (d) need Track L.\
Facts: Kotlin/Native is a closed world with no runtime class loading; the dex loader is
`PluginManager.kt:611`. Guideline 2.5.2 bans downloading code that changes functionality, but
**Guideline 4.7 explicitly permits "HTML5 and JavaScript mini apps … and plug-ins"** not embedded in
the binary, subject to 4.7.1–4.7.5 (filtering and reporting, no native API exposure without
permission, an index with universal links, age restriction): that is the sanctioned route for (a)
and (b). DPLA 3.3.1(B) wording was confirmed only from a 2022 filed copy. "In-app JavaScriptCore
has no JIT" is unverified.

| Option | Pros | Cons | Cost | Extension ABI |
|---|---|---|---|---|
| (a) Extensions also compiled to Kotlin/JS, run in JavaScriptCore behind a native HTTP/cookie/storage bridge | authors stay in Kotlin; `JSContext` exists on tvOS 9+ | needs Track L; jsoup/NiceHttp extensions do not recompile unchanged; performance needs a prototype; 4.7.2 may read the bridge as exposing native APIs | very high | none to classes.jar; repository JSON gains a JS artifact |
| (a′) Same, but an embedded interpreter (QuickJS or Hermes) instead of JavaScriptCore | engine under our control; runs on tvOS | binary size; same 4.7 obligations | very high | same as (a) |
| (a″) Kotlin/Wasm (WASI) extensions in an embedded Wasm interpreter (wasm3, WAMR) | no WebKit needed | unverified whether an interpreter counts as "interpreted code" under DPLA 3.3.1(B) | very high | same as (a) |
| (b) New versioned JS/TS contract (search, load, loadLinks) | small contract independent of `:library` | a parallel ecosystem; an Android JS host is its own dependency decision; precedent fragile | high | none |
| (c) Companion JVM host (desktop/NAS/Docker), iOS as thin client | existing cross-platform `BasePlugin` jars run unchanged; nothing executable on device; serves tvOS too | user-run host or a project-run server; the jvm `WebViewResolver` is a no-op (`WebViewResolver.jvm.kt:31-34`); needs a provider-result wire format | medium + medium | none. The recloudstream gradle plugin **already** writes `jarUrl`/`jarHash`/`jarFileSize` into `plugins.json` (`PluginEntry.kt`; `isCrossPlatform` at `CloudstreamExtension.kt:92`; `EnsureJarCompatibilityTask` rejects `android.`). Reading them into `SitePlugin` (a public data class in classes.jar) is a binary change unless the old constructor and `copy` overloads are kept. |
| (d) Curated providers compiled into the iOS binary | best-supported Kotlin/Native model; nothing downloaded | needs Track L; every fix is an app release; review sees every source | medium per provider | none |
| (e) No extensions on iOS | lowest cost and risk | weak product fit | lowest | none |

### O3. Apple TV scope
**Blocks:** a `tvosCanary` on non-UI modules (G5). Rule 5 keeps model, data, storage, HTTP and
media-contract modules on `compose-runtime` at most, which is cheap. It does **not** keep ViewModels
tvOS-resolvable: they expose `UiString` (S2), which depends on `components-resources`.\
Facts: Compose Multiplatform 1.12.0 `ui`, `foundation`, `material3`, `components-resources`, coil 3.3.0 and
`navigation3-ui` publish no tvOS variants; the runtime, coroutines, serialization and Ktor do. tvOS
also lacks `WKWebView`, LocalAuthentication and document pickers.

| Option | Pros | Cons | Cost | Extension ABI |
|---|---|---|---|---|
| iPhone and iPad only | reuses `:core:theme`/`:core:designsystem` | TV design unused on Apple hardware | lowest | none |
| SwiftUI/TVUIKit tvOS app over shared non-UI Kotlin | native focus engine; engines exist (VLCKit's TVVLCKit, MPVKit tvOS) | UI written twice; tokens mirrored in Swift; StateFlow bridged. **Cannot use `:feature:*` ViewModels as they are:** they expose `UiString`, and `components-resources` has no tvOS artifact. Needs a string-key mirror (resource key + args, resolved from a Swift string table) or its own state layer | high | none |
| Wait for CMP tvOS artifacts | one UI codebase | no announced timeline | unknown | none |

### O4. CI runner and minimum iOS version
**Blocks:** G4 becoming a required check; Phase 6 torrent and download choices on iOS.\
Facts: Kotlin/Native needs a Mac to build klibs whose dependencies use cinterop (Compose UI does).
Minimum iOS: **14** (CMP 1.12 floor) · **18** (upstream TorrServer XCFramework) · **26**
(`BGContinuedProcessingTaskRequest` background downloads).

| Option | Pros | Cons | Cost | Extension ABI |
|---|---|---|---|---|
| `macos-latest` for the whole canary job | proves Compose klibs honestly | slower, pricier minutes | low | none |
| Split: ubuntu for non-UI modules, macOS for Compose modules | cheaper for most modules | two jobs | low | none |
| Verify locally on the owner's Mac only; ubuntu job for non-UI modules | no macOS minutes | Compose modules unguarded in CI | none | none |
| Add a `js`/`wasmJs` compile canary (before or beside iOS) | rejects `java.*`, `synchronized`, `String.format` with no Apple klibs or macOS; aligns with upstream's `webMain` | not the declared target; misses Apple-specific gaps | low | none |

### O5. Extension-supplied Android UI and resources (`Plugin.load(Context)`, `resources`, `openSettings`)
**Blocks:** `:feature:extensions` settings on non-Android hosts only.

| Option | Pros | Cons | Cost | Extension ABI |
|---|---|---|---|---|
| Non-Android hosts accept only `BasePlugin` extensions (upstream `isCrossPlatform`) | exists upstream | `Plugin` extensions excluded; no settings UI | low | none |
| Additive declarative settings schema on `BasePlugin`, rendered by Mihon widgets | settings on every host | only adopting extensions; `:library` API design with upstream | medium | additive to `library.api` |
| No extension settings off Android | nothing to build | configurable providers unusable | none | none |
| Android shim on a JVM server (with O2 (c)) | runs `Plugin` extensions off-device | open-ended; UI must be remoted | high | none |

### O6. Playback of extension links on iOS (headers, interceptors, containers, DRM)
**Blocks:** the iOS `:media:video` sibling; shape of `StreamRequest` headers (already data).\
Facts: `CS3IPlayer` sets referer/headers on Media3 sources and applies
`getVideoInterceptor(extractorLink: ExtractorLink): Interceptor?` (`MainAPI.kt:702`) to video,
subtitle and audio sources. AVPlayer has **no public arbitrary-header API**
(`AVURLAssetHTTPHeaderFieldsKey` is private), but public `AVURLAssetHTTPUserAgentKey` (iOS 16+),
`AVURLAssetHTTPCookiesKey` and `AVAssetResourceLoaderDelegate` exist. AVPlayer does not play DASH.
Widevine/PlayReady links cannot play on iOS and must be filtered.

| Option | Pros | Cons | Cost | Extension ABI |
|---|---|---|---|---|
| AVPlayer + UA/cookie keys + resource-loader delegate | covers Cloudflare `cf_clearance` + UA-pinned providers; native PiP/AirPlay | no arbitrary headers; interceptors not honoured | medium | none |
| AVPlayer + in-process localhost proxy (header injection, HLS rewrite, DASH→HLS) | arbitrary headers | `NSAllowsLocalNetworking`; still no interceptors | medium-high | none |
| libmpv via MPVKit | headers, referrer, external audio/subs, libass, broad containers | README: "will not be maintained too frequently"; PiP bridge; LGPL vs GPL build | medium | none |
| VLCKit | VideoLAN-maintained, LGPL-2.1 | only referrer + UA; CocoaPods (trunk read-only 2026-12-02) | medium | none |
| Hybrid per link from `EngineCapabilities` | best engine per link; filters DRM/torrent cleanly | two engines | high | none |
| Additive declarative hook in `:library`, e.g. `getVideoRequestHeaders(extractorLink: ExtractorLink, url: String): Map<String, String>?` beside `getVideoInterceptor` | any engine honours it as data | extensions must adopt; upstream coordination; never change `getVideoInterceptor`'s return type | low-medium | additive to `library.api` |

### O7. WebView request capture (`WebViewResolver`) and Cloudflare solving
**Blocks:** which providers an iOS build can offer.\
Facts: Android intercepts every subresource in `shouldInterceptRequest`; `expect class WebViewResolver`
extends `okhttp3.Interceptor` (`library/.../network/WebViewResolver.kt:17`). `WKWebView` cannot
intercept http(s) subresources and does not exist on tvOS. `CloudflareKiller` reads
`android.webkit.CookieManager`.

| Option | Pros | Cons | Cost | Extension ABI |
|---|---|---|---|---|
| iOS-only Cloudflare solver (hidden `WKWebView`, copy `cf_clearance`, pin UA) behind a common `ChallengeSolver` | covers the most common WebView use | not on tvOS; main-thread hosting unverified; TLS fingerprint may still fail | medium | none |
| Best-effort capture via injected `WKUserScript` (fetch/XHR hooks) | recovers many URLs | no headers/bodies; misses element and CSS loads | medium | additive only |
| Declare capture Android-only; iOS stub like the jvm one + additive capability query | trivial, honest | WebView-dependent providers unavailable | trivial-small | none or additive |
| Off-device headless browser (with O2 (c)) | works for tvOS | infrastructure + legal exposure | high | none |

### O8. Downloads that resolve links with extension code and fetch HLS in the background
**Blocks:** nothing on Android; the `:media:download` three-layer split is built either way.\
Facts: links resolve inside the foreground service; HLS segments are fetched, decrypted and
concatenated in-process. iOS has no foreground services; a background `URLSession` runs out of
process with no app code.

| Option | Pros | Cons | Cost | Extension ABI |
|---|---|---|---|---|
| Resolve in foreground, hand direct files to a background `URLSession` | survives suspension | HLS unsolved | medium | none |
| Common Kotlin byte engine on Ktor under `BGContinuedProcessingTaskRequest` | one output format incl. HLS | iOS 26+; user-initiated; can expire | medium-high | none if `DownloadManager`/`VideoDownloadManager` FQNs stay |
| `AVAssetDownloadURLSession` for HLS | official offline HLS | headers unofficial; asset package output; no DASH | medium | none |
| Foreground-only or no downloads | simplest; removes 5.2.3 exposure | poor UX or lost feature | low | none |

### O9. Self-update and APK installer
**Blocks:** the settings capability object's iOS entries (depends on O1).\
Facts: `InAppUpdater` + `PackageInstaller` cannot exist on iOS (2.5.2).

| Option | Pros | Cons | Cost | Extension ABI |
|---|---|---|---|---|
| "New version available" link-out | informs sideload/marketplace users | pointless on the App Store | small | none |
| No update UI on iOS | nothing to build | sideloaded users never told | none | none |
| Channel-specific feed (e.g. an AltStore source) behind a platform capability | real one-tap update where supported | depends on O1 | small | none |

### O10. `Event` / `EmptyEvent` on iOS
**Blocks:** any commonMain code that wants `afterPluginsLoadedEvent`; B8's inversion is unaffected.\
Facts: `Event.kt` uses `synchronized`; `EmptyEvent` exposes `Runnable`; `Event` is the type of
`MainActivity.afterPluginsLoadedEvent` and a sentinel.

| Option | Pros | Cons | Cost | Extension ABI |
|---|---|---|---|---|
| Leave JVM-only; new common code uses a Flow-based event | zero risk | two event types while both platforms live | none now | none |
| `expect class Event` in commonMain; today's code as the jvmCommonMain actual; iOS actual on atomicfu | one name everywhere | byte identity (incl. `$stable`) must be re-proven; expect/actual classes are Beta | moderate | designed to be none; unverified until javap + `abiValidation` |
| Rewrite in commonMain with atomicfu and function types | simplest code | `EmptyEvent.plusAssign(Runnable)` changes | low code, high risk | **breaks extensions**; only inside a versioned break (e.g. with `UiText` removal) |

### 10.11 Later ABI questions (raise when the phase reaches them)

| Question | Raised by | Options |
|---|---|---|
| Sync/subtitle API shape: R-derived enums (`SyncWatchType`, `ListSorting`) and `@WorkerThread` in `SyncAPI`/`SubtitleAPI` ABIs | moving the Auth/Sync/Subtitle stack | redesign in a versioned break, or keep the stack in `:app` |
| Resource ids in provider ABIs (`getIcon()` from `R.drawable`, `R.string` in `LocalList`/`AuthRepo`) | same | `:app`-supplied icon/string indirection, or keep those providers in `:app` |
| `ExtractorUri.uri: android.net.Uri` in `VideoGenerator.generateLinks` | Phase 6 | versioned break to a `String` URI, or keep the generator hierarchy Android-side (after D3) |
| Nested models (`DataStoreHelper$*`, `BackupUtils$BackupVars`/`$BackupFile`) | Phase 4 | keep nested, or a versioned break |
| `:shared`'s own composeResources strings (`ok` is "Ok" there, "OK" in `:app`) | Phase 5 | consolidate into `:core:strings`; one surface changes visibly |
