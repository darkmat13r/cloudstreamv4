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

**Status:** **steps 1 to 4 of 5 written, on the TV layout only; steps 3 and 4 are draft #91 and are
not merged** (plan row 18, Phase 7 vertical 1) · the seams landed in #79, the copy types went in
`I18N-08` · **run on the `Television_720p` emulator, not on a real TV** (`DEC-17` accepts that for switching) · phone and
emulator layouts still use `SearchFragment` · step 5 (deleting it) waits a release and the owner

> **The state machine's defects were split out and landed on their own, 2026-11-03.** Building the
> Android host (step 4) found four defects in `SearchViewModel`, and all four were in the **desktop**
> app as shipped, not just the unbuilt Android path. Three are `BUG-24`; the fourth was found
> independently from a screenshot of the owner's and landed first as `BUG-23` (#114). #91 keeps only
> what needs a device: `SearchTvFragment`, the one-line `MainActivity` repoint,
> `ApiRepositorySearchSource`, `DataStoreSearchHistory` and the `classes.jar` lines those add.

### Android TV opens the new screen: steps 3 and 4

`SearchTvFragment` is the glue. `MainActivity` sends `navigation_search` to it on the TV layout:

    if (isLayout(TV)) navController.graph.openSearchOnTvScreen()

**Rollback is deleting that line.** The plan asks for no kill switch, so there is none.

**Why not the one-line XML repoint the recipe describes.** A nav graph has no per-layout form, and
`isLayout(TV)` is a preference with an auto-detected default, so no resource qualifier can express
it. A second node would be worse: `navigation_search` is also the menu item, the back-stack key and
what `MainActivity` compares against. `FragmentNavigator.Destination.setClassName` changes the class
behind the same node, and the graph is already set at that point (`MainActivity` calls
`navController.navigate` later in the same `onCreate`). `mobile_navigation.xml` is unchanged.

**Known edge:** Settings' layout switch calls `activity.recreate()`, which keeps saved back stacks. A
user who visited search and then switches layout can get the other layout's fragment back on the
next visit, until the next cold start. Each still works in either layout. Not handled.

#### The in-app language

Read, not run: `stringResource` resolves through `rememberResourceEnvironment`, which reads
`androidx.compose.ui.text.intl.Locale.current` (`javap` on `components-resources-android` 1.12.0).
That is `android.os.LocaleList.getDefault()` on API 24+ and `java.util.Locale.getDefault()` on 23
(`javap` on `ui-text-android` 1.12.0), and `LocaleList.getDefault()` puts `Locale.getDefault()` first
(SDK 36 source). `CommonActivity.setLocale` calls `Locale.setDefault`, and `MainActivity` calls it
before composition and again from `onConfigurationChanged`, so **`:core:strings` words are correct for
a non-system language.** `Locale.current` is read when composing, and the settings language change
recreates the activity. Hebrew and Indonesian are the `LocaleCopies` work already in place.

**`ApiRepositorySearchSource` was wrong**, and is what `AndroidSearchCopy.kt`'s trap still applied to:
its card labels (type, quality, dub) came from the application context, whose configuration never
learns the in-app language. It now rebuilds a configuration context from `Locale.getDefault()` per
round. A label already on screen keeps its old language until the next search, because the ViewModel
survives the language change.

The `Known limitation` on `relativeWhenLabel` was wrong and is removed: SDK 36's
`DateUtils.getRelativeTimeSpanString` uses `Locale.getDefault()`. No source older than 36 was
available to check.

#### Defects the switch exposed, each measured failing first

`MainAPI.hasQuickSearch` defaults to false, and the state machine runs a quick search on every settled
keystroke. Against a real provider set that was a failure band with `NO_QUICK_SEARCH` on the first
letter. Five new `SearchViewModelTest` cases failed against the previous ViewModel:

| Case | Where it was fixed |
|---|---|
| No quick-search source: typing showed a failure | `SearchSources.quickSearchable` — `BUG-24`, merged |
| A debounce pending at Enter replaced the full search with the quick subset | the `submitted` guard — `BUG-24`, merged |
| Text cleared inside the debounce window was still searched | the settled text must equal the field — `BUG-24`, merged |
| Same query typed after a clear stalled on the skeleton | `distinctUntilChanged` deleted from `DebounceQuery` — `BUG-23` (#114), merged |
| Source count went stale after plugins loaded or settings changed | `SearchAction.SourcesChanged` — `BUG-24`, merged. **This PR still sends it from `onResume` and `afterPluginsLoadedEvent`**, which is the Android caller |

**None of these five is in this PR any more.** They were all in the shipped desktop app too, so they
landed on their own rather than waiting for a television. `dune` → `dunes` → `dune` inside one 200 ms
window, listed here as still open, went with `BUG-23`.

#### Every behaviour of `SearchFragment`, on the TV path

| Behaviour | New path |
|---|---|
| Full search on submit, history row written | **Kept**, same key and folder, one row across both paths |
| Re-running a recent search moves it to the top | **Kept** |
| No search history for one character | **Kept** for history. A one-character *search* now runs; the old path refused it |
| Clear-all asks first (same dialog, same strings) | **Kept**, the glue intercepts `RecentCleared` |
| Intent search: `SEARCH_QUERY` argument and `nextSearchQuery`, cleared after use | **Kept** |
| Open a result in the layout's results destination, with the title as placeholder | **Kept**, `AppContextUtils.loadResult` |
| Posters: singleton Coil loader, app user agent, provider headers | **Kept**. `SearchResultItem.posterHeaders` is new; without it hotlink-protected posters would 403 |
| Language setting and `prefer_media_type_key` (keeps NSFW out by default) | **Kept**. The seam ignored the second until now |
| Persisted provider picks | **Kept, read-only.** Honoured and counted, but the panel that changes them (4d) is not built |
| Empty selection means "search everything" | **Changed.** Falls back to language and media type, never to NSFW |
| Hide-by-quality setting | **Kept** |
| Pinned providers first | **Kept** |
| Plugins loading while the screen is open | **Kept** |
| System-bar padding, keyboard hidden on submit and on leaving | **Kept** |
| Failed providers | **Better**: named on screen. The old screen showed nothing |
| Type chips (`searchPreferenceTags`) | **Lost, and not applied.** See below |
| Provider and type filter sheet | **Lost.** Artboard 4d not built |
| **Voice search** (mic, `RecognizerIntent`) | **Lost.** No icon pack (`DEC-11`) and not drawn |
| Search suggestions (`search_suggestions_enabled`) | **Lost.** 8a/8b not built |
| Remove one history row | **Lost.** `RecentRemoved` has no affordance yet |
| Paging: rail expand, `Load more`, the "see all" sheet | **Lost.** First page only |
| Long-press a card for the metadata popup | **Lost.** The card has one action |
| `advanced_search = false` flat grid | **Lost.** Always rails |
| Keyboard opening on arrival (`SOFT_INPUT_STATE_VISIBLE`) | **Lost.** It should open on OK in the field; not observed |
| Nav rail long-press scrolls to the top | **Lost**, and harmless: it finds no recycler |
| App theme and accent setting | **Not read.** The new design's dark colours, as on desktop |
| `filterSearchResponse` (dub filter) | Was dead code: defined, never called |

**The type chips are a decision for the owner.** They default to Movie and TV, and the screen cannot
show or change them, so applying them would silently narrow every search on a fresh install. They
are ignored, so a search can ask more providers than before.

#### `classes-jar.api`

67 lines added, 0 removed, all under `com/lagradost/cloudstream4/search/`: `SearchTvFragment` (with its
`$stable` and five `viewModels` lambdas), `SearchTvFragmentKt`, `ComposableSingletons$SearchTvFragmentKt`,
`ApiRepositorySearchSourceKt$pinnedFirst$$inlined$sortedBy$1`, plus new members on
`ApiRepositorySearchSource`, `ApiRepositorySearchSourceKt`, `DataStoreSearchHistory` and
`DataStoreSearchHistoryKt`. The first draft removed two synthetic accessors from
`ApiRepositorySearchSource`; the source was reshaped so the dump stays strictly additive. Nothing in
`com/lagradost/cloudstream3` moved, and `MainActivity`'s one line adds no member.

The APK carries the strings (65 `composeResources` string files, including `values-b+he` and
`values-b+id`).

#### Not verified

`TvSearchNavigationTest` runs the real app on `Television_720p`: the rail opens `SearchTvFragment`, and D-pad
keys through `CommonActivity.dispatchKeyEvent` reach Compose and every stop on the idle screen, with no dead end
or trap. Still unobserved: a physical remote (keys are injected as `SOURCE_HDMI`, see `INF-10`), focus
restoration on return from a result, and real provider traffic, because the emulator has no extensions.

`:feature:search` holds the contracts, the provider seam, the MVI state machine and the TV screen.
Phase 7's recipe is five steps — state machine, screens, glue `Fragment`, repoint one nav node,
delete the old fragment a release later.

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
- [x] ~~`SearchSource` and `SearchHistoryStore` implementations in `:app`~~ — #79; the copy types
      were removed by `I18N-08`
- [x] ~~Glue `Fragment` (step 3) and **one** nav node repointed (step 4)~~ — TV layout only; the old
      fragment stays as the rollback lever
- [x] ~~Run it on a TV emulator~~ — `TvSearchNavigationTest`, the device gate in `docs/HANDOVER.md`
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
- The audio selector (7e; the source selector came in `KMP-29`), resume, watch progress, focus restored to the activated item on back. Subtitles came in `KMP-25`.
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

**Status:** done · #119. **Owner's request 2026-11-04:** "option to set the stretch mode, fill screen, fill width with
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

## KMP-29 — Choosing another source inside the desktop player · **measured 2026-11-04**

**Status:** done · #121. Someone whose link fails or stutters had to leave playback to try another.

The Source selector, first in the selectors row of `KMP-31`, reads out what is playing (`VidSrc Pro · 1080p` over
`Source`, artboard 7d) and stays through loading and failure, as 7d's error state requires. It and S open the list of what
the title's search has found so far. Pressing a row commits it, with no Apply (7e). New links join the list when it is next
opened, because rows must not slide under a pointer. Every link on it has been through the same URL policy as before,
because the list is `DetailState.links`, which `DesktopDetailSource` filters.

A switch starts a new host for the new link, releases the old one, and keeps the screen, so by construction the subtitle
track, full screen, the volume and the picture size stay as they were. No test drives that, because `PlayerScreen` needs a
real `VlcPlayer` and the tests drive `PlayerContent`. `ResumeSeek` starts the new stream at the old one's position, and
reports nothing to `WatchRecorder` until it is about there, so a slow start cannot overwrite the person's place. That
also applies to a resume from the library, which until now reported from zero. It does not report at all if a stream
cannot seek, and then the saved position stays where it was.

| Measured, through two real sandboxed hosts, silent 120 s WAVs, a probe that was not committed, 2 runs | Result |
|---|---|
| Switch from about 32,200 ms | the new host reached about 32,400 ms in 2.6 to 3.0 s, its first reported position |
| Status reports kept out of the record on the way there | 4 of 11 or 12 |
| A further switch to a link that fails, position 0 | the next one would still start at about 34,000 ms |

`PlayerScreenTest` covers the selector through failure and loading, the commit, the current row, Escape's order, a click
outside, and `ResumeSeek`. The rendering of the list, of a failed row and of the failed state were read as PNGs. The render
also caught `%s` printed literally, because Compose resources fill only `%1$s`.

**Not measured:** a real switch in a live window, with video, or a person watching it; how long a switch takes between
two remote streams; subtitle timing during the switch, which reads the new host's position and so goes blank until the seek lands.

**Departures from 7e** are in `docs/plans/desktop-design.md`.

**Strings:** `player_source_playing`, `player_source_switch`, `player_source_retry`, `player_source_resumes_at`, in all 62
directories, all machine translated and not reviewed by a native speaker; `qt` uses only words from its own file. Tenth
move of the oracle baseline. The selector's label reuses `video_source`, which 9 directories do not have, so they fall back.

## KMP-32 — The home source chosen last opens again · **measured 2026-11-04**

**Status:** in pr. **Owner's request 2026-11-04:** "the last plugin source should stay default when i reopen app".

Kept in `~/.cloudstream4/home-source`. Only a person's choice is kept, so a source that is gone falls back to the first
without forgetting it, and it comes back with its plugin. This also survives the `HomeViewModel` rebuilt when an
extension is allowed or stopped. It is matched by name, because the order sources arrive in varies between launches.

**Tested:** `HomeViewModelTest` and `FileHomeSourceMemoryTest`. **Not tested:** a person choosing a source, quitting and
reopening the window. Search is not affected; it has no single source to keep.

---

## KMP-33 — The Compose rail on Android TV (queue `Q5`) · **measured 2026-11-04**

**Status:** in pr #139, waiting on the owner for its `classes-jar.api` delta · **Owner's rulings it follows:** `DEC-17`, `DS-09`. Android TV only. Rollback for one release.

`shell/TvRail.kt` in `:app` hosts `NavRailHost`, new in `:core:designsystem`: `NavRail` with the dim, the shadow and the
keys `DS-09` left to its host. Home, Search, Library, Downloads and Settings come from `res/menu/bottom_nav_menu.xml`, so
order and translated titles have one source and no string was added.

**The switch.** `TvRailSwitch.useComposeRail`. `false` means `MainActivity` builds no `TvRail`, and every line that names
one does nothing. `theSwitchPutsTheXmlRailBackAt62` runs it.

### The audit's claims, measured

- **The 14dp miss is real.** On the XML rail the page starts at 62dp and the search field's text at 98dp. With this rail
  they are 76dp and 112dp, where 112 is `ContentOriginX` 100 plus the field's own padding of 12. It was fixed by giving
  the page the token's width on TV, not by editing `nav_rail_view_width`, which phone also reads.
- **"A Compose rail needs an activity change" is true, and smaller than it sounded.** Three lines in `MainActivity`, plus
  `focusNavRail`.

### The smallest change, and why

A `ComposeView` added to `homeRoot` in code. `activity_main_tv.xml` is untouched, because the emulator layout inflates it
too, and it must keep a `NavigationRailView` for `ActivityMainBinding.bind`.

The XML rail stays, `GONE`, as the navigation seam: committing an item sets `navRailView.selectedItemId`, as the
`cloudstreamsearch://` handler and `SearchTvFragment` already do, so the debounce and the back stack are unchanged. The
mark follows the `NavController` through `updateNavBar`.

The view is 76dp wide collapsed and the whole screen expanded. A view cannot paint outside itself, so a 76dp view could
not show 188dp of rail; a permanently full-screen one would never be found by LEFT, since `FocusFinder` goes by bounds.
The page's margin does not change, so it does not reflow.

### What the real app showed

- **A `GONE` rail's items still take focus.** XML pages name them in `nextFocusLeft` (`home_change_api`), `FocusFinder`
  checks only the item's own flags, and focus went somewhere invisible, which was behind most of the first run's 22 failures out of 24. The
  hidden rail's views are now unfocusable, so the search falls through to geometry.
- **Exact return on RIGHT needed the rect.** A Compose page forgets its focused node as soon as its view loses focus, and
  `focusRestorer` does not see that exit. So `TvRail` reads the stop's rect before each D-pad key and asks the page to
  search from one pixel before it. A View takes `requestFocus` directly. If the stop has gone, RIGHT goes to the nearest
  stop towards the page.

### Found, not changed

- **Two stops lose focus on the XML rail too,** which two `theXmlRailLosesFocus…` tests measure: DOWN from Home's
  provider button (`nextFocusDown` names a hidden `home_preview_info_btt`), and RIGHT from the Library's pager. LEFT
  recovers from both. `noDeadEndsOnHome` and `noDeadEndsOnLibrary` pin them, so fixing either fails a test on purpose.
- **Settings' search field is not an exact return, and DOWN from it goes nowhere.** It expands when focused and collapses
  when not, so RIGHT lands on `Default`, the stop above it. LEFT still leaves it. Every other stop tried returns exactly.
- **`NavRail`'s brushes are not mirrored in a right-to-left layout.** The keys are. Neither has been run in one.

### What the XML rail had that this does not

- **The profile picture in the footer**, which opens account select. No board draws it and `DEC-17` does not list it.
  Home's own `home_switch_account` still opens the same list, but it did not show on Home with no extension installed.
- **Long press on an item to scroll to the top.**
- **The Library item's icon changing to the sync provider's.** `icons.md` makes that a brand mark, and no board draws it.
- **The cast mini controller starting after the rail.** It is constrained to the hidden rail, so it started at the screen's
  edge and the rail covered its first 76dp while casting. Fixed by `BUG-32`.

`DEC-13`: nothing is lost. The old TV rail's indicator was `textColor`, not the user's primary colour.

**Tested:** `TvRailNavigationTest` on `Television_720p`, `NavRailHostTest` and `TvRailDestinationsTest` on the JVM.
**Not tested:** `focusNavRail`'s call site, which needs a provider's hero on Home, and every page with content, since no
extension is installed. A real TV remote.

## KMP-34 — The TV library on Android: grid and empty page (queue `Q11`) · **measured 2026-11-04**

**Status:** in pr. Boards `5a` and `5c` of `design/TV Library.dc.html`. Sort, the provider panel and the unreachable
state (`5b`, `5d`, `5e`) are `Q12`. Android TV opens this through real navigation, and one line in `MainActivity`
switches it; deleting that line is the rollback, and `LibraryFragment` stays for one release (`DEC-17`). Phone and the
emulator layout still use `LibraryFragment`.

**The model.** `:feature:library` had no provider and only a desktop store, and `:app`'s library is `SyncAPI`. Read, not
assumed: the local list is `LocalList`, over `RESULT_WATCH_STATE` (status), `RESULT_WATCH_STATE_DATA` (`BookmarkedData`)
and `RESULT_FAVORITES_STATE_DATA` (`FavoritesData`), per account. `AndroidLibraryStore` reads those same keys.

The seam is small, and nothing published changed (`SyncAPI$Page` is untouched):

- Reads are real. **Writes refuse**, as `AndroidExtensionRuntime.install` does: nothing on Android calls one, 5a and 5c
  draw no control that writes, and a title's list is still changed on its result page. The first screen that needs a write
  implements it.
- `LibraryStore.titles()` may now return one id twice with different buckets, because on Android a title can be in
  Watching and in Favourites at once. Desktop still returns one row per id.
- Additive, all with defaults: `LibraryViewModel(initialTab)`, `LibraryTitle` and `LibraryCard` gain `typeLabel` and
  `posterHeaders`, `LibraryState` gains `counts`, `totalTitles`, `filter` and `returnTo`, and there are new actions. The
  desktop screen sends and reads none of them, so its behaviour is unchanged. `:desktopApp` compiled against this, and
  `LibraryViewModelTest`'s existing cases still pass.

Tab counts come from the rows read, so a tab cannot disagree with its page, and they are absent before the first read and
after a failed one. A list holding nothing reads 0, which is a fact.

**Every reduction from the boards, for the owner:**

| Board | Drawn | Here | Why |
|---|---|---|---|
| 5a | Provider button, `AniList` / `LAGRADOST · SYNCED 4 MIN` | Plain text: `Library` and the total titles. Not a stop | Local only until `Q12`. No sync time exists. Its slot is kept, so the control moves nothing when it lands |
| 5a | Sort button, `LAST UPDATED · NEWEST` | Empty slot. Order is newest `latestUpdatedTime` first | `Q12` |
| 5a | Five tabs | Six: Favourites added | The old TV screen has it, and people's favourites are in it |
| 5a | Rating badge, top right | None | Local bookmarks hold no rating of the user's own. `BookmarkedData.score` is the public score, which is not what the board means |
| 5a | `12 / 28 EPISODES` in the reveal | Provider name | Local bookmarks hold no episode counts: `toLibraryItem` passes null for both |
| 5a | Bar from episodes done over total | Position in the last-played part, from the keys `HomeViewModel` reads. No bar if never played | Same. A different fact, and a real one |
| 5a | Field placeholder `Filter this list` | `Filter Bookmarks` | Existing key. The magnifier is drawn, `CloudStreamIcons.Search`, through a new optional `TextField(leadingIcon)`. Search's field does not ask for it, so it renders as it did |
| 5a | Type badge in the board's words `ANIME`, `TV`, `MOVIE` | `TvType`'s own translated word, upper-cased: `TV SERIES`, `MOVIE` | Existing keys, so no new translations |
| 5c | `Nothing on hold` | The list's name, `On-Hold` | No such key; no new key added |
| 5c | `Titles you pause land here. Four are in Watching that you haven't opened in a month.` | `This list is empty. Try switching to another one.` | Nothing records when a title was last opened, so that sentence could not be true |
| 5c | `Go to Watching` | The name of the first list with titles in it, `Watching` | No such key. With the whole library empty it is `Search`, with the old screen's empty-library message |

**What the old TV screen does that this does not yet:** switching to a sync provider, so **AniList, MyAnimeList and Simkl
lists are not on TV until `Q12`**; sorting; a saved per-list opener (`LibraryOpener`), so a title always opens its page, or
a search by name when its provider is gone, which is the old default; Random; the long-press metadata popup. The old
filter re-ranks by similarity, and this one narrows by containing the text. `DEC-13`: this screen takes `:core:theme`, so
it has no user-selected primary colour or Material You.

**Strings.** No new key, so no `R` field. Coverage of the keys reused, directories out of 62 (the rest fall back to
English): `title_search`, `type_watching`, `type_completed`, `type_plan_to_watch` 61; `type_on_hold`, `type_dropped` 60;
`filter_bookmarks` 59; `sort_clear` 58; `library` 49; `error` 45; `empty_library_logged_in_message`,
`empty_library_no_accounts_message` 42; `plugin_load_fail` 41; `favorites_list_name` 39; `no_data` 61; and the retry
label's `search_state_retry` 1, which is English only and already was.

**Measured, each a wrong turn first.** Focus passed in the module and failed in the real app, repeatedly, for four
causes, which is the case for the `:app` test:

- Adding `focusRequester` to only the card being returned to rebuilt its focus node when that condition went false, which
  dropped the focus it had just taken. The first version of the module test read focus the moment it arrived and missed
  this. It now reads it after the return point is cleared. Every card now has the same modifier chain.
- On `Television_720p` the fragment's view composed and took focus about 650 ms before `onResume`, and by `onResume` focus
  was on the rail (logged with a global focus listener). So focus now waits for `LibraryAction.Reappeared`, which the
  fragment sends from `onResume`. Retrying inside the first frames, tried first, did not help.
- RIGHT from the rail searches from just before the stop that left, and on this screen that landed on the field from a tab
  and on a tab from a card. `focusRestorer` and `focusGroup` did not change that, and why the search misses was not
  established. `LibraryFocusMemory` sends focus on to the stop that left, whichever stop the search lands on.
- Moving between two stops reports "no focus" for a moment, in the same frame, and acting on that sent focus back to the
  stop it had just left. The memory now reads it through state, which the moment does not reach.

**Tested:** `LibraryScreenTvDeviceTest`, 9 on `Television_720p` with a fake store and the real ViewModel: content, real
counts, card size, opening a title, an empty page with a reachable action, an empty library, failure and retry, a long
title ellipsized, a walk over every tab and 16 cards with no dead ends or traps and no keyboard, focus back on a third-row
card, and the filter. `LibraryTvNavigationTest`, 1 in the real app beside the Compose rail: the rail opens
`LibraryTvFragment`, a bookmark under the real keys is on it, a remote opens `ResultFragmentTv` through a stand-in provider
that answers nothing, Back restores focus, and LEFT then RIGHT returns to the same card. JVM: `AndroidLibraryStoreTest` 13,
`LibraryViewModelTest` and `LibraryShelfTest` (added to).

**Screenshots (`INF-11`).** Four cases in `LibraryScreenTvDeviceTest`, beside the design system's rail, with one-colour
posters: `library-grid-focus-card` and `library-grid-focus-tab` (5a), `library-empty-focus-action` (5c) and
`library-filter-focus-field`. Recorded once on `Television_720p`, each image looked at before it was committed.

**Not verified:** a title's loaded page, because the stand-in provider answers nothing, so the page opened showed its
failure; the search-by-name path when a provider is gone, which ran in earlier runs of this test and no longer does; real
poster loading; favourites through the real keys (mapping is JVM-tested only); a language long enough to make
the tab row scroll; a long type word on a badge, which clips at the card edge; a focused card removed by the refresh on
return, where focus falls to the window's choice.

**`TvRailNavigationTest`, changed because this PR changes what it measured.** `KMP-33` wrote two cases against the old
page. `noDeadEndsOnLibrary` expected focus to be lost at the pager's right edge, and the new page has no pager, so it now
walks a seeded title and expects no loss. `theXmlRailLosesFocusFromTheLibrarysPagerToo` measures the old page, so it moved
to the emulator layout, which still opens it. Its finding stands: that loss belongs to the old page.

**Found, reported and not changed:**

- `LibraryViewModel.showTab` after a failed read shows `Empty`: `BUG-33`.
- LEFT from the library's filter field does not reach the rail: `BUG-34`, which search's field has too.
- A stop reached by the empty page's action is not remembered, because `StateView` takes no modifier for its button. RIGHT
  from the rail then returns to the last stop that was: a tab.
- `LibraryPoster` repeats `PosterArtwork` from `SearchTvFragment`, 15 lines. Sharing them means editing search's fragment.

---

## KMP-35 — `ConnectivityFlow`: the offline state every screen can observe (queue `Q2`) · **measured 2026-11-04**

**Status:** in PR, nothing calls it yet. `Q14`, `Q18` and `Q24` are its first callers.

`:core:connectivity` is new and first-party: never merged into `classes.jar`. `:core:common` is frozen, and `:core:http`
would hand every observer Ktor.

| | |
|---|---|
| `Connectivity` | `ONLINE`, `OFFLINE`, `UNKNOWN`. A screen draws offline for `OFFLINE` only |
| `ConnectivityFlow` | `val status: StateFlow<Connectivity>`. The host makes one and hands it down |
| `UnknownConnectivityFlow` | Always `UNKNOWN`: the desktop and iOS answer, in place of a guessed `ONLINE` |
| `AndroidConnectivityFlow(context, scope)` | One `ConnectivityManager` callback, shared, registered while collected and for 5 s after |

**Online means the default network has `NET_CAPABILITY_INTERNET`**, the test `AppContextUtils.isNetworkAvailable` already
uses. It is deliberately not `VALIDATED`: a blocked check URL, a captive portal or a LAN serving the user's own media
would read as offline while working. The one-shot is untouched.

### The emulator's network is Ethernet, and that decided the test

Measured on `Television_720p` (Android 16, `user` build), each from inside the test as the shell user:

| Tried | Result |
|---|---|
| `svc wifi disable`, `svc data disable`, `cmd connectivity airplane-mode enable` | `activeNetwork` stays `100`, for 20 s after each |
| `ip link set eth0 down`, `ifconfig eth0 down` | `activeNetwork` stays `100` |
| `EthernetManager.setEthernetEnabled` by reflection | `NoSuchMethodException`: hidden from a test |
| **`service call ethernet 13 i32 0`** | `dumpsys ethernet` says `disabled`, `activeNetwork` is `null`. `i32 1` brings it back as network `101` |

**13 is this image's transaction number, found by trying 1 to 13 in order, and it can change with the image.** So the test
confirms every toggle through `dumpsys ethernet` and fails by saying so. No AVD was changed: `device-tests.sh` boots it
`-read-only`.

### What was measured

- `:core:connectivity:connectedAndroidDeviceTest`: 2 tests, 0 failures. The real network goes `[ONLINE, OFFLINE, ONLINE]`,
  each checked against `activeNetwork` read directly (`100 -> null -> 101`).
- Registration, by counting this uid's live requests in `dumpsys connectivity`: making the flow 0, one collector 1, two
  collectors 1, none after the timeout 0. **Count only above `mNetworkRequestInfoLogs`:** below it is a history, where a
  release adds a line too. Counting the whole dump gave 2, 4, 4, which looks like a leak and is not.
- `:core:connectivity:jvmTest`: 8 tests, 0 failures, on the reducer's rules, including a handover not flickering offline.
- `app/api/classes-jar.api` unchanged by the `:app` edge, and no `core/connectivity` entry in `classes.jar`.

### Not verified

The API 23 path, `ActiveNetworkCallback`, has never run: only `Television_720p` is available, on Android 16. The Wi-Fi and
cellular paths have not run either, because this AVD's default network is Ethernet. No phone has run any of it.


## KMP-36 — Download facts: rate, ETA, queue position, state and a typed reason (queue `Q13`) · **measured 2026-11-04**

**Status:** in pr #148 · **The three findings below:** fixed by `BUG-35`, on the owner's decision. This section records what the older code did, and nothing in `KMP-36` itself changed what a download does.

`DownloadFacts` (`:app`, `cloudstream4/download/`) holds one `DownloadProgress` per episode id. Downloads screens observe
`DownloadFacts.progress`, which adds the queue; their Android host will be in `:app`, so everything is `internal`.
`DownloadProgress` (`:media:model`) already had rate, ETA and state, and gains `bytesWritten`, `queue` and `failure`.
`:media:model` has no ABI baseline and is not merged into `classes.jar`.

- **Queue position** counts running and waiting together, running first, so "queue 2 of 5" on 6b and "Queue (0/3)" on 6e
  come from one number.
- **Reasons do not survive process death.** They are in memory; `DownloadStatus` has nowhere to keep them and must not change.
- **Not implemented, because it is new behaviour:** the pre-flight free-space check 6e's banner implies. The shortfall is
  known only once a write has failed, from `StatFs`, and is `null` for a document-tree folder.

### The sidecar disagrees with the older record on purpose

`COMPLETED` means every declared byte was written. Everything the older code files as a success on a truncated file stays
`FAILED` here, with its reason. The downloader only gained lines that report; `failedStatus()`, the retries and every
swallowed exception are unchanged.

### What the older code does, measured on `Television_720p`

`DownloadFactsDeviceTest`, through `EpisodeDownloadInstance`, a real MediaStore file and a fake server on loopback. `BUG-35` rewrote
those tests to the new behaviour.

| Fault | Written of total | Older record | Fact |
|---|---|---|---|
| Lost past 60 MiB, range server | 62,914,560 of 104,857,600 | `isCompleted`, `IsFailed` announced, no queue key, status gone, so the button computes paused | `FAILED`, `NetworkLost` |
| Same fault, direct `downloadThing` | 62,914,560, and on another run 48,889,856 | `PARTIAL_SUCCESS`, and on that run `DOWNLOAD_FAILED` | |
| Lost past 15 MiB of 40 | 3,399,680 on the run behind this table; 4,440,064 and 5,234,635 on earlier ones | `isFailed`, status `IsFailed` | `FAILED`, `NetworkLost` |
| Lost in the last range | 44,040,192 of 47,185,920 | `isCompleted`, **`IsDone` announced** | `FAILED`, `NetworkLost` |
| Lost in a single-range file | 12,582,912 of 15,728,640 | `isCompleted`, **`IsDone` announced** | `FAILED`, `NetworkLost` |
| Disk full, 30 MiB free, 200 MiB file | 31,449,035; 31,440,896 on an earlier run | **Never ends.** Status `IsDownloading` after 45 s with no progress. Stop ends it in 55 ms | `DOWNLOADING`, `StorageFull(178266165)` |

**1. Past 50 MB, "recorded as complete" is narrower than the audit says.** No record claims the file is finished: the button
computes paused, and the popup still offers Resume below 98% (`PieFetchButton`). What is lost is the failure: `isCompleted`
removes the queue key, so nothing retries it and it is not resumed on the next launch. A manual resume finished the same
file, byte for byte, once the server was healthy.

**The 50 MB line is also a race.** A range in flight is abandoned when another fails, so the same fault at the same offset
wrote 60 MiB on one run and 46.6 MiB on another, and landed on opposite sides of it.
Three device runs, a server dropping past 60 MiB each time. A direct `downloadThing`, one attempt: 62,914,560, 48,889,856 and
62,914,560, so 2 past the line and 1 under it. Through the instance, which retries once: 62,914,560 all three times, all filed
as completed. Three runs is not a rate; what it shows is that the outcome depends on timing. The test accepts either side and
pins what each side does.

**2. A lost connection in an open-ended range is worse, and at any size.** `resolveSafe` returns true when `end == null`
however `resolve` ended, and `resolve` swallows read errors. So the downloader announces `IsDone` on a truncated file. That
range is the last one of every multi-range file, and the whole of any file under 20 MiB or of unknown length.

**3. A full disk hangs.** The write error is swallowed by `resolve`, the range retries past the gap, later data waits in
`pendingData`, and at 50 MB ahead `isTooFarAhead` keeps every job waiting. It holds its download slot until the user stops
it, and no storage-full state can ever fire from the older record.

**Recommended to the owner, and approved as `BUG-35`:** one behaviour PR. (1) `DOWNLOAD_PARTIAL_SUCCESS` becomes a failure that keeps the
file, so it gets a Retry. (2) `resolveSafe` returns false when the open-ended range ended by an error. (3) A failed write
fails the transfer instead of retrying. (2) and (3) are bug fixes with no good reason to keep today's behaviour. (1) changes
what users see on files that were silently partial, so it is the one that is a real choice. Each makes its pin red, to be
rewritten in that PR.

### Published API

Additions only: 73 lines in `app/api/classes-jar.api`, none removed or changed. `internal` does not keep a class out of this
dump, so `DownloadFacts` and its `Flow.map` helper classes appear, as do the mangled `noteFailure$app`, `getStoragePath$app`,
`setStoragePath$app` and the two failure properties on `LazyStreamDownloadData`. No published constructor changed.

### Not verified

- HLS. `downloadHLS` got the same reports and has no device test; the fake server serves one file.
- A document-tree download folder, and an unknown-length file.
- Queue positions with the real service running. `merge` is tested on the JVM; the device test reads `DownloadFacts.progress`
  with an empty queue only.
- What the notification shows for a truncated file.

---

## KMP-38 — The TV detail page on Android (queue `Q20`, boards 7a, 7b, 7a-ii) · **measured 2026-11-04**, id lift **2026-11-05**

**Status:** `#153` merged; the icon-only row follow-up is in pr `#170` · **Owner's rulings it follows:** `DEC-17` (hollow neutral failure, panel commit rule, the
program's merge rule). Android TV only. Phone and `EMULATOR` unchanged. The old `ResultFragmentTv` stays as
the rollback for one release.

`DetailScreenTv` existed as a partial 7a that nothing called. It now draws 7a, 7b and 7a-ii and is the live TV
result page, over `:app`'s real provider loading.

### The repoint, and the audit's one wrong constraint

The audit said `navigation_results_tv` was "the one node that can be repointed in XML". **It cannot.**
`getResultsId` branches on `TV or EMULATOR` (`utils/AppContextUtils.kt:703-709`), so an XML repoint moves the
EMULATOR layout too. `Globals.layoutId` holds one value (`ui/settings/Globals.kt:24-32`), so `isLayout(TV)`
excludes `EMULATOR` and the runtime `setClassName` form that `KMP-12` used is the only mechanism that moves TV
alone. `openDetailOnTvScreen()` is called from one line in `MainActivity` at `:1688`; deleting that line is the
rollback. The audit's bullet is struck through and corrected. **Q21 and Q22 inherit this.**

### Two of the audit's facts were wrong in our favour

- **A season integer and a per-season count do exist.** `ResultData` lacks them, which is what the audit
  measured, but `EpisodeResponse.seasonNames` carries `SeasonData(season, name, displaySeason)`
  (`MainAPI.kt:2227-2237`) and `Episode.season` is an `Int?` (`:2558`). `TitleDetail.seasons` is built from the
  mapped episodes, using the provider's **integer** and never its free-text season name, which would put
  untranslated prose in a fixed-width button.
- **`ResultViewModel2` is not reusable headlessly, and is not needed.** Its output is `LiveData`, its `load`
  takes an `Activity`, and 24 of `ResultData`'s 29 fields are pre-rendered `UiText`. `APIRepository.load(url)`
  is headless, in `:core:data`, returns `Resource<LoadResponse>` and never throws
  (`ui/APIRepository.kt:95-131`). `AndroidDetailSource` maps `LoadResponse` straight to `TitleDetail` and never
  goes through `toResultData` — which is also what fixes the cast: `toResultData` decides image-vs-initials for
  the whole rail from the **first** actor alone (`ResultViewModel2.kt:277,:339`), so one missing headshot at the
  top turned 38 names into a single "Cast: a, b, c" line. The mapping decides per person.

### Resume reads through the seam; the id arithmetic was never replicated

`ResultViewModel2` derives each `ResultEpisode.id` arithmetically and differently per response type (anime:
`mainId + episode + idIndex * 1_000_000 + season * 10_000`, `:2226-2229`). Replicating it would write positions
under keys no other screen reads, which is worse than writing none, so it was not copied — and when the owner
ruled, it was **lifted** rather than copied: see the id lift below. What resume itself reads is unchanged:
`LoadResponse.getId()`
(`:410-414`) for the parent, `DataStoreHelper.getLastWatched(parentId)` (`:592-598`) which hands back the stored
`episodeId`, then `getViewPos(episodeId)` (`:745-748`). A movie's stored id **is** the title's own, confirmed at
`:2333-2353` (`id = (mainId) // HAS SAME ID`). Raw position and duration, not `fixVisual()`, because
`ResultViewModel2.resume()` reads raw too (`:2461-2474`); `DetailResume` applies the zero guard that
`ResultEpisode.getWatchProgress()` is missing.

### The `setLastWatched` regression, and the lift that closed it · **measured 2026-11-05**

**What was wrong.** `setViewPosAndResume` writes `setViewPos(id, …)` unconditionally but writes `setLastWatched`
only when the meta is a `ResultEpisode` or an `ExtractorUri` (`DataStoreHelper.kt:693-742`). This host first
played through `DetailPlaybackGenerator : NoVideoGenerator(id)`, whose `videos` list is empty, so
`PlayerGeneratorViewModel` had no meta to pass (`:396`):

| | position saved | `setLastWatched` |
|---|---|---|
| Movie | yes, under `getId()` | **no** |
| The episode that was already last-watched | yes, under its stored id | **no** |
| Any other episode | **no** (`id` is null) | **no** |

For a series — most of the content — watching an episode added nothing to Home's Continue Watching and never
advanced which episode the viewer was up to. Two fixes were put to the owner; **the owner chose the first**:
lift the per-response-type id derivation out of `ResultViewModel2` into one shared function that both pages
call, with no change to how it works.

**The lift.** `episodeSlots(mainId)` in `app/.../ui/result/EpisodeSlots.kt` is now the single source of truth
for a `LoadResponse`'s playable parts: the walk order, the `episode ?: index + 1` fallback, the de-duplication,
and all three id formulas.

| Type | Id |
|---|---|
| `AnimeLoadResponse` | `mainId + episode + dub.id * 1_000_000 + season * 10_000` |
| `TvSeriesLoadResponse` | `mainId + season * 100_000 + episode + 1` |
| `MovieLoadResponse`, `LiveStreamLoadResponse`, `TorrentLoadResponse` | `mainId` |

`postEpisodes` consumes it; nothing is copied. Three things that look like detail and are not, because the id is
built from them: a series is walked **sorted** and anime is not, so the walk order is part of the formula; a
repeated id **drops** the second part rather than renumbering it; and anime shares one seen-set across its dub
lists, which is what the `1_000_000` stride keeps apart. Each of `postEpisodes`' per-type field expressions is
kept verbatim — including `if (seasonData != null) seasonData.displaySeason else season`, which is **not**
`seasonData?.displaySeason ?: season` when `displaySeason` is null.

**What the fix restored beyond the ask.** Playback now goes through `RepoLinkGenerator` over the real parts,
which is the generator the old page builds (`ResultViewModel2.kt:2116-2125`). That brings back the two things
that hung off the id and were listed as losses: the player's **next and previous episode**
(`VideoGenerator.hasNext` is `videoIndex < videos.lastIndex`, and the empty list made both always false) and the
**twenty-minute link cache**.

**One fault found on the way, in this PR's own code.** `playData()` had `magnet ?: torrent` where `postEpisodes`
has `torrent ?: magnet`. Harmless while nothing was stored; once the part is looked up by its `data` it would
have played the magnet and found no slot for it, so a torrent would have stored nothing. Fixed to match
`postEpisodes`.

**Still not reproduced:** `isFiller` on the player's episode meta, which comes from the filler-list request
`updateFillers` makes and this page does not.

### Reduced because no string exists

No key was added: a new one costs 62 translated directories, and `KMP-33` and `KMP-34` added none either.
"Full synopsis" → `synopsis`. The "Cast" rail header → **no header**, as the old TV page also has none.
"More like this" → `recommended`. "Start over" → `play_movie_button` / `play_episode`, which beside "Resume"
reads correctly as resume-versus-from-the-start. "Resume from 1:24:07" → `resume`, since no h:mm:ss formatter
exists in the tree. "2h 16m" → `duration_format` ("136 min"). "Full cast" and "See all" → **nothing drawn**.
The synopsis panel's dismiss row uses `go_back`, which is what it does.

### Reduced because no data exists

The `· TMDB` provenance leg on both count lines: nothing records which tracker supplied the cast
(`applyMeta` fills from whichever answered, `:1722`). A cast item opens nothing — `Actor` is `(name, image)`
with no id (`MainAPI.kt:1534-1537`) — so it stays a focus stop whose only press swaps the role line to the
voice actor, and does nothing for a member without one. 7b's title-level Download: download wiring is
per-episode-row only (`ResultFragmentTv.kt:437-439`). "No playable source" for a movie:
`noEpisodesFoundText` is series/anime-only (`:396-399`). The OFFLINE and PARTIAL banners every board owes:
`isNetworkError` is read in exactly one place in the app and never on the result page, so without a
connectivity source they would degrade to ERROR — that seam is `Q2`. **It landed while this branch was in
flight:** `:core:connectivity` and `ConnectivityFlow` are on master as `KMP-35`, which this branch is now
rebased onto. Nothing here consumes it yet — drawing the two banners is a change to this page's state and
copy, so it is a follow-up and not a quiet addition to this PR — but the reason given above for reducing
them has expired, and the page is the first screen that should pick it up.

### Divergences on purpose

- **The nav rail shows**, as the boards draw it. *This reverses the reduction this PR shipped first*: the page
  had no rail because `updateNavBar` leaves `navigation_results_tv` out of `isNavVisible`, and the owner ruled
  on 2026-11-05 that the detail page shows it. The change is one call site, **not** an entry in `isNavVisible`:
  that list drives the XML rail too, which the old result page and the emulator layout still use, and
  `TvRailSwitch.useComposeRail = false` has to keep this page rail-less exactly as it was. `TvRail` already
  hides the XML rail, shows the Compose one and sets the nav-host margin. The page's own inset becomes
  `ContentOriginX − NavRailWidth` (24 dp), which is `HomeScreenTvDefaults.ContentInsetStart` and
  `SearchScreenTvDefaults.ContentInsetStart` verbatim, and the failure state centres on the content band so it
  lands on the board's x516 rather than the x518 a page-area centre would give. LEFT into the rail and RIGHT
  back to the stop that yielded focus are `TvRail`'s own.
- **7a and 7a-ii are one scrolling surface at two fixed stops.** *This corrects what this PR first claimed.*
  It said the two boards are "one page at one offset" and that "no 292 dp constant is needed"; the boards put
  the cast avatars at 364 dp on 7a and 72 dp on 7a-ii, which is a fixed 292 dp jump exactly. Arriving at an
  offset no board draws was `BUG-38`, and the cause was that the page had no `BringIntoViewSpec` at all, so
  entry focus got Compose's default TV pivot — 0.3 of the viewport, y216. `DetailNoScrollSpec` plus
  `DetailFocusScroll.stopFor` is `HomeNoScrollSpec`'s pattern, and the stops are unit-tested. The episode rows
  are items of that column, not a nested one, and take the only stop derivable rather than drawn: their own.
- **The episode list stays on the page**, below both rails. Board 6a's panel is `Q21`; removing the list first
  would leave no way to pick an episode. `Season N` and `Episodes · N` scroll to it.
- **`Season N` / `Episodes · N` only for a single-season title**, counted from that season's `episodeCount`
  (`TitleDetail.namedSeason() = seasons.singleOrNull()`). Beyond one season both are dropped: the board's
  Season control opens the unbuilt panel, so naming a season would claim a selector that does not exist, and
  each episode row already carries its `S1:E4`.
- **Five controls is the action row's ceiling**, not six. `ResumeWidth` 220 + five ghosts at the 120 dp
  `TextButtonMinWidth` floor + five 12 dp gaps is 880 dp against 833 available on `Television_720p`
  (961.5 − 100 − 28), and a `Row` sheds overflow from its last children. Neither board draws six: 7a has no
  Start over, 7b has no Season or Episodes. Start over is therefore drawn only for a playable title, which is
  also the honest reading — a series' "start over" would be its *first* episode, a different action from
  resuming S3:E4. The board spec's §5 did not carry this ceiling; it does now.
- **The chrome glyphs show a selected state** (`ink` when set). The boards draw all three cells as
  undifferentiated `muted` squares and say nothing about it; flagged in the KDoc for the design.

### Found and not changed — shared components, each would move search, library and home

`GhostTextButton` strokes a 1 dp `Hairline` where the board draws a 2 dp `line` ring. `RailHeader` centres its
count where the board bottom-aligns it, which puts the 16 dp count at +6 dp, off the step — a real defect, and
every rail header in the app. `IconCell` has no `--raised` plate behind its glyph. `PrimaryTextButton`'s KDoc
says `FocusRingBleed` is 8 dp; the token is 4 dp. `ProgressRule` is 2 dp against
`MediaCardDefaults.ProgressRuleHeight`'s 4 dp (H §8.4, still open) — the resume bar uses 4 dp per the board.
The generic loading skeleton carries no `contentDescription`, so no test can positively assert a loading state.
`StateView` calls `actionFocus.requestFocus()` unguarded where this screen uses `requestFocusIfAttached()`.
`toPageUiState` builds `UiState.Error` with no `detail`, so `DetailLoad.Failed.detail` never reaches the screen
— deliberate per `BUG-21`, but it means no test can assert *why* a load failed.

**Security, reported not fixed:** the old result page passes provider plots and episode descriptions straight
into `HtmlCompat.fromHtml` with no allowlist (`ResultFragmentTv.kt:794,:883,:894` →
`AppContextUtils.kt:133-147`), so a provider controls `<a href>` targets. The same route is taken by
`ResultFragmentPhone.kt:980` and `MainActivity.kt:1534`. The new screen strips markup and renders Compose text,
so it is not affected. Separately, `StreamLink`'s old claim that "the host has already refused any URL it will
not play" is true on desktop, which has `SandboxPolicy.isPublicHttpUrl`, and **false on Android**, which has no
equivalent — `APIRepository.isInvalidData` rejects only `""`, `"[]"` and `"about:blank"`.

### What the old TV result page had that this does not

The duplicate-bookmark dialog, and with it the de-duplication it performs on confirm — `checkAndWarnDuplicates`
is `@MainThread` and shows an `AlertDialog` (`ResultViewModel2.kt:1039-1046`), so bookmarking one title from two
providers now silently makes two library rows. Autostart: `START_ACTION_RESUME_LATEST` and `START_ACTION_LOAD_EP`
no longer open straight into the player, so Home's continue-watching card lands on the page. The dub/sub
selector, the episode range selector (`EPISODE_RANGE_SIZE = 50`), the six `EpisodeSortType`s, and the episode
action menu (reload, mark watched, cast, batch download) — none drawn on any board. Chromecast from the detail
page. Subscribe, which is emulator-only today (`:633`) and deliberately undrawn; the chrome row has one free
96×96 slot at x764 dp if it is ever meant to ship. Episode descriptions, which `DetailEpisode` does not carry.
The four side effects of `ResultViewModel2.load`: `SyncRedirector.redirect` (`:2684`), `applyMeta` (`:2710`), the
download-header cache write (`:2720-2732`) and eager `loadTrailers` (`:2733`) — so a MAL or AniList URL is not
redirected to a provider and no tracker backfill happens. A trailer whose
extractor fails now shows a button that does nothing, where the old page hid it until mirrors resolved.
Cast and recommendation artwork gets the **title's** poster headers, because `TitleDetail` carries none of its
own, so a recommendation from another provider gets only the app user agent.

### Start over writes storage

`GeneratorPlayer.newInstance` has no start-position parameter (`:151-164`) and `getPos()` reads `getViewPos`
itself (`:247-254`), so "from the beginning" is `setViewPos(episodeId, 0, storedDuration)` before navigating.
Three consequences, all intended and none hidden: pressing it and backing out without watching **loses the
stored position**; it is a no-op when nothing is stored or the stored duration is under 30 s, because
`setViewPos` early-returns there (`DataStoreHelper.kt:683-687`). It now reaches every part, since the id lift
gave this host all of them.
The alternative — an explicit start position on `GeneratorPlayer.newInstance` / `VideoGenerator`, both in
`classes-jar.api` — is a published-API change and **waits for the owner**.

### `classes-jar.api`

**Two measurements, because the id lift came after the first.**

**The page alone, at `4e2fb45c5`: +157 lines, 0 removed, 0 changed** (37219 → 37376), regenerated with
`:app:dumpExtensionApi` against `origin/master` at `8f35d725f`, never hand-edited. 141 signature lines and 16
blank separators. An earlier read of this said +152, measured before `wirePanelBack` existed; its
`OnBackPressedCallback` and state collector are two more synthetic classes, and the five extra lines are theirs.
`AndroidDetailSource`, `AndroidDetailSourceKt` and its three synthetic lambda classes,
`DataStoreDetailRecords`, `DetailHostFacts`, `DetailTvFragment` with the five
`$special$$inlined$viewModels$default$N` classes `by viewModels` always emits, `DetailTvFragmentKt`, and
`LocalDetailRecords`. `internal` does not keep a class out of the dump — `extensionApiDumpPackages` is
`listOf("com/lagradost/")` and the dumper reads raw `ACC_PUBLIC`, the same reason
`ApiRepositorySearchSource` is in it. All four new classes carry `$stable`, so the `KMP-09` trap did not bite:
nothing moved between modules and `:app` has the Compose compiler. `DetailPlaybackGenerator` was a private
top-level class and so package-private in the bytecode and absent from the dump; it is deleted now that
`RepoLinkGenerator` does its work.

**The id lift: +55 lines, 5 removed, 0 changed** (37376 → 37426). The 55 are 51 signature lines and 4 blank
separators, all new — `EpisodeSlot`, `EpisodeSlotsKt`, `DetailPlaybackParts`, `chosenDub`, `playbackParts$app`,
and three synthetic accessors.

**The 5 removed lines are a stop condition and went to the owner, not around them.** They are one
compiler-generated class and its blank separator:

```
-public final static class com/lagradost/cloudstream3/ui/result/ResultViewModel2$postEpisodes$$inlined$sortedBy$1 : java/lang/Object, java/util/Comparator {
```

which appears in the additions as `EpisodeSlotsKt$tvSeriesSlots$$inlined$sortedBy$1`. It is the anonymous
`Comparator` Kotlin emits for `episodes.sortedBy { … }`, named after its enclosing function, so moving that call
site renames it. **No surface is lost** and nothing can reference it: the name is unstable and the dumper only
lists it because it reads raw `ACC_PUBLIC`.

It is also unavoidable under the approach the owner chose, and three ways out were tried on paper and rejected:
a named comparator in the new file still removes the old name; `sortedWith(compareBy { … })` likewise, because
the removal is caused by the call site **leaving** `postEpisodes` rather than by what replaces it; and keeping a
`sortedBy` in `postEpisodes` to hold the name means dead code and two sort orders. Declaring the shared function
in `ResultViewModel2.kt` does not help either, since the generated name embeds `postEpisodes`.

**One removal that was avoidable was removed.** Giving `DetailTvFragment.openPlayer` a defaulted `index`
rewrote its `access$openPlayer` accessor — a changed line. It is two overloads instead, and that removal is gone.
The lesson generalises: **a default argument on a private member rewrites its synthetic accessor**, so in this
module an overload is the cheaper shape.

### A contract change broke the desktop consumer, which is why that build is a gate

Adding `OpenPlayer`, `PlayTrailer` and `OpenTitle` to the `DetailEffect` sealed interface made
`Main.kt:462`'s `when` non-exhaustive, and `:desktopApp:compileKotlinJvm` was red until the three branches were
added. Every new `TitleDetail` **field** is defaulted, so the data class was safe; a sealed interface has no
such escape, and a consumer build is the only thing that says so. The three branches are named rather than
swept into an `else`, so a fourth effect still fails the build. Desktop acts on none of them:
`DetailScreenDesktop` plays by picking a stream from the source list, and draws no trailer button and no
recommendations rail, so it never sends the actions that produce them. **Desktop behaviour is unchanged**, and
`PlayRequested` → `Play` is byte-for-byte as it was.

### What the device suite found, and what each cost

- **`SidePanelHost`'s platform back is the host's job, and it is wired here — `DEC-17` needed it.** Its KDoc
  says so outright: *"A host on Android should also wire its platform back (the activity's
  `OnBackPressedDispatcher`) to the same `onDismissRequest`, gated on `expanded`. This module deliberately
  does not."* Nothing had called `SidePanelHost` before this page, so the gap surfaced on first use: BACK
  consumed only as a Compose key event never reached the panel, and
  `theWatchStatusPickerOpensIsTrappedAndCommitsOnARowPress` failed twice with *"No compose hierarchies found"*
  — the activity being finished — including once through the same `SOURCE_HDMI` injection every other key in
  that suite uses. So "Back closes either" simply did not hold, and the picker could be left only by picking
  something. Fixed by `DetailTvFragment.wirePanelBack`: an `OnBackPressedCallback` gated on
  `DetailState.panelOpen`. That is why **both** panels' open-ness is in state now — a back callback needs
  something to gate on — which is also why `LibraryState.picking` is state. Every later panel host
  (`TV Search` 4d, `TV Library` 5d and 5e, the player's 7e) needs the same six lines, which is an argument
  for `:core:designsystem` owning the wiring rather than each host repeating it.
- **A dismissed panel slid out as the *other* panel, and that was ours.** `SidePanelHost` composes `panel`
  *"while open and while sliding"*, and `PanelLayer` stays mounted for the whole 240 ms exit
  (`expanded || travel.value < CLOSED`). `DetailPanelsTv` chose between the two panels by reading
  `state.watchStatusPicking` live — which the dismiss has already cleared — so the picker's subtree was
  disposed one frame into its own exit and a "Synopsis" header with a `go_back` row slid out in its place,
  on every dismiss of the picker. Fixed by latching the kind for the exit (`PanelLatch`); the panel that is
  leaving keeps the identity it had. **Any host with two panels behind one `SidePanelHost` has this bug** —
  4d, 5d/5e and 7e all will — which is a second argument for the design system owning this.
- **Measured: `SidePanelHost` does not return focus to the control that opened the panel here. It returns it
  to the chrome row's first cell.** Both dismiss paths — platform BACK and the row press that commits — land
  on `Go Back`, not on `Set watch status`. Measured on `Television_720p`, named by the assertion:
  `expected:<[Set watch status]> but was:<[Go Back]>`.

  The component's KDoc promises the opener and says *"It needs no wiring from the caller"*, and its own
  jvmTest `closing the panel gives focus back to the control that opened it` passes — for a **flat** opener
  held by a `FocusRequester`, with `expanded` flipped from outside, which is exactly this dismiss path. The
  difference is **depth**: here the opener is an `IconCell` inside a `Row` inside the `LazyColumn`'s keyed
  head item. `focusRestorer()` on the content group restores the child it recorded, and that child enters at
  its own first stop rather than replaying the chain down to the cell.

  Two earlier readings of this were wrong and are retracted. First, *"the dismiss comes from outside, so the
  restorer has no record to replay"* — disproved by the jvmTest above. Second, an intermediate test asserted
  only that the two paths **agree**, and it passed: both were wrong in the same way, so agreement proved
  nothing. Asserting the opener by name is what produced the fact.

  **Not fixed here; filed as `BUG-36`.** It is a shared component with four more hosts queued behind it (4d, 5d/5e, 7e), and
  `.claude/rules/no-unsolicited-changes.md` says to propose a change to working shared code rather than make
  it. The test pins the current behaviour the way `SidePanelMotionTest` pins its own exit-window hole, so
  fixing the component fails this test and the fix gets looked at. The likely fix is a `focusRestorer()` on
  the nested groups, or an opener `FocusRequester` the host restores explicitly — the second contradicts the
  "no wiring from the caller" promise, so it is the component's call, not this page's.

  `DEC-17` is still met: the panel closes, BACK commits nothing, a row press commits, and focus is on the
  page rather than lost. What the viewer loses is their place in the chrome row.
- **The fragment's own back wiring has no automated cover.** The feature device test mirrors it in its host,
  because `createComposeRule` has no fragment, so what runs is the production dismiss path (`PanelDismissed`
  closing either panel) and not `wirePanelBack` itself. The `:app` test cannot reach a panel: with no
  extension installed the page never loads a title. **`TvDetailPlaybackTest` shows how that limit can be
  lifted** — it registers a fake `MainAPI` through the public `APIHolder.addPluginMapping`, which is the same
  call a plugin makes, so a real title loads on the live path with no network. A panel test could do the same.
- **No video is decoded anywhere in this suite.** `TvDetailPlaybackTest` asserts on the meta the real
  `GeneratorPlayer` holds, which is what `setViewPosAndResume` reads, and then calls that function with that
  meta. The step it stands in for is the player calling it off playback progress; every value passed is the
  player's own, but the call is the test's.
- **Six screenshot cases, and looking at them found three things.** `#151` merged while this was in flight, so
  screenshots are device tests with committed PNGs rather than the broken plugin (`INF-11`).
  `DetailScreenTvScreenshotTest` records `detail-show-focus-primary`, `detail-movie-focus-primary`,
  `detail-loading`, `detail-error-focus-retry`, `detail-scrolled-recommendations` and
  `detail-synopsis-panel-open`.

  Two of the three findings were faults in the cases themselves, and neither would have failed anything: the
  hero and the headshots were filled with `colors.surface` and `colors.raised`, both near-black, so they were
  invisible and **the references guarded nothing about the hero, its gradient or the cast row** — they are
  `muted` and `line` now; and `screenshotScrolledToRecommendations` compared a card's focus label to its title
  with `==`, walked past the rail into the episodes and failed on `Chapter 03`, so it matches any of the three
  titles and prints the DOWN trail. **A screenshot case can be green and guard nothing**, which is the argument
  for the rule's "did the diff get looked at" line being about recording too.

  `detail-loading` holds the clock (`autoAdvance = false`, one fixed advance). `Screenshot.kt:48` says the rule
  already cancels an infinite animation while `autoAdvance` is on, so this may be belt and braces; what is
  shown is that the frame reproduces, since the compare matched the recording.

  **The third is in the page, not the test, and it is `BUG-38`.** On the loaded page the chrome row — Back,
  Favourite, Watch status — is above the top edge in all three captures of it. Measured in the screen's own
  host: the back cell is **laid out at -76..-12 px in a 720px root, with 0 px surviving the column's clip**.
  The ladder puts it at 28..76dp (37..101px), so the page arrives about 85dp already scrolled, and the whole
  48dp row is clipped away before the viewer has done anything. `DetailScreenTvDeviceTest` asserts the gaps
  *between* elements and never an absolute top, so **a page shifted up as a whole passes all of it**.

  The measurement is recorded as a defect rather than shipped as a test, because the fix is a scroll or focus
  change to the page and the design review has not ruled; the screen's KDoc says the chrome row scrolling away
  with item 0 **is** the design, so what is wrong is that it has already happened on arrival.

  **It was wrong three times before it was right**, which is the part worth keeping. It matched the side
  panel's "Go Back" *text* rather than the chrome icon's content description; then it read bounds before the
  bring-into-view scroll settled; then it asserted on `boundsInRoot`, which is **clipped**, so a row scrolled
  out from under the clip reports an empty rect at y=0 and satisfies `top >= 0`. Each wrong version passed or
  failed for a reason that had nothing to do with the page, and the third is the one that looks obviously
  right. `positionInRoot` + `size` against the clipped rect is the question that can actually fail.

  Three more for the design review, not changed here: the failure's focused **Try again** draws no focus ring
  where the action row's primary does; the loading skeleton is one rail of boxes in the top quarter with the
  rest of the screen empty; and in that skeleton **the back cell is drawn over the first box of the rail**
  rather than above it, which is the same missing top inset seen from the other side.
- **`DpadDriver` has no public BACK**, which is an `INF-10` gap rather than a quirk. `closeKeyboard()` sends
  one but only while the keyboard is up, so `DetailScreenTvDeviceTest` had to reimplement the exact event —
  both phases, `KeyCharacterMap.VIRTUAL_KEYBOARD`, `InputDevice.SOURCE_HDMI`, through
  `uiAutomation.injectInputEvent`. Every TV screen test that dismisses a panel, dialog or destination will
  reinvent it, and the next person to reach for `UiDevice.pressBack()` will lose the same half-hour finding
  out it goes past the panel to the activity. It belongs on the driver beside `center()` and `enterKey()`,
  with `closeKeyboard()` using it.

### One bug worth remembering: a focus lift that eats directional focus

`CastItem` put `tvFocusLift()` **above** its focus target in the modifier chain, and DOWN then never left the
cast rail — every cast item's DOWN went to a same-row sibling, so a remote could not get past the cast row to
the recommendations rail or the episodes. `boundsInRoot()` walks upward from a node's own coordinator, so a
`graphicsLayer` above the focus modifier enters the focus rect and one below it does not; a focused block
therefore reported a rect 4 dp higher than its neighbours, and Compose's two-dimensional search admits a DOWN
candidate when `focusedRect.top < candidate.top`, which every sibling on the row then satisfied. Their 4 dp of
major-axis distance beat the 88 dp to the rail below. `MediaCard` appends the lift *after* the caller's
`clickable` (`MediaCard.kt:68-71`), which is why no `PosterCard` rail ever showed this. The fix is modifier
order and the mechanism is in `CastItem`'s KDoc, because it is invisible on inspection: the chain reads
perfectly well and silently breaks directional focus. **The first diagnosis — that the sections below were
uncomposed `LazyColumn` items — was wrong**, and only the walk's dumped focus graph settled it.

### Suggested follow-up, not done here

`.claude/rules/code-style.md` says to suggest a split rather than make one. It caps by kind, and **four files
here are over their cap** — measured, not estimated:

| File | Lines | Cap | Over by |
|---|---|---|---|
| `AndroidDetailSource.kt` | 724 | 300 (`.kt`) | 424 |
| `DetailScreenTv.kt` | 720 | 250 (composable screen) | 470 |
| `DetailScreenTvDeviceTest.kt` | 815 | 500 (test file) | 315 |
| `DetailTvFragment.kt` | 336 | 300 (`.kt`) | 36 |

Re-measured after the id lift, and it is worth saying which way each moved: `AndroidDetailSource.kt` went
610 → 724, because `DetailPlaybackParts` and `toResultEpisode` landed in it, so **the lift made the worst file
worse**. `DetailTvFragment.kt` went 382 → 336, because `DetailPlaybackGenerator` left it. The four new files
are all inside their caps: `EpisodeSlots.kt` 150/300, `EpisodeSlotsTest.kt` 265/500,
`TvDetailPlaybackTest.kt` 377/500, `TvDetailNavigationTest.kt` 212/500.

The lift also took 60 lines **out** of `ResultViewModel2.kt` (2751 → 2691), which is the direction that file
wants to move in. That is incidental here, not the reason for the change.

`AndroidDetailSource.kt` splits into `DetailRecords.kt` for `LocalDetailRecords`, `DataStoreDetailRecords`,
`bookmarkOf` and `favouriteOf`; `DetailMapping.kt` for the pure `LoadResponse → TitleDetail` functions; and
`DetailPlayback.kt` for `DetailPlaybackParts`, `toResultEpisode` and `resolveTrailers`. `DetailScreenTv.kt` is
the one worth doing first and the easiest: `HeadBlock` and the chrome row are already private composables with
no shared state beyond their parameters, so `DetailHeadTv.kt` takes them and the file drops under 300 on its
own — the four rails already live in their own files for exactly this reason. `DetailTvFragment.kt` is only
over because of the host plumbing that `DetailPlayback.kt` would take anyway. `DetailScreenTvDeviceTest.kt` splits along the suite's own seams — focus-graph walks, panels,
and content states — but its fixtures and `DpadDriver` helpers are shared, so that one wants `INF-10`'s view
on where shared device-test helpers belong before it is cut.

### Follow-up: the action row becomes icon-only (`DEC-17`, boards 7f–7l) · **2026-11-06**

`#153` shipped the row as five text buttons. The owner then asked for 7a's row redrawn with icon-only
secondaries; boards **7f–7l** are in `design/TV Detail Row.dc.html`, and the rule they needed is
`ui-components.md` rule 4's **one ruled exception** (`DEC-17`, `#168`), with the watchlist's glyph pair
settled by `#169`.

**`#170`, in pr:** Start over and Trailer become `IconOnlyButton` cells and the primary gains its `Play`
glyph; the watchlist leaves the chrome row for the action row as `Add` (Plus) off and `Done` (Check) on;
the row's box becomes `IconTargetCell` with the 32dp text buttons centred; `DetailCopy` passes
`CloudStreamIcons.Retry` (board 7l); board 7a-ii's rail header reads `24 · HDREZKA`.

The watchlist is an `IconOnlyButton`, **not** an `IconOnlyToggle`: OK opens the five-value status panel
rather than flipping a flag, and the toggle's checked `raised` fill is what `#169` removed. State is said
once, by shape. Favourite stays in the chrome row — no board moves it, and it is a real toggle.

A new `CloudStreamIcons.Added` on Tabler `Check` **cannot** be added: `Done` is already that glyph and is
live at `SubtitlePicker.kt:207`, so `CloudStreamIconsTest."one glyph per meaning"` fails with
`expected:<[]> but was:<[[Done, Added]]>`. Measured, not argued; `#169` is the answer and the test is
untouched.

The 48dp cell also moved two things the full device suite caught and the screenshot-only pass could
not: `CaptionToActions` is 8dp rather than 16, because the board's 16 is measured to the text buttons
and the cell now supplies 8 of it, and `DetailSkeletonDefaults.ActionCellPadding` adds that 8 back in
the skeleton, which has no cell and whose bar stands for the row's 32dp of ink at the board's y512.

`DetailSkeletonDefaults.ActionBarWidth` is re-derived as
`ResumeWidth + 3 × (MinFocusGap + IconTargetCell) + MinFocusGap + TextButtonMinWidth` = **532dp** — the
**film** row, which is now the shorter of the two, the opposite of the boards, because 7g's extra length
was Download and Mark watched. On the recorded images the bar ends at x739 and a film's row at x741, two
pixels apart; the old 784dp overhung by 46.

### Open after `#170`

- **The cast row is 25.25 dp short of board 7a, and that is a live question.** Measured on the recorded
  references: the avatars' first inked row moves 478 → **489 px**, identical on both the series and film
  pages. The viewport is 540dp tall (`DetailFocusScrollTest.containerSize`), so 720/540 = 1.3333 px/dp and
  cast sits at **366.75 dp** against the board's **392**. The +8.25dp of that move is what the 48dp cell
  adds *below* the buttons once `CaptionToActions` absorbs the 8dp above them, so `#170` narrowed the gap
  from 33.5dp without touching the ladder above the row. It is **not** a
  missing rail header — "Cast 3" draws normally at x33 — and it is **not** `BUG-40`, whose shift is a
  uniform 5dp inside the head block. It wants its own measurement against the board.
- **Icon-only glyphs render `ink` at rest, not `muted`.** That is `DS-17`, app-wide; `#170` does not touch
  `IconOnlyButton`'s tint, and every reference showing an icon-only control at rest changes again when
  DS-17 lands.
- **Full synopsis still trails the row as a text button.** It is on no board here and stays until 7j/7k
  replace it. The row's order is the board's **as a prefix**, so nothing is reordered when it goes.
  Season and Episodes left the row with "7i, the row", below.
- **Queued, in order:** boards 7j and 7k, the collapsed synopsis opening the panel (Synopsis leaves);
  then Download and Mark watched for films, wired to real `DetailAction`s with tests — `DetailAction`
  has neither today, which is why 7g's two cells are not drawn. **More** has no board yet.

### Board 7i is drawn, less its two text buttons (PR 2a `#181`, PR 2b)

PR 2a drew the rows; PR 2b adds the season chips, so 7i's list is complete as far as the contract
reaches (`KMP-63` holds the two readouts it cannot supply).

**What the chips are keyed on, and why it is not `TitleDetail.seasons`.** The groups are derived from
`DetailEpisode.season`, not from the `seasons` list the action row's button reads. `toSeasons()` is
`mapNotNull { it.season }.groupingBy { it }.eachCount()`, so it **drops every null-season episode** —
chips built from it would hide exactly the episodes the Other chip exists to show. The owner's ruling
is that no episode is ever hidden, which rules the `seasons` list out as the source.

**Which chip a page opens on** (#244's review). The chosen chip is a **nullable** index: null until
the viewer picks one. While it is null, the page opens on the group holding the episode the primary
action plays, so Resume at S3E5 opens Season 3 with that row marked; otherwise it opens on the first
group. `shownSeasonIndex` resolves and clamps it on the screen, which keeps the empty-list guard.
- `aResumeInSeasonTwoOpensOnSeasonTwoWithItsRowMarked` holds it on the device. It fails on #244's earlier
  head `9a837e7fe`, which opened on Season 1.
- A series with no resume opens on the group of the episode Play starts. Since `KMP-92` that is S1E1,
  not a special a provider lists first, so the page opens on Season 1. `detail-season-chips` changed in
  2b only by the marked "One One" row.

**7i, the row: Season and Episodes leave the action row** (done 2026-11-09). The row is 7f's as a
prefix: Resume, Start over (a film with a position), Trailer, the watchlist, then Full synopsis until
7j/7k. The chips choose the season, and the episode header's count is the only count on the page.
- **Focus.** Arrival and the return from the player are unchanged. The one-press jump to the list
  (`onScrollToEpisodes`) went with the buttons: D-pad down from the row now reaches the list through
  the rails above it. That is board 7f's choice, which draws no shortcut. `namedSeason()` had no other
  caller and is deleted with its JVM case.
- **Tests.** `seasonOneAndOtherPutOneEpisodeCountOnScreen` is the `[1, Other]` title below, with a
  fixture of its own (`SeasonOneAndOther`). It and the show's row test fail on the old row ("Season 1
  is in the action row"). The button's scroll test is gone; the focus walk is 14 stops, not 16.
- **References.** `detail-show-focus-primary`, `detail-watch-status-cell-focused` and
  `detail-synopsis-panel-open` were recorded again. Every other Detail case passed against its old
  reference first. Each diff is in the row's band only, x550..1013, y329..373 at 1280x720: the two
  buttons gone, Synopsis moved left into Season's place.
- **The loading bar is 472dp** (follow-up, decided 2026-11-09: the placeholder must never overhang).
  `DetailSkeletonDefaults.ActionBarWidth` was the resuming film's 532dp; with a series' row at 472dp it
  overhung every series by 60dp. It is now the shortest row, `ResumeWidth` + 2 × (`MinFocusGap` +
  `IconTargetCell`) + `MinFocusGap` + `TextButtonMinWidth`, and `detail-loading` was recorded again.

What the overlap was, kept for the record. They were **almost** mutually exclusive with the chips, and
the gap was worth knowing before anyone removed them. `namedSeason()` was `seasons.singleOrNull()`, so the two buttons
appeared only when the title has exactly **one** season, and chips appear only at **two or more**
groups. The overlap is a title whose episodes carry one season number *and* some with no season at
all: `toSeasons()` drops the nulls and yields a single season, so both buttons draw, while the groups
are `[1, Other]`, so the chips draw too. On that title the Episodes button counts season 1 while the
header counts the chosen group, so picking Other put two different counts on one screen. First
measured from the two functions only, since `SeasonGroups` sets `seasons = emptyList()`; the
`SeasonOneAndOther` fixture now produces it, and the row test fails on the old row.
- **Board 6a's chip gap is wrong**, not the code's: 6a draws range chips at 12dp where `ChipRow` spaces at
  `ChipRowSpacing` 24dp. Board 7i is drawn at the token. Raised as an owner correction.

---

## KMP-42 — TV search: the source panel, type chips and the D-pad's moves (queue `Q6`) · **measured 2026-11-05**

**Status:** in pr. Boards 4a, 4b, 4c, 4d and 8c on `Television_720p`, API 36. Fixes `BUG-37`, and `BUG-34` for search.

### What it adds

- **The source panel (4d, `DEC-17`).** Multi-select. Done applies, and Back discards. The picks persist the way `:app` already keeps them.
- **`SearchSource` can write.** `choices`, `selectSources`, `searchMore`, `typeFilters`, `selectTypes` and `clearFilters` have interface defaults, so `DesktopSearchSource` is untouched.
- **Type chips (4b, 8c).** A chip narrows the search. Nothing lit means nothing narrows.
- **8c's action.** It sits on the chip row, clears the provider and type filters, then searches.
- **`See all` pages in place** with `SeeAllCard`, labelled `load_more`.
- **4c.** Two columns, a leading disc, a per-row remove as a trailing stop, and a clock glyph (`CloudStreamIcons.History`). `Clear all`'s trash sits in a remove's cell with a remove's tile, so it lines up with the right column's removes.
- **Cards (4a).** The quality badge and then `DUB` or `SUB` sit side by side at the leading corner. The reveal is the title alone.
- **The state actions carry #159's glyphs:** `Retry failed` has `Retry`, `Search everything` has `Search`, and `Browse home` has `Home`.
- **Search's rails use the 12 gap**, and `SkeletonRail` still spaces at `ChipRowSpacing` 24, so a rail moves when it replaces its skeleton.

### Focus choices

| Press | Goes to |
|---|---|
| Down from the field, no chips | The first card of the first row |
| Down from the field, with chips | The first chip, then Down to the first card |
| Right at a rail's end | Stays |
| Left from the field | The rail, with text in the field or without |
| Right from the rail, first time | The field |
| Right from the rail, later | The stop that left |

**Why the page remembers its own last stop.** `focusRestorer` does not see a page's view losing focus to a rail in the host's views, the same as `KMP-33` and `HomeStops`' KDoc. RIGHT then went by geometry and found `Sources` (measured: `expected Browse home, was Sources`). `SearchStops` is Home's pattern, so three small helpers now exist twice. Why geometry picks `Sources` was not measured.

### Each critical test was made to fail

Two control runs on the device, with the fix removed and then restored:

| Fix removed | Failed with |
|---|---|
| Down's target | `withNoChipsDownFromTheField…`: `expected Blade Runner, was Final Cut` |
| Rail's right wall | `rightAtTheEndOfARail…`: `expected Final Cut, was Sources` |
| Left to the host | `leftFromTheField…`, `leftFromAField…`, and on the real path `leftFromTheFieldReachesTheRail` |
| `SearchStops` | `rightFromTheRail…`, and on the real path `rightReturnsToTheExactStopOnSearch` |
| Done's write | `doneApplies…`, at the results changing |
| Dismiss not discarding | `backDiscards…`: `expected [Filmxy, Vidplay], was [Vidplay]` |
| The type write | `eachTypeChip…`, at the write |
| `clearFilters` | `noResultsAction…`: `expected [Filmxy, Vidplay, Zoro], was [Filmxy, Vidplay]` |
| The platform back callback | `sourcesOpens…`: Back left for Home |
| The history list's padding (`BUG-37`) | `search-history-filled`, 26,660 pixels, and the ring's top edge is cut off in the actual image |

**One wall is not shown to matter.** With `Sources`' `right = Cancel` removed, `sourcesOpens…` still passes: nothing lies to its right.

### Reduced from the boards

- `.failpill`'s gap of 12 px is 6 dp, off the step, so it uses 8.
- The board's unnamed button beside the field is not built, because voice search was dropped. It leaves a 72 dp slot, which the board should correct.
- Chips scroll rather than using `ChipRow`, and 10 exist where the board draws 7. Labels are the existing words (`Asian Dramas`, `TV Series`, `Livestreams`).
- A Done with nothing lit falls back to everything eligible.
- Panel rows narrow by the lit types, so a panel's total can exceed its row count.
- A source's health is held in memory only.
- History dates stay lower case, from `DateUtils`, and `today` drops 4c's time (`TODAY 19:04`).
- 4d's leading disc is not drawn. An installed plugin's record (`PluginData`) keeps no icon. `iconUrl` arrives only with a network fetch of the repository's plugin list, and one plugin can register several providers.
- A card no longer shows its type (`Movie`), because 4a's reveal has no meta line.
- The first entry from the rail lands on the field.

### Found, not changed

```
Found: `SkeletonRail` spaces at `ChipRowSpacing` 24, and the rails it stands in for now space at 12
Suggest: move the skeleton, which is shared
Why: a rail jumps when it replaces its skeleton.

Found: `SearchStops` repeats `HomeStops`' three helpers
Suggest: one copy in the design system
Why: not on this task's approved list of shared changes.
```

- **4c's `.rm` draws no tile,** and `IconCell` has one at rest since #156. `Clear all`'s trash copies the tile so the two match.
- **A remove sits outside its focused row's ring,** because `ListRow`'s actions are beside the row. That is a shared `ListRow` question, raised separately.
- **Home's panel rows sit about 3 dp lower** than search's.
- **`action_remove_from_bookmarks`,** the remove's description, is in 59 of 62 locales, so 3 fall back to English.
- **Translations are weakest** in `ti`, `am`, `so`, `mt`, `ckb`, `my`, `or`, `as` and `qt`. The 18 locales with no `setup_done` use a chosen word for Done.

### Not verified

`TalkBack`, a real remote, a real provider on the live path (no extension is installed on the AVD), the first-card move from a scrolled rail, and how desktop looks with the shared `FailureBanner` pill.

### Published API

Additions only, none removed or changed. `selectSearchProviders` keeps its signature, and the chips' version is an overload.

---

## KMP-39 — The highest resolution plays first, and the next best when it fails · **measured 2026-11-05**

**Status:** in pr #155. **Owner's request 2026-11-05:** "random to the highest resolution, if not then next best resolution and
then next", confirmed as: when a film or episode plays, try its highest-resolution link first, then the next best, then the
next. Desktop first, because the owner uses it daily with CineStream's CineTmdb; the ordering is in common code for TV.

### What it does

- **Play plays.** Asking to play (Play, an episode, or Resume from the library) still opens the source list, and after a wait
  plays the best link found so far. A row clicked inside the wait is the choice, and nothing plays over it. Backing out of
  the player lands on the list, which does not play again by itself.
- **The wait:** 8 s from the first playable link, or at once if the search ends sooner or a 2160p link arrives, since nothing
  ranks above it (the Android player also starts at once at its own top, `AUTO_SKIP_PRIORITY`).
- **On failure, the next best, without asking.** A stream fails when its host will not start it, libVLC gives up on it, or it
  ends without a single frame. The player moves to the best link not yet tried, at the same position, and says
  `Source error · <name> · <quality>` above the controls for five seconds while the transport says `Loading…`.
- **No loop.** Each link is tried by itself once. When every link found has failed, the player shows the failure state it
  always had, and the Source selector stays the way out. A link that arrives after that is tried in turn.
- **The player's source list is best first,** the order it tries them in. It still holds its rows while open. Any row,
  failed or not, can be chosen.
- **What carries across a switch:** the position (`ResumeSeek`, as `KMP-29`), the subtitle track (the session is the
  screen's), and now the audio track. **Before this, the audio track was not carried:** a new source opened a new host, and
  nothing selected a track on it, so it played libVLC's default whatever had been chosen. Now a chosen track is matched on
  the new stream by libVLC's whole description, then by its language, and left at the default when neither matches.

### The ranking, from the old app

`PlayerGeneratorViewModel.sortLinks` with `QualityDataHelper.getLinkPriority`: source priority plus quality priority, sorted
high first, stable. With no profile changed every source is 1, so the order is the quality's `defaultPriority` for the
`Qualities` entry nearest the link's number. Measured against the real enum for every number from -10 to 5000
(`SourceSwitcherTest`).

| Quality | 2160 | 1440 | 1080 | 720 | 480 | **Unknown (400)** | 360 | 240 | 144, and 0 (Auto) |
|---|---|---|---|---|---|---|---|---|---|
| Rank | 8 | 7 | 6 | 5 | 4 | **4** | 3 | 2 | 0 |

**Unknown is not last, because the old app does otherwise:** `Qualities.Unknown` is 400 with priority 4, the same as 480p, so
an unknown link ties with 480p (arrival order decides) and beats 360p. A null quality is unknown too (`closestQuality(null)`).
CineStream's odd numbers rank as their nearest: 266 as 240p, 534 as 480p, 800 as 720p.

**Where this differs from Android, on purpose:** Android's next mirror is the next index after the current one in the sorted
list, so a better link that arrived after the start is skipped. Here the next is the best not yet tried.

### The wait, measured

Copied `~/.cloudstream4/extensions/CineStream.cs3`, its `.sha256` and `extensions.decisions` into a fresh home in the
scratchpad; the packaged app with `JAVA_TOOL_OPTIONS=-Duser.home=<that home>`, `--check-extensions <query> --verbose`, which
now prints each new link's quality and, at the end, which the player may open. Numbers only: no URL was logged. Times are
for links the player may open, from the request; CineStream 487, sha256 `30dc6436…`.

| CineTmdb | First playable answer | Best in it | Best overall | Arrived | After the first | Search ended |
|---|---:|---:|---:|---:|---:|---:|
| Inception | 1,149 ms | Unknown | 2160p | 5,642 ms | +4.5 s | 64.5 s |
| The Godfather | 957 ms | Unknown | 2160p | 4,747 ms | +3.8 s | 73.1 s |
| Interstellar | 1,088 ms | 1080p | 2160p | 8,200 ms | +7.1 s | 73.6 s |
| Oppenheimer | 1,276 ms | 872 (as 720p) | 2160p | 7,273 ms | +6.0 s | 68.0 s |
| Breaking Bad, S1E1 | 1,237 ms | 720p | 2160p | 6,102 ms | +4.9 s | 19.0 s |

In none of the five was the first answer's best the title's best. CineStream's other two providers, in the same runs, put
their best 0 to 7.2 s after their first, 15 runs in all, and the first 1080p 0 to 6.4 s after it. One earlier run of
Inception, without the may-open column, had CineTmdb's first link (1080p, not one the player may open) at 187 ms and its
first 2160p at 4,135 ms.

**Chosen: wait, rather than start and upgrade.** Starting at once would show 480p or 720p about 1 s in, then restart the
picture 4 to 7 s later for 2160p: a second host start (2.6 to 3.0 s for a remote switch, `KMP-29`), a rebuffer and a jump, to
arrive at the same picture no sooner than waiting does. 8 s covers every run measured; a 2160p link ends the wait early, which
in these runs was 3.8 to 7.1 s after the first link. A title with no 2160p waits the full 8 s. The constant is
`DesktopDetailViewModels.BEST_LINK_WAIT`.

### Where it lives

`:feature:detail` common code: `bestFirst`, `rankOf`, `TOP_RANK` and `StreamQueue` (`StreamOrder.kt`), and the wait in
`DetailViewModel(playBestAfter)`, null by default so a host that does not ask keeps today's behaviour. `StreamLink` gained
`quality`, the plugin's own number. Desktop: `SourceSwitcher` (what the screen decides, testable with no host), `AudioChoice`,
the notice in `PlayerScreen`, and `--check-subtitle-render … plugin:<provider>:<query> --best-first`, which runs the window's
own path silently.

**Words:** none new. Android's player says `source_error` in a toast and moves on; no string says "trying the next source",
so the notice is `Source error` and the name, and the rest is the transport's `Loading…` and the selector. No baseline move.

### Verified, on the isolated copy

The same fresh home, the packaged app at this branch, `--check-subtitle-render <out> 1280x720 plugin:CineTmdb:<query>
--best-first`: the real `DetailViewModel` with the real 8 s wait, the real screen in an offscreen scene, every media host
started with `silent = true` and no separate audio stream. Each run waits until the clock passes 3 s of playback.

| Query | Played | After asking | Best found by then | Moved on | Played 3 s |
|---|---|---:|---|---:|---|
| inception | 2160p | 5,869 ms | 2160, 1080 ×6, 800 | 0 | yes, real frames (PNG read) |
| oppenheimer | 2160p | 7,767 ms | 2160 ×2, 1080 ×5, 872 | 0 | yes |
| breaking bad (S1E1) | 2160p | 5,748 ms | 2160 ×2, 1080 ×4, 720 ×2 | 0 | yes |

Each start was a 2160p arrival ending the wait, not the 8 s running out. No real link failed, so a second flag forces one:
`--break-first` swaps the chosen link's address for a public one that answers 404.

| Query, `--break-first` | First | Failed | Moved to | Played 3 s |
|---|---|---|---|---|
| inception | the broken 2160p | it, by the real host | the real 2160p, the best not yet tried, through a second silent host | yes |
| breaking bad | the broken 2160p | it | the real 2160p | yes |

**Tests:** `StreamOrderTest` 10, `DetailViewModelTest` 26 (10 new), `SourceSwitcherTest` 10, `AudioChoiceTest` 6, and
`PlayerScreenTest` 60 (2 new, 1 extended). With the sort removed (`bestFirst` returns arrival order), 11 tests fail; with the
timed start, the skip past an unstartable link, the "ended with no frame" rule and the audio match each removed, 11 more
fail, each the one aimed at it. The renders of the notice and of the list in both states were read as PNGs.

### Not verified

- **Nobody listened, or watched.** Every host here was silent; no run played sound and nobody looked at a live window. The
  real-video checks are offscreen.
- **The audio carry-over on a real stream.** `AudioChoice` is tested on the JVM only: no measured title had two audio
  tracks to choose between.
- **A stream that stalls, buffering for ever, is not a failure.** Nothing times it out; the selector is the way out. The
  request named failing to open and stopping with no frames, and a timeout would cut off slow starts that do play.
- **Titles without 2160p,** which wait the whole 8 s; none of the five measured lacked one.
- **Plugins other than CineStream's three providers.**

### Found, not changed

- `DesktopDetailSource` labels a link `${quality}p` for any number above 0, so an unknown link reads `400p` and CineStream's
  odd numbers read `266p`, `534p`, `872p`. The old app writes nothing for 400 (`Qualities.getStringByInt`). Suggest: the same
  here, which is a separate change of what the rows say.
- The detail page's own source list stays in arrival order: it grows at the foot so a row never slides under the pointer
  (`BUG-19`). Only the player's list, which holds its rows while open, is best first.
---

## KMP-37 — The TV home on Android: rails and a trailing card (queue `Q9`) · **measured 2026-11-05**

**Status:** in pr #147. Board `3d` of `design/TV Home.dc.html`, and the source control `3c` draws. The hero and Continue watching
(`3c`, `Q10`) are the next PR: the page is one keyed list, so they go in between the chrome and the rails. Android TV only.
`TvHomeSwitch.useComposeHome = false` puts `HomeFragment` back, kept for one release (`DEC-17`). Phone and the emulator layout
still use `HomeFragment`.

**Parts.** `HomeScreenTv` in `:feature:home` `commonMain`. In `:app`: `HomeTvFragment`, `AndroidHomeSource` over `APIRepository`,
and `DataStoreHomeSourceMemory` over the key the old screen already uses, `DataStoreHelper.currentHomePage`. That key is per
account, and is also what `MainActivity` reads to load one plugin first. `HomeViewModel` gained, all with defaults, so desktop
is unchanged: `SourcesChanged`, `Reappeared`, `Disappeared`, a return point, and "the remembered source wins over a fallback".

### What the brief assumed, measured

- **`setClassName` cannot switch a start destination.** `NavHostFragment` navigates to it during inflation, before
  `MainActivity` has a `navController` to edit. Measured: with search's one line, `HomeFragment` stayed on screen and 9 of 13
  `TvHomeNavigationTest` cases failed. So this is a `FragmentFactory` set before `super.onCreate`, which also covers a process
  restored from saved state, and is one line. Merging with `#140`, its `MainActivity` line does not touch this one, and the
  import lines and the dependency line in `app/build.gradle.kts` sit side by side, to keep both.
- **The old home's view model kept running behind the new screen.** `MainActivity` builds it for the XML rail's footer, and it
  fetches on every plugin stage. Measured with the guard off: `[0/1, 0/1, 1/1, 1/1]`, every section twice. The guard skips it
  while `HomeTvFragment` is the home, so with the XML rail as well (`TvRailSwitch` off) that footer has no account picture.
- **`SidePanelHost`'s `focusRestorer` does not restore on this page.** Closing the panel and RIGHT from the nav rail both landed
  on the first card. `HomeStops` remembers the last stop and sends focus entering the page there.
- **A `BringIntoViewSpec` cannot pin a rail.** It is asked again on every frame, so "pin it" stopped the scroll as soon as the
  rail came inside the band, 269dp short. A rail decides once when it takes focus.
- **Back on API 36.** Back closed the panel and also finished the activity in the module test, with only the panel's key
  handler. `HomeTvFragment` also registers a callback for as long as the panel is open.
- **A crash of mine, caught by the whole suite.** `ExampleInstrumentedTest.layoutTest` opens and closes `MainActivity` at once.
  A view closed that fast never had its scope cancelled, so a 15.5 s timer reached a `viewModels` that throws once detached,
  and the app's crash handler ended the process, in the middle of the next test. It writes only to `files/last_error`, which
  Gradle's uninstall deletes, which is why logcat had no stack. Read by installing the APKs by hand. Why the scope is not
  cancelled is read from `Fragment.performDestroyView`, not measured: it sends `ON_DESTROY` only if the view's lifecycle reached
  `CREATED`. `aPageClosedAsSoonAsItOpensDoesNotCrashWhenItsTimerEnds` pins it.

Focus follows `KMP-34`'s two traps: every card has the same modifier chain, and nothing is taken until `onResume`.

**Plugins load behind a page already drawn.** `HomeSources.stillLoading` keeps it on the skeleton rather than saying nothing is
installed, and a remembered source that is missing is waited for rather than fetching another source's page to throw away.
It gives up after 15 s, because `loadedOnlinePlugins` is never true with auto-update off.

**Sanitized,** because on Android a plugin answers from inside the process with no boundary before it: format and control
characters in all text; only http(s) posters reach Coil, which would otherwise load `file:`, `content:` and
`android.resource:`; header names and values that could split a request; an address too long for the `Bundle` that opens the
title; and row, item and section counts. This keeps a bad answer from breaking the screen. It is no defence against a plugin.

### Every reduction from the boards

| Board | Drawn | Here | Why |
|---|---|---|---|
| 3d | `See all` | `Load more`, 62 of 62 translations | The press pages in place through `RowMoreRequested`. No board draws a destination, so `See all` would be untrue, and that key does not exist |
| 3d | `See all` on every rail | Only on a rail with more to load | On a complete rail it would be a stop that does nothing |
| 3d | `34 TITLES` on the card | None | A provider reports `hasNext`, never a total |
| 3d | Dashed outline on that card | `SeeAllCard`'s raised fill and chevron | The existing component |
| 3d | `ACROSS 12 EXTENSIONS`, `ADDED THIS WEEK` | None | No data. Home shows one source at a time |
| 3d | Badges `NEW`, `SAVED` | None | No data |
| 3d | Badges `4K`, `DUB` | Real: `SearchResponse.quality`, else sub or dub, in the existing words such as `HD` | Not a gap on a rail. Per-card source is, on `3c` |
| 3d | Rails named `Popular movies`, `TV shows` | What the provider calls them, in its language | They are the provider's |
| 3c | Source control | Built: 160dp minimum, `CloudStreamIcons.Disclosure` | The only way to change source. No board draws the panel, so it is `SidePanel`, as desktop has one and as `DEC-17` rules: no buttons, Back closes |
| 3c | Avatar | Built as a generic `Profile` cell, no picture | See below |
| 3c | Clock and two icon cells | None | `Q10` |
| none | Partial failure | Text in the chrome row with a hollow `StatusDot` | `FailureBanner` paints `live`, against `DEC-17` |
| none | Rows a provider marks horizontal | `BackdropCard`, 128x72 | The board draws posters only |

**One deviation from the brief's "copy search":** cards are 12dp apart, not 24. `.row { gap: 24px }` halves to 12, and that is what
fits nine cards where `3d` draws them.

### What the old TV home does that this does not

- **Random.** Not drawn. Also gone: choosing `None` or `Random` as the source. Either stored value reads as no choice.
- **Bookmarks row and its watch-status filter.** Not drawn. They are in the library.
- **Continue watching and the hero:** `Q10`. **Removing from Continue watching** is on no board.
- **The expanded row sheet.** More of a row now arrives in the row.
- **Reload provider.** A failure has Retry, but a page that loaded cannot be reloaded by hand.
- **Search this provider.** The rail's Search searches every selected source.
- **Long press on a card.** No metadata popup or quick actions.
- **Watch progress on cards.** `HomeItem` has no field. Most provider hits have no `id` to look it up by.
- **Account picture in the chrome.** Switching accounts is kept: this and the XML rail's footer were its only ways on a TV, and
  `KMP-33` removed the footer, so dropping it would have left none.
- `DEC-13`: no user-selected primary colour or Material You.

**Strings.** No new key, so no `R` field. Coverage of those reused, out of 62, with the rest falling back to English: `load_more`
62, `title_search` 61, `no_data` 61, `pick_source` 60, `loading` 59, `source_error` 54, `switch_account` 45, `home_source` 43,
`extensions` 42, and `search_state_retry` and `search_state_retry_failed` 1, English only, as they already were.

### Measured

`HomeScreenTvDeviceTest` 25 and `TvHomeNavigationTest` 13 on `Television_720p`. JVM: `HomeViewModelTest` 34 (11 new),
`HomeFocusScrollTest` 5 in `commonTest`, `AndroidHomeSourceTest` 17, `DataStoreHomeSourceMemoryTest` 5. `TvRailNavigationTest`: its cases
about the old home's provider button now switch that screen back on, because they measure it, and `noDeadEndsOnHome` now
walks the new one with no lost focus.

**Each critical test was shown failing.** Fourteen `HomeScreenTvDeviceTest` cases went red with seven mutations together (the wall
at a rail's end, focus after a page, the trailing card, focus on return, the resume gate, the pin, and focus on a state). Nine
`TvHomeNavigationTest` cases with `setClassName`. Three with the old view model back, the fragment's Back callback gone and the
`isAdded` check gone, and that crash stopped the run at 7 of 13, so `HomeStops` was shown failing separately: before it existed,
`leftFromTheFirstCard…` read `A 0` for `B 0`.

### A real plugin, on a throwaway emulator

`TvHomeRealPluginProof`, skipped unless a plugin has been pushed, so it reports as skipped in every normal run. CineStream v487, sha
`30dc6436…`, the reviewed copy, in a `-read-only` AVD that discards everything. It went through `MainActivity`'s real stages with `CineTmdb` remembered:

- `PluginManager` loaded it in 0.3 s. It registered `CineStream` with 22 sections, `CineSimkl` 16, `CineTmdb` 17.
- The page opened on `CineTmdb` with real posters, focus on the first card, `The Mongoose`, 3.1 s after the provider appeared, and the rail closed.
- DOWN reached `Resident Evil`, RIGHT `Primetime`. OK opened its real `ResultFragmentTv` page. Back returned with focus on `Primetime`.

This is the first plugin to have run in these Android tests. **It is not** playback, and it is one launch on the network of the moment.

### Not verified

A real TV remote. A cold start with the plugin already compiled, or on real hardware. Paging, sub and dub badges and the source panel
against a real plugin (the proof pressed none of them). Account switching end to end: the effect is tested, and the dialog and what
follows are not. A process restored from saved state, which the factory is for. A right-to-left layout. The XML rail with the new
home, beyond its existing test.

**Screenshots (`INF-11`).** Six cases in `HomeScreenTvDeviceTest`, 1280x720 on `Television_720p`, with solid posters: rails with a card
focused, the trailing card focused, a partial failure with its retry focused, nothing installed with its action focused, the source panel on
the current source, and loading. Recording them found one defect, now fixed: the failure's headline shared the chrome row's free width with
a spacer and was squeezed to nothing.

### Found, not changed

```
Found: `colors.live` on `FailureBanner`'s dot in core/designsystem/.../state/FailureBanner.kt:102
Suggest: `StatusDot(StatusDotState.Failed)`
Why: `DEC-17` rules a failure a hollow neutral marker. Search and desktop home still show it. Its KDoc still calls it legitimate.

Found: a `Canvas` chevron in core/designsystem/.../card/SeeAllCard.kt, used here for the first time
Suggest: `CloudStreamIcons.Disclosure`
Why: `icons.md`. It predates the pack.

Found: `HomePoster` is the third copy of the same 15 lines, after `SearchTvFragment` and `LibraryTvFragment`
Suggest: one composable in `:app`
Why: sharing means editing both of the others' fragments.
```

- **`focusRestorer` in `SidePanelHost`** may fail the same way on any lazy page. Search's and the library's panels are not built yet.
- **The crash handler writes only to a file.** A crash in `androidTest` leaves `Process crashed` and no stack anywhere.

### Published API

Additions only: 102 lines in `app/api/classes-jar.api`, all under `com/lagradost/cloudstream4/home/`, none removed or changed.
`:feature:home` is not merged into the jar.


## KMP-40 — The TV downloads screen on Android, boards 6c and 6e (queue `Q14`) · **measured 2026-11-05**

**Status:** in pr · Android TV only: `MainActivity` sends `navigation_downloads` to `DownloadsTvFragment` in one line, and deleting that line is the rollback. `DownloadFragment` is
untouched and stays the screen on every other layout. `DEC-17` is the authority to switch.

| | |
|---|---|
| `:feature:downloads` | New. `DownloadsSource` is the seam, `DownloadShelf` turns disk and `DownloadFacts` figures into a row, `DownloadsViewModel` holds the network and what is being chosen, `DownloadsScreenTv` is 6c and 6e |
| `AndroidDownloadsSource` | `:app`. The same `DownloadViewModel` the old screen reads, per-episode figures read beside it with the same two calls, and `DownloadFacts.progress` |
| `DownloadsTvFragment` | `:app`. The glue: effects into navigation, the old screen's delete dialog, Back ends choosing |
| `PlayFromUrlDialog` | `:app`. A copy of `DownloadFragment.showStreamInputDialog`, which is private to the rollback and leaves with it |

**A failed row has a verb the board does not draw.** 6c draws a failed row with no way back in, and the board's own note says a failure has no other re-entry. `Retry` sits beside the row as a
separate stop, as 6d draws its verbs, and asks for what the title has left through the old `DOWNLOAD_ACTION_RESUME_DOWNLOAD`. A short file with no fact, which is also what a failure is after a restart,
reads `Download Paused` with `Resume Download`: facts are in memory, so it makes no claim that nobody can back.

**The two chromes.** 6c has the meter on top and chips in the band. On 6e the band belongs to the storage strip or the banner, so `Play from link` and the queue become buttons on the top row. Offline rides the
top row's middle on both. Choosing what to delete has no board, and takes the band in the same chips.

### What the old screen did that this does not, on a TV

| Lost | Why | Back with |
|---|---|---|
| Pause, on a film's row | 6c's rows have no verbs, and 6d has no film row | undecided |
| Opening a title's result page from its poster | 6c draws no poster | `Q15` for a series; a film has no drawn route |
| Poster and watch progress on a row | Not on 6c | undecided |
| One-row delete from a film's popup | `Select`, then `Delete`, does the same | not needed |

Unchanged routes, no board: **the queue** opens the old XML page, **Play from link** opens the old dialog, which still calls it what it did, a **series** opens `DownloadChildFragment` until `Q15`, and a **film** plays as the old row did, a partial
one included. **Open local file** was already hidden on TV, so nothing is lost. The delete dialog and its wording are the old screen's.

### Where this departs from the boards

- **Words reuse keys:** chip count `1/4`, not `1 of 4`. `Download queue (0/3)`, not `Queue (0/3)`. `5.4 GB / 6.4 GB`, not the board's `of`. Row and delete figures use the board's `·`, through three format keys. `Download Failed`, not `failed`. The legend is sentence case.
- **One word for one action:** 6c says `Play from URL` and 6e says `Play from link`. Both say `Play from link`, as `DS-15`'s table does.
- **`3 QUEUED ITEMS TOTAL 2.4 GB` keeps the count and drops the total:** a waiting download has not read its size. Nothing is invented.
- **Whole sizes come from the platform.** `formatShortFileSize` on API 36 gives `384 GB` and `10 GB` but `1.0 GB` and `2.0 GB`, so a `.0` shows on a whole size under 10 GB. Removing it would mean editing a locale's formatted number, so it stays. Under 10 GB this differs from the board; `.0` above that came only from the test's fake, which is fixed.
- **The shortfall is as of the failed write**, and the banner goes when that download is retried or deleted. `KMP-36`'s pre-flight check still does not exist, so the banner never appears first.
- **Free up space with nothing to delete** opens the system's storage settings. The note on the board only covers the case with titles.
- **The leading glyph** is `Play` for a film and `Disclosure` for a series, which is what OK does. The pack has no film or series glyph.
- **The offline pill** carries a hollow marker, because `StatusPill` requires one. The failed trailing word is `ink2`, as the board's closing note says, and the marker beside it stays `muted`.
- **Scrolling** follows the board, after design review: a row that fits does not move the list, and one that does not moves it by whole rows, so nothing is cut against the chips. `DownloadsRowScroll`. The default TV pivot moved the list for rows that fitted and left half a row showing.

### Measured, each one a wrong turn first

- **`stringResource(res, args)` leaves `%s` and `%d` on screen.** The first recording showed `Needs %s more · %d downloads queued`. `UiString` is the rule, written down in `UiStringFormat`.
- **When a focused stop leaves, the view hands focus to another stop by itself, before any effect runs.** So `last` already names the stop it chose: a row removed under focus landed on `Download queue`, and
  cancelling from its chip landed on the first row. What held focus is therefore read during composition, before the change is applied.
- **`requestFocus()` on a requester with no node returns `false` rather than throwing** on this Compose, so `requestFocusIfAttached`'s answer is what decides the fallback.
- **Right from a row with no verb stays put, so it needs no guard.** I had added one on the argument that it would land on the neighbouring row's verb. Removed alone in a control run, `aRowWithNoVerbDoesNotSendRightToAnotherRowsVerb` still passed with the verb directly below, so the guard went. The test stays as a pin, and has not been shown to fail.
- **Up from a row lands on the nearest chip,** `Select` or `Delete`, not the first. Every chip is along the row.
- **A touch in one test class took away the next class's focus.** The navigation test first tapped the delete dialog's button with `UiObject2.click()`, which puts the display in touch mode, where it stays until a key is pressed. `HomeTvFragment` lands focus before any key is pressed, so `TvHomeNavigationTest` went 9 red of 13 straight after this class and 13 of 13 green alone. The button is now pressed by D-pad, and with that one change the whole `:app` suite is green. A remote cannot put a display in touch mode; a touch or pointer device on a TV might, and that was not tried.

**Strings:** 12 new keys in all 62 directories and 3 formats with no words in the default one, all machine translated and not reviewed by a native speaker; `qt` uses only words from its own file. `ti`, `ckb`, `mt`, `so`, `or`, `my` and `am` are the least sure, `apc` and `ars` are
dialect approximations, and `so`'s word for library and storage is the same. Fourteen locales read the legend as `X / Y used` to keep the arguments in order. Twelfth move of the oracle baseline. Reused keys keep the directories they had.

**Glyphs** follow `DS-16`: `Link` on the top row's Play from link, `Library` on Browse library, `Retry` on both Retries, `Play` on Resume. Queue and Free up space stay text-only, as its table says, and a `Chip` has no glyph, so the band's chips have none.

**Loading** shows six skeleton rows at the row's geometry with an undivided meter. The board has the meter running on its own, but the source answers with both at once, so the storage figures do not arrive first.

**Published API:** `app/api/classes-jar.api` +127 lines, 0 removed: 15 `R$string` fields and the new `cloudstream4.download` classes.

### Each critical test shown to fail

Temporary changes, all reverted. One run on `:feature:downloads`, 13 of 32 red, and one on `:app`, 4 of 5 red.

| Change | What went red |
|---|---|
| A failed row has no verb | `aFailedTransferShowsRetry...`, the list's walk, `whileChoosingNoRowOffersAVerb` |
| The banner's rule is `live` | `aFailureSpendsNoLive`, `screenshotStorageFullOverTitles` |
| `UNKNOWN` reads as offline | `offlineRidesTheTopRow...` |
| A row takes focus before the screen has resumed | `focusReturnsToTheOpenedRow...` |
| The list stops at 20 rows | `fortyRowsAreAllReachable...` |
| `MainActivity`'s one line removed | 4 of 5 in `DownloadsTvNavigationTest`. `thePhoneLayoutKeepsTheOldFragment` stays green, as it should |
| Before the focus fix, not a planted change | `cancellingFromItsChip...`, `aRowThatGoesWhileFocused...`, `theLastRowGoing...`, `aRowDeletedWhileAway...` |

Screenshots are 1280x720, recorded on `Television_720p`. With `Television_720p_2`, the cases the changes did not touch compared 0 pixels different.

**Re-recorded once, after `DS-14` merged.** All 7 went red against the merged design system, by 3,950 to 7,898 pixels, and no other test did. Each new image was looked at, and the diffs looked at for two: the changes are `DS-14` items 4 (`ListRow` subtitle and `StateView` body to `muted`) and 5 (`Chip` to `raised` with a hairline and a `muted` label). Layout, focus rings, `Retry`, the failure marker, the meter and the banner did not move. `git status` showed those 7 PNGs and nothing else.

**Re-recorded a second time, after design review and `DS-15`/`DS-16` merged,** with 3 new cases: loading, the failed read, and a scrolled list. Each of the 10 was looked at against the reference in git before it was kept. The changes: the failed word in `ink2`, the separators, `Play from link`, glyphs on Retry, Play from link and Browse library, `DS-15`'s selection marker, and no scroll on rows that fit. **The scrolled case caught a bug in the first try at whole-row scrolling:** Compose asks the scroll spec again on every step of the animation and takes the answer as what is left, so a distance rounded up to a whole row answered 0 as soon as the row fitted, and the list stopped 20 px short with a row's foot against the chips. The spec now names a fixed line, and a JVM test asks it again part way, as Compose does.

### Not verified

- **A real retry finishing.** `Retry` is pressed against a fake source; through the real app it is shown reachable but not pressed. The old resume path itself is `BUG-35`'s measurement. `getDownloadResumePackage` returning null makes a retry do nothing, as on the old button.
- **A film opening the player**, and `Free up space` reaching the system's storage settings: only the effect is tested.
- **Real rate and progress on the screen.** Figures come from `DownloadShelf`'s tests and a real `IsFailed` fact; no live transfer ran under this screen.
- **Any language but English on a device,** and any native speaker reading the 12 translations.

## KMP-41 — The TV home's hero, Continue watching, and watch progress on its cards (queue `Q10`) · **measured 2026-11-05**

**Status:** in pr #162. Board `3c` of `design/TV Home.dc.html` and its closing note, on the page `KMP-37` built. Watch progress on
Home's cards is the owner's request, which the boards already draw. Android TV only; desktop's home is unchanged (its
`HomeSource` keeps the default, so it has no hero, and nothing reads history there).

**The page now reads, top to bottom:** the chrome, with the hero under it and the hero's art behind both; Continue watching;
the source's rails. The hero is in the chrome's list item because its art runs behind the chrome. Continue watching is this
device's and not the source's, so it stays above a failure, being offline, a page still loading and "nothing installed",
as the owner ruled for `3g` and `3m`.

**Parts.** In `:feature:home`: `HomeSource.featured` (with a default, so no host has to change), `HomeFeatured`,
`HomeWatchHistory` / `HomeHistory` / `HomeResume`, `HomeHero` in the state, `tv/HomeHeroTv.kt`, `tv/HomeContinueTv.kt`, and
progress on `HomeRailTv`'s cards. In `:app`: `AndroidHomeSource.featured`, `LibraryHomeHistory`, and
`AndroidLibraryStore.resuming`.

### One store, read the way the old home reads it

Continue watching reads through `AndroidLibraryStore`, the store the TV library already reads (`KMP-34`), and its records
seam gained two reads with defaults: the resume record ids and the title each one is about. **No second store, and nothing
new is written.** Desktop's `KMP-26` rule (`LibraryShelf`: a position past 30 s and short of 95%) is **not** used, because on
Android the resume record already decides: the player removes it when a title is finished, and at 90% moves it to the next
episode, which has played for 0 s. That rule would hide exactly the "next episode" entries the old home shows. So the list
is the old home's (`HomeViewModel.getResumeWatching`): every record with a known title, newest first, its part's position,
capped at 60 as `LibraryShelf` caps. Resume-only titles do not enter `titles()`, so the library's lists are unchanged.

Card progress comes from the same store: every position it knows, bookmarks and resume records alike, newest per title,
keyed by `encodeId(apiName, url)`, which is also a card's id. Zero is not drawn on a rail card.

### Every reduction from 3c

| Drawn | Here | Why |
|---|---|---|
| Hero carousel, pager `1 / 12` and dots | One title | The first of the source's first three titles whose own page answers, asked one at a time (one request when it answers). A carousel needs a paging control the board does not place on the D-pad |
| Eyebrow `FEATURED · NEW SEASON` | The name of the row the title was found on | Nothing says "featured" or "new season" |
| `2 SEASONS · 24 EP` | None | The old hero does not show them, and no key holds either with its number |
| `8.7 IMDb` | `8.7` | Which site the score is from is not known |
| `Resume S2 E4` | `More info` with `CloudStreamIcons.Info`, which opens the title's page | `ui-components.md` rule 1 makes Play or Resume a title's primary. This button does not play, so `Resume` would be untrue; it is the old TV hero's only button, which also opens the page (confirmed in review). `More info` is in that rule's table with its glyph, and becomes Resume when the hero can resume |
| `Watch trailer` and `Library`, each with its glyph on the board, and the square cell | None | No trailer player is built; a library write is the next PR's (`TV Home Extras`); the cell's meaning is not drawn |
| Art under the nav rail | Art from the page's edge | The rail is the shell's; the page starts beside it |
| `6 ITEMS` on Continue watching | None | `KMP-37` shows no counts on rail headers either |
| Card line `S2 E4 · 28m left · vidplay 1080p` | `S2 E4 · <source>` | Time left has no key without a line break (`resume_time_left`): `I18N-10`, blocked only on that key, since the store has the duration. Quality is not stored |
| `SAVED` badge | None | Bookmark state on Home is the next PR's |
| Art 16:9 | The poster, cropped | A resume record keeps a poster and no backdrop |

| Offline's body under Continue watching | The headline and Retry only | `StateView` is 180dp plus its body; the body is four lines, 260dp in all, and 216.8dp is left at 720p. Without it the state fits and nothing moves. Its first sentence repeats the headline |

**Gap, not drawn:** removing an entry from Continue watching. The old home does it with a long press; no board draws it.

**Kept for the next PR, with room as 3c leaves it:** the bookmarks row, Random, the details panel and reload
(`design/TV Home Extras.dc.html`).

`DEC-17`: `live` is only the progress rule; the one button is `PrimaryTextButton`, white at rest. `DEC-13` as before.

### Focus

- **Landing** waits for the hero to be found or given up (at most 6 s), so focus lands once: on the hero's button, as 3c
  draws it at rest; else the first Continue watching card; else the first card. It does not take focus from a Continue
  watching card, or from the nav rail.
- **Return** works for all three: the hero, a Continue watching card and a rail card. An entry that has gone (finished while
  its page was open) falls back to the landing.
- Every card keeps one modifier chain (`KMP-34`'s trap); nothing is taken before `Reappeared` (the 650 ms trap).
- **Down from a Continue watching card lands on the poster under its middle**, which for the first card is the second poster:
  the card is 192dp wide over 80dp posters. Compose's own search; measured and kept.

### Strings

No new key, so no `R` field. Reused, with their coverage out of 62 (the rest fall back to English): `home_more_info` 60,
`duration_format` 59, `continue_watching` 56, `season_short` 51, `episode_short` 50.

### Sanitized

A title's page is plugin output read inside the process: text loses control and format characters and is capped; the synopsis
is HTML in some plugins, so tags are dropped, five entities decoded, and spaces folded; only http(s) art reaches Coil, a
backdrop that is not falls back to the poster; header names and values are checked; year, minutes and genres are bounded.
Resume records are read the same way: a title with no usable name is dropped, and a poster that is not http(s) is dropped.

### Measured, each a wrong turn first

- **A state under Continue watching had no room for its button.** Recording `home-offline-continue-focus-retry` showed Retry
  squeezed to an empty ring: 216.8dp is left under the rail at 720p, and the offline state needs 260. The first fix grew the
  state and moved the page by the least that showed the button, 44px, which left the chrome 4px from the top edge, inside a TV's
  overscan (design review of #162). Now offline fits without its body and nothing moves; a state that still does not fit, such as
  a failure whose detail wraps, moves the page until the chrome is wholly off the screen. That first stopped 15px short: a list
  scrolls only as far as its content reaches, so a state under Continue watching now has `ChromeRowBottom` of room under it. The
  rail's header then sits 12dp from the top. Without Continue watching nothing changed.
- **Coming back by the nav rail left focus on the source control.** `TvHomeNavigationTest` read `Source · Fake One`: the window
  gives a rebuilt view's focus to its first stop before the rows can take it. A rebuilt view now lands once, as the first open
  does; anything that takes focus first, a return included, is that landing.
- **The hero's page may not be asked for at all.** `APIRepository` keeps a title's page for 10 minutes across the process, so
  the second test to open a source's home asked for nothing. The `:app` cases assert at most one request.
- **Down from a Continue watching card** lands on the poster under its middle (see Focus). Up from a centred state's button
  lands on the card above its middle.
- A Continue card measures 150.99dp tall, not 152, on `Television_720p`: five bands each rounded to a pixel at 1.33125.

### Tested

`HomeScreenTvDeviceTest` 50 on `Television_720p` (25 new): the hero present with its facts at the board's y, a hero with no
facts, no hero, Continue watching with one entry and with twelve, a position of 0 and of half by pixel colour, progress on a rail
card by pixel colour, long titles in the hero and on a card, offline with Continue watching above the state and the chrome where it rests, a state too tall for that moving the chrome wholly
off the screen, the height reserved for Continue watching against the card as it measures, a walk from the hero
through every rail with no dead ends or traps, and focus back on a Continue card, on the hero, and on the hero when the entry has
gone. Four screenshot cases: `home-hero-focus-button`, `home-continue-focus-card`, `home-offline-continue-focus-retry`,
`home-rails-progress-focus-card`.
`TvHomeNavigationTest` 14 (1 new, through real navigation and the player's real keys: a played title is on Continue watching,
opens its real `ResultFragmentTv` page, and Back puts focus back on it); its other cases now land on the hero.
`TvRailNavigationTest` and `LibraryTvNavigationTest` unchanged and passing. JVM: `HomeViewModelTest` 42 (8 new),
`AndroidHomeSourceTest` 20 (3 new), `LibraryHomeHistoryTest` 6, `AndroidLibraryStoreTest` 13 unchanged.

**Each critical test was shown failing,** in one control run with six mutations together (14 of 47 red): the hero not landed,
Continue watching dropped when there are no rows, no progress on rail cards, no wall at the rail's end, and a card drawn full
whatever its progress. Returning to a Continue card was hidden behind the first, so it was shown alone: `expected Resume 6 but
was More info · The Long Dark`. Every file was restored and the real run replaced that XML.

**Screenshots.** After `#156` merged, eight Home references differed and each diff was looked at against git first: five by 130
pixels, only the Profile glyph, which is now `muted` at rest and `ink` focused, as the boards draw it (a reviewer's finding); the
hero, Continue watching and offline cases also by `#156`'s badges, icon tile and secondary text colour. Re-recorded, the eight
are pixel-identical to the images looked at. After `#159` and `#160`, a compare run on the merged tree changed only what this PR
changed: the hero's button gained `Info` (817 pixels, inside the button, in the hero and Continue watching cases), and offline
moved because it no longer scrolls; `#160`'s ring and scrim changed none of Home's images. Each was looked at, re-recorded, and
checked pixel-identical to what was looked at. The last compare run read 0 pixels different in all ten.

### Not verified

A real plugin's hero (the `:app` fakes answer with a title only); a real remote; real poster loading on a Continue card; a
download's resume record (`isFromDownload`, which the old home plays directly; here it opens the page like any other); an account
switch re-reading the list; right-to-left; the 6 s hero wait against a slow source.

### Published API

Additions only: 60 lines in `app/api/classes-jar.api`, all under `com/lagradost/cloudstream4/home/` and `/library/`, none removed
or changed. A first dump had one changed line, a synthetic accessor of a private helper given a default parameter; the helper
was put back and the synopsis given its own.

### Found, not changed

```
Found: Home's state actions (`Try again`, `Retry failed`, `Extensions`, `Search`) are text-only in HomeCopy.kt
Suggest: `StateAction(icon = CloudStreamIcons.Retry / Repository / Search)`, as `ui-components.md`'s table lists
Why: rule 2 and the table since #159; this is `KMP-37`'s copy, and the change moves every Home state's reference
```

```
Found: `focusRestorer`-style default entry, measured again: a rebuilt view's first stop takes focus before the page can place it
Suggest: nothing more here; noted because every page with a chrome row will meet it (search's and the library's do)
Why: `KMP-34`'s `LibraryFocusMemory` and this page's landing both work around the same window behaviour
```

## KMP-45 — Home's bookmarks rail and its watch-status filter (`TV Home Extras` 3e to 3g), and glyphs on Home's state buttons · **measured 2026-11-06**

**Status:** in pr #164. Boards `3e`, `3f` and `3g` of `design/TV Home Extras.dc.html` (`DS-13`), on the page `KMP-37` and `KMP-41`
built. Android TV only; desktop's home gains nothing but the state buttons' glyphs (its `HomeViewModel` keeps
`HomeBookmarkStore.None`, so it has no rail). `KMP-44` (Reload, Random, Search, `None`) and the details panel follow.

**The page now reads:** the chrome and hero, Continue watching, **Bookmarks**, then the source's rails. Bookmarks are this device's,
so, as the owner ruled for 3g and 3m, they stay above being offline, a failure, a page still loading, and "nothing installed".

**Parts.** In `:feature:home`: `HomeWatchStatus`, `HomeBookmark`, `HomeBookmarks` (in the state), `HomeBookmarkStore` (with a `None`
default), `HomeAction.BookmarkFilterPressed`, `HomeRowKey.Bookmarks`, and `tv/HomeBookmarksTv.kt`. In `:app`: `LibraryHomeBookmarks`, and
`AndroidLibraryStore.bookmarked()`, which reads the same rows `titles()` does without favourites and keeps `quality` for the badge.

### Read the way the old home reads it

- **The rows** are the library store's watch-status rows (`KMP-34`), so no second store and nothing new written. Newest by
  `latestUpdatedTime`, no paging, no trailing card, as the old rail.
- **The filter** is the old home's key, `DataStoreHelper.homeBookmarkedList` (per account), so the rollback and this screen agree.
  One status, or none; pressing the chosen chip clears it (`HomeParentItemAdapterPreview.kt:530-540`). The old home reads an empty
  key as none chosen, and so does this, which is why a first launch with bookmarks opens on 3f.
- **A chip only for a status that holds a title.** A kept status that no longer does reads as none chosen; the old one left an
  invisible chip checked.
- **A bookmark opens from its own source**, by the same `encodeId` as a card, whichever source the page shows.
- **Bookmarks are read locally, so they arrive before the source's rows.** Nothing reserves their band while the read runs; if
  that read ever became slower than the source's, everything below would jump down by `BookmarksRailHeight` (184dp: the 48dp chip
  cell, 16dp clearance and a 120dp poster).

### Every reduction from the boards

| Board | Drawn | Here | Why |
|---|---|---|---|
| 3e | Chips 12dp apart (`.chiprow { gap: 24px }`) | `ChipRow`'s 24dp (`ChipRowSpacing`) | The token wins (owner's ruling 6 on these boards); the CSS-against-token question is already open in `HANDOVER.md` |
| 3e | The chip's 8x16 marker cell | `Chip` as built: the selected chip's `muted` edge is its marker | The shared component |
| 3e | `HOLD OK OR MENU · DETAILS` on the rail | None | It names the details panel's gesture, which is the next PR's |
| 3e, 3f | Loading: skeleton cards with no chips | Nothing until the first read returns | It is one local read; a skeleton band for everyone without bookmarks would appear and vanish, moving the rails below it |
| 3g | Offline's body under Continue watching as well as the bookmarks | Under the bookmarks alone, the body; under Continue watching, the headline alone | `KMP-41`'s measured rule: there is no room at 720p |
| 3g | `9 BOOKMARKS` | Every bookmark, whatever the filter shows (`10 BOOKMARKS` in the screenshot) | "The count is `list.size` from the read" |

### Glyphs on Home's state buttons

`StateAction(icon = …)` per `ui-components.md`'s table: `Retry` on Try again and Retry failed, `Search` on Search, `Repository` on
Extensions. `Browse home` is not one of Home's: it is search's idle state (`search_state_idle_action`), so it is not touched here.
Desktop's home draws the same `StateView`, so its state buttons gain the same glyphs.

### Strings

Five new keys, each in all 62 directories, with translator notes in `docs/strings-translator-notes.md`:
`home_bookmarks_no_status_headline`, `home_bookmarks_no_status_body`, `home_offline_source_headline` (`%1$s`),
`home_offline_bookmarks_body`, and the plural `home_bookmarks_count`, with each directory's own plural categories. Reused:
`error_bookmarks_text`, `type_watching` and the four other statuses, `search_state_offline_headline`.

### Measured, each a wrong turn first

- **The offline state under the bookmarks moved the chrome off the screen for nothing.** Recorded: the state sized to exactly
  what is left came out a pixel over, from each band above rounding to whole pixels, and `KMP-41`'s reveal then moves the page
  by the whole chrome. A state now has to overflow by more than two pixels to move the page.
- **A rail card has no description**, so tests cannot find an unfocused one by its title; they walk to it and read focus.
- **Which chip is above a card** is Compose's search, under the card's middle, so tests accept either.

### Tested

JVM: `HomeViewModelTest` 47 (5 new). Device, `Television_720p`: `HomeScreenTvDeviceTest` 64 (14 new), including four
screenshot cases, `home-bookmarks-focus-card`, `home-bookmarks-no-status-focus-chip`, `home-offline-bookmarks-focus-retry`, and
`home-offline-continue-bookmarks-focus-retry` (review): offline with Continue watching and the bookmarks both above the prompt,
the tightest page at 720p and the one path that drops the bookmarks' offline body, with Try again focused and out of the bottom
focus band. `TvHomeNavigationTest` gains one through real navigation: a title given a watch status under the keys the app writes
is on the rail with its chip chosen, opens its real title page (`DetailTvFragment` since `KMP-38`, #153, merged in; it was
`ResultFragmentTv` before), Back puts focus back on it, and the chosen chip pressed empties the old home's key.

**Each critical test was shown failing**, in two control runs. Six mutations together (the rail dropped under a state, a
bookmark's return target, bookmarks out of the landing, the wall, the dim, the filter never clearing, and the fragment given no
store) turned 11 of the bookmark cases and the `:app` case red; the wall and the clear were hidden behind the landing, so a
second run with those two alone turned both red (`expected Saved 1 but was Watching`; a timeout waiting for the cleared filter).
Every file was restored and a real run replaced that XML.

**One run lost focus mid-run and is not counted.** On `Television_720p_2` the first 17 cases passed and every one after reported no
focus, the old tests included; the same commit was green on both emulators before and after. It reads like the display leaving
D-pad mode, which nothing in these tests does (no `.click()`); not explained, and recorded here rather than passed over.

**Screenshots.** Three references changed, each only by the state button's glyph, each looked at: `home-partial-failure-focus-retry`
(`Retry`), `home-nothing-installed-focus-action` (`Repository`), `home-offline-continue-focus-retry` (`Retry`).

### Published API

Additions only: 50 lines in `app/api/classes-jar.api` (five `R$string`/`R$plurals` fields, `LibraryHomeBookmarks`, `Bookmarked`,
`AndroidLibraryStore.bookmarked`), none removed or changed.

### Not verified

A real remote; real bookmarks with posters; an account switch re-reading the rail; right-to-left; a language whose chips do not
fit the row (`ChipRow` does not scroll, by design).

### Found, not changed

Nothing new.

## KMP-44 — Home's chrome named: Reload, Random and Search, and `None` as a source (`TV Home Extras` 3h to 3j, 3m) · **measured 2026-11-06**

**Status:** in pr #165, stacked on `KMP-45` (#164). Boards `3h`, `3i`, `3j` and `3m` (`DS-13`) and the owner's rulings of 2026-11-05: no Random
as a source, `None` stays; the Random button always shown, opening a random loaded title, absent when nothing is loaded; search in
the top bar only; a failed reload keeps the local rows above the error; a single choice applies at once with no buttons. Android TV
only; desktop's panel offers no `None` and its effect handler ignores the new effect.

**Parts.** `HomeSourceId.None`, `HomeStatus.Unsourced`, `HomeState.reloading` and `unsourced`, `HomeRowKey.Chrome`, the actions
`ReloadRequested`, `RandomRequested`, `SourceSearchRequested`, the effect `OpenSourceSearch`, and an injectable `Random` on
`HomeViewModel`. `CloudStreamIcons.Random` (Tabler `Dice`, in the 1.1.1 jar), and the glyph table's `Reload` and `Random` rows.
In `:app`: `DataStoreHomeSourceMemory` keeps `None` under the old screen's own name for it (`APIRepository.noneApi.name`), and
`HomeTvFragment` opens Quick Search on the source with nothing typed, as the old TV search button did.

### What each does

- **Reload** (3j): every section asked again while the rows and focus stay; a second press while one runs asks nothing; the answer
  replaces the rows in place, a partial answer is the built notice, and a failure is 3m, with Continue watching and the bookmarks
  above it and Try again focused. The hero stays: it came from a title's own page. `APIRepository.getMainPage` keeps no cache,
  so a reload is a real request.
- **3m's band** (review): under Continue watching or the bookmarks, a failure, a reload's or a first load's, is not the full-page
  `StateView` but one 48dp band where the source's rails were: the chrome's own `HomeFailureNotice` (hollow marker, "Could not
  load <source>", the source's mono detail, Try again at the band's end) in 3m's `.slot`, `surface` with a `line` hairline. Try
  again takes focus when it lands. With neither local rail the failure is still the built full-page error, as 3m says. This fixed
  what the first build of 3m drew: the headline and body read the wrong way round, Continue's art was cut by the top edge, and the
  ring sat inside the 28dp bottom focus band.
- **Random** (3j): one title from everything loaded, distinct by id, as `HomeFragment.kt:855-868` chose; focus comes back to the
  cell when the title closes.
- **Search** (3j): the only search on Home, of this source alone.
- **`None`** (3h, 3i): first in the panel, applies at once and closes. No hero, no source rails, nothing asked of a source; Reload,
  Random and Search are absent. Focus lands on the first card kept on the device; with neither rail kept it is the built
  `StateView`, whose one button (`Sources`, with `Source`) opens the panel. A `None` the old screen stored now reads as `None`; a
  stored `Random` still reads as never having chosen.

### Every reduction from the boards

| Board | Drawn | Here | Why |
|---|---|---|---|
| 3j | Clock `22:14` | None | `KMP-41`'s, still not built |
| 3j | Cells as a 48px tile in a 96px cell, glyphs `muted` at rest | `IconOnlyButton`: 32dp tile in the 48dp cell, glyph `ink` at rest, label drawn under the cell on focus | The shared component, which the brief names for icon-only cells |
| 3j | Reload and Random icon-only | Icon-only, as drawn | The owner named them an exception to `ui-components.md` rule 4 on 2026-11-06 (`DEC-17`) |
| 3j | Search during loading | Shown | It needs a source and a connection, and loading has both |
| 3h | Header meta `14 SOURCES AND NONE · APPLIES AT ONCE` | None | The panel's header is the built `RailHeader`; no word holds that line |
| 3m | Detail `SOURCE ERROR · HTTP 503 · RELOAD FAILED` | The source's own detail, `HTTP 503` | The built state's detail line; nothing says "reload failed" that the headline does not |
| 3m | Try again 144dp wide (`width: 288px`) | `StateActionButton`'s own width | The built button the notice already uses |
| 3m | Band padding 16dp and 12dp (`0 24px 0 32px`) | 16dp, and `MinFocusGap` for the 12 | In `HomeScreenTvDefaults`, as the rule for a value no token means asks |

### Strings

Three new keys in all 62 directories, with translator notes: `home_none_headline`, `home_none_body`, `home_source_none_subtitle`.
Reused: `reload_provider`, `home_random`, `search`, `none`, `pick_source`.

### Tested

JVM: `HomeViewModelTest` 53 (6 new), `DataStoreHomeSourceMemoryTest` 6 (1 new, 1 changed: `None` is now a choice),
`CloudStreamIconsTest` 6 unchanged (no glyph shared). Device, `Television_720p`: `HomeScreenTvDeviceTest` 76 (13 new, 2 changed for
the new cells and the panel's first row), 78 on top of `KMP-45`'s review (its one new case, and 3m's review here: one new,
`aFailureWithALongDetailUnderContinueWatchingIsOneBandAndNothingMoves`, and two changed, the reload failure asserting the band and
its ring, and `KMP-41`'s too-tall case moved to offline under both rails); four screenshot cases, `home-chrome-focus-random`, `home-source-panel-focus-none`,
`home-none-focus-source`, `home-reload-failed-focus-retry`. `TvHomeNavigationTest` 17 (2 new, through real navigation: `None`
chosen in the panel asks nothing and survives a restart; Random opens a loaded title's real page and Back puts focus on Random).

**Each critical test was shown failing**, in one control run with four mutations together (a reload that drops the rows, Random
forgetting where to come back to, Search offered offline, and `None` not kept by the host): all five cases red, three in the
module and both `:app` cases (`expected None but was null`; `expected Random but was More info · Fake One A 0`). Every file was
restored and a real run replaced that XML.

**Screenshots.** Ten references changed, each looked at: every page with rows gains Reload, Random and Search in the chrome; loading
gains Search; the source panel gains `None` as its first row. After review, `home-reload-failed-focus-retry` changed once more, on purpose: 3m's band in place of the
full-page state, looked at; it is the only reference this PR re-recorded after the first build.

### Published API

Additions only, in `app/api/classes-jar.api`: three `R$string` fields and one synthetic accessor in `DataStoreHomeSourceMemory`'s
companion (`access$getNONE$delegate$cp`), none removed or changed.

### For the owner — both answered 2026-11-06

```
Found: Reload and Random are icon-only on 3j, and `ui-components.md` rule 4 allows icon-only only for toggles, overflow and search
Suggest: name the chrome's cells as a fourth exception, or draw them as text buttons
Why: built as drawn; the rule and the board disagree
```

**Answered:** they stay icon-only with the label on focus, as built. Rule 4 names "Home top bar: Reload, Random" as an exception,
and `DEC-17` records the ruling.

```
Found: `IconOnlyButton` draws its glyph `ink` at rest, while the chrome's `IconCell` (Profile) is `muted` at rest
Suggest: one rule for an icon in the chrome at rest
Why: 3c and 3j draw every chrome glyph `muted` at rest
```

**Decided:** `muted` at rest. It changes the shared `IconOnlyButton` on every screen, so it is its own design-system PR after this
one and `KMP-45` merge; nothing here changes it.

### Not verified

A real remote; Quick Search opened from Home on a TV (the effect is tested, the fragment it opens is the old one); a reload against
a real plugin; right-to-left.

---

## KMP-43 — One series' downloads on TV, boards 6d and 6b (queue `Q15`) · **measured 2026-11-06**

**Status:** in pr · Android TV only. `MainActivity` sends `navigation_download_child` to `DownloadSeriesTvFragment` in one line, and deleting that
line is the rollback. `DownloadChildFragment` is untouched and stays the page on every other layout. It has the same destination and the same
`name` and `folder` arguments, so both callers, `DownloadsTvFragment` and `DownloadFragment`, open it unchanged.

**What it is.** `:feature:downloads` gains `series/`:
- `DownloadSeriesSource`, `SeriesShelf` (one episode's state, `Play next`, the order) and `DownloadSeriesViewModel`.
- `DownloadSeriesScreenTv`: the name, two lines of figures, `Play next`, `Delete all` and `Retry failed (n)`, then the episodes under
  their seasons. Beside each episode are 6d's three verbs: Play, the state's own verb, and Delete.

`:app` gains `AndroidDownloadSeriesSource`, over the keys `updateChildList` reads plus `DownloadFacts`, and `DownloadSeriesTvFragment`. Play,
pause, resume, cancel and delete all go through the old screen's own calls. **Play** is `handleDownloadClick(DOWNLOAD_ACTION_PLAY_FILE)`, so the
player still gets the whole series as a playlist from that episode.

**Column B**, the verb 6d's note calls the only way back into a dead download:

| State | B |
|---|---|
| Downloading | Pause |
| Paused | Resume |
| Failed | Retry |
| Queued | Cancel |
| Done | Nothing, and its place stays empty, so B never moves and Down from a B lands on the B below |

Resume and Retry are both the old popup's Resume, which did both.

### What the old page did that this does not, on a TV

- **Choosing some episodes and deleting them together.** Long-press, the checkboxes, Select all and `Delete (n | size)` are gone. Each row has
  Delete, and `Delete all` takes the series. Both go through the old dialogs.
- **The circular watch-progress mark**, and the check it became at 95%. Now it is the words `38% watched` on a finished row.
- **The download button's long-press snackbar** (`offline_file`).
- **`S1:E2 Name`.** Now it is `S1 · E2 · Name`.

**Gained:** Retry, Cancel, Pause on the row itself, `Play next`, `Retry failed (n)`, the poster, and a queued episode with no file yet. The old
page listed only episodes with a file, so a queued one appeared only once it had started.

### Where this departs from the boards

- **The three verbs are icon-only, as 6d draws them**, and each names itself under its cell when focused. `DS-16`'s rule 4 allowed icon-only
  only for toggles, overflow and search. Asked through the coordinator, the owner ruled on 2026-11-06 that trailing actions on a list row are a
  second ruled exception, beside the title detail action row (`DEC-17`; rule 4 in `ui-components.md`). `IconOnlyButton` draws a 32 dp outlined tile in a 48 dp cell, where 6d has a
  24 dp raised tile.
- **No Back cell and no free-space figure** in the top row. The remote's Back does what the cell would, and the rail is beside the page.
- **A season's header is a row tall and has no count.** 6d draws it 28 dp, which would put every row after it off the scroll pitch. Its count
  is left out because `episodes` is the word alone, and no format in every language joins a number to it. `3 MORE EPISODES` under the list is
  not drawn: the list scrolls.
- **Words reuse keys.**
  - The second figure line reads `1 active download · 1 download queued · 1 failed`.
  - A running row reads `128 MB / 500 MB · 4.2 MB/s · 1m 30s`, with no "left", from the old notification's own formats.
  - A queued row reads `Queued · 2/3 · 500 MB`.
  - A failed row reads `Download Failed · Network lost · 96 MB / 500 MB`.
  - A title reads `S1 · E3 · Name` where 6d has `S1 E3 · Name`.
- **A finished row's B is empty**, where 6d draws a dimmed Pause.
- **A failure's reason reuses five keys.** `NetworkLost` reads "Network lost"; `StorageFull` and, since `KMP-46`, `InsufficientSpace`
  read "Storage full"; `StorageUnavailable` and `NoUsableLink` have their own; `SourceFailed` and `KMP-46`'s `ServerRefused`,
  `AddressRefused`, `SourceChanged`, `SizeMismatch` and `HashMismatch` read the source reason. `Unknown` and `DestinationExists` show
  none, only `Download Failed`. No new key: nothing produces `KMP-46`'s cases yet, as no consumer uses the new engine.
- **A queued row has no leading glyph:** the pack has no queue glyph. A paused one has Pause.
- **A queued row's Play is disabled** until something is on disk, so Right from that row is Cancel. The old popup allowed Play only with bytes too.
- **`Retry failed` is there only when something failed**, and it hands focus to `Play next` when it goes.
- **Loading, empty, a failed read and offline have no board**:
  - **loading:** six skeleton rows;
  - **empty:** "No downloads yet" with `Go Back`, from the keys and the glyph table `KMP-40` and `DS-16` use;
  - **a failed read:** `KMP-40`'s words with Retry;
  - **offline:** the pill on the top row, which costs nothing.

  A page that empties while open closes, as the old one did.
- **Scrolling** is `KMP-40`'s: a row that fits does not move the list, and one that does not moves it by whole rows.

### Measured, each one a wrong turn first

- **The figure line read `Episodes · 980 MB on disk`**, with no number, on the first recording: the `episodes` plural is the word alone. It now
  uses `downloads_series_subtitle`, as the Downloads page's rows do. The season header had the same flaw, which is why it has no count.
- **A header for an unknown season** would have drawn nothing and still taken a slot, putting the rows after it off the pitch. Episodes with no
  season now have no header item.
- **Rows below the fold are not composed**, so a test that waited for one's text timed out. The tests travel there by remote.
- **Every row's verbs share their labels**, so `DpadDriver` names a stop the same on every row. The tests find the row from where the
  focused stop sits.

### Each critical test shown to fail

Temporary changes, all reverted. One run on `:feature:downloads` went 12 of 61 red; one on `:app` is recorded in the PR.

| Change | What went red |
|---|---|
| A failed row has no verb | the column test, the column-held-on-Down test, the Retry test, the failed-row screenshot |
| Nothing lands on arrival | arrival, `Play next`, `Delete all` and `Retry failed`, offline, two screenshots |
| Coming back does not look for the stop that left | the focus-return test |
| The platform's default scrolling | `series-scrolled`, by 124,121 pixels. **The forty-row test stayed green**, so it pins reachability, not the scroll |

### Not verified

- **The player opening** from Play or `Play next`. Only the effect is tested on the device; the real call is the old page's.
- **Pause, resume and cancel on a live transfer.** The source is driven with fakes. In the real app only a real failure fact was shown
  reaching its Retry; Retry was not pressed.
- **The poster loading**, which uses the app's loader and its user agent.
- **Any language but English on a device**, and any native speaker reading the 12 translations. Least sure: `ti`, `ckb`, `mt`, `so`, `my`,
  `or`, `am`, `apc` and `kn`. In `fa`, `kn`, `am`, `ti`, `ckb` and `az`, `Retry failed (n)` reads "Retry (n)", the file's own word kept short.

## KMP-46 — `:media:download`: a segmented downloader in commonMain, on FileKit · **measured 2026-11-06**

New module, new code. It downloads one file over several ranged connections at once and joins them.
Nothing depends on it yet: no `:app` or `:desktopApp` wiring, no UI, and HLS is out of scope. The
Android downloader is untouched.

**What it does.**
- **Probe:** a `GET` with `Range: bytes=0-0`, because HEAD lies.
- **Parts:** up to 8 by default, 32 at most, equal ranges. A worker with nothing left to do splits the
  part with the most bytes left, at or above that part's write cursor.
- **No ranges or no length:** one stream, which restarts from zero on every attempt.
- **Final file:** appears only after the checks of rule 4 below.
- **Progress:** a `Flow<DownloadSnapshot>` carrying bytes, total, rate, ETA and per-part state.
- **Pause, resume and cancel:** cancelling the collector pauses; `discard` cancels and removes the temp files.
- **Failures:** typed. `DownloadFailure` gains `ServerRefused`, `SourceChanged`, `SizeMismatch`,
  `HashMismatch`, `InsufficientSpace`, `AddressRefused` and `DestinationExists`. No exhaustive `when`
  exists on master.

The design contract is the KDoc of `DownloadRun`, `PartFetcher`, `PartTable` and `Join`. These are the rules
that exist because the old downloader broke them:
- **Rule 1:** the resume offset is always the part file's size, read from disk at every attempt.
- **Rule 3:** a single stream never appends.
- **Rule 4:** a file is reported complete only when every range is present, `size(joined) == total`,
  the hash (when given) matches the bytes re-read from disk, the move is atomic and `size(final) == total`.
- **Rule 5:** a 206 is accepted only when its range and validators match.
- **Rule 8:** storage errors are never retried.
- **Rule 17:** a transient probe failure never deletes a partial.

**Joining: part files, then an append-join.**
- *Chosen because* FileKit has no random-access write, and the rule "the offset is the file's size"
  falls out of it.
- *Peak space* is the total plus the largest part.
- *Join time, measured directly* (`joinParts` on 256 MiB, 3 runs; a temporary test, not committed):
  0 ms for 1 part (a move), and 132–180, 159–217 and 193–206 ms for 4, 8 and 16 parts.
- *Throughput at 4 MiB/s per connection* (256 MiB, medians of 3): 1, 4, 8 and 16 parts take 64.1, 16.2,
  8.3 and 4.3 s. A preallocated `RandomAccessFile` prototype is within 1–3% at every count.
- *Unthrottled on loopback* (server in the same JVM): the engine takes 0.82–1.13 s against the
  prototype's 0.28–0.35 s. The join explains about 0.2 s of that gap; the rest is the fetch pipeline,
  not yet profiled. On a real network the throttled numbers are the relevant ones.
- *Peak heap above baseline:* 10–15 MiB throttled. Unthrottled, 11, 18, 48 and 59 MiB for 1, 4, 8 and 16
  parts. Each connection holds Ktor's 1 MiB channel plus 8 × 64 KiB of queue, and the figures include
  uncollected garbage.

**FileKit 0.17.0** (`filekit-core` only), chosen by the owner.
- *0.14.2 is the newest version that forces no bumps.*
- *0.17.0 forces* stdlib 2.4.21, serialization 1.12.0 and minSdk 24, and it brings JNA on the JVM.
  `:app` is on minSdk 23, so `:app:processPrereleaseDebugMainManifest` fails if `:app` depends on this
  module. That is why nothing does yet.
- *Trap:* FileKit's inline `list { }` and `withScopedAccess { }` do not compile against JVM target 11.
  Use `list()`.

**Security.**
- Only `http` and `https`, with no userinfo.
- Literal and resolved addresses are refused when private, loopback or link-local, in every IPv4
  spelling and in IPv6, including v4-mapped, NAT64 and 6to4. Tests opt in with `allowPrivateAddresses`.
- Redirects are followed by hand and every hop is re-checked. `Authorization` and `Cookie` are dropped
  when the origin changes.
- The file name is sanitised, with no path traversal.
- The number of parts and the state file are capped (64 KiB).
- Nothing logs. No URL, header or referer reaches a `toString`, an exception, the state file or a snapshot.
- *Known residue:* DNS rebinding between the check and the connect. The fix is an OkHttp `Dns` in the
  transport.

**Tests.**
- JVM against a raw `ServerSocket` server, which can lie about lengths, reset mid-body and close
  without a length. No new dependency and no real network.
- `KillSuiteTest` kills a child JVM with `destroyForcibly` and resumes from what is on disk.
- Every completion is compared byte for byte with the source, and a watcher thread fails any run in
  which the final file was visible at a partial size.
- The rules were shown to guard by breaking each one and running its suite:

| Break | Tests that fail |
|---|---|
| rule 1: the offset cached across attempts instead of re-read | 15 in `FaultSuiteTest`, including both mid-part drop tests |
| rule 3: `runSingle` stops deleting before a retry | the single-stream drop test |
| rule 4: no hash check before the move | `wrong SHA-256 fails with HashMismatch…` |
| rule 4: no `size(joined)` check | nothing: the post-move `size(final)` check catches the same short file |
| rule 4: neither size check | `a join that comes out short…`, added for this |
| state file: `recordedDone` trusted over the disk | `a state file claiming every part is done…` |
| rule 8: a write error retried as a network one | 4 storage tests in `FaultSuiteTest` |

**A flaky test fixed on the way.** `splitting stops at maxPartFiles` failed 1 run in 5: its fourth split sat on the `left >= 2 × minPartBytes` edge. It now uses `minPartBytes` = 32 KiB, and passed 8 runs out of 8.

**Ktor loses bytes on a dropped connection.**
- Ktor's `ByteChannel` buffer is 1 MiB (`javap`, ktor-io-jvm 3.6.0). When the engine fails a body, the
  unread contents of that buffer are discarded, so up to about 1 MiB of received bytes can be re-fetched.
- A raw socket received all 1,000,003 bytes in 15 of 15 attempts. A bare Ktor read loop got 270,336
  to 1,000,003.
- The tests allow for this loss with a named tolerance. Fixing it means reading below Ktor.

**Adopting it.**
- *Android:*
  - minSdk 24, or `tools:overrideLibrary`, plus the stdlib and serialization bumps.
  - kotlinx-io's `atomicMove` on API 23–25 and SAF `content://` (where FileKit's move is copy and
    delete) are untested.
  - It still needs wiring to the foreground service and to `DownloadFacts` (`KMP-36`).
- *Mac:* JNA comes with FileKit on the JVM; a Java 17 runtime; wiring into `:desktopApp`.

**Not verified.**
- iOS runs the pure rules only (`commonTest` on the simulator gate), not a download.
- iOS `getaddrinfo` and free space are compiled, not exercised.
- No Android device run: a test APK would hit the same minSdk wall.
- DNS rebinding, as above.
- The engine's unthrottled slowdown is not profiled.

## KMP-59 — The Android downloader runs `:media:download` behind a switch · **measured 2026-11-06**

`ExtractorLinkType.VIDEO` downloads can go through the segmented engine (`KMP-46`) instead of `downloadThing`.
The switch is `EngineDownloadSwitch.useSegmentedEngine`. **It defaults to `false`**, so nothing changes for users until it is turned on.
Subtitles, HLS, DASH and torrents stay on their own paths. Builds on `DEC-18` (minSdk 24). Android only:
the Mac service is `KMP-52`.

**Shape.**
- `DownloadManager`'s `VIDEO` arm gets one `if`, and `DownloadQueueManager.init` gets one call (the sweep below). Nothing else in an existing file changes.
- `EngineVideoDownload` reports through the same `DownloadMetaData` as `downloadThing`. So events, `DownloadFacts` (`KMP-36`),
  the notification and the foreground service hear what they always heard.
- `EngineDownloadRules` holds the pure decisions and has 19 unit tests.
- **Staging.** Every destination (MediaStore, a document tree, a path) gets a staged copy first, in `noBackupFilesDir/downloads/<episode id>/`.
  This is because the engine needs a real directory with atomic moves.
- **On COMPLETED, in this order:**
  1. Write the marker `complete`.
  2. Copy into the destination that `setupStream` resolves (truncating).
  3. Check the copy's length on disk.
  4. Write `KEY_DOWNLOAD_INFO`.
  5. Delete the staging directory.
- **If it crashes:**
  - *After the copy:* one visible file. The restart copies again without fetching.
  - *During the copy:* the restart copies over the partial.
  - *A wrong length:* the destination is deleted, the staged file is kept, and the result is `SizeMismatch` with no next mirror.
- **Space.** The peak is about 2× the file, checked at the first snapshot that carries a total and again before the copy (`InsufficientSpace`).
- **Sweep.** At start-up, staging whose episode is gone (stopped, deleted, or pruned after a failure) is removed. It never touches an id that is
  running, queued, or has a resume package and an episode entry.

**API 24/25: kotlinx-io has no atomic move there.**
- kotlinx-io 0.9.1 picks its mover with `Class.forName("java.nio.file.Files")`. Below API 26 that class does not exist, so it throws
  `UnsupportedOperationException`. This was measured on an API 25 emulator, for kotlinx-io directly and through FileKit.
- Desugaring does not help: it rewrites class references to `j$.nio.file.Files` but not the string.
- **The fix:** `FileKitStorage.atomicMove` falls back to `File.renameTo` only on that exception. A `false` from it becomes an
  `IOException`, and the source is kept.
- Before the fix, 3 of the 5 move tests failed on API 25.
- `media/download` now applies `cloudstream.kmp.device-test`. `AtomicMoveDeviceTest` passes 6/6 on `Api25_Test` (7.1.1).
  The JVM's `AtomicMoveFallbackTest` adds 6 more, and `jvmTest` passes 203/0.

**What changes when the switch is on.** TLS and the LAN refusal first.

| Old behaviour | New behaviour | Who notices |
|---|---|---|
| — | **Switch off too:** `DownloadQueueManager.init` runs the staging sweep at every start. With nothing staged it reads one directory listing (null when the directory does not exist) and returns | Nobody |
| TLS not checked (`verify = false`) | Checked, over `app.baseClient`: self-signed or expired hosts fail | Users of sources with broken certificates |
| LAN, loopback and private addresses allowed | Refused (`AddressRefused`, next mirror) | Users downloading from LAN or self-hosted servers |
| The partial file is visible and listed in Downloads | Nothing is visible or listed until the copy ends. Every list reader drops an episode with no file. The fix is a reader change (below) | Everyone |
| Peak space ≈ 1× the file | ≈ 2×, always on internal storage, even when downloads go to an SD card, USB drive or document tree | TV boxes with small internal storage. Likely the most visible |
| Done at the last byte | Plus a full local copy; the notification sits at 100% meanwhile | Big files on slow storage |
| A failed download keeps its file | Its staged parts stay on internal storage until the next start after the episode is stopped, deleted or pruned | Users with failed downloads |
| 3 connections (`download_concurrent_key`) | 8; the setting is ignored | Throttling hosts; users who set it |
| Under 10 MiB is invalid; over 50 MB a failure keeps the file and tries no other mirror | Neither rule. HTML is refused by content (`NoUsableLink`) | Users of short clips |
| Resume needs ranges | Also needs an ETag or Last-Modified, or it starts again from 0 | Users of minimal servers |
| An old-path partial resumes | Ignored; the engine starts again in staging, and the old partial stays visible until the copy overwrites it | Users mid-download when the switch flips |
| OkHttp follows 20 redirects | 5, each re-checked; HTTPS→HTTP refused | Long redirect chains |
| Extension headers sent as given | `Range`, `If-Range`, `Accept-Encoding`, `Host`, `Content-Length`, `Transfer-Encoding`, `Connection` and non-ASCII values are dropped | Extensions that set those |
| Name from `getFileName` | Engine sanitising on top (control and bidi characters, reserved names, 200 bytes) | Rare; subtitle matching by name in those cases |
| Deleting the file mid-download stops it (`fileChecker`) | No file exists yet to delete | — |
| A running queue item offers "Delete file" | Offers "Cancel", which cleans up staging | Queue users |
| Pause keeps connections open | Pause closes them | Servers |
| Work stealing (engine) | A split can fetch an in-flight range's tail twice (seen as `GET 32153500-33554431` inside `GET 30687232-33554431`) | Bandwidth on metered links (`KMP-61`) |

**"Not listed until done" needs a reader change.** The smallest one: a `getDownloadListInfo(id)` that falls back to
`KEY_DOWNLOAD_INFO` and the staged length when the file is missing. It would be used in `DownloadViewModel.calculateDownloadStats`,
`DownloadViewModel.updateChildList`, `AndroidDownloadsSource.readEpisodes` and `AndroidDownloadSeriesSource`.
Their `fileLength <= 1` filters and the play actions would need to handle it. Not done here, because it changes the list readers: that is `KMP-60`, which blocks turning the switch on.

**Tests** (all local, none CI).
- **Unit:** `:app:testPrereleaseDebugUnitTest` 230/0; `EngineDownloadRulesTest` 19/19.
- **`EngineDownloadDeviceTest`:** 8/8 on `Television_720p` (API 36) and 8/8 on `Api25_Test` (API 25).
  - Switch on: byte-identical. Switch off: still `downloadThing`.
  - Cut off midway and resumed from disk in a fresh instance, with no held byte fetched again.
  - A dropped connection resumed within its part.
  - A full disk: `StorageFull`, no visible file, parts kept.
  - A crash after the copy, and one during it: each restart copies without fetching.
  - The start-up sweep.
- Below API 29 the test grants storage permission with `pm grant`.
- The full `:app` device suite: 97 tests, 0 failures, 1 skipped (`TvHomeRealPluginProof`).
- **`checkExtensionApi`:** additions only, for the new classes. No existing line removed or changed.

**Not verified.**
- A real process kill on Android. The device test cancels and resumes in a fresh instance; the engine's JVM `KillSuiteTest` kills a process.
- Document-tree folders, SD cards and a real full partition (only an injected quota).
- The sweep inside a real `DownloadQueueManager.init` at app start; the test calls it directly.
- A pause or resume sent through `downloadEvent` during a run.
- A DNS setting change rebuilding the client.
- The two space refusals on a device (unit tests only).

## KMP-60 — Engine downloads listed while they run · **built 2026-11-07**, blocks turning `KMP-59`'s switch on

With `EngineDownloadSwitch.useSegmentedEngine` on, a download used to appear in no Downloads list until its copy finished:
`getDownloadFileInfo` returns null while the destination file is missing, every list reader dropped null, and the
pruning in `calculateDownloadStats` keyed off the same null.

**What was built.** All new code is in `EngineVideoDownload`'s companion; the existing files get one-line changes.
- **The record at start.** The wrapper writes `KEY_DOWNLOAD_INFO` at the run's first snapshot, and again when the total
  first becomes known, through the same `DownloadMetaData.setDownloadFileInfoTemplate` that `downloadThing` uses after
  its headers. It holds what `downloadThing`'s holds: total, folder, display name, base path and `linkHash`. No URL,
  header or referer.
- **`getDownloadListInfo(context, id)`.** Returns `getDownloadFileInfo` when the destination file exists. Otherwise, only with
  the switch on and only when `<staging root>/<id>/` exists and the record does, it returns the staged bytes (file
  sizes under the staging directory, the marker aside, capped at the total), the record's total and `Uri.EMPTY`.
  With the switch off it is `getDownloadFileInfo` and reads nothing else.
- **`refusesPlay(context, id)`.** True only with the switch on, a staging directory, and no destination file.
- **`deleteStagingForDelete(context, id)`.** `deleteFile` found no file and returned false, so a listed engine download
  could not be deleted. Now it does, only when the file lookup is null: a running download's staging is left to its
  own stop, which the delete's Stop event triggers; an idle one's is removed. With the switch off it is false.

**The readers.** Every caller of `getDownloadFileInfo`, and what it does now:

| Caller | Now |
|---|---|
| `DownloadViewModel.calculateDownloadStats` | `getDownloadListInfo`; its `fileLength <= 1` filter lets an empty path through. Not pruned while it has staging |
| `DownloadViewModel.updateChildList` | `getDownloadListInfo` |
| `AndroidDownloadsSource.readEpisodes` | `getDownloadListInfo`; its `fileLength <= 1` filter lets an empty path through |
| `AndroidDownloadSeriesSource.read` | `getDownloadListInfo` |
| `BaseFetchButton.setPersistentId` (result-page and list buttons) | `getDownloadListInfo`, so a staged download shows its bytes and offers Resume/Pause as a partial file does |
| `DownloadButtonSetup` `DOWNLOAD_ACTION_LONG_CLICK` | Unchanged: the "available offline" snackbar stays file-only |
| `DownloadQueueManager.addToQueue` (the 98% check) | Unchanged on purpose: a 99%-staged download must still be queueable, and `fileLengthOnDisk` must never get an empty path |
| `DownloadFileGenerator.generateLinks` | Refuses (below) |
| `DownloadQueueAdapter` (reads `KEY_DOWNLOAD_INFO`) | Unchanged code; now offers "Delete file" for a running engine download, which works through `deleteStagingForDelete` |

**The play actions.** Every play route goes through `DownloadButtonSetup.handleDownloadClick(DOWNLOAD_ACTION_PLAY_FILE)`
and then `DownloadFileGenerator`:
- Phone Downloads: `DownloadAdapter` (a film's header row, a child row).
- The download button's popup, "Play file" (`PieFetchButton`), on the result page and in the lists.
- Continue Watching from a download (`SearchHelper`).
- TV Downloads, a film (`DownloadsTvFragment.open`), and TV series Play and Play next (`DownloadSeriesTvFragment`).

The refusal is at both seams. `handleDownloadClick` refuses the episode with a "Downloading" toast (an existing string) and
does not navigate. It also leaves running engine episodes out of the playlist it builds. `DownloadFileGenerator` returns
no links for an empty-path episode that `refusesPlay` names, instead of handing the player `Uri.EMPTY`. No download has a
share or "open with" action: the result page's share sends the title's page link. During the copy, the destination
file exists, so a play opens the partial copy, which is what `downloadThing`'s partial file does.

**What changes with the switch on** (the switch defaults to off):

| Old (switch on, `KMP-59`) | New | Who notices |
|---|---|---|
| Not listed until the copy ends | Listed from the first snapshot, with the staged bytes | Everyone |
| A failed download's staged parts are invisible; the list prunes its entry when idle | Listed with its staged bytes and offered Resume, which resumes from staging. Not pruned | Users with failed downloads |
| The result-page button shows nothing for a staged download; a click starts again from 0 | Shows the staged bytes and offers Resume/Pause/Delete | Result-page users |
| The queue popup offers Cancel | Offers "Delete file", which stops the run and removes its staging | Queue users |
| — | Play on a running download shows "Downloading" and stays put; the playlist skips it | Anyone who taps it |
| — | During the copy, the row's bytes drop to the copy's progress, because the real file wins | Big files on slow storage |

**Switch off.** Every new branch checks the switch first and reads nothing when it is off: `getDownloadListInfo` is
`getDownloadFileInfo`, the `<= 1` filters see a real path, `refusesPlay` and `deleteStagingForDelete` are false.

**`checkExtensionApi`.** 3 added lines and 0 removed or changed, all on `EngineVideoDownload$Companion`:
`getDownloadListInfo`, `refusesPlay` and `deleteStagingForDelete`. The record is read with `DataStore`'s `Class` overload,
because the reified one inlines a `TypeReference` class into `classes.jar`.

**Tests** (`EngineDownloadListDeviceTest`, switch on, against `EngineTestServer`, throttled so each download is still
running when the test looks). All local, none CI, on `Television_720p` (API 36):
1. **Listed while running.** After 2 MiB are staged, `getDownloadFileInfo` is still null, and each of the four readers
   lists the episode through its real code: `updateHeaderList` (so `calculateDownloadStats`), `updateChildList`,
   `AndroidDownloadsSource.contents` and `AndroidDownloadSeriesSource.contents`, each with progress bytes > 1 and the
   right total. The two Compose sources get no facts, so only their disk read can list it. `getDownloadListInfo`'s path is
   `Uri.EMPTY`, and the record holds the folder, display name, `linkHash` and nothing else.
2. **Play refused.** `refusesPlay` is true, and `DownloadFileGenerator.generateLinks` returns false without handing the
   player anything. In the real `MainActivity`, `handleDownloadClick(DOWNLOAD_ACTION_PLAY_FILE)` runs with an activity and
   does not navigate to the player. Read, not driven by taps: `DownloadAdapter`, `PieFetchButton`'s popup, `SearchHelper`
   and the two TV fragments, which all send that same click.
3. **Not pruned.** With the queue idle as `calculateDownloadStats` sees it, a control episode with no record is pruned and
   the running download's entry is not.
4. **Stop and Cancel.** A Stop through `downloadEvent` during the fetch, a Cancel (`EpisodeDownloadInstance.cancelDownload`,
   which is what `DownloadQueueManager.cancelDownload` calls for a running one) during the fetch, and a Stop at the copy.
   Each leaves no staging directory, no destination file and no listing. The record stays, as stopping `downloadThing`
   leaves it, and the stop ends as `success`, as `DOWNLOAD_STOPPED` does. The copy is reached by a restart after a
   crash after the copy, and the Stop is sent from the notification it raises as it starts copying, so the copy loop's
   stop check ends it with the destination open.
5. **Delete.** `deleteFilesAndUpdateSettings` on a running engine download reports it deleted, stops it, and leaves no
   staging, no file and no record.
6. **Pause and Resume through `downloadEvent`.** After the pause, no request is made, no byte is sent and no part file
   grows for 2 s. After the resume, no request overlaps a held range by more than the channel's discard (1 MiB,
   `KMP-69`), and the file is byte-identical.

8/8 pass. The full `:app` device suite: 120 tests, 0 failures, 1 skipped (`TvHomeRealPluginProof`), including
`DownloadFactsDeviceTest` 7/7, `DownloadFactsDiskFullDeviceTest` 1/1 and `EngineDownloadDeviceTest` 8/8, all unchanged.
(The full run predates test 5; 8/8 is from a class run after it was added.) `:app:testPrereleaseDebugUnitTest`: 244/0.
`EngineTestServer` now treats an interrupt in a throttled body as the client leaving: closing it mid-transfer used to
crash the test process.

**Not verified.**
- API 24: not run. API 25: 8/8 on `Api25_Test` (Android 7.1.1), run in `KMP-80`.
- A Cancel during the copy. It reaches the engine as the same `downloadEvent` Stop (`EpisodeDownloadInstance.cleanup`), read, not run.
- A stop in the middle of a long copy. The test's stop lands at the copy's first buffer.
- The play actions driven by real taps on the phone and TV lists, the popup and Continue Watching (read: all use the tested click).
- `BaseFetchButton` showing staged bytes; a failed engine download listed, resumed from staging, and not pruned while idle;
  deleting an idle staged download.
- Document-tree and SD-card destinations.

## KMP-80 — Switch on: a restart's fresh link reaches the engine's resume · **built 2026-11-08**, the last blocker for `KMP-59`'s switch

`KMP-62` made a resume ask the provider again for a link; `KMP-59`'s engine resumes only when the file's identity
(size, ETag, Last-Modified) still matches what it staged, and otherwise fails `SourceChanged`, deletes the staging and
the mirror loop tries the next link from 0. The two had never run together: `NoStoredLinksDeviceTest` runs with the
switch off. `EngineResumeAfterRestartDeviceTest` runs them together, switch on, through the real
`EpisodeDownloadInstance`.

- **The first run** is cut off the way an expired link is: once about a third is staged, the server cuts every connection
  and answers 403. The run fails `ServerRefused(403)` and keeps its parts and its link-free resume package.
- **The restart** leaves what a restart leaves: stored keys and staging on disk, no link cache, no status. A new instance
  starts from the stored package; the provider answers with a fresh URL on another server, under the same labels.
- **Same file behind the fresh link:** completes byte-identical, and the fresh server is asked for exactly the bytes the
  staging lacked: no request overlaps a held range, and the union of requests is the file minus what was held.
- **Another file behind it** (same size, another ETag): `SourceChanged` is reported, the staging holds 0 bytes at that
  moment, no body byte is fetched from the changed link, and the next mirror downloads its own file from 0.
- Parts are fixed at an eighth of the file (`minPartBytesForTests`), so no part is split and the held ranges are exact.
- `EngineTestServer` gains a refusal status, a hook before each answer, and a way to cut connections in flight.

**Results** (all local, none CI).
- `EngineResumeAfterRestartDeviceTest`: 2/2 on `Television_720p` (API 36) and 2/2 on `Api25_Test` (Android 7.1.1).
- **Control:** with the fresh link's ETag changed in the first case and the changed link's ETag set back to the
  original in the second, both fail (2/2): "the resume failed … SourceChanged" and "SourceChanged was never reported".
- `EngineDownloadListDeviceTest` (`KMP-60`): 8/8 on `Api25_Test`. It had run on API 36 only.

**Not verified.** API 24; a real process kill (the restart here is a new instance on the same disk, as in `KMP-62`);
a size change rather than an ETag change (the engine checks the size on the same line, read, not run); a picked
mirror (no `mirror` label is stored when the queue item has no links).

**Release notes for turning the switch on** (owner, 2026-11-08: accepted with no opt-in):
- Downloads now check the server's security certificate. A source whose certificate is self-signed or expired can no
  longer be downloaded from; the app tries its other mirrors.
- Downloads from servers on your own network (home servers, local addresses) are no longer allowed.
- A download needs free space for about twice its size on the device's internal storage while it runs, even when it is
  saved to an SD card or USB drive. If there is not enough, the download stops with a not-enough-space failure before it fills the storage.

## KMP-61 — Engine: a work steal refetches an in-flight range's tail · **open**, measured, no fix

Seen in `EngineDownloadDeviceTest` (`aDownloadCutOffMidway…`): the split's new request started inside a range already in flight, so 1,400,932 bytes were fetched twice on a 32 MiB file.
- The range in flight was `#18 GET 30687232-33554431`.
- The new request was `#19 GET 32153500-33554431`.

The test now bounds the union of the requested ranges, not their sum, so it still proves a resume. The cause is not investigated. The first place to look is where `PartScheduler` picks the split point against the victim's open request.

## KMP-62 — The old downloader stores no link · **built 2026-11-07**, waits on review

Found while checking `KMP-59` against the no-link ruling for `KMP-52`; the owner approved applying it here (2026-11-06). The old downloader keeps what it needs to ask the provider again, not the links.
No `DownloadObjects` class changed, and `:app:checkExtensionApi` shows no diff.
- **What was stored.**
  - `KEY_RESUME_PACKAGES` held each `ExtractorLink` (url, referer, headers, audio tracks).
  - `KEY_RESUME_IN_QUEUE` and `QUEUE_KEY` held the same, plus `SubtitleData` (url, headers) for a picked mirror.
- **Now.**
  - Those three keys are written with `links` empty and `subs` null.
  - A side record under the private key `download_resume_source/<id>/` holds:
    - `item`: the `DownloadQueueItem` without links or subs, which carries the episode's `data` and the provider;
    - `mirror`: the SHA-256 of the picked mirror's `source` and `name`, which are the provider's and can hold a host;
    - `attempt`: the same hash for the link the partial file came from.
  - A resume with no links runs `RepoLinkGenerator` again, with the same `LOADTYPE_INAPP_DOWNLOAD` filter and profile
    order as a first download. A picked mirror is kept if the provider still offers it, otherwise the profile's top
    link is used. The partial file resumes only on the link it came from.
  - Links the user just picked stay in memory for the running process.
- **Removed** with `KEY_RESUME_PACKAGES` (the queue service's Stop handling and `PieFetchButton`), on every file
  delete (`deleteFileAndUpdateSettings`, which every delete in the app reaches, so it holds with no service running),
  and when a download completes. It is kept out of backups. A finished download reads nothing from it.
- **Migration** runs at `DownloadQueueManager.init`. It is idempotent, and it writes the side record before it strips
  the package that needs it.
  - A package whose queued item survives gets a side record and loses its links.
  - Queued items lose theirs.
  - A package with nothing to ask the provider with keeps its links until it finishes or is removed.
- **Behaviour change.** A source that no longer answers now fails that resume with `DownloadFailure.NoUsableLink`, and
  the partial file is kept.
- **Tests.** `NoStoredLinksDeviceTest`, 9 cases. They read every `shared_prefs/*.xml` raw and look for markers in a
  link's url, referer, headers, source and name and in a subtitle's url and headers. The checks run after enqueue, run, fail,
  stop, finish, restart and migration.
- **Known and left.** `KEY_DOWNLOAD_INFO.linkHash` (`url.hashCode()`) and `DOWNLOAD_HEADER_CACHE.url` (the result
  page URL).

---

## KMP-49 — The TV library's sources: 5e's panel, 5a's provider button, a service's lists (queue `Q12`, first slice) · **measured 2026-11-06**

**Status:** in pr · Android TV only. The library opens on the source chosen last, as the old screen does, and shows the
local list or a tracking service's own lists. Sort (5d) is `KMP-50` and the unreachable and cached states (5b) with
offline are `KMP-51`. The owner's rulings for all three are `DEC-20`.

**The seam.** `:feature:library` gains `LibrarySources`: every source with its state (connected, account, the time of
its last read as this app recorded it, the local list's count), the one to open on, remembering a choice, and reading a
service's lists. `SyncedList` is a service's own list, and `LibraryTab.Synced` its tab. `LibraryTitle` and `LibraryCard`
gain, with defaults, `episodesWatched`, `episodesTotal` and `rating`. `LibraryViewModel(sources = null)` is the default,
which is desktop's: nothing there changes, and `:desktopApp`'s 395 JVM tests still pass.

**On Android.** `AndroidLibrarySources` reads `AccountManager.syncApis` through `SyncRepo`, the old screen's way:

- **The source to open on** is `LibraryViewModel.currentSyncApi`'s rule: the one under `"$currentAccount/last_sync_api"`
  where it is still signed in, else the first that is. Choosing one writes that same key, so the old screen and this one
  agree.
- **A read** is `SyncRepo.library()`. It fails with the exception it holds, or with the old screen's `"Unable to fetch
  library"` where a service answers nothing. While `requireLibraryRefresh` is false, the last read is shown again, as
  `reloadPages(false)` does. A successful read clears that flag, as the old screen does.
- **The time of a read** is a new key, `"$currentAccount/library_synced_at"`, written only on a successful read. Nothing
  before this PR wrote one, so no source shows an age until it has been read once here (`DEC-20`).
- **A service's title** opens through the old screen's opener (`LibraryFragment.loadLibraryItem`): the opener saved for
  the title, unless it is the default, else the one saved for the service, else the default. That is the title's page
  where a provider of that name is loaded, and a search by its name otherwise. A service's titles are not providers, so
  in practice a search. The saved openers are read, never written.
- **Its text is sanitised** as the local store's is. `AndroidLibraryStore`'s three helpers are repeated in
  `LibrarySourceText.kt` rather than moved: moving them took `MAX_TEXT_CHARS` and a synthetic accessor out of
  `classes-jar.api`, which may only grow.

**The owner's choices, each in one place** (`DEC-20`): which sources are listed and in what order is
`AndroidLibrarySources.listed`, how a service's lists become tabs is `syncedLists`, and what a source that is not signed
in does is `LibrarySourcePanelTv`'s row.

### What the boards draw that this does not, or draws differently

| Board | Drawn | Here | Why |
|---|---|---|---|
| 5e | AniList, Local, MyAnimeList, Simkl, Trakt | MAL, Kitsu, AniList, Simkl, Local, signed-in ones first | `DEC-20`: the five the app has. MyAnimeList is `MAL`, the app's own name for it |
| 5e | A leading disc on each row | None | It would hold the service's logo, a brand mark this module does not have |
| 5e | `NOT CONNECTED` | `Not connected`, disabled and still a stop | `DEC-20`. A stop, so its reason can be read at three metres: `ListRowState.focusableWhenDisabled`. Logging in stays in account settings |
| 5e, 5a | `SYNCED 4 MIN` | `Synced 4m ago` | The app's own `%dm` / `%1$dh %2$dm` / `%1$dd %2$dh %3$dm` formats. Upper case is not applied, as the readout's is not |
| 5e | Local's `ON DEVICE · 308 TITLES` | `On device · 308` | No `titles` word in every language; the readout already shows the number alone |
| 5a | A rating badge `★ 9.1` | `9.1` in the card's trailing badge | `Badge` has no glyph slot. The badge is only on a service's card with a rating of the user's own |
| 5a | `12 / 28 EPISODES` | `12 / 28 Episodes` | `library_episodes_progress` with the `episodes` plural |

**Gained back from `KMP-34`'s reductions,** for a service's cards only: the user's own rating, the episode count, and the
bar from episodes seen over episodes in all. A local card is unchanged.

**What the old TV screen does that this still does not:** sort (`KMP-50`), Random, choosing or changing an opener (the
long-press popup), and saying why a service could not be read (`KMP-51`; for now it is the shared failure page with
Retry).

**A service's cards are in the order they were last updated** (`lastUpdatedUnixTime`, newest first), as the local
list's are by `keptAt`, not in the order the service returns them.

### Measured, each one a wrong turn first

- **The provider button's second line was clipped** in the first recording: its descenders were cut. It used
  `labelMono`, a 32px line, in the stack's 12dp box. The board's `.cbm` is 20/24px, which is `captionMono`. The panel's
  note had the same mistake.
- **BACK left the screen with the panel open.** `UiDevice.pressBack()` finished the test's activity, and so did a
  remote's BACK, sent as `DetailScreenTvDeviceTest` sends it, with or without the field ever focused and after a press
  inside the panel. The logcat showed it going to the window's back dispatcher, which held only the activity's own
  callback. `SidePanelHost`'s KDoc says a host must wire platform back itself, and `DetailTvFragment.wirePanelBack` does;
  this fragment did not, so **in the real app BACK would have left the library rather than closing the panel**.
  `LibraryTvFragment` now registers the same callback, enabled while the panel is open, and the device test hosts one
  as the detail test does. Every later `SidePanelHost` caller needs those lines too, which is the detail fragment's
  argument for the design system owning them.
- **Waiting for the panel's focus by label proved nothing**: the button and the shown source's row have the same words.
  The device test waits on where the focused node is.
- **The first API dump removed two lines**, from moving the store's helpers. They are repeated instead (above).
- **Two translations escaped an apostrophe** as `\'`, which `materialiseComposeStrings` refuses. These sources hold a
  plain `'`.

### From the review on `#175`

- **Switching back to a service shown from its cache opened the wrong titles.** The opener's map was one for all
  sources, replaced only by a fresh read, so A, then B, then A from the cache kept B's, and A's cards fell back to the
  local opener. It is kept per source now, with the one shown. The new JVM test fails on the old code.
- **A late read could still take the shown source's place**: the shown one was set when a read returned, so a slow read
  for B returning after switching back to A made B the shown one. It is set when a source is asked for now, and a late
  read stores only its own titles. A second JVM test, with B's read held open, fails on the previous code.
- **A service not read yet, or not readable, showed the local list's tabs** under its name: the first recording of the
  failure page had Watching to Favorites over AniList's error. It has no tabs now (`tvTabs`).
- **The provider button** takes `ChromeButton`'s padding and inset outline. `insetOutline` was internal to
  `:core:designsystem` and is public now. It is not a `ChromeButton` variant: that is one line, a label and a count,
  and a two-line variant would have this one caller.
- **The failure page's Try again** carries the Retry glyph (`DEC-17`). The line is `KMP-34`'s.
- **A service's empty list and a service's failure** have device cases, and the failure a screenshot.
- **`DEC-20`, not `DEC-18`**: master's `DEC-18` is minSdk 24, and `DEC-19` is taken.

### Each critical test shown to fail

Temporary changes, each reverted, on `Television_720p`.

| Change | What went red |
|---|---|
| A disabled row is not a stop; the panel does not land on the shown source; the store's tabs on a service | the disabled-row test, the service's-tabs test, both screenshots (13,098 and 15,986 pixels). The landing test then stayed green, because the shown source was also the first row, so a test that reopens on the second was added |
| The panel does not land on the shown source, alone | `reopenedAfterAChoiceThePanelLandsOnTheNewSource` |
| No platform back callback (the code as first written) | `backClosesThePanelAndFocusComesBackToTheButton`: BACK finished the activity |
| `:app`: the fragment's ViewModel without sources | `theButtonOpensEveryRealSourceAndOnlyTheLocalListIsConnected` and `leftFromTheFieldReachesTheRail`; the records test stayed green, as it should |

### Tested

- **JVM.** `LibrarySourcesViewModelTest` 13, `LibraryShelfTest` (4 added), and `AndroidLibrarySourcesTest` 13 over a fake
  `SyncAPI`: the order, the source to open on, the lists and their words, sanitising, a rating of 0, the recorded time
  and the service's cache, switching back to a cached service and a late read for another, both failures, and the opener's rule.
- **Device, `:feature:library`.** `LibrarySourcesTvDeviceTest`, 13 with fake sources: the button and Left from the field,
  the panel opening on the shown source with every source's state, a choice applying at once, reopening on the new
  choice, a disabled row that is a stop and chooses nothing, Done and BACK each closing with focus back on the button, a
  service's own tabs and its cards' facts, a service's empty list, a service that fails and Retry, and three screenshots. `KMP-34`'s four screenshots are unchanged, 0 pixels.
- **Device, `:app`.** `LibrarySourcesTvNavigationTest`, 3 in the real app: the panel over the real `syncApis`, none
  signed in and none made; Left from the field reaching the rail (`BUG-34`); and both records under the real keys.

**Screenshots:** `library-service-grid-focus-card` (5a over a service), `library-source-panel` (5e) and
`library-service-failed-focus-retry`, each looked at.

### Not verified

- **A real service's lists.** No account is signed in on the emulator and none is made: every service read in a test is
  a fake `SyncAPI`.
- **A service's title opening.** The opener's rule is JVM-tested; the activity calls it makes are the old screen's and
  were not run.
- **Logging in or out while the page is open.** The next arrival reads the sources again.
- **Any language but English on a device**, or a native speaker reading the five new translations. Least sure: `ti`,
  `am`, `so`, `mt`, `ckb`, `my`, `or`, `as`, `kn`, `ml`, `ne`, `eo` and `apc`.

---

## KMP-47 — TV search suggests while typing, boards 8a and 8b (queue `Q7`) · **measured 2026-11-06**

**Status:** in pr. Boards 8a and 8b of `design/TV Search Suggestions.dc.html` on `Television_720p`, API 36.

### What it adds

- **8a: one column of suggestions while typing.** Up to six rows. They merge what the suggestion service offers with recent searches that contain the typed words. A recent search and a suggestion that differ only in case fold into one row, `RECENT · ALSO SUGGESTED`, keeping the recent search's words. Recent searches keep two of the six places. The row equal to the typed text comes first, then the service's order, then recent-only rows.
- **A count per row, `7 SOURCES`.** It is the number of distinct sources whose quick search returned that exact title (ignoring case). Before the fan-out answers, a row shows no number, so nothing is shown that could change. A partial fan-out rides the chip row as `FailureBanner` with `Retry failed`, and the counts are of the sources that answered.
- **OK on a row** runs the full search for its words and records them in history.
- **8b: nothing matches.** Nothing suggested and nothing recent matching gives `Nothing matches “…”` with the query quoted, and `Search all N sources` on the chip row. **The field keeps the focus** (8c's action takes it; 8b's does not, because the user is still typing). Offline with nothing suggested says offline, not that nothing matches.
- **The old `SearchSuggestionApi` is wrapped, not moved** (plan §5.3). `:feature:search` has a `SearchSuggestionSource` seam; `SearchTvFragment` passes one when `search_suggestions_enabled` is on (default on), and none when it is off. With none, typing runs the quick search into rails as before. Desktop passes none.
- 8 new keys, in all 62 locales.

### Focus

| Press | Goes to |
|---|---|
| Down from the field, with suggestions | The first chip, then Down to the first row |
| Down / Up in the column | The next / previous row |
| Right on a row | Stays |
| Down from the field, 8b | The first chip; Right along the chips reaches `Search all N sources` |

### Reduced from the boards

- **The count and its word are one line, `7 SOURCES`,** not the board's number over a word. A plural word without its number fails lint's `ImpliedQuantity` in 19 locales (wherever one form covers several numbers: `fr` 0 and 1, `ru` 1, 21, 31…), and `:app:lintPrereleaseDebug` stops on it. The trail is at least 120 dp (`.trail` 240 px) and grows for a long word.
- **8b's copy changed for `BUG-30`:** "Press OK on the field" is now "Press the keyboard’s search key", since OK on the field opens the keyboard.
- `dune messiah` sits sixth, where the board has it fifth: recent-only rows follow the service's suggestions.
- The focused row has no selected fill, like 4c; 8a draws one.
- The retry keeps `Retry failed`, not the board's `Retry 3 sources`.
- History dates stay lower case (`2 days ago`), as in `KMP-42`.
- Captions use the theme's caption face; the board draws them in mono.
- **8b's mono line counts instead of claiming an answer:** `0 SUGGESTIONS · 0 RECENT MATCHES`, not the board's `SUGGESTIONS ANSWERED · 0 MATCHES`, which a failed service would make false (the Found item below).
- **Offline is read from the device on 8b's path** (`:core:connectivity`'s `ConnectivityFlow`), not only from the fan-out, which runs only for quick-searchable sources. Before this, offline on most installs showed "Nothing matches". `UNKNOWN` behaves as online. The view model collects the flow for its lifetime (`stateIn(Eagerly)` in its scope): `AndroidConnectivityFlow` shares `WhileSubscribed`, so a bare `value` read with no collector stays at what it read when it was made, and a network lost after search opened was missed (caught in review).
- **A suggestion service that is down reads as 8b.** `SearchSuggestionApi.getSuggestions` returns an empty list on any failure (it logs it), so "down" and "answered with nothing" cannot be told apart without changing it.
- **Counts appear only when the selected sources can quick-search.** `MainAPI.hasQuickSearch` defaults to false, so on most installs typing runs no fan-out and the rows have no counts. A full search per settled keystroke was ruled out: it would query every provider on every keystroke (Found, not changed).
- The setting is read when the screen is made, so changing it applies on the next visit.

### Tests

- `SearchSuggestionsTest` (common, 7): the board's merge, the six-row and two-recent caps, filling from recent, exclusion, blank input, the counts.
- `SearchSuggestionsViewModelTest` (JVM, 12): offline with no quick-searchable source (it failed before the connectivity check); losing the network after search opened, with a fake shared `WhileSubscribed` like the real flow (it failed on a bare `value` read); online and unknown being 8b; and only the settled query is asked, rows before counts, 8b, 8b's Submit, offline, a partial fan-out, no quick search, a row's full search, no suggestion source.
- `SearchSuggestionsDeviceTest` (device, 8, the board's data): six rows with counts 7, 9, 6, 4, 2, 0; the D-pad walk; OK on a row; 8b keeps the field focused and its action searches; screenshots `search-suggestions-counted`, `-counting`, `-partial`, `-nothing-matches`, each looked at before it was recorded.
- `TvSearchNavigationTest` (`:app`, the real path): with suggestions on, typing one letter by remote (OK, the keyboard, then `input text`) shows 8b through the real fragment, setting and `SearchSuggestionApi`; with them off, no suggestions appear. One letter, because the API answers it without the network. The test waits for 8b's action on the chip row, not its headline: it first passed, then failed twice waiting for the headline, and a run that printed the screen's text showed the query, `Search all 0 sources` and the open keyboard's own words, with no headline. The keyboard covers the middle of the screen, where 8b's headline sits.

**The gates caught one more:** `HashMap.putIfAbsent` in the merge is `java.util`'s, so the iOS canary did not resolve it and lint's `NewApi` stopped on it at minSdk 23. It is `getOrPut` now. The JVM and Android builds compiled it.

**One fix was caught by a test, and one test was made deterministic.** The first device run failed `nothingMatchesKeeps…` at the field's focus (`Focused = 'false'`): the chip row's action requested focus on appearing, as 8c wants. The screenshot showed the ring on `Search all 12 sources`.

### Found, not changed

```
Found: `SearchSuggestionApi.getSuggestions` returns an empty list on every failure
Suggest: return a result that tells a failure from no suggestions
Why: a suggestion service that is down shows 8b's "Nothing matches", which is not true. 8b's mono line is worded to be true either way meanwhile.

Found: rows count only quick-searchable sources, and `MainAPI.hasQuickSearch` defaults to false, so most installs show no counts
Suggest: nothing; a full search per settled keystroke was ruled out (app-10, 2026-11-06)
Why: it would query every provider on every keystroke, which costs their servers and gets the app throttled.
```

**A board correction, logged for the owner's next design pass:** 8b assumed no on-screen keyboard. While the keyboard is open, 8b's headline and body sit behind it; only the chip row's action shows. Recorded under "Owed on the boards" in `docs/plans/tv-screen-audit.md`; no change now (app-10, 2026-11-06).

### Not verified

`TalkBack`, a real remote, the live TMDB answer on the device (the real-path test's one letter never reaches the network), counts against a real quick-searchable provider (none is installed on the AVD), and the least-confident translations: `ti`, `am`, `ckb`, `my`, `or`, `as`, `mt`, `apc`.

### Published API

Additions only. `app/api/classes-jar.api` gains 8 `R` fields (3 `R$plurals`, 5 `R$string`), 0 removed or changed.

## KMP-53 — The Mac window's shell: a sidebar, a menu bar and their keys · **steps 1 and 2 built 2026-11-07**

`docs/plans/desktop-design.md` kept three tabs while three did the job: "A sidebar is worth it at the fourth"
(lines 117-118). Downloads is the fourth (`KMP-54`), so the bar becomes board D1's sidebar for every top-level screen.

**Step 1, this change.**
- `DesktopSidebar` in `:desktopApp`, `home/DesktopShell.kt`:
  - a 220 column on `surface` with a hairline on its right edge;
  - 32 rows with a 16 glyph and the destination's name, the chosen row `raised`;
  - at its foot, the extensions notice, which sat at the end of the tab bar before.

  `TopLevelScreen` keeps its signature, so no screen changed.
- `DesktopShortcuts`, read by the window before any screen, as `EscapeHandler` is:
  - ⌘1 to ⌘3 go to Home, Search and Library, at the top level only, where the sidebar is;
  - ⌘[ goes back wherever there is something under.
- Tests in `:desktopApp`:
  - `DesktopShellTest` (3): the shortcuts, plain keys ignored, and a click on a row;
  - the four window-level tests that clicked the old tab bar now click the sidebar, with coordinates measured from renders.

**Next steps.**
- **Downloads as the fourth row, and ⌘4.** It is the one-line wiring once `KMP-52` (#177) and `KMP-54` (#179) are on
  master: `TopLevelDestination.Downloads`, its glyph `Download`, and the host composing `DownloadsScreenDesktop` over
  `DesktopDownloadsSource`.
- **Settings ⌘,.** Board D1 puts it at the sidebar's foot, but the Mac has no settings screen yet.

**Step 2, the menu bar.** Its one new word is `I18N-13` (#185).
- `DesktopMenuBar` puts the screen's menu bar in the window. Its Go menu lists Home, Search and Library with their keys, a
  separator, then Back. What does nothing is greyed: the destinations away from the top level, and Back where nothing is
  under.
- **The menu owns the keys, and nothing else reads them.** Compose's `MenuBar` binds each item's shortcut. The window's
  `onPreviewKeyEvent` from step 1 would have fired each key a second time, and a doubled ⌘[ pops two screens. So it now
  handles only Escape.
- **Keys per platform.** On a Mac, ⌘1 to ⌘3 and ⌘[, as Finder and Safari. Elsewhere, Ctrl+1 to Ctrl+3 and Alt+Left, as
  Windows' and GNOME's file browsers.
- `DesktopShellTest`: the Go menu's routing and greying, and the key table on both platforms. A `MenuBar` needs a real
  window, so the menu itself is not drawn in a test.

**DECIDED.** The menu has only Go. Options were (A) also File (Play from link) and Window, or (B) Go alone. Chose B:
Play from link has no Mac host yet, and macOS gives every app its own app and Window menus.

**DECIDED.** The menu bar is split off. Options were (A) ship it now with English menu titles, or (B) defer it to an
`I18N` task that adds the titles. Chose B: every word comes from `:core:strings`, and the shortcuts already work without it.

## KMP-48 — TV Quick Search is a side panel over its caller, boards 8d and 8e (queue `Q8`) · **measured 2026-11-06**

**Status:** in pr, stacked on `KMP-47`'s branch (Q6–Q8 are serial in the audit). Boards 8d and 8e of `design/TV Search Suggestions.dc.html` on `Television_720p`, API 36.

### What it adds

- **The panel is drawn over the screen that opened it, which does not reflow.** On TV, `navigation_quick_search` is replaced at runtime by a `DialogFragmentNavigator` destination with the same id (`openQuickSearchOnTvScreen`, one line in `MainActivity`, which is the rollback). A fragment destination would replace the caller; the board's note is that the caller stays. The dialog window covers the nav host, not the rail, so the rail is dimmed by `SidePanelOutsideDim` once, as for 4d.
- **Every `QuickSearchFragment.pushSearch` caller reaches it unchanged:** Home, the result pages and the library pass the same arguments (the query, trimmed of `(DUB)`/`(SUB)` there, and optionally the providers).
- **8d, answering.** `SidePanel` with `Quick search`, `RESULTS STREAM IN AS SOURCES ANSWER` and `3 OF 12 ANSWERED · 2 FAILED`, which update as each source answers (`QuickSearchSource`, a `Flow`; `ApiRepositorySearchSource.quickSearchPanel` launches one search per provider and emits after each). Rows are `ListRow`s with the chassis disc and `PROVIDER · TYPE · QUALITY`, in the order the providers were asked, pinned first. Until a source answers the only stop is `Done`; the first answer moves focus to the first row, and later answers leave it where it is.
- **8e, empty.** Every source answered and none carries it: `No matches`, the query quoted, and `Open full search` as the commit row, focused. It takes the query to the search screen through `nextSearchQuery` and the rail item, as the intent handler does.
- **Every source failed** says `Search failed`, or `You are offline` when every failure was the network's, with `Try again`, which carries `Retry` (`DEC-17`). Not drawn on the board; the copy is the search screen's 4b copy. Their glyphs are the pack's `Warning` and `Offline`; 8e's is `Search`, as 8b's is.
- **OK on a row** opens the title. The navigation pops the dialog, so BACK from the title returns to the caller.
- **More rows than fit scroll by whole rows** with `RowScroll`, moved from `:feature:downloads` to `:core:designsystem` in its own commit (approved by app-10; the downloads call sites change only names and imports, and the downloads suite stays 61/0 with its 17 screenshots 0/0). Five rows fit whole and do not move the list.
- 7 new keys, in all 62 locales, none a plural.

### Focus

| Press | Goes to |
|---|---|
| Opening, before any answer | `Done` |
| The first answer | The first row |
| Down / Up | The next / previous row, then the commit row |
| Left / Right, Down past the commit row | Stays (the panel's trap) |
| BACK, `Done` | Closes; the caller keeps its focus and state |

### Reduced from the boards

- **No year in a row's line.** `SearchResultItem` carries no year, so the line is `VIDSRC · MOVIE · 4K`, not `VIDSRC · 2024 · MOVIE`. Adding one is a change to the shared item.
- **8e's body does not spell the number.** `Every source answered and none of them carry “…”` instead of `All twelve sources answered…`: the status line above already says `12 OF 12`, and a number in the sentence would make it a plural in 62 locales.
- **The meta line is left out once a source has failed,** since `ALL SOURCES ANSWERED` would not be true; the status line moves up into its place.
- **No paging, and one search for every caller.** The old fragment gave a single provider with `hasQuickSearch` a quick search as you typed and a paged grid. The panel runs every provider's full first-page search, a single provider included.
- **8e and every-source-failed have no `Done`.** Their action takes `Done`'s slot, as 8e draws it, so BACK is the only way to close the panel from those two states.
- **It closes without sliding out.** The dialog goes with the press; `SidePanelHost` has no signal for the end of its exit.
- The caller is the old fragment's callers' screens; the board draws the search screen behind the panel.
- The board's panel title sits at y64; `SidePanel`'s header is at `ChromeRowTop` (y56), as 4d's is.
- With no providers at all the panel says `Search failed`, as the search screen's `noTargets` does.

### Tests

- `QuickSearchViewModelTest` (JVM, 5): the trimmed query asked once, each answer replacing the last, a blank query asking nobody, `Try again` starting over, each action's effect, when a round is finished.
- `QuickSearchPanelDeviceTest` (device, 14, the board's data): a sixth row moving the list by exactly one row and back; counting and the first row's focus; Down through the rows to `Done` and `Done` closing; OK on a row; a later answer keeping focus; 8e's copy and `Open full search`; every source failed and `Try again`; offline; screenshots `quick-search-asking`, `-answering`, `-scrolled`, `-empty`, `-failed`, `-offline`, each looked at before it was recorded.

**The scroll test caught a layout bug.** Its first run failed at "a row that fits moved the list". Logging the spec's inputs showed a 379px viewport, not 395px: `SidePanel`'s rows slot spaces its children `RowGap` apart, and the list and the rule were two children, so the list was 12dp short and cut the fifth row's foot. They are one child now.
- `TvQuickSearchNavigationTest` (`:app`, the real path, 4): 8e's `Open full search` lands on the real search screen with the query in the field and the Compose rail (`TvRailSwitch.useComposeRail`) on Search; and `pushSearch` from the new Home with two fake providers registered where plugins go, one answering and one failing. The panel is a `FloatingWindow` destination over the same, still drawn `HomeTvFragment`; it counts `1 OF 2 ANSWERED · 1 FAILED`; the `(DUB)` suffix is trimmed before the provider is asked; BACK and `Done` leave Home as it was; OK on a row loads that title through the provider, and BACK from the title returns to Home.

### Found, not changed

```
Found: SidePanelHost has no callback for the end of its exit slide
Suggest: an onClosed parameter, raised when the panel unmounts
Why: a host in its own window (this dialog) cannot slide the panel out before closing the window.
```

### Not verified

`TalkBack`, a real remote, a real provider's answer (the fakes stand in), the Home and result-page buttons that call `pushSearch` (the test calls it directly; the result page needs a loaded title), and the least-confident translations: `ti`, `am`, `ckb`, `my`, `or`, `as`, `mt`, `apc`.

### Published API

Additions only. `app/api/classes-jar.api` gains 60 lines: 7 `R$string` fields, `QuickSearchTvFragment` and its synthetic classes, `ApiRepositorySearchSource.quickSearchPanel`, and `openQuickSearchOnTvScreen`. 0 removed or changed.

## KMP-63 — Per-episode watch state across the detail seam (board 7i's `WATCHED` and `24 MIN LEFT`) · **measured 2026-11-07**

**Status:** open · **Found by:** `KMP-38`'s PR 2a, measuring board 7i before building it.

Board 7i draws two readouts on an episode row that nothing in the tree can supply: `WATCHED` on a
finished episode and `24 MIN LEFT` on a started one. 7i's other two row fields were simply discarded
in one mapping and are carried as of PR 2a (`DetailEpisode.runTimeMinutes`, `.airDateMs`). These two
are **not a mapping fix**, and that is the whole content of this task.

### Why it is a contract question

Per-episode progress is `LocalDetailRecords.viewPosition(id)` (`AndroidDetailSource.kt:288`), keyed on
`:app`'s **episode id**. `DetailEpisode` carries no id, and that is deliberate: its `data` is opaque and
"goes back to `DetailSource.links` unread" by its own KDoc, which is what keeps `:feature:detail` free
of any `:app` or `:library` edge. The only progress this seam carries is `DetailResume`, and that is
**title-level** — one resume for the last-watched episode, not one per row.

So the question is what crosses the seam, not how to format it:

| Option | Shape | Cost |
|---|---|---|
| A | `DetailEpisode.progress: DetailResume?` per row | No id leaves `:app`; the host does the lookup it already does for `resume`. No new concept. |
| B | `DetailEpisode.watched: Boolean` + `remainingMinutes: Int?` | Smallest surface, but two derived fields the host must keep consistent with each other. |
| C | An id on `DetailEpisode` plus a `progress(id)` call on `DetailSource` | Puts `:app`'s id into the contract and adds a round trip per row. |

**Recommendation: A.** `resumeOf` (`AndroidDetailSource.kt:591`) already builds a `DetailResume` from a
stored position, so a per-episode one is that function called per row rather than a new mapping, and
`DetailResume` already owns the zero-duration guard that `ResultEpisode.getWatchProgress()` lacks
(`DetailContracts.kt:72-75`). B re-derives in the host what `DetailResume.remainingMinutes` already
computes in the module. C is last because it is the only one that changes what the seam *means*.

**`classes-jar.api`:** A and B add fields to a type that is not in it — `DetailEpisode` appears nowhere
in `app/api/classes-jar.api`, and `toTitleDetail` takes an erased `List`, so no published line changes.
Verified by grep, not assumed.

**Why PR 2a does not do it:** a readout whose data does not exist is worse than none — the same reason
`KMP-38` left Download and Mark watched out of the action row.

## KMP-54 — The Mac downloads pages, boards D1 to D6 · **built 2026-11-07, not wired**

`DownloadsScreenDesktop` in `:feature:downloads`, `desktop/`, from `design/Desktop Downloads.dc.html` (`DS-18`, #172). It runs
on `DownloadsViewModel` and `DownloadsState` unchanged: what is shared is the state, the logic and the words; what is the Mac's
is the row, the selection, the menu and the sheet (`DEC-19`). Built on `DesktopFoundation`'s existing components; the
desktop component layer (`DS-19`) comes later. Strings are `I18N-11` (#178), which this stacks on.

**What it does.**
- **D1:** a 56 row per title with art, name and kind, status with its rule, and size. On hover the row shows its verbs:
  Retry or Resume, Show in Finder, and Delete. They are glyph-only 24 cells with tooltips (rule 4, extended by the owner).
- **D2:** selection is the pointer's, as in Finder. Click, ⌘-click, ⇧-click and ⌘A all act on press.
  - `DownloadsDesktopSelection` turns each gesture into the view model's existing actions, so TV's choosing mode is
    untouched.
  - Right-click, or Control-click, opens a menu at the pointer (Show in Finder, Delete), which acts on the selection.
  - The footer counts the titles and their size, or what is selected.
- **D3:** the confirmation sheet hangs under the toolbar, and Cancel is the filled default (rule 1). Return and Escape
  both cancel; only a click deletes.
- **D5:** the offline pill sits beside the title. The storage-full banner offers Free up space, and the view model decides
  between choosing rows and the system's storage settings. Empty and failed are `StateView`s that never take focus.
- **D6:** below a 780-wide page the size column folds into the second line.
- **D4, one series:** `DownloadSeriesScreenDesktop` on `DownloadSeriesViewModel` unchanged.
  - The toolbar has Back (⌘[) and the path, Downloads › the series.
  - Below the art and counts: Play next, Retry failed (when there is one), and Delete all, through the same sheet.
  - Episodes sit under season headers, which now give the count through `downloads_episode_count`.
  - Each row's hover verbs follow the board: Play on a finished row; Pause and Cancel on a running one; Resume (the `Play`
    glyph, one glyph per meaning) on a paused one; Retry on a failed one; Cancel on a waiting one. Show in Finder appears
    once the file is on the disk, and Delete is always last. A double-click plays.
  - The meta line reads "7 EPISODES · 3.94 GB ON DISK · 12.40 GB FREE", the counts read "1 downloading · 1 queued · 1
    failed", the footer reads "Season 1 · 7 episodes · 3.94 GB", and the breadcrumb's Downloads is a muted link. The new
    keys are in `I18N-11`.
  - **A failure names its reason.** The owner ruled on `KMP-52` that a resume asks the extension for a fresh link, so a resume
    that finds none reads "Download Failed · No working link" and offers Retry.
- **Keys:** ⌘A, ⌘⌫ (asks first), Return opens the one selected title, ⌘R shows it in Finder, and Escape clears.
- `CloudStreamIcons.Folder` (Tabler `Folder`) is added for Show in Finder, as the glyph table rules.

**Measured.** All local:
- `DownloadsScreenTest`: 7 tests in `:desktopApp`, with real pointer and key events against a fake source and the real view
  model. Renders: `-Pcloudstream.downloads.renders=<dir>`.
- `DownloadsDesktopSelectionTest`: 10 tests in `commonTest`.

**Not here.**
- **Wiring.** The host needs:
  - `KMP-52`'s source (#177): `fileOf` behind `canReveal` and `onReveal`, and `delete` after the sheet;
  - the shell's sidebar entry (`KMP-53`).

  Both are a few lines in `:desktopApp` once those land. #177 also adds `:feature:downloads` to `:desktopApp`, so the second
  to land drops the duplicate line.
- **Open in the menu.** No word for it yet; double-click and Return open.
- **The transfer rate.** `DownloadTitle` carries none.
- **New components.** The tooltip, the context menu and the bottom rule are private here. They are candidates for `DS-19`.
- **Shared words.** The failure reason and the episode code's fallback are copies of TV's private rules in `SeriesRows`,
  kept identical until `KMP-65`. The line itself is the name alone, on purpose: D4 has a number column.
- **Cancelling asks first when there is something to lose.** A running or waiting episode with bytes on the disk opens a
  sheet before Cancel discards them ("Cancel this download?", with Keep downloading as the filled default); one with
  nothing fetched cancels at once. This is the PM's call (2026-11-07), following rule 1's pattern for destructive
  confirmations; the words are in `I18N-11`.
- **"Show in Finder" is the Mac's word.** It sits in a shared key; Windows and Linux will need their own ("Show in folder")
  when those builds exist.

**DECIDED** (small calls on the recommended option, the owner's standing rule of 2026-11-07):
- **Cancel on a running episode.** Options were (A) an additive `SeriesAction.CancelRequested` that calls the source's
  `cancel`, (B) routing Cancel through Delete, or (C) leaving Cancel off the running row. Chose A: the board draws it, and
  `KMP-52`'s host cancels in any state. TV never sends it.
- **Free space on D4.** Options were (A) an optional `DownloadSeriesContents.freeBytes`, defaulting to null so every host
  compiles unchanged, or (B) a second source for the disk. Chose A: one emission carries the page. `KMP-52` fills it,
  and the line leaves it out while it is null.

**Reductions from the board**, where existing copy was reused rather than adding keys:
- "Download Failed" and "Download Paused · 40%" for D1's "1 failed" and "Paused · 40%".
- "Download queue · 1/4" for "Queue 1/4".
- "Play from link" for "Play link".
- "Movie" for "Film".
- "4.24 GB / 5.10 GB" for "4.24 GB of 5.10 GB".
- "Not enough storage · Needs 2.40 GB more" for D5's sentence.
- "Free up space" for "Open Storage Settings".
- **D1's Play from link and Download queue are not drawn on the Mac** (`KMP-75`, the PM's call: no dead controls). The Mac has
  no page behind either. `DownloadsScreenDesktop`'s `canPlayFromLink` and `canOpenQueue` draw them once a host has one;
  the toolbar band stays, so nothing below moves.
- D5's empty copy is TV's.
- The D3 sheet's body gives the size freed without the titles' names.

## KMP-65 — One source for a series' words on TV and the Mac · **open**, follow-up of `KMP-54`

`DownloadSeriesScreenDesktop` copies two private rules from TV's `series/tv/SeriesRows.kt`: `titleOf` ("S1 · E3 ·
Name") and `reason` (a `DownloadFailure` in words). The reason mapping and the code's fallback logic are kept identical
meanwhile. **The line's format differs on purpose**: TV draws "S1 · E3 · Name" in one line, and the Mac draws the name
alone, because board D4 gives the number its own column (the code stands in only where there is no name). To do:
1. Move both into one `internal` function in `series/`, and have both screens call it.
2. Replace `"${episode_short}$number"`, the one-number fallback in both copies, with a key that takes the number as
   an argument, so a translation can place it.
   The same concatenation is in `HomeResume.line()` in `HomeScreenDesktop.kt` (`KMP-55`): `season_short` and
   `episode_short` glued to their numbers for Continue watching's "S2 E4". It should use the same key, with a sibling
   for the season, so both read the same in every language.
3. Rerun the TV downloads device suite and keep its screenshots at 0/0, because this touches a `tv/` file.

**Done in `KMP-66` (#205):** the player panels' numbered fallbacks take `player_panel_numbered` (`%1$s %2$d`), so a
translation can place the number. The sites, all in `GeneratorPlayer.kt`: the Source row's `"${video_source} ${index + 1}"`,
the two Subtitles group rows' `"$title ${index + 1}"`, and the Audio row's `"${audio_singular} ${index + 1}"`. Step 2's
episode and season numbers want their own keys, since "E3" is not "Episode 3"; they stay to do here.

## KMP-69 — Engine: a dropped body loses megabytes already received (about 5 MiB at most) · **open**, low, follow-up to `KMP-61`

Measured in `KMP-46`: Ktor's `ByteChannel` buffer is 1 MiB (ktor-io-jvm 3.6.0). When the engine fails a body, the
unread bytes in it are discarded. A raw socket received all 1,000,003 bytes in 15 of 15 attempts, and a bare Ktor
read loop got 270,336 to 1,000,003. So each dropped connection can refetch up to about 1 MiB. `BUG-45` hit this in a
device test.
- Like `KMP-61` (a work steal refetching a tail), it wastes bandwidth and does not corrupt anything: the bytes on
  disk are always the truth.
- The fix means reading below Ktor (draining the channel into the part file before failing, or a smaller channel).
  Not started; measure the cost on a metered link first.

**"Up to 1 MiB" is wrong: it is up to about 5 MiB** (measured 2026-11-07, `BUG-45` reopened).
- **Measured on a device.** `EngineDownloadDeviceTest` cuts a part with a clean close (a FIN). The loss is the bytes
  sent minus the part file's size at the resume.
  - Cut at 1.5 MiB of a 2 MiB part, 10 runs on `Television_720p`: 0, 917,504, 0, 1,417,216, 270,336, 0, 966,656, 0,
    892,928 and 1,327,302 bytes.
  - Cut at 3 MiB of a 4 MiB part, 10 runs: 638,976, 835,584, 1,040,384, 253,952 and 974,848, then (splitting off)
    0, 1,138,886, **2,416,838**, 1,605,632 and 443,746.

  The maximum, 2,416,838, is more than one channel can hold. Each run gives only a lower bound on the worst case,
  which the code sets.
- **Where the bytes are**, from `javap` of ktor-client-core-jvm, ktor-client-okhttp-jvm and ktor-io-jvm 3.6.0, and
  okhttp-jvm 5.3.2. The body crosses **two** `ByteChannel`s.
  - *Channel 1, the OkHttp engine's: two buffers.* The OkHttp engine's `toChannel` loop (`OkHttpEngineKt$toChannel$1`) runs
    `writeAvailable { source.read(ByteBuffer) }`, then `flushWriteBuffer()`, then `flush()` only when `!hasFreeSpace`.
    `hasFreeSpace` is `flushBufferSize < 1048576`. So `flushBuffer` grows to 1 MiB plus one read, of at most one 8 KiB
    segment, before the writer waits. `moveFlushToReadBuffer` moves all of `flushBuffer` into `_readBuffer`, but only
    when `_readBuffer` is exhausted. So the reader's buffer can hold about 1 MiB while `flushBuffer` fills to about
    1 MiB again.
  - *Channel 2, the response pipeline's copy.* `bodyAsChannel()` asks `HttpClientCall.bodyNullable` for a
    `ByteReadChannel`. `defaultTransformers` (`DefaultTransformKt$defaultTransformers$2`) answers that type with a new
    channel, filled by `writer { body.copyTo(channel) }`. `copyTo` moves **all** of channel 1's `readBuffer` (up to
    about 1 MiB + 8 KiB) into channel 2's `writeBuffer`, then calls `flush()`. `flush()` moves all of that into
    `flushBuffer` and only then waits while `flushBufferSize >= 1 MiB`, so channel 2's `flushBuffer` can reach about
    2 MiB + 8 KiB, and its `_readBuffer` the same once moved. This channel is what the engine reads.
  - *The bound, from the code, not measured:* channel 1's `flushBuffer` (1 MiB + 8 KiB), plus channel 2's two
    buffers (2 MiB + 8 KiB each), so about 5 MiB + 24 KiB. Counting channel 1's `readBuffer` too, which `copyTo`
    empties at each step, gives 6 MiB + 24 KiB at most.
  - *Why it is all lost.* When the body throws, the engine's writer job calls `ByteChannel.cancel(cause)` on channel 1.
    After that, `isClosedForRead` is true whenever `closedCause` is set, whatever is still buffered, and
    `getReadBuffer()` throws. `copyTo` then fails, cancels channel 1 and closes channel 2 with the cause, which drops
    channel 2's buffers the same way. `readAvailable` returns -1 and the engine sees `closedCause`
    (`PartFetcher.stream`).
  - *OkHttp and Okio add nothing on a clean close.* `Http1ExchangeCodec$FixedLengthSource.read` throws
    `ProtocolException("unexpected end of stream")` only when the socket read returns -1. That happens after every
    byte before the FIN has been handed on, so no Okio segment and no socket receive buffer still holds body bytes
    when the channel is cancelled. A reset (RST) instead of a FIN could also drop what the socket held. That case is
    not tested or measured here.
  - *The engine adds nothing.* `PartFetcher.stream` reads at most `bufferBytes` (64 KiB), reserves it and writes it
    to the part file's buffered sink before reading again. The sink is closed in a `finally` under `NonCancellable`,
    which flushes its last partial 8 KiB segment. There is no queue between the network read and the file write. In
    every run the size read after the failure equalled the next attempt's offset. `KMP-46`'s heap note, "8 × 64 KiB
    of queue", fits 8 workers each with a 64 KiB read buffer. No write queue exists in the code.
- **So the cost is up to about 5 MiB refetched per dropped connection,** or the whole cut when less than that was
  sent. A fix that drains the channel before failing would have to reach channel 2, which the pipeline owns, or read
  the body without the pipeline's copy. Correctness is unaffected: the resume offset is always what the disk holds (rule 1), and `BUG-45`'s test now
  asserts exactly that.

## KMP-68 — A reload that fails keeps the last rows, in memory, for the Mac's H4

**Status:** in review · `:feature:home` only · ahead of `KMP-55`'s H4 (`DS-21`) · the PM's call, option (B), 2026-11-07

`DS-21`'s H4 keeps the source's rows after a failed reload and says how old they are (`I18N-14`'s `home_reload_failed`).
The view model followed TV 3m instead: a failed reload became `Failed` or `Offline`, and the rows were gone. That is now
`HomeStatus.Stale(failure, kept, loadedAt)`:
- `failure` is what the reload said, a `Failed` or an `Offline`, and the only thing TV draws.
- `kept` are the rows from before, in memory only, never written anywhere.
- `loadedAt` is when they loaded, from a clock the view model takes as `now` (a test passes its own).

**TV does not change.** `Stale.rows` is empty, as it was for the failure. `toUiState` maps `Stale` to its failure's state.
`HomeScreenTv` reads `status.shown` where it asked `is Offline`, so 3m draws exactly as before. TV's Reload is shown only
with rows on screen, so TV never reloads from `Stale`; the Mac can, and a reload that fails again keeps the same rows and
the same time.

Tests:
- `HomeViewModelTest`: a failed reload after one that loaded is `Stale` with the old rows and their time. Offline and then
  failed both keep the first time, and a reload that succeeds swaps the rows in.
- The existing failed-reload test now asserts `Stale`, whose `shown` is the old `Failed`.
- `HomeScreenTvDeviceTest` and the 18 Home shots are unchanged.

## KMP-50 — The TV library's sort: 5d's panel and 5a's sort button (queue `Q12`, second slice) · **measured 2026-11-07**

**Status:** in pr, on master after `KMP-49` (`#175`, train 1). Android TV only. The owner's ruling is `DEC-20`'s last row: **one sort
for every source, as the old screen keeps it, with a fallback for a source that cannot sort by it.**

**The seam.** `LibrarySort` is a criterion (`Updated`, `Title`, `Released`, `Rating`) and a direction: the old
screen's eight `ListSorting` entries, four criteria each both ways. A `LibrarySource` says which criteria it can sort by.
`LibrarySources` gains `sort()` and `sortBy()`, both with defaults, so a host that keeps none has the default.
`LibraryTitle` gains `releaseYear` and `ratingValue` (out of 100), both null by default. Desktop has no sources and
shows no sort button; its cards are in the same order as before, and its tests pass unchanged.

**The rules, each in one place.**

- **The default** is `LibrarySort.Default`, last updated, newest first. It is the order a list had before this, so
  `KMP-34`'s screenshots are unchanged.
- **The fallback** is `appliedTo`: the choice where the shown source can sort by it, else the default. The choice is
  kept, not overwritten, so a source that can sort by it shows it again. The button names the applied sort, never one
  the grid is not in.
- **A press** (`chooseSort`) applies at once and leaves the panel open (`DEC-17`, a single choice). The row already
  applied turns the other way round. A criterion the shown source keeps nothing for is refused.
- **Ties and gaps** (`LibraryShelf.sortedFor`): titles are put newest kept first before the sort, so a tie keeps that
  order. A title with no year, or no rating, goes last both ways round. A name sorts ignoring case. Continue watching is
  always newest watched first.

**On Android.**

- **The choice is the old screen's own key**, `"$currentAccount/library_sorting_mode"`, as the `ListSorting` ordinal,
  so both screens read the same choice. The key is read raw: `DataStoreHelper.librarySortingMode` answers
  `AlphabeticalA` where nothing is stored, and nothing stored is this screen's default. The old screen writes the key
  as soon as it shows a list, so **a user who has opened it gets the order it showed them**, A to Z unless they chose
  another. `Query` (no sort) and an ordinal this build does not know read as the default. All nine ordinals are
  pinned by a test.
- **What a service can sort by** is its read's `supportedListSorting`. Before the first read nothing is ruled out, and
  the panel's rows are read again with the sources after it. The local list sorts by everything but a rating,
  `LocalList`'s own set.
- **A service's title** carries the year of its `releaseDate`, and its rating out of 100 only where it shows one: 0 is
  AniList's and MAL's "not rated". **A local title** carries its bookmark's `year`, one line in `AndroidLibraryStore`.

### What the board draws that this does not, or draws differently

| Board | Drawn | Here | Why |
|---|---|---|---|
| 5d | Four rows, each a criterion over a second line, with the direction trailing | Four rows, each one of the app's eight labels, e.g. `Updated (New to Old)` | The eight are translated in every language already. One new string in all, for the reason below |
| 5d | `LOCAL SUPPORTS 3 OF 4 CRITERIA` | Not drawn | The disabled row and its reason say which |
| 5d | `NOT STORED BY LOCAL LISTS` | `Not kept by Local`, disabled and still a stop | `library_sort_not_kept`, with the source's name |
| 5a | `Sort` / `LAST UPDATED · NEWEST` | `Sort` / `Updated (New to Old)` | The applied sort, in the same label as its row |

### Measured, a wrong turn first

- **Focus after BACK goes to the provider button, not to Sort.** That is `BUG-36`: `SidePanelHost` restores a nested
  opener at its first stop. The source panel never showed it, because the provider button is the row's first stop.
  The device test pins it as it is, as the detail page's does, so the component's fix fails it and gets looked at.
  It is not patched here: `BUG-36` names 5d among the hosts the fix should be made once for.

### Each critical test shown to fail

Temporary changes, each reverted, on the JVM.

| Change | What went red |
|---|---|
| `appliedTo` returns the choice unchanged (no fallback) | `LibrarySortShelfTest`'s fallback test and `LibrarySortViewModelTest`'s fallback test |
| A press never flips (always the criterion's first direction) | `pressing the applied row again turns it the other way round` |

### Tested

- **JVM.** `LibrarySortShelfTest` 9 (each criterion both ways, gaps last, ties, a service's list, continue watching,
  the fallback), `LibrarySortViewModelTest` 8, and 5 added to `AndroidLibrarySourcesTest`: the nine ordinals, the
  mapping, the stored choice, what a service and the local list can sort by, a title's year and rating.
- **Device, `:feature:library`.** 5 added to `LibrarySourcesTvDeviceTest`: the button names the applied sort and the
  panel opens on it, a press applies at once and a second turns it round, a criterion the source keeps nothing for
  is a stop that chooses nothing, BACK closes with the choice applied (`BUG-36` pinned), and a screenshot.
- **Device, `:app`.** `theSortIsKeptUnderTheOldScreensKeyAsItsOrdinal`: the real key, read back by the old screen's
  own delegate.

**Screenshots:** `library-sort-panel` is new. `library-service-grid-focus-card` and `library-service-failed-focus-retry`
change only by the sort button in the right slot, which was empty. Each looked at.

### Not verified

- **The old phone screen reading a choice made here**, on a device. The key and the ordinal are tested; the old
  screen's own reading of them was not run.
- **A real service's `supportedListSorting`.** Every service read in a test is a fake.
- **A native speaker reading the new string** in any language.

## KMP-51 — The TV library when a service cannot be read (5b), offline, and `BUG-33` (queue `Q12`, third slice) · **measured 2026-11-07**

**Status:** in pr, stacked on `KMP-50` (`#195`) with `#196` (`I18N-15`) merged in for its words. Android TV only.

**What the screen does now.**

| The shown tracking service | Before | Now |
|---|---|---|
| Fails, after a good read in this session | the failure page | **that read stays up, marked:** every card's top-left badge is `Cached`, the provider button has a failed dot and `Unreachable · cached 2h ago`, and `Retry sync` is the next stop to its right (5b) |
| Fails, with nothing held | the failure page | the same |
| The device is offline, a read held | it was asked, and failed | **not asked:** that read, with `Offline · cached 2h ago` |
| The device is offline, nothing held | it was asked, and failed | **not asked:** `You are offline` / `AniList needs a connection`, with Retry |
| The device comes back online | — | **read again once,** after the connection has held for 2 s, where what it shows is held or offline |

The local list never depends on the network: offline it reads as before. Connectivity is `:core:connectivity`'s
`ConnectivityFlow`, the one the downloads screens use. `UNKNOWN` is never offline.

**`BUG-33`, fixed.** After a failed read, choosing another tab or typing a filter kept the failure off the screen and said
the list was empty. Both now keep the failure. This reaches desktop too, as `BUG-33` says it should.

**The rules, each in one place.**

- **What is held** (`LibraryViewModel.held`): each service's last good read **in this session, in memory only**. A
  service's lists are the user's account on someone else's site, and nothing here writes them to disk. After the
  process dies nothing is held, and a failure is the failure page, as before.
- **The age** is the source's `syncedAt`, recorded when that read was made (`KMP-49`), so `2h` is a fact. Without a
  clock the button keeps its usual line beside the failed dot.
- **Coming back** (`reconnected`): only the shown service, only where it shows a held read or the offline page, and
  only once the connection has held for `RECONNECT_SETTLE_MS`. The collector takes the latest status, so a network that
  flaps cancels the wait each time and makes one read when it settles, not one per flap.
- **The failure is the words and the dot's shape, not a colour** (`DEC-17`): the meta line stays muted, as the board's
  red would break that ruling.

### The words

`KMP-51` adds no keys. It uses `#196`'s, agreed with `I18N-15` so each sentence has one owner and the age sits where
each language puts it: `library_sync_unreachable` and `library_sync_offline` ("… · cached %s ago"),
`library_sync_cached` ("Cached") and `library_sync_retry` ("Retry sync"). The offline page reuses Search's
`You are offline` and Home's `%1$s needs a connection`. The age reuses the formats `KMP-49`'s `Synced %s ago` uses.

### What the board draws that this does not, or draws differently

| Board | Drawn | Here | Why |
|---|---|---|---|
| 5b | The meta line in red | Muted, with the failed dot | `DEC-17`: a failure is a hollow neutral marker, never a colour |
| 5b | `CACHED` on every card | `Cached` in the type badge's place | The type is not the fact that matters while the read is old |
| 5b | `UNREACHABLE · CACHED 2H` | `Unreachable · cached 2h ago` | `#196`'s sentence, one per reason |
| 5b | The field narrowed to make room | The field takes what is left | It is weighted already, so Retry's width comes out of it |

### Measured, a wrong turn first

- **The first read did not see a starting OFFLINE.** It ran before the connectivity collector had stored the status,
  so the service was asked. It reads the flow's current value now, which is never stale while the ViewModel collects.
  Three JVM tests went red on it, and green after.

### Each critical test shown to fail

Temporary changes, each reverted, on the JVM.

| Change | What went red |
|---|---|
| A failed read never shows the held one | `a service that fails after a good read shows that read, marked unreachable` |
| No settle before reading again | `back online, the shown service is read once, after the connection has held` and `a network that flaps is one read when it settles, not one per flap` |

### Tested

- **JVM.** `LibraryOfflineViewModelTest` 9: a failure after a good read keeps it marked, and a good read clears it; a
  failure with nothing held; offline with and without a held read, the service not asked; `UNKNOWN` is online; one read
  after the connection holds, none before; **a flapping network is one read**; a live service or the local list is not
  read again; and `BUG-33` on a tab and a filter.
- **Device, `:feature:library`.** 3 added to `LibrarySourcesTvDeviceTest`: 5b's held read with its badges, Retry as the
  button's next stop, and Retry clearing it; offline with nothing held, the service not asked; and a screenshot.

**Screenshots:** `library-service-unreachable-focus-retry` is new, looked at.

### Not verified

- **A real network dropping and coming back** on a device. The flow is the downloads screens' own; this screen's use
  of it is tested with a fake.
- **A held read across a configuration change**: it lives with the ViewModel, so it should survive one, but no test
  rotates.


## KMP-64 — The TV player HUD, artboard 7d (queue `Q24`) · **measured 2026-11-07**

**Status:** in pr. Board 7d of `design/TV Detail Player.dc.html` on `Television_720p`, API 36, over the existing player.

### What it adds

- **New `:feature:player`** (commonMain): `PlayerHudState` (labels and numbers only), `PlayerHudController` (7d's visibility and key rules), `PlayerHudTv` (the screen). In the iOS canary.
- **`:app` hosts it over `FullScreenPlayer` on TV** (`TvPlayerHud`, one line in `onBindingCreated`), behind `TvPlayerHudSwitch.useComposeHud` (on; off is the old controller, tested). The Media3 controller is off while it is up; the video, subtitles and pre-roll overlay are the old player's.
- **Every 7d readout from what the player already knows** (measured before building): title and `S1:E4 · Powder · 1080p · AVC · English 5.1`, `DOWNLOADED`, the clock, elapsed / remaining / total, played and **buffered** bands in one track, skip stamps as ticks, `Skip opening`, transport, `Up next · S1:E5 …` (from the generator's episode list), and SOURCE, SUBTITLES, AUDIO, SPEED as value over label.
- **States:** shown; hidden with the scrub readout (`+00:30 → 42:43`) over its rule; buffering (badge, auto-hide suspended); a playback error banner whose action is the SOURCE selector; offline over a downloaded copy.
- **Keys** keep the old player's settings and feel: auto-hide 3000 ms while playing; hidden, Left/Right scrub by `android_tv_interface_off_seek_key` (10 s default) without revealing; Up/Down reveal; Centre toggles and reveals, or takes an offered skip; shown, the seek cell moves by `android_tv_interface_on_seek_key`. The activity's key listener asks the HUD first; keys it does not use, other than the D-pad and OK, still reach the old handler (MENU, keyboard shortcuts).
- **The one published addition:** `CS3IPlayer.getBufferedPosition()`, on the final class only, not on `IPlayer` (app-10's ruling: no new member for implementers of the published interface).
- 8 new keys in all 62 locales, none a plural.

### Security

`TvHudFacts.toHudState` is the boundary. The link goes in for its display name and quality; its URL, headers and referer never come out. A downloaded file comes in as a flag only, so its path never enters. Provider text loses control characters, and a name that reads as an address (`://`, `/…`, `content:`, `file:`) is dropped. The HUD code logs nothing.

### Focus

| Press | Goes to |
|---|---|
| Reveal | Play/pause |
| Down from the chrome row or `Skip` | The seek cell |
| Down from the seek cell | Play/pause |
| Along the transport | Back, forward, previous, next; Right past next and Left past play/pause stay |
| Down from the transport | SOURCE |
| Up from AUDIO or SPEED | The seek cell, which spans them |

### Reduced from the board

- **Buffering keeps the play/pause glyph:** 7d draws an indeterminate ring in the cell, and the pack has none; drawing one is a design-system component.
- **The transport's four cells** are seek back, seek forward, previous and next episode, the pack's glyphs; 7d draws them unlabelled.
- **One dialog for SOURCE and SUBTITLES,** the old sources-and-subtitles picker; AUDIO opens the tracks picker and SPEED the speed dialog. 7e's panels are `Q25`.
- **`Skip opening`, not `Skip intro`:** the label is the skip stamp's own.
- **The skip ticks are skip stamps:** the player has no chapters.
- **Ticks are 2dp** (7d's 4px), a stroke weight.
- **Empty (no usable link)** is the old player's toast and exit, not 7d's full-screen state; it happens before the HUD exists.
- **Previous episode played the next one,** an existing bug: fixed separately in `BUG-42` (#182), so this cell works once that merges.

### Tests

- `HudTimeTest` (common, 4) and `PlayerHudControllerTest` (JVM, 7): the board's times; auto-hide re-armed by keys, held while paused or buffering; a hidden scrub burst and its readout clearing after 1500 ms; scrubs kept inside the title; Up/Down/Centre while hidden; Centre taking an offered skip.
- `TvHudFactsTest` (`:app` JVM, 5): the board's lines from the board's facts; a link whose URL, referer and headers carry markers leaves none in the state; names that read as addresses are dropped; control characters go; the offline banner needs a download.
- `PlayerHudTvDeviceTest` (device, 14, no video so no sound): every readout; reveal focus; the transport and its ends; Up to the seek cell; the seek cell's Left/Right; each control's action; a disabled previous cell; hidden draws only the skip and readout; screenshots `player-hud-shown`, `-hidden-scrub`, `-buffering`, `-failed`, `-offline`, `-nothing-known`, each looked at before it was kept.
- `TvPlayerHudNavigationTest` (`:app`, the real path, 4): a downloaded file played through `GeneratorPlayer` over `DownloadFileGenerator`. The file is `hud-silent.mp4` (28 KB, digitally silent, -91 dB, made with `ffmpeg`) and the media volume is 0 for the test. The HUD draws and the old controller is off; the file's path, which carries a marker, is nowhere on screen; it hides while playing, a hidden Right shows the readout without revealing, and Up reveals with focus on Pause; with the switch off the old controller is back.

**The device run caught three things.** `IconOnlyButton` shows its label on focus, so `Pause` sat over SOURCE: the cells are `IconCell`, icon-only with a content description, as the design system rules for 7d's transport. Right from the last cell and Down from the seek cell went where geometry led, not where 7d says. And the real-path scrub test looked for a readout after `DpadDriver.press`, which waits on the accessibility tree longer than the readout lives.

### Found, not changed

```
Found: GeneratorPlayer.playerError logs the stream URL, headers and referer (Log.e)
Suggest: drop them from the line
Why: headers can be credentials; the owner's rule is no URLs, headers or paths in logs. Taken by the security sweep, BUG-43.
```

### Not verified

`TalkBack`, a real remote, a real provider's stream (only the local file), the episodes overlay on MENU with the HUD up, chapters (the player has none), and the least-confident translations: `ti`, `am`, `ckb`, `my`, `or`, `as`, `mt`, `apc`.

### Published API

Additions only. `app/api/classes-jar.api` +70 / −0: 8 `R$string` fields, `CS3IPlayer.getBufferedPosition`, `FullScreenPlayer`'s `playerError` override and failure flag, `GeneratorPlayer`'s facts override, and the HUD host classes.

## KMP-52 — The Mac download service and its data · **measured 2026-11-06**

`:media:download` runs on the JVM in `:desktopApp`, in package `com.lagradost.cloudstream4.downloads`, with a queue, persistence and the two
download sources. There is no UI: that is `KMP-54`, which wires `source()` and `series(id)` into its screen. No caller enqueues anything yet.

**The contract with `KMP-54`.**
- `DesktopDownloadsSource : DownloadsSource`. `storage` is the volume holding the download folder, non-null from the first emission.
- `DesktopDownloadSeriesSource : DownloadSeriesSource`.
- The host `DesktopDownloads` has `delete(Set<DownloadTitleId>)`, `deleteEpisodes(Set<DownloadEpisodeId>)`, and `fileOf` for a title and
  for an episode. `fileOf` is null until done and on disk; for a series it gives the folder.
- Ids are stable across restarts.
- `contents` emits at most once every 250 ms while running, and not at all when idle.
- `DownloadSeriesContents.freeBytes` (added by `KMP-54`) is filled from the download root's volume: the titles page's own
  `total - used`, so the two pages agree. It is null only while the disk is unread.
- There are no changes to `:feature:downloads`, except one KDoc line on `DownloadTitleState.Paused`. The PM cleared it: it now says that only Android reads a failure as Paused after a restart, until `KMP-60`/`KMP-62`.

**Shape.**
- `enqueue(DesktopDownloadRequest)` accepts VIDEO links only; HLS, DASH and torrents are refused with a typed `EnqueueRefusal`.
  `DesktopDownloadLink.of(RemoteLink)` adapts the sandbox's link. A request names its source (`DownloadSourceRef`: provider and episode
  data); its link is optional and only ever in memory.
- At most 3 downloads run at once, first in, first out. Pause frees the slot (on Android a paused download keeps it).
- Cancel discards the parts and the record.
- Enqueuing a paused or failed episode again takes the new link, because signed URLs expire. A blank source is `EnqueueRefusal.NoSource`.
- The engine writes straight into `~/Downloads/CloudStream/<title>/`. On the JVM its atomic move works, so there is no staging (unlike `KMP-59`).
- File names are `S01E02 Name.mp4`, or the title for a film, with ` (2)` on a clash. `DownloadPaths` restates the engine's internal name
  rules more strictly, so the result is always one safe path segment. A title or episode name that reads as an address is dropped
  before it is stored or names anything (`KMP-71`).
- The HTTP client is `HttpClientFactory(posterHttpClient())`. It verifies TLS and checks the resolved and connected addresses, which also
  closes the engine's DNS-rebinding gap on the Mac.
- Delete removes only empty folders, and refuses a folder whose real path is not directly inside the download root.
- After a restart nothing starts by itself. An unfinished episode comes back Paused, with its bytes measured from the engine's part files.
  A finished episode whose file is gone is dropped.
- Logging: the exception class and a fixed text only.

**Persisted: `~/.cloudstream4/downloads.json`, format 3, `0600`, written atomically. No link (owner's ruling, 2026-11-06).**
- *What it holds:*
  - ids, metadata, folder and file name, state, byte counts and queue order;
  - the episode's source: `provider` (the extension's name) and `episodeData`, the string the player passes to
    `SandboxedProvider.links(data)`. **Unfinished episodes only:** both are cleared when an episode reaches Done, and a Done record
    reads back without them (any other record without a source is dropped);
  - a failure's typed reason: the `DownloadFailure` variant name plus its numbers (status code, byte counts), never text. So a
    Failed episode stays Failed with its reason after a restart. `StoredFailure`'s `when` is exhaustive, so a new variant will not
    compile until it is named.
- *Not a link, but not "no URL at all":* `episodeData` is the same episode reference that watch history and bookmarks keep. It is often
  an episode-page URL on the provider's site, and sometimes JSON with URLs inside.
- *What it never holds:* the media link's `url`, `headers` or `referer`. Those stay in memory for the current process only.
- *Resume and retry:* a resume after a restart, and every retry, asks the extension again through `LinkResolver`. In production that is
  `SandboxLinkResolver`, which works the player's way: provider by name, then `links(data)`, then `isPublic`, then quality rank. The first
  VIDEO link is used.
  - No usable link: `NoUsableLink`.
  - The resolver throws or takes more than 60 s: `SourceFailed`. The call runs under `runInterruptible`, so a resolver that blocks
    a thread is interrupted too. `SandboxedProvider.links` already suspends and sends `OP_CANCEL` to the plugin host when cancelled.
  - Either way the part files stay.
- *Resume after a pause* in the same process reuses the link in memory.
- *Reading it back:* everything is validated again (path segments, enum names), a bad record is dropped, and a format-1 file is refused.
- *What stays out:* no `toString` includes the source or the link.

**Tests** (local, none CI). Counted from the XML:
- `DesktopDownloadsTest`: 23/23 (the 22 before it ran 10 times in a row), against a JDK `HttpServer` with Range and ETag support; every completion is compared byte for byte.
  It covers:
  - done, and `fileOf` only at done
  - at most 3 running
  - pause and resume continuing from disk, with a ranged request after 0
  - a restart with a new host over the same files: same ids, Paused, then resumed and byte-identical
  - cancel of a waiting download, and of a running one (it stops, and no part, file or record is left); a cancel of a finished one
    is refused and keeps its file and record; delete of a title and of an episode
  - the series page's `freeBytes` equals the titles page's free figure
  - closing the host while a resolve is in flight leaves `downloads.json` byte-identical, and a new host reads the episode Paused
  - a file deleted outside the app, noticed on `refresh`
  - the emission rate: 2.64–2.68/s while running, every gap ≥ 240 ms, nothing over 1.25 s idle
  - the file's permissions are `rw-------`
  - no link text in logs, stdout, stderr or any public `toString`
  - no link text on disk: marker strings in the url, a header, the user agent and the referer never appear in the raw bytes of
    `downloads.json`, any other file in its folder, or any file under the download root (the engine's state and parts). This is checked
    after each of Queued, Running, Running past the engine's state rewrite, Paused, Done and Failed.
  - after a restart, the resolver is asked once with the stored provider and data; a fresh URL resumes from the parts, byte-identical
  - a resolver with nothing, only HLS, or an exception: `NoUsableLink` or `SourceFailed`, with the parts unchanged
  - a retry asks again; a resume after a pause does not
  - a resolver that never answers, and one that blocks its thread, both fail `SourceFailed` within the (injected) timeout with
    the parts kept; the blocked thread is released
  - a failed `NoUsableLink` survives a restart as Failed, then a retry completes; every failure variant round-trips
  - a finished episode leaves no provider or data marker on disk, is still Done with its file after a restart, and is gone after delete
- **A real run with an official extension** (InternetArchive, in a throwaway `user.home`; nothing played, and no link or data
  printed). The "big buck bunny" episode gave 4 VIDEO links. The download was enqueued with its source only. It fetched 5,301,880
  bytes in 5 parts, then the host closed. A new host listed it Paused. Resume asked the extension once, and the fresh link resumed
  **from the parts**: every one of the 8 fetches started at its part's planned start plus its bytes on disk, and none at 0. The ETag
  and Last-Modified matched across the two runs, and both went through the same hosts. It was stopped at 15.4 MB of 645 MB.
  The live links call answered in 4 ms, so the timeout could not be seen cutting it there.
- Without the 250 ms throttle and with a fourth slot, 3 of these fail.
- Full `:desktopApp:jvmTest`: 417/0. `:desktopApp:createDistributable` builds; the app was not launched.

**Not verified.**
- A whole download from a real host to the end, and a CDN that changes its ETag between resolves.
- A full disk.
- An unreadable downloads file, where `contents` throws and `enqueue` returns `StoreUnreadable`.
- A process kill, as opposed to `close()`.

## KMP-71 — Mac downloads: a title or episode name that is an address never reaches disk · **built 2026-11-08**, waits on review

A follow-up to `KMP-52`. A plugin's `titleName` and `episodeName` became the folder and file names and were written to
`downloads.json`. A plugin can put a signed link there, so it could end up on disk as a file name.

- **The rule** is the TV HUD's title rule as `KMP-66` tightens it, restated in `DownloadPaths.looksLikeAddress`:
  - a scheme anywhere, or a path, `content:` or `file:` at the start;
  - a host at the start followed by a port, path, query or fragment.
  A dotted title on its own (`Dr.Harbor`, `Mr.Lantern`, `Vol.II`) stays. Only a source's name gets the HUD's stricter rule (a bare host,
  `sig=`, a backslash path), and a title never does.
- **Where it applies.** It goes in `DownloadPaths.displayText`, which every title and episode name already passes through:
  - on enqueue, before the name is stored or names a folder or file;
  - on reading `downloads.json` back, so a hand-edited file is checked too (not tested with an edited file).
- **Fallbacks:**
  - A title that is an address becomes `Download`, both as the row's name and as the folder. A blank title stays blank, as before.
  - An episode name that is an address is dropped, so the file is `S01E05.mp4` and the list shows the episode's code.
- **Not handled:** a record written before this change keeps its folder and file names. No caller enqueues anything yet (that is
  `KMP-54`), so no such record exists.

**Tests** (local, none CI). Counted from the XML:
- `DownloadPathsTest`, new, 4/4. The address samples are copied from `TvHudFactsTest` (`KMP-64`); the dotted titles are `KMP-66`'s.
- `DesktopDownloadsTest`, 24/24. The new test enqueues a series episode whose title is a link and whose name is a link carrying a
  token, and runs it to Done. The file is `Download/S01E05.mp4`. Neither marker appears in `downloads.json`, in any other file in its
  folder, or in any file's bytes or any path name under the download root, either then or after a restart.
- With `looksLikeAddress` forced to false, 3 of these fail: 2 in `DownloadPathsTest` and the new one in `DesktopDownloadsTest`.

## KMP-81 — The title rule finds an address anywhere in a name, in any script · **in review**, follow-up of `KMP-71`

Review of #233 found two gaps in the title rule that `KMP-66` gave the HUD and `KMP-71` restated for Mac downloads:
- **An address inside a name** passed: the host check was anchored at the start, so `Ep 5 cdn.host.to/v/x?token=…`
  was kept.
- **A host in another script** passed: `\w` and `[a-z]` are ASCII, so `видео.рф/x` was kept. So was the same address
  written in full-width characters.

**The rule now**, read after NFKC so full-width characters count as their ASCII forms:
- **Unchanged:** a scheme (`://`) anywhere; a path, `content:` or `file:` at the start.
- **New, at the start of any word** (after a space or an opening bracket or quote):
  - a host with a port, path, query or fragment after it;
  - `content:/` or `file:/`;
  - a path of two parts or more (`/storage/emulated/…`).
- **Hosts:** labels may be in any script. The dots are `.`, `。` and `｡`. The top-level domain is two letters or more in
  any script, or punycode.
- **Fewer false positives:** a `?` or `#` counts only with a token after it, so `Is it Dr.Harbor?` and `Who is Mr.Lantern?!`
  stay. `Harbor / North`, `Low Tide 1/2` and `Case File: 7` stay too.

**Copies.**
- **Mac downloads** (`DownloadPaths.looksLikeAddress`): done here. `DownloadPathsTest` passes 6/6. Control: with the old
  rule, the new address test fails.
- **TV HUD** (`TvHudFacts.hudText`): the same rule, restated, now that #205 is on master (train 9). A source's or a
  subtitle's name goes through it too, since `hudLabel` reads `hudText` first. `hudLabel`'s own check for a host with
  nothing after it (`stream.example.to`) also finds one at the start of any word and in any script now, so
  `Mirror видео.рф` falls back to the quality. A name such as `Mirror 5.1` keeps its label.
- **Tests:** `TvHudFactsTest` uses the same two lists as `DownloadPathsTest`, so the rule is checked on the same text
  in both copies. Each list's titles are made up (`BUG-70`).

**Proof**, all local (CI is off):
- Control, with master's `TvHudFacts`: `TvHudFactsTest` 11 with 2 failures. All 11 addresses got through in a title
  and an episode name, and `Mirror stream.example.to` kept its label. The titles that must stay passed there too, as a
  guard should.
- With the new rule, three runs in a row: `TvHudFactsTest` 11/0, `TvPanelRowsTest` 3/0, `DownloadPathsTest` 6/0 and
  `DesktopDownloadsTest` 26/0.

## KMP-55 — The Mac's Home, built from `DS-21`

**Status:** in review · stacked on `#191` (`I18N-14`) · H4 waits on `KMP-68` (`#197`)

`HomeScreenDesktop` draws what the device keeps above the source's rows:
- **The hero** (H1) is a card in the page, with More info.
- **Continue watching** (3c) is one line of 16:9 cards, each with its resume rule in `live`.
- **Bookmarks** (3e, 3f) are the status chips, with the chosen one outlined, and its titles. With no status chosen, the
  prompt to choose one.

These are local, so they stay through every state of the source's. The source's state draws where its rows would be:
loading, a failure, offline, or None (H5, in `home_none_*` with `home_choose_source`). With none of them on screen, the
page is exactly what it was, and every coordinate in the existing tests still holds.

The bar:
- the source, or None;
- the offline pill (H4), `downloads_offline_pill`, beside the source;
- Reload and Random (3j) as icon buttons, while the source's rows are on screen.

The source panel (H3):
- `home_source_picker_title` and its line;
- None first, with `home_source_none_line`;
- each source with its section count (`home_source_sections`);
- a check on the chosen one;
- Done.

The Mac's library store now feeds Home, as Android's does: `DesktopHomeHistory` (resumable positions, newest first) and
`DesktopHomeBookmarks` (a bucket is a status; Favourites is not one).

**Measured** in real renders (`HomeScreenTest`'s `15`-`21`):
- At 800×520 offline (`21-kept-minimum-offline`), the pill fits beside the source button with room to spare.
- At 1280 the hero is 320 tall; under a 720 content width it is the compact 240 one, with the title a size down and no
  synopsis (H6).

Tests:
- `DesktopHomeLibraryTest` (2).
- `HomeScreenTest`'s picker case now also chooses None, and its coordinates are measured from `18-kept-picker`.
- `:desktopApp` 397, `:feature:home` 68, 0 failures.

**DECIDED** (reported to the PM):
- **The hero has More info and no Play.** The shared contract has no "play from home"; TV's hero opens the title too.
  Options: (A) More info only, (B) Play that opens the title, (C) a new play action. Chose A, because a button named Play
  that opens a page says something untrue.
- **The card context menu (H2) is deferred.** "Add to bookmarks" has no action in the contract, and the menu API is
  desktop-only. It wants its own task, with the action added to the shared model. Hover lift is in.
- **The bookmark filter is kept while the window is open.** There is no desktop preference store yet.
- **H4's kept rows and their age** wait on `KMP-68`. Until then, a failed reload draws as 3m, under the device's rails.
  Built since in `KMP-84`.

## KMP-84 — The Mac's H4: a failed reload keeps its rows, says how old they are, and Reload and Random act on them

**Status:** in review · `:feature:home` and `:desktopApp` tests · over `KMP-68`'s `HomeStatus.Stale` · `DS-21` H4

`KMP-68` kept a failed reload's rows in the view model, and the Mac still drew 3m. Now `HomeScreenDesktop` draws H4:
- **A banner at the top of the page**, the board's: the Warning glyph, `home_reload_failed` with the source and the rows'
  age, and Try again (Reload). The age is `4m`, `2h 5m` or `1d 2h 5m`, as `I18N-14` decided, counted from `loadedAt`
  and read again each minute while the window is open.
- **The kept rows under it**, after what the device keeps (Continue watching, Bookmarks). The hero goes with the
  reload, as the view model already did.
- **The offline pill** reads `status.shown`, so it shows for a reload that failed offline. A reload that failed some
  other way has no pill.
- **Reload and Random** show while there are kept rows. `HomeStatus.keptRows` (new, in `HomeMvi.kt`) is the rows on
  screen or a `Stale`'s kept ones. Random now picks from it, so it is not a dead control on H4. TV does not change: its
  Random is only beside rows, and a `Stale`'s `rows` is still empty.

**DECIDED** (reported to the PM):
- **A kept row has no Load more.** Its pages went with the reload, and `showMore` reads the rows on screen. A button
  there would do nothing. Try again is the way on.
- **The banner has no failure detail.** The board draws the sentence alone, and the pill already says when it is the
  connection.
- **The age formatter is a copy of the library's** (`LibrarySourcesTv.kt`), which is private to `:feature:library`.
  Sharing it means a change there, which this PR does not make.

**Tests:**
- `HomeAgeTest` (new, 2): minutes, hours and minutes, days; a clock behind the record reads as now.
- `HomeViewModelTest`: Random after a failed reload opens one of the kept titles.
- `HomeScreenTest`:
  - a failed reload's rows stay, and Try again, Reload, Random and a kept card each send their action, with nothing
    where a Load more would be;
  - the control for that: the same rows on screen do have their Load more at that point;
  - the offline pill shows for `Stale(Offline)` and not for `Stale(Failed)`, sampled from the render;
  - renders `24-stale-offline`, `25-stale-failed-nothing-kept-locally`, `26-stale-minimum-offline`, `27-stale-alone`.
  Their coordinates were measured from those renders.
- **Proved by mutation**, each failing only its own test:
  - Random reading `rows` again: the Random test never gets its title (a one-minute timeout);
  - the pill on `is Offline` again: the pill's pixel reads the background;
  - kept rows given Load more: the click there sends `RowMoreRequested`.

## KMP-77 — The Mac's sidebar stays beside a title's page

**Status:** in review · `:desktopApp` only · `DS-21` E1

`DS-21`'s Detail boards (E1 to E6) keep the sidebar beside a title's page, with the tab it was opened from chosen. The
built window dropped it for anything pushed. Now:
- **A title's page is inside the shell** (`TopLevelScreen`), with the tab at the bottom of the stack chosen: Home,
  Search or Library, whichever the title was opened from.
- **A row there, or ⌘1 to ⌘3, leaves the title for that tab**, as a sidebar does in Finder or Music: what was pushed
  goes, and each title's view model goes with it (`DesktopDetailViewModels.forget`), as Back does.
- **The player and the extension review keep the whole window.** The keys are off there, as the sidebar is.

**With `KMP-75` (`#229`, which this is stacked on):** Downloads and a series' downloads page are in the same scheme.
Downloads is a sidebar row with ⌘4, and a series' page keeps the sidebar with Downloads chosen; ⌘1 to ⌘4 work wherever
the sidebar is. A test (`LibraryScreenTest`) opens a title from Library and presses ⌘4: it lands on Downloads with
nothing under it, and the title's view model is gone.

**Tests** (`HomeScreenTest`, through `DesktopApp`): a title opened from Home keeps the sidebar with Home chosen (its
row's fill, sampled from the render); Search's row leaves the title for Search with nothing under it; ⌘3 leaves a title
for Library the same way. With the title's page outside the shell again, the test fails at the chosen-row check
("Home is chosen under the title"): there is no sidebar to sample.
After each way out, the title's view model is gone (`DesktopDetailViewModels.isLive`, internal, for this); with
`root`'s `forget` removed, the test fails there.

**Not verified:** that the keys are off over the player. `FakeHome`'s titles cannot be opened (no provider answers them),
so the test cannot reach a player to check that `atTop` is false there and ⌘3 leaves the screen alone. The rule is one
line (`Screen.hasSidebar`), read, not run.

**Follow-up:** the window's `rememberSaveableStateHolder` keeps every key it has seen: nothing calls `removeState` for a
screen that was popped, by `root` or by Back, so a title's scroll position outlives the title. Small, and the same for
both ways out; it wants its own task.

## KMP-87 — The Mac player's HUD, `DS-23` M1 to M3, and its keyboard map

**Status:** in review · `:desktopApp` · `DS-23` (merged) · over the built `PlayerScreen`

- **M1, the top band:** Back, the title, then the episode, quieter: `S1:E4 · The Lighthouse`, from the episode playing
  (`episodeMeta`, the app's own `S1:E4` in the translated letters, then its name). `DetailHost` works it out from the
  episode it asked for and the player carries it (`Screen.Player.meta`).
  - The source and its quality left the band for the Source selector, which already says `Source One · 1080p`.
  - Where there is no selector (a host that cannot switch), they join the band's line instead (`hudMeta`), so the band
    never says less than it did.
- **M2, showing and hiding:** the pointer goes with the HUD and comes back with it (`playerPointer`).
  - Only a pointer that really moved counts. Compose re-sends hover when something moves under a still pointer, as Skip
    does when the HUD leaves, and counting that brought the HUD straight back: a flicker, found by the test.
  - A pointer coming into the window counts as well: it arrives as Enter, before any Move.
  - The wait is injected (`HudIdle`), three seconds in the app, so a test says when it has passed instead of sleeping.
- **M3:**
  - Resting on the timeline shows the time under the pointer, above the rule, until it leaves (`hoverTime`).
  - **Skip** draws from the shared `HudSkip` (`:feature:player`, now a dependency of `:desktopApp`), outside the HUD, so it
    stays while the HUD is hidden. The Mac has no skip stamps yet (`KMP-86`), so the host passes none and nothing is drawn.
- **The keyboard map** is the built one, unchanged: Space, ← →, ↑ ↓, M, C, A, R, S, F, and Escape closing a list, then
  full screen, then the player.

**DECIDED:**
- The band's code is `S1:E4`, the app's own form everywhere else, not the board's `S1 E4`.
- `:desktopApp` depends on `:feature:player` for `HudSkip`, so the Mac and TV share the type `KMP-86` will fill. Its
  runtime classpath gains that one project and nothing else (the dependency report, against master): no Android artifact,
  no new library.

**Tests** (`PlayerScreenTest`, 68 in all, all run):
- `episodeMeta` and `hudMeta`, every case.
- M1: the band draws the line beside the title, and nothing there without one.
- M2: the HUD leaves when the wait passes while playing, and a move brings it back and waits again; paused, there is
  nothing to wait out. Driven by a hand-run `HudIdle`: no sleep, no tolerance.
- M3: the time is drawn above the pointer and goes when it leaves, and resting seeks nothing; `hoverTime` at the middle,
  past the end, and over an hour. Skip answers with the HUD up and hidden, and nothing answers where it would be without
  one.
- The keyboard map: every key in one window, each doing what the Playback menu says.
- Proved by mutation: counting every hover event fails the Skip test (the HUD never goes); without Enter on the rule,
  the hover test fails. **Not proved:** Skip's floor at the window's margin while the HUD's inset grows back from 0. The
  press lands before that frame, so no test sees it; read, not run.
- **Changed existing test code:** the harness takes `meta`, `skip` and the idle; the fixtures `VidSrc`, `VidSrc Pro` and
  `Filemoon` are now `Source One` and `Source Two`. Each assertion is the same as before with the name swapped.

## KMP-86 — Skip stamps for the Mac player · **open**, follow-up of `KMP-87`

`DS-23` M3 draws Skip Intro while the stream has something to skip. The Mac player draws it from `HudSkip` (`KMP-87`),
as TV does, but nothing on the Mac fills it: TV's stamps come from its host. Find where the stamps come from for a
title, read them for the episode playing, and pass the current one to `PlayerContent.skip`, with `onSkip` seeking past it.

## KMP-101 — Mac Search and Downloads polish: S3's banner, S2's second line, D5's pill, outlined state actions · **in review**

**Status:** in review · `:desktopApp`, `:core:designsystem`, `:feature:{search,downloads,home}`, `:core:strings` · a design
audit against boards S3, S2, D5, D4 and S6 · stacked on `KMP-98`, which changes both pages' bars · its key is `I18N-22`

- **S3, a partial failure, is one band across the page** (`DesktopFailureBanner`): a warning glyph, what failed, the
  mono detail, and an outlined **Retry N sources** at the far end, which counts the sources it asks again. The TV's
  `FailureBanner` pill stays as it is: it is sized to ride a chip row, because a TV's vertical budget decides whether a
  rail reaches the focus band.
- **S2, the card's second line.** `DesktopSearchSource` filled `typeLabel` and `qualityLabel` with null, because a
  Compose resource has no synchronous accessor. It now resolves them with the suspend `getString`, once for each distinct
  value in a search, from the keys Android's search uses (`DesktopResultLabels`). A card shows `Movie · 4K`, a type
  alone, or no second line.
- **D5, offline on Downloads and a series' downloads, is Home's pill**: the wifi-off glyph and the same words, where it
  drew a failed status dot. Home's private `OfflinePill` moved to `:core:designsystem` as `DesktopOfflinePill`, so the
  three pages draw one component. Home's pixels do not change.
- **D4 and S6, an empty or failed page's action is outlined**: a hairline and no fill, `raised` under the pointer.
  `DesktopButtonTone.Outlined` is new, and `DesktopStateView` is `StateView` with that button. `StateView` gained an
  `actionButton` slot whose default is the TV's button, so TV and Android draw what they drew.

The string is `search_state_retry_sources`, a plural, in all 62 directories (`I18N-22`).

## KMP-98 — The Mac window's chrome: the title bar, Settings in the sidebar, one page bar · **in review**

**Status:** in review · `:desktopApp`, `:core:designsystem`, `:feature:{home,search,library,detail,downloads}` · a
design audit against boards D1 (chrome and sidebar), S1, H1 and D1 (the bar).

- **The title bar is transparent over the content** wherever the sidebar is, as the Settings window has it: the sidebar
  runs the window's full height with the traffic lights in it, and its rows start under a 52 band. Over the player and
  the extension review the title bar is plain, since neither has a column for the lights. `titleBarOverContent` sets
  `apple.awt.fullWindowContent`, `transparentTitleBar` and `windowTitleVisible`; the host switches it per screen.
- **Settings ⌘,** is the sidebar's last row, below whatever the foot holds (the extensions notice now, the profile
  button after `KMP-95`). It opens the Settings window, as the app menu's Settings… does. The key is shown on a Mac only;
  elsewhere nothing binds it.
- **One page bar, `DesktopPageBar`** (`:core:designsystem`): 52 tall, the hairline along its bottom edge, on Home,
  Search, Library, a title's page, Downloads and a series' downloads. Before, Home's bar was 68 with no hairline,
  Search had a padded field and no bar, and both Downloads pages drew a box round theirs. Library's and the title
  page's were already 52 with a hairline, which now sits inside the band, so the band stays on the 4 dp step.
- Home's rows did not move: the 16 the old bar held below its controls is now the list's top padding. Search's chips
  and results moved up 20 (the old bar had 24 round the field).

### Measured on the way

- **A control in the title band takes its click.** Asked AppKit, on Temurin 17 with JNA, which view a point
  hit-tests to: with the three properties set, every point of the top 28 is the content's `AWTView`; without them the
  band is `NSTitlebarView`. `AWTView` answers yes to `mouseDownCanMoveWindow`. Robot could not click here (no
  Accessibility permission, and the control window got no clicks either), so a press and a drag in the real window
  are still to be tried by hand.
- **Switching it with the window open applies at once:** the frame's top inset went 28 to 0 and back, and the band's
  hit-test followed.
- **The lights sit in the standard 28 band**, not centred on the 52 bar. Centring them needs an `NSToolbar` or JBR's
  custom title bar; the Settings window has the same placement.

### Tests

- Each page's test (`HomeScreenTest`, `SearchScreenTest`, `LibraryScreenTest`, `DetailScreenTest`,
  `DownloadsScreenTest`, `DownloadSeriesScreenTest`): one page bar, 52 at the top, the page's width, a hairline along
  the bottom and none along the top, read from the pixels (`PageBarAssertions`).
- `DesktopShellTest`: the first sidebar row starts at 52 and the page bar beside it measures as above; Settings is the
  last row, a button, `⌘,` on a Mac, and a click opens the window; no row without an opener; the window is told the
  sidebar is shown on every top-level page; the three properties set and unset.
- Moved coordinates in the existing tests: sidebar rows +28, the Search field and Home's bar at the bar's middle, the
  chips at 84.

## KMP-97 — The boards' fonts, bundled: the Mac first · **in pr** · the owner approved bundling Manrope and JetBrains Mono

A design audit found that the Mac never loaded the boards' fonts. `cloudStreamTypography(sans, mono)` takes the
families, but every caller passed none, so both the Mac and TV drew the platform face. The ramp also set no weights,
so everything drew at 400. Those two gaps together made every page unlike its board.

**PR 1, the Mac (this one).** TV's pixels do not move.
- **The files:** Manrope 4.505 at 400, 500, 600, 700 and 800 (`googlefonts/manrope`, commit `468c0dbe`), and JetBrains
  Mono 2.304 at 400, 500 and 700 (the official release). These are exactly the faces `Design System v2` loads. They are
  static TTFs, upstream's bytes renamed only, in `:core:designsystem`'s `composeResources/font/`. Their OFL texts are in
  `files/licences/`. `NOTICE` records each file's source and SHA-256.
- **Why static:** minSdk is 24, and Android applies a font's variation only from API 26. Below that, a variable Manrope
  draws its default instance, which is ExtraLight.
- **The code:** `CloudStreamFonts` builds the two families, and `cloudStreamAppTypography()` puts them on the ramp. The
  Mac draws with `desktopTypography()`, which is that plus `forDesktop()`. Main, the Settings window, the subtitle
  render check and the desktop test hosts use it. `DesktopInteractionTest` and `:core:designsystem`'s `SceneHarness` had
  no desktop ramp, so they take the families alone.
- **The weights are the boards':** the weight of each role in `Design System v2`'s line-box samples, and the one most of
  the boards use at that role's size:

  | Role | Weight |
  |---|---|
  | display | 800 |
  | h1 | 700 |
  | railTitle | 700 |
  | body | 400 |
  | label | 600 |
  | caption | 700 |
  | labelMono | 500 |
  | captionMono | 500 |

  They live in `forDesktop()` until PR 2 moves them into the shared ramp.

**Proof,** all local (CI is off):
- `CloudStreamFontsTest` measures text against the files' own advance widths, read from their `hmtx` tables. At 100 px,
  ten `H` are 684.5 px in Manrope Regular and 734.0 px in ExtraBold, and ten JetBrains Mono glyphs are 600.0 px. The
  test also checks that the Mac's ramp reaches ExtraBold for display and Regular for body. Its control checks that the
  platform's faces measure otherwise, so a family that fails to load and falls back without an error fails the test.
- An early version measured the paragraph's width, which is rounded up to a whole pixel, and read 735.0 for 734.0. The
  test now reads the line's width.
- Moved assertions: none. Every one of `:core:designsystem`'s 128 JVM tests and `:desktopApp`'s 538 still passes with
  the real faces. They assert positions relative to each other and to the line boxes, and the line boxes did not change.
- Gates: abi, check, xml, extapi, canary and dist all 0. `classes-jar.api` is unchanged.

**Size.** The Mac app's `designsystem` jar gains 716,128 bytes compressed (1,558,658 raw): the eight fonts and the two
licences. The APK gains nothing yet, because `:core:designsystem` has no `androidResources`, so its `composeResources`
do not reach an APK (CMP-9547).

**PR 2, TV:**
- [ ] Move the weights from `forDesktop()` into the ramp in `:core:theme`.
- [ ] Give the sans caption `Design System v2`'s 16sp box (Manrope at 20 px inks 26 px, which a 24 px box clips).
  captionMono keeps 12sp. Name every reflow; the 10sp-vs-12sp floor stays open.
- [ ] Enable `androidResources` on `:core:designsystem`. The extension-API build notes that such a module's `R` classes
  are compiled into the published jar, so read `classes-jar.api` before and after.
- [ ] Pass `cloudStreamAppTypography()` at the ten TV roots.
- [ ] Switch the device-test hosts, then re-record the 99 references with every diff read.

## KMP-91 — The Mac player's panels from the right, `DS-23` M4 to M7

**Status:** in review · `:desktopApp` · after `KMP-87` (#257) and `I18N-19` (#249)

The four lists (Source, Subtitles, Audio tracks, Picture size) were pop-ups over the controls. They are now one panel
from the right (`PlayerListPanel`):
- 360 wide (`DesktopDimens.SidePanelWidth`) and the window's height, with no scrim: the picture plays on beside it.
- Its title is a heading; an optional line under it; the rows; then Done. Source's line is `2 sources`
  (`player_source_count`).
- The HUD's controls centre in the room left of it while it is open, and move back when it closes.
- At the smallest window (800) beside a panel the controls are 392 wide. A fixed 80 volume rule there left the seek
  rule none, so below 480 the volume rule gives way: the speaker still mutes, and the arrow keys still set the level.
  The seek rule keeps about 80. The selectors shorten their words with an ellipsis, as they already did when narrow.
- Done, Escape, the same key, or a click on the picture closes it, as before.
- Picture size had its own pop-up; it uses the same panel now.

Every behaviour the lists' tests pinned is unchanged: the keys, a list held while open, failed rows, an outside click
choosing nothing.

**Not here — the rows (`KMP-93`):** the panel's frame matches the boards, its rows do not yet. The boards' rows are 56
tall with a mono second line (`1920×1080 decoded`, `3 variants`), a verb at the right (Use, On, Switch, Retry) and the
4×24 marker on the chosen row; here, Source has the verbs and the marker, and the other lists keep a check at the left
and one line. The words exist (`I18N-19`); the second lines need data the player does not have for every row yet.
M5's Load from file, Load from Internet and Subtitle delay rows are not features yet, so they are not drawn.

**Tests** (`PlayerScreenTest`, 71, all run):
- New: the panel is at the right and the window's height, and Done closes it and chooses nothing; the controls move
  into the room it leaves and back.
- New: at the smallest window with every selector and full screen, nothing of the controls is in the 24 before the
  panel, and the seek rule runs unbroken where the end time and the speaker would sit with a volume rule.
- **Changed existing tests:** six clicked rows counted up from the old pop-up's foot. They now count down from the
  panel's heading (`panelRow`). Each asserts what it did, except the held-list test, which keeps its point another way.
  Before, it clicked the foot, which was English while the list was held. Now a click where German's row would be
  chooses nothing while held, and German is there when the list opens again.

## KMP-94 — The Mac player's Up next, `DS-23` M13 and M16

**Status:** in review · `:desktopApp`, `:feature:settings`, `:feature:detail` · stacked on `KMP-90e` (#274), which is on
`KMP-90g` (#273)

At the end of an episode with one after it, a card offers the next one (`UpNextCard`), above the controls at the right:
its line (`S1:E5 · Low Tide`), the title's poster (an episode has no still of its own), and what happens next.
- **It counts down 10 s, then plays** (the owner's ruling, `DS-23`): a line, `Next episode in 8 s`
  (`player_up_next_in`), and a rule along the card's foot that empties. Play now and Cancel are under it.
- **The count runs only in the episode's last 10 s** (the PM's ruling in review), so autoplay never cuts an episode's
  end. The card comes 30 s from the end showing `Next episode`; Cancel there stops the count that was to come. Until
  `KMP-86`'s credit stamps reach the Mac, the end of the stream is the only "credits" the player knows.
- **The count holds** while a panel is open or the pointer is on the card, and goes on from the same second.
- **Cancel, or Esc, keeps the card** with no count (M16): Play now and × are left. × puts it away for the rest of the
  credits; the end of the stream then goes back, as it always did. Once nothing counts, Esc goes back as before.
- **Play now plays at once**, and only once: the card says `Loading…` while the next episode's sources are found.
- **"Play the next episode" off** (`PlaybackSettings.autoplayNext`): the card comes with no count. A change in the
  Settings window while the card is up starts or stops the count; it does not undo a Cancel.
- An episode that ends with the card unanswered stays on it; with no episode after it, the end goes back as before.
- Skip gives way to the card. The card gives way to an open panel, and its count holds meanwhile.

**Where the next episode comes from.** The title's page is under the player, so its view model is live.
- **Which one:** the one after this one in the order a viewer goes through them, `playOrder()` (`:feature:detail`, public
  for this). That is the seasons in `seasonGroups()`' order (numbered ascending, then Specials, then Other), each by
  its episode number with an unnumbered one last, as `firstEpisodeToPlay` reads them. It is not the provider's order,
  which can list S2 first, put specials among the numbers, or count a season down. Two episodes with the same `data`
  cannot be told apart by the player, so the first in that order is taken as the one playing.
- **Playing it** asks the page for its sources as its Play does, playing the best found (`requestPlay`, `KMP-39`). It
  takes this player's place, and full screen stays as it was (`PlayerHandover`). With nothing to play, the player goes
  back to the page, whose source list says why.
- **Closed while it loads** (review of #276): the wait is cancelled with the player, and it dismisses the page's search,
  so nothing that search finds later plays the episode that was backed out of.

**Settings.** The Playback pane and its Play the next episode row are back (`SettingsRow.PlayNextEpisode`,
`SettingsAction.SetAutoplayNext`), as `KMP-90` had them before #266's review took them out until this reader landed.

**DECIDED (small calls, reported to the PM).**
- **When the card comes:** the last 30 s, or the end of the stream; the PM kept it, and moved the count's start to the
  last 10 s. The Mac has no credit stamps (`KMP-86`); with them, the card would come at the credits.
- **No "Up next" heading:** the boards' small heading would be a new key in 62 locales. The line under the title says
  `Next episode` when nothing counts, and the count when something does; both strings exist.
- **The art is the title's poster**, because an episode carries no still.

**Tests** (all run):
- `PlayerScreenTest`, 11 new:
  - the count from ten, once;
  - no card before the credits or after the last episode;
  - seeking out and back starts a fresh ten;
  - holds for a panel and for the pointer;
  - Cancel, Esc, Play now;
  - the setting off, and the setting changing;
  - × and the end of the stream;
  - an ended stream waiting on the card.
- `UpNextCountdownTest`: the count, when the card is due, and the handover keeping full screen.
- `NextEpisodeTest`: the next episode across seasons and at the ends, and asking the page to play it, with a search
  that ends with nothing or fails.
- `SettingsWindowTest` and `SettingsSearchTest`: the row's write-and-read-back test and the Playback pane are back.

## KMP-93 — The Mac player panels' rows, `DS-23` M4 to M7

**Status:** open · `:desktopApp` · after `KMP-91`

`KMP-91` built the panel; this makes its rows the boards' rows: 56 tall, a name and a mono second line, a verb at the
right (Use, On, Switch, Retry, Open), the 4×24 marker on the chosen row. A second line is drawn only from data the player
has; a row without one stays one line. Only the rows that are features get drawn.

## KMP-89 — Player KDoc examples use a made-up source

**Status:** in review · comments only · from `KMP-87`'s review

Two KDoc examples named a real site as a source's readout. Both now say `Source One · 1080p`, as the boards and the tests
do: `SourcePicker.kt`'s `sourceName()` and `PlayerHudState.source`. No code changes.

**Not here, for whoever owns them:** test fixtures that still use real site names, `app`'s `TvHudFactsTest` and
`:feature:player`'s `PlayerHudTvDeviceTest` (a device suite this machine cannot run). `:library`'s extractors are real
classes with real names and are not fixtures.

## KMP-85 — One age formatter, in `:core:strings` · **built 2026-11-08**, follow-up of `KMP-84` (`#240`)

The review of `#240` found two copies of the code that says how old something is (`4m`, `2h 5m`, `1d 2h 5m`):
- the library's private `elapsed`, in `LibrarySourcesTv.kt`, for the sync line;
- Home's `homeAge`, which `#240` copied from it for H4's kept rows.

Both are now `elapsedAge(elapsedMillis): UiString`, in `:core:strings`. Each caller still wraps it in its own sentence, so
the age sits where each language puts it. Neither screen changes what it shows: the same keys, the same arguments, and a
clock behind the record still reads as now.

**On master,** after `#240`, which brought Home's copy.

**Tests:** `HomeAgeTest` moved to `:core:strings` as `ElapsedAgeTest`, its two cases unchanged, plus one that resolves
the three forms in English. `:feature:home`, `:feature:library` and the Mac's Home and Library screen tests still pass.

**Overlap with `#223` (`KMP-57`):** `#223` (on master) made it internal; this repoints LibraryScreenDesktop's caller to
`elapsedAge(…).resolve()`.

## KMP-70 — A failing source's reason in plain, translated words · **open**, follow-up of `KMP-55`

`#200` made Home's failure detail safe. A thrown failure is now its type, such as `IllegalStateException`, never its message,
because a message can carry an address, a path or a token. That holds on both platforms. It is developer language on a TV,
though. To do:
1. Map the common types to a typed reason in `:feature:home`: network, timeout, refused, other.
2. Give each reason a key with its plain, translated words (an `I18N` task first). A screen draws the words; the type
   stays only as the pasteable detail.
3. Keep the rule that no message, address or path reaches the screen. Rerun the TV Home device suite.


## KMP-73 — TV account select, artboard 8d (queue `Q16`) · **measured 2026-11-07**

**Status:** in pr, stacked on `BUG-50` (#215), with `BUG-49` (#213), `I18N-16` (#217) and `BUG-36` (#212) merged in. Board
8d of `TV Onboarding Extensions.dc.html` (a board file in the owner's Claude Design project), on `Television_720p`, API 36.

### What it adds

- **`:feature:account`**, a new module: `AccountSelectScreenTv` and `PairingPanelTv` over a plain state
  (`AccountSelectState`), so it needs no `:app`.
- **`AccountSelectActivity` draws 8d on TV**, behind `TvAccountSwitch`, over the same `AccountViewModel` the old grid used:
  selecting, the PIN check and the hand-off to `MainActivity` are unchanged.
- **Profiles are a card row.** Focus starts on the current profile, which rests filled; a focused card lifts and takes a
  ring with no offset. A locked profile says only "Locked · PIN" (what it syncs with is its own business until the PIN);
  the others say whether they are current and which services they use.
- **Sync services are a list with their true state**: Simkl pairs with a code; MyAnimeList and AniList offer a browser
  sign-in where the device has a browser and say "Not available here" where it does not; Kitsu signs in with its form; a
  connected service offers "Remove from this device", behind a confirmation.
- **Pairing is a side panel** with the code, a QR code of the address and the countdown. It reuses `BUG-49`'s
  `DevicePinPoll`, so every way out ends the pairing, and focus goes back to the row that opened it (`BUG-36`'s `opener`).
- What 8d does not draw stays the old dialogs: the profile editor, the PIN prompt, Kitsu's form, the remove confirmation.

### Behaviour changes

| Before | Now | Why |
|---|---|---|
| **One added profile plus the default skipped the picker** (the stored list, which omits the default, had one entry) | The picker shows; a single profile still skips it | The audit's finding; counted as `getAccounts`, the default included |
| The same count decided `MainActivity`'s biometric prompt | It uses the same count | Otherwise a phone with one added profile would be asked twice |
| A browser-only service offered a sign-in that silently failed on a TV with no browser | It says "Not available here" | 8d |
| Signing out of a service on TV said "Log out" | "Remove from this device", after a confirmation | No provider revokes a token; the words say what happens (owner's ruling) |
| Editing a profile opened from `MainActivity` switched to it and went back | Unchanged on the new screen | The review: match the old grid (PM's call) |

### Security

- **Sign-in state is read by presence** through `CredentialStore` (`BUG-50`): a token is never parsed for this screen. A
  sign-out leaves its key holding an empty array, so an empty array counts as signed out; the value is compared, never
  parsed or logged.
- **The pairing code** is shown because the person has to read it, and is never logged; the screenshots use a fixture
  (`FAKE00`) and a checkerboard in place of the QR. `Pairing.toString` leaves the code out, so a state logged by accident
  cannot carry it.
- **`hasBrowser`** asks the package manager for an `https` `VIEW` handler; the manifest's `<queries>` now names that
  intent, which Android 11+ (Google TV 14 included) needs for the answer to be accurate.

### Measured on the way

- **BACK on the pairing panel closed the whole screen.** API 36 sends BACK to the back dispatcher, not to
  `SidePanelHost`'s key handler, as its KDoc warns; the host adds an `OnBackPressedCallback` while pairing.
- **Closing the panel put focus on the first card**, not the row that opened it (measured: "Focus is on Main, not
  Simkl"): `BUG-36`. Fixed with its `opener`, latched so it survives the exit.
- **The cards drew no ring**: focus was read through an interaction source shared by two focus targets. One target now,
  read with `onFocusChanged`.
- **The panel title read "Pair %s"**: Compose's `stringResource(res, args)` does not fill a bare `%s`. `UiString` does.

### Reduced from the board

- **No "last sync" age**: none is recorded (owner's ruling).
- **No service logos** in the rows' disc.
- **A profile picture from an address shows initials**; the bundled pictures show.
- **The add card's glyph is the 24 dp `Add` icon** centred in 8d's 32 dp cell; the pack has no 32.
- **Kitsu's sign-in and the profile editor are the old dialogs**: 8d does not draw them.

### Tests

- `ProfileLineTest` (common, 2): what a card's line says.
- `PairingTest` (common, 2): the fixture code is in neither `Pairing`'s nor the screen state's `toString`.
- `AccountSelectScreenTvDeviceTest` (device, 6): focus starts on the current profile; Manage, then cards, then rows; a card
  press selects; pairing takes focus to *Get a new code*, BACK cancels and focus returns to the Simkl row; screenshots
  `account-select` and `account-select-pairing`, each looked at.
- `AccountSelectTvNavigationTest` (`:app`, the real path, 6): one added profile shows the picker (**fails with the old
  count**: "8d did not show with two profiles"); one profile still skips; choosing a profile hands over as that profile; a
  marker sign-in shows as "Current · Simkl" and its value never reaches the screen; an empty array reads "Current · No
  sync"; an edit opened from `MainActivity` switches to that profile and goes back (**fails without the fix**: "It did
  not go back to MainActivity").
- **Leaves nothing behind** (train 8): run straight after this class, `TvDetailNavigationTest` failed 3 of 3 ("expected
  Try again, was Go Back"); alone it passed. Measured cause: **touch mode.** The edit test taps Apply
  (`apply.click()`), and touch mode is the whole device's until a key ends it, so the detail page's first focus
  request didn't take and its first D-pad press landed on Go Back. Of the six tests paired one at a time with the
  detail test, only the edit test failed it, and a key-names-only snapshot of every preferences file showed it leaves
  nothing stored. Finishing the activities alone did not fix it (still 3 of 3 red). Now each test, afterwards, finishes
  every activity it started and waits until each is destroyed (`MainActivity` is `singleTask`, so a leftover one would
  be reused), turns touch mode off, and puts back the profiles, the selection, the skip setting, `hasLoggedIn` and the
  cached sign-in IDs. The edit test paired with the detail test passes 1 of 1, and the whole pair (account, then detail)
  passes 3 of 3, locally on `Television_720p`.

### Not verified

**The production BACK callback** (`closesPairing`, removed and re-added in `AccountSelectTv.attach`) is not covered: the
real panel needs Simkl's live code endpoint, and the feature test builds its own callback, so it covers the same BACK
handling, not this code. Also: a real browser sign-in, Kitsu's form, `TalkBack`, a real remote.

## KMP-56 — The Mac's Search, built from `DS-22` S1 to S7 · **built 2026-11-07**

The boards are `Desktop Search.dc.html` S1 to S7, a board file in the owner's Claude Design project (`DS-22`).

- **S1:**
  - The type chips: the types some installed provider serves, lit by their outline, as Home's status chips are.
  - Sources is a button, `Sources 12 / 48`, that opens the panel. It used to be a read-out.
- **S2:** results grouped by source, each group with its count, as built before.
- **S3:** a partial search keeps its results under the failure banner, with "Retry failed". The exact sentence on the
  board ("%d sources didn't answer. %s timed out after %d s.") has no key, and a `SearchOutcome` carries no timeout,
  so the banner keeps `search_state_partial_headline`.
- **S4:** the sources panel from the right edge, over a scrim:
  - A row toggles a draft; Done commits it; the scrim and Escape discard it (`DEC-17`).
  - Escape is caught at the screen's root, so it closes the panel even while the field holds focus, and never empties
    the field instead.
  - A row is checked when on, and shows a warning glyph when its provider's last answer failed.
- **S6:** no results names the lit chips, through the existing `narrowedBy`.
- **S7:** renders at 580×520, the page's share of an 800×520 window beside the 220 sidebar.
- **S5** (suggestions as a popover) is `KMP-72`.

`DesktopSearchSource` now implements `choices`, `selectSources`, `typeFilters`, `selectTypes` and `clearFilters`:
- Desktop has no language preference, so every installed provider is eligible.
- Picks and chips follow Android's rules: a pick that matches nothing installed falls back to every provider; chips
  that nobody serves narrow nothing; a row the chips hid keeps its state through Done.
- The `SearchTypeFilter` → `TvType` mapping is a copy of `:app`'s, which desktop cannot reach. Keep the two the same.

**Decided** (reported to the PM):
- Picks and chips last as long as the app runs. There is no desktop preference store yet, as with Home's bookmark
  filter (`KMP-55`).
- A failure's detail goes through `withoutUrls()`. Before this, the first line of a plugin's `errorString` reached the screen
  unredacted.
- **After review (`#221`):**
  - `:desktopApp`'s `withoutUrls` is now the fourth synced copy of the BUG-43/44/47 rules, with the same case table
    (`DesktopRedactionTest`). The sandbox's copy is `internal` to its module. So Home's, Search's and a title's lines now
    keep a url's host, and lose its path, query, userinfo and fragment, plus `file:` URIs and the home, volume, storage and
    Windows user paths. The old rule wrote `<url>` for the whole url. The home-directory-to-`~` rule is gone; `<path>`
    covers it.
  - Invisible characters are stripped **before** redacting, on Search and Home: one inside `://` hid the url from the
    pattern, and stripping afterwards put it back together. Proved by mutation: with the old order,
    `a url split by an invisible character does not reach the detail` fails with the whole token on screen.
  - The failed-provider glyph is described with the existing `error` key.

**Tests:**
- `SearchScreenTest`: the Sources button and a chip; the panel's row, Done and scrim; and Escape closing the panel
  rather than clearing the query. That last one was proved by mutation: with the root handler off, it fails
  `expected:<[SourcePanelDismissed]> but was:<[Cleared]>`.
- `DesktopSearchSelectionTest`: picks, chips, a hidden row, Search everything, the fallback, health and redaction.
- Two `DesktopInteractionTest` cases rested on the old premise that only the field takes focus. They now assert that
  Tab lands on Sources, not on the clear button.

## KMP-72 — The Mac's suggestions as a popover over the results, `DS-22` S5 · **open**, follow-up of `KMP-56`

- The shared `SearchViewModel` replaces the results with TV's 8a column when it has a suggestion source. S5 keeps the
  results under a popover instead, which is a change to the shared view model and needs a TV device run.
- The only suggestion source today is Android's `SearchSuggestionApi`, which sends every keystroke to a third-party
  service. On the Mac, start recent-only, from local history. **A service is the owner's call** (security first, the
  PM's ruling of 2026-11-07); raise it when this task starts.
- Words: `search_suggestions_return_hint` (`I18N-15`). The rest reuse `search_tv_suggestion_*` and
  `search_quick_meta_answered`.

## KMP-57 — The Mac's Library, built from `DS-22` (L1 to L7)

**Status:** in review · took over from desktop-session-1, the PM's call of 2026-11-07, to run beside `KMP-56`

`LibraryScreenDesktop` now draws the `DS-22` boards on the shared `LibraryViewModel` (`KMP-49` to `KMP-51`):
- **L1:** the bar holds the source, its line (`library_on_device`, or the account and `library_synced_format`), the
  filter (`filter_bookmarks`; Escape empties it) and Sort (`library_sort_current`). Under it, the lists as buttons,
  each with its count in the mono face, and the cards in a grid, four to a line at 1280.
  - A card's line is its kind, the user's rating with the pack's star, and its episode count, where each is known.
  - **Remove** shows at the poster's top-right while the pointer or focus is on the card (DS-22's decision).
- **L2:** over a held read, the bar's line says why (`library_sync_unreachable`, `library_sync_offline`). A banner says
  `library_sync_failed` with the age and offers `library_sync_retry`. The cards stay, dimmed to half.
- **L3:** an empty list says `library_list_empty_headline` and `library_list_empty_body` with the list's name, and offers
  `library_go_to_list` for the first list with titles. With nothing kept anywhere, it offers Search. A filter that
  hides a list's titles offers Clear.
- **L4:** Sort lists all four criteria, with `library_sort_supported`. One the source does not keep stays in the list,
  greyed, with `library_sort_not_kept`, and takes no press.
- **L5:** the source panel, with a check on the source shown. One that is not connected says so and cannot be chosen.
- **L6:** the list picker, each list with `library_title_count`, a check on the card's own, and Remove below a rule.
- **L7:** under a 720 content width the sync line and the filter give way. The filter stays while it holds words, so a
  list is never narrowed by something unseen.

`DesktopLibrarySources` gives the window one source, the local list. It sorts by Updated and Title only, because
`DesktopLibraryStore` keeps no rating and no year. The sort is kept in `~/.cloudstream4/library-sort`: one line, written
atomically, owner-only, and read back only as a criterion's exact name and a direction. `DesktopApp`'s default keeps
nothing, so no test writes to the real home directory. Escape closes whichever panel is open.

**Tests** (`:desktopApp:jvmTest` with real pointer and key events):
- `LibraryScreenTest`: 23 cases, 12 of them existing.
  - New: L2, L3 (both actions), L4, L5, L7's filter, Remove on hover, the secondary click, the context-menu keys,
    Escape in the filter, and Sort reordering a list in the whole window.
  - Every coordinate is measured off the renders.
- `LibrarySortMemoryTest`: 6 cases, covering the source, read back, owner-only, rejected text, too large, and `None`.
- `:desktopApp` 458 and `:feature:library` 84, with 0 failures.

**DECIDED** (reported to the PM):
- **The desktop has the local list as its only source.** There is no tracking-service sign-in on desktop.
  - Options: (A) local only; (B) also list the services as Not connected; (C) no sources at all.
  - Chose A. B is a dead end, and C would offer sorts with no data behind them.
  - L2 and L5 are built and tested with services in the state, so they work when sign-in arrives.
- **The local source is named `Local`**, the name Android's `LocalList` gives it. No key in `:core:strings` means "local".
  - The name reaches the panel and Sort's lines untranslated, as it already does on Android TV.
  - A key for it is an `I18N` follow-up.
- **L6 opens from a secondary click and the context-menu keys** (Menu, Shift+F10), because L1 dropped the built list
  button.
  - Options: (A) that; (B) a second hover verb, Move, which needs a new key and is a second verb DS-22 did not draw;
    (C) keep the list button under the card.
  - Chose A, because it is how a Mac opens a thing's menu.
- **Cards keep the built 200 minimum.** That gives L1's four at 1280 and two at 800×520. L7 draws three at 164,
  under the board's own stated minimum.
- **Favourites keeps its list button**, seven in all where the board drew six. Hiding it would hide what is kept there.
- **No sort, filter or chevron glyph.** `CloudStreamIcons` has none of them, and adding one is an icon decision. The bar
  uses words, as Home's source button does.
- **Sort's rows have no explanatory lines**, and the bar reads the full label (`Updated (New to Old)`), as `I18N-15`
  decided by reusing the `sort_*` keys. It fits the minimum window.

## KMP-58 — The Mac's title page, built from `DS-21` (E1 to E6)

**Status:** in review · took over from desktop-session-1, the PM's call of 2026-11-08 · scope approved by the PM before
building

`DetailScreenDesktop` now draws the `DS-21` boards on the shared `DetailViewModel`:
- **The bar:** Back, then where the page was opened from (Home, Search or Library), which goes back too, then the title.
- **E1:**
  - the poster beside the name;
  - the shared run of facts, then the kind of title and its provider;
  - a two-line synopsis that opens in place (`SynopsisToggled`).
- **E1's actions:**
  - Resume `S1:E3` with its rule and what is left under it (`resume_time_left` on one line), else Play;
  - Start over (`detail_start_over`), the same episode or film from its beginning;
  - the watch list, `Add` or `Done` by whether the title is on one, which opens the watch-status panel.
- **Episodes** are on the page under a button per season, opening on Resume's season. Season 0 is `season_specials`.
  Episodes with no season are `season_other`, last (`I18N-12`).
- **E2:** a row's Play shows while the pointer or focus is on it, and its words never move for it.
- **E3:** the sources panel, with Cancel. The loading words have a line of their own, so finishing moves no row (`BUG-19`).
- **E4** is the skeleton. **E5** has Try again and Go back, and shows nothing of the failure (`BUG-21`).
- **E6:** in the 800×520 window the name is a size down, and everything still answers.

Every action that plays sends `PlaybackRequested`. The desktop host turns it into its source list with the right start:
the stored position for a Resume, the beginning for Start over or another episode (`startPosition`, tested). Escape
closes the source list, then the watch-status panel, then the page.

`LibraryDetailSource` adds what this computer remembers about the title, from the same store the library reads:
- **Resume:** the newest resumable position, only on an episode the provider still lists.
- **Seasons**, counted from the episodes.
- **The watch list** and `setWatchStatus`. A title put on a list for the first time keeps its name, poster and provider.
  None does not take a title out of Favourites, which the page cannot show.

A library that cannot be read costs only these, and the page still opens.

**Tests** (`:desktopApp:jvmTest`, real pointer and key events):
- `DetailScreenTest` (14): each control is found by what a screen reader is told, then pressed at its middle.
  - E1's actions, the watch list, the synopsis, the bar's way back.
  - Seasons: the selected one in semantics, Specials and Other in order.
  - E2's hover verb, E3's panel, the watch-status panel's selection, E4, E5 (no failure text), E6, Tab to Resume.
  - The start position.
- `LibraryDetailSourceTest` (7).
- `SourcesPanelTest`: the header is a reserved line taller, so `HEADER_BOTTOM` is 64 → 80 and `FIRST_ROW_Y` is 88 → 104,
  measured off its renders. One case is renamed: Cancel, not Close, keeps its place.

**DECIDED** (approved by the PM before building):
- **Score, tags, certification, status, runtime and the trailer do not reach the Mac yet.** The plugin sandbox's
  `RemoteDetails` does not carry them, and changing that format is a security-boundary change. It is `KMP-76`.
- **Per-episode Watched and time left, and their rules,** wait on `KMP-63`. The contract has no per-episode watch state.
- **E2's Download and Mark watched** need `DetailAction`s that do not exist, and the Mac's downloads are `KMP-75`. The
  row's verb is Play alone, with no tooltip: one verb that is the row's own says nothing a tooltip would add.
- **No episode thumbnails, Share or More.** `DetailEpisode` has no image, there is no share action on desktop, and nothing
  More would hold works here yet.
- **The synopsis has no "More" word.** There is no such key. The block itself is the target, named `synopsis` for a
  screen reader.
- **The sidebar on E1 is `KMP-77` (shell)**, desktop-session-1's, for every pushed screen.

## KMP-76 — The plugin sandbox carries a title's facts to the Mac · **built 2026-11-08**, follow-up of `KMP-58`

The design was approved by the owner on 2026-11-08, before any code. `RemoteDetails` now carries five facts from
`LoadResponse`: the score, the tags, the content rating, the show status and the runtime. `DesktopDetailSource` maps them
onto `TitleDetail`'s existing fields, which feed the shared run of facts under the title's name. Neither `feature/detail`
nor Android changed.

**The trust boundary is the app's reader.** The plugin runs inside the host process, so it can write any bytes the host
could. The host's caps only help an honest plugin's page fit. Everything is checked again in the app, in three steps:
1. **Shape**, in `readFacts`. Every length and count is checked against what is left before anything is allocated. The
   section is read from its own slice of the frame, so nothing in it can reach past its stated length.
2. **Bytes.** A tag over 256 bytes, or a certification over 64, is skipped without being decoded. So is a tag past
   16 KiB of decoded tags, or past the 16th.
3. **Meaning**, in `RemoteFacts.cleaned`:
   - the score must be 0 to 100 tenths;
   - the runtime must be 1 to 1440 minutes;
   - the status must be code 1 or 2, never free text;
   - a tag loses control and format characters (U+202E included), and is dropped if blank, repeated, or over 64
     characters;
   - a certification must be 1 to 16 letters, digits, spaces or `-+/`, or it is refused, not repaired.

| Fact | Wire | Bound |
|---|---|---|
| score | `Int`, tenths of a point out of ten | `0..100` |
| tags | count, then strings | 16, each ≤ 64 chars and ≤ 256 bytes, ≤ 16 KiB in all |
| content rating | optional string | ≤ 16 chars, ≤ 64 bytes, letters, digits, space, `-+/` |
| status | one byte | 0 none, 1 Ongoing, 2 Completed |
| runtime | `Int`, minutes | `1..1440` |

**A bad fact costs only itself.** The page opens with the rest. A section that cannot be read inside its own length, cut
short or with a lying length within it, costs all the facts and keeps the page: the outer reader has already stepped past
it. Only a section length that lies past the frame fails the call and ends the host, as any lying length does elsewhere in
this format. Each drop is logged as the fact's name and a reason in the code's own words, never the value, not even a
plugin's number, and many dropped tags make one line.

**Compatibility.** The section follows the episodes as a version byte (`FACTS_V1`) and a length:
- A reply that ends after the episodes (a host from before this) has no facts, and the page is as it was.
- An app from before this ignores the trailing bytes.
- A version this app does not know is skipped whole.
- A later fact goes in a new version, never by redefining version 1.
- `classes-jar.api` is untouched: the sandbox is not part of it.

**Tests:**
- `HostProtocolFactsTest` (15), hand-written frames:
  - each bound, just inside and just past;
  - an old reply and an unknown version;
  - oversized bytes skipped with the reader still in step;
  - a hundred thousand tags;
  - a section length lying past the frame, which fails the call;
  - a section cut short, or lying inside its own length, which keeps the page;
  - no value, and no plugin-chosen number, reaches the log.
- `PluginHostClientTest`, three lying-host cases through the real client:
  - facts past every bound cost only themselves, and the host lives;
  - a section cut short inside its length costs the facts, and the page and host live;
  - a section lying about its length past the frame ends the host.
- `SandboxEnforcementTest`, a malicious plugin in a real sandboxed host. Its score is valid, but it sends:
  - a 1 MiB tag, 100,000 tags, and tags with terminal escapes, U+202E and zero-width joiners;
  - `<script>` and a forged line as the rating;
  - `Int.MAX_VALUE` minutes.
  The page loads with each bounded or dropped, and the host lives on.
- `TitleFactsTest` (2), the mapping onto `TitleDetail`.
- **Not here: the facts on E1's screen.** Master's Mac title page does not draw the run of facts yet; `#226` (`KMP-58`)
  does. Asserting them there is `KMP-82`, a one-line follow-up once both have landed.
- **Red without the app's checks.** With `RemoteFacts.cleaned` switched off, 5 of these fail, the lying-host case among
  them.

**DECIDED** (approved with the design):
- **No `hasTrailer`.** The Mac cannot play a trailer, so the flag would draw a button that does nothing. Playing one later
  means a trailer URL crossing the sandbox, which needs link-style checks of its own. It belongs with trailer playback.
- **The host shortens an honest plugin's tags to 64 characters** rather than losing them. The app does not shorten
  anything: what arrives over a bound is dropped.
- **Score on the wire is a whole number of tenths**, so no NaN or infinity can be sent. The page keeps it out of ten.
- **A section unreadable inside its own length keeps the page** (the PM's call, after review). The reader of the reply
  has already stepped past the slice, so it is still in step; only the facts are lost. This covers a lying length inside
  the slice as well as a slice cut short, since both are contained by it.

## KMP-82 — The Mac title page shows `KMP-76`'s facts, and its genres · **built 2026-11-09**, follow-up of `KMP-76` and `KMP-58`

**Found:** the page drew the facts, but as one line cut with an ellipsis, in the shared order: score, every tag, year,
certification, runtime or status, then type and source. A title with a few genres lost everything after them,
certification included, and the score read `8.4/10.0`.

**Now, E1 under the name** (the owner approved the layout from a render):
- **The facts line:** the score with its star (`☆ 8.4`, Tabler `Star`, read as "Rating 8.4" from the existing `rating`
  key), the certification in a hairline outline (`muted`, `labelMono`, `BadgeDefaults`' height and padding), then the
  year, the runtime, the status, the kind of title and its source. Runtime and status both show where both are given.
  The line wraps rather than cuts, so no fact is lost off its end. A separator sits only between two facts on the same
  line (`SeparatedLines`), so no line starts with one, and a fact wider than a whole line is cut with an ellipsis on a
  line of its own. The dots are decoration: a screen reader reads the facts.
- **The genres line:** up to five of the source's tags (`GenreCount`), quieter (`muted`, `label`), on one line cut with an
  ellipsis. Text, not chips: a chip on the Mac reads as something to press.
- Each fact only where the source gave it. With none, neither line is drawn and the synopsis sits under the name.

**DECIDED:** the star takes the text's colour (no colour role means rating); no new strings; TV keeps the shared
`metaRun`, unchanged. `oneDecimal` and `runtimeLabel` in `DetailCopy` are now internal so the Mac page can use them.
`ui-components.md` gains the star's row.

**Tests** (`DetailScreenTest`, 5 new, 21 in all):
- every fact drawn, five genres and no sixth, in the order facts, genres, synopsis;
- a title with no facts: the synopsis is the column's own gap (8 px) under the name;
- five 64-character genres: one line, its height that of a short one, inside the reading width, with the facts above
  still all there;
- facts forced onto two lines (the smallest window, a long source name): every fact there, and each line's first fact
  at the column's own edge, so nothing comes before it;
- a source name wider than a line: one line, on its own, inside the reading width.

Proved against master's page code: the first and third fail ("the score, with its star"; no genres line). The second
passes there, as master draws nothing for nothing, and fails (8 → 35 px) when the genres line is drawn without genres.
The two wrap cases fail when a separator is put back before the fact that starts a line (the fact lands 14 px in).

## KMP-100 — The Mac title page's backdrop, E1 and E3's wash · **built 2026-11-09**, stacked on `KMP-82` (`#298`)

`TitleDetail.backdropUrl` existed, but the sandbox did not carry it and the Mac page never drew it. An image URL from an
extension is a security boundary: the app fetches and decodes it in its own process.

**The wire.** The host sends `LoadResponse.backgroundPosterUrl` (an existing API field; `:library` is unchanged) in a
section after the facts, framed as the facts are: a tag byte, a length, a slice read on its own. The art has a tag of its
own, `0x21` (`ART_V1`); the facts keep `0x01`, as on master, and `0x31` is taken by `KMP-86`. After the facts the reader
takes each section by its tag, and steps over one whose tag it does not know (or a second art) rather than read it as the
wrong section, saying so once. A slice that cannot be read costs its section, not the page; only a slice whose own
length lies past the frame fails the call. The URL is at most 2 KiB (`MAX_IMAGE_URL_BYTES`), skipped unread past that. A
frame from before has no section and opens with no backdrop. `HostProtocol` states the rule for every section after the
episodes: append-only, always written, never reordered.

**The check** (`cleaned()`, in the app): `httpsUrlOrNull()`, the poster's `httpUrlOrNull()` (controls out; no
`localhost`, `.local`, loopback, private, link-local or unspecified address as written), https only, and no user info
(`https://user:pass@host/`): an image needs no credentials, and they would let a plugin have the app send a name and
password of its choosing, or dress one host up as another.

**The fetch** (`BackdropImage`, `:desktopApp`): the poster's client, which allows only public addresses on every hop
and re-checks the address really connected to, plus:
- **https on every hop:** `followSslRedirects(false)`, and a network interceptor refusing any hop that is not https.
- **A bounded decode.** Measured by reading Coil 3.3.0's bytecode: its desktop decoder makes a Skia image from the
  encoded bytes and draws it into a bitmap of the requested size. The requested size (the header's pixels) bounds the
  bitmap kept, but not the decode: Skia decodes the whole image first. So `BoundedDecoder` reads the size the image claims
  from its header with Skia's codec, which decodes no pixels for it, and refuses a file past 8 MB or an image past
  4096 by 4096 (Coil's own bitmap ceiling, 64 MB) before Coil's own Skia decoder sees the bytes. No new decode path.
  Measured: without the check, a 175-byte PNG claiming 20000 by 20000 was decoded, and that one call took about four
  minutes.
- **A byte cap,** 8 MB (`BACKDROP_MAX_BYTES`), because Coil fetches the whole body, to its disk cache or to memory,
  before any decoder runs: the decoder's own byte check came too late to stop a server sending 600 MiB. It is in the
  client, `boundedImageClient`, at two levels: as a network interceptor, on every hop, redirects included; and as an
  application interceptor, on the body Coil reads. OkHttp asks for gzip and decompresses above the network level, so the
  network count alone is of compressed bytes: 64 MiB of zeros comes as 65 KB (the reviewer's measurement). A body that
  declares a length past the cap fails on its first read, before a byte of it is taken; one with no length fails once it
  passes the cap.
  - A refusal cancels the call, so its socket closes. Closing alone is not enough: OkHttp reads what is left of a body it
    closes, to keep the connection, for up to 100 ms (measured: all 32 MiB of a test body, read after a refusal).
  - Nothing cancels inside the chain. Measured by reading Coil 3.3.0's bytecode, its callback drops the failure of a call
    that was cancelled, so a call cancelled inside the chain leaves the load waiting for ever (the first version did; its
    test hung until it was stopped). A refusal is made when Coil reads the body, after the call has answered. A body
    closed before its end, which is what OkHttp does with a redirect's inside the chain, closes its hop's socket if the
    hop is HTTP/1, and nothing more: an HTTP/2 stream is reset by OkHttp's own close, which does not drain, and its
    socket carries other streams. A body read to its stated length counts as ended: OkHttp hands the connection back to
    its pool on the read that takes the last byte, before any end is read, and the socket may already be another
    call's. A body of no bytes (a redirect is often `Content-Length: 0`) has ended before it is read: OkHttp pools its
    connection as soon as it is opened.
- **The cache.** Backdrops use Coil's own disk cache, the one posters share: under the temporary directory, files named
  by a SHA-256 of the URL, readable by this user's other processes (0644), each body stored with its response headers.

**The page.** A defaulted `backdrop` slot on `DetailScreenDesktop`, filled in `Main.kt` (one line, cleared with its
owner). The boards' wash: the art across the page under the bar, 296 tall, under a scrim from the ground at 0.3 at the
top, 0.6 at 52 %, to the whole ground at its foot (`linear-gradient(0deg, #000 0%, rgba(0,0,0,0.6) 48%, rgba(0,0,0,0.3)
100%)`, read top-down). It scrolls away with the header. Without a backdrop nothing is drawn: no poster in its place.

**Tests:**
- `HostProtocolArtTest`, 9: the round trip; a frame from before; an unknown tag skipped (`0x01` again, `2`, `0x22`,
  `0x31`, `0xFF`); art read past a section it does not know, and the first of two kept, the skip said once; only public https kept
  (http, `file:`, `data:`, `localhost`, `.local`, `127.0.0.1`, `10.`, `192.168.`, `169.254.169.254`, `[::1]`, no scheme
  empty, and user info in five forms, all refused); 2 KiB read and one byte more skipped with the page kept; lying
  lengths inside the slice cost the backdrop only; a slice past the frame fails the call; nothing the URL held reaches
  the log.
- `BackdropImageTest`, 11, against a server on this machine, with the client built by the app's own `boundedImageClient`
  (https off, as a test can only serve http) and one temporary disk cache per test: an image inside its bounds loads,
  kept no larger than twice the drawn size; a 175-byte file claiming 20000 by 20000, and one claiming 4097 by 4096,
  never reach the decoder (counted); a file one byte past its cap is refused; the client sends no plain-http request and
  follows no https-to-http redirect; a body of the cap and 1 MiB, with a length and chunked, ends as Coil's
  `ErrorResult` within 60 s, the server stops short of it, and the client takes off its socket only the read that
  brought the headers (with a length: measured 8192 bytes) or the cap and one read (chunked: the cap and 8283); a
  gzipped body of the cap and 1 MiB of zeros, under 16 KiB on the wire (asserted), ends as `ErrorResult` past the cap;
  an image behind a 302 with a body loads; a body closed unread never cancels its call, and closes its hop's socket over
  HTTP/1.0 and 1.1 and not over HTTP/2; a body read to its stated length, in pieces, leaves its socket open, and one
  byte short closes it; a body of no bytes closed unread leaves its socket open; a body of unknown length closed unread
  closes it, and one read to its end leaves it open (a chain made by hand: a test server for HTTP/2 would be a new
  dependency, and OkHttp's own HTTP/2 classes differ between the version this compiles against, 4.12.0, and the one it
  runs with, measured as a `NoSuchMethodError`). The byte count is the client's socket, not the server's: loopback's
  socket buffers take 384 to 530 KiB past a reader that has stopped (measured), and asking for 64 KiB a side did not
  change that.
- `DetailScreenTest`: a title with a backdrop asks the slot for its URL and draws it under the bar, with the ground at
  the scrim's foot; one without asks nothing and draws plain ground. `TitleFactsTest`: the URL is carried, and none is
  none, not the poster.
- Proved by mutation: without the pixel check, the claiming file decodes; with `httpUrlOrNull` in place of the https
  check, `http://` is kept; without the interceptor, a plain-http request is sent; without the wash, the slot is never
  asked; without the byte cap, the over-cap body is read; without the refusal of a declared length, the cap is read
  before it fails; with the call cancelled and failed inside the chain, the load times out at 60 s; without the
  application-level cap, the gzipped body is read whole; with the cap's lines taken out of `boundedImageClient`, the
  over-cap test fails; with a close that cancels, the redirect times out and the closed-unread test fails; with every
  protocol's socket closed, the HTTP/2 case fails; with an unknown tag read as art, the tag tests fail; without the
  user-info check, `https://user:pass@…` is kept; with a body ended only at its end, the pooled socket is closed; with a
  body of no bytes not counted as ended, its socket is closed; with a body of unknown length counted as ended from the
  start, its socket is left open when closed unread; without the end of a body counted as its end, its socket is closed
  after it was read whole.

**Changed existing test code:** `HostProtocolFactsTest`'s "cannot read into what follows it" case writes bytes past a
short facts slice. They now meet the reader of the sections after the facts, as an empty section of an unknown tag and
then one claiming 90 bytes the frame does not hold, so the call now fails on that lying length, as any does; the test
asserts that failure. Had the facts reader taken those bytes, they would have read as facts and nothing would fail.

## KMP-102 — The same hardening for posters · **built 2026-11-09**, stacked on `KMP-100` (`#299`)

Posters went through `httpUrlOrNull()` and `posterHttpClient`, so they accepted http, followed https-to-http redirects,
were read whole whatever their size, and were decoded whole whatever size they claimed. Now:
- **The check:** `cleaned()` keeps a home, search or title-page poster only through `httpsUrlOrNull()`, as the backdrop:
  public, https, and no user info.
- **The loader:** posters load through the backdrop's own loader, `extensionImageLoader`, one for every image an
  extension names: https on every hop, the byte cap at both levels (`boundedImageClient`), and `BoundedDecoder`.
- **`posterHttpClient` is unchanged.** Downloads and subtitles build on it, and their bodies are neither images nor
  under 8 MB; only the image loader is bounded.

What a poster from an extension that serves http now does: the sandbox drops its URL, and the card shows its own
placeholder, as for any poster that fails. Posters reaching the page by other roads (the Mac's stored library, home
rows built outside the sandbox) keep their own URL checks, which allow http; the loader refuses those at the first hop.

**Tests:**
- `PosterUrlTest`, 2: a public https poster is kept on the home page, in search and on the title page; http, `file:`,
  `data:`, `localhost`, private and link-local addresses, empty, and user info are dropped on all three.
- `PosterLoaderTest`: posters load through the same loader object as backdrops, so `BackdropImageTest`'s eight cases
  are the posters' too.
- Proved by mutation: with `httpUrlOrNull` back in `cleaned()`, http posters are kept; with the old poster loader, the
  loaders differ.

**Not here:** a poster is asked for at its file's size, not the card's, so one up to 4096 by 4096 is kept whole (64 MB).
Asking at the drawn size, as the backdrop does, changes `PosterImage`'s layout and is its own change.

## KMP-88 — DNS over HTTPS for the Mac's media host · **open**, follow-up of `DS-24`

**Status:** open · found by reading the code for `DS-24`'s Network pane (ST8), not by a run

`DS-24` gives the Mac one Network setting, DNS over HTTPS. The resolver can cover everything that resolves in the app's
process:
- A sandboxed plugin host reaches the network only through `OutboundGate`, which resolves names itself
  (`OutboundGate.kt:93`, `InetAddress.getAllByName`). A DNS-over-HTTPS resolver there covers all extension traffic.
- Posters (`PosterImage.kt:74`) and subtitles build their own OkHttp clients on `Dns.SYSTEM`, which an OkHttp `Dns` can
  replace.
- `DohProviders.kt` is already in `:core:network`'s `jvmCommonMain`.

**What it does not cover.** libVLC runs in the `--media-host` child and resolves stream hosts with the system's DNS. So
with the setting on, a stream's lookups still go to the system resolver, and ST8's line says "Video streams use your
Mac's DNS."

- [ ] Find whether the media host can resolve through the same resolver: libVLC's own options, or the app handing it
      addresses it resolved, which has to keep TLS and the host header right.
- [ ] If it can, the line under the setting goes, in the same PR.
- [ ] If it cannot, record why here, and the line stays.

## KMP-75 — Downloads in the Mac's sidebar, over `KMP-52`'s service · **built 2026-11-08**

What `KMP-54` left for wiring:
- **The fourth sidebar row, with ⌘4** (Ctrl+4 elsewhere): `TopLevelDestination.Downloads`, with `CloudStreamIcons.Download`
  and `title_downloads`. The Go menu lists it with its key.
- **`DownloadsHost` and `DownloadSeriesHost`** (`desktopApp/.../downloads/DesktopDownloadsHost.kt`), over
  `DesktopDownloads`:
  - Delete goes through the page's own sheet. `ConfirmDelete` names exactly what is asked about. The sheet's Delete
    calls `delete` (titles) or `deleteEpisodes` (D4); its Cancel and Escape remove nothing.
  - Show in Finder is offered only for what `fileOf` finds on the disk, a finished file or a series' folder, and selects
    it with `Desktop.browseFileDirectory`.
  - Free space on D4 is the source's own `freeBytes`, from the same volume as the titles page.
  - A file removed in Finder is noticed on arrival: `refresh()`, then `Reappeared`.
- **`main()` makes the one `DesktopDownloads`** (`~/Downloads/CloudStream`, `~/.cloudstream4/downloads.json`) and closes
  it in a shutdown hook, so a running download comes back Paused. `DesktopApp`'s `downloads` defaults to null, so no test
  reads or writes the real downloads file; the page is then empty.
- **Sizes** are formatted Finder's way: decimal units, two decimal places from GB up, with the locale's decimal mark.

**Hidden:** D1's Play from link and Download queue, which have no Mac page behind them (the PM's call: no dead
controls). See the reduction in `KMP-54`.

**Fixed on the way: a press on a row's hover verb was also the row's.** `DesktopVerb` consumes its press now, and both
row handlers skip a consumed one.
- Before, a click on a title and then, within the double-click time, on its Delete verb read as a double-click. It opened
  the title and asked nothing.
- On D4, a click on an episode and then on its Delete verb played the episode.
- Each has a test, proved by mutation. With the row's check removed:
  - the titles test fails `expected:<[DownloadTitleId(value=2)]> but was:<null>`;
  - the series test fails with `[Play(id=…1), ConfirmDelete(id=…1)]`.

**Review fix: the view models' lifetimes.** Both were made with `remember` in their hosts, while their readers ran in
`DesktopApp`'s window-long scope. Every visit to Downloads or a series page left another set of readers running, each
rebuilding the page as the service ticked, and D1's state was made again with nothing in it.
- **D1's view model is `DesktopApp`'s**, made once beside the library's, so the selection and `returnTo` outlast a series
  page, another tab, or a visit to Home.
- **D4's runs in a child of the window's scope**, cancelled when the page leaves. A delete its sheet confirmed runs in the
  window's scope itself, so leaving does not cut it short.
- Two tests, each failing with its fix undone:
  - the selection survives Downloads → Home → Downloads, and a series opens and closes over the list (undone, the
    row's fill reads as unselected);
  - a series page's readers stop when it is left (undone, they are still running after 20 s).
- **The Mac does not draw `returnTo`.** No desktop Downloads file reads it, so there is no return to the row's place
  yet. It is kept in state for when one does: `KMP-83`, not built here.
- **The same check on the other pages:** Library and Search are made once per window, as now Downloads is. Detail is
  kept per title while the player is over it, and Back's `forget` stops its link search. Home is made again only when
  what is installed changes; it holds no reader that outlives its loads.

**Not here:**
- **Playing a downloaded episode** (D4's Play) is `KMP-74`, built: the app hands the media host one open descriptor.

**Tests** (`DownloadsHostTest`, against the real service and a loopback server in a temporary folder):
- A film is downloaded; ⌘⌫ asks and Cancel keeps the file; the row's menu Delete, then the sheet's Delete, removes the
  file and its record.
- ⌘4 and the sidebar's fourth row open Downloads through `DesktopApp`.
- The size format.
- The list's view model outlives a visit elsewhere, and a series page's readers stop with it (the review fix above).

**The PM asked whether a Return-cancel breaks the row's Delete verb in the same window.** It does not:
- `after Return cancels the sheet, the row's Delete verb asks again` passes.
- What looked like it in `DownloadsHostTest` was its presses all stamped time 0, which the row read as a double-click.
  That is how the verb bug above was found.

**Flaky on master when this was built, not touched here:** two `DesktopDownloadsTest` (`KMP-52`) tests, since fixed as
`BUG-53` (`#231`, on master before this merged):
- `a series page reports the same free bytes as the titles page` reads the live volume twice. It failed 3 of 5 lone runs
  on this branch.
- `after a restart a resolver with no VIDEO link…` failed once in a full gate run.

## KMP-92 — An unstarted show's Play starts S1E1, not a special · **TV and the Mac in #265; the phone in pr #272** · the owner's ruling, 2026-11-08

## KMP-99 — The Mac downloads' artwork: every row's poster, and D4's header · **in review**

**Status:** in review · `:feature:downloads`, `:desktopApp` · from the design audit: `DownloadTitle` had no poster and
neither host passed `artwork`, so every row's art and D4's header were empty in the real app.

- `DownloadTitle.posterUrl`, `DownloadSeriesContents.posterUrl` and `SeriesState.posterUrl`, all defaulting to null, so
  Android's source is unchanged. `DownloadShelf.title` takes it.
- `DesktopDownloadRequest.posterUrl` goes into `StoredEpisode.posterUrl`. Kept once `Done`, as the title's name is:
  it is what the row shows, not how to fetch anything. `KMP-52`'s ruling is about the media link (URL, headers,
  referer), and the library already keeps its titles' posters the same way. A file written before the field reads
  as null; the format stays 3.
- Only plain `http(s)`, with no space or control character and at most 2048 characters, is kept (`posterOf`), on the
  way in and again on reading. `PosterImage` checks the host when it loads, as for every other poster.
- A title's poster is its newest episode's. Both hosts pass `artwork` through `PosterImage`.
- **Not yet fed:** nothing in the app enqueues a download yet; tests are the only callers. The caller that does
  (a Download button on the title page) passes the page's `posterUrl`.

### Tests

- `DesktopDownloadsTest`: a film's and a series' posters are their rows', the series' is D4's, the film's is in the
  file once `Done`, and both read back after a restart; `posterOf` keeps http(s) and refuses `file:`,
  `javascript:`, `data:`, scheme-relative, a space, a newline and an over-long address.
- `DownloadShelfTest`: the row carries the poster it was given, none by default.
- `DownloadSeriesViewModelTest`: the poster comes with the contents, and a read without one clears it.

## KMP-92 — An unstarted show's Play starts S1E1, not a special · **in pr #265** · the owner's ruling, 2026-11-08

Found while fixing `#244`'s review. With no resume and no title-level `playData`, `TitleDetail.primaryData` was
`episodes.firstOrNull()`: whatever the provider listed first. A provider that lists specials first therefore made
Play start a special, and since `#244` the page also opened on the Specials chip.

**The owner's rule.** An unstarted show starts S1E1: the lowest season above 0, and its first episode. With no
numbered season at all (only specials, or only unnumbered episodes, as some anime have), it is the provider's
first episode that is not a special, and with only specials, the first of them.

**Where.** `primaryData` is the one place TV and the Mac ask what Play plays. It now ends in
`firstEpisodeToPlay()` (`DetailSeasonGroups.kt`). "First" means the lowest episode number in that season, with
unnumbered episodes after and list order between equals. A resume, then a film's `playData`, still come first.
On TV the chip follows on its own through `shownSeasonIndex`, and so does the marked row. The Mac's episode list
opens on the season of the episode `primaryData` names when there is no resume (`DetailScreenDesktop`'s `TitlePage`),
so its list and its Play agree. Continue Watching's fallback on the Mac, `PlayRequested(resume.episodeData ?:
primaryData)` in `Main.kt`, follows too.
- **The phone** never reads `primaryData`; its follow-up is below.

**The phone follow-up** (the PM, 2026-11-08: the owner's ruling covers the phone too). The phone and emulator
layouts open the legacy `ResultFragment`, over `ResultViewModel2`. Measured before changing it:
- **An unstarted series has no Play there.** The primary button is posted only for a film. A series shows Resume only
  once something was watched (`getLastWatched`), and that resume is untouched. The rule can only decide which season
  the page opens on.
- **The page opened on the season nearest the remembered one, else nearest 1.** The remembered season is saved the
  first time the page shows, so a remembered season always wins and stays. With none, it aimed for 1. That already
  gave Season 1 when a show has one. **With no Season 1**, Specials (0) and Season 2 tie on distance, and
  `minByOrNull` takes the first the provider listed. So a show whose specials come first opened on the specials, and
  remembered them. This page also files episodes with no season under 0.
- **The fix.** With no remembered season, the page aims for the season of `firstEpisodeToPlay()`. That function is now
  public, so the phone and the Compose pages share one rule. The choice is moved verbatim into `openingIndexer`
  (`OpeningSeason.kt`) so it can be tested. A remembered season and the dub preference work as before.
- **Tests,** `OpeningSeasonTest`, 5 cases:
  - no Season 1 with specials first opens Season 2; on the old target this fails, `expected:<2> but was:<0>`;
  - with Season 1, it still opens Season 1;
  - a remembered season wins, Specials included;
  - only specials, or no seasons, opens the one group there is;
  - `startSeason` agrees with `firstEpisodeToPlay`.

**Tests.**
- jvm (`DetailSeasonGroupsTest`):
  - specials listed first, S1E1;
  - a lower season and episode win over list order;
  - only specials, the first special;
  - no seasons, the provider's first episode, and an empty list gives null;
  - no numbered season with a special listed first, the first unnumbered episode;
  - within a season, a numbered episode before an unnumbered one listed first;
  - a resume and a film's data still win.
- Mac (`DetailScreenTest`): an unstarted show with a special listed first opens its list on Season 1, and Play Episode
  plays S1E1.
- Device: `SeasonGroups` is back to listing a special first, and `anUnstartedShowOpensOnSeasonOneNotTheSpecialListedFirst`
  asserts Season 1 opens with "One One" marked.

## KMP-90 — The Mac's Settings window, part 1, from `DS-24` · **in pr #266**

**Status:** in pr #266, on master after train 11, which carried #256 (`I18N-20`'s keys) and #229 (`KMP-75`, the Mac's
downloads engine in the window).

**What part 1 is** (the PM's calls, 2026-11-08): the window, its sidebar and its search, and only the rows that take
effect. No control in the window does nothing. A pane is in the sidebar only once it has a row that works, and search
finds only rows that exist. The `DS-24` boards stay the target; every row they draw that is not here is listed below,
with what it waits for.

- **The window.** Settings… in the Mac's app menu on ⌘,, through `Desktop.setPreferencesHandler`. It is a second
  window, 760 wide at every height, 600 tall by default, and never under 480 (ST9). Its title bar is transparent over the
  content, so the sidebar runs the full height with the traffic lights in it. Off a Mac there is no such menu item, so
  the window is not reachable there yet.
- **The sidebar** lists Downloads, the one pane with a working row. A search keeps the panes whose name or a row's words
  match, and marks the matching rows. With none, it says so ("No results for “%s”", ST7). Escape empties it.
- **Playback: Play the next episode is not a row yet** (review of #266; **added back by `KMP-94`**, with its reader). Nothing on the Mac reads it until
  desktop-session-1's Up next (`DS-23`) lands, so a switch for it would do nothing, and Playback stays out of the
  sidebar. The setting is kept: `autoplay_next_key`, on by default, `SettingsRepository.setAutoplayNext`, and
  `PlaybackSettings.autoplayNext`, the `StateFlow` in `:feature:settings` commonMain that Up next takes as a parameter
  defaulting to `PlaybackSettings.Default` (agreed with that session through the PM). **The Up next PR adds the row back,
  with the Playback pane, in the same PR as its reader**, along with `SettingsRow.PlayNextEpisode` and
  `SettingsAction.SetAutoplayNext`.
- **Downloads: the folder** by its name only (ST6), with Show in Finder while it exists. Missing, the line says so and
  the button is not offered.
- **Downloads: Downloads at once**, under Android's `download_parallel_key`, 1 to 5, 3 by default. The engine reads it at
  every start, and `limitChanged()` starts what a raised limit now allows. `Main.kt` builds the settings before the
  engine, passes `runningLimit = { settings.downloadsAtOnce.value }`, and calls `limitChanged()` on every new value.

**Where things live.** `:feature:settings` holds the screens and the read interfaces (`PlaybackSettings`,
`DownloadSettings`), in commonMain, under the iOS canary. It is not `:core:settings`. That module is Android's
`AppSettings` over Mihon's `PreferenceStore`, with `:library` in its commonMain, so it is canary-exempt; screens go in
`:feature:*` and storage in the host. The Mac stores the values in `~/.cloudstream4/settings.properties`: owner-only,
written temp-then-move, capped at 16 KiB (read as at most 16 KiB + 1 byte), with anything malformed or out of range
reading as its default. `~/.cloudstream4` is created owner-only (0700) if this is the first to create it; one that
exists is left as it is. A change goes through `MutableStateFlow.update`, so two at once never lose one, and the views
and the file always end on the latest. Preferences
only: a token or a password never goes in that file. Those go to the Keychain, when there is one.

**New in the design system.**
- `DesktopDimens.RowControlHeight` (32), the compact control in a row (the PM's call on `DS-24`).
- `DesktopSwitch` and `DesktopPopupButton`, with their geometry in `DesktopControlDefaults`. Nothing draws the switch
  in part 1; Play the next episode is its first row, with Up next.
- `CloudStreamIcons.Selector` (Tabler `Selector`), the pop-up button's mark. Its row in `ui-components.md`'s icon table
  is in #259, the PM's rules PR.

**Deferred rows, each with what it waits for:**

| ID | Rows (`DS-24`) | Waits for |
|---|---|---|
| `KMP-90b` | General: app language | An in-app locale switch on the Mac; today the app follows the system |
| `KMP-90c` | General: check for updates at launch, Check now | A Mac updater (`DEC-03`, distribution) |
| `KMP-90d` | General: Run setup again; Help and community; Disclaimer | A Mac setup flow; the Help menu; a disclaimer page |
| `KMP-90e` | Appearance: poster size | Built (below) |
| `KMP-90e` | Appearance: title under the poster | The Mac always draws the title under. Off means the title on the art (Android's `bottom_title_key` false), which needs an overlay and scrim design |
| `KMP-90e` | Appearance: badges, hide quality in search | Labels to show: `DesktopSearchSource` sets `qualityLabel` and `dubLabel` to null. First draw them, resolving `SearchQuality` and `DubStatus` through the `quality_*` strings; then each row is a switch |
| `KMP-90f` | Appearance: trailers, cast, filler episodes | The Mac title page drawing them; it draws none today |
| `KMP-90g` | Appearance: Random button | Built (below) |
| `KMP-90g` | Appearance: suggestions while typing | A suggestion source: the Mac passes none (`SearchScreenDesktop`'s `SearchContent.Suggestions` branch) |
| `KMP-90h` | Playback: Offer Skip intro, update watch progress | The Mac player reading them |
| `KMP-96` | Playback: seek amount | Built (below), the first slice of `KMP-90h` |
| `KMP-90i` | Playback: source order (Edit) | A source-order editor |
| `KMP-90j` | Playback: network buffer, disk cache, clear cache | libVLC's options from the host, and a cache to clear (hash-keyed, never by URL) |
| `KMP-90k` | Downloads: connections per download, Choose folder | The engine taking a per-download connection count, and a chosen root |
| — | Subtitles, Extensions, Accounts and Privacy, Network, Backup, Advanced | Part 2; Accounts and Backup wait on the Keychain decision |

**Tests.**
- `:feature:settings` commonTest: parsing, the defaults, out-of-range values, and search. A pane's name finds its pane
  and marks no row, and a word for a row that is not built, such as "episode", finds nothing.
- `:feature:settings` jvmTest: the repository writes every change at once and a new one reads it back; an unchanged
  value is not rewritten; a failed write keeps the value and reports only the exception's class; two changes on two
  threads at once are both kept in memory, in the views and in the stored map, over 500 rounds.
- desktopApp: `FileSettingsStorageTest`, covering owner-only file and created directory, an existing directory left
  alone, no temporary file left, too large, exactly at the cap, bytes that are not UTF-8, malformed lines, and a value
  that would break the file.
- desktopApp: `SettingsWindowTest`, through real pointer and key events. Each control writes and reads its setting; the
  folder shows its name, never a path, and no Show in Finder while missing; search finds and empties; the 760×480
  window fits every pane.
- desktopApp: `DesktopDownloadsTest`, `a changed Downloads at once applies to the next start`. Raised from 1 to 2, the
  waiting film starts beside the running one. Lowered back to 1, both keep running and a third waits.
- Renders with `-Pcloudstream.settings.renders=<dir>`.

## KMP-66 — The TV playback panels, artboard 7e (queue `Q25`) · **measured 2026-11-07**

**Status:** in pr, stacked on `KMP-64` (#189). Board 7e of `design/TV Detail Player.dc.html` on `Television_720p`, API 36.

### What it adds

- **SOURCE, SUBTITLES and AUDIO open 7e's side panels** over 7d, the HUD staying mounted under them. Pressing a row is the commit: no Apply, no Cancel. `Done` is pinned under 7e's rule. BACK goes up a level or closes, never out of the player, and focus goes back to the selector that opened the panel. Focus enters on the active row (what plays, the track in use), or the first.
- **Source:** the links in the quality profile's order, the playing one kept even when the profile would hide it and marked three ways (marker, fill, `Playing`); a failed link keeps its row (`failed`, the failed marker, `Retry`); `N hidden links` and `Switching keeps your position: resumes at 42:13` in the header; the quality profile's row opens the old editor. **A switch keeps the position and the play state**: playing stays playing, paused stays paused, both tested.
- **Subtitles:** Off, the groups (a group's variants are a second level in the same panel), `Load from file`, `Load from Internet` when a provider is on, and `Subtitle Settings`, a level with the three editors that today live in three places: style, sync and encoding.
- **Audio:** the tracks, committed with no reload (track overrides); `Video tracks` as a second level when there is more than one.
- **Empty** is `Retry all sources` first; **offline** is a line in the header, no row moving.
- 9 new keys in all 62 locales, none a plural.

### Behaviour changes

| Before | Now | Why |
|---|---|---|
| A long press on a source copied its URL to the clipboard | No counterpart on the panel; the old dialog keeps it with `TvPlayerHudSwitch` off | A row never carries an address (app-10's ruling); some users relied on it |
| Rows were picked, then `Apply` | Pressing a row commits | 7e; a dismiss can no longer swallow a pending edit |
| Opening the dialog paused playback | The panel opens over playing video | 7e draws it over still-playing video |
| Sync, encoding and style were three separate places | One `Subtitle Settings` level opens each | 7e's "single door" |

### Security

`TvPanelRows` is the panels' boundary, as `toHudState` is the HUD's: a link and a subtitle go in with their URL and headers, and only a display name and quality come out, through `hudLabel`. Tested with markers in the URL, referer and headers, and on the real path with a file whose path carries a marker.

### Reduced from the board

- **A failed row says `failed`, not `failed · code 2004`:** the player records that a link failed, not why.
- **Loading, not Partial:** the player has no "still looking" state beyond loading, so there is no `Keep looking` row.
- **No source count line:** `4 sources · all shown` would be a plural in 62 locales; the hidden count and the resume line are kept.
- **The failed row's title is not dimmed;** the marker says it.
- **The style editor, online search, sync, encoding and the quality-profile editor stay the old dialogs** (undrawn).

### Measured on the way

- **A switch could not keep the play state by passing it.** Releasing the old player clears its playing flag only when it has to pause it: playing, the new player always loaded paused (the old dialog plays again on dismiss); paused, `autoPlay`'s default started it. Both halves are needed: `autoPlay = wasPlaying`, then `Play` if it was playing. `loadLink` takes `autoPlay`, defaulting to the old behaviour.
- **BACK left the player.** On API 36 BACK goes to the dispatcher, not the key listener, and the dispatcher asks the newest callback first: one added at start was passed over. It is added again each time a panel opens, as `SearchTvFragment`'s is.
- **Focus landed on `Done`** when a panel opened before its lazy rows existed; the panel asks again a frame later.
- **The sixth subtitle row sat cut against `Done`** until the list ended at 7e's rule.

### After the rebase onto train 6

- **The list scrolls by whole rows** through `RowScroll` (#176), with the focus ring's bleed kept inside the list.
- **`BUG-36`'s opener:** the panel host is handed the selector that opened the panel, latched through the exit. Measured: the HUD without the opener already passes the new focus test, because no lazy list sits between the content and the selectors. So the opener is a guard, not a fix of something seen here.
- **Two fast BACKs:** the panel's callback now asks the panel itself whether it is open, not `panelOpen`, which reaches its collector later, and passes a BACK on when it isn't. Measured: two BACKs sent from plain main-thread code already left the player on the old code, since the collector runs in between. Sent from one coroutine on `Main.immediate`, the old code swallowed the second ("still in the player") and the new one leaves. Separate key presses on a real remote come one per main-thread turn, the first case, so this guards a case not seen from a remote.
- **`player_panel_numbered`** (`%1$s %2$d`), so a translation can place the number; the sites are listed under `KMP-65`.

### Tests

- `TvPanelRowsTest` (`:app` JVM, 3): row words; no URL, token or header in a source row or a subtitle row; address names dropped to the fallback.
- `PlaybackPanelTvDeviceTest` (device, 10): SOURCE opens the panel with focus on the playing row, Up/Down only, Done returns focus to SOURCE; a press is the commit; SUBTITLES opens its own; closing a panel gives focus back to the AUDIO or SUBTITLES selector that opened it, compared by node, since AUDIO's value and the Audio panel's first row read the same; seven rows: the five that fit don't move the list, and each past the fifth moves it by one whole pitch, whole between the header and the rule (on the pre-`RowScroll` list it fails: "A row that fits scrolled Arabic out of the list"); screenshots `playback-panel-source`, `-subtitles`, `-subtitle-variants`, `-audio`, `-empty-offline`, each looked at.
- `TvPlaybackPanelsNavigationTest` (`:app`, the real path, 5): one episode from two sources, each a silent local copy (-91 dB, volume 0). Switching while playing keeps playing at the position; switching while paused stays paused at the position; BACK closes the panel and stays in the player; two fast BACKs close the panel and then leave; no path on screen.
- `KMP-64`'s own: HUD device 14/0 with its six screenshots unchanged, real path 4/0, `TvHudFactsTest` 8/0 with the review's title cases (dotted words such as `Vol.II` kept).

### Not verified

`TalkBack`, a real remote, a real provider's links (two local copies only), the video-track level with real multi-track media, online subtitle search from the panel, and the least-confident translations: `ti`, `am`, `ckb`, `my`, `or`, `as`, `mt`, `apc`.

### Published API

Additions only. `app/api/classes-jar.api` +92 / −0 over master after the rebase: 10 `R$string` fields, the panel hooks on `FullScreenPlayer` and `GeneratorPlayer`, `PanelStep`, `TvPanelRows`, and `TvPlayerHud`'s new constructors; its six-argument one from #189 is kept by `@JvmOverloads`.

## KMP-90g — Settings' Appearance pane, its first row: Random Button · **in review (#273)**

**Status:** #273, on master after train 12. The PM's call, 2026-11-08: one PR per row, Random first, then Poster size
(`KMP-90e`).

- **The row.** Appearance enters the sidebar, before Downloads as `DS-24` orders it, with one group, Home and Search
  (`settings_home_search`), and one switch, Random Button (`random_button_settings`, reused). No line under it: the
  board's "On Home and Library." is untrue on the Mac, whose Library has no Random, and no new string is needed.
- **The setting.** Android's `random_button_key`. On by default, where Android's is off: the owner approved the switch
  on 2026-11-08, and it overrides the 2026-11-05 "always shown" ruling on the Mac only (noted in `design-system.md`
  where that ruling is). Read through `AppearanceSettings.showRandom` in `:feature:settings` commonMain, beside
  `PlaybackSettings` and `DownloadSettings`.
- **The reader.** `HomeScreenDesktop(showRandom)`, default true, so every other caller is unchanged. `HomeHost` collects
  the flow and `DesktopApp(appearance)` passes `Main.kt`'s `SettingsRepository`. Off, the top bar has no Random; Reload
  stays.

**Tests.**
- `SettingsValuesTest`: default on; `false` reads back under `random_button_key`; anything else reads as on.
- `SettingsSearchTest`: "random" finds Appearance.
- `SettingsRepositoryTest`: written, read back by a new repository, and seen through `AppearanceSettings`.
- `SettingsWindowTest`: on by default, no "On Home and Library." line, a click writes `false`, and a new window over
  what was stored shows it off.
- `HomeScreenTest`: off, nothing is named Random and Reload still acts; turned on, Random is back and acts.

## KMP-90e — Settings' Appearance pane: Poster size · **in review (#274)**

**Status:** #274, stacked on `KMP-90g`'s #273, which merges first. The PM approved the widths and the key on
2026-11-08.

- **The row.** Appearance's first group, Posters (`settings_posters`), holds Poster size (`poster_size_settings`, reused)
  as a pop-up of Small, Medium and Large, with Medium as the default. The size words are Subtitles' size keys
  (`settings_subtitle_size_*`, `I18N-20`), which the PM approved on 2026-11-08 and `i18n.md` records under I18N-20's reuse
  list. Both are the size of a thing, and the locales checked agree with Poster size's noun (de, fr, es, it, pt, ru,
  pl, ar). No new string.
- **The setting.** The Mac's own key, `poster_size_desktop_key` = `small` | `medium` | `large`; anything else reads as
  `medium`. Not Android's `poster_size_key`: that is a 0 to 15 slider, 10% a step from its default of 0, so it has
  nothing smaller than the default. `PosterSize` is in `:core:designsystem` (`desktop`), because Home, Search and
  Library read it and none of them depends on `:feature:settings`. `AppearanceSettings.posterSize` reads it.
- **The readers**, each a smallest card width in its `Defaults`, so a bigger size means fewer, wider columns:
  - Search's grid: 128, 160, 200 (`SearchScreenDesktopDefaults.cardMinWidth`).
  - Home's poster rows, bookmarks line and skeleton: 128, 160, 200 (`HomeScreenDesktopDefaults.portraitCardMinWidth`,
    through `columns`). 16:9 rows keep 280, because the setting is about posters.
  - Library's grid: 160, 200, 240 (`LibraryScreenDesktopDefaults.cardMinWidth`), a step wider, as Library already was.

  Medium is every screen's width before the setting, so a default window draws as it did. Each screen takes
  `posterSize`, defaulting to Medium, and each host collects the flow.

**Tests.**
- `SettingsValuesTest`: every size round-trips under `poster_size_desktop_key`; a bad value reads as Medium; Android's
  `poster_size_key` is not read.
- `SettingsSearchTest`: "poster" finds Appearance and marks Poster size.
- `SettingsRepositoryTest`: written and read back by a new repository.
- `SettingsWindowTest`: the pop-up opens on Medium, choosing Large writes `large`, and a new window shows Large.
- `SearchScreenTest` and `LibraryScreenTest`: the cards on the first line at 760 and 1280 wide, for each size, and no
  `posterSize` is Medium's count. Search: 5/4/3 and 8/7/5. Library: 4/3/2 and 7/5/4.
- `HomeScreenTest`: `columns` per size at both widths, 16:9 rows unchanged at every size, and a row on screen holding
  what its size allows.

## KMP-96 — Settings' Playback pane, its first row: Seek amount · **in review (#277)**

**Status:** #277, stacked on `KMP-90e`'s #274 (which carries #273), the PM's order so the Settings files meet once.
The first slice of `KMP-90h`, claimed by the PM on 2026-11-08, with the key, the amounts and the buttons' names the
PM's calls the same day. Up next (`KMP-94`, #276) stacks on this and adds Play the next episode to the same pane.

- **The row.** Playback enters the sidebar between Appearance and Downloads, as `DS-24` orders them. One group,
  Playing (`settings_playing`), holds Seek amount (`settings_seek_amount`) as a pop-up of 5, 10, 15 and 30 seconds,
  each named by `player_seek_seconds` (`I18N-19`'s plural). No new string.
- **The setting.** The Mac's own key, `seek_amount_desktop_key`, in whole seconds; anything not offered reads as 10.
  Not Android's seek keys: those are the TV's and the phone's double tap, in another range and with another meaning.
  Read through `PlaybackSettings.seekSeconds`.
- **The reader.** `PlayerScreen(seekMillis)`, defaulting to `PlayerScreenDefaults.SkipMillis` (10 s, the step until
  now), so every other caller is unchanged. The left and right arrows and both skip buttons move by it.
  `DesktopApp(playback)` collects the flow while the player is up, so a change applies to the next press.
- **The buttons' names.** "Seek back" and "Seek forward" (`player_hud_seek_back` and `_forward`, which the TV HUD
  uses), not "Skip back 10 seconds": a name with a number would be untrue at every other amount, and joining two strings
  is not translatable. `seek_back_10_seconds` and `seek_forward_10_seconds` stay in the catalogue, unused on the Mac:
  removing them would remove `R` fields, which is the owner's call.

**Tests.**
- `SettingsValuesTest`: default 10; every amount round-trips under `seek_amount_desktop_key`; anything else reads as
  10, and Android's seek keys are not read.
- `SettingsSearchTest`: "seek" finds Playback and marks Seek amount.
- `SettingsRepositoryTest`: written, read back by a new repository, seen through `PlaybackSettings`; 20 is refused.
- `SettingsWindowTest`: the pop-up opens on 10 seconds and offers the four; choosing 30 writes `30`; a new window shows
  it. The sidebar lists Appearance, Playback, Downloads.
- `PlayerScreenTest`: at 30 s, both arrows and both buttons move 30 000 ms; with nothing passed, the step is
  Settings' default.
- `FileSettingsStorageTest`: the file gains the line `seek_amount_desktop_key=10`.

## KMP-95 — The Mac's account and profiles, built from `DS-25` · **95a in pr #278, 95b in pr #281, 95c in pr #286, 95d in pr #290, 95e in pr #296 (AniList in pr #300)** · the PM's task, 2026-11-08

`DS-25`'s boards, built in six PRs (the PM's plan, 2026-11-08):

| Part | What | Waits for |
|---|---|---|
| `KMP-95a` | Profiles: the file, a library each, the launch picker (A1, A15), the sidebar's button and list (A2) | — |
| `KMP-95b` | Managing: Manage (A5), the editor and Add (A6, A6b, A6c), Delete (A8) | 95a |
| `KMP-95c` | The PIN (A3, A3b, A4, A4b, A6d, A7 to A7c), `BUG-55`'s limit over a boot-relative clock | 95b |
| `KMP-95d` | Sign-ins over an in-memory store: Sync (A9), Simkl's code (A11, A11b), A12 to A14, OpenSubtitles and SubDL (A16, A16b) | 95a |
| `KMP-95e` | The browser's redirect for AniList and MyAnimeList (A10): the scheme, the handler, `state` and PKCE | 95d |
| `KMP-95f` | The Keychain through JNA 5.19.1, and Touch ID (A3t), measured on a signed build | The owner's Team ID and certificate |
| `KMP-95g` | Pictures: the bundled ones, and "Use an image address" (A6) | The owner; see 95b |
| `KMP-95h` | Renewing MyAnimeList's token with its refresh token before it expires (about 31 days) | 95e, 95f |

**Rulings** (the owner and the PM, 2026-11-08):
- JNA 5.19.1 is approved for the Keychain: an exact pin, declared only in `:desktopApp`'s account code (95f).
- Touch ID tries the Keychain's biometry access control first. A Swift shim goes back to the owner only if that cannot
  show our reason text.
- **What a profile separates: the library only** (bookmarks, watch positions, resume), as Android's accounts do.
  Settings, downloads, extensions, the home source, the volume and the picture fit are the Mac's.
- **The PIN before 95f** is kept in its own owner-only file, never in `profiles.json` (the reviewer of `#278`): written
  temp-then-move, never logged, shown or in anything shared. It is not hashed: a hash of a 4-digit PIN is reversed at once, so it would only look safer (as `d081`, 2026-11-07). It
  moves to the Keychain in 95f.
- **Sign-ins before 95f** are kept in memory only and lost on quit, and the window says so. None is ever on disk in
  plain text.

### KMP-95a — Profiles

**The file.** `~/.cloudstream4/profiles.json`, owner-only (`DesktopProfileFile`): the profiles' ids and names, and the
one watching. Profile 0 is always there; its name is empty in the file and drawn as `default_account` in the current
language. A file that is missing, too large (64 KB), not JSON or not this shape reads as profile 0 alone, which is what
the Mac had before. A file that parses is cleaned on the way in:
- a negative or repeated id is dropped;
- a name loses control and format characters and is cut to 64 code points, never through a surrogate pair;
- the list is cut to 32;
- profile 0 is put back if missing, and a `current` that is not a profile becomes 0.

**A library each, with no migration.** Profile 0 reads and writes the files the Mac already had (`library.tsv`,
`watch-positions.tsv`), so nothing moves for someone who never adds a profile. Profile `n` has `library-n.tsv` and
`watch-positions-n.tsv` beside them.

**The window** (`ProfilesHost`):
- With two profiles or more it opens on the picker (A1). The current profile's card is ringed, so Return opens it; the
  arrow keys move the ring and a click opens the card under the pointer. The card row is the one focus stop and draws
  the ring itself. Measured: a card's own `clickable` did not take a focus request in the test scene, and a request
  made beside `BoxWithConstraints` ran before its content existed and failed silently.
- Below a 640 content height the heading moves up (A15), so four cards fit in one row at 800×520, and a fifth wraps.
- The sidebar's foot has the profile button (A2), and its list opens above it, the current profile checked. Choosing
  another makes the window again over that profile's library, under its key, and the stack goes back to Home.
- **Nothing of one profile reaches the next** (the reviewer of `#278`). `DesktopDetailViewModels.get` keeps the first
  view model made for a title, whatever library it is handed later. So a switch drops every title's view model, and
  search's, **before** the profile changes (`forgetWindowViewModels`), and the window's dispose drops them again. A
  test opens a title in one profile, switches, checks it was let go, and opens it in the next: no resume, no list. A click outside, the list's Escape and the window's Escape all close it.
  `EscapeHandler.overlay` lets the list take the window's Escape before the screen under it.
- The choice is remembered for the next launch. If it cannot be written it still holds until the app quits, and the
  console says the class of the failure, never a path.

**Not yet:**
- Manage profiles and Add profile: on A1 and in A2's list, they come with 95b. Until then a second profile exists only
  in a file written by hand or by a test.
- A locked profile: 95c.
- Sync services in A2's list: 95d.

**Read from the boards, and where the build differs:**
- Every number is the boards' (`ProfilesDesktopDefaults`, each with the token it mirrors).
- The type roles are the boards' names, but the sizes are the Mac's (`forDesktop`): the boards restated the base
  `CloudStreamTypography`, which the window does not use.
- Manage profiles (95b) will draw `Profile` (`User`), the glyph `ui-components.md` gives it; the board drew a pencil.
- TV's profile line and initials (`AccountSelectScreenTv`) moved, unchanged, to `ProfileText.kt`, so the Mac and TV
  read one copy.

**Tests** (desktopApp jvmTest):
- `DesktopProfilesTest`: no file; owner-only write, read back, no temporary file; an unknown or current choice writes
  nothing; a failed write is reported and still holds; cleaning; a name cut by code points; the cap; too large and not
  this format; profile 0's files are the old ones; a title kept by one profile is not in another's library.
- `ProfileTextTest` (`:feature:account` commonTest): initials, one outside the BMP kept whole.
- `ProfilesScreenTest`, through real pointer and key events:
  - the picker opens with two profiles, and Return opens the current one;
  - the arrows move and Return opens the focused card;
  - a click opens a card, and the choice is remembered;
  - one profile shows no picker;
  - at the minimum window the heading moves up and four cards fit in one row;
  - the sidebar's list opens above its button, and a choice opens Home over the other profile's library;
  - the list's Escape, the window's Escape and choosing the current profile all close it with nothing changed;
  - a title opened in one profile is let go on a switch, and opened in the next shows no resume and no list.
- Renders with `-Pcloudstream.account.renders=<dir>`.

### KMP-95b — Managing profiles

**Manage profiles** (A5) fills the window from the picker's corner button or the sidebar list's new Manage profiles row,
which sits under a rule. In Manage mode:
- the heading says Manage profiles, and each card carries an Edit chip and opens its editor;
- Add profile is the last card, which the ring reaches too;
- Done goes back where Manage came from, the picker or the window. **From the window, Done lands on Home in a fresh
  window:** Manage replaces the window rather than covering it, so a search, an open title and a scroll position are
  gone.

With one profile, the sidebar's list is the way to a second.

**The editor** (A6, A6b, A6c) is a 440 sheet hung from the window's top edge over a dimmed window. It has a name field
and Cancel, with Save as the one primary (Add when adding). Delete profile sits apart at the lead.
- The name opens selected, so typing replaces it; Return in the field saves and Escape cancels.
- Add starts with `Profile N`, N being one more than the profiles there are. The pattern is Android-style, so it is
  filled through `UiString`, which `stringResource` would not do.
- A blank name keeps the one the profile had. On the first profile it goes back to the default name in the current
  language, and when adding it becomes `Profile N`.
- The first profile's editor has no Delete: it is the way back in (A6c).
- Adding does not switch to the new profile.

**Delete** (A8) asks first. Cancel is the primary, and Return and Escape both land on it, as on the downloads sheet;
only a click deletes. Cancel goes back to the editor.
- The profile leaves `profiles.json` before its library files go, and **only if that write succeeded** (the reviewer of
  `#281`). A delete that cannot be written still holds until the app quits, and the profile comes back at the next
  launch with its library, never without it.
- **At launch, a library under an id `profiles.json` no longer lists is removed**, so a deleted profile's history does
  not sit on disk until its id is taken again. **Only over a strict read** (the reviewer of `#281`): every key present
  and none unknown, version 1, and nothing that cleaning would change about the ids (no repeat, none out of range, no
  more than 32, the first profile there). Loading stays lenient. A file that is missing, unreadable, `{}`, another
  version's list or a damaged one reads as the first profile alone, and a sweep over that would remove every other
  profile's library, so none runs.
- Only `library-n.tsv` and `watch-positions-n.tsv` with `n` written as this app writes it (`0`, or 1 to 4 digits with
  no leading zero) are a profile's. `library-007.tsv` is not read as 7.
- Ids are below 10,000. A larger id in the file is dropped, so a hostile `Int.MAX_VALUE` cannot make the next id wrap
  negative, and Add stops at the cap.
- `add` clears any files under the id it takes, so a new profile never starts with what a deleted one left behind.
- Deleting the profile watching makes the first profile watch. The window's view models are dropped first, through
  the same path as a switch.

The window's Escape closes what is on top: a sheet, then the profile list, then Manage.

**Initials only** (the PM, 2026-11-08). The boards' picture row and "Use an image address" are `KMP-95g`:
- **Bundled pictures:** copying Android's profile drawables into `composeResources` is fine when it is done. They are
  the app's own artwork, not new icons.
- **"Use an image address":** fetching an arbitrary URL is a security surface: a network fetch, decoding an untrusted
  image, and a cache. **It needs a security design and the owner's OK.** The PM's leaning is not to build it on the Mac
  unless the owner asks.

The PIN row is `KMP-95c`, along with A6c's "can't be locked" line.

**Where the build differs from the boards:**
- The Edit chip is words only. No glyph means Edit in `ui-components.md` yet, and `icons.md` says a glyph is not drawn
  ahead of its row.
- A6's lead Delete profile is the secondary button, not quiet: `DesktopButton` has no quiet tone.
- The sheets are on `surface`, as the downloads sheet is, so the secondary buttons read as buttons.

**Tests** (desktopApp jvmTest):
- `DesktopProfilesTest`, 21 cases. Add covers the next id, cleaning, a blank name and the cap. Rename covers a blank
  name and the first profile's default. Delete covers that the first is never deleted, that the library goes, and that
  the first profile watches if the deleted one was watching. A new id never inherits a deleted one's files. A delete
  whose save fails keeps the library. The launch sweep removes only unlisted ids' files, and nothing over a file it
  cannot read strictly (nine cases, from `{}` to a 33rd profile). Only a profile's own file name is read as an id. Ids
  are capped.
- `ManageProfilesTest`, 6 cases through real pointer and key events, with names typed through the field's own
  `SetText`:
  - Manage from the sidebar with one profile, adding a second, and Done back to the window;
  - Manage from the picker, a rename with Return, a blank name kept, and Done back to choosing;
  - the first profile's editor has no Delete;
  - Delete asks first, Return and Escape cancel, and a click deletes the profile and its library;
  - deleting the profile watching makes the first watch;
  - the window's Escape closes the sheet, then Manage.
- Renders: A5, A6, A6b, A6c and A8, compared with the boards by eye.

### KMP-95c — The PIN

**The rule** is `BUG-55`'s, as its own `PinLimit` in `:feature:account` (`:app`'s `PinAttempts` is untouched):
- four wrong PINs are free;
- the fifth starts a 30 s wait, each one after doubles it, and the 12th reaches the one-hour cap, where it stays;
- a right PIN clears the count;
- during a wait nothing is compared, the right PIN included;
- the count is per profile and kept across restarts;
- only counts and waits are kept, never a PIN.

**The clock**, measured on the Mac: `System.nanoTime()` counts from boot. Two separate JVMs read the same 2,044,795 s,
which matches `CLOCK_UPTIME_RAW`. So:
- a change to the wall clock does not move it, and a restart of the app does not reset it;
- it does not advance while the Mac sleeps, so a wait can only grow, never shrink;
- a reboot resets it, and then the full wait runs again from boot, as in `BUG-55`.

No JNA is needed for any of this.

**Where it is kept** (D1, and the reviewer of `#278`): two owner-only files beside the profiles, written temp-then-move.
- `pins.json` holds each profile's PIN. It is never in `profiles.json`, and never logged, shown or in anything shared.
  It is not hashed: a hash of four digits is reversed at once. It moves to the Keychain in `95f`.
- `pin-attempts.json` holds counts and waits only.
- A deleted `pins.json` is no PINs (D1). A PIN is a household lock against someone at the window, not against someone
  who can read this account's files.
- **"Couldn't read" is never "no file" once running** (the reviewer of `#286`, who found the fail-open). The read has
  three outcomes: absent, read, failed. On failed (permissions, too large, not JSON, a folder in its place), a locked
  profile's check refuses without comparing or counting a wrong PIN, and it stays locked. The prompt says "Couldn't read
  the PINs on this computer, so this profile stays locked." Set, Remove, forget and the launch sweep write nothing and
  return false, so a read failure never rewrites `pins.json` with one profile's PIN alone, wiping the others'.
  `account_pin_unreadable` is a new key in all 62 locales: the strings baseline's twenty-eighth move.
- **The same at launch** (the PM, 2026-11-09): a `pins.json` that cannot be read then leaves which profiles have a PIN
  unknown, so every profile but the first is locked, with the same reason, until the file reads. Nobody is locked out:
  the first profile never has a PIN. That closes "break the file, then restart".
- **Add adds nothing while a PIN left under the new id cannot be forgotten** (the reviewer of `#286`): otherwise a reused
  id could inherit a deleted profile's PIN once the file reads again. `add` forgets the PIN first, and on false returns
  null before touching anything; the sheet stays open with "Couldn't add a profile while the PINs on this computer
  can't be read. Nothing was changed." `account_profile_not_added` is a new key in all 62 locales: the strings
  baseline's twenty-ninth move.
- An attempts record out of range reads as the strictest wait, never a free one.
- Deleting a profile removes its PIN and count. At launch, the PINs and counts of profiles no longer listed are
  removed, over the same strict read as the libraries' sweep and never over a lenient one.
- **A new profile on a reused id starts with no PIN or count** (the reviewer of `#286`): `add` forgets them beside the
  library, so a profile 2 deleted before never locks the next profile 2.
- **No PIN is held in memory** (the reviewer's nit): only which profiles have one. A check reads `pins.json` for that
  one comparison.
- **A PIN change that cannot be written changes nothing** (the reviewer of `#286`): Set and Remove return to the editor
  with the PIN row as it was and the line "Couldn't save the PIN change. Nothing was changed." So a profile never looks
  locked and then opens after a restart, or the other way round. `account_pin_not_saved` is a new key in all 62
  locales: the strings baseline's twenty-seventh move.
- The first profile can never be locked.

**The prompts:**
- **A locked profile asks its PIN** before it opens, from the picker or the sidebar's list.
- **In Manage, a locked profile asks its PIN** before its editor opens (A5).
- **A3 and A3b:** four cells, each a dot and never a digit. The fourth digit submits, and Backspace takes the last one
  back. A wrong PIN clears the cells and says "Incorrect PIN. Please try again.", with no count of tries left.
- **A4 and A4b:** while a wait runs the cells are off and nothing typed reaches them. The line counts down each frame
  from the boot clock, as `mm:ss` rounded up, so it never reads 00:00 while it runs. Cancel stays live, and so does
  every other profile. The wait outlives closing the prompt and a restart.
- **The editor's PIN row:**
  - **A6:** Off, with Set PIN.
  - **A6d:** On, with Change PIN and Remove PIN.
  - **A6c:** the first profile can't be locked, and there are no buttons.
  - Its line is the caption size: at the Mac's label size, A6d's wrapped beside two buttons.
- **Setting a PIN (A7, A7b):** the PIN twice; a mismatch starts over. A new PIN is not under the limit, since there is
  nothing to guess yet.
- **Changing or removing a PIN (A7c):** the PIN in use first, under the same limit.
- Escape on a PIN step goes back to the editor.

The wait's words are `pin_error_wait`, `BUG-55`'s key from `#230`, taken once train 9 was on master. The one new key is
`account_pin_not_saved`, from the review.

**Not here:**
- **Touch ID (A3t):** `95f`, on a signed build.
- **The PIN row while adding (A6b):** a PIN is set once the profile exists, from its editor.
- **A name typed in the editor is not kept across a PIN step:** the editor opens again with the saved name. Save the
  name first.
- **A "PINs didn't match" line in A7b** (a follow-up, the PM, 2026-11-09): a mismatch starts over silently today,
  because no key says so. The line wants its own `I18N` key in every locale, as its own task.

**Tests:**
- `PinLimitTest` (`:feature:account` commonTest), 6 cases: the numbers, running down, per profile, a restart, a
  reboot, and a store that cannot write.
- `DesktopPinsTest`, 16 cases (6 from the review: a set or remove that cannot be written, a check against the file,
  a reused id, a `pins.json` that cannot be read once running, by `chmod 000` and by a folder in its place, and an Add
  refused while it cannot be read). Each `chmod 000` case assumes the file then really is unreadable, so a run as root
  skips rather than passing for the wrong reason:
  - right, wrong and the count;
  - nothing compared during a wait;
  - the first profile and what counts as a PIN;
  - kept across a restart, in owner-only files, with no PIN in the attempts file;
  - never in `profiles.json`;
  - an unreadable `pins.json` at launch: every profile but the first locked, nothing counted or written, and only the
    file's own PINs once it reads again;
  - an unreadable one once running: a locked profile refused with the right PIN and the wrong, nothing counted, set,
    remove, forget and the sweep refused, and the file unchanged;
  - a folder in its place: unreadable, not empty, and not written over;
  - Add over an unreadable file: nothing added or deleted, then added clean once it reads;
  - an attempts record out of range;
  - delete and the launch sweep, and none over a file that is not strictly this version's;
  - the `mm:ss` clock.
- `PinFlowTest`, 13 cases (4 from the review: Set and Remove that cannot be written; a `pins.json` that cannot be
  read keeping Sam shut with the reason shown, then opening once readable; a launch over one, with Robin, who has
  no PIN, locked too and Alex open; and Add refused with the reason shown) through real key and pointer events on a fake boot clock:
  - A3 and A3b, with no digit drawn;
  - the wait counting down and nothing getting past it;
  - the wait outliving Cancel and a restart;
  - Backspace;
  - Manage asking the PIN before the editor;
  - Set PIN with a mismatch;
  - Change and Remove behind the current PIN;
  - the first profile;
  - Escape back to the editor.
- Red-checked: with a failed read taken as no file again, the three unreadable tests fail; with launch leaving
  profiles open over a failed read, the two launch tests fail; with `add` ignoring `forget`'s false, the two Add tests
  fail.
- Renders: A3, A3b, A4, A6d, A7, A7b and A7c, compared with the boards by eye.

### KMP-95d — Sign-ins, kept in memory until you quit

**What it adds** (`DS-25` A9, A11, A11b, A12, A13, A13b, A14, A16, A16b; the PM's answers, 2026-11-09):
- **Sync services** in the sidebar's profile list opens **Sync for <profile>** (A9), the current profile's sign-ins. Each
  row reads Connected or Not connected and never shows an account name or an id.
- **Simkl pairs with a code** (A11):
  - the code, a QR of the page to approve it on, the page's address, a countdown, and Open, which opens the page in the
    browser;
  - polling at Simkl's own interval until the code is approved;
  - an expired code is struck through and its QR dimmed, and nothing polls (A11b);
  - Get a new code asks for another.
- **OpenSubtitles and SubDL** sign in with an email and password (A16), the owner's exception:
  - the password is masked, with Show, and never prefilled;
  - a refusal keeps the email, clears the password and says so (A16b).
- **Failure** (A13) and **offline** (A13b) use our own words, with no detail from the service. Try again starts afresh.
- **Removing a sign-in** asks first (A14). Cancel is the primary, and Return lands on it.
- **Every way out ends a sign-in at once** (A12, `BUG-49`):
  - leaving a step cancels its poll or the request in flight;
  - an answer that arrives after is never stored;
  - Cancel goes back to A9, focused on the row that opened it.
- A profile card's line names the sync services it is signed in to. Deleting a profile drops its sign-ins.

**Where sign-ins are kept: memory only, until the Keychain arrives in `95f`** (D3).
- `InMemorySignInStore` holds one credential per profile and service. Nothing goes to a file, a preference or a log, and
  quitting forgets them all.
- **The screen says so:** "Sign-ins last until you quit CloudStream." (`account_signins_until_quit`) is on A9, A11 and A16.
  It is a new key in all 62 locales: the strings baseline's thirtieth move, and one `R` field in
  `classes-jar.api`.
- On A16 the password note ("kept on this device only and never backed up") stays, with that line under it (the PM, Q2).
- What each service keeps, as `DS-25` read it from Android:
  - Simkl: its token.
  - SubDL: the API key its login trades the password for, and the email. Never the password.
  - OpenSubtitles: its token, which lasts about 24 hours with no refresh, with the email and password kept beside it to
    renew it (the owner's ruling). Nothing renews it yet: nothing on the Mac uses the token.
- `Credential`, `PairCode`, the password step and the store all print as `<hidden>`.

**The network** (`HttpSignInClient`): the same exchanges as `SimklApi`, `OpenSubtitlesApi` and `Subdl`, over the OkHttp
the Mac already has. There is no new dependency.
- HTTPS to each service's own host. No redirect is followed. Answers are capped at 64 KiB.
- No interceptor, and nothing about a request or an answer is printed. A failure is only its kind: offline, refused,
  failed or pending.
- Cancelling the coroutine cancels the call.
- Simkl's answer is untrusted:
  - only a code of 4 to 16 plain letters and digits is shown;
  - only an https page on simkl.com is shown or opened, and anything else falls back to `https://simkl.com/pin`.
- **Simkl's client id** comes from the `SIMKL_CLIENT_ID` env var, or `simkl.id` in the untracked `local.properties`, as
  `:app` reads it (the PM, Q3). `generateDesktopSecrets` writes it into a generated source under `build/`, and only
  plain letters and digits get there. Without it, Simkl reads "Not available here" and has no button. Nothing is in the
  repo.
- OpenSubtitles' app key and user agent are `OpenSubtitlesApi`'s own, already public in the Android source.
- **A token is checked before it is kept or put in a header** (the reviewer of `#290`): the characters of a bearer token,
  `[A-Za-z0-9._~+/=-]`, 1 to 4,096 of them. A SubDL token with a CR, LF or non-ASCII would have made OkHttp throw
  outside the request's error handling and could crash the window. Now it is a failure. Simkl's, OpenSubtitles' and
  SubDL's key are held to the same check.
- **A11's QR** is `qrcode-kotlin` 4.5.0, the catalog's version that `:app` uses for TV. The owner approved it for
  `:desktopApp` (2026-11-09). It encodes the page's address only, never the code.

**Not here:**
- AniList and MyAnimeList are hidden until `95e` adds the browser's redirect (the PM, Q4). Kitsu is never on the Mac.
- **Nothing uses a sign-in yet.** Syncing progress, and subtitle search through the services, come later.
- The Keychain, so sign-ins outlive a quit, is `95f`.

**Different from the board:**
- **A9 has a "Subtitle services" group** (the existing `settings_subtitle_services`) with OpenSubtitles and SubDL. The Mac's
  Settings has no subtitle services yet (`DS-24` ST3b is unbuilt), so A16 had no way in.
- Show is a toggle that keeps its word. No "Hide" key exists, and none was added.
- The field label says Email for OpenSubtitles as the board does, but OpenSubtitles' login takes a username. Whether it
  also takes an email was not measured.

**Tests:**
- `SyncFlowTest`, 10 cases through real key and pointer events, over a fake client and a fake clock:
  - A9 by presence, AniList, MyAnimeList and Kitsu absent, the until-quit line, and Done;
  - Simkl's code, QR, countdown and polling interval, Open, and approval connecting this profile only;
  - Cancel ends the wait: no poll after it, and an approval after it stores nothing;
  - expiry stops polling, and Get a new code;
  - no client id means "Not available here";
  - A16 and A16b: masked, never prefilled, the email kept, the password cleared, Show, and SubDL keeping no password;
  - A13 and A13b, and Try again;
  - A14, with Return as Cancel;
  - deleting a profile drops its sign-ins, and a restart has none;
  - **the PM's test:** the three sign-ins write no file under the profiles' directory or a sandboxed `user.home`, and no
    output or log line carries the email, password, token or code. Red-checked both ways: a token printed from the store,
    and a file written to `user.home`, each fail it.
- `PairQrTest`: the QR is square, of dark and light cells, the same for the same address and different for another.
- `HttpSignInClientTest`, 8 cases against a loopback server:
  - Simkl's code and the page check;
  - a code with markup refused;
  - no client id sends nothing;
  - a poll pending, then answered with a token;
  - OpenSubtitles: the key sent, the password kept, a 401 refused;
  - SubDL: the login traded for the key, and no password kept;
  - a redirect not followed, a huge or unreadable answer failing, and a closed port reading as offline;
  - a SubDL token with CR and LF failing with nothing sent, and Simkl tokens with LF or non-ASCII failing. Red-checked:
    without the check, OkHttp's `IllegalArgumentException` escapes.
- Renders of A9, A11, A11b, A12, A13, A13b, A14, A16 and A16b were compared with the boards by eye.

### KMP-95e — MyAnimeList through the browser, back through `cloudstreamapp://`

**What it adds** (`DS-25` A10, A12, A13, A13b; the PM's plan, 2026-11-09):
- **MyAnimeList on A9**, first, as the board orders it. Sign in opens the browser on MyAnimeList's own page, and A10
  waits: "Waiting for your browser…", Open again (the same page), and Cancel. There is no password field: the app
  never sees one.
- **The redirect comes back to the app.** The `cloudstreamapp` scheme is in the Mac bundle's Info.plist
  (`CFBundleURLTypes`, through Compose's `infoPlist`), and `main` hands macOS's open-URL events to `BrowserRedirects`
  before the window opens. Only a packaged app has the scheme.
- On success A9 reads Connected, and the card's line names MyAnimeList. A refusal (`error=…`, or no code) is A13. An
  exchange that cannot reach MyAnimeList is A13b.

**What a redirect must carry to be taken** (`BrowserRedirects`; the reviewer's and the owner's rules in `DS-25`):
- **A sign-in from this window must be waiting.** An unsolicited `cloudstreamapp://` URL does nothing.
- **That sign-in's path** (`mallogin`) and **the `cloudstreamapp` scheme**, in the bare form only (the reviewer of `#296`):
  a user part (`x@mallogin`), a port (`mallogin:99`) or a path (`mallogin/other`) is refused.
- **The very `state` it sent**, compared in constant time: 32 bytes from `SecureRandom`, base64url, fresh for every
  sign-in. This replaces Android's `RequestID<n>` counter, which the owner ruled out for the Mac. A wrong or missing
  state does nothing, and the sign-in keeps waiting.
- **Once.** The first redirect taken ends the wait. A query that names a parameter twice is not read.
- **Decoded as RFC 3986 reads a query** (the reviewer of `#296`): `%XX` only, so a `+` in a code stays a `+`.
  `URLDecoder` alone is HTML form decoding, and would turn it into a space.
- **Cancel, Escape or a switch ends the wait at once**, so a redirect that arrives later does nothing (A12).
- **Never logged or stored.** Only the code reaches the exchange, and only the token it trades for reaches the store.

**PKCE:** MyAnimeList allows only `plain`, so the verifier is the challenge and travels in the page's address. It is
fresh for every sign-in and never reused: 96 bytes from `SecureRandom`, base64url without padding, 128 characters.
That is the same construction as `AuthAPI.generateCodeVerifier`, checked again here. `code_challenge_method=plain` is
sent explicitly. The exchange is `MALApi.login`'s form POST, and its access and refresh tokens are checked as `95d`'s are.

**MyAnimeList's client id** comes from `MAL_KEY`, or `mal.key` in the untracked `local.properties`, as `:app` reads it,
into the same generated `DesktopSecrets`. Without it, MyAnimeList reads "Not available here".

**MyAnimeList's tokens expire after about 31 days, and nothing renews them yet.** The refresh token is checked and
held, but unused: until `KMP-95h` (refresh-token renewal) lands, an expired sign-in needs a new one. The store holds
sign-ins only until the app quits (`95d`), so a Mac session never outlives a token today; this matters once `95f`
keeps them in the Keychain.

**AniList** (`#300`, the follow-up to `#296`; the owner's ruling (a), 2026-11-09): its docs describe only the implicit grant for
an app that cannot keep a secret, with no PKCE and no `state` shown, and Android sends none.
- The Mac sends a random `state` and **requires it back**. AniList's redirect puts the token in the URL's fragment, so
  `BrowserRedirects` reads the fragment as well as the query, and refuses a name that appears in both.
- **If AniList does not echo the state**, a redirect with none on `anilistlogin` ends the sign-in as A13 ("AniList didn't
  confirm the sign-in. Nothing was saved."), and its token is dropped. It is said plainly, rather than waiting for ever.
  A state that is present but wrong is still ignored, as for MyAnimeList.
- The token is checked as every token is, and kept alone. It lasts a year, and AniList has no refresh token.
- **Its client id** comes from `ANILIST_KEY`, or `anilist.key` in the untracked `local.properties`, as `:app` reads it.
  Without it, AniList reads "Not available here".
- **Not measured: whether AniList echoes the state.** The owner will run one real sign-in. If it does not echo it,
  AniList is to be marked unavailable on the Mac.
- Tests:
  - `BrowserRedirectsTest`, 2 more: the fragment token with its state and a name in both halves refused; a redirect with
    no state ending an AniList sign-in, while MyAnimeList's keeps waiting.
  - `HttpSignInClientTest`, 1 more: AniList's page.
  - `SyncFlowTest`, 2 more: AniList signing in through the fragment with its own state only; and with no state, A13 and
    nothing kept.
  - Red-checked: without ending on a stateless redirect, the no-state test fails.

**Measured on a packaged build** (`createDistributable`, Temurin 17, this Mac):
- The bundle's Info.plist carries `CFBundleURLTypes` with `cloudstreamapp`, read back with `PlistBuddy`.
- After `lsregister -f`, LaunchServices lists this bundle as the only one claiming `cloudstreamapp:`. The bundle is
  ad-hoc signed, not developer-signed, so **an unsigned build registers the scheme**.
- With the app running, `open 'cloudstreamapp://mallogin?code=…&state=…'` (made-up values) reached its handler. A
  temporary probe, reverted and never committed, wrote only that an event arrived. The handler did not take it, because
  no sign-in was waiting.
- Not measured: a real MyAnimeList sign-in, which needs the app's MAL client id and an account.

**Tests:**
- `BrowserRedirectsTest`, 5 cases:
  - nothing is taken with no sign-in waiting;
  - only the path, the scheme and the very state are taken, and only once, with twelve wrong URLs refused (a user
    part, a port and a path among them, from the review);
  - a cancelled wait takes nothing, and a new sign-in ends the one before;
  - a state and a verifier are fresh, url-safe and the right length;
  - a repeated or overlong query is not read, and a `+` survives decoding.
- `HttpSignInClientTest`, 2 more:
  - MyAnimeList's page: the client, the state, and the plain challenge;
  - the exchange: its form, a 400 as a refusal, and a code with CR/LF sending nothing.
- `SyncFlowTest`, 3 more:
  - A10 end to end: wrong, missing and other-path redirects ignored, Open again with the same state, and the right one
    connecting with its own verifier, once;
  - Cancel ending the wait, with a late redirect doing nothing, and a new sign-in getting a new state and verifier;
  - A13 on `error=`, and A13b on an exchange offline.
- Red-checked: without the state comparison, 3 of these fail; without ending the wait on leaving the step, the late
  redirect is taken and the Cancel test fails; without the bare-form check or the RFC 3986 decoding, their tests fail.
---

## KMP-74 — The Mac plays a downloaded episode: the app opens the file, and the media host gets only its descriptor · **built 2026-11-09**

**Status:** in the PR that adds this entry · taken over from desktop-session-1, at the PM's word

**The owner's ruling (2026-11-09):** "hand it just the one file". The app opens the chosen file itself and passes only
that open descriptor to the media host. The host's sandbox rules don't widen, and it never sees a folder or a path.
`BUG-76` measured why this is needed: the host may read nothing outside its own directory, so a download played by
path gets `frames=0`.

**How the descriptor crosses.** Java starts a process with nothing open above stderr, and the host's three are taken:
commands come in on stdin, frames go out on stdout, and stderr is drained. So nothing can be inherited. And a socket
the host connects to would need a new `network-outbound` rule, which is a wider sandbox. Instead:
1. The app makes a socket pair (JNA). One end becomes the host's stdin: `ProcessBuilder.Redirect.from(/dev/fd/N)`
   opens a duplicate, and the app closes its own copy.
2. The app opens the file and sends it with `sendmsg(SCM_RIGHTS)` and one marker byte. It closes its copy of the file
   at once, sent or not.
3. The host is told only that a descriptor is coming (`-Dcloudstream.mediahost.file=true`, never a path). It takes
   it with one `recvmsg` before anything reads stdin with buffering, because a plain read of that byte would drop it.
   A host given none exits.
4. Commands follow on the same socket, in the same form as ever, written through `FileOutputStream(/dev/fd/<app end>)`.
   The app's end closing is still the host's sign to exit.
5. `OP_PLAY_FILE` (7) carries no text. The host builds `fd://<n>` itself, so the app has no say in what libVLC opens.

**What the app opens (`LocalMedia`).** Only a regular file inside the download folder, reached without a link at any
step below it. The path is checked first: its real path must equal the folder's real path plus the same steps. Then
the descriptor is checked: it is opened with `O_RDONLY | O_NOFOLLOW | O_NONBLOCK | O_CLOEXEC`, `fstat` must say a
regular file, and `F_GETPATH` must give the same path still inside the folder. So a link or a folder swapped in
between the two checks is refused too.

**Accepted residual (owner, 2026-11-09):** the host can ask the kernel for the path of a descriptor it holds
(`F_GETPATH`; measured, it answers). So a compromised decoder could learn the download's folder and file name. It
still cannot open anything by that path, and its home is already named after the account. No descriptor design hides
this on macOS. The only alternative, the app streaming bytes to a libVLC callback stream, was not chosen. The host's
own KDoc says so.

**JNA** is declared in `:desktopApp` at 5.19.1, pinned. The owner approved it 2026-11-09, at the version `BUG-73`
settles on.

**Review (#293):**
- `release()` closes the app's end of the socket at once, rather than at collection.
- `receive` has room for four descriptors, so extras arrive whole and are closed, and only exactly one is taken.
- A host that never became a player is cleaned up on any failure, thrown or not: its process, its directory, and
  both socket ends.

**Second review (#293, at 859284c12):**
- `receive` reads every control message the kernel left, not only the first. It takes a descriptor only from exactly
  one `SCM_RIGHTS` message carrying exactly one, whose aligned length is the whole control length. Anything else is
  refused, and every descriptor in every message is closed, so a second message cannot install descriptors unclosed.
  Measured: macOS refuses a `sendmsg` with two `SCM_RIGHTS` messages (`EINVAL`), so this guards a kernel that would
  deliver one, and is tested over hand-built control buffers.
- The Seek amount window test ends its `/bin/sleep` process and deletes its directory in a `finally`.

**New with the rebase onto `KMP-96` (#277):** the download player steps by Settings' Seek amount, as a stream's player
does: `DesktopApp` passes it `seekMillis` from `PlaybackSettings.seekSeconds`, read while playing. Up next and Play
the next episode do not apply, because a download has no next link.

**Where Play goes.** `DownloadSeriesHost` turns `SeriesEffect.Play` into `DesktopDownloads.playable(id)`: the episode's
file, the download folder, and its name and numbers. The app pushes `Screen.DownloadPlayer` over the series page. Its
name in a screen's text is `PlayableDownload(id=…)`, with no path. The player is the stream player, given a `start`
that plays the descriptor, no address and no source selector. The top band reads `S1:E2 · name · Downloads`. A file
gone since the page was drawn plays nothing, and the page reads the disk again.

**Measured end to end, through the real sandboxed host** (`--check-download-playback <file> [seconds]`, the packaged
build, a scratch `user.home`). Two 20 s 640×360 clips in a folder the host may not read, one with no audio and one
with a silent track:

| | No audio | Silent track |
|---|---|---|
| Played 4 s | frames=25, playing | frames=35, playing |
| Seek to 0.75, then 2 s | position 16575 of 20000, 51 more frames, playing | position 16571 of 20000, 51 more frames, playing |
| Failed | false | false |

- **Control:** the same file by path through `--check-playback`, without the hand-off, gets frames=0 and fails. The
  host's sandbox still refuses the folder.
- **Refusals:** a link to the clip and a folder named like one are both refused before any host starts (exit 4).
- **`lsof` during playback:**
  - The app holds no descriptor on the clip.
  - The host holds it read-only, twice: the one it received and libVLC's duplicate. Its stdin is the Unix socket.
- **Before the code (scratch, the rendered media-host profile):**
  - VLC 3.0.24 played `fd://3` and seeked (`mp4 demux: seeking`); by path, "Operation not permitted".
  - Its log never named the path with `fd://`; by path it did, 14 times.
  - A sandboxed receiver read the whole clip through an `SCM_RIGHTS` descriptor. The descriptor was `O_RDONLY`, and a
    `write` failed.

**Tests:**
- `LocalMediaTest` (12, run against the real kernel, because the structure layouts are written by hand):
  - A file inside opens read-only; writing through it fails; it closes once.
  - Refused: outside the folder, through `..`, the folder itself, a missing file, a link at any step below the folder
    (to outside, to inside, a linked folder), a folder.
  - A folder named through a link, as `/tmp` is, still works.
  - `O_NOFOLLOW`, and `fstat` on a folder.
  - A descriptor sent across a socket pair reads the same bytes; a byte sent with no descriptor is not taken for one.
  - A FIFO is refused, without waiting for a writer (`O_NONBLOCK`).
  - Two descriptors in one message are refused, and both are closed: the process's open descriptors are counted
    before and after (review).
  - Two `SCM_RIGHTS` messages in one `sendmsg` are refused by the kernel (second review).
  - Over hand-built control buffers: one message of one descriptor is taken; two messages, a control length past the
    first message, `MSG_CTRUNC`, and two descriptors in one message are each refused, with every descriptor listed to
    close (second review).
- `DownloadsHostTest` (+3, over the real service and a loopback server):
  - Double-clicking an episode hands over its file, the folder, its name and its numbers, and the name has no path.
  - An episode whose file went since plays nothing and leaves the list.
  - In the window, Play opens `DownloadPlayer` for that episode, opened once, and Escape returns to the series page.
  - In the window, with Seek amount at 30 s, the download player's first command is `OP_PLAY_FILE`, alone and with
    no text after it, and the arrows send 30 000 and -30 000. It reads the bytes the screen wrote, through
    `VlcPlayer.forDownload`, a seam over a process that writes nothing.
- **Mutations, run:**
  - Dropping the regular-file check fails "a folder is refused".
  - Dropping both link checks fails "a linked folder on the way".
  - Unwiring Play fails both host tests.
  - Exactly those four failed, and nothing else.
  - Review round: dropping the regular-file check also fails the FIFO case. Leaving refused descriptors open fails
    the two-descriptor case (206 open before, 208 after).

**Not here:**
- Resume and watch progress for a download. There is no library record to write to; the stream player's
  `WatchRecorder` needs one.
- Playing a film from the D1 list. D1 has no Play on the Mac board.

## KMP-113 — A local media gateway, so every connection libVLC makes is address-checked · **design, in review**

The full fix for `BUG-90`. libVLC checks nothing itself and ignores every proxy setting for https (measured on
3.0.24), so the media host gets gateway urls on loopback, and a sandbox policy that allows nothing else. The gateway
fetches with the app's address-checked client, follows redirects itself, and rewrites playlist urls to itself. The
kernel refuses whatever it does not route, so the rewriter is there for playback, not for safety.

The design is in `docs/plans/media-gateway.md`: the url encoding; the HLS rewriter (DASH a second step) and its
limits; redirects, byte ranges and keep-alive; which headers pass and which never do; per-host cookies; the
gateway's own limits; the failure modes; the test plan; and three measurements to take before the first line of code.
No code until the design is approved.
