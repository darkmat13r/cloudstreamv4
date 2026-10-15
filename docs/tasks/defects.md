# BUG — found and not yet fixed

Each entry carries the measurement that found it. Several were found by an adversarial verification
pass **after** a plausible argument said the opposite, which is why the evidence column matters more
than the description.

---

## BUG-08 — `addDate` reads an offset-less date-time as UTC, not device time · **medium**

**Status:** done · found 2026-10-03 while verifying `I18N-07` locally · `MainAPI.kt`, `Episode.addDate(String?, String)`

**Changes what users see.** Every provider that returns a bare date-time (no offset, no `Z`) now
gets that date moved by the device's UTC offset: `2026-05-17 14:35` was stored as 14:35Z and is now
stored as 14:35 in the device zone (09:05Z on a phone in `Asia/Kolkata`, 18:35Z in
`America/New_York`). Dates that carry an offset or `Z`, and date-only dates, are unchanged.

`MainAPI.kt:2594-2603` (the function starts at 2578; line numbers before the fix) documented that a date-time with no offset "falls back to device timezone",
and implemented it as
`runCatching { components.toInstantUsingOffset() }.recoverCatching { … currentSystemDefault() }`.
Under kotlinx-datetime 0.8.0 the first call **does not throw** on an offset-less input — it returns
a UTC reading — so the documented device-timezone branch was dead for exactly the inputs it was
written for. Date-only inputs still took it, because `toInstantUsingOffset` does throw for those.

Why it returns UTC: `DateTimeComponents.toInstantUsingOffset()` calls `toUtcOffset()`, which builds
`UtcOffset(null, null, null)` when no offset field was parsed, and that is `UtcOffset.ZERO`. Only a
missing *time* makes it throw (`toLocalTime()` fails).

| Input class | `toInstantUsingOffset()` | Produced before | Produced after |
|---|---|---|---|
| Date-time with an offset or `Z` | returns the instant for that offset | that instant | unchanged |
| Date-time with **no** offset | **returns a UTC reading** (does not throw) | wall-clock read as UTC | wall-clock read in the device zone |
| Date-only (with or without an offset field) | throws | start of day in the device zone | unchanged |

Measured: `:library:jvmTest --tests "*EpisodeDateTest*"` failed two tests on any machine whose
offset is not zero, and passed under `TZ=UTC` or `TZ=Etc/GMT`. On this machine (`Asia/Kolkata`) the
actual values were exactly 19,800,000 ms — 5h30m — from the expected ones.

| Test | Expects | Got |
|---|---|---|
| `2026-05-17T10:30:00` | 05:00Z (10:30 IST) | 10:30Z |
| `17/05/2026 14:35` | 09:05Z (14:35 IST) | 14:35Z |

(The first row of this table originally read `04:30Z`. That was an arithmetic slip: 10:30 at +05:30
is 05:00Z, and the 19,800,000 ms gap between 10:30Z and 05:00Z is what was measured.)

**Decision: the documented contract wins.** An offset-less date-time is read in the **device
timezone**. The KDoc said device-local, the tests encoded device-local expectations, and the only
thing that said UTC was an accident of which `DateTimeComponents` overload happens to throw. Making
the code device-local is what makes the implementation, its documentation and its tests agree, and
it removes the need to pin `TZ` for the suite.

**The fix.** The branch is now chosen by what was parsed, not by which call throws: if any of
`offsetHours` / `offsetMinutesOfHour` / `offsetSecondsOfMinute` was parsed, `toInstantUsingOffset()`
is used; otherwise the time is read with `toLocalDateTime().toInstant(TimeZone.currentSystemDefault())`;
a date with no time still falls through to start of day. An explicit `+00:00` or `Z` has a parsed
offset field, so it is not mistaken for a missing offset (covered by a test). The function signature
is unchanged, so `classes.jar` is unaffected.

