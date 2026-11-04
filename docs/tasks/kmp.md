# KMP — module migration

Carving `:app` into modules, restructuring `:shared`, and keeping iOS possible.

The plan is [`docs/plans/kmp-migration.md`](../plans/kmp-migration.md). This file is the work list;
the plan is the reasoning.

**The constraint behind all of it:** `app/build/classes.jar` is a binary contract that third-party
extensions compile against. It is guarded by `app/api/classes-jar.api` and `:app:checkExtensionApi`.
"No android import" does not mean movable — see `KMP-09` for the trap that caught two attempts.

---

## Merged

| ID | What | PR |
|---|---|---|
| KMP-M1 | `:core:common`, `:core:network`, `:core:data`, `:core:model` (B1–B3) | #4–#6, #14, #16, #19 |
| KMP-M2 | `:core:strings` generated from Android res | #17, #18 |
| KMP-M3 | `:core:http` over `app.baseClient`; ktor 3.6.0 | #10, #11 |
| KMP-M4 | `:core:storage` ports, Android actuals, persistent JVM stores | #20, #22 |
| KMP-M5 | `UiString` + formatter + plural guard | #21, #23 |

---

## KMP-01 — B4: `DdosGuardKiller` → `:core:network`

**Status:** done · #30 · **Size:** one PR

Merged: `DdosGuardKiller` moved to `:core:network`. Added 1 sentinel to
`cloudstream.extension-api-jar.gradle.kts`; `app/api/classes-jar.api` md5 unchanged before and
after (`48709d3ac28143a2fe251ca28bcf14c1`). Local run, not CI (see `INF-01`). The checklist below is
the original plan, left as written; ticked items are the ones #30's description evidences.

Body-only change first: a private header merge replaces the call to `:app`'s
`RequestsHelperKt.getHeaders`. Adds `:library`, coroutines and `annotation` to `:core:network`.

- [ ] Run the `$stable` scan before moving (see `KMP-09`)
- [ ] Replace the `getHeaders` call with a private merge
- [x] Move, verify javap identical, `checkExtensionApi` dump unchanged (md5 above)

---

## KMP-02 — B5: `SSLTrustManager`, `DownloaderTestImpl` → `:core:network`

**Status:** closed, not moving — security review done 2026-10-15. **One finding for the owner (below) that is not about the move.**

> **Superseded in part, 2026-10-15.** The finding below became `BUG-10`, and `BUG-10` is now **fixed**:
> the `if (ignoreSSL)` block, the field and their imports are deleted from `CS3IPlayer.kt`, so nothing
> installs a process-wide TLS default any more. Everything in sections 1–2 is the state of the tree
> *before* that fix and is kept as the record of the review — read the present-tense sentences, in
> particular "the trust-all runs in release today" in section 3, as describing the pre-fix tree.
> `SSLTrustManager` itself is unchanged apart from a KDoc, and the conclusion not to move it stands.

The entry's premise was *"is it still only reachable behind the same debug/dev gate as today"*.
**There is no such gate.** Everything below is from reading the tree at `claude/wt-walk`; nothing
was built or run.

### 1. What `SSLTrustManager` is

`app/src/main/java/com/lagradost/cloudstream3/ui/player/SSLTrustManager.kt:6-16` — a public,
final-by-default Kotlin `class` (not an `object`) implementing `X509TrustManager`. Both
`checkClientTrusted` (`:7-8`) and `checkServerTrusted` (`:10-11`) are empty bodies, and
`getAcceptedIssuers` (`:13-15`) returns an empty array: it accepts **every certificate chain**,
expired, self-signed or forged. It has no hostname logic of its own.

The hostname half is at the one call site, `CS3IPlayer.kt:1893-1902`: inside `if (ignoreSSL)` it
builds `SSLContext.getInstance("TLS")`, `init`s it with `SSLTrustManager()` (`:1896`), then installs
`HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }` (`:1898-1900`) and
`HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)` (`:1901`). So the pair
disables **both** certificate and hostname verification, and it does so through **process-wide
static defaults**, not on a per-connection object — once set, nothing in the process unsets it.

`grep -rnE "TrustManager|HostnameVerifier|setDefaultSSLSocketFactory|sslSocketFactory"` over every
`*.kt/*.kts/*.java` outside `build/` finds only those two files. There is no other `X509TrustManager`.

### 2. Who reaches it today, and what gates it

One caller: `CS3IPlayer.kt:1896`, inside `loadOnlinePlayer` (`:1801`), which is reached from `:310`,
`:1791` and `:1984` — i.e. every "play this link" path.

The gate is `ignoreSSL`, declared at **`CS3IPlayer.kt:161`: `private var ignoreSSL: Boolean = true`**.
`grep -rn "ignoreSSL"` across the repo shows exactly two reads/writes in that file — the declaration
(`:161`) and the test (`:1893`). **It is never assigned.** It is not a developer setting, not a
hidden preference, not `BuildConfig.DEBUG`, not a flavour check: `grep BuildConfig` finds nothing in
`CS3IPlayer.kt`, `SSLTrustManager.kt` or `DownloaderTestImpl.kt`. So the branch is taken **on every
online playback in every variant**. `app/build.gradle.kts` makes no difference: `release` is
`isDebuggable = false, isMinifyEnabled = false` (`:148-151`), so the class is neither compiled out
nor shrunk; the `stable`/`prerelease` flavours (`:168-176`) are not consulted by this code.

Scope of the effect, so it is not overstated: the player's own HTTP sources are OkHttp
(`CS3IPlayer.kt:748, 802, 817`, on `app.baseClient` or a derived client), and OkHttp does not read
`HttpsURLConnection`'s static defaults. What does read them is any `HttpsURLConnection` in the
process. Repo code that opens one directly: **none** (`grep openConnection|HttpsURLConnection` in
`app/`, `library/`, `core/` finds only the `CS3IPlayer.kt` setters); the one indirect user in this
file is `createOfflineSource`'s `DefaultHttpDataSource` (`:836`). I did not audit third-party libraries
for `HttpsURLConnection` use, so "nothing else is affected" is **not** claimed.

Related, separate path, noted only so it is not confused with this one: `insecureApp`
(`library/.../MainActivity.kt:36-39`, `@UnsafeSSL`, a WARNING-level opt-in) is built with
`ignoreAllSSLErrors()` from NiceHttp (`RequestsHelper.kt:45-47`, set up at `MainActivity.kt:1186`
and `SettingsGeneralScreen.kt:223`). That is a different, opt-in, annotated mechanism and does not
use `SSLTrustManager`.

### 3. What the move would change

- **Is `:core:network` in the published jar? Yes — already.** `cloudstream.extension-api-jar.gradle.kts`
  merges, whole, every project on `:app`'s `prereleaseDebug` classpaths that has a
  `src/<set>/{kotlin,java}/com/lagradost/cloudstream3` directory (`extensionApiModules`). `:core:network`
  has one (`core/network/src/jvmCommonMain/kotlin/com/lagradost/cloudstream3/network/`) and carries
  two sentinels, `DohProvidersKt` and `DdosGuardKiller`. Its KDoc says the same
  (`core/network/build.gradle.kts`, "FQN freeze: these classes are published extension API").
- **But this does not make `SSLTrustManager` more exposed, because it is already published.**
  `app/api/classes-jar.api:28106-28112` lists
  `public final class com/lagradost/cloudstream3/ui/player/SSLTrustManager : … javax/net/ssl/X509TrustManager`
  with a public `<init>`, and `:680-693` lists `DownloaderTestImpl` and its `Companion`.
  `:app`'s own classes are in `classes.jar`; every public class under `com/lagradost/` is in the
  dump. An extension can already compile `SSLTrustManager()` today. The move is FQN-frozen (a rename
  or a narrowing is a removal from the dump and can break a published extension), so the jar's
  contents after the move are the same line for line, `$stable` kept via `cloudstream.kmp.extension-api`.
  **The premise "becomes a documented, compile-against-able API" is false: it is one already.**
  (Whether a loaded plugin can *call* it at runtime depends on the plugin classloader, which I did not
  trace; compile-time reach is what the dump proves.)
- **Visibility in bytecode:** unchanged, `public` before and after — the class is a plain public
  class, not `internal`. Making it `internal` would not help: Kotlin `internal` is public in bytecode,
  and a top-level `internal` is not even mangled (`INF-07`, `docs/tasks/infrastructure.md:181`).
  The only real narrowing is removal, which is the ABI break just described.
- **Does the gate survive?** There is nothing to carry. The gate is a runtime field in `CS3IPlayer`
  that is always `true`, and `CS3IPlayer` stays in `:app` either way. `:core:network` has no
  `BuildConfig` of its own that anything could consult (the KMP library plugin generates none —
  `makeJar` fails if one turns up in an android-only module), and a `BuildConfig.DEBUG` check would
  be the wrong gate anyway: the trust-all runs in release today.
- **New reach from the move:** `settings.gradle.kts` / `grep core:network` shows the only dependent is
  `:app` (`app/build.gradle.kts:302`). No other module, `:desktopApp` included, can reach it. So the
  move would add **no caller and no consumer**: it relocates the file and nothing else.

### 4. `DownloaderTestImpl`

`app/src/main/java/com/lagradost/cloudstream3/DownloaderTestImpl.kt`. Not a security concern in
itself: it wraps a plain `OkHttpClient.Builder` with a 30 s read timeout (`:14`), does no TLS
customisation, and turns HTTP 429 into `ReCaptchaException` (`:39-41`). It calls no
`SSLTrustManager`. One caller: `CommonActivity.kt:252`, `NewPipe.init(DownloaderTestImpl.getInstance())`,
always via the no-argument default builder, so it is **not** on `app.baseClient` (no DoH, no cache).

The entry's claim holds: it imports `org.schabi.newpipe.extractor.*` (`:6-9`), and
`newpipeextractor = "v0.26.3"` is already in `gradle/libs.versions.toml:42,134` and used by `:app`
(`app/build.gradle.kts:261`). Moving it adds `newpipeextractor` to `:core:network`'s
`jvmCommonMain` — a catalog entry, not a new dependency, but it puts `Downloader` in the public
signature of an extension-API module, so it would need `api`, widening what that module exposes.
Already public in the dump (`classes-jar.api:680-693`), so no new exposure, same as above.

### 5. Recommendation: do not move — close `KMP-02`

Both classes have exactly one caller, both in `:app`; no module needs them. The move shrinks `:app`
by two files and buys nothing else, while it cannot make `SSLTrustManager` any safer (the FQN is
frozen) and would place a trust-all in the module whose documented purpose is OkHttp plumbing
for extensions, beside `DdosGuardKiller`. `DownloaderTestImpl` is harmless but would need a new
`api` edge for no consumer. The entry marked itself *Optional*; the cost is real and the benefit is
zero. If a second module ever needs a NewPipe `Downloader`, reopen `DownloaderTestImpl` alone — it
has no security objection.

- [x] Security review: who reaches it after the move, and is it behind the debug/dev gate — it is
      **not** behind any gate today; the move changes neither its reach nor its exposure.
- [x] Move: not done, by design.

### For the owner — not part of this task, not changed here

**Playback disables TLS verification for every link, in every build.** `CS3IPlayer.kt:161` defaults
`ignoreSSL = true` and nothing ever sets it false, so `:1893-1902` runs on every `loadOnlinePlayer`
and installs a trust-all socket factory and an always-true hostname verifier as the **process-wide**
`HttpsURLConnection` defaults. Whether that is intended (some streams do have bad certificates; the
field is a `var`, which suggests a setting was planned) is the one thing only the owner can decide.
The options are: keep it as a deliberate, documented choice; make `ignoreSSL` follow a setting
defaulting to `false`; or drop the global-default install and apply trust-all only to the OkHttp
client for the one link that needs it. Each changes player behaviour and therefore needs the owner's
call under `no-unsolicited-changes`. It should get its own `BUG-` or `DEC-` entry; this file does not
file it.

---

## KMP-03 — B6: `TestingUtils` (+8 nested) → `:core:model`

**Status:** done · #31 · **Size:** one PR

Merged: `TestingUtils` moved to `:core:model` as a pure rename — 0 lines changed, identical
sha256 on both sides. Added **9 sentinels** to `cloudstream.extension-api-jar.gradle.kts`: the
outer class plus its 8 declared nested classes (13 lines, 4 of them comment). A tenth binary name,
`TestingUtils$Logger$Message$WhenMappings`, is in the dump but deliberately **not** sentinelled —
it is a compiler-synthesised switch map for `when` over an enum, not a name an extension can
reference. `app/api/classes-jar.api` md5 unchanged (`48709d3a`) before and after `makeJar`.
Local run, not CI (see `INF-01`).

Needs `:library` and coroutines, so **not** `:core:common` (dependency rule 2). Straightforward;
it was originally bundled with B2/B3 and did not ship with them.

---

## KMP-04 — B7: `fcast/Packets.kt` → `:core:common`

**Status:** done 2026-10-15 — **split, not moved whole**, and the split answers `DEC-07`

The file is split. Eight classes — `Opcode` and the seven non-serializable message classes — moved to
`:core:common` **`commonMain`**. `PlayMessage` **stays in `:app`**.

### Why the whole file could not move

`PlayMessage` is `@Serializable`, so the compiler emits **`write$Self$app`**, and that `$app` is
`:app`'s Kotlin module name. It is published — `app/api/classes-jar.api:5293` lists it, and
`cloudstream.extension-api-jar`'s own KDoc names `write$Self$app` as something the dump deliberately
guards. Moving the class renames a member third-party extensions link against, which is the
`DEC-08`/`KMP-05` kind of accepted ABI delta, not a file move. Measured per class — it is the **only**
one of the nineteen `actions/temp/fcast` classes in the dump with a module-suffixed member.

### Why this is better than the planned whole-file move

The plan's default was jvmCommonMain *because* of `PlayMessage`'s Jackson use, and `DEC-07` priced
that at "`:core:common` gains the serialization plugin, kotlinx-serialization and Jackson
annotations". Leaving `PlayMessage` behind means **`:core:common` gains no dependencies at all** — it
is still dependency-free — and the eight classes land in `commonMain` rather than `jvmCommonMain`, so
they are iOS-ready. The `write$Self$app` constraint forced the better placement.

### Verified

- `:app:checkExtensionApi` passes and `:app:dumpExtensionApi` regenerates the dump
  **byte-identically** — zero ABI change.
