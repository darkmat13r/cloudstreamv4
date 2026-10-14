# BUG — found and not yet fixed

Each entry carries the measurement that found it. Several were found by an adversarial verification
pass **after** a plausible argument said the opposite, which is why the evidence column matters more
than the description.

---

## BUG-08 — `addDate` reads an offset-less date-time as UTC, not device time · **medium**

**Status:** open · found 2026-10-03 while verifying `I18N-07` locally

`MainAPI.kt:2578-2605` documents that a date-time with no offset "falls back to device timezone",
and implements it as
`runCatching { components.toInstantUsingOffset() }.recoverCatching { … currentSystemDefault() }`.
Under kotlinx-datetime 0.8.0 the first call **does not throw** on an offset-less input — it returns
a UTC reading — so the documented device-timezone branch is dead for exactly the inputs it was
written for. Date-only inputs still take it, because `toInstantUsingOffset` does throw for those.

Measured: `:library:jvmTest --tests "*EpisodeDateTest*"` fails two tests on any machine whose
offset is not zero, and passes under `TZ=UTC` or `TZ=Etc/GMT`. On this machine (`Asia/Kolkata`) the
actual values are exactly 19,800,000 ms — 5h30m — from the expected ones.

| Test | Expects | Gets |
|---|---|---|
| `2026-05-17T10:30:00` | 04:30Z (10:30 IST) | 10:30Z |
| `17/05/2026 14:35` | 09:05Z (14:35 IST) | 14:35Z |

**Why nobody noticed:** no workflow sets `TZ`, and GitHub's Ubuntu runners default to UTC, so CI
would be green — if CI ran at all (`DEC-01`). The six passing tests in that file compute their
expected value with the same `currentSystemDefault()` call as the code, so they are tautological
and would pass whatever the timezone.

- [ ] Decide which behaviour is the contract: extension-supplied dates with no offset are currently
      read as UTC, and the KDoc says device-local. Changing it shifts what users see for every
      provider that returns a bare date-time
- [ ] Only then consider pinning `TZ` for the test task — pinning first would freeze the dead
      branch in place and keep the device-timezone path untested
- [ ] Until then: run `:library:jvmTest` with `TZ=UTC`

---

## BUG-01 — `runTime * 60` overflows on untrusted input · **medium**