**Verification.** Compiled and run outside Gradle with the real function body lifted from
`MainAPI.kt` (kotlinc from Gradle's lib dir, kotlinx-datetime 0.8.0 jar, JUnit 4): old logic fails
4 of the new tests under `UTC` and 8 under `Asia/Kolkata`; new logic passes all 34 under `UTC`,
`Asia/Kolkata`, `America/New_York`, `Pacific/Kiritimati`, `Australia/Lord_Howe`,
`America/St_Johns`. The Gradle run is separate.

**Tests.** The pre-existing `addDate(String)` cases that depend on the zone all build their
expectation from `currentSystemDefault()`, the same call as the code. The five date-only ones can
never fail on the zone (they would pass whatever it was) and the two offset-less date-time ones are
the pair that failed off-UTC; nothing pinned a zone, so no case said what a given zone must produce. Added: four round-trip cases in `EpisodeDateTest` (read the stored instant back in the
device zone and compare with the wall-clock in the string), and `EpisodeDateFixedZoneTest`
(`jvmTest`), which pins the JVM default zone per case and states the expected instant as a literal:
offset-less in `Asia/Kolkata` / `America/New_York` (summer and winter) / `UTC`, explicit offset
winning in four zones, `+00:00` and `Z` not read as missing, date-only unchanged.

**Not touched:** `isUpcoming` (`MainAPI.kt`) has the same `toInstantUsingOffset().recoverCatching`
shape and the same comment, but its format only accepts `yyyy-MM-dd`, so it only ever sees
date-only input and the dead branch cannot be reached there. Left alone.

- [x] Decide which behaviour is the contract — device-local
- [x] Make the code, the KDoc and the tests agree
- [x] Dropped the `TZ=UTC` instruction from `CLAUDE.md` and `docs/HANDOVER.md`. The evidence: the
      full four-gate run (`checkKotlinAbi`; `assemblePrereleaseDebug lint check
      -Pcloudstream.strings.guards=fail --rerun-tasks`; `:app:checkExtensionApi`; `iosCanary`) with
      **no `TZ` set** on a machine in `Asia/Kolkata`, and the test JVMs forced to `Asia/Kolkata` by an
      init script, all exit 0 — 678 of 678 tasks executed. `:library:jvmTest` is 562 tests, 0
      failures, and also green under `America/New_York` and `Pacific/Kiritimati`. **Control:** the
      same forced-zone command against a tree whose `:library` is unmodified `master` fails with 548
      tests, 2 failed — exactly `addDateDateTimeNoOffsetUsesSystemTimezone` and
      `addDateCustomFormatDateTimeNoOffsetUsesSystemTimezone`, the two this entry names
- [x] **Worth keeping:** a client-side `TZ=…` on a Gradle command does **not** reach the test JVM when
      the daemon is reused — the daemon keeps the `TZ` it was started with. Pin it on the `Test` task
      (`systemProperty("user.timezone", …)` + `environment("TZ", …)`) when a zone is load-bearing

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

- [x] **Settled 2026-10-14: the KDoc was wrong, and now says minutes.** `MainAPI.kt:2549` documented
      `Episode.runTime` as **seconds** while every renderer treats it as **minutes** — one of them was
      wrong by 60x. The evidence all points one way: `EpisodeAdapter.kt` passes it through
      `runTimeMinutesToSeconds` before formatting, a helper that exists only because the value is
      minutes; the home preview renders it with `duration_format` (`%d min`); `I18N-03` now renders the
      result page the same way; and nobody has ever reported runtimes showing 60x too long, which is
      what the seconds reading would produce for every title in the app. Changing the KDoc rather than
      the code is also the only option that breaks nothing: the data is whatever extensions already
      send, and they are writing minutes

---

## BUG-02 — `maxLines="1"` on a two-line string · **low**

**Status:** done · in pr · `app/src/main/res/layout/fragment_result.xml:652`

The TextView receives `resume_remaining` = `%s\nremaining`, a deliberately two-line string, under
`maxLines="1"`. The TV layout (`fragment_result_tv.xml:298`) uses `maxLines="2"`.

**Fix:** `maxLines="1"` → `maxLines="2"` on `result_resume_series_progress_text` at
`fragment_result.xml:652`, and nothing else. The id is set in `ResultFragmentPhone.kt:751`
(`setText(progress.progressLeft)`); `progressLeft` is built in `ResultViewModel2.kt:2424` from
`R.string.resume_remaining`. The string is confirmed two-line in `values/strings.xml:530`
(`"%s\nremaining"`). No fixed height constrains the view: `layout_height="match_parent"` inside a
`wrap_content` FrameLayout whose size comes from the `WhiteButton` sibling, with no dp height in
the style chain. Verified by reading the XML and the call chain only (no Gradle run, per the
worktree rules); the on-device numbers above are unchanged by this edit and were not re-measured.

**What the user actually saw, and why the recorded `lineCount=2` is not a contradiction.** The
newline never reached the Layout. `TextUtil.kt`'s `fun TextView?.setText(text: UiText?)` does this
before assigning:

    val str = text.asStringNull(context)?.let {
        if (this.maxLines == 1) it.replace("\n", " ") else it
    }

So under `maxLines="1"` the view was handed `2h 22m remaining` on one line — flattened, not
truncated or ellipsised. That is why nothing looked broken enough to notice, and why the
`lineCount=2` measurement below (taken on the Layout for the raw two-line string, not on what the
TextView received) does not disagree. Raising the cap to 2 makes the same code path leave the
newline in, so the phone now renders the two lines the string was written for, as the TV layout
already did.

Measured on device: the Layout produces `lineCount=2`, `measuredHeight=126px` under `maxLines="1"`,
identically for `0m\nremaining`, `45s\nremaining`, `1m\nremaining` and `2h 22m\nremaining`.

Pre-existing and unchanged by #26 — measured width is byte-identical (238px phone / 164px TV)
across all four strings, because "remaining" is the widest line, so the duration text cannot
re-wrap it.

---

## BUG-03 — Double newline in the download notification · **low**

**Status:** done · `utils/downloader/DownloadManager.kt:295, :345` (were :294, :345 when found)

`rowTwoExtra` already ends in `\n` (`" - ${ep.name}\n"`) and is then joined with another `\n`, so
the notification body renders `LinkName\nS1:E4 - Title\n\n50 % (…)`. Confirmed both from source and
from a device capture of `Notification.EXTRA_BIG_TEXT`. Pre-existing; present before and after #25.

**Fix:** the trailing `\n` is dropped from `rowTwoExtra` (`:295`, now `" - ${ep.name}"`); the join at
`:345` is unchanged. Rendered bodies, with `linkName` set and `IsDownloading`:

| Case | Before | After |
|---|---|---|
| `rowTwoExtra` present (`ep.name` set, season + episode) | `LinkName\nS1:E4 - Title\n\n50 % (…)` | `LinkName\nS1:E4 - Title\n50 % (…)` |
| `rowTwoExtra` absent (`ep.name` null) | `LinkName\nS1:E4\n50 % (…)` | `LinkName\nS1:E4\n50 % (…)` (unchanged) |

The same `rowTwo` feeds the pending, failed and done notifications, which lost a trailing newline
too; the `rowTwoExtra`-absent output is byte-identical in every state.

---

## BUG-04 — Dead code and an unused string · **measured; deliberately not done**

**Status:** open, and staying open on purpose · re-measured 2026-10-14 · **not trivial after all**

It was filed as `trivial`. Both halves were measured, and each one costs more than it saves. The
measurements are the deliverable here; the code is unchanged.

### `prettyCount` is dead, and it is on the published extension surface

`PluginAdapter.kt:237` declares `fun prettyCount(number: Number): String?`. The finding was right
about reachability: the only call is at `:185`, inside a `/* … */` block that opens at `:178` and
closes with `}*/` at `:190`, and a repo-wide grep of `*.kt` (excluding `build/`) finds nothing else.

**But it is in the binary contract.** `app/api/classes-jar.api:31094`:

    public final static class com/lagradost/cloudstream3/ui/settings/extensions/PluginAdapter$Companion : java/lang/Object {
            …
            public final fun prettyCount (Ljava/lang/Number;)Ljava/lang/String;
    }

So deleting it is an **ABI deletion**, in the same class as `DEC-08` and `DEC-09`, not a cleanup.
`:app:checkExtensionApi` would fail, and the only way to make it pass is to re-record the dump —
which is exactly what a third-party extension compiled against `prettyCount` would not survive.
Dead in this repository is not the same as unused by an extension.

**Left in place.** If the owner wants it gone, it is one PR that deletes the function, the
`:178-190` comment block and the dump line together, with the deletion called out — and it should
also check whether `DecimalFormat`, `floor`, `log10` and `pow` become unused imports in that file
(not checked).

### `resume_time_left` is unreferenced, and deleting it moves a frozen oracle

Confirmed unreferenced: outside `core/strings/fixtures/` (frozen test inputs) and the generated
`app/src/main/res/`, the key appears in no `*.kt`, no layout, no menu and no `*.kts`. It is declared
in **46** authored files under `core/strings/src/commonMain/composeResources/values*/`, counted two
ways (`grep -l` and `grep -c`); the other 16 authored directories never declared it. It is **not**
in `core/strings/authored-aliases.tsv`.

**The cost is `core/strings/oracle-baseline/`.** That directory is the pre-flip ground truth, and
its `README.md` opens with "**Never regenerated.**" `OracleBaselineTest` compares five of its six
files byte for byte and additionally pins each row count in code
(`decoded.tsv` 32,646 · `emitted.tsv` 34,558 · `lookup.tsv` 66,315 · `requests.tsv` 87 ·
`findings.tsv` 0 · `report.txt` 12). Deleting the key from 46 authored files changes `decoded.tsv`,
`emitted.tsv`, `lookup.tsv` and `report.txt`, so it fails four assertions and requires a deliberate
baseline move — the `I18N-05` procedure: a commit of its own, with every changed cell accounted for.

**That is a bad trade here.** `I18N-05` moved the baseline because translations were actually
*wrong*. Nothing is wrong with `resume_time_left`; it is merely unused, and its entire cost is 46
lines of translation and a few hundred bytes of resource table. The oracle's value is that it has
never been touched casually. Spending it to delete an unreferenced string makes every later
comparison slightly less trustworthy for no user-visible gain.

**Left in place.** If it is ever deleted, it should ride along with a change that has to move the
baseline anyway, and the move should be accounted for cell by cell as `I18N-05`'s was.

### What this entry is for now

Both halves are characterised rather than fixed, in the same spirit as `BUG-06` and `BUG-07`. The
next person does not need to re-derive either measurement, and neither is a drive-by.

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

**Status:** done · was not reproducible · found while designing `PlayerCommand` (`KMP-07`)

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

**Resolution:** the first convention was chosen. `value` is the wire format: `PlayerView`'s receiver
now finds the case whose `value` equals the received int (`firstOrNull`, inline at the call site,
so no member is added to the published class) and returns without calling `handleEvent` on an
unknown code, so a stale `PendingIntent` no longer throws. It was chosen over deleting `value`
because `CSPlayerEvent` is published and its numbers are already on the wire. The published case
order and every case's `value` are unchanged. `CSPlayerEventWireValueTest` pins uniqueness, the
`value` round trip and the unknown-code fallback.

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