- **`$stable` kept on all seven moved data classes**, read with `javap` from the merged
  `app/build/classes.jar` (`KMP-09`'s trap): 1 each. `Opcode` has none, as before — enums do not get
  one. `PlayMessage` is untouched and still carries `write$Self$app`.
- The classes genuinely changed module: `:app`'s `prereleaseDebug` output holds only
  `PlayMessage.class`; `:core:common`'s android and jvm outputs hold `Opcode` and the message classes.
- Eight sentinels added to `cloudstream.extension-api-jar` in the same change, as that plugin requires.
- Four gates: `abi=0 check=0 extapi=0 canary=0`.

Only `Opcode` has in-repo users (`FcastSession.kt:35,38`, `FcastAction.kt:56`). The seven message
classes have **no callers anywhere in the repo** — they exist purely as published FCast protocol
surface, which is why the sentinels matter more than usual here.

---

## KMP-05 — B8: `APIRepository` → `:core:data`

**Status:** done 2026-10-15, pending the sign-off `DEC-08` asks for · **the measured ABI delta is one
synthetic accessor, and it is not the one `DEC-08` named**

`APIRepository` is in `core/data/src/jvmCommonMain`. The event subscription is inverted:
`:app`'s `CloudStreamApp.onCreate` subscribes `APIRepository.Companion::onPluginsLoaded` once.

### The exact delta, which is what `DEC-08` asked to see

```
- public static final synthetic fun access$afterPluginsLoaded (Lcom/lagradost/cloudstream3/ui/APIRepository;Z)V
+ public final fun onPluginsLoaded (Z)V
```

**One removal, one addition.** Nothing else in `app/api/classes-jar.api` moved.

**`DEC-08` predicted the wrong entry.** It said the move "removes
`access$getAfterPluginsLoadedEvent$cp` from the published extension jar". **That entry survives** —
measured, still present at `MainActivity`'s line in the dump — because
`MainActivity.Companion.getAfterPluginsLoadedEvent()` still reads the outer class's static backing
field, and nine other `:app` files still use the event. The move never touched it.

**Why the entry that does go is benign.** `access$afterPluginsLoaded` is a compiler-generated
accessor that existed for one reason: so the class's own `init` block could take a callable reference
to a **private instance method**. Kotlin code cannot reference an `access$…` synthetic at all — it is
not in the Kotlin-visible surface — so only hand-written JVM bytecode or reflection could have called
it, and doing so would have been invoking a private method through the back door. It is an artefact of
an implementation detail, not API anyone could depend on deliberately.

That is a much weaker deletion than the entry anticipated. The sign-off question is still the owner's,
but it is now about this one line.

### Placement: `jvmCommonMain`, not `commonMain` — and why that matters

`APIRepository` needs `:library` (`MainAPI`, `Resource`, `safeApiCall`, `logError`, `atomicListOf` are
all there). Putting it in `commonMain` would add `:library` to `:core:data`'s commonMain, and plan
§4.1 rule 4 then forces the module into `IOS_CANARY_EXEMPT` — a list `cloudstream.kmp.library`'s KDoc
says **"may only shrink"**. It would have dropped the canary from 12 modules to 11, weeks after
`KMP-10` got it to 12/12 for the first time.

So `:library` and coroutines are declared on **`jvmCommonMain`**, exactly as `:core:network` does for
`DdosGuardKiller`. `:core:data`'s commonMain keeps `DataStore` / `DataStoreHelper` and stays pure, the
module keeps its iOS targets, and **the canary stays 12/12**. `DEC-08` and this entry both missed this
cost; it is the part that actually constrained the design.

### A behaviour change, deliberately

`afterPluginsLoaded` was a `private fun` that **every instance** subscribed from its own `init`, and
nothing ever unsubscribed. `cache` is a companion property, so all those listeners cleared the *same*
list. `SearchViewModel` builds one `APIRepository` per installed provider and **rebuilds the whole
list** on reload (`SearchViewModel.kt:52,71`), so the observer set grew without bound for the life of
the process. `Event` holds a `Set`, but bound references with different receivers are distinct objects,
so they did not collapse.

It is now one subscription for the process. That is both what the move requires and a fix for an
unbounded listener leak — called out here because it is a behaviour change, not just a move.

### Verified

- Dump delta is exactly the two lines above; `:app:checkExtensionApi` passes against it.
- `:core:data:updateKotlinAbi`: 49 insertions, **0 removals**, generated on Temurin 17 (`INF-07`).
- Five sentinels added to `cloudstream.extension-api-jar` — all five declared `APIRepository` binary
  names, matching the `AbstractSubtitleEntities` (3/3) and Auth DTO (4/4) precedent.
- Four gates, and the canary is the load-bearing one here: it proves the module kept its iOS targets.

---

## KMP-06 — Restructure `:shared`

**Status:** done · #41, #42, #45, #46 · order was 06c → 06b → 06d. `:shared` no longer exists.
`DEC-09` (`BaseComposeFragment`) was never on the critical path: that class is `:app`'s.

The plan's "split `:shared` into `:core:ui` + `:core:designsystem`" is **stale**.
`:core:designsystem` already exists with the redesign system; merging the vendored Mihon tree into
it would collide on the name `CloudStreamTheme` — `:shared`'s installs `MaterialTheme`,
`:core:theme`'s deliberately does not.

**Measured facts this rests on:**

- 47 Kotlin files. Consumers: `:app` and `:desktopApp` only. `:core:theme`, `:core:designsystem`,
  `:core:screenshot` and `:library` reference it **zero** times in either direction.
- 83 import lines, 36 distinct symbols, 19 files. Top: `SearchableSettings` ×13, `Preference` ×7.
- All seven Compose settings screens are live nav-graph nodes
  (`app/src/main/res/navigation/mobile_navigation.xml`). **This module ships to users.**
- `:desktopApp` is one 60-line `Main.kt` rendering "Hello, World!", two buttons and a switch.
- The Mihon tree carries **seven off-4dp-step `Dp` literals** and ten hand-exported vectors, which
  would be instant violations of `dimension-scale.md` and `icons.md` in code nobody here wrote.

**Three `:shared` FQNs are frozen into the published extension ABI** — so package names must not
change through any move, and `:app:checkExtensionApi` enforces it:

| FQN | occurrences in `app/api/classes-jar.api` |
|---|---|
| `com/mihon/presentation/settings/SearchableSettings` | 17 |
| `com/lagradost/cloudstream4/compose/Screen` | 3 |
| `com/lagradost/cloudstream4/R` | 1 |

### KMP-06a — attribution, before anything moves

**Status:** done · decided 2026-09-18 (see `DEC-03`)

- [x] One `NOTICE` at the repo root plus a rewritten module README, naming Mihon, the URL,
      Apache-2.0, both copyright lines and that the files are modified
- [x] Per-file upstream mapping for all 29 files, measured by fetching each upstream file and
      diffing it
- [x] Not 29 file headers — one notice, as decided

**What the measurement changed.** All 29 files were fetched from Mihon `main` on 2026-10-07 and
diffed. Two claims in the original entry were wrong:

- *"`AppBar.kt` differs by 157 lines of 405"* — 157 is the **sum of both directions**. Ours is 405
  lines against upstream's 449: 100 upstream-only, 57 ours-only.
- *"All 16 vendored files checked differ"* — true of all 29, but **three differ in the `package`
  line and nothing else**: `material/Constants.kt`, `material/Pill.kt`, `material/Typography.kt`.
  Verified by diff: one line out, one line in. The NOTICE says so rather than implying more.

`material/SettingsItems.kt` is the honest example of real porting: `ImageVector` → Compose
`Painter`, Moko `StringResource` → Compose Resources, `Preference<T>` → `PreferenceData<T>`; 30
upstream lines removed, 46 added.

Three files are not same-name copies, which a reviewer would otherwise waste time on:
`PreferenceData.kt` ← upstream `Preference.kt` (rename), `presentation/Navigation.kt` ← upstream
`Navigator.kt` (one declaration extracted), `settings/Search.kt` ← upstream
`SettingsSearchScreen.kt` (substantial rework).

**Also established:** Mihon's `LICENSE` is the bare Apache-2.0 text naming no holder, exactly as our
vendored copy is — so that copy is faithful, not truncated. The copyright lines come from Mihon's
README: *Copyright © 2015 Javier Tomás* and *Copyright © 2024 Mihon Open Source Project*. Mihon
ships **no NOTICE file**, so §4(d) does not apply; and its sources carry **no per-file licence
headers** in any file sampled.

**Still unrecoverable:** the upstream commit the tree was taken from. It was not recorded when
vendored, and the comparison above is against a moving `main`. The module README says so and asks
whoever next updates a file to record the commit.

### The order changed: 06c before 06b

The original sizing (29 files, consumed from 23 files via 30 symbols) measured only **who imports
the Mihon tree**. Measured on 2026-10-07, the Mihon tree also imports first-party `:shared` code, in
**12 of its 29 files** — 8 import `compose`/`theme` symbols, 10 import `Res`, overlapping — so moving
it out first would have created a module cycle:

| Mihon file(s) | first-party symbol |
|---|---|
| `SearchableSettings.kt` | `compose.Screen` — its supertype, and both FQNs are in `app/api/classes-jar.api` |
| `ListPreferenceWidget.kt`, `MultiSelectListPreferenceWidget.kt` | `SingleSelectDialog`, `MultiSelectDialog` |
| `BasePreferenceWidget.kt`, `material/SettingsItems.kt` | `focusOutline`, `whiteOutline` |
| 4 widget/item previews | `theme.CloudStreamPreviewTheme` — and with it all of `theme/**`, Product Sans included |
| 10 files | `:shared`'s `Res`: 8 drawables and 6 strings used, plus an unused `lorem_title` import |

The other direction is 5 files (4 by `import com.mihon`, plus `Dialog.kt`, whose import came out in 06c): `ComposeFragment.kt` (`LocalBackPress`, `collectAsState`),
`DevicePreferenceStore` ×3 (`PreferenceStore` and its stores) and `Dialog.kt` (one
`MaterialTheme.padding.medium`). `Screen` alone forces the first-party layer below the Mihon tree,
so `:core:ui` comes first (owner's choice, 2026-10-07).

### KMP-06c — `:core:ui` for the first-party Compose layer

**Status:** done · #41 (merged at `d646470`, before this verification record was written) · built
and run locally by the owner on 2026-10-07 at master `0bc8709`. All checks closed — see below.

Moved by `git mv`, every file's contents unchanged except `Dialog.kt`'s (below):

- `compose/`: `Colors`, `Dialog`, `Modifiers`, `Screen`, `Viewmodel`; `theme/**` in all three source
  sets; the whole `composeResources` tree (strings, 9 vector drawables, `default_icon.png`, the 12 Product
  Sans fonts, unchanged — `DEC-02` is unaffected by where they sit).
- `Dialog.kt`: `MaterialTheme.padding.medium` (Mihon's `Padding`, `16.dp`) became a private
  `ActionDialogIconTitleSpacing = 16.dp`, and the `com.mihon.material.padding` import went. Same value;
  it is the only cycle edge that needed an edit.
- **Stayed in `:shared`:** `ComposeFragment.kt` and `DevicePreferenceStore` ×3, which import Mihon;
  and `compose/Layout.kt`, because `ComposeFragment` calls its `internal` `layoutToFlag`, and moving
  it would have meant widening a visibility. 06d places them.
- `Viewmodel.kt` moved, as `.claude/rules/mvi-viewmodel.md` requires.
- `DEC-09` (`BaseComposeFragment`) is untouched — that class is in `:app`, not `:shared`.

Two names move with it and must not change:

- **`Res`** at `com.lagradost.cloudstream4.generated.resources`, `publicResClass`. `:shared` now has
  `generateResClass = never`; its Mihon files import `:core:ui`'s `Res` unchanged.
- **Android namespace `com.lagradost.cloudstream4`**, whose empty `R` is in `classes-jar.api`. It
  moved to `:core:ui` with `androidResources`; `:shared` became `com.lagradost.cloudstream4.shared`
  without them. Otherwise retiring `:shared` would later drop that `R` from the jar.

`:shared` depends on `:core:ui` as `api` — `SearchableSettings : Screen` is public API — so `:app` and
`:desktopApp` needed no build-file change.

**Verification** — local runs by the owner, 2026-10-07, Temurin 17, `TZ=UTC`; not CI (`DEC-01`):

- [x] `checkKotlinAbi` → exit 0.
- [x] `assemblePrereleaseDebug lint check -Pcloudstream.strings.guards=fail` → exit 0.
- [x] `:app:checkExtensionApi` → first **exit 1, pre-existing and not this change**: 0 removed, 0 changed,
      and the 1 addition is `RunTimeSecondsKt`, from #38 (`INF-07`). The thing this PR risked — the
      `com/lagradost/cloudstream4/R` line — did **not** move: `:core:ui`'s `R` took `:shared`'s place
      exactly. With `INF-07`'s block corrected, the owner measured exit 0.
- [x] `$stable` scan, 2026-10-14, local (Temurin 17, `TZ=UTC`), baseline `f29ff63a` (master before #41)
      vs `0bc8709` + #43. Classes compiled from the 13 moved `.kt` (`git mv`'d in `f2e0e435`, not
      `bdd98926`): 30 android, 29 jvm. `public static final int $stable` on **9 → 9** per target,
      0 dropped (`Colors`, `AppFont`, `CloudStreamColorScheme`, `CloudStreamPalette`, `DeviceTheme`,
      `DebounceQuery`, `SingleActiveQuery`, `DefaultStateContainer`, `DefaultEffectContainer`).
      Counted twice: grep over `javap -p -c` text, and a direct class-file parser (field flags +
      `SourceFile`). `javap -p -c` of all 338 classes on each side (`:shared`/`:core:ui` android
      and jvm, plus every `:app` `prereleaseDebug` and `:desktopApp` class referencing
      `cloudstream4/`): 333 identical, 5 not. 3,362 `getstatic` targets per side, 94 of them on a
      `$stable` (none owned by a moved class), no owner changed; the only `getstatic` difference
      is `DialogKt`'s intended edit (`MaterialTheme.padding.medium` → `ActionDialogIconTitleSpacing`). The 5: `DialogKt` and
      `ComposableSingletons$DialogKt` on each target — that edit, plus one accessor renamed by
      module-name mangling, `getLambda$-338782920$CloudStream_shared` → `…$CloudStream_core_ui`,
      called only by `DialogKt` and absent from `classes-jar.api` — and `:app`'s
      `SettingsFragmentScreen`, which differs only by an inlined build timestamp.
      `com/lagradost/cloudstream4/R` is still in `app/build/classes.jar`.
- [x] Runtime: all 23 resources in the APK under one `com.lagradost.cloudstream4.generated.resources`
      package (12 Product Sans faces, 10 drawables, strings), no `Res` collision; Settings → General,
      Player, Layout render with Product Sans, not the Roboto fallback; a list-preference dialog
      draws `Res.drawable.check` on the selected row; settings search shows "No results found"; the
      desktop window is titled "CloudStream" and the `preview` drawable renders. No
      `MissingResourceException` in logcat.

### KMP-06b — `:core:mihon`, the vendored tree in its own module

**Status:** done · #42 · written without an Android SDK; built and checked locally on 2026-10-14
(below), and CI green on `bce90a8f`. **Runtime UI verified 2026-10-14 — see "The settings screens
were finally opened" at the end of this file.** The `core-ktx` 1.2.0 compile-classpath note below was
left as is by the owner's choice (2026-10-14).

Moved by `git mv`, every file's contents unchanged: all 29 `.kt` under `com/mihon/**` (27
commonMain, 2 androidMain), plus `LICENSE` and `readme.md`, at the same relative paths under
`core/mihon/src/`. Packages unchanged, so the `com/mihon/presentation/settings/SearchableSettings`
FQN that `classes-jar.api` names 17 times does not move.

- `:core:mihon` is `cloudstream.kmp.compose` with `api(:core:ui)`, plus `kotlinx-collections-immutable`
  and (androidMain) `preference-ktx` — what `:shared` supplied, nothing new.
- `:shared` gains `api(:core:mihon)`, so `:app` and `:desktopApp` need no build-file change.
- `NOTICE` and the vendored `readme.md` point at the new paths.
- Checked statically before the move: none of the tree's 12 `internal` declarations is used outside
  it; no `expect`/`actual`; its only first-party imports are `:core:ui`'s.
- [x] `com/mihon/common/preference/**` **stays** in `:core:mihon`, as recommended: one licence under
      one root. Moving it to `:core:storage` is a separate decision for whenever `DevicePreferenceStore`
      moves (06d).

**Verification** — local runs, 2026-10-14, Temurin 17.0.20.1, `TZ=UTC`, `-Xmx12G`, branch head
`bce90a8f`; not CI (`DEC-01`):

- [x] Move re-measured two ways: `git diff -M100%` against #43's head `a3314cc8` shows 30 `R100`
      renames (29 `.kt` + `LICENSE`); a blob-hash comparison of `shared/src/**/com/mihon` there vs
      `core/mihon/src` here matches 30 of 31 blobs. The 31st is `readme.md`, whose only change is the
      `shared/src/androidMain` → `core/mihon/src/androidMain` path on line 3. The `com/mihon` blobs at
      `0bc8709` and `a3314cc8` are identical.
- [x] `checkKotlinAbi` → exit 0.
- [x] `assemblePrereleaseDebug lint check -Pcloudstream.strings.guards=fail` → exit 0. `:core:mihon`
      compiled for jvm and android, and its lint ran.
- [x] `:app:checkExtensionApi` → exit 0. `:app:dumpExtensionApi` → exit 0, and `git status` is clean
      afterwards, so the dump is byte-identical to `app/api/classes-jar.api`.
- [x] `classes.jar` holds **no** `com/mihon/` entry, and it held none before this PR either. The 17
      `SearchableSettings` occurrences in the dump are references from `:app`'s settings classes
      (supertypes, descriptors), not the class itself. `makeJar` merges a module only when it has
      `src/*/kotlin/com/lagradost/cloudstream3`, and neither `:shared` (at `0bc8709`) nor `:core:mihon`
      does. So `:core:mihon` needs no sentinel: there is nothing of it in the jar to guard. Those
      references resolve at runtime from the APK: all 113 compiled `com/mihon` classes are in its dex.
- [x] `$stable` scan: baseline `:shared` at `0bc8709` (throwaway worktree) vs `:core:mihon` here, jvm
      and android outputs, plus `:shared`'s remaining classes, `:app` (`prereleaseDebug`) and
      `:desktopApp`. Done two ways: `javap -p -c` per class, then `javap -p` / `javap -v`
      constant-pool `Fieldref …$stable:I` sets.
      - `$stable` fields: mihon jvm 18 → 18, android 29 → 29; `:shared` rest 9 → 9; `:app` 885 → 885.
        Declaring-class sets identical. **None dropped.**
      - `getstatic` owners: mihon jvm/android 37 → 37 each, `:app` 98 → 98, desktop 3 → 3. Fieldref
        owner sets (40 / 51 / 456) identical. **None changed.** `:app` has no `$stable` Fieldref to a
        `com/mihon` owner, before or after.
      - Class sets: 91/91 (jvm) and 113/113 (android). After normalising constant-pool indices and
        the module-name suffix, every moved class is identical except `AndroidPreference` (below).
        `:shared`'s rest and `:desktopApp`: 0 classes differ. `:app`: 2 classes differ, both only in
        the inlined `BuildConfig` build-date constant.
      - Expected renames: 19 `ComposableSingletons` `getLambda$…` accessors, `$CloudStream_shared` →
        `$CloudStream_core_mihon`. Also, **not foreseen in the PR**, 7 `internal` member declarations
        (4 names: `internalSet`, `internalOnValueChanged`, `internalSubtitleProvider`,
        `InternalIconProvider`) on `Preference$PreferenceItem` subclasses. Nothing outside the module
        calls them (`:app` and `:shared` bytecode is unchanged), and none of these names is in
        `classes-jar.api` (0 matches for either suffix).
      - **Finding: `AndroidPreference` (android only)** gains two
        `Intrinsics.checkExpressionValueIsNotNull(editor, "editor")` calls. They come from the inlined
        `SharedPreferences.edit {}`. `:core:mihon`'s `androidCompileClasspath` resolves
        `androidx.core:core-ktx` **1.2.0** (via `preference-ktx` 1.2.1) and `core` 1.8.0, where
        `:shared`'s resolved 1.18.0 through `activity-compose`. It is harmless at runtime: the call
        exists in kotlin-stdlib 2.4.0, and `edit()` never returns null. But "what `:shared` supplied"
        is not quite true for the transitive compile classpath. Aligning it would add a dependency edge
        to `:core:mihon`, which is the owner's call, so it is not changed here.
- [x] `:desktopApp:compileKotlinJvm` / `jvmJar` → exit 0.
- How the `$stable` scan was set up (the brief it was run against): `javap -p -c` of the moved
      classes, `:shared`'s jvm jar on #41's head vs `:core:mihon`'s here. Compose module → Compose
      module, FQNs unchanged. Two things to know before reading the result:
      - **Expected, not a finding:** `ComposableSingletons$…Kt` accessors rename from
        `getLambda$…$CloudStream_shared` to `…$CloudStream_core_mihon`. The Compose compiler mangles
        them with the Kotlin module name; they are absent from `classes-jar.api`. The owner saw the
        same rename on 06c.
      - **Baseline is the pre-carve-out parent (#41's head), never `master`.** Once this merges,
        `master` *is* this branch and the diff comes back vacuously empty, which reads as clean.
- [ ] Runtime: the seven settings screens and settings search render, a list preference opens its
      dialog, and the desktop app's `SwitchPreferenceWidget` row renders. **The desktop half is now
      verified** — see 06d below; the Android half is not. The
      prerelease debug APK installed on a Pixel 9a (API 37), and a launch started the process with no
      `FATAL`, `NoClassDefFound`, `NoSuchField`, `NoSuchMethod` or `MissingResource` in logcat. But
      the device was behind a secure keyguard, so no screen rendered. Statically, the APK has all 23
      Compose resources under the one `com.lagradost.cloudstream4.generated.resources` package (as on
      06c), and all 113 `com/mihon` classes plus 2 desugaring `$-CC` companions are in its dex. The
      desktop app was compiled, not run.

### KMP-06d — retire `:shared`

**Status:** done · #45 (step 1) and #46 (step 2) · 06c (#41) and 06b (#42) landed before them ·
step 2 took **option A**, which is reversible; the options are below, and `DEC-12` is what it
deliberately left open

What is left after 06b: five files, 333 lines, two dependents.

| File | Declares | Used from outside `:shared` |
|---|---|---|
| `commonMain/…/DevicePreferenceStore.kt` (201) | `expect rememberAppSettings`, `AppSettings`, 8 `*Preferences` classes | `rememberAppSettings` in 6 `:app` settings screens; `AppSettings` in `SettingsGeneralScreen`, `SettingsAccountScreen`, `SettingsUpdatesScreen` and `AppContextUtils.kt:485,504`. The `*Preferences` classes are reached only through `AppSettings` |
| `androidMain/…/DevicePreferenceStore.android.kt` (19), `jvmMain/…/DevicePreferenceStore.jvm.kt` (16) | the `actual`s, plus `AppSettings(context)` / `AppSettings()` | `:app` uses the Android one. **Nothing uses the jvm one** — see step 1 |
| `androidMain/…/compose/ComposeFragment.kt` (50) | `Screen.createComposeView(…)` | one caller: `:app`'s `BaseComposeFragment.kt:22` |
| `androidMain/…/compose/Layout.kt` (47) | `DeviceLayout`, `PHONE`/`TV`/`EMULATOR`, `isLayout`, `internal layoutToFlag` | `isLayout` and the flags in 6 `:app` settings screens; `DeviceLayout.Companion.isAutoTv` in `Globals.kt:8`. `ComposeFragment.kt:33` is the only caller of the `internal` `layoutToFlag` |

**Step 1 — `:desktopApp` off `:shared`** · #45. Measured: `desktopApp/src/jvmMain/.../Main.kt`
imports `:core:ui` (`compose.BlackButton`/`WhiteButton`/`LocalFocusOutlineDefault`, `theme.*`, `Res`)
and `:core:mihon` (`SwitchPreferenceWidget`) and **nothing `:shared` declares** — not
`rememberAppSettings`, not `AppSettings`, not `DeviceLayout`. So it depends on `:core:mihon`
directly, and `:shared` is left with one dependent, `:app`. See the PR for the classpath drift this
exposed.

#### The jar is not a constraint here — but `:app` is

`grep cloudstream4 app/api/classes-jar.api` returns **4 lines, none of them these five files'**:
`com/lagradost/cloudstream4/compose/Screen` ×3 as a supertype of `:app`'s `BaseComposeFragment`,
`SettingsFragment2` and `SettingsFragmentScreen`, and `com/lagradost/cloudstream4/R` — both
`:core:ui`'s, both staying put. `:app`'s `Globals` declares its **own** `Int`-based `PHONE`/`TV`/
`EMULATOR`/`isLayout` (`Globals.kt:13-15,45`); `DeviceLayout` is not in the jar, and `Globals`
imports only `DeviceLayout.Companion.isAutoTv`.

**But `extensionApiDumpPackages` is `com/lagradost/` and `:app`'s own classes jar is merged whole**,
so moving any of the five **into `:app`** adds its classes to the published dump: ~15 public classes
(`AppSettings`, the 8 `*Preferences` + `ProviderPreferences$Companion`, the `DevicePreferenceStore*Kt`
facades, `DeviceLayout` + `$Companion`, `LayoutKt`, `ComposeFragmentKt`). Additions are
source-compatible, but they are a published contract afterwards.

#### `:core:ui` cannot take `ComposeFragment.kt` — measured, not argued

`:core:mihon` is `api(project(":core:ui"))`, so an import of `com.mihon` from `:core:ui` is a cycle.
`ComposeFragment.kt` has three such edges, and none of them can be cut inside `:core:ui`:

- `com.mihon.presentation.LocalBackPress` — `SearchableSettings.kt:23` **reads** that exact
  `staticCompositionLocalOf`, so a re-declaration in `:core:ui` would provide a different local and
  the back handler would silently stop working.
- `com.mihon.presentation.settings.collectAsState` (`Preference.kt:195`) — an extension on Mihon's
  `PreferenceData`.
- `rememberAppSettings()`, whose `AppSettings` takes `com.mihon.common.preference.PreferenceStore`.

So whatever hosts `ComposeFragment.kt` sits **above** `:core:mihon`, or is `:app`.

#### Step 2: where `DevicePreferenceStore` and the Android pair go

Not settled. The owner decides; each option's cost is measured.

**A — a new module above `:core:mihon`** (e.g. `:core:settings`), `cloudstream.kmp.compose`,
`api(:core:mihon)` + `:library`, android + jvm. Takes all five files **verbatim**: no content edit,
no visibility widening, nothing added to the jar, `:shared` deleted. Honest cost: a module replaces
a module, so the "retirement" is a rename to a scoped name plus a build file that declares what the
five files actually need (activity-compose, preference-ktx, `:library`) instead of `:shared`'s
catch-all (which also carries `libs.bundles.compose` and `kotlinx-collections-immutable`).

**B — fold all five into `:app`.** `:shared` disappears and nothing replaces it; `expect`/`actual`
collapses to one Android function, since nothing uses the jvm `actual` after step 1. Cost: ~290
lines of first-party Compose code move **back** into `:app`, against the migration's direction, and
~15 classes join the published `classes.jar` ABI permanently.

**C — `:core:storage`, as `docs/plans/kmp-migration.md` §7 Phase 5 says.** That line predates
`:core:mihon` existing. It now costs three things, all measured:

1. **`:core:storage` would depend on `:core:mihon`.** The 9 classes take
   `com.mihon.common.preference.PreferenceStore` and expose `PreferenceData<T>` from the same
   package. The alternative — porting onto `:core:storage`'s own, deliberately identical-shaped
   `PreferenceStore`/`PreferenceData` — retypes **62 `preference = settings.…` arguments** across 6
   `:app` settings screens plus 13 `collectAsState()` calls, because the receiving fields are
   declared `PreferenceData<…>` in **vendored Mihon code** (`Preference.kt:41,69,112,142`). That is
   a retype of the Mihon widget API or a new adapter layer, not a move.
2. **`:core:storage` would depend on `:library`.** `DevicePreferenceStore.kt` imports
   `AllLanguagesName`, `DubStatus`, `SearchQuality`, `TvType`. `core/storage/build.gradle.kts`'s
   KDoc states the module takes layer 0 only and **not** `:library`, "so plan §4.1 rule 4 does not
   apply and this module is not iOS-canary exempt". Adding it reverses a documented property.
3. **`:core:storage` would need the Compose compiler** for `@Composable expect fun
   rememberAppSettings()`. It is `cloudstream.kmp.library`, not `cloudstream.kmp.compose`.

**D — put them in `:core:mihon`.** One module, no cycle, nothing added to the jar — and first-party
code under the Apache-2.0 vendored root, which is the boundary 06b exists to draw
(`core/mihon/build.gradle.kts`: "no first-party code belongs here").

**Sub-option, independent of A–D:** `Layout.kt` imports nothing from Mihon, so it could go to
`:core:ui/androidMain` on its own — at the cost of widening `DeviceLayout.Companion.layoutToFlag`
from `internal` to public, because `ComposeFragment.kt:33` is its only caller. Keeping the pair
together avoids the widening.

#### Step 2 — `:core:settings`, option A · #46

Taken because A is the only option that moves the five files **verbatim**, and because it is
reversible: B, C and D are a rename and a build-file edit away. `DEC-12` records the one thing it
deliberately does not settle, the two `PreferenceStore` declarations.

`:core:settings` is `cloudstream.kmp.compose`, `api(:core:ui)` + `api(:core:mihon)` +
`implementation(:library)`, android + jvm. Two of `:shared`'s dependencies are **not** carried over
because no file here uses them: `kotlinx-collections-immutable` (the Mihon tree's) and
`preference-ktx` (`DevicePreferenceStore.android.kt` reaches `SharedPreferences` through Mihon's
`AndroidPreferenceStore`, which declares it itself). `libs.bundles.compose` is not declared either —
`cloudstream.kmp.compose` supplies it as `api`.

**`:core:settings` is iOS-canary exempt**, the second entry in `IOS_CANARY_EXEMPT` after
`:core:model`, because `DevicePreferenceStore.kt` imports `AllLanguagesName`, `DubStatus`,
`SearchQuality` and `TvType` (plan §4.1 rule 4). The list "may only shrink", so: this is not a new
`:library` dependency — `:shared` declared the same edge and had no iOS target at all — it is the
same code compiling under a convention plugin for the first time. It lifts with `:core:model`'s.
Measured both ways: with the exemption, `:core:settings` has no `iosCanary` task and `:core:ui`
still has one; with it removed, `:core:settings:dependencies --configuration
iosArm64CompileKlibraries -Pcloudstream.iosCanary=true` prints `project :library FAILED`.

**Verification** — local runs, 2026-10-14, Temurin 17.0.20.1, `TZ=UTC`, `-Xmx14G`, each command
redirected to a file with `$?` captured:

- [x] `checkKotlinAbi` → exit 0.
- [x] `assemblePrereleaseDebug lint check -Pcloudstream.strings.guards=fail` → exit 0.
- [x] `:app:checkExtensionApi` → exit 0. `:app:dumpExtensionApi` → exit 0 and `git status` clean
      afterwards, so `app/api/classes-jar.api` is byte-identical. `com/lagradost/cloudstream4/R` is
      still in `app/build/classes.jar`, and no `com/lagradost/cloudstream4/compose/*` entered it:
      `:core:settings` declares no `src/*/kotlin/com/lagradost/cloudstream3`, so it is not merged.
- [x] Move re-measured: `git diff -M100%` shows **5 `R100` renames** for the sources plus one for
      `shared/readme.md` → `docs/compose-rewrite.md`. No content edit in any of the five.
- [x] `$stable` scan, baseline **#45's head `5b2c216a`** in a throwaway worktree (never master
      after the merge, or the diff is vacuously empty). Counted two ways on every side: a regex over
      `javap -p -c`, and a direct class-file parser (field access flags plus constant-pool
      `Fieldref` owners).
      - `:app` `prereleaseDebug`: **2202 classes, 888 `$stable` declarers, 2407 `getstatic`
        targets, 9276 `Fieldref` owners — identical sets**, not merely identical counts. A
        byte-level `sha256` comparison of the same 2202 class files adds the one caveat: **2 differ**,
        `SettingsFragment` and `SettingsFragmentScreen`, and in both the entire diff is one
        `ldc2_w` operand, `1791992530844` → `1791992051696` — two epoch milliseconds 8 minutes
        apart, i.e. `BuildConfig.BUILD_DATE` inlined at `SettingsFragment.kt:255`. The same two
        classes differed for the same reason in 06b. (The set comparison cannot see this: the dump
        deliberately does not guard constant *values*, and nor does a `$stable`/`getstatic` set.)
      - `:desktopApp`: 3 classes, 0 `$stable`, 19 `getstatic`, 21 `Fieldref` — identical sets.
      - The moved files: `:shared` 13 jvm / 18 android → `:core:settings` 13 jvm / 18 android, same
        class names, **9 → 9 `$stable`** per target with identical declaring-class sets, and
        identical `Fieldref` owner sets (87 jvm, 99 android). Per-class `javap -p -c` text is
        **identical for all 31** after normalising constant-pool indices and the module-name suffix.
      - **Expected, not a finding:** 2 `internal` members rename with the Kotlin module name —
        `DeviceLayout.and-sihxV88` and `DeviceLayout$Companion.layoutToFlag-PtjY-Ko`,
        `$CloudStream_shared` → `$CloudStream_core_settings`. Both are 0 matches in
        `classes-jar.api`. 06b saw 26 such renames, 06c saw 1.
      - **Expected, not a finding:** `:core:settings` gains 7 classes per target that `:shared` did
        not have — an empty generated `Res` (`Res`, `Res$array/$drawable/$font/$plurals/$string`,
        `ActualResourceCollectorsKt`) at `com.lagradost.cloudstream4.core.settings.generated.resources`,
        carrying 6 of the `$stable` fields and 5 `getstatic`s. `:shared` set `generateResClass =
        never` to avoid colliding with `:core:ui`'s public `Res`; `cloudstream.kmp.compose`'s
        default package is per-module, so there is no collision, and `:core:mihon`, `:core:theme`
        and `:core:designsystem` each carry the identical 7. `publicResClass = false`, so none of
        them is public API.
- [ ] Runtime: the seven settings screens, settings search and a list-preference dialog. **Still
      not verified** — same gap as 06b, and the same cause: the attached Pixel 9a was behind a
      secure keyguard (`mDreamingLockscreen=true`), so nothing could be driven on screen.
      What *is* verified statically, in the `prereleaseDebug` APK this branch builds: all 9
      `AppSettings`/`*Preferences` classes, `DevicePreferenceStore_androidKt`, `DeviceLayout`
      (+ `$Companion`), `LayoutKt`, `ComposeFragmentKt`, `compose/Screen`,
      `com/lagradost/cloudstream4/R`, `SearchableSettings`, `AndroidPreferenceStore`,
      `BaseComposeFragment`, `Globals` and all 7 `Settings*Screen` classes are in its dex —
      27 of 27 present, 0 missing, out of 49,542 classes. So the module move cannot have produced a
      `NoClassDefFoundError`; what remains unproven is what the Android screens look like.
- [x] Runtime, desktop: `:desktopApp:run` on this branch opens the window and it renders — the
      title "CloudStream" from `stringResource(Res.string.app_name)`, `CloudStreamTheme`'s dark
      surface, `:core:ui`'s `WhiteButton`/`BlackButton`, and **`:core:mihon`'s
      `SwitchPreferenceWidget`** with its icon (`Res.drawable.preview`), title and subtitle. That is
      the item 06b left open ("the desktop app was compiled, not run"), and it exercises what a
      module move most endangers: Compose resources resolving from `:core:ui`'s `Res` across a
      module boundary, now two moves from where they started. The window still reads "Hello,
      World!" because `desktopApp/src/jvmMain/.../Main.kt` is a 62-line placeholder that `KMP-06d`
      does not touch.

#### Also in step 2

- `settings.gradle.kts:23`, `app/build.gradle.kts:300` lose `:shared`.
- **`NOTICE` is already clean** — `grep -i shared NOTICE` is 0 matches; 06b repointed it.
  `docs/HANDOVER.md` says otherwise and is wrong on that point.
- Two readmes outlive the module and need a decision: `shared/readme.md` is LagradOst's 100-line
  rationale for the whole Compose rewrite (file structure, MVI, resources, "why compose", the AI
  usage policy) and is not about this module's mechanics — it belongs in `docs/`.
  `shared/src/readme.md` is 9 lines of the Compose-Multiplatform template's source-set blurb.
- `:shared`'s Android namespace `com.lagradost.cloudstream4.shared` generates no `R`, so deleting
  the module removes nothing from the jar — confirm with `:app:checkExtensionApi`.

---

## KMP-07 — `:media:model` skeleton

**Status:** done · #48 (contracts) and #59 (the `ExtractorLink` / `ExtractorUri` → `StreamRequest`
adapter, `KMP-07`'s own entry below) · contracts only, no implementation

`:media:model` exists: `cloudstream.kmp.library`, android + jvm, **no internal dependencies at
all**, iOS canary on. Eight files, 39 tests.

### Why it depends on nothing

Plan §4.2 lists this module's canary as plain "yes", not "subject to rule 4" — unlike `:core:model`
and `:core:settings`, which are exempt because their commonMain takes `:library`. Keeping it that
way is the whole design constraint: **every shape here is a restatement, not a move**, and `:app`
owns the adapters. That is not a workaround, it is the point — the contracts have to outlive
`:library`'s Android-bound declarations for an iOS player sibling to exist (plan §4.3).

It also means no class here is in any ABI dump, nothing is merged into `classes.jar`, and `KMP-09`
does not apply: there is nothing to scan, because nothing moved.

**`:library` is itself not Kotlin/Native-compilable today**, which settles the "just depend on it"
shortcut: `ExtractorApi.kt` has `java.util.UUID` in public signatures at `:531` (`newDrmExtractorLink`),
`:679` (`setUuid`), `:684` (`getUuid`), plus `CLEARKEY_UUID`/`WIDEVINE_UUID`/`PLAYREADY_UUID` built
with `.toJavaUuid()` at `:492`/`:495`/`:498`. That is `KMP-08`/Track L's problem.

### What is in it

| File | Holds |
|---|---|
| `StreamRequest.kt` | `StreamSource` (`Remote`/`Local`), `StreamProtocol`, `AudioTrackSource`, `StreamRequest` |
| `PlaybackRequest.kt` | `PlaybackRequest` |
| `Drm.kt` | `DrmScheme`, `DrmConfiguration` |
| `EngineCapabilities.kt` | `HeaderSupport`, `EngineCapabilities` + `supports(StreamRequest)` |
| `Subtitle.kt` | `SubtitleCue`, `SubtitleSource`, `SubtitleTrack` |
| `CaptionStyle.kt` | `ArgbColor`, `CaptionEdge`, `CaptionFont`, `CaptionStyle` |
| `PlayerCommand.kt` | `CommandSource`, `PlayerCommand` (sealed, 15 cases) |
| `NowPlaying.kt` | `NowPlaying`, `PlaybackPosition`, `PlaybackState`, `PlaybackStatus` |
| `PipState.kt` | `AspectRatio`, `PipState` |
| `DownloadProgress.kt` | `DownloadState`, `DownloadProgress`, `SegmentProgress` |

### The decisions, and what each one costs

Each was taken against a measured shape; the survey behind them is in the PR.

- **No `TvType`, no `Score`, no `LoadResponse`.** `TvType` is the most pervasive `:library` leak
  across the shapes `NowPlaying` replaces (`ResultEpisode`, `ExtractorUri`, four download DTOs).
  It is left out rather than restated: no playback surface — notification, lock screen, cast
  receiver, PiP — branches on it. `NowPlaying.seriesTitle` being null is what separates a film from
  an episode, which is the only distinction those surfaces draw. **Cost:** anything that does need
  `TvType` keeps using `:app`'s types.
- **`StreamSource` is a sealed type, replacing two nullable parameters.** `loadPlayer(link, data, …)`
  takes `ExtractorLink?` and `ExtractorUri?` and throws `IllegalArgumentException("Requires link or
  uri")` when both are null — a sum type written as two nulls. Now the invalid case cannot be built.
- **`DrmScheme` is a new enum.** There is none today: `:library` has three `Uuid` constants and
  `:app` dispatches with a `when` over `uuid.toJavaUuid()` (`CS3IPlayer.kt:1282`, `:1304-1305`)
  that falls through to a logged error. A UUID cannot be exhaustively matched, so "this engine
  cannot play this scheme" is a runtime log instead of a compile-time case. The UUIDs stay; `:app`
  maps. **Cost:** a mapping table, and `UNKNOWN` for schemes this build has not heard of.
- **`HeaderSupport` exists because of iOS.** Media3 sets any header; AVPlayer has no public
  arbitrary-header API but does expose a user agent and cookies (plan §10 O6). Since extensions
  routinely pin a UA or pass a Cloudflare `cf_clearance` cookie, those two cases survive on an
  engine that can do nothing else, and `EngineCapabilities.supports()` says so rather than failing
  at play time.
- **`CaptionStyle` drops `elevation`, `edgeSize` and `backgroundRadius`** — dp, px and px, resolved
  only on Android through `TypedValue` and `.toPx`. A contract carrying a px value carries a screen
  with it. `textSizeSp` is kept: sp is a stated scale, and it is the one the user sets. It also
  drops `removeCaptions`, `removeBloat` and `upperCase`, which are text *filtering* and belong to
  `:media:subtitle`. **This is why the type is 10 fields where `SaveCaptionStyle` is 17.**
- **`CaptionEdge` is an enum because the int is in two incompatible namespaces** — Media3's
  `CaptionStyleCompat.EDGE_TYPE_*` for the player and Cast's `TextTrackStyle.EDGE_TYPE_*` for
  Chromecast. The same user setting is stored twice as two different integers today and nothing
  says so.
- **`CaptionFont.PlatformDefault` is not "no choice".** `SaveCaptionStyle.typeface` is an Android
  `@FontRes` id — a lookup into a resource table that exists only in an Android build. There is no
  portable value with that meaning, so a bundled font is named rather than numbered.
- **`PipState` models the decision, not the permission.** Whether PiP is possible depends on the
  SDK level, `PackageManager.FEATURE_PICTURE_IN_PICTURE` and an `AppOpsManager` check the user can
  revoke; none has a portable form, so `isSupported` is supplied by the platform. The three magic
  numbers for the aspect clamp (`0.41841f`, `2.39f`, accuracy `100000`) move from local `val`s
  inside the Android helper into named constants on `AspectRatio`, where they are testable.
- **`PlayerCommand` is sealed, and does not replace `CSPlayerEvent`.** Half the commands carry a
  value, which an enum forces into a side channel — which is how `IPlayer` ended up with two
  command surfaces, an 11-case enum plus ~20 methods that are commands in everything but name. The
  enum stays in `:app`: it is published ABI and it has a live `Intent` wire format (see `BUG-09`).
- **`DownloadProgress` prefers segments over bytes.** An HLS download's byte total only counts
  segments already seen, so a byte-progress bar grows and then jumps backwards. `:app` already
  carries `hlsProgress`/`hlsTotal` beside the byte counts for this reason.
- **Unknown totals return `null`, never `0f`.** `PlaybackPosition.fraction`,
  `DownloadProgress.fraction` and `SegmentProgress.fraction` all do this, so a progress bar stays
  indeterminate rather than snapping to empty.

### Deliberately not done

- **No kotlinx-serialization.** Nothing here crosses a process or disk boundary yet; `:app`'s
  persistence keeps operating on its own Jackson-annotated types. Adding the plugin later is
  additive. Keeping it out is what makes the module dependency-free.
- **No mime strings.** The engine boundary uses `androidx.media3.common.MimeTypes` on Android and
  would use `AVFileType` on Apple platforms, and the two disagree even on HLS — `ExtractorLinkType`
  says `application/x-mpegURL`, Media3 says `MimeTypes.APPLICATION_M3U8`. Declaring one here makes
  the module wrong for one platform.
- **No `:app` call site is changed.** This is a skeleton; nothing consumes it yet. The `:app`
  adapter (below) is the first test of the shapes; rewiring `CS3IPlayer` to use it is still to do.

### The `:app` adapter — the design's first test

`app/src/main/java/com/lagradost/cloudstream3/ui/player/StreamRequestAdapter.kt`, an `internal object`
beside `CS3IPlayer` and `ExtractorUri` (the two types it reads). One direction only
(`:library`/`:app` → `:media:model`), pure, no state: `fromLink(ExtractorLink)`, `fromUri(ExtractorUri)`,
`protocolOf(ExtractorLinkType)` and `schemeOf(Uuid)` / `schemeOf(java.util.UUID)`. `:app` gained
`implementation(project(":media:model"))`; no catalog entry. Tests:
`app/src/test/.../ui/player/StreamRequestAdapterTest.kt`. **No call site is changed** — `CS3IPlayer`
still takes `ExtractorLink` / `ExtractorUri`; rewiring it is its own task.

`fromUri` itself is not unit-tested: `ExtractorUri.uri` is an `android.net.Uri`, which a plain JVM
test cannot build (no Robolectric/Mockito in the catalog). Its Uri-free body, `localStream`, is.

**Where the design does not fit**, by field. (a) = deliberately dropped by the design; (b) = a gap in
`:media:model` needing a follow-up; (c) = the adapter derives it honestly.

| Source field | Class | What happens |
|---|---|---|
| `ExtractorLinkPlayList` (`url = ""`, `playlist: List<PlayListItem>` with `durationUs`) | **(b)** | `StreamRequest` has no concatenated-slices shape, so `fromLink` returns `null`. `CS3IPlayer` plays it through `ConcatenatingMediaSource2`. Needs either a `StreamSource.Playlist` or a decision that playlists stay `:app`-only. |
| `ExtractorLink.referer` vs a `Referer` key in `headers` | **(b)**, docs only | `getAllHeaders()` lets an existing header win over the field. `StreamSource.Remote` keeps both and does not say which wins. The adapter passes both through unchanged and does not lift the header into `referer`. One sentence on `Remote` would settle it. |
| `StreamSource.Remote` KDoc: "fetched over HTTP(S)" | **(b)**, docs only | `TORRENT`/`MAGNET` links carry `magnet:` or `.torrent` urls. `protocol` already distinguishes them; the KDoc is wrong about the url. |
| `AudioTrackSource.languageTag` | **(b)**, docs only | `AudioFile` is `(url, headers?)` and has no language, so nothing can supply it today; the adapter leaves `null`. The KDoc says "where the extension supplied one", which no extension can. |
| `StreamRequest.protocol` for a local file | **(c)** | `ExtractorUri` carries nothing to derive it from. `PROGRESSIVE` is what the player already does (`loadOfflinePlayer` opens every local file as `MimeTypes.VIDEO_MP4`). A local HLS/DASH manifest would be mislabelled. |
| `ExtractorLinkType.MAGNET` | **(a)** | Folded into `TORRENT`; `StreamProtocol` says so. The url prefix keeps the distinction. |
| `ExtractorLink.quality` | **(a)** | Carried straight through, as designed. `quality` is `Int?` but a link always has one, so `null` is only ever produced by `fromUri`. The `Qualities.Unknown` sentinel (400) and `0` = Auto are carried as numbers, not mapped to `null`. |
| `ExtractorLink.extractorData`, `getVideoSize()` | **(a)** | Extension concerns; stay on the link (stated in the `StreamSource` KDoc). |
| `getVideoInterceptor(link)` (looked up by `link.source`) | **(a)** | A runtime object, not data. `providerName` is exactly the key needed to look it up, so the field earns its place. |
| `DrmExtractorLink.kty` | **(c)**, note | Defaults to `"oct"` for every scheme, Widevine included, though only the ClearKey branch reads it. Passed through; the adapter does not null it by scheme. |
| `DrmExtractorLink.uuid` not recognised | **(a)** | `DrmScheme.UNKNOWN`, as designed. Nothing is lost that exists today: `CS3IPlayer`'s fall-through logs `drm::class.simpleName`, not the uuid. |
| `DrmExtractorLink.keyRequestParameters` `HashMap` | **(a)** | Copied into a `Map` (shape change stated on `DrmConfiguration`). |
| `ExtractorUri.id`, `episode`, `season`, `headerName` | not `StreamRequest`'s | `NowPlaying` has homes for them (`id`, `episode`, `season`, `title`). A separate adapter. |
| `ExtractorUri.parentId` | **(b)** | No home in `StreamRequest` or `NowPlaying` (`NowPlaying.id` is the episode id; there is no series id). |
| `ExtractorUri.tvType` | **(a)** | `TvType` is deliberately not restated (see "No `TvType`" above). |

No field was found that `StreamRequest` demands and the source cannot supply, apart from
`protocol` for a local file (above). The shapes otherwise held.

**ABI.** `internal object` keeps the adapter off the source-level API, but a Kotlin `internal` class is
public in bytecode, so `classes.jar` gains `StreamRequestAdapter` (and it is not a top-level facade,
which is the `INF-07` trap). `:media:model` is not merged into the jar — its package is
`cloudstream4.media.model`, not `cloudstream3` — so the adapter's public descriptors name classes
that are not in `classes.jar`; harmless unless an extension calls it, which it has no reason to.
`:app:checkExtensionApi` is expected to report additions only, 0 removed and 0 changed, until
`:app:dumpExtensionApi` is run.

### Verification

Local, 2026-10-14, Temurin 17.0.20.1, `TZ=UTC`, `-Xmx14G`, each command redirected with `$?` captured.

- [x] `checkKotlinAbi`, `assemblePrereleaseDebug lint check -Pcloudstream.strings.guards=fail`,
      `:app:checkExtensionApi` → all exit 0. The ABI dump is unchanged, as it must be: nothing moved.
- [x] `:media:model:jvmTest` → **39 tests, 0 failures, 0 skipped**, counted from the JUnit XML
      rather than from the task's exit code (the first count was taken with a regex that matched
      no attribute order and reported 0 — re-measured by parsing the XML).
- [x] `:media:model:iosCanary -Pcloudstream.iosCanary=true` → exit 0, both `iosArm64` and
      `iosSimulatorArm64` klibs compiled.
- [x] **The canary is not decorative — it caught a real error on the first run.** `@JvmInline` on
      `ArgbColor` compiled for android and jvm and failed for Native with `Unresolved reference
      'JvmInline'`, because `kotlin.jvm.*` is a default import only on JVM. Fixed with an explicit
      `import kotlin.jvm.JvmInline`. This is the first module whose `commonMain` purity is enforced
      by a build rather than by reading — `:core:model`'s KDoc admits its own is "checked by
      reading, not by a build".

---


---

## KMP-11 — `:feature:extensions`: the `ExtensionRuntime` seam and the catalogue model

**Status:** the **contracts half is done** 2026-10-15 (plan row 17, Phase 7 vertical 6) · the
**adapter is done** · #89 · and the owner's `F1`/`F2` answers are recorded · #100. `loaded`, `load`,
`unload` and `uninstall` work over an unedited `PluginManager`, `install` refuses, **nothing in `:app`
calls it yet, and it has never run on a device.** See "The adapter" below.

`:feature:extensions` exists, commonMain only, **contracts and no implementation** — the same shape
`KMP-07` gave `:media:model`. **Nothing in `:app` changed.**

| File | Holds |
|---|---|
| `ExtensionIndex.kt` | `ExtensionRepositoryIndex`, `ExtensionStatus`, `ExtensionListing` |
| `ExtensionInstall.kt` | `InstalledExtension`, `ExtensionState`, `ExtensionVerification` |
| `ExtensionRuntime.kt` | `ExtensionRuntime` (install / load / unload / uninstall / loaded), `UnsupportedExtensionRuntime` |

### Zero published-ABI risk, by construction

Package `com.lagradost.cloudstream4.feature.extensions`, no `com/lagradost/cloudstream3` directory,
so `:app:makeJar` never merges it — plan §4.2 has `:feature:*` as not-merged, old classes staying in
`:app`. No sentinel is needed and the dump does not move.

`:app`'s `plugins.Repository`, `SitePlugin`, `PluginData` and `PluginWrapper` are **all in
`app/api/classes-jar.api`** (checked, 3 hits each) and stay exactly where they are. These types
restate the portable half beside them; they do not replace them.

### Two decisions taken, both reversible

- **`cloudstream.kmp.library`, not a new `cloudstream.kmp.feature`.** §4.2 pencils in `kmp.feature`
  for `:feature:*`, but it would have the same contents as `cloudstream.kmp.library` — android plus
  the jvm purity canary, no jar merge — so adding it would be a second name for one behaviour. When a
  feature module needs the Compose compiler for its screens (Phase 7 recipe step 1),
  `cloudstream.kmp.compose` already exists. Add `kmp.feature` when it earns distinct behaviour.
- **`ExtensionListing.tvTypes` stays a `List<String>`**, as it is on the wire, instead of
  `List<TvType>`. `TvType` is a `:library` type, and that edge from commonMain would make this module
  iOS-canary exempt under §4.1 rule 4 — and `IOS_CANARY_EXEMPT` "may only shrink". The wire comment on
  `SitePlugin.tvTypes` says those strings "are yet to be mapped and used" anyway, so the mapping
  belongs at `:app`'s edge where `TvType` already is. **Same lesson as `KMP-05`:** choosing the source
  set and the field types to avoid a `:library` edge is what keeps the canary at 12 modules.

The module therefore depends on **nothing** but coroutines, and **joins** the canary: it ran
`:feature:extensions:iosCanary` and the canary went from **12 modules to 13**. The zero-dependency
choice did not merely avoid an exemption, it widened iOS coverage.

### What is deliberately absent from the seam

No `Context`, `File` or `Activity` — those are what kept this logic in `:app`. No download or HTTP
(`:core:http`'s job; `install` takes bytes). No persistence (`:core:storage`'s). **No verification**:
`ExtensionVerification` names the outcomes and the runtime reports which it reached, so a skipped
check cannot hide inside a success. `ExtensionVerification.Skipped` exists because `:app` carries
`fileHash` through the index today and **never compares it** — the check has nowhere to live yet, and
this is that place.

`UnsupportedExtensionRuntime` refuses every call with a stated reason, so iOS and `:desktopApp` can
compile and run the catalogue and show an honest "not supported here" while `DEC-04` and §10 O2 are
open. A build with no runtime at all would fail at the first install, far from the cause.

### Verified

4 tests, 0 failures: the status mapping is total in both directions, an unknown wire code is kept
rather than collapsed, a `Skipped` verification is not equal to a `Verified` one, and the unsupported
runtime refuses all four operations with the given message.

Four gates: `abi=0 check=0 extapi=0 canary=0`, and the **canary is 13 of 13** with
`:feature:extensions` among them. `app/api/classes-jar.api` is **unchanged** — as it must be, since
nothing merged into the jar.

### The adapter — 2026-11-02

`app/src/main/java/com/lagradost/cloudstream4/extensions/`: `AndroidExtensionRuntime`, and
`PluginEdgeMapping.kt` for `Repository`, `SitePlugin` and `PluginData` onto the contracts. `:app`
gains `implementation(project(":feature:extensions"))`.

**`PluginManager.kt` has no diff and the runtime has no caller**, so everything `PluginManager` does
today does not change:

| Behaviour | How it was checked |
|---|---|
| Load order, notifications, OAT cleanup, error handling | `git diff origin/master -- app/src/main/java/com/lagradost/cloudstream3` is empty; `grep -rl AndroidExtensionRuntime app/src/main` finds only the two new files |
| Safe mode at startup | The same. The runtime's own `load` also refuses in safe mode (below) |
| Published entry points | `:app:checkExtensionApi`: **0 removed, 0 changed, 3 added** |

**Why no edit to `PluginManager`, measured.** Adding one `internal` forwarding member made the check
report `0 removed, 1 changed`, with `loadForRuntime$app` as a public method on the published class.
That is why `load` goes through `loadSinglePlugin` even with the limit listed below.

**The dump gains 37 lines and loses none**, all in three new `com/lagradost/cloudstream4/extensions/`
classes: `AndroidExtensionRuntime`, `PluginEdgeMappingKt`, `PluginManagerPort`. The dump covers
`com/lagradost/`, so a new `:app` class cannot avoid it. `KMP-07` and `KMP-12` accepted their adapters
the same way. Nothing moved between modules, so no `$stable` field can have been lost.

**Tested without a device, and only that.** 32 tests against a fake of the `PluginManager` members
used. `LivePluginManagerPort`, the one-line delegates to the real object, is not exercised: JVM tests
cannot initialise `PluginManager`, because it needs `Environment`. One test pins the source text of
`loadSinglePlugin`'s selection, which `apiNameForLoad` restates. 19 mutations, 19 caught.

### Where the seam and `PluginManager` disagree

Each is reported, not forced. **`F1` and `F2` were decided by the owner on 2026-11-03:** Android keeps calling `downloadPlugin` until an Android screen needs this route, so `install` keeps refusing; and the loser of a shared `internalName` stays refused, so `PluginManager`'s published block does not change.

- **`F1` — `install` refuses.** Three separate gaps: `getPluginPath` hashes the repository *index*
  URL, and a listing does not carry it (`ExtensionListing.repositoryUrl` is the extension's own page);
  `downloadPluginToFile` owns the fetch and the hash check and takes a URL, not bytes; `setPluginData`
  is private, so nothing outside can save the record that startup loads from. Options:
  - (a) Android screens keep calling `PluginManager.downloadPlugin` and only desktop installs through
    the seam. No change needed. **Recommended until an Android screen needs it.**
  - (b) Add the index URL to `ExtensionListing`, and accept one `internal` member on `PluginManager`
    as a reviewed dump change.
  - (c) The adapter writes `PLUGINS_KEY` itself under the public `lock`. A second writer of a
    persisted format; not recommended.
- **`F2` — `load` can refuse a loadable plugin.** `loadPlugin` is private, and `loadSinglePlugin`
  selects by name, online first. When two repositories share an `internalName`, or `Foo` sits beside
  `FooProvider`, the one that loses is refused rather than the wrong code being loaded. Options: accept
  it, or accept one `internal` member (the measured `1 changed`).
- **`F3` — a failed `load` has no cause.** `loadPlugin` returns `Boolean`; it has already logged and
  shown a toast. `Result.failure` says only to look in the log.
- **`F4` — a failed `load` can leave the plugin live.** `loadPlugin` adds to `plugins` before calling
  the plugin's `load`, and keeps it if that throws, with whatever it registered. So `loaded()` lists it
  after `load` failed, where `JvmExtensionRuntime` purges. Unloading here would make the seam tidy but
  remove providers that work today on the normal path, so it reports what is true.
- **`F5` — `uninstall` of a missing file fails and keeps the record.** `deletePlugin` removes the record
  only after a successful delete, and `deletePluginData` is private. `JvmExtensionRuntime` succeeds
  here. Also, `uninstall` unloads first, as the seam says, where `deletePlugin` deletes first.
- **`F6` — verification differs, and three KDocs are wrong.** `:app` **has** compared `fileHash` since
  `7926e60f` (2026-04-19): `RepositoryManager.kt:214-220`. `ExtensionInstall.kt:72`,
  `ExtensionIndex.kt:136`, `ExtensionHash.kt:30`, and the "never compares it" above, say otherwise.
  The comparison is also stricter than `ExtensionHash`: exact string match, so uppercase hex, a
  `sha512-` value and a blank value are all refused, where `ExtensionHash` gives `Verified`, `Skipped`
  and `Skipped`. No code path reaches this while `install` refuses. It decides `F1`(b).
- **`F7` — what `Context` a plugin receives is the caller's choice.** `MainActivity` passes an Activity;
  `SubscriptionWorkManager` does not. Upstream's `ExamplePlugin.kt` does
  `context as? AppCompatActivity` and needs it for its settings sheet, so an application `Context`
  loads such a plugin with settings that silently do nothing. The runtime takes a provider and holds
  no `Activity`; its first caller has to choose.
- **`F8` — an unknown status.** The contract says to treat `Unrecognised` as `Down`;
  `OnlinePluginData.isDisabled` tests only for `0`, so `PluginManager` treats it as enabled. The mapping
  keeps the code and applies no policy.

**Safe mode:** `PluginManager` does not enforce it, its callers do, so the runtime's `load` refuses by
itself. `unload` and `uninstall` stay allowed.

### Found in existing code, not changed

```
Found: no safe-mode check before loading every extension, in SubscriptionWorkManager.kt:131-132
Suggest: skip the work, or the load, when PluginManager.isSafeMode()
Why: checkSafeModeFile's KDoc says it overrides any extension loading to fix crashes

Found: currentlyLoading is left set on the two early returns, in PluginManager.kt:616 and :641
Suggest: clear it on those paths
Why: CloudStreamApp.kt:57 writes it into the crash log, blaming a plugin that is no longer loading
```

- [x] Owner go-ahead to adapt `PluginManager` to the seam
- [x] The adapter, for `loaded` / `load` / `unload` / `uninstall`, with the dump diff recorded
- [x] Owner: decide `F1` and `F2` (2026-11-03: both as recommended)
- [ ] Wire a first caller, choosing the `Context` (`F7`), and exercise it on a device

---

## KMP-12 — `:feature:search`: the first feature vertical's state machine

**Status:** **steps 1 and 2 of 5 done** 2026-10-15 (plan row 18, Phase 7 vertical 1) · step 2 is
**TV only — touch and desktop have no design to build from** · steps 3 and 4 are written and sit in
**draft #91**, held for a television · `:app`'s search path is untouched

> **The state machine's defects were split out and landed on their own, 2026-11-03.** Building the
> Android host (step 4) found four defects in `SearchViewModel`, and all four were in the **desktop**
> app as shipped, not just the unbuilt Android path. Three are `BUG-24`; the fourth was found
> independently from a screenshot of the owner's and landed first as `BUG-23` (#114). #91 keeps only
> what needs a device: `SearchTvFragment`, the one-line `MainActivity` repoint,
> `ApiRepositorySearchSource`, `DataStoreSearchHistory` and the `classes.jar` lines those add.

`:feature:search` holds the contracts, the provider seam, the MVI state machine and the TV screen.
Phase 7's recipe is five steps — state machine, screens, glue `Fragment`, repoint one nav node,
delete the old fragment a release later. `SearchFragment` and `:app`'s `SearchViewModel` are still
the live path and are unchanged.

| File | Holds |
|---|---|
| `SearchContracts.kt` | `SearchResultItem`, `SearchResultId`, `SearchSource`, `SearchOutcome` |
| `SearchContent.kt` | `SearchContent` (`Recent` / `Results`), `SearchResultGroup`, `groupByProvider` |
| `SearchHistory.kt` | `SearchHistoryEntry`, `SearchHistoryStore`, `SearchSources` |
| `SearchMvi.kt` | `SearchState`, `SearchAction`, `SearchEffect`, `SearchPrompts` |
| `SearchViewModel.kt` | the state machine |
| `tv/SearchScreenTv.kt` | the screen and its geometry (`SearchScreenTvDefaults`) |
| `tv/SearchChromeRow.kt` | the query field, the source chip, the chip row |
| `tv/SearchResultRails.kt` | one rail per provider, and the poster card |
| `tv/SearchRecentColumn.kt` | recent searches, and `Clear all` pinned at the foot |
| `tv/SearchTvCopy.kt` | every word on screen, injected |
| `tv/SearchScreenTvPreviews.kt` | one preview per state, at the artboard's own 960×540 dp |

### Row 18's stated blocker does not apply, and that was the point

The plan annotates row 18 *"ViewModel needs Track L for iOS (rule 4)"*, because the old
`SearchViewModel` consumes `SearchResponse` from `:library`. A `commonMain` edge to `:library` would
force this module into `IOS_CANARY_EXEMPT`, and that list "may only shrink".

`SearchResultItem` restates the fields the UI reads and `SearchSource` is the seam `:app` implements
over `APIRepository`, so **there is no `:library` edge, no exemption, and no Track L dependency.**
Mapping `SearchResponse` happens in `:app`, beside the types it belongs to — the same split `KMP-07`
used for the player and `KMP-11` for the plugin index.

**Third time this move has paid:** `KMP-05` chose a source set to avoid the edge, `KMP-11` chose a
field type, and here it removes a blocker the plan had written down. Worth treating as the default
question for any new module: *what would it take for this not to touch `:library`?*

### `SearchOutcome.Partial` is the defect this vertical exists to fix

`.claude/rules/mvi-viewmodel.md` cites the app's most common defect: a failure that is a `Toast`,
with one branch reading `// No user indication it failed :( // TODO`. Search is where that bites
hardest, because searching twelve providers and having three fail **is the normal case**.

So `SearchSource` returns a `SearchOutcome` rather than throwing — a signature that can only succeed
or throw has nowhere to put "9 of 12 answered" — and `Partial` carries the count plus a
machine-readable `detail`. The ViewModel maps it to `UiState.Partial`, which keeps the results on
screen and puts the shortfall in a chip-row banner, instead of replacing usable content with an error
band.

### `SearchPrompts`: the seam between two rules that disagree

`:core:designsystem`'s `StatePrompt` / `FailureNotice` hold **already-resolved** display strings and a
required `StateAction` lambda, and that module "never holds display strings". But the migration plan
says a ViewModel exposes `UiString`, **never** a resource id — and a ViewModel resolving strings
itself would need a platform string loader in `commonMain`.

`SearchPrompts` is injected, which satisfies both: state is `UiState` as the MVI rule requires, and
the copy is resolved by whoever constructs the ViewModel (the Android screen from `stringResource`, a
test from literals). **Flagged for review** — it is the first vertical, so this pattern will be
copied six more times, and it is cheaper to disagree with it now.

### The screen was rendered, and rendering found two defects the gates could not

Compiling a screen says nothing about whether it lays out. `:feature:search` is a KMP module, so
there is no screenshot suite (see below) and no `compose-ui-test` in the catalog — but Compose
Desktop ships `androidx.compose.ui.renderComposeScene(width, height, density, content)`, which
renders to a Skia `Image` with **no window and no screen-capture permission**. A throwaway harness
in `:desktopApp` (not committed) rendered all five states at 1920×1080 density 2 — the artboard at
its own size.

Two defects, both measured off the pixels rather than eyeballed:

1. **The rails were 4dp off the content origin, and jumped on load.** `SearchScreenTvDefaults` had a
   `RailInsetStart` of `ContentInsetStart − FocusRingBleed` on the theory that the `LazyRow` would
   "give the bleed back" as content padding. It does not work that way: content padding *is* the
   inset, so the first card landed at 20dp while the skeleton, which the host insets normally, was
   at 24dp.

   ```
   before  results-cards x=40px (20.0dp)   skeleton x=48px (24.0dp)
   after   results-cards x=48px (24.0dp)   skeleton x=48px (24.0dp)
   ```

   A 4dp horizontal jump every time a search returned, which is the `UiStateHost` skeleton contract
   failing in the other axis. `RailInsetStart` is gone; the row's content padding is
   `ContentInsetStart`, and a `LazyRow`'s content padding already leaves the ring its room because
   it scrolls with the content instead of clipping it.

2. **`Clear all searches` sat flush against the bottom edge**, where its focus ring would have been
   cut. The screen had horizontal insets and no bottom one. `ContentInsetBottom` mirrors
   `ContentInsetEnd` / `FocusBandInset`.

The card **pitch** was right first time: 208px = 104dp = an 80dp card plus `ChipRowSpacing`, in both
the skeleton and the content, which is the measurement that justifies following the token over the
artboard CSS.

**Worth having as a task:** that harness is ~90 lines and is the only way to look at a `:feature:*`
screen until the glue fragment exists. A `renderScreens` task in `:desktopApp` writing PNGs to
`build/` would make it repeatable; it is not screenshot *testing* (nothing compares), but it is the
difference between a screen that compiles and a screen somebody has seen.

### Reported, not changed: `DebounceQuery` cannot be driven by virtual time

`:core:ui`'s `DebounceQuery` hardcodes `.flowOn(Dispatchers.Default)` and a 200 ms `debounce(...)`;
neither is injectable. A `TestDispatcher`'s `advanceTimeBy` therefore does **not** move it — the
pipeline runs on a real dispatcher against the real clock.

Found the hard way: the first draft of `SearchViewModelTest` used `StandardTestDispatcher` +
`advanceTimeBy(1_000)` and the debounced search never fired, which reads exactly like a product bug
and is not one. The suite now waits ~600 ms of real time instead, and says so.

```
Found: `DebounceQuery` hardcodes Dispatchers.Default and 200.milliseconds
       (core/ui/src/commonMain/kotlin/com/lagradost/cloudstream4/compose/Viewmodel.kt)
Suggest: take a CoroutineDispatcher and a Duration as constructor parameters, defaulted to today's
         values, so a test can drive it with virtual time
Why: `.claude/rules/mvi-viewmodel.md` says "inject dispatchers rather than hardcoding ... so the
     ViewModel stays testable"; this helper is the one thing on the search path that cannot be
```

Not changed, per `.claude/rules/no-unsolicited-changes.md` — it is existing `:core:ui` code on the
path of the seven migrated settings screens. One line of each test's cost until someone decides.

Related: `DefaultEffectContainer` uses an **unbuffered** `Channel`, so a collector that attaches after
an effect is posted misses it. Correct for one-shot events and not a defect; worth knowing because it
makes test ordering load-bearing.

### Verified

**16 tests, 0 failures** (7 at step 1, 16 after step 2): with no history a blank query is the idle
prompt and not `Loading` (nothing is in flight, so a skeleton would be a lie); **with** history a
blank query shows it, which is artboard 4c; the source counts reach state; the query echoes into
state immediately; four keystrokes produce **one** quick search and **zero** full searches; `Submit`
runs the full search without waiting; results arrive grouped one rail per provider in first-seen
order; each of the four `SearchOutcome` cases becomes its own `UiState` — with `Partial` keeping its
content and its `9 of 12 answered` notice, and an empty-but-successful search becoming `Empty` with
8c's mono status line rather than `Error`; clearing returns to the history without searching;
activating a recent search runs the full search for it; removing one entry re-reads the list;
clearing all history falls back to the idle prompt with no status line; activating a result posts an
effect rather than navigating. Plus three on `groupByProvider` — one group per provider in first-seen
order, a header count that is the group's own size so it cannot disagree with the rail, and an empty
outcome grouping to nothing rather than one empty rail.

`app/api/classes-jar.api` unchanged — nothing merges into the jar.

**The iOS canary earned its place as a gate on this change.** The first run came back `canary=1`:

```
e: .../SearchContracts.kt:60:2 Unresolved reference 'JvmInline'
> Task :feature:search:compileKotlinIosSimulatorArm64 FAILED
> Task :feature:search:compileKotlinIosArm64 FAILED
```

`@JvmInline` on `SearchResultId` compiles on JVM and Android and **not** on Kotlin/Native, because
`kotlin.jvm.*` is a default import only on JVM. The fix is one named import — and this repo had
already written that down, in `media/model/.../CaptionStyle.kt`:

```kotlin
// kotlin.jvm.* is a default import only on JVM; Kotlin/Native needs it named.
import kotlin.jvm.JvmInline
```

Two things worth keeping from it. **A `value class` in commonMain needs that import**, and nothing
but the canary says so — `abi`, `check` and `extapi` were all green on the broken tree. And a module
only "joins the canary" once `iosCanary` has actually run for it: the first run's module list had 13
entries and `:feature:search` was **not** among them, because its compile had failed rather than been
skipped. Counting the list is not the same as finding your own module in it.

After the import, the second run is `abi=0 check=0 extapi=0 canary=0`, the list has **14** entries and
`:feature:search:iosCanary` is one of them. **The canary is 14 of 14** — up from 12 at the start of the
day, having gained `:feature:extensions` (`KMP-11`) and now this.

### Step 2: the TV screen, and what reading the design cost

Step 1's contracts were derived from **the old code** — `SearchFragment`, `SearchViewModel`. Step 2
is derived from **the drawn design**, and the two disagreed. The cluster is nine artboards across
two files: `design/TV Search.dc.html` 4a–4d and `design/TV Search Suggestions.dc.html` 8a–8e. Four
are built.

**Name the file with the id.** Two clusters are numbered 8: `TV Search Suggestions.dc.html` uses
`8a`–`8e`, and `TV Onboarding Extensions.dc.html` uses `8a`–`8d` for first run and extensions. An
unqualified "artboard 8c" means "Search — no results" in one and "Extensions — browse a repo" in the
other. The 8-series rows below are all the Suggestions file.

| Artboard | What it is | Built |
|---|---|---|
| 4a | Search — results. One rail per provider | yes |
| 4b | Search — partial. 3 of 12 failed, reported in the chip row | yes |
| 4c | Search — history (empty query) | yes, minus the per-row remove |
| 8c | Search — no results | yes |
| 4d | Source picker, 560-wide side panel | no — needs the selection state it commits |
| 8a, 8b | Suggestions while typing, and nothing matches | no — a different content type (one ranked column of dedup'd queries with origins), and the suggestion service is TMDB autocomplete, not the provider seam |
| 8d, 8e | Quick Search side panel, answering and empty | no — streams results into a panel with its own state |

**Three things the artboards require that step 1 did not model**, each a state change rather than a
rendering detail:

1. **A blank query is content, not a void.** 4c is captioned *"What you see before typing"* and
   draws a list of recent searches. Step 1 made it a `UiState.Empty` prompt — a headline, a line of
   body copy and a button. `SearchState.content` is now `UiState<SearchContent>`, where
   `SearchContent` is `Recent` or `Results`, so the six states still apply to both and the screen
   reads one `when` rather than choosing between two fields. `SearchHistoryStore` is the new seam,
   separate from `SearchSource` because a disk read and a dozen network calls fail differently.
2. **Results are grouped per provider.** 4a: *"One rail per provider, so you can see which source
   answered."* A flat list cannot render that, and "which source answered" is the point of a surface
   that fans out over a dozen of them. `groupByProvider()` is a pure function in first-seen order —
   not by count, which would reshuffle rails as late replies arrive and move a focused card out from
   under the user.
3. **The chrome row's `Sources 12 / 48` is load-bearing.** 8c's copy reads *"All nine selected
   sources answered and none of them carry it"*, so the number in the chip and the number in the
   empty state's copy are the same fact and must agree. `SearchSources` is on the state and
   `SearchSource.sources()` supplies it, because only `:app` knows the user's selection.

### `UiState.Empty` gained a `detail`, and three artboards asked for it

`UiState.Error` carried a machine-readable `detail` for the mono line; `Empty` did not. But 8c sets
`9 QUERIED · 9 ANSWERED · 0 FAILED · 0 RESULTS` under "No results", 8b sets
`SUGGESTIONS ANSWERED · 0 MATCHES`, and 8e sets `12 OF 12 ANSWERED · 0 FAILED`. An empty result is
not a failure, but it is still a finding, and the arithmetic is what tells a user the search was
real.

So `UiState.Empty(prompt, detail: String? = null)`, and `UiStateHost` passes it exactly as it does
for `Error`. `StateView` already took a `detail` parameter, so nothing in the renderer changed.
Measured as safe: `grep -rn "UiState.Empty"` outside this feature returns **one** hit, `UiStateHost`
itself — the state had no other construction site, so no existing surface renders differently.
`:core:designsystem` is not ABI-validated (no `core/designsystem/api/`), so no dump moved.

`SearchPrompts` now returns the state rather than the prompt — `noResults(query, queried, onRetry):
UiState.Empty` — so the copy and the status line that quote the same number come from one call and
cannot drift. That is the defect the suggestions cluster was re-solved twice over: *"The status line
and the rows have to agree, and they did not."*

### The chassis was already in the design system, which is the payoff

Every number the screen needed was a `CloudStreamDimens` token, verified against the artboard CSS at
`artboard px / 2 = dp`:

| Artboard CSS | dp | Token |
|---|---|---|
| `.field { height: 96px }`, chrome y56…152 | 48, 28…76 | `ChromeRowHeight`, `ChromeRowTop`, `ChromeRowBottom` |
| `.chipcell { height: 96px }`, `.chip { height: 48px }` | 48, 24 | `ChipFocusCell`, `ChipVisualHeight` |
| `.rhd { height: 56px; gap: 16px }` | 28, 8 | `RailHeaderDefaults.Height`, `.TitleCountGap` |
| `.row { gap: 24px }` (cards) | 12 | `MinFocusGap` |
| `.seeall { width: 160px; height: 240px }` | 80×120 | `PosterRung.Medium` |
| `.lrow { height: 96px; gap: 16px; padding: 0 16px }` | 48, 8, 8 | `ListRowHeight`, `ListRowPadding` |
| `.field.foc { outline: 4px; outline-offset: 4px }` | 2, 2 | `FocusRingStroke`, `FocusRingOffset` |
| rail pitch 352 | 176 | header 28 + `LiftedRowClearance` 16 + cards 120 + `MinFocusGap` 12 |
| content x 200…1864 | 100…932 | `ContentOriginX`, `ContentEndX` |

Only three numbers are written in this feature, all in `SearchScreenTvDefaults` with the token or
artboard each mirrors: `ContentRegionTop` (148dp, the y every artboard mounts its one content region
at), `ContentInsetStart` (`ContentOriginX − NavRailWidth`, because the nav rail is the shell's) and
`RailInsetStart` (`ContentInsetStart − FocusRingBleed`, so a `LazyRow` can give the bleed back as
content padding and still put the first card's box on the content origin).

### Reported, not changed

```
Found: the design file contradicts itself on chip-row spacing. Its CSS is `.chiprow { gap: 24px }`
       and `.failgrp { gap: 24px }` — 12dp at the design system's own artboard-px/2 rule, which
       every other mapping in the table above follows — while its own prose, cause 6, says "In
       Compose that is wrapContentWidth in a Row with 24dp spacing". CloudStreamDimens.ChipRowSpacing
       is 24dp, i.e. it follows the prose; SkeletonRail uses the same token for the gap between rail
       cards and documents it as such.
Suggest: decide which is the contract. If the prose is right the CSS should read `gap: 48px` and
       nothing in code changes; if the CSS is right then ChipRowSpacing is 12dp (= MinFocusGap),
       ChipRow's KDoc claim that chips "sit at twice the minimum" is wrong, and SkeletonRail and
       this screen move with the token.
Why: a 2x spacing error is invisible in isolation and obvious beside the artboard, and ChipRow is
       shared — six more verticals will copy whichever answer stands.
```

**This screen followed the design system, not the CSS**, and spaces rail cards at `ChipRowSpacing`.
The deciding argument is not which number is prettier: `SkeletonRail` already spaces its cards at
`ChipRowSpacing`, and `UiStateHost`'s KDoc warns that a skeleton shaped unlike the content makes the
screen jump when the content lands. A rail at 12dp behind a skeleton at 24dp is that jump. The first
measurement of this went into the task file as a flat "the token is 2× the artboard" before the
file's own prose had been read — corrected here, and a reminder that a design file is a document with
a body as well as a stylesheet.

```
Found: `.claude/rules/dimension-scale.md` says `FocusRingStroke` is 4dp ("a weight that happens to
       land on [the step]"). The token is 2.dp, and the artboards agree with the token
       (`outline: 4px` at px/2). The rule file also claims Hairline is "the only off-step value in
       the theme", but FocusRingStroke 2, FocusRingOffset 2 and FocusRingBleed 4 are all strokes.
Suggest: correct the rule file to match the theme.
Why: the rule file is authoritative for reviewers, so a reviewer following it would reject a correct
       2dp ring.
```

```
Found: docs/plans/screen-implementation-handover.md §7 lists "Search suggestions, no-results and the
       Quick Search panel" under "TV — specced but never drawn". They are drawn:
       design/TV Search Suggestions.dc.html holds 8a–8e and reports "0 off-grid, 0 clipped,
       0 overflowing, 0 band violations across 312 boxes".
Suggest: move those three from "specced but never drawn" to "drawn".
Why: §7 is what a session reads to decide whether it may implement a screen, and this entry says no
       when the answer is yes.
```

Also unchanged and already known: `:core:designsystem`'s `ProgressRule` is 2dp on a `bg`@0.6 track
while the artboard's `.cardbar` is `height: 8px` (4dp) on `rgba(255,255,255,0.18)`, and cards do not
use the primitive. That is duplication #4 in the screen handover's §8 and one of the two numbers is
wrong; this feature uses `MediaCardState.progress` and so inherits whichever the card draws.

### What is deliberately missing from the TV screen, and why

- **Touch and desktop screens.** `docs/plans/screen-implementation-handover.md` §7: *"Mobile —
  nothing. Desktop — nothing."* Phase B of that document is explicit: *"Before implementing any
  screen, its design must exist and must have been measured … If the screen you are about to build
  has no artboard, draw it first — do not improvise it in Compose."* So recipe step 2 is **complete
  for TV and blocked on design for the other two form factors** — not skipped, and not improvised.
- **The per-row remove on 4c.** It needs either a glyph (forbidden while `DEC-11` was unanswered; answered 2026-11-02) or a
  second focus stop nested inside a row that is itself a focus stop, which the artboard does not draw
  in enough detail. `SearchAction.RecentRemoved` exists and is tested; nothing emits it yet.
- **`See all` on a rail.** 4a and 4b draw one. It implies a per-provider destination that does not
  exist, and `SearchEffect` has nowhere to send it; the rail is a `LazyRow` that scrolls through the
  whole group instead, so no information is lost. Raise it when there is a destination.
- **Filter chips.** 8c's copy depends on them — *"Movies only — your type filter is narrowing the
  search"*, with an action that clears the provider **and** the type filter — so they arrive with the
  state that models them. The chip row is mounted regardless, because it is a band of the chassis:
  a row that appears and disappears moves the content region under the user's focus.
- **A magnifier in the field.** `.claude/rules/icons.md`, `DEC-11`. The placeholder carries the
  meaning instead, which is what artboard 4c's own *"Search all sources"* does. `DEC-11` has since been
  answered and the desktop field has one; this TV screen has not been revisited.

### No screenshot tests, and it is not a choice

`.claude/rules/screenshot-testing.md` asks every feature for cases on its load-bearing states.
`com.android.compose.screenshot:0.0.1-alpha16` renders nothing on this toolchain **and does not
support KMP modules at all** — applied to a KMP module it contributes zero tasks. `:feature:search`
is a KMP module, so there is no suite that could hold them. The eight previews in
`tv/SearchScreenTvPreviews.kt` cover exactly the cases that would be recorded the day a working
plugin or Roborazzi lands (`DEC-10`), pinned to 960×540 dp so a diff shows what moved rather than a
resize.

There are also **no composable tests**: `compose-ui-test` is not in the catalog, and adding it is a
dependency decision (`.claude/rules/dependency-security.md`). What is tested is everything that is
not a layout — the state machine and the grouping.

### Next, in order

- [x] ~~Screens per form factor in commonMain (recipe step 2)~~ — **TV done**; touch and desktop
      blocked on design (Phase B), 4d / 8a / 8b / 8d / 8e blocked on the state they commit
- [ ] `SearchSource` and `SearchHistoryStore` implementations in `:app` over `APIRepository` and
      `DataStoreHelper`, mapping `SearchResponse` → `SearchResultItem`, plus `SearchPrompts` and
      `SearchTvCopy` from `stringResource`
- [ ] Glue `Fragment` (step 3) and **one** nav node repointed (step 4); the old fragment stays as the
      rollback lever
- [ ] Delete the old fragment and layouts a release later (step 5) — that removes
      `com.lagradost.cloudstream3` classes from `classes.jar`, so it needs the javap tripwire output
      and owner sign-off

---

## KMP-13 — a JVM `ExtensionRuntime` for `:desktopApp` · **measured 2026-10-15**

**Status:** **built 2026-10-15** · `JvmExtensionRuntime` + the three `ExtensionListing` fields + the
desktop bootstrap, 18 new tests · **no gate run** (this branch may not run Gradle — see §7) ·
`:app`'s `PluginManager` is not edited and not ported · **`classes.jar` gains and loses nothing**

Desktop is the primary target and has **no providers at all**. `APIHolder.apis` starts empty
(`MainAPI.kt:131`) and is only ever filled by `addPluginMapping` (`MainAPI.kt:134-139`), which only
`BasePlugin.registerMainAPI` calls (`BasePlugin.kt:20-25`), which only a loaded plugin calls. Nothing
loads one off Android, so `DesktopSearchSource` queries nothing and answers
`NO_SOURCES · 0 INSTALLED` (`DesktopSearchSource.kt:92-93`). This entry is plan row 17's remaining
half for the **JVM** side, not the Android side — plan §4.3 and vertical 6 name only a
`PathClassLoader` runtime (`docs/plans/kmp-migration.md:268`, `:656`).

### 1. The crux: a `.cs3` is dex-only, so no JVM `ClassLoader` can load one

Checked two ways, because the whole plan turns on it.

| Measurement | Result |
|---|---|
| **Read the producer.** Upstream `recloudstream/gradle@master`, `tasks/Tasks.kt:143-171`: `make` is a plain `Zip` that takes `manifest.json` (`:152`) and `compileDex`'s `classes.dex` (`:153`, output declared `:49`), plus `res.apk`'s contents minus `AndroidManifest.xml` only when `requiresResources` (`:155-160`) | no `.class` entry is ever added |
| **Open a real artefact.** `YoutubeProvider.cs3` from `recloudstream/extensions@builds` (13,152 B), `unzip -l` | exactly two entries: `manifest.json` 112 B, `classes.dex` 28,800 B |

So the obvious plan — point the existing flow at a JVM loader — **is dead**, and that is the finding
this entry exists to record. `PathClassLoader` is not an inconvenience in the loader; the artefact
itself is Dalvik bytecode. A JVM runtime over the `.cs3` would need a dex-to-JVM converter, which is
not a dependency anyone should be asked to approve for this. **Superseded by `KMP-14`:** the owner
approved one on 2026-11-02.

### 2. What makes it possible anyway: a second, plain-JVM artefact already exists

Cross-platform extensions publish a `.jar` beside the `.cs3`, and the index already carries it.

- `CompilePluginJarTask` copies `createFullJarDebug`'s jar to `build/<name>.jar`, gated on
  `isCrossPlatform` (`Tasks.kt:95-107`, `CompilePluginJarTask.kt:33-39`).
- `WriteCacheEntryTask` writes `jarUrl` / `jarHash` / `jarFileSize`, non-null exactly when that jar
  exists (`WriteCacheEntryTask.kt:51`, `:72-74`; `entities/PluginEntry.kt:19-22`). Plan §10 O2 option
  (c) already noted this (`kmp-migration.md:771`); what was missing is whether it is real in a
  published index.
- It is: `recloudstream/extensions@builds/plugins.json` has `jarUrl`, `jarHash` and `jarFileSize` on
  **all 5** of its entries.
- `YoutubeProvider.jar` (46,472 B), `unzip -l`: 7 `.class` entries and one
  `META-INF/*.kotlin_module`. No dex, and **no `META-INF/MANIFEST.MF`**.
- `javap -c` over every class in it: each `com/lagradost/*` constant-pool reference resolves inside
  `:library` — `MainAPI`, `MainAPIKt`, `SearchResponse`, `SearchResponseList`, `TvType`, `Episode`,
  `HomePageResponse`, `plugins/BasePlugin`, `utils/ExtractorApiKt`, all present in
  `library/api/jvm/library.api` — and there are **zero `android/` references**. `:desktopApp` already
  depends on `:library` (`desktopApp/build.gradle.kts`), so the classpath a loaded provider needs is
  already on the host. Class-file major version is 52 (Java 8), below our `jvmTarget`.
- The plugin class is findable without the `.cs3`: `@CloudstreamPlugin` declares no `@Retention`
  (`library/src/commonMain/.../plugins/CloudstreamPlugin.kt`), so Kotlin's `RUNTIME` default applies,
  and `javap -v recloudstream/YoutubePlugin.class` shows it under `RuntimeVisibleAnnotations`.
  `Class.forName(name, false, loader)` over the jar's entries reads it **without running static
  initialisers**. The `.cs3`'s `manifest.json` answers the same question
  (`{"requiresResources":false,"version":1,"pluginClassName":"recloudstream.YoutubePlugin",…}`, and
  `java.util.zip` can read it even though the dex beside it is unloadable), but that costs a second
  download for a fact the jar already carries.

**The cost is coverage, and it is the number to put in the PR.** Across the first 14 of the 31
repositories listed in `recloudstream/cs-repos`' `repos-db.json` (first three plugin lists of each),
**5 of 235 catalogue entries carry a `jarUrl`, and all 5 are the official repository.** The 86-entry Phisher index does not have the key
at all. So a desktop runtime starts with five providers and grows only as authors set
`isCrossPlatform = true`. Honest, and still infinitely better than zero.

### 3. What is Android-only in today's loader

Every one of these is why the runtime is **new code beside the seam**, not a port.

| Android-only | Where |
|---|---|
| `dalvik.system.PathClassLoader` — the import, the `classLoaders` map's key type, the construction | `PluginManager.kt:61`, `:201-202`, `:611` |
| `Context` threaded through every entry point: `context.classLoader`, `filesDir`, `getExternalFilesDir` | `:593`, `:611`, `:169`, `:754`, `:530` |
| Extension resources by reflection: `AssetManager.addAssetPath` + `Resources(assets, displayMetrics, configuration)` | `:645-659`, and `Plugin.resources` / `load(Context)` / `openSettings` at `Plugin.kt:16,34,39` |
| OAT-file cleanup to force a dex recompile after an app update | `:164-175`, called from `MainActivity.kt:1228` |
| Android 14+ dex handling: `file.setReadOnly()`, and the copy from external storage into the app-specific dir that exists only because of it | `:600-609`, `:527-556` |
| Notification channel and update notifications | `:73-75`, `:901-918`, `:920-966` |
| `Activity` / `FragmentActivity` in the four `___DO_NOT_CALL_FROM_A_PLUGIN_*` entry points | `:274`, `:352`, `:485`, `:506`, `:830` |
| `Environment.getExternalStorageDirectory()` for the local-plugin folder and the safe-mode file | `:186-189`, `:579-588` |
| `showToast` + `R.string.plugin_load_fail` on a load failure | `:679-683` |
| `getKey` / `setKey` persistence through `CloudStreamApp` | `:26-28`, used `:130`, `:143`, `:179`, `:183` |
| `context.cacheDir` for the download temp file; `context!!.getString(R.string.jsdelivr_proxy_key)` in the URL rewriter | `RepositoryManager.kt:204`, `:126` |

Already portable, and worth not rewriting: `RepositoryManager.sha256` is plain
`java.security.MessageDigest` (`:107-122`) and produces the **same** `sha256-<lowercase hex>` form the
producer writes (`WriteCacheEntryTask.kt:85-97`) — two independent implementations that already
agree, so `jarHash` can be verified byte-for-byte. `BasePlugin` itself is `:library` `commonMain` and
needs nothing Android: `registerMainAPI` (`:20-25`), `registerExtractorAPI` (`:31-35`), `load()`
(`:48`), `filename` (`:62`). `Plugin` — the Android subclass with `Context` — is in `:app`, and
`EnsureJarCompatibilityTask` means a cross-platform jar cannot reference it.

### 4. The shape of the work

| | |
|---|---|
| Module | `:feature:extensions`, **`src/jvmMain`**. `cloudstream.kmp.library` already declares `jvm()` (`cloudstream.kmp.library.gradle.kts:74`), so the source set needs no new convention plugin and no `settings.gradle.kts` edit |
| Build-file delta | one `jvmMain.dependencies` block: `project(":library")` (for `BasePlugin`, `APIHolder`, `extractorApis`, `app`) and `libs.kotlinx.coroutines.core`. **No new catalog entry** — `java.util.zip`, `java.net`, `URLClassLoader` and `MessageDigest` are the JDK |
| What it implements | `ExtensionRuntime` (`ExtensionRuntime.kt:28-70`) as, say, `JvmExtensionRuntime`: `install` writes the jar bytes under a host-provided directory and returns `InstalledExtension(location = <abs path>)` — `location` is opaque by contract (`ExtensionInstall.kt:9-18`); `load` opens a `URLClassLoader` whose **parent is this module's own loader** (so `BasePlugin` and `MainAPI` have one identity), finds the `@CloudstreamPlugin` class, instantiates it, sets `filename` *before* `load()` because `registerMainAPI` stamps `sourcePlugin` from it (`BasePlugin.kt:22`); `unload` removes that `filename`'s entries from `APIHolder.apis` (`MainAPI.kt:141-146`), `APIHolder.allProviders` and `extractorApis`, then closes the loader — `unload`'s KDoc already concedes that means "its providers no longer answer", not "its code is gone" (`ExtensionRuntime.kt:62-66`); `uninstall` unloads and deletes |
| Also in scope | three **additive** nullable fields on `ExtensionListing` (`ExtensionIndex.kt:78-112`) — `jarUrl`, `jarHash`, `jarFileSize` — mirroring `PluginEntry.kt:19-22`. The module stays annotation-free and parser-free, as its KDoc requires. Desktop then becomes the first host that can return `ExtensionVerification.Verified` rather than `Skipped`, which `ExtensionInstall.kt:69-79` records as something `:app` has never done |
| Must not touch | `PluginManager`, `RepositoryManager`, `Plugin`, `PluginData`, `SitePlugin`, `PluginWrapper` — all published (`app/api/classes-jar.api:15763-16073`; `PluginManager`'s own surface is `:15875-15910`, where every entry point that loads, downloads or reloads a plugin takes `Context` or `Activity` — the state accessors (`getPlugins`, `isSafeMode`, `unloadPlugin(String)`, `deletePlugin(File)`) do not). **`PluginManager` needs no edit at all**: the seam is an `interface`, not an `expect` (`ExtensionRuntime.kt:6-10`), so a second implementation is an addition. `:app` keeps its own loader until someone asks for the adapter in `KMP-11`'s open half |
| Not in scope | the catalogue UI, `:app`'s adapter, iOS (§10 O2 is open), and `Plugin`-style extensions with Android resources or settings (§10 O5, `kmp-migration.md:807`) |

### 5. What it costs elsewhere

- **`classes.jar`: nothing, gains or loses.** `makeJar` merges a candidate only when it has a
  `src/<sourceSet>/{kotlin,java}/com/lagradost/cloudstream3` directory
  (`cloudstream.extension-api-jar.gradle.kts:18-21`); this code is `com.lagradost.cloudstream4.…`.
  `:feature:search` is already on `:app`'s classpath (`app/build.gradle.kts:310`) and is not merged for
  exactly that reason (`KMP-12`); `:feature:extensions` is not even on it. No sentinel, no dump line, no
  `$stable` exposure (`KMP-09` does not apply — nothing moves out of a Compose module).
- **iOS canary: still 14 of 14.** The `:library` edge is in `jvmMain`, and `IOS_CANARY_EXEMPT` is
  about `commonMain` only (`cloudstream.kmp.library.gradle.kts:45-48`, `:60-67`); `jvmMain` is never
  compiled for Native. This is the fourth time choosing the source set has avoided an exemption —
  `KMP-05`, `KMP-11`, `KMP-12`, here.
- **No new dependency.** `app` (NiceHttp) is initialised in `:library` itself
  (`library/src/commonMain/.../MainActivity.kt:30-32`), so fetching the jar bytes needs nothing added;
  the seam takes bytes, so the fetch stays outside it either way (`ExtensionRuntime.kt:22-23`).

### 6. Two traps to know before writing it

- **`WebViewResolver` is a no-op on the JVM.** `intercept` proceeds unchanged
  (`WebViewResolver.jvm.kt:31-34`) and `resolveUsingWebView(request, …)` is
  `TODO("Not yet implemented")` (`:71`), i.e. it **throws `NotImplementedError`**. A provider that
  needs it will not fail gracefully. Loading providers is therefore not the same as them working, and
  the PR should say which of the five actually return results.
- **The cross-platform guarantee is thinner than it reads.** `EnsureJarCompatibilityTask` runs
  `jdeps --print-module-deps` and greps the output for `"android."`, with `isIgnoreExitValue = true`;
  when `jdeps` produces nothing it only **warns** (`EnsureJarCompatibilityTask.kt:35-52`). So treat
  `NoClassDefFoundError` at load as an expected outcome with a reported `ExtensionState.Failed`, not
  as an invariant violation.

### 7. What was built, and what each file is for

| File | Lines | What it is |
|---|---|---|
| `feature/extensions/src/jvmMain/.../JvmExtensionRuntime.kt` | 298 | The runtime. `install` (write + move), `load` (`URLClassLoader` + discovery + `filename` then `load()`), `unload` (purge + close), `uninstall`, `loaded` |
| `.../ExtensionHash.kt` | 123 | `sha256-<lowercase hex>` and the three `ExtensionVerification` outcomes. **Public**, because the seam deliberately makes verification the host's step |
| `.../ExtensionJar.kt` | 102 | `internal`. Zip entries → class names → the one `@CloudstreamPlugin` class, resolved with `initialize = false` |
| `.../ProviderRegistry.kt` | 54 | `internal`. The three `:library` registries an unload has to clear, with the lock discipline `AtomicList` requires |
| `desktopApp/.../extensions/DesktopExtensionBootstrap.kt` | 259 | Verifies and loads every `*.jar` in `~/.cloudstream4/extensions` before the window opens |
| `desktopApp/.../extensions/DesktopExtensionPins.kt` | 88 | The 5 official jars' hashes, measured from the index. Not a catalogue |

Plus: three additive nullable fields on `ExtensionListing` (`jarUrl`, `jarHash`,
`jarFileSizeBytes`), one `jvmMain.dependencies` block (`:library` + coroutines, **no new catalogue
entry**), `implementation(project(":feature:extensions"))` in `:desktopApp`, and `Main.kt` +
`DesktopSearchSource.kt` KDoc brought in line.

**Three decisions the design entry left open, taken here:**

- **A mismatch never reaches disk, and the runtime recomputes rather than trusting the verdict.**
  `install` refuses an `ExtensionVerification.HashMismatch`, and when `listing.jarHash` is present it
  re-runs the comparison itself. The seam says verification is the caller's policy; this declines to
  make "did anyone actually check?" a property of the call site. One SHA-256 over ≤126 KB.
- **`internalName` is whitelisted before it becomes a path**, and `load`/`uninstall` refuse a
  `location` outside the managed directory. Both are third-party index strings; `../` otherwise turns
  `load` into "run any jar on this disk".
- **Two annotated classes in one jar is a refusal, not a choice.** `:app` never faces this — its
  `manifest.json` names one class — and guessing which arbitrary code to run is not a guess worth
  making.

**Why there is no catalogue fetch.** Reading the index at runtime needs a JSON parser, and desktop
has none: every dependency `:library` declares is `implementation`
(`library/build.gradle.kts:55-69`), so neither Jackson nor `kotlinx.serialization.json` is on a
consumer's compile classpath. Adding one is a dependency decision, and "launching CloudStream may
download and execute third-party code unasked" is a product decision. The bootstrap prints `curl`
lines instead and a human performs the install.

**`ExtensionContractsTest` moved `jvmTest` → `commonTest` (`INF-04`).** It is pure Kotlin, so the
`-Pcloudstream.iosTest=true` gate now has a suite to run in this module instead of printing the skip
line its KDoc describes. One test name lost a comma, which Kotlin/Native rejects in a backticked
identifier and the JVM accepts.

### Next, in order

- [ ] **Run the four gates.** `abi`, `check`, `extapi`, `canary` — nothing here is verified by
      execution, because the branch that wrote it may not run Gradle. The dump must be **unchanged**:
      this code is `com.lagradost.cloudstream4.…`, and `makeJar` merges a module only when it has a
      `src/<sourceSet>/{kotlin,java}/com/lagradost/cloudstream3` directory
      (`cloudstream.extension-api-jar.gradle.kts:18-21`)
- [ ] **Run `iosSimulatorTest -Pcloudstream.iosTest=true`.** The moved suite is this module's first
      workload for the gate — not the gate's first, since `:feature:search`'s `SearchContentTest`
      already moved to `commonTest`; if KGP's configuration cache misses the new `commonTest`
      directory, re-run with `--no-configuration-cache` (the convention plugin's KDoc flags this
      exact uncertainty)
- [ ] **Run the desktop app against the five official jars** and report, per provider: did `jarHash`
      verify, did the class load, and did a search return results. §6's `WebViewResolver` trap says
      loading is not the same as working — `resolveUsingWebView` is `TODO()` on the JVM and **throws**
- [ ] Confirm `jvmTest` sees `jvmMain`'s `implementation(project(":library"))` through the
      main↔test compilation association. If it does not, the fixture plugin will not compile and the
      block moves (or `jvmTest` gets its own `:library` line)
- [ ] A `docs/HANDOVER.md` row for this, which this branch did not touch

---

## KMP-14 — sandboxed extensions on desktop, and `.cs3` conversion · **measured 2026-11-02**

**Status:** done · #82 · owner approved
`de.femtopedia.dex2jar:dex-translator` 2.4.38 the same day, reversing `KMP-13` §1's "not a dependency
anyone should be asked to approve", because 230 of 235 surveyed extensions publish no jar.

`KMP-13` ran extensions **inside the app's JVM**, with the user's whole account. The owner asked for
plugins to run in a sandbox, with security checks, converted where needed. `:desktopApp` no longer
loads a plugin class at all. `JvmExtensionRuntime` is now used only *inside* a host.

### The pipeline, per `*.jar` / `*.cs3`, every launch

| | Step | Runs in | What it can do |
|---|---|---|---|
| 1 | Hash, against a `.sha256` sidecar or `DesktopExtensionPins`. Mismatch refuses | app | |
| 2 | Copy, or convert with dex2jar, to `.store/<sha256>.jar` | sandbox: **no network**, writes only `.store` | parses hostile bytes, runs no plugin code |
| 3 | `ExtensionScanner`: constant pool, native methods, zip structure | app, so a plugin cannot forge its verdict | never class-loads |
| 4 | One host process per extension, under Seatbelt | sandbox: network, writes only its own `.sandbox/<name>` | runs plugin code |

`SandboxedProvider` in `APIHolder` proxies across a hand-written frame protocol, so `DesktopSearchSource`
needed no change. **Java serialization across this boundary would hand a plugin code execution in the
app**; the protocol can express only strings, numbers and lists of fixed records. Replies are cleaned
of control characters, capped, and posters are limited to http(s) on public hosts.

No sandbox available (anything but macOS today) means **nothing loads**. There is no in-process fallback.

### What was measured, and how

**Enforcement.** `SandboxEnforcementTest` runs a real child under the real policy, with a positive control
beside each denial. Denied: reading outside its directory including the real `$HOME`, writing outside
including its own jar, starting any process, connecting to localhost. Its environment has none of the
user's variables. **Checked a second way by mutation:** with `(deny default)` changed to `(allow default)`,
5 of the 11 tests fail, exactly the file and process ones. The localhost rule is an explicit deny and
holds regardless, as intended.

**Conversion is behaviour-preserving, on the only evidence available.** _Wrong for the wider catalogue: `KMP-18` found two dex2jar defects that broke 30 of 53 and 3 of 53 sampled plugins._ The five official extensions
publish both artefacts. Run through the packaged app, `.cs3` and author's `.jar` gave identical
per-provider outcomes in `--check-extensions`.

**The five official extensions, query `nasa`, packaged `.app`, converted from `.cs3`:**

| Provider | Search | Open | Links | Why |
|---|---|---|---|---|
| YouTube | 17 | yes | **14 links**, 21 subtitles | needed `NewPipe.init` in the host |
| Dailymotion | 26 | yes | 0 | `:library`'s extractor throws: `subtitles.data` is now `[]` where it expects an object. See below |
| Internet Archive | 26 | yes | 0 for that (audio) item | the first hit was audio on both queries tried; a video item is not yet measured |
| Twitch | 38 | no | | twitchtracker.com page has no `#app-title` for this JVM. Cause not established |
| Invidious | 0 | | | the default instance answers `403` (curl agrees). Not ours |

**Scanner and converter across the wider catalogue, with no plugin code run** (`--scan-extensions`, 90 files
sampled from 34 repositories of 543 entries, static only):

- 89 of 89 real files convert. The 90th was a 0-byte download.
- 88 clean of blocks, **1 blocked**, for referencing `dalvik/system` (dynamic code loading). 82 have no warnings.
- By class reference, **50 of 89 now resolve completely** against the host, up from 13 before the shims.
- 72 of 89 extend `Plugin`, so nothing would have loaded without that shim. 15 of 89 are
  `requiresResources`, and will fail wherever they touch `R`.

**Of everything sampled, only the five official extensions and test fixtures were ever executed.** "Resolves" is a linkage count, not a
claim that any of them works; the class-level number is a floor because JVM linking is lazy and settings
UI classes fail only if opened.

### Limits a reader should not have to discover

- **Static scanning is triage.** It is defeated by anything obfuscated. The sandbox is the boundary.
- **Network is unrestricted to the public internet.** A plugin can send anything it can see, which is its
  own directory. Seatbelt can exclude localhost but not LAN ranges, so **a plugin can reach the local
  network**. There is no per-plugin host allow-list.
- **No CPU or disk quota.** Heap is capped at 512 MB and `ActiveProcessorCount=4`; a host can still spin
  and can fill its directory. Calls time out at 60 s.
- **Posters are fetched and decoded by the app, outside the sandbox.** Only public addresses are connected to,
  checked at connection time on every redirect hop (`posterHttpClient`), but a malformed image still reaches
  Skia and a poster request tells a site the user's IP address.
- **`sandbox-exec` is deprecated by Apple.** It works here (Darwin 27) and has no replacement for an
  unsigned app. A real distribution would look at App Sandbox with XPC.
- **Cost:** 85–130 MB RSS per host, one per extension, all started before the window opens.
- `BuildKonfig` API keys are on the classpath a plugin can read, as they are on Android.

### Found on the way and not fixed

```
Found: Dailymotion extractor fails on a current API response (`subtitles.data` is `[]`) in :library's extractors
Suggest: accept the array form
Why: every provider using it returns 0 links, on Android too if the response is the same. Extractors are extension ABI, so this wants its own change

Found: 7 plurals use bare %d in core/strings values/strings.xml (`search_tv_result_count`, `search_tv_recent_count`, `downloads_active`, `downloads_queued`, `links_hidden`, `search_state_no_results_body`, `search_state_partial_headline`), in 27 files
Suggest: author them as %1$d. Compose Resources substitutes only positional arguments, so they render literally off Android
Why: the fix moves core/strings/oracle-baseline, so it is the owner's call. DesktopSearchCopy works around the two it shows
```

### What is not done

- **Playback and a detail screen.** Opening a result still prints to stdout: no desktop artboard exists, and
  `screen-implementation-handover.md` §7 Phase B forbids improvising one. `SandboxedProvider.details` and
  `.links` are ready for it.
- **A catalogue UI.** Installing is still "put a file in the directory".
- **The live window was not driven.** On 2026-11-02 macOS refused screen capture and reported no frontmost
  process, consistent with a locked session. The window was confirmed to open at 960x540 and stay alive, and
  the screen was verified with `ImageComposeScene` over the real ViewModel and real hosts. **Keyboard input
  in the real window is unverified.**
- The scan has no user override, and `requiresResources` gets no resource table.
- `:feature:extensions:sandbox` has no conversion test: no dex fixture can be committed without a binary.
  Done by hand with d8: a `Plugin`-style fixture dexed, converted and answering in the packaged app.

### Gates, 2026-11-02, local on this tree

`abi=0 check=0 extapi=0 canary=0`, and `:desktopApp:packageDmg` exit 0. The `app/api` dump is unchanged.

### Running it

    CloudStream --check-extensions "query" [--verbose]   # load, search, open, links, per provider
    CloudStream --scan-extensions                        # convert and scan; runs no plugin code

---

## KMP-15 — desktop detail page, source list and embedded playback · **measured 2026-11-02**

**Status:** done · #83. Owner approved
`uk.co.caprica:vlcj` 4.12.1 (GPL-3.0, fits this GPL-3 project; brings JNA; needs VLC installed) the same day,
choosing embedded playback over handing off to VLC. A 5.x exists only as milestones, so 4.x is pinned.

Search results now open `:feature:detail`'s TV page (artboards 7a/7b), play goes to a source list (reduced 7c),
and a source plays in a Compose screen (reduced 7d) through libVLC.

### Measured

An offscreen `ImageComposeScene` drove the real ViewModels, sandboxed YouTube plugin and libVLC: real 1080p frames
drawn on screen, with position advancing. A headless vlcj run first gave 741 frames in 30 s with varying pixel data.
Skia's `installPixels` copies its array once, so the first version drew solid white while VLC reported playing; a
status check alone would have passed it.

**Live window input is still unverified**, as in `KMP-14`, and so is audible sound: audio tracks are attached with
`:input-slave`, and no one has listened to them.

### Review, 2026-11-02

Three reviewers and five adversarial verifiers over this diff. Fixed, each with a test where the behaviour can
run without a window:

- **`#` in an audio URL** passed the URL check as a fragment, then libVLC split `:input-slave` on it and opened the
  rest, including local files (measured on 3.0.23). Refused in `SandboxPolicy.isPublicAudioUrl` and in `VlcPlayer`.
- **Padding past the check length hid the host.** `http://public:000…80@127.0.0.1/` read as `public` after truncation
  to 2048, while VLC connected to loopback (measured). The exact string is now checked, and userinfo is refused.
- **Keyboard died when the HUD hid** with focus on one of its buttons. From Compose sources; not reproduced live.
- Frame size capped at 3840x2160, one plugin can no longer claim another's provider name, a stale links result cannot
  reopen a dismissed list, and back cannot empty the stack.

Of 26 findings, the top 5 were adversarially verified. All 5 held, 4 of them at lower severity than first reported.
The other 21 were triaged by hand and not independently verified; those fixed were checked against the code then.

### Limits a reader should not have to discover

- **libVLC parses third-party media in the app process, outside the sandbox.** This is the largest remaining gap in
  the sandbox story. A memory bug in a demuxer is code execution with the user's account.
- **The URL check covers only the URL it is given.** libVLC can still follow a redirect, see a name change address,
  and **fetch whatever a public HLS playlist lists, including LAN addresses** (measured: blind GETs). Fixing this needs
  a local filtering proxy for stream traffic.
- **Only referer and user agent reach libVLC.** VLC 3 has no general header option, so a link needing other headers fails.
- Link filtering does blocking DNS per link and stops only between links.
- The frame cap's downscaling path has never run against a stream that large.

### Not built

- Departures from the artboards are listed in `DetailScreenTv`'s KDoc: text buttons (icon pack undecided), no nav rail,
  bookmark, cast rail or More like this, episodes in the cast slot, no live per-extractor rows in 7c.
- Source and audio selectors (7e), resume, watch progress, focus restored to the activated item on back. Subtitles came in `KMP-25`.
- **Screenshot cases.** `DetailScreenTvPreviews` holds 7 pinned states. `:core:screenshot` cannot render on this
  toolchain (`DEC-10`), so nothing guards them yet. No preview exists for the player, which needs libVLC.
- Function-size rule: `PlayerScreen` and `TitlePage` exceed the 40-line limit. Reported, not split.

### Found and not fixed

```
Found: search_state and detail share `source_error` copy, so a failed title reads "Source error / Error Loading Links"
Suggest: author title-specific strings
Why: a new key moves core/strings/oracle-baseline, which is the owner's call
```

---

## KMP-16 — libVLC moves out of the app process, into its own sandbox · **measured 2026-11-02**

**Status:** done · #85 · closes the largest limit `KMP-15` listed

libVLC decodes media a third-party plugin chose, so a decoder bug is code execution. It now runs in a
`--media-host` child of the launcher, one per stream, under `SeatbeltProfile` plus `mediaHostRules`.
There is no unsandboxed fallback: no `sandbox-exec` or no `/Applications/VLC.app` means
`PlayerUnavailable`.

### What was measured, on the packaged app

| Check | Result |
|---|---|
| `vmmap` of the app process / the host, grep `libvlc` | **0 / 8** mappings |
| Public HLS stream | frames at 1902x1080, position advancing, `failed=false` |
| YouTube plugin's first link, 2160p VP9 with separate audio | 251 frames over 8.1 s of position, 1905x1080 |
| libVLC's log, `-vv`, temporary | `analog output successfully opened`, 0 audio errors |
| Host CPU, HLS H.264 | about 10% |
| Mutating `mediaHostRules` to `(allow file-read*)` | the policy test fails, exit 1 |

**Sound has not been heard by a person.** The log says the output opened.

Frame counts alone would have passed with silent audio. Three denials were found from libVLC's log,
none of them in the unified log: `trustd` (`-9808` on every https stream), `coreaudiod` ("No audio
output devices found") and `AudioComponentRegistrar` ("cannot find any AudioComponent").

### Decisions

- **Frames cross a pipe, not shared memory.** A host sharing a mapped file could truncate it under the
  app and fault the reader. Through a pipe it can only send bytes, and `MediaStreamReader` decides how
  many it takes and where they go. The cost is the copy, which is why frames are capped at **1080p**.
  Video above that is scaled down in the host, including on a larger display.
- **Hardware decoding stays off.** VideoToolbox is denied, so libVLC falls back to `avcodec`. Allowing
  it would open kernel drivers to a process parsing hostile media. The CPU cost of 4K VP9 on battery has
  not been measured.
- **`deleteHostDirectory`, not `File.deleteRecursively`.** Measured: `deleteRecursively` emptied the
  target of a planted symlink, so a host could have had the app delete anything the user can.

### Limits

- The host can reach the LAN and the internet. Loopback is denied. Only the first URL is checked, so a
  redirect or an HLS segment can still reach the LAN. Fixing that needs a filtering proxy.
- It can reach `coreaudiod`, `audiohald`, `trustd` and the security services, each an attack surface
  beyond a plugin host's. Microphone access was not tested; it should fall to macOS's privacy prompt.
- `sandbox-exec` is deprecated, as in `KMP-14`. No CPU or memory quota on the native side.
- Native code writing to fd 1 would corrupt the stream, which ends that playback as failed.
- `--check-playback` is the headless check: a URL, or `plugin:<provider>:<query>`.

---

## KMP-17 — desktop's own screens, following Apple's principles · **measured 2026-11-02**

**Status:** done · #87 · the owner's direction, recorded with every decision in `docs/plans/desktop-design.md`

`SearchScreenDesktop`, `DetailScreenDesktop`, a floating player HUD, and `core/designsystem/desktop`
(`DesktopDimens`, `DesktopMotion`, `DesktopButton`, `DesktopRow`, `forDesktop()`). The TV screens are
untouched apart from one `private` becoming `internal`. **Android is unaffected:** nothing in `:app` calls
these.

**Verified:** rendered offscreen at 1280×800 and 800×520, with real plugins and posters for search and detail.
`DesktopInteractionTest` drives real pointer and key events through the scene: idle has nothing to press, focus
survives a search that finds nothing, a card opens on click, the panel dismisses on an outside click but not an
inside one, and scrubbing seeks once on release. The focus test found a real defect: `StateView`'s initial focus
would have taken keystrokes mid-typing.

**Not verified:** the live window, which this session cannot drive. No screenshot cases, because the plugin does
not render here and cannot see KMP modules (`DEC-10`).

---

## KMP-21 — `:media:subtitle`, the commonMain half · **measured 2026-11-02**

**Status:** done · #90 · plan §4.3 and Phase 6's second step. New first-party code, nothing consumes it yet.

`cloudstream.kmp.library`, android + jvm, iOS canary on. Pure SRT and WebVTT parsers, plain-text cue
cleaning, the delay arithmetic and a lookup over cues. **`:app` is untouched:** `CustomSubripParser`,
`CustomSubtitleDecoderFactory` and `PlayerSubtitleHelper` are still the live path, so `classes.jar` and
`KMP-09` are not in play. `:media:subtitle` is not merged into the jar.

| File | Holds |
|---|---|
| `SubtitleParser.kt` | `parse(ByteArray)`, `parse(String)`, `detect`; line walking and hand-parsed timecodes |
| `CueText.kt` | one raw line to plain lines: tags and `{\…}` removed, references decoded once, controls dropped |
| `SubtitleParseResult.kt` | `SubtitleFormat`, `SubtitleLimits`, `SubtitleTruncation`, `SubtitleParseResult` |
| `SubtitleTimeline.kt` | `SubtitleDelay`, `SubtitleTimeline` (`activeAt`, `latestStartedIndex`) |

### The decisions

- **`SubtitleCue` and `CaptionStyle` stay in `:media:model`,** taken through `api`. `KMP-07` put them there;
  the plan's table lists them under both modules. A third cue type would be worse. **This is an edge between
  two modules plan §4.1 draws at layer 2, and rule 2 says "lower layers only".** It is acyclic and it does not
  cost the canary, but the plan's diagram needs `:media:model` one layer down, or an owner's word otherwise.
- **Input is untrusted, so a parse has no failure case.** No `Regex` in the module. `SubtitleLimits` caps
  input size, cue count, line length and lines per cue; `truncations` says which cap was hit.
- **Output is plain text, not markup.** `&lt;b&gt;` comes out as `<b>`, because that is what the author asked to
  display. References decode once and tags are removed in one pass, so `<<b>script>` also leaves `<script>` as
  text. Anything handing a cue to an HTML parser must escape it (`dependency-security.md`).
- **`{\anN}` is read, and `SubtitleCue` has a `placement`** since `KMP-25`. This PR stripped it, because the cue had
  nowhere to put it.
- **`delayMs` is positive-means-later,** the sign the dialog shows. `CustomDecoder.subtitleOffset` is its negative.
- **A position can be `C.TIME_UNSET`,** so delay arithmetic saturates; a plain subtraction wraps to the far future.

### Where it deliberately differs from `CustomSubripParser`

Read, not run: `android.text.Html` needs a device or Robolectric, so **no differential test against the legacy
parser exists.** The tolerance tests were written from its source.

| Input | `CustomSubripParser` | Here |
|---|---|---|
| No index line, `1 ` or an index over `Int` | cue skipped | cue kept |
| No blank line between cues | merged, next index shown as text | two cues |
| Whitespace-only line | added as an empty line, cue continues | ends the cue |
| `X1:40 X2:600 …` after the end time | cue skipped | cue kept |
| 20-digit hours | `NumberFormatException` out of `parse` | counted in `malformedCues` |
| End before start | unguarded, `endTimeUs - startTimeUs` goes straight to `CuesWithTiming` | counted in `malformedCues` (`SubtitleCue` would throw) |
| `<b>` with no `>` on the line | whatever TagSoup does | shown as text |
| Styling, `<font>`, `<b>`, `<i>` | spans | removed |

### Not done

- **Charset detection.** No BOM means UTF-8; `:app` uses `UniversalDetector`, a JVM library. A caller holding
  windows-1256 bytes decodes them and calls the `String` overload.
- **The text filters** `KMP-07` assigned to this module: `removeBloat`, `removeCaptions`, `upperCase`. Not in the
  plan's column. Worth doing by hand: `bloatRegex` has a `.*\s+` run over the whole file.
- **`X-TIMESTAMP-MAP`.** Segmented HLS WebVTT needs it; this reads whole files.
- **Moving or adapting `:app`'s classes,** the later step that does touch `classes.jar`.
- **ABI baseline.** `kmp.library` has no `abiValidation`: `checkKotlinAbi` does not exist for this module or for
  `:media:model`, so there was nothing to record.

### Verification

Local, Temurin 17.0.20.1, `TZ=UTC`, `-Xmx6G`, scoped to the module; each command redirected with `$?` captured.

- [x] `:media:subtitle:jvmTest` → exit 0, **104 tests, 0 failures, 0 skipped**, counted from the JUnit XML.
- [x] `:media:subtitle:iosCanary -Pcloudstream.iosCanary=true` → exit 0, both klibs compiled.
- [x] `:media:subtitle:iosSimulatorTest -Pcloudstream.iosTest=true` → exit 0, **104 tests, 0 failures**, counted
      from the XML. Slowest about 1 s against a 15 s budget.
- [x] `:media:subtitle:check :media:subtitle:assemble` → exit 0.
- [ ] `checkKotlinAbi`, the full lint/`check` gate and `:app:checkExtensionApi` were not run here. `settings.gradle.kts`
      is the only existing file touched besides this one.

**Mutation checks,** each applied alone, each turning `jvmTest` red:

| Mutation | Failed by |
|---|---|
| No last-closer guard on `<` | `repeated unclosed tag openers stay linear`: 48 s and 58 s in two runs, against under 0.4 s unmutated; plus two out-of-bounds |
| No timecode digit cap | `a timecode too long for a long…`, `a huge digit run…` |
| No end-before-start guard | `an end before its start…`, `random bytes never throw…` (`IllegalArgumentException`) |
| No cue cap | `the cue count is capped and reported` |
| No size limit on string input | `string input past the size limit…` |
| Entity output unsanitised | `a reference cannot smuggle…`, `tabs and unusual spaces…` |
| Wrapping delay arithmetic | `an unset position saturates…`, `a maximal position saturates` |
| No line cut | `one enormous line…`, `a line limit cut inside a surrogate pair…` |

The first pass sized that pathological input at 500k openers and a mutated run took only 8.6 s against a 5 s budget,
too thin a margin to mean anything. It is 1M now, and the budget 15 s so the Kotlin/Native debug binary has room.

---

## KMP-20 — `:feature:home`: Phase 7's next vertical, on desktop · **measured 2026-11-02**

**Status:** done · #92 · desktop only. **TV screen not built**, though `design/TV Home.dc.html` covers it (below).

`MainAPI.getMainPage` per provider: rows of titles for a chosen source, a source picker, more of a row on
request, and a title opens through the existing `:feature:detail` flow. Home and search are now the window's two
top-level screens and the window opens on home. `SearchEffect.BrowseHome` goes there. Escape and Back behave as
before; Escape also closes the picker. `:app`'s home fragments are untouched.

- **The seam.** `HomeSource` in commonMain, implemented by `DesktopHomeSource` over `APIRepository`. No `:library`
  edge, so the module is in the iOS canary.
- **Words (`I18N-08`).** `HomeStatus` has none; `toUiState` resolves `:core:strings`. `I18N-09` added the row's
  button, a partial page's headline and the search-only prompt's body.
- **Sections fail alone.** Each is asked separately, which is what makes "partial" reachable for one source. A
  retry asks only what failed and keeps what is on screen and the pages already loaded. A source that sets
  `sequentialMainPage` is asked one section at a time at its own delay, capped at 10 s.
- **Paging is a section's,** because that is what a provider pages, so a press on one row can grow its siblings. A
  page adding nothing ends the section whatever `hasNext` claims, and a row stops at 500 titles and a page at 64 rows.
- **The sandbox gained `OP_MAIN_PAGE`.** It carries a section index, not the provider's `data`, which never leaves
  the host. Replies pass the same count checks, `cleaned()` and poster filter as search, and rows, items and sections
  are capped on both sides. A plugin whose `mainPage` getter throws loses its home page and keeps its search.
- **On top of the sandbox,** `DesktopHomeSource` drops format characters (U+202E and friends) from titles, drops
  titles with nothing left, and passes on only http(s) posters. Search and detail do not drop format characters:
  `isISOControl` does not cover them, so a title there can still reorder the text around it. Reported, not changed.

**A real defect the tests caught:** `stringResource(id, name)` left `%s` unfilled ("Could not load %s"), because
Compose Resources fills only positional arguments. Through `UiString.Resource` it is filled, and
`HomeStatusWordsTest` pins it.

**A behaviour change outside home:** a double click on a card pushed the title twice, so Back took two presses.
`Main.kt` now ignores opening a title while one is on top. Search shares that lambda.

**Verified:** rendered offscreen at 1280×800 and 800×520 in all twelve states plus the whole window, and looked at.
`HomeScreenTest` drives real pointer and key events, including `DesktopApp` itself over a fake source for where the
window opens, a double click, Escape and the tabs. `SandboxEnforcementTest` runs hostile main pages against a real
child under real Seatbelt.

**Not verified:** the live window, which this session cannot drive, and **any real plugin's `getMainPage`**:
everything above ran against fakes and the hostile fixture.

**Not done:**

- **The TV screen,** and any Android host. `design/TV Home.dc.html` draws a hero and rails that need a different
  screen and data this seam does not carry.
- **The chosen source is not remembered** across launches. Android stores it; desktop opens on the first source.
- **`sequentialMainPageScrollDelay`** is not carried, because a next page here takes a press.
- **`TitleCard` restates search's `ResultCard`,** which is private to `:feature:search`. If a third screen wants one
  it belongs in `core/designsystem/desktop`.

**Found, not changed here:** `PluginHostClient.readLoop` treated a reply to a timed-out call as a protocol violation
and ended the host, so a plugin answering at 61 s lost its whole host, for search as much as for home. See `BUG-14`
in [defects.md](defects.md).

**Run against real plugins, in the sandbox, on 2026-11-03.** `--check-extensions` now reports each provider's first main-page section through `DesktopHomeSource`: Invidious 2 rows and 40 titles, Dailymotion 1 and 26, Twitch 1 and 20, Internet Archive and YouTube 0 rows. Later sections of those two were not checked by this command, and no window was driven. A row header now holds the button's height, because cards jumped 8 dp when `Next` became `Loading`; a test pins it and fails without the fix.

---

## KMP-18 — non-official plugins, reviewed and then run in the sandbox · **measured 2026-11-03**

**Status:** done · #94 · Owner's authorisation, 2026-11-02: "review
for security issues before running anything", "convert to our app (as some plugin will contain android
related things)". Everything was reviewed first, and everything that ran ran under Seatbelt.

### What the sample was

`recloudstream/cs-repos` `repos-db.json`: 34 repositories, 33 reachable, 582 plugins. Dropped by rule, before
any download: the 5 in the verified official repo, 10 with `NSFW` in `tvTypes`, 42 not `status: 1`. From each
remaining repo, the first 2 by `sha256("kmp18:" + internalName)`: 56 candidates. Ayu CloudStream Games (2) were
then dropped unfetched: their 3 to 8 MB files probably bundle game content, and the limit is no media downloads.
54 fetched, each in a fresh directory, and all converted and scanned statically: 53 jars, 1 conversion failure.

From those, **15 chosen by hand for variety, one from each of 15 repositories**: clean ones, Android-heavy ones,
`requiresResources`, remote-config, reflection, opaque payloads. So this is a sample picked to stress the
review, **not** a measure of how many plugins in the catalogue work.

### Verdicts

Review is a second, independent constant-pool parser (no JVM, no class loading) over the jar `.store` holds,
plus reading the classes it flagged. Secret-shaped strings were reported by class and kind, never by value.

| Plugin (repo) | Verdict | Evidence |
|---|---|---|
| InternetArchive (Luna712) | run | one host; no reflection, files, loaders |
| ARD (German) | run | public broadcasters' APIs only |
| GuardaSerie (Italian) | run | one host |
| BambooUA (CakesTwix) | run | one host |
| 5movierulz (Phisher) | run, noted | `DonationManager`: GET of a goal file and a worker, plus what looks like an ad-network link. All dialog code, needs an activity, and a host has none |
| BollyFlix (Megix) | run, noted | its base URLs come from a remote `urls.json`, so its targets cannot be fully reviewed in advance |
| MovieLinkBD (CNC) | run, noted | Android-heavy; a WebView path that fails here; a donation dialog |
| Nepu (raghav) | run, noted | a proof-of-work solver for the site's own Anubis challenge, sized by `availableProcessors`: CPU, not mining (host capped at 4) |
| AnimeUnity (doGior) | run, noted | `requiresResources`; `Runtime.exit` in a settings restart, which would end only its host |
| EgyBest (AllForU) | run, noted | an announcer dialog with a payment link; needs an activity |
| **Aniyomi Compat** | **refused** | scanner Block: `dalvik/system`, downloads and loads APKs. No sandbox could make that a media provider |
| **Mega repository** | **refused** | no provider at all; its one job is adding every catalogue repo to the user's repositories. Harmless here (`RepositoryManager` is absent and stays so) and pointless |
| **Fshare (Vietnamese)** | **refused** | enumerates installed packages and hashes signing certificates, beside long opaque constants, behind deliberately meaningless identifiers (`f0001`, `c0001`). Purpose not established; that is the point |
| **BingeCloud (FLUMMOX)** | **refused** | undeclared telemetry: a persistent random install ID, extension name and version, POSTed to a personal Cloudflare worker with a hard-coded token. A `Prewarm` class also sends HEAD requests to 9 hosts. It is not exfiltration of anything sensitive; it is not disclosed either |
| **Eclipsia** | **refused** | More than 50 base64 strings, up to 16 KB each, that decode to JavaScript providers (decoded as data, by the review). Most call `fetch`. Nothing in the jar's constant pools describes what they do, which defeats this review and any scan. They name dozens of further hosts, one a raw IP behind `nip.io`. Also opens a local `ServerSocket`, which the policy does not allow |

Outside the 15, in the static pool only: **AnimeON (CakesTwix) cannot be converted**, `OutOfMemoryError` at the
conversion host's 512 MB from a 347 KB dex. It fails safely. The cap was not raised and the cause was not chased.

### Two conversion defects, both real, both `KMP-14`'s

The first baseline run found these. KMP-14's "behaviour-preserving" rested on 5 extensions, none of which hit them.

- **dex2jar rewrites `-` to `_` in names.** `kotlin.Result.constructor-impl` became `constructor_impl`, a method
  that does not exist: `NoSuchMethodError` from any inlined `runCatching`. Measured in the bytes: the dex has the
  hyphen, the jar the underscore. **30 of 53 jars** refer to such a name on a `kotlin/` class.
- **Dex 037 or later becomes class version 52, with no stack maps**, and the JVM refuses it: `VerifyError: Expecting
  a stackmap frame`. Measured: 035 gives version 50, which needs none. **3 of 53.**

`DexConverter` now sets `dontSanitizeNames(true)` and `computeFrames(true)`. Frames are computed from the dex's own
hierarchy (`Dex2jar$1$1.getCommonSuperClass` reads a map, read in its bytecode), so no plugin class is loaded by it.
The verifier was never turned off, and that was never an option. The store's name for a converted jar now carries a
revision (`-c2`): the store persists, so a better converter would otherwise never reach an existing file.

### Conversion: what was added

Measured with `ScanReport.externalClasses`-equivalent lists over 53 jars against the bundle's own classes:

| | before | after |
|---|---|---|
| jars that resolve completely | 26 | **41** |
| distinct `android.*` / `androidx.*` classes missing | 164 | 54 |
| distinct `com.lagradost.cloudstream3.*` classes missing | 22 | 11 |

- **Real behaviour, because the run path needs it:** `Handler`, `Looper` and `Dispatchers.Main` (one stand-in main
  thread; 15 of 53 refer to Main), `Bundle`, `Build.VERSION`, `SystemClock`, `Color`, `DataStore` and `CloudStreamApp`
  keys on the host's own preferences, `Event`.
- **Types and nothing else** for `android.view`, `android.widget`, `android.app`, fragments, bottom sheets: their real
  hierarchy, for the verifier. `CommonActivity.activity` is null and `openSettings` is never called, so none is
  constructed for real. A plugin that does call into one gets `NoSuchMethodError`, the rule `Context` already set.
- **Failing on purpose.** `WebView` throws from its constructor. `CookieManager` stores nothing. `CloudflareKiller`
  passes through, so a challenge stays visible to the plugin. `Context` still has no `getPackageManager`. `RepositoryManager`
  and `PluginManager` stay absent. `WebViewResolver` was not touched and still fails.

`AndroidShimGroupsTest` reads the shims' own constant pools and fails if any names a file, a socket, a process, a loader
or reflection.

**Not shimmed: `com.google.gson.Gson`**, 3 of 53. It is a real library and adding it is a dependency decision. _Taken: `KMP-23` adds the real library to the desktop host._

### Results, in the packaged `.app`, each alone in a fresh extensions directory

    env -i HOME=<fresh> CLOUDSTREAM4_EXTENSIONS_DIR=<fresh> PATH=/usr/bin:/bin CloudStream --check-extensions <q> --verbose

No `--check-playback`, nothing played or downloaded. Link counts are listing only. Baseline is the same bundle before
this task's code.

| Plugin | Baseline | Now: load | Search | Open | Links |
|---|---|---|---|---|---|
| InternetArchive `nasa` | worked | yes | 26 | yes (audio) | 0 |
| ARD `nasa` | `NoSuchMethodError: Result.constructor_impl` on open | yes | 31 | yes | **10**, 2 subtitles |
| AnimeUnity `nasa` | `VerifyError`, no load | yes | 17 | yes, 42 episodes | **1** |
| BollyFlix `nasa` | `NoClassDefFoundError: CloudflareKiller` | yes | 20 | yes | **5** |
| MovieLinkBD `earth` (`nasa` gives 0) | `NoClassDefFoundError: AppCompatActivity`, no load | yes | 30 | yes | **1** |
| EgyBest `earth` (`nasa` gives 0) | `VerifyError`, no load | yes | 4 | yes | **1** |
| BambooUA `nasa` | `NoClassDefFoundError: Gson` on open | yes | 1 | **`NoClassDefFoundError: com/google/gson/Gson`** | |
| 5movierulz `nasa`, `earth` | 0 results | yes | 0 | | cause not established; `curl` gets a 301, this plugin's site answers from Cloudflare |
| Nepu `nasa`, `earth` | `NoClassDefFoundError: DataStore`, no load | yes | 0 | | `curl` gets `403`, `cf-mitigated: challenge`. Its solver needs a WebView, so 0 is expected |
| GuardaSerie `nasa` | `NoClassDefFoundError: CloudflareKiller`, no load | yes | `UnknownHostException` | | the site's domain no longer resolves. Not ours |

### The sandbox really was in force, and the network was public

- **`sandbox_check(pid, NULL, 0)`, from outside, on every child seen:** 1 for every host, in every run; 0 for its parent
  app, the control. Parent PID and `--plugin-host` in argv confirmed. A child was occasionally caught as `(CloudStream)` with no arguments, mid-exec; it returned 1 too.
- **Peers, by `lsof` every 0.5 s:** no private, loopback or link-local address, and no listening socket, for any plugin.
  Attributed by resolving each plugin's own hosts afterwards: in the final runs, 14 addresses match a host the plugin names. **9
  public addresses are not attributed**, 8 in Cloudflare's ranges and 1 not. Redirects and extractor hosts would explain them, and
  that was not established. Short connections can be missed between polls. IPs are all this can see.
- **The owner's data:** `~/.cloudstream4` was listed before and after, and is unchanged.
- **Denials could not be observed.** A control denial under `sandbox-exec` left nothing in `log show` here, with or
  without `--info --debug`, so an empty log means nothing and none is claimed. What can be said is narrower: the host stderr captured
  (`--verbose` keeps the first 12 lines per provider) never mentions `Operation not permitted` or `Permission denied`. `SandboxEnforcementTest` remains the proof of denial.

### Scanner gaps, closed

Fshare, Eclipsia and BingeCloud all had no Block from the scanner before this task. New rules, measured against all 53 jars before being
added, where they fire only on what is listed:

| Rule | Fires on | Severity |
|---|---|---|
| A long base64 constant decoding to a script, class, dex or zip | both Eclipsia plugins; not the 1 other long base64 string | Block |
| `PackageManager.getInstalledPackages` / `getInstalledApplications` | Aniyomi, Fshare | Block |
| Extending `ClassLoader`: inside a subclass, `defineClass` names the subclass, so the method rule never matched | none | Block |
| Text naming `URLClassLoader`, `ProcessHandle`, `javax.script.`, `jdk.internal.`, `defineClass` and similar: the class rule blocked these only when not reached by reflection | none | Block |
| `Runtime.exit` (only `halt` and `System.exit` warned), and a string that is exactly a device-ID key | AnimeUnity, NetNaija; BingeCloud, Otakutsu, FootReplays | Warn |

**BingeCloud-style telemetry still only warns.** A key name is a hint, and a POST is ordinary. Static scanning cannot
tell an analytics ping from a search.

_Since `KMP-24`, a warning no longer means it runs unseen._ When this was written, a scanner Warn reached only a log line, so
a BingeCloud-style plugin would have run silently if installed. It is now held, off, until its exact file is allowed in the window.

### Mutation checks

Each edited alone, scoped tests run, each restored. All went red.

| Mutation | Fails |
|---|---|
| No `dontSanitizeNames` | names test, real-host test |
| No `computeFrames` | stack-map test, real-host test |
| Each of 4 scanner rules removed | its test |
| `WebView` stops throwing | webkit test, sandbox end-to-end |
| A shim names `java.io.File` | the capability test |
| Dispatcher guard removed | dispatcher test |
| Dispatcher service entry removed | sandbox end-to-end |

**The first round caught this task's own false test.** With both converter flags removed, the real-host test still passed:
the fixture's source was on the test classpath, and a host loads parent-first, so it ran the original rather than the converted
class. The source now lives outside any source root, and the test asserts it is not on the classpath.

### Limits a reader should not have to discover

- Nothing here is a claim that these 10 are **safe**. They contain nothing this review recognised as hostile, and that review
  is static, of jars whose remote config and redirects it cannot see. The sandbox stays the boundary.
- **LAN reachability and unchecked redirects from `KMP-14` are unchanged.** Not exercised against a hostile plugin here.
- "Loads" is a floor. Only search, open and listing links ran, on the first result. Settings, home pages and most extractors did not.
- The shims' `android.*` types cover what these 53 reach, and 54 classes remain missing: `Canvas`, `Path`, `Bitmap`, `ClipboardManager`, `MediaStore`.
  Those were left alone on purpose where they are capabilities, and because nothing that ran needed them.

### Found and not fixed

```
Found: the conversion host's writable directory is the whole `.store`, in SandboxedExtensions.toStore (`PluginHostClient.convert(launcher, source, staging, store)`)
Suggest: give each conversion its own empty subdirectory and move the result out
Why: dex2jar parses hostile bytes. A dex that exploited it could overwrite another extension's scanned jar, between that jar's scan and its host starting. Narrow, and no such exploit is known, and nothing here can test it without one
```

### Gates, local, scoped (the coordinator runs the full ones)

`:feature:extensions:sandbox:jvmTest` 67 tests, 0 failures. `:desktopApp:jvmTest` 22, 0. `:desktopApp:createDistributable` 0.
`checkExtensionApi` and `check` were not run here: this module is not on `:app`'s classpath.

---

## KMP-08 — Track L: `:library` iOS surgery

**Status:** blocked by `DEC-04`

Needs a design workflow before any code. Cannot be scoped until the iOS extension strategy is
decided, because that decides how much of `:library` is worth making iOS-compilable.

---

## KMP-10 — the iOS canary is red · **measured 2026-10-14**

**Status:** done · #49 (step 1, `:core:ui`), #50 (step 2, `:core:mihon`), #51 · **the iOS canary is
green: 12 of 12 modules**, and has stayed 12/12 through every module move since — the `:core:common`
arrivals in `KMP-04` and `:core:data`'s in `KMP-05` were both checked against this list

`./gradlew iosCanary -Pcloudstream.iosCanary=true --continue` is **red on master**, and has been
since `KMP-06c` (#41) created `:core:ui`. Twelve modules have an `iosCanary` task. Ten pass. One
fails:

    e: core/ui/src/commonMain/.../theme/DeviceTheme.kt:7:10
       Expected DeviceTheme has no actual declaration in module <commonMain> for Native

`internal expect object DeviceTheme` has an `androidMain` actual (Material You, via
`android.R.color.system_*`) and a `jvmMain` one, and no Native one. `:core:mihon` is the twelfth: it
cannot run at all, because it depends on `:core:ui`.

Plan §4.2 lists `:core:ui` as canary "yes, macOS runner", so this is a gap between the plan and the
tree, not a decision. Nobody noticed because `INF-02` records that the canary "is run by hand" and
has no CI job — and by hand, nobody ran it.

### Step 1 — `:core:ui` · #49

Done. `core/ui/src/iosMain/.../DeviceTheme.ios.kt` returns the static scheme and the normal primary
colour, which is what `jvmMain` already does.

**There was no design choice to make, though it looked like one.** Android's Material You exposes
the wallpaper palette through `android.R.color.system_*` and macOS exposes a chosen accent through
`NSColor.controlAccentColor`, but **iOS publishes no system accent or palette to apps**. There is
nothing to read. `CloudStreamThemeMode.Dynamic` also already documents itself as falling back "to
`Dark` on unsupported platforms", so the behaviour was specified before the source set existed.

### Step 2 — `:core:mihon`'s four `String.format` calls · #50

Fixing `:core:ui` let `:core:mihon` compile for the first time, and it fails — at **exactly the four
sites the plan already named** (§7 Phase 5: "Replace `String.format` as the Mihon files move
(`Preference.kt:74,100,124`, `EditTextPreferenceWidget.kt:41`)"):

    e: core/mihon/.../settings/Preference.kt:74:37   Unresolved reference 'format'.
    e: core/mihon/.../settings/Preference.kt:100:37  Unresolved reference 'format'.
    e: core/mihon/.../settings/Preference.kt:124:31  Unresolved reference 'format'.
    e: core/mihon/.../settings/widget/EditTextPreferenceWidget.kt:41:30  Unresolved reference 'format'.

All four are `subtitle?.format(…)` — `kotlin.text.format`, which is `java.lang.String.format` and
JVM-only. Each substitutes one value into a preference subtitle template.

Its own PR, because this is **vendored Apache-2.0 code** (`KMP-06a`, `NOTICE`), where every edit
widens the diff against upstream that 06a measured file by file. Four lines of first-party code
would not need their own review; four lines inside the Mihon tree do. `readme.md` now records the
edit, so the next person re-measuring against upstream expects it.

**The "how" was settled by measuring the corpus, not by choosing.** Every template that reaches
these calls is exactly one `%s` — the `"%s"` defaults on `ListPreference`, `BasicListPreference`
and `MultiSelectListPreference`, and four `"%s\n" + stringResource(…)` overrides in `:app`'s
player and updates screens. The one `"%s %d"` in the tree is in `:app`'s legacy
`SettingsAccount.kt`, a view-binding fragment that never reaches a preference subtitle. So a
one-placeholder substitution is faithful, and porting `String.format` properly would be inventing
capability nothing uses.

`withValue` is first-party and lives in `:core:ui` (`compose/Template.kt`), not in the Mihon tree,
so the licence boundary holds: the vendored files gain an import and four call sites, and no
first-party file joins them.

**One real difference, in the safe direction.** `String.format` throws
`UnknownFormatConversionException` on a stray `%`, and three of the four `:app` templates
concatenate a *translated* string — so a translator writing "50% off" would have crashed the
settings screen. `replaceFirst` cannot throw. That is a latent class of crash removed, **not a live
bug**: across all 49 locale files, none of `video_disk_description`, `video_ram_description` or
`automatic_plugin_download_summary` contains a `%` today.

**Reproduce:** `./gradlew iosCanary -Pcloudstream.iosCanary=true --continue`. Kotlin/Native 2.4.0
must be in `~/.konan`; it downloads on first run. On this machine the whole canary takes about
4 seconds warm — which is the strongest argument for `INF-02` finally giving it a CI job: the thing
nobody ran for five weeks costs seconds.

---

## KMP-09 — The `$stable` trap (reference, not a task)

Kept here because it has already cost two attempts.

The Compose compiler emits `public static final int $stable` on every class it processes. Moving a
class to a module **without** the Compose compiler silently drops that field — an ABI break that
reads as an innocent file move. Worse: when a class moves to a *different* Compose module, a
constant `$stable` becomes `getstatic Other.$stable`, which changes the runtime value while the ABI
dump looks identical.

- PR #4 dropped `$stable` on four classes and the PR body claimed signatures were unchanged. #5 fixed
  it with the `cloudstream.kmp.extension-api` convention plugin.
- B2 dropped three auth DTOs for exactly this reason; B3 proved them safe and moved them.

**Always run the `$stable` scan before claiming a move is signature-neutral.** An ABI dump diff
alone does not catch the cross-module case.

---

## The settings screens were finally opened · **2026-10-14**

`KMP-06b` (#42), `KMP-06d` (#46) and `KMP-10` (#50) each moved or edited the code behind the Android
settings screens, and each shipped with the same caveat: *not opened on a device*, because the
attached Pixel 9a was locked every time. It was not a device that was needed — it was **any** device.

**An emulator closes it.** Four AVDs already exist on this machine. Booted headless:

    ~/Library/Android/sdk/emulator/emulator -avd QA_36_1 -no-window -no-audio -no-boot-anim \
      -gpu swiftshader_indirect -no-snapshot-save -port 5570

API 36, 1080x2400. The APK is the one the verification run already built —
`app/build/outputs/apk/prerelease/debug/app-prerelease-debug.apk` — installed with `adb install -r -g`.
The launcher activity is **not** `MainActivity`; resolve it rather than guess:

    adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER \
      com.lagradost.cloudstream3.prerelease.debug
    # -> …/com.lagradost.cloudstream3.ui.account.AccountSelectActivity

### What rendered

Every section of the settings root opened and drew correctly: **General**, **Player**, **Layout**,
**Updates and Backup**, **Accounts and Security**, **Extensions**. The vendored Mihon widgets are
live — `SwitchPreferenceWidget` toggles, the two sliders on General with their value badges, section
headers, icons and subtitles all present.

**Direct runtime evidence for `KMP-10` step 2.** The `%s` subtitles that #50 moved off
`String.format` and onto `:core:ui`'s `withValue` resolve on screen: General shows *App Language →
`en-US`* and *Extension languages → `All Languages`*, Player shows *Preferred video player →
`Internal player`*, Layout shows *Primary color → `Normal`*, *App theme → `Dark`*, *App Layout →
`Auto`*. Those are exactly the call sites `Preference.kt:74,100,124` feed.

A **list-preference dialog** opens, shows its radio rows and its Cancel / Ok buttons, and dismisses —
the `ListPreference` path #50 also touched.

### Two things found while looking, neither changed

- **`ListPreferenceWidget` does not use Mihon's `AlertDialog`.** It calls `:core:ui`'s
  `SingleSelectDialog` (`core/ui/.../compose/Dialog.kt`); the upstream `AlertDialog` block is
  commented out in place at `ListPreferenceWidget.kt:48-75`, with its `DialogRow` helper commented out
  below the function. Pre-existing and deliberate-looking. `core/mihon/readme.md` does cover the file
  — the widget row reads "`presentation/settings/widget/` × 8 … `MultiSelectListPreferenceWidget`
  +30; rest small" — but swapping the dialog implementation for a first-party one is not "small", and
  it is the sort of divergence the Apache §4(b) record exists to name. Worth a line in that table when
  someone next touches it; **not** changed here, because the readme's counts were measured at a commit
  (`KMP-06a`, 2026-10-07) and editing one row by hand desynchronises it from the rest.
- **The dialog has no visible edge.** `SingleSelectDialog` defaults to
  `containerColor = MaterialTheme.colorScheme.background`, which in the dark scheme is the same black
  as the screen behind it, so the dialog reads as floating text rather than a surface. It **is**
  opaque — the "Features" section header behind it is clipped by its left edge, which is how this was
  told apart from a transparency bug. Cosmetic, pre-existing, and a `:core:theme` / `DS` question
  rather than a `KMP` one.

### Caveat

Headless swiftshader, not hardware. It proves the composition builds, resolves its resources and
responds to input; it does not prove GPU-dependent rendering or anything about the TV layouts.

---

## KMP-22 — A conversion can no longer write the extension store

**Status:** done · #95 · found by `KMP-18`

`.cs3` conversion ran dex2jar with write access to all of `.store`. A dex that exploited dex2jar could have replaced another extension's jar between that jar's scan and its start, which is the guarantee the store exists to give.

`convertIntoStore` now gives each conversion its own empty directory under `.converting`, outside the store. The result is refused unless it is a regular file, and it is copied into the store rather than moved, so a hard link or a process left holding the file open cannot reach what was scanned.

`ConvertIntoStoreTest` plays the taken-over host: a symlink, a directory, a kept hard link, and a failure. No real dex2jar exploit was used, because none is available. The policy side is `writable`, which `SandboxEnforcementTest` already proves.

**Not addressed:** a taken-over conversion can still fill the disk in its directory until the 120 s timeout, and a process it left running is not killed along with it. A jar already in an existing store is not re-checked.


---

## KMP-23 — Gson for plugins, on the desktop host only

**Status:** done · #98 · owner approved the dependency 2026-11-03, on condition of the usual security check

`com.google.code.gson:gson:2.15.0` is `runtimeOnly` in `:feature:extensions:sandbox`. Nothing first-party calls it.
A plugin's `URLClassLoader` has the app class loader as its parent, with no allowlist, so a plugin sees whatever is on
the host's classpath. The host re-executes the same bundle, so the app's JVM has the jar on its classpath too, and never
loads it. `:app` keeps its own literal `gson:2.11.0`, the sandbox module is not in its graph, and neither Android nor iOS
gets anything: `app/api/classes-jar.api` is untouched.

### Security check, 2026-11-03

| | |
|---|---|
| Coordinates | `com.google.code.gson:gson`, `google/gson` on GitHub, not archived. Jar's own manifest and POM agree |
| Version | 2.15.0, published 2026-10-24: 10 days old when pinned. Maven Central `release` and the GitHub release agree |
| Known CVEs | One: `CVE-2022-25647` / `GHSA-4jrv-ppp4-jm57`, fixed in 2.8.9. OSV has nothing for 2.15.0 |
| Licence | Apache-2.0, as is the transitive dependency |
| Transitive | `error_prone_annotations:2.50.0`, 30 annotation classes, no runtime dependencies of its own. Left in: Gson's POM says so on purpose |
| Integrity | Published SHA-256 matches, both jars. **The PGP signature was not checked:** no `gpg` here, and installing one needs asking |

Bundle: +337,460 B of 163,481,404 (+0.21%), two jars. Packaging rewraps them: the bundled hashes differ from Central's, and every entry's CRC matches.

### What it adds to the sandbox

**No capability.** The Seatbelt policy is unchanged and plugin code already runs in that process.
**It does add a route the scanner cannot see.** `fromJson` and `toJson` allocate a class without running its constructor
(`sun.misc.Unsafe`, through the bundle's `jdk.unsupported`) and read and write private fields of any class outside the JDK,
host classes included. All of it with no reference to `java/lang/reflect`, so it scans clean: `GsonInHostTest` pins zero
findings. The same allocation without Gson works and gets a `reflection` warning, also measured.

**The scanner is not changed.** Blocking `com/google/gson/internal/` would remove nothing, since the public API does the
same, and warning on `com/google/gson/Gson` would flag ordinary use. A plugin bundling its own Gson is already blocked
(`sun.misc.Unsafe` in its strings), measured against the real jar, and no sampled plugin does.

For hostile JSON, measured in a real host: the type comes from the calling code, so `@class` and `class` keys are ignored;
`java.base` classes are refused, because the host has no `--add-opens`; 100,000 open brackets end at the nesting limit, not
in a `StackOverflowError`. **Not measured:** memory exhaustion from a huge document. `-Xmx512m` is the cap on that.

### Plugins

The `KMP-18` sample was rebuilt by its own rule: 33 of 34 repositories, 56 candidates, 54 fetched. A byte search of each
dex finds Gson in the same count, 3, and none defines it. Each was reviewed from its dex and by the scanner before anything ran.

| Plugin | Review | Before | After |
|---|---|---|---|
| BambooUA v13 | one host, no file, socket, process, loader or reflection use. **Run** | search 1, open `NoClassDefFoundError: com/google/gson/Gson` | search 1, opened, 8 episodes, **1 link** |
| ViStream v35 | no file, socket, process, loader or reflection use. About 80 hosts, some taken from remote text files and one a third-party decryption API, so its targets cannot be fully reviewed. **Run, noted** | not loaded: `NoClassDefFoundError: java/util/Object` | **the same. Still fails, and Gson is not why** |
| Fshare v35 | scanner Block, `getInstalledPackages`. **Refused**, as in `KMP-18`, and not run | | |

**ViStream is a separate conversion defect, not fixed here.** `computeFrames` wrote a stack map frame naming `java/util/Object`
in `ViStreamPlugin`; that name appears nowhere in the dex. Its Gson use is therefore unproven. Whether other plugins hit
this was not measured.

The five official plugins, `nasa`, before and after, with the same outcome per provider: all load, all hashes verified.
Dailymotion, YouTube and Internet Archive open. Twitch searches and fails to open (`Could not load page`) and Invidious finds
0, **both also before this change**.

Each run was in the packaged `.app` with a fresh `user.home`. Polled from outside, `sandbox_check` returned 1 for every
host and no peer was non-public. No `--check-playback`. Every file in `~/.cloudstream4` is unchanged, hash and mtime. One
thing moved: the mtime of the empty `.media-host` directory, at a time none of these runs was going. Only the player opens that
path, an app window on the real home was open throughout, and a control run left it alone. Its cause was not established further.

**Android plugins get 2.11.0 and desktop 2.15.0.** 2.15 makes `LinkedHashMap` the default `Map`, so a plugin casting to
`LinkedTreeMap` would behave differently here. None of the 3 refers to a `gson.internal` class. The wider catalogue was not checked.

---

---

## KMP-24 — An extension with warnings stays off until you allow that exact file · **measured 2026-11-03**

**Status:** done · #102 · **Owner's rule:** "show that warning clearly at install and let you decide per plugin". Found by `KMP-18`.

Desktop has no install screen: files are dropped in `~/.cloudstream4/extensions` and the startup pass runs them. A scanner Warn
reached a log line, so undeclared telemetry, which can only ever be a Warn, ran unseen.

### What it does

- **Warn and no decision: it does not start.** That is one branch in `SandboxedExtensions.loadOne`, after Block and hash mismatch, and every
  route to a host passes it. `--check-extensions` and `--scan-extensions` print `HELD`, with no flag to bypass. Conversion still runs
  first because the scan needs a jar. **It is not plugin code:** dex2jar reads the dex and loads no class from it, with no network, in its own
  sandbox, and that sandbox gets a private copy checked against the hash.
- **Block and hash mismatch are unaffected.** They return before the decisions are read, the row has no buttons, and
  `ExtensionApprovals` ignores an action on one.
- **Where:** a notice in the tab bar on Home and Search, `Extensions waiting for you: N`, opens a list. When nothing is waiting and
  something has been decided, a quiet label stays there, so a mind can be changed in the same list.
- **Words:** a sentence per Warn rule from `:core:strings`, then the raw finding, smaller. `ExtensionScanner.warnRules` is new so that
  `ExtensionReviewWordsTest` fails when a rule is added without a sentence. No words in `ExtensionApprovals` (`I18N-08`). The list says
  "extension", matching `home_search_only_body`.
- **Honesty:** no status says safe. The one sentence with the word denies it: "nothing obvious was found, not that they are safe". Neither
  choice is the primary button.
- **No restart.** `loadOne` was already self-contained, so Allow takes the file through the whole pipeline and registers its providers, and
  Keep off closes the host and unregisters them. `SearchViewModel` and `HomeViewModel` read the provider list once, so `DesktopApp` rebuilds
  both when `providerChanges` moves. Their state is lost at that moment. The list sits above the top level only, so no title or player is under it.
  A new file in the directory still needs a restart, as before.

### The decisions file

`~/.cloudstream4/extensions.decisions`: a sibling of the extensions directory, so also of `.store`, `.sandbox` and `.converting`. One line per
file name: choice, SHA-256, name. Then a SHA-256 of everything before it.

- **Bound to name and hash.** An update asks again, the same bytes under another name ask again, and a new decision for a name forgets
  the old hash. Allow records the hash the row showed, then re-reads the file: if it was swapped while the window was open, the new file is held.
- **A file swapped mid-load** cannot enter the store under the old hash: `copyExpecting` checks the private copy before it is used.
- **Corrupt means undecided, all of it, and it says so.** Strict parser: truncation, a flipped word, a bad checksum, an unknown version, a
  duplicate name, a link or a directory all read as nothing recorded. The window and the log say the choices were ignored.
- **The checksum is not a signature.** Any unsandboxed process running as this user can recompute it, and could do worse anyway.
  **The boundary against a plugin is Seatbelt.**
- **Atomic.** A `0600` temp file in the same directory, `force`, `ATOMIC_MOVE`.

### What a sandboxed plugin can reach, measured

`ExtensionSandboxReachTest`: a real plugin, a real host, the real policy, the app's real layout, with a positive control for each verb.

| Target | read | write / append / delete / rename / replace | hard link | list |
|---|---|---|---|---|
| decisions file | denied | denied | denied | parent denied |
| symlink to it from its own directory | denied | | | |
| its own `.store` jar | **allowed** | denied | | |
| another `.store` jar | denied | denied | denied | `.store` denied |
| `.sandbox/<other>` | denied | denied | | denied |
| `.converting/...` | denied | denied | | |
| the extensions directory, a new jar, a `.sha256` sidecar | denied | denied | | denied |
| its own `.sandbox/<name>` (control) | allowed | allowed | allowed | allowed |

**One thing it can learn:** `file-read-metadata` is open on every path, so it can see that the file exists and its size. Pinned by a test. Also run
in the packaged `.app`, with a query asking the probe to write the file: its hash did not change, and the same write to its own directory landed.

### Tests and mutations

`ExtensionDecisionsTest` 14, `ExtensionHoldTest` 16, `ExtensionSandboxReachTest` 5, `ExtensionReviewScreenTest` 13 (real pointer and key events),
`ExtensionReviewWordsTest` 5, `UntrustedTextTest` 4. Each mutation below was applied alone, tests run, and restored. All went red.

| Mutation | Fails, for example |
|---|---|
| Hold removed | 9, incl. `with warnings and no decision`-class tests |
| Hash binding removed | 4, `changing the file loses the approval` |
| Name binding removed | 3, `the same bytes under another name` |
| Keep off treated as Allow | 2 |
| Approval overrides Block | 9, `never reaches past a blocking finding` |
| Approval overrides hash mismatch | 1 |
| File moved into the host's directory | 15, incl. the reach test |
| File moved into the extensions directory | 3, incl. the reach test |
| Checksum unchecked | 1 |
| Damage not reported | 5 |
| Write in place, not atomic | 2, `a reader never sees half a write` |
| Link followed | 1 |
| Copy not checked against the hash | 1 |
| Text not cleaned | 2 |
| Holder acts on a blocked row | 1 |
| Keep off does not stop the host | 11 |
| Blocked row offered Allow | 1 |

**Looking at the renders caught a real bug:** the notice showed a literal `%d`, because Compose's `stringResource(res, n)` only fills `%1$d`.
It uses `UiString.Resource`, and a test now pins it. They also showed a class name's punctuation moving in a right-to-left paragraph, so plugin text
is forced left to right.

`:core:screenshot` does not render here and nothing was added to it. The PNGs were looked at and guard nothing.

### Strings

27 keys, none plural, authored in all 62 directories. Fifth move of `core/strings/oracle-baseline`, on top of `KMP-25`, with the proof in its README: 0 lines lost or
changed. `expectedOwn` 33,005 -> 34,679, `classes-jar.api` +27 `R$string` fields, 0 removed. "Extensions waiting for you: %d" is a label and a number
so that no locale needs plural forms.

**Not reviewed by a native speaker: all of it is machine translation.** Least certain: `ti`, `so`, `or`, `ta`, `ckb`, `as`, `am`, `az`, `mt`, `my`, `kn`, `ml`, `ne`.
`ar`, `apc`, `ars`, `fa` and `so` keep "reflection" and "class loader" in English. `ars` equals `ar`, as its file is Modern Standard Arabic. `qt` is
made of words from its own file, chosen by script.

### Limits a reader should not have to discover

- **The live window was not driven.** The screen ran offscreen with real events, and the pipeline in the packaged app with a hand-written decisions
  file. A real click in a real window, and a real Allow in the `.app`, were not done.
- **A right-to-left window was not seen.** The offscreen render sets the direction by hand.
- **An approval is for the file, not for what the scanner says about it.** A later scanner rule finding something new in an allowed file does not ask again.
- **Two app instances** can lose each other's write, last wins. Neither can invent an approval.
- **Reporting a warning is all this does.** The sandbox's network rules are unchanged: an allowed plugin can POST to anyone public, as before.

### Found and not fixed

```
Found: Home's empty state says "Install the extension first" when the only installed extension is held, in HomeCopy's NoSources(0)
Suggest: a state for "installed, waiting for you", leading to the list
Why: the notice beside it is accurate and this is not. Home and its state are outside this task
```
```
Found: DesktopHomeSource.safeText keeps U+2028 and U+2029, and its take() can cut a surrogate pair
Suggest: the same cleaning as untrustedText, moved somewhere both can reach
Why: provider titles are untrusted too. Maxlines bounds the damage, so this is narrow
```

## KMP-25 — Subtitles end to end, in the desktop player · **measured 2026-11-03**

**Status:** done · #101 · the owner's words, asked whether `:media:subtitle` should keep cue placement: "subtitle should be
end to end working".

**Scope: desktop.** Android's player already renders subtitles through Media3 and swapping it is not this task. `:app`
has no code change: only its generated `strings.xml` files and three new `R` fields in its API dump.

### The chain, and what was missing

| Link | Before | Now |
|---|---|---|
| Plugin to host | `subtitleCallback` only counted | Each `SubtitleFile` kept, at most 200 |
| Host to app | An `Int` in `RemoteLinks` | `RemoteSubtitle` records, cleaned on the app's side |
| App to player | `StreamLink` had no field | `StreamLink.subtitles: List<SubtitleTrack>`, so `:feature:detail` takes `:media:model` |
| Fetch | Nothing | `HttpSubtitleLoader`, in the app process |
| Parse | SRT and VTT, placement stripped | Those with placement, and TTML |
| Time | Status arrives about four times a second | `PlaybackClock` runs between reports |
| Draw | Nothing | `SubtitleOverlay` over the frame |
| Choose | Nothing | `SubtitlePicker`, the button at the end of the transport, or C |

### Formats, measured on real plugins

| Plugin | Offers | Result |
|---|---|---|
| YouTube | 21 tracks, **every one `fmt=ttml`** | All 21 parse: 160 to 874 cues |
| ARD | 2 tracks, EBU-TT-D and VTT | Both parse: 744 cues each, first cue at 2120 ms in both |
| Internet Archive | 4 tracks, 2 SRT and 2 VTT, rolling captions from YouTube | All 4 parse: 1,285 to 1,291 cues, on a title found by searching for it |
| Dailymotion, Twitch, Invidious | No subtitles on the title reached | Nothing to measure |

So a parser with only SRT and VTT would have shown nothing for the plugin most people have.

**Two findings a sample of the spec would not have given:**

- **ARD's EBU-TT-D cues begin at `10:00:02.120`** for a video whose VTT twin says `00:00:02.120`, because broadcast
  timecode starts a programme at 10:00:00. In a file with an `urn:ebu:tt` namespace whose first cue is an hour in or later,
  the whole hours before it come off. The real pair now agrees cue for cue at the start.
- **The Internet Archive's VTT starts each cue with a line holding one space.** `KMP-21` had a whitespace-only line end the
  cue, which lost the cue's words. Spaces before any text no longer end a cue; a truly empty line still does.

**Not supported, and the track says so:** SSA/ASS, MicroDVD, SMI, bitmap formats, and anything else `detect` does not
know, including an HTML error page served with status 200. None was measured on these plugins.

### Encoding

A BOM decides (UTF-8, UTF-16 LE and BE). Otherwise strict UTF-8 if the bytes are valid, then the charset the server
declared, then a guess from the track's language tag (`windows-1256` for `ar`, `Shift_JIS` for `ja`, and so on), then
`windows-1252`. **A guess can show wrong letters and never fails.** `:app` uses `UniversalDetector`; that would be a new
dependency here. `jdk.charsets` joined the packaged runtime, because `java.base` alone has neither `windows-1256` nor `Shift_JIS`.

### Placement: what is honoured

`CuePlacement` on `SubtitleCue`: a point as a percentage of the picture, which part of the box sits on it, text alignment,
and a width. The renderer keeps the whole box inside the safe area, so an edge means the margin.

| Source | Honoured | Not honoured |
|---|---|---|
| SSA tag, in SRT or VTT | `{\an1}` to `{\an9}`, first valid one wins, and it beats VTT settings | `\a`, `\pos`, `\move`, and every other override |
| VTT | `line:N%[,align]`, `position:N%[,align]`, `align:` all six values, `size:N%` | `vertical:`, `region:`. Drawn horizontal, in the default region |
| VTT `line:N` | Approximately: rows of 5.33% from the top, or from the bottom when negative | Real row height, which depends on the font |
| TTML | `tts:origin`, `tts:extent` in percent, `tts:displayAlign`, `tts:textAlign`, through `region` and `style` | Pixel values, `tts:writingMode`, nested regions |
| SRT | | `X1:` coordinates |

Overlapping cues at the same placement stack in time order. **Cues at different placements are not kept from overlapping
each other.**

**Styling: none is kept.** `<i>`, `<b>`, `<font>`, `{\b1}`, `<c.yellow>` and TTML colours are removed, and the text is
drawn in the viewer's `CaptionStyle` defaults: white, black outline, the app's font. Text is plain, in `BasicText`, with no
markup and no links. **U+202E in a cue stays,** as `KMP-21` decided, because Thai and right-to-left subtitles use those
characters. It reverses its own line and no other, because each line is its own paragraph (`SubtitleOverlayTest`). In a
*label* it is removed.

### Security

**The fetch is in the app process, not the media host.** The host's Seatbelt policy lets it connect anywhere but
localhost, so a plugin's URL could reach the LAN from there, with nothing able to check the address. In the app, the poster
client checks the address of every connection, including each redirect hop. What the app then does with the bytes is
bounded and pure: no `Regex`, linear time, input treated as hostile.

| What | How | Test |
|---|---|---|
| Scheme, shape | http(s) with a host, no userinfo or controls, at most 16 KiB, when the list arrives. No lookup: nobody chose it yet | `HostProtocolTest` |
| Count, label | At most 200, in host and app. Label at most 128 chars, no controls, format characters or line separators; blank falls back on the tag or drops the track | `HostProtocolTest` |
| Address | `isPublicHttpUrl` before anything opens, then the DNS filter and connection check on every hop | `SubtitleLoaderTest`: 127.0.0.1 gets no TCP connection; with the URL check off it gets no request; a redirect to it gets no request |
| Size | 8 MiB after decompression, whichever way gzip arrives | `SubtitleLoaderTest`: a 64 MiB bomb, both ways |
| Time | 20 s for the whole call, body included | `SubtitleLoaderTest`: a server that stops sending |
| Sent | Only the track's `Referer` and `User-Agent`, as for a link. No cookie jar, no cache | `SubtitleLoaderTest` |

**A track that needs another header, such as `Authorization`, will fail to fetch.** As with posters, the host a plugin
names sees this machine's address when someone picks that track.

### Timing

libVLC's reports are about 250 ms apart, so the position is run forward at wall-clock speed from the last one, by at most
1 s. A pause holds the estimate. A seek moves it at once and ignores reports still about the old position. A cue is looked up
every 40 ms.

Measured with `--check-subtitle-render` on a 90 s video with no audio stream, whose frames carry their own number in a strip
along the top, through the real sandboxed libVLC. Each new picture's own time against the clock the cues read from, in ms:

| Phase | n | p50 | p95 | max |
|---|---|---:|---:|---:|
| Playing | 326 | 9 | 16 | 30 |
| After resume | 89 | 10 | 17 | 19 |
| After a seek has landed | 90 | 9 | 16 | 18 |

The 800 x 520 run agrees within 2 ms at p95. Add up to 40 ms for the lookup and one frame to draw, so a cue is on screen about
72 ms from the picture at p95, inside the 100 ms asked for. A pause moved the clock 1 ms as it took effect, then 0 ms in 1.5 s. A
+10 s seek moved the clock on the same frame, and the picture followed 29 to 67 ms later. The largest figure in the seek phase, about
9.8 s, is that one frame. **This measures the clock and the picture, not the pixels of the cue;** the PNGs show those agreeing, with
the burned-in second and the cue's second the same in each.

### Words

Three new keys, in all 62 directories: `subtitles_load_failed`, `subtitles_format_unsupported`, `subtitles_file_empty`. Reused:
`player_subtitles_settings`, `no_subtitles` (the Off row, and what an empty list says), `loading`.

**Not reviewed by a native speaker.** All of it is machine translation. Least certain, as in `I18N-09`: `ti`, `ckb`, `so`,
`mt`, `am`, `my`, `or`, `as`, `kn`, `apc`. `qt` uses only words its own file already has.

Baseline: fourth move, additive, proof in its README. 0 lines lost or changed in any file. `app/api/classes-jar.api` gains exactly
the 3 `R` fields; **`SubtitleCue` did not reach it**, because `:media:model` is not in the jar.

### Not done

- **Remembering a choice.** Off every time, for every stream. *Since `BUG-22`: remembered as a language, as on Android.*
- **Offset.** `SubtitleDelay` exists and `activeAt` takes it. There is no control, because one needs strings and a design.
- **Caption settings.** `CaptionStyle` defaults only. `font` is not read, and `RAISED` and `DEPRESSED` draw as a shadow.
- **Styling, per the table above.**
- **`X-TIMESTAMP-MAP`,** and subtitles embedded in the video.
- **A `SubtitleCue` for `:app`.** Android still uses `CustomSubripParser`.
- **The screenshot suite.** It cannot see `:desktopApp` (`DEC-10`), so these are offscreen renders that someone looks at.

### Verification

Local, Temurin 17.0.20.1, `TZ=UTC`. CI is off, so all of it.

**The real plugins, `--check-extensions --verbose`,** in the packaged `.app` with a fresh `user.home`, reporting per track: bytes, format,
cue count, first cue time and its length, never a URL or a word. Numbers are in the table above.

**The real screen, `--check-subtitle-render`,** which drives `PlayerScreen` offscreen over the real media host with real key events:

- Coded video, 1280 x 800 and 800 x 520: exit 0.
- YouTube, `nasa`, 1280 x 800: 21 tracks, English chosen through the list, a cue on a real frame, pause and seek. Exit 0.
- ARD, `nasa`, 800 x 520: 2 tracks, the EBU-TT-D one chosen. Exit 0.

**Silent, three ways:** `--no-audio`, the sandbox refuses the host every audio service (`mediaHostRules(audio = false)`, tested), and a
plugin's separate audio URL is not passed on. Nobody listened. `--check-playback`, which plays sound, was not run.

The video and cues, for anyone repeating this: a white 1280 x 720 at 30 fps, 16 `drawbox` filters writing bit *k* of `n` as a 40 x 16
block along the top, 14 more drawing the second in seven segments, and an SRT with one cue a second.

**Tests:** `media:subtitle` 139 in `commonTest`, also run on the iOS simulator. `desktopApp` player package 90, under real pointer and key events
where it is a screen, and PNGs at 1280 x 800 and 800 x 520 that were looked at, because `DEC-10` means a build cannot.

**Mutation checks,** each alone, each red:

| Mutation | Failed by |
|---|---|
| `\an` ignored, or VTT settings ignored | 6 and 7 tests in `CuePlacementTest` |
| Percent not clamped | `out of range values are clamped to the picture` |
| Spaces end a cue again | `a line of spaces after the timing does not lose the cue` |
| EBU hours kept | `only an ebu file loses its hours`, and the ARD shape |
| TTML: `br`, line cut, cue cap, region | `the bounds hold`, and the two real shapes |
| Clock: no extrapolation, or no cap on it | 8 tests; `a stream that stops reporting cannot run away` |
| Pause falls back to the last report | `a pause holds what was on screen` and 2 more |
| A stale report undoes a seek | `a report from before the seek, still in the pipe` |
| No URL check | `the real loader opens nothing for an address on this machine` |
| A plain client, no address check | `the url check alone is not what stops it`, `a redirect to a refused address` |
| No read bound | `a huge body with no length`, `a gzip bomb` |
| No unpacking of a `.gz` | `gzip is unpacked`, `a gzip bomb` |
| No status check | `an error status is a failed fetch` |
| A fetch not cancelled | `a slow file cannot replace a later choice` |
| No key guard under the list | 9 tests in `PlayerScreenTest` |
| Escape ignores the list | `escape closes the list before it leaves the player` |
| A seek does not move the clock | `a cue appears when it starts…` |
| No failure notice | 4 tests |
| No lift, no picture rect, no clamp, no outline | `SubtitleOverlayTest`, 1 to 3 tests each |
| A label keeps U+202E, any scheme passes | `HostProtocolTest` |
| The protocol reads no subtitles | `links carry their subtitles across the boundary` |

Three did not go red at first. **Ignoring the declared charset survived,** because the test's byte reads as `é` in `windows-1256` as well; the
test now uses bytes only the declaration can read, and it goes red. **A line consuming Left and Right under the list survived twice,** with a
stronger test the second time, so that line was dead and is deleted. **The host's own `subtitleCallback`** has no unit test, because it needs a
loaded plugin: what proves it is the real check finding 21 tracks.

### Not verified

- **The live window.** It cannot be driven from here, so a person must try the steps in the PR. The check drives the same composable offscreen.
- **Cues against speech.** Silent, on purpose. A real title's cue was seen on a plausible frame, and the measured sync is on the coded video.
- **A real file in a legacy encoding.** Every real track measured was UTF-8. The fallback has tests with made-up bytes.
- **The EBU rule beyond ARD.** It rests on two ARD titles.
- **The plugins beyond these six.** `SubtitleFile` from the wider catalogue was not surveyed, so a format not named here may turn up. It would say so.

### Found and left

```
Found: libVLC's format callback reports more lines than the picture has, and the frame is stretched to it: 1280x720 arrives as 1280x738, 480x270 as 480x290, in MediaHost.kt getBufferFormat
Suggest: size the buffer from the visible height (`newFormatSize` is there and ignored), and measure it on a few codecs
Why: pictures are 2.5 to 7% too tall. Older than this task, and not about subtitles: cues are placed on the frame as it is drawn
```

Since fixed as `BUG-15`, and `newFormatSize` was not the way: it only repeats the size the app returned.

```
Found: YouTube's timedtext answered 429 after this task's repeated fetches, which the player shows as "Couldn't load these subtitles"
Suggest: leave it. The failure is legible and the track can be chosen again. A cache or backoff would be its own task
Why: measured once, with curl on a dummy URL as well as through the check, and gone on the next run; a person choosing one track is far from that rate
```

---

## KMP-26 — `:feature:library`: bookmarks, watch progress and resume, on desktop · **built 2026-11-03**

**Status:** done · Phase 7 vertical 3 (plan §7, 970 lines on Android) · desktop first, as `KMP-20` was

This is the gap `KMP-15` named — its "Not built" list reads *"resume, watch progress"* and its departures
from the artboards include *"bookmark"* — and it is what `docs/plans/desktop-design.md` meant by the nav
sidebar *"arrives with Library"*. The desktop app no longer forgets everything between launches.

### What it does

**Continue Watching works end to end.** Play anything for more than half a minute and it is on the list,
newest first, with a progress rule across the foot of its card. Press the card and the title's page opens
with that episode's sources already being fetched, and the player starts where it was left. Past 95% the
title leaves the list.

**Six lists** — Watching, Completed, On-Hold, Dropped, Plan to Watch, Favorites — chosen from a panel on
each card. **The library is where a title is organised, and the detail page is where it is watched:** the
picker is here and not on the detail page because the detail page has no overlay slot, and giving it one
means changing `:feature:detail` to carry a control it does not own. A bookmark button there is a
convenience that can follow.

### The parts, and where each lives

| | |
|---|---|
| `:feature:library` commonMain | `LibraryContracts` (the identity, the buckets, `LibraryTitle`, `WatchPosition`, the `LibraryStore` seam), `LibraryMvi`, `LibraryShelf` (the pure fold), `LibraryViewModel`, `LibraryCopy`, `desktop/LibraryScreenDesktop` |
| `:desktopApp` | `DesktopLibraryStore` (two files), `WatchRecorder`, `LibraryHost`, a third `TopLevelDestination`, and `Screen.Library` |
| Changed, because the task needs it | `PlayerScreen` gained `startAtMillis` and `onProgress`; `TopLevelScreen` stopped being a `Boolean`; `DetailHost` learnt what a resume is and what the library needs to record a position |

No `:library` edge, so the module is in the iOS canary — **18 modules now, up from 17.**

### Identity: the one decision worth arguing about

A library row names a title by **the same opaque string the detail flow already takes** —
`DesktopSearchSource.encodeId(apiName, url)`. Nothing parses it and nothing hashes it, so a row reopens a
title by handing the id straight back to the screen a search hit would open, and no new plumbing was needed.

`:app` does it differently and measurably worse. `getLoadResponseIdFromUrl` is a 32-bit `String.hashCode()`
of the provider-*relative* path with its slashes stripped, and the provider's name is used only to find the
prefix to strip — **it is not part of what is hashed.** Two providers that both serve `/movie/dune` collide
to one id and share a bucket, a record and a position. The id also changes depending on whether the
extension is loaded, because an unloaded one means the whole URL is hashed instead. Episode ids are worse
still: `mainId + episode + idIndex * 1_000_000 + season * 10_000` for anime and
`mainId + season * 100_000 + index + 1` for series, both `Int` addition on a hash, both wrapping, and only
collisions *within* one title are guarded. Desktop keys an episode by the provider's own opaque `data`
instead, which is the string that goes back to `DetailSource.links` unread.

**The two stores are not shared** — a bookmark on the phone does not appear on the desktop, the call
`DesktopSearchHistory` already made. The cost of the desktop choice is honest: an id dies when a provider
changes its URLs, the row stays, the card draws, and opening it fails like any dead link. A hash would not
have survived that either; it would have hidden which row broke.

### Storage: two files, and why not `:core:storage`

`~/.cloudstream4/library.tsv` and `~/.cloudstream4/watch-positions.tsv`, beside `search-history.tsv`.
Atomic replace through a staging file in the same directory, a header line, a 4 MiB cap, 2,000 rows each,
and five characters escaped — a title's id carries a NUL on purpose and a plugin's title can carry anything.

**No checksum, unlike `ExtensionDecisions`,** and that is a decision: that file decides whether third-party
code runs, so a half-written one must read as "nothing was approved". This one decides what is on a list, so
a truncated line costs that line and not the library.

**Everything is sanitised again on the way out**, because the file was written from what a plugin returned:
titles and provider names lose control and FORMAT characters and are truncated at 200, and a poster that is
not plain `http(s)` is dropped before it can reach the image loader. The id is only escaped, never
sanitised.

`:core:storage`'s `FileKeyValueJsonStore` is very nearly this class's shape and is still unused. `BUG-05`
and `DEC-12` park it until something consumes it; **this is the strongest candidate yet and it is the
owner's call, not this task's.** Swapping it later touches one class.

### What `:app` does that this deliberately does not

- **Advance the resume pointer to the next episode at 90%.** That needs an episode order, and
  `DetailEpisode.data` is opaque. Here 95% simply means watched and the next episode is one you pick.
- **Keep the display fields somewhere else.** `:app`'s resume row carries no name or poster of its own; it
  reads `DOWNLOAD_HEADER_CACHE`, which is not account-scoped while every bookmark is, and which the
  downloader prunes — hence its own comment about restoring data for affected users. A row here carries its
  own fields.
- **Subscriptions.** `:app`'s seventh list is about notifications for new episodes, which desktop has no
  machinery for at all.

### No new strings, and the one place that shows

Every word already existed in `:core:strings` and is already translated into 62 locales — the list names are
the same `type_*` keys `:app`'s `WatchType` points at, so the two screens cannot drift apart in one language
and not another. A new key would have been 62 translations, a regenerated set of `strings.xml` files, and a
re-recorded `classes-jar.api`, because an `R` field is published ABI.

The cost: there is no "could not read your library" string, so the failure body is `plugin_load_fail`
("Could not load %s") filled with the word "Library". **In a language that inflects the object of that verb
the result will be grammatically off.** Written down rather than hidden; the fix is a key of its own.

### Verified

- **The four gates, local, on this tree:** `checkKotlinAbi` 0 · `assemblePrereleaseDebug lint check
  -Pcloudstream.strings.guards=fail` 0 · `:app:checkExtensionApi` 0 · `iosCanary` 0 (18 modules).
  **1,447 tests, 0 failures.**
- **14 of the new tests run on a real iOS simulator** (`:feature:library:iosSimulatorArm64Test`, 14/0) —
  the fold, the resume rule and the caps are pure Kotlin on purpose.
- **77 new tests in all**: 14 fold, 16 ViewModel, 18 store (over real files in a temp directory — nothing
  touches `~/.cloudstream4`), 10 recorder, 11 screen, 8 words.
- **Rendered offscreen at 1280×800 and 800×520** through `ImageComposeScene` with real pointer events, and
  looked at: the lists, a card with progress, the picker, loading, empty, failed, and Greek at the minimum
  window. The whole window too — a click on the Library tab, the store's title on screen, its picker open,
  and Escape closing it.

**Two defects the renders caught**, neither visible to a gate:

- A one-line title beside a two-line one put their buttons 24 dp apart. The title is pinned to two lines.
- `WatchRecorder` wrote nothing on its first call under an injected clock: "never written" was stored as
  `0L` and compared against the interval. It is null now. With the real wall clock it happened to work,
  which is the kind of bug only a test with its own clock finds.

### Not done

- **A bookmark button on the detail page.** See above: it needs a slot in `:feature:detail`.
- **Watch progress on search and home cards.** `SearchResultItem.watchProgress` already exists and is still
  always null on desktop; filling it means those sources reading the library store.
- **Resume does not start playing by itself.** It opens the title's page with that episode's sources being
  fetched; a link still has to be chosen, and that is the screen that chooses one.
- **Android.** `:app`'s `LibraryViewModel` and its fragments are untouched, as home's and search's were.
- **Sorting.** `:app` has nine sort orders (six that `LocalList` advertises). This has one per list, newest
  first, and no filter.
- **The live window** — never verified, as with every desktop screen so far. Everything above was driven
  offscreen.

### Found and not fixed

```
Found: the iOS test gate has been red since :feature:home landed (#92) — `iosSimulatorTest` cannot compile
       `HomePagingTest`, whose five backticked names contain a comma, which Kotlin/Native refuses
Suggest: rename those five, which is what this task did to its own
Why: it is existing code in another vertical, and a broken gate deserves its own revertible change.
     Filed as `BUG-20` in defects.md (filed as `BUG-17`, renumbered — #106 took that id first)
```

## KMP-27 — Full screen in the desktop player · **measured 2026-11-03**

**Status:** done · #117. **Owner's request 2026-11-03:** "add enter full screen on video player".

A control at the right end of the transport row, F and Cmd+F (`docs/plans/desktop-design.md` listed Cmd+F as not done).
Escape closes the subtitle list, then full screen, and only then leaves the player. Closing the player, by Back or by
the stream ending, returns the window to where it was when playback began; a window already full screen stays so.

It goes through `WindowState.placement`, so it is the window's real full screen, and not a second idea of it.

| Measured | Result |
|---|---|
| Real macOS window, throwaway probe, not committed | `Fullscreen` 3440x1440 on a 3440x1440 screen, back to `Floating` 960x540 |
| The same, `window.placement` set on the window rather than through `WindowState` | `PlayerFullscreen.on` true, and `leave()` returned it to 960x540 |
| `PlayerScreenTest` | keys, the control, Escape's order, restore from floating and maximized, at 800 x 450 |

**Not measured:** a person pressing the window's green button, which the second row stands in for; the transition with
video playing in a real player; a second screen.

**Not built:** a double click on the picture, and hiding the pointer once the controls have gone.

**Strings:** `player_enter_fullscreen`, `player_exit_fullscreen`, in all 62 directories. 48 are Google's own wording from
`media3-ui` 1.9.3 (`exo_controls_fullscreen_*_description`, Apache-2.0), which this app already ships. Machine
translated, not reviewed by a native speaker: `apc`, `ars`, `as`, `ckb`, `eo`, `mt`, `nn`, `or`, `pt-BR`, `so`, `ta`,
`ti`; `qt` uses only words from its own file. `ta` was replaced because media3's text there describes a button.
Seventh move of the oracle baseline.

## KMP-28 — Volume and mute in the desktop player · **measured 2026-11-03**

**Status:** done · #118. The player had no volume control at all. Asked for under "continue development", and chosen by me as
the largest gap in playback.

A speaker control and an 80 wide rule in the transport row, up and down in steps of 5, M to mute. The level is shared by
every stream in a run, and a level chosen while muted unmutes. **It is not kept between runs.**

**libVLC refuses a level while its audio output starts, and it was measured.** Through the real sandboxed host, playing a
silent 30 s WAV: a level sent just after play returned `ok=false, read=-1`, and so did one sent at the `playing` event,
so neither place can be relied on. A muted stream would have started at full volume. `MediaHost.keepVolume` therefore
checks every 20 ms and sets it again until it reads back.

| Measured, 3 runs, level 0 sent just after play | Result |
|---|---|
| libVLC reads 100 until the level takes | about 30 to 50 ms after the app is told `playing` |
| Afterwards | stays at the level, no further corrections logged |

**Not measured:** whether anything of those 30 to 50 ms can be heard at the start of a stream when muted. Nobody has
listened. The measurement was of what libVLC reports, on a silent file, and the probe was not committed.

**Not built:** remembering the level between runs, scroll wheel on the rule, a percentage readout.

**Strings:** `player_mute`, `player_unmute`, in all 62 directories, all machine translated and not reviewed by a native
speaker; `qt` uses only words from its own file. Eighth move of the oracle baseline.

## KMP-31 — Picture size, and the player's controls brought back to artboard 7d · **measured 2026-11-04**

**Status:** in pr. **Owner's request 2026-11-04:** "option to set the stretch mode, fill screen, fill width with
maintaining aspect ratio, original aspect ratio", and then "are you following design??".

**Picture size:** Original aspect ratio (all of the picture, with bars), Fill width (own shape, top and bottom cut off
if the picture is taller), Fill screen (stretched to every edge, so its shape can change). R, or the selector. Shared by
every stream and kept in `~/.cloudstream4/picture-fit`. A cue is placed on the part of the picture that can be seen.

**The answer to the owner's question was no, and these are what it found.** `KMP-27` and `KMP-28` were built from the
tokens and `docs/plans/desktop-design.md`, without opening `design/TV Detail Player.dc.html` or the semantics table in
`Design System v2`:

| Design says | What had shipped | Now |
|---|---|---|
| `live` red is playback state only | The volume rule was red (#118) | Ink |
| Selectors read out what is playing; "a generic icon is exactly what makes today's HUD unanswerable" (7d) | Subtitles were a speech bubble, in the transport row | `PlayerSelector`, value over label, in its own row |
| Selectors stay mounted through failure (7d) | Nothing but the failure text and Back | The row stays; Subtitles leaves only because a failed stream has none to show |
| Selected = raised fill + ink label + muted marker; white is focus only | A white tick and no fill | As the design says |

**Not brought in line:** 7d's Speed selector, buffered band, BUFFERING badge and skip intro; 7e's right-hand panel with a
Done row, because `docs/plans/desktop-design.md` already chose a short list above the controls; Source and Audio
selectors, which `KMP-29` and `KMP-30` add to this row.

**Measured:** `:desktopApp:jvmTest` 328 tests, 0 failures. Each fit against a wide and a tall frame, by pixels and read as
PNGs at 800 x 600 and 800 x 450. `Picture size` is 4 new keys in all 62 directories, machine translated and not reviewed
by a native speaker; `qt` uses only words from its own file. Ninth move of the oracle baseline.

**Not measured:** a real film in each fit in a live window. Fill width on a tall picture cuts it, by the owner's wording.

## KMP-30 — Audio tracks in the desktop player, and the volume kept between launches · **measured 2026-11-04**

**Status:** done · #120.

**Volume.** One line in `~/.cloudstream4/player-volume`, `70` or `70 muted`. Anything else reads as never having chosen, and
reading writes nothing. A write waits 500 ms and writes the latest, so a drag is one write and not one per pixel, and
quitting inside that wait loses that one change. **Mute is kept on purpose:** silence is visible (the crossed speaker is on
screen while a stream starts) and one key fixes it, whereas an unasked-for full level cannot be taken back.

**Audio tracks.** A selector in the player's selector row, shown only with more than one track, reads out what is playing as
artboard 7d asks: `English · 5.1 · EAC3` over its caption. It and A open the list; pressing a row commits (7e).

Measured through the real sandboxed host, on silent `mkv` files generated with the `ffmpeg` already on this machine and not
committed: one with three AAC tracks (one titled with a U+202E override), one with one track, and one with a separate audio
file as `:input-slave`. The probes were not committed.

| Measured | Result |
|---|---|
| When libVLC lists tracks | Not at play: the list arrives 1.1 to 4.7 s after play, so the host polls every 250 ms and the selector appears then |
| The two libVLC lists share ids | Yes: channels and codec from `media().info()` matched the descriptions by id on all tracks, `2/mp4a` and `1/mp4a` |
| Readout on the three-track file | `English · 2.0 · AAC` + title `Original`; `German · 2.0 · AAC`; `Hindi · 1.0 · AAC` + title `Commentary gnp.exe` |
| A U+202E override in a title | Reached the host raw and was stripped by the app's reader |
| `setTrack` as soon as listed | Accepted (returned the id), and the host reported it within about 250 ms |
| Position across a switch, 3 runs | 1,504, 1,487 and 1,202 ms of position in about 1,500 ms of wall time, 0 buffering flips, frames kept coming |
| A wrong id, sent before play and after | Ignored, selection unchanged |
| The chosen track after a 5 s seek | Unchanged |
| A separate audio file (`audioUrl`) | Plays, and lists as 2 tracks with the separate one selected by libVLC itself |
| One track | No selector; the list had 1 entry |

**Not measured.** Whether the tracks actually sound different, because the files are silent and nobody has listened. 5.1,
EAC3, DTS and TrueHD: the codec table is written from libVLC's codes but only AAC went through it. HLS and DASH streams,
whose audio renditions may list differently or later. The `:input-slave` run's final position (2,727 ms) was lower than the
others' after the same 5 s seek; that was not looked into and may be a seek on a slaved file rather than anything here.

**Not built:** language preference across streams, as `subtitle-language` has; a remembered track per title. Departures from
7e are listed in `docs/plans/desktop-design.md`.

Reused `audio_tracks`, so no new string and no baseline move. It exists in 41 of the 62 directories and falls back to English
in the rest.
