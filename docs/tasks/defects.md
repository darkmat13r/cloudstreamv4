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

**Status:** done · #54 · `app/src/main/res/layout/fragment_result.xml:652`

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

## BUG-10 — playback turned off TLS verification process-wide, in every build · **high**

**Status:** done · found 2026-10-15 while doing `KMP-02`'s security review, fixed the same day ·
`CS3IPlayer.kt`

`loadOnlinePlayer` installed a trust-everything `SSLSocketFactory` and an accept-any hostname
verifier as `HttpsURLConnection`'s **JVM-wide static defaults**:

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

`ignoreSSL` was `private var ignoreSSL: Boolean = true` with **no assignment anywhere in the repo** —
not a setting, not `BuildConfig.DEBUG`, not a flavour check — and `release` is
`isDebuggable = false, isMinifyEnabled = false`, so the branch ran on every online playback in every
variant. The defaults were process-wide rather than per-connection, nothing ever unset them, and they
routed around the per-client opt-in that already exists (`insecureApp`, behind `@UnsafeSSL`).

**The fix: the block, the field and their four imports are deleted.** 17 lines, no replacement.

### Why deletion rather than the two shapes this entry first proposed

Both earlier suggestions were wrong, and measuring the data-source graph is what showed it.

**"Scope the trust-all factory to the one `DataSource` that needs it" is not implementable.**
`DefaultHttpDataSource.Factory` exposes no way to supply one — `javap -p` on
`media3-datasource:1.9.3` lists 9 setters (`setDefaultRequestProperties`, `setUserAgent`,
`setConnectTimeoutMs`, `setReadTimeoutMs`, `setAllowCrossProtocolRedirects`,
`setCrossProtocolRedirectsForceOriginal`, `setContentTypePredicate`, `setTransferListener`,
`setKeepPostFor302Redirects`) and **zero** mentioning SSL or sockets. The entry called this option
"strictly better"; it does not exist.

**"Wire `ignoreSSL` to the `@UnsafeSSL` switch" is less secure than deleting it**, because the thing
being switched is still a JVM-wide default that nothing unsets. It would move the defect behind a
toggle rather than remove it.

**And the behaviour change the entry was waiting on the owner for does not exist.** The concern was
that providers with broken certificates would stop playing. Measured, that cannot happen through this
block, because nothing on any remote path reads what it set:

| Path | Data source | Reads `HttpsURLConnection` defaults? |
|---|---|---|
| Online video (`createVideoSource`) | `OkHttpDataSource` / `CronetDataSource` | no |
| Remote subtitles (`SubtitleOrigin.URL` → `createOnlineSource`) | `OkHttpDataSource` | no |
| Audio tracks (`getAudioSources` → `createOnlineSource`) | `OkHttpDataSource` | no |
| DRM / DASH (`dataSourceFactory` over `onlineSource`) | `CacheDataSource` over the above | no |
| Downloaded media + `DOWNLOADED_FILE` / `EMBEDDED_IN_VIDEO` subtitles (`createOfflineSource`) | `DefaultHttpDataSource` | **yes** |

- **OkHttp does not consult those statics.** Unzipped `okhttp-jvm` 5.3.2 and 5.5.0 and grepped every
  class: **0** files reference `getDefaultSSLSocketFactory` or `getDefaultHostnameVerifier`. It
  configures TLS on the `OkHttpClient`, which `MainActivity.kt:1184` builds with `ignoreSSL = false`.
- **`DefaultHttpDataSource` does**, indirectly: it never names `HttpsURLConnection` (grep: 0 hits in
  `media3-datasource`) but calls `URL.openConnection()` at 3 sites, and for an `https://` URL the JDK
  returns an `HttpsURLConnectionImpl` that reads both statics.
- **`createOfflineSource` is only ever handed local URIs** — the downloaded file in
  `loadOfflinePlayer`, and subtitles whose origin is `DOWNLOADED_FILE` or `EMBEDDED_IN_VIDEO`.
  `DefaultDataSource` routes `file://` / `content://` / `asset://` to non-HTTP readers and reaches
  the HTTP factory only for an `http(s)` scheme, which a downloaded file does not have.

So the block bought nothing for playback and cost verification for the entire process, including any
third-party library in it that opens an `HttpsURLConnection`. Third-party libraries were **not**
audited, and that is an argument for deletion rather than against it.

### What does change

Verification is restored for the offline `DefaultHttpDataSource` (asked only for local URIs, so in
practice no change) and for every other `HttpsURLConnection` in the process. An extension that needs
a host with a broken certificate still has `insecureApp`, which is per-client and opt-in.