**Status:** done · `EpisodeAdapter.kt:329` · the `ResultViewModel2.kt` half was already fixed by
`cf3ce1c6` (#29)

**This entry had gone stale.** It was written against two sites. The first, `ResultViewModel2.kt:345`
(`secondsToReadable(dur * 60, "0 mins")`), was replaced by `cf3ce1c6` with
`txt(R.string.duration_format, dur)`, which does no arithmetic, and the entry was never updated. Only
`EpisodeAdapter.kt:329` still had the defect. `ResultViewModel2.kt:2427` was never a `* 60` site.

The defect as found: a runtime in **minutes** was multiplied by 60 and narrowed to `Int`. Measured on
the original `Int` form: `dur = 35_791_395` → `-2_147_483_596` → renders `0 mins`; `dur = 40_000_000`
→ `-1_894_967_296` → `0 mins`. `dur > 0` does not prevent the product going negative. The surviving
site did the arithmetic in `Long` (`times(60L)`) and then `.toInt()`, which truncates and wraps the
same way.

**Why it mattered beyond cosmetics:** `runTime` comes from an extension-supplied `Episode`.
`.claude/rules/dependency-security.md` classes extension output as **untrusted input that must be
validated**. This was that rule's case, not a hypothetical.

**Fix:** `runTimeMinutesToSeconds` (`ui/result/RunTimeSeconds.kt`) takes the product in `Long` and
clamps it to the `Int` range, so an absurd runtime saturates at `Int.MAX_VALUE` seconds instead of
going negative. Negative input stays negative, so it still renders as the empty `completedValue`
exactly as before. `EpisodeAdapter.kt:329` calls it.

**Evidence:** `RunTimeSecondsTest` (6 cases), `:app:testStableDebugUnitTest --tests` with `TZ=UTC` and
JDK 17. Against the unclamped helper: 3 of 6 failed (`35_791_395` / `40_000_000` / `Int.MAX_VALUE`
returned wrapped values; a negative input became positive). After the clamp: 6 of 6 passed. Full
`:app:testStableDebugUnitTest`: 19 tests, 0 failures, exit 0.

- [ ] **Open, deliberately not changed:** `MainAPI.kt:2549` documents `Episode.runTime` as **seconds**,
      while `EpisodeAdapter.kt` treats it as **minutes**. One of them is wrong by 60x. The fix is
      correct under the minutes reading the display code uses; it does not resolve which is the
      contract. If the KDoc is right, every episode runtime renders 60x too long

---

## BUG-02 — `maxLines="1"` on a two-line string · **low**

**Status:** open · `app/src/main/res/layout/fragment_result.xml:652`

The TextView receives `resume_remaining` = `%s\nremaining`, a deliberately two-line string, under
`maxLines="1"`. The TV layout (`fragment_result_tv.xml:298`) uses `maxLines="2"`.

Measured on device: the Layout produces `lineCount=2`, `measuredHeight=126px` under `maxLines="1"`,
identically for `0m\nremaining`, `45s\nremaining`, `1m\nremaining` and `2h 22m\nremaining`.

Pre-existing and unchanged by #26 — measured width is byte-identical (238px phone / 164px TV)
across all four strings, because "remaining" is the widest line, so the duration text cannot
re-wrap it.

---

## BUG-03 — Double newline in the download notification · **low**

**Status:** open · `utils/downloader/DownloadManager.kt:294, :345`

`rowTwoExtra` already ends in `\n` (`" - ${ep.name}\n"`) and is then joined with another `\n`, so
the notification body renders `LinkName\nS1:E4 - Title\n\n50 % (…)`. Confirmed both from source and
from a device capture of `Notification.EXTRA_BIG_TEXT`. Pre-existing; present before and after #25.

---

## BUG-04 — Dead code and an unused string · **trivial**

**Status:** open

- `prettyCount` — `PluginAdapter.kt:237`. Its only caller is at `:185`, **inside a `/* … */`
  block** (the block closes at `:190`). A bare grep makes it look live; it is not.
- `resume_time_left` (`%dm\nremaining`) — no reference in any `.kt` or layout; `resume_remaining`
  (`%s\nremaining`) is the live one

Deleting a string from `values/strings.xml` while 60 translated copies remain leaves orphans that
Weblate then has to reconcile. Worth doing deliberately rather than as a drive-by.

---

## BUG-05 — `:core:storage` limits, disclosed at merge · **medium**

**Status:** open · known and documented in #20, not regressions

- **Two instances over one file lose writes.** There is no file lock; last writer wins. Fine for one
  process, wrong the moment a second one exists.
- **Desktop secrets are stored in plaintext.** The JVM secret store has no keychain integration.
- **Nothing is wired into `:desktopApp`.** The stores exist and nothing calls them.

The first two are the ones that would bite; the third is just unfinished.

---

## BUG-06 — `formatShortFileSize` is not monotonic · **won't fix**

Measured on device: 999 B → `1.00 kB`, but 1000 B → `1.0 kB`. Two significant figures below the
boundary, one above.

This is the Android platform's behaviour, not ours, and matching the rest of the app is worth more
than smoothing it. Recorded so the next person does not treat it as a bug in #25.

---

## BUG-07 — `secondsToReadable`'s negative guard is misnamed · **low, characterised**

**Status:** open, deliberately not changed · `AccountManager.kt:160`

`if (minutes < 0) return completedValue` reads as a negativity test. It is a *minutes-within-the-hour
remainder* test. Measured over `-7200..-1`: fired 7080, missed 120. Inputs `-1..-59` fall through and
render `0m` rather than the caller's `completedValue`.

Left as it is in #26 — characterised rather than redefined, because changing it changes behaviour
for extension callers (the function is on the published ABI).

---

## BUG-09 — picture-in-picture buttons write `value` and read `ordinal` · **latent, measured 2026-10-14**

**Status:** open · not reproducible today · found while designing `PlayerCommand` (`KMP-07`)

`CSPlayerEvent` declares an explicit `value: Int` (`app/.../ui/player/IPlayer.kt:12-25`). The
picture-in-picture buttons are Android `RemoteAction`s, so the command has to survive a trip
through an `Intent`. The two ends disagree about what is in it:

| | |
|---|---|
| write | `getPen(activity, event.value)` → `Intent(ACTION_MEDIA_CONTROL).putExtra(EXTRA_CONTROL_TYPE, code)` — `PlayerPipHelper.kt:100`, `:83` |
| read | `CSPlayerEvent.entries[intent.getIntExtra(EXTRA_CONTROL_TYPE, 0)]` — `PlayerView.kt:505` |

`entries[i]` indexes by **ordinal**. The write sends **`value`**. They agree only because all
eleven cases happen to have `value == ordinal` — `Pause(0)` … `PlayAsAudio(10)`, declared in that
order. If `value` always equals `ordinal`, the property has no reason to exist; the moment anyone
uses it as intended, the PiP buttons invoke a different command than the one drawn on them.

**Three ways it breaks, none of which any check would catch:**

- a new case inserted anywhere but the end,
- a new case given a non-sequential `value` (the obvious thing to do, since `value` looks like a
  stable wire id),
- a case removed, which also renumbers every ordinal after it.

`entries[…]` additionally throws `IndexOutOfBoundsException` on an unknown int rather than
defaulting, so a stale `PendingIntent` from a previous version can crash the receiver.

**Not fixed here**, because the fix is a choice, not a correction: either read by `value`
(`CSPlayerEvent.entries.first { it.value == code }`, with a null-safe fallback) and treat `value` as
the wire format, or delete `value` and use `ordinal` at both ends. The first keeps the ints stable
across reorderings; the second admits they are not stable. `CSPlayerEvent` is in
`app/api/classes-jar.api` (18 occurrences), so its case order is published either way.

**Evidence:** read at `IPlayer.kt:12-25`, `PlayerPipHelper.kt:78-86`, `:96-102`, `PlayerView.kt:500-510`.

---

## BUG-10 — playback turns off TLS verification process-wide, in every build · **high**

**Status:** open · **not changed** — the fix is a behaviour decision, not a correction · found
2026-10-15 while doing `KMP-02`'s security review

`CS3IPlayer.kt:1893-1902`, inside `loadOnlinePlayer`:

```kotlin
if (ignoreSSL) {
    // Disables ssl check
    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    sslContext.init(null, arrayOf(SSLTrustManager()), SecureRandom())
    sslContext.createSSLEngine()
    HttpsURLConnection.setDefaultHostnameVerifier { _: String, _: SSLSession -> true }
    HttpsURLConnection.setDefaultSSLSocketFactory(sslContext.socketFactory)
}
```

`SSLTrustManager` (`ui/player/SSLTrustManager.kt:6-16`) has empty `checkClientTrusted` and
`checkServerTrusted` bodies and returns an empty `getAcceptedIssuers`, so it accepts **every**
certificate chain — expired, self-signed or forged. The verifier beside it accepts every hostname.

**The gate does not gate.** `CS3IPlayer.kt:161` declares `private var ignoreSSL: Boolean = true`, and
`grep -rn "ignoreSSL"` finds exactly two references in that file: the declaration and the `if` at
`:1893`. **Nothing ever assigns it.** It is not a setting, not a hidden preference, not
`BuildConfig.DEBUG`, not a flavour check. `release` is `isDebuggable = false, isMinifyEnabled = false`
(`app/build.gradle.kts:148-151`), so nothing is compiled out. The branch is taken on **every online
playback, in every variant, including release**.

**Three things make it worse than "the player ignores bad certs":**

1. **It is a process-wide static default, not a per-connection setting.** `HttpsURLConnection`'s
   `setDefaultSSLSocketFactory` / `setDefaultHostnameVerifier` are JVM-global.
2. **Nothing ever unsets it.** One playback of one stream disables verification for the rest of the
   process's life, including after the user leaves the player.
3. **It bypasses the opt-in that already exists for exactly this.** `RequestsHelper.kt:25,36` take an
   `ignoreSSL` parameter defaulting to `false` and carry the comment *"Only use ignoreSSL if you know
   what you are doing"*; `MainActivity.kt:1184-1186` wires `app` with `ignoreSSL = false` and a
   separate `insecureApp` with `ignoreSSL = true`, reachable only behind the `@UnsafeSSL`
   WARNING-level opt-in. The player's hardcoded path routes around that design.

**Scope, not overstated.** The player's own HTTP sources are OkHttp, and OkHttp does **not** read
`HttpsURLConnection`'s static defaults. What does read them is any `HttpsURLConnection` in the
process. No repo code opens one directly; the indirect user in this file is `createOfflineSource`'s
Media3 `DefaultHttpDataSource` (`:836`), which is built on `HttpURLConnection`. Third-party libraries
were **not** audited for `HttpsURLConnection` use, so "only Media3 is affected" is **not** claimed —
and the global, permanent nature of the change is the defect regardless of who reads it.

`.claude/rules/dependency-security.md` says "Use HTTPS for all external requests". This satisfies the
letter and defeats the purpose.

**Why it is not fixed here.** Turning verification on is a user-visible behaviour change: providers
serving broken or self-signed certificates would stop playing, and that may well be why the line was
written. That is the owner's call, not a correction to apply silently. Two shapes for it:

- **Scope and reset it** — build the trust-all `SSLSocketFactory` into the one `DataSource` that needs
  it rather than into the JVM defaults, so it cannot leak past the player or outlive the playback;
- **Wire it to the opt-in that already exists** — make `ignoreSSL` follow the same `@UnsafeSSL` /
  `insecureApp` switch the HTTP stack uses, defaulting to `false`.

The first is strictly better and is not much more work; the second is the smaller diff. Either way
`SSLTrustManager` stays in `classes.jar` (`app/api/classes-jar.api:28106`), so neither is an ABI
change.

**Evidence:** `SSLTrustManager.kt:6-16`; `CS3IPlayer.kt:161`, `:1801`, `:1893-1902`;
`RequestsHelper.kt:25,36,45-47`; `MainActivity.kt:1184-1186`; `app/build.gradle.kts:148-151`;
`app/api/classes-jar.api:28106`. Read, not run. See `KMP-02` in `docs/tasks/kmp.md` for the full
review this came out of.