`SSLTrustManager` **stays**: it is published at `app/api/classes-jar.api:28106`, so deleting the class
would be an ABI removal of the `DEC-08` kind. It now has no caller in `:app` and carries a KDoc
saying why it survives and that nothing should install it. **Zero ABI change.**

### The guard, and its control

`app/src/test/java/.../player/NoProcessWideTlsDefaultTest.kt` scans `:app`'s sources and fails on any
`setDefaultSSLSocketFactory` or `setDefaultHostnameVerifier`. A deletion that nothing stops returning
is a weak fix; neither setter has a legitimate caller in this module.

Measured both ways, which is what makes the green run mean anything:

| Tree | Result |
|---|---|
| Fixed | 2 tests, 0 failures |
| `CS3IPlayer.kt` restored from `274503f3` (control) | **1 failure**, naming both calls |

The test also asserts it found >100 Kotlin files, so a change in the test's working directory makes
it red rather than vacuously green — the second test passed in the control run, proving the scan read
the module.

### Android lint already knew — reported, not acted on

Lint reports this on **every** run and has done all along, as **Warning**, which never fails the
build: `TrustAllX509TrustManager` ×2 and `CustomX509TrustManager` ×1, all on `SSLTrustManager.kt`.
`app/lint.xml` does not suppress them; warnings simply do not abort.

Promoting them to `error` would need an `<ignore path>` for `SSLTrustManager.kt`, since that class is
ABI-frozen and cannot be deleted — so the promotion would guard only against a *new* trust-all
manager. **Not done here:** changing a lint severity changes the build gate for everyone, which is
the owner's call. Note too that `BadHostnameVerifier` did **not** fire on the
`{ _: String, _: SSLSession -> true }` lambda in a tree that still contained it, so lint would not
have caught the installation either way. The source scan above is the check that does.

**Evidence:** `CS3IPlayer.kt` before the fix at `:161`, `:1893-1902`, with `createVideoSource`
`:790-831`, `createOnlineSource` `:737-754`, `createOfflineSource` `:833-838`, `getSubSources`
`:1714-1747`, `getAudioSources` `:1754-1768`, `loadExo`'s factory choice `:1257-1273`;
`SSLTrustManager.kt`; `RequestsHelper.kt:25,36,45-47`; `MainActivity.kt:1184-1186`;
`app/api/classes-jar.api:28106`; `javap` on `media3-datasource:1.9.3` and `okhttp-jvm` 5.3.2/5.5.0;
`app/build/reports/lint-results-prereleaseDebug.xml`. See `KMP-02` in `docs/tasks/kmp.md` for the
review this came out of.

---

## BUG-11 — desktop search opened on a blank screen and a blank button · **medium**

**Status:** done · #84 · found 2026-11-02 by the owner, from a screenshot of the packaged app

`SearchViewModel.init` calls `prompts.idle()` while the host's composition is running, and
`rememberDesktopSearchCopy` filled the holder that `idle()` reads in a `SideEffect`, which runs after
it. So the idle state was built from empty strings: no headline, no body, a focused button with no
label. Present on master since the desktop host landed. Earlier offscreen renders went straight to a
search, so none of them showed this screen settled, which is why it reached the owner.

Fixed by writing the holder during composition. `DesktopSearchCopyTest` guards it, and it was run
against the old order to confirm it fails there (`AssertionError` on the headline).

**Since closed by `KMP-17`:** `Browse home` did nothing on desktop, because there is no home to go to,
and took initial focus, so typing needed a click first. Desktop's idle state now has no button and the
field holds focus. TV is unchanged.

---

## BUG-12 — typing a desktop search said "Search failed" · **high**

**Status:** in pr · found 2026-11-02 by rendering the real plugins while typing, with no Return

`DesktopSearchSource.quickSearch` asked only providers declaring `hasQuickSearch`. Declaring it proves
nothing: `MainAPI.quickSearch` throws `NotImplementedError` by default. So a user who typed and waited got
"Search failed" with a stack trace on screen, and with no provider declaring it, `NO_SOURCES · 0 INSTALLED`.
Return ran a full search, which hid it from every earlier check, because they all pressed Return.

Measured with the five official plugins: the screen showed `NotImplementedError` from `CoroutineDebugging.kt`
through `APIRepository.kt`. After the fix, typing `nasa` with no Return shows results, 26 of them from Dailymotion in the rendered view.

Fixed: typing asks every source, using quick search where it works and a full search otherwise, and the error
line keeps only its first line. `DesktopSearchSourceTest` covers it, and fails when a failed quick search does
not fall back. **`:app`'s `SearchSource` was not checked for the same fault.**

---

## BUG-14 — a plugin answering after the 60 s timeout lost its whole host · **medium**

**Status:** in pr · found 2026-11-03 while doing `KMP-20` · `PluginHostClient.kt`

A call that passed its timeout removed its id from `pending`, and `readLoop` treats a reply to an id it cannot
find as a protocol violation. So one slow answer at 61 s ended the sandboxed host, and search and home for that
plugin went with it. Measured with the real child under real Seatbelt: with the lookup reverted,
`a real plugin answering after the timeout keeps its host` fails with `the plugin host is not running`.

Fixed: a timed-out call moves to `timedOut`, and a reply is looked up in both maps, under one lock.

| Property | How it holds | Test (`PluginHostClientTest`) |
|---|---|---|
| An id never issued ends the host | It is in neither map | `an id never issued…`, `the next id, not yet issued…`, and the real child |
| One reply per id | The lookup removes the id | `a second reply to a timed-out id…` |
| A late payload reaches nobody | It completes a `Pending` that nothing awaits any more | `a late reply is not delivered to the call waiting…` |
| A late payload is checked like any other | It goes through `readFrame` and the call's own decoder | `a late reply that does not parse…`, `…past the limit…` |
| The stream stays in sync | Frames are length-prefixed, and the frame is read whole first | `a late reply of the largest legal size…` |
| Bounded memory | `MAX_TIMED_OUT_CALLS`, 256 | `at the bound…`, `an answered late reply gives its place back` |
| Ids are not reused | `nextId` only counts up, and a timeout returns nothing to it. It would wrap after 2^32 calls | `at the bound…` checks 256 are distinct |

**At the bound the host ends.** The 257th call still unanswered past its timeout ends the host, and any call then
waiting fails. Forgetting the oldest id instead would hand the plugin the choice of when its late reply kills the
host. A late reply gives its place back, so only calls never answered count.

The tests run the 60 s on virtual time, so nothing in the client was made injectable for them. Each was checked by
breaking the fix: without the `timedOut` lookup 6 client tests and the real-child test fail, without consuming the
id 2 fail, and without the bound 2 fail.

**Found by reading, not measured, and not changed:** a caller cancelled while waiting is not a timeout, so its id
stays in `pending` until the plugin answers, with no bound. And the request write has no timeout, so a plugin that
stops reading stdin can hold a call past 60 s.

---

## BUG-13 — the subscription worker loaded extensions in safe mode · **medium**

**Status:** in pr · found by reading the code; not reproduced on a device

Safe mode exists so a crashing extension stops being loaded. `MainActivity` skips loading when
`checkSafeModeFile()` is true or `lastError` is set, and `DownloadQueueManager` will not start its queue when
`isSafeMode()` is true. `SubscriptionWorkManager.doWork` checked neither and loaded every online and local
plugin, so the extension safe mode was keeping out still ran in the background, every 6 hours.

`isSafeMode()` alone is not enough here. `lastError` is only filled by `setLastError`, and the only callers
were `MainActivity.onCreate` and `DownloadQueueService`. A worker in a process where neither ran would see
`lastError == null` with a crash on record. The worker must not call `setLastError` either: it deletes `last_error`
as it reads it and keeps the text only in memory, so if the worker were the first to read it and its process then
died, the next launch would show no crash dialog and would load extensions. So the worker looks for the file and
leaves it there. `DownloadQueueService` still has that property; reported, not changed.

Fixed: the check comes first in `doWork`, before the notification channel and the foreground notification, and in
safe mode the worker returns `Result.success()`. Not `retry`, which the catch block at the end of `doWork` already
avoids for battery reasons, and not `cancelWorkById`, which would end the periodic work for good. The next run
checks again.

**No regression test, and why.** Measured with a throwaway JVM test under `:app`'s
`unitTests.isReturnDefaultValues`, since deleted: `PluginManager.isSafeMode()` fails with
`ExceptionInInitializerError`, because `PluginManager.kt:187` calls `.absolutePath` on a null
`Environment.getExternalStorageDirectory()`; and the worker's `colorFromAttribute` field initialiser throws
`NullPointerException`. `:app` has no Robolectric or mocking library, and adding one needs the owner's approval.
A test of an extracted boolean would not exercise this path. **Not run on a device.**
