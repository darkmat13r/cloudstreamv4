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

**Status:** done · #86 · found 2026-11-02 by rendering the real plugins while typing, with no Return

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

**Status:** done · #97 · found 2026-11-03 while doing `KMP-20` · `PluginHostClient.kt`

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

**Status:** done · #96 · found by reading the code; not reproduced on a device

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

---

## BUG-15 — desktop video was drawn in the shape of the decoder's buffer · **medium**

**Status:** done · #104 · noted in `KMP-25` as 1280x720 arriving as 1280x738 · `MediaHost.kt`, `FrameGeometry.kt`, `PlayerScreen.kt`

**It was a real stretch, not padding.** libVLC's format callback reports the decoder's buffer, not the picture: libavcodec's
aligned size, 1280 x 738 for 1280 x 720. Whatever size is returned, libVLC scales the visible picture to fill it. On a test card
with bars at known rows, the 1280 x 720 card's bars at source rows 179.5 and 539.5 reached the app at 184.0 and 553.0, which is
those rows times 738/720, and a 360 x 360 circle came back 360 x 370. No band of padding appeared. Sample aspect ratio was
never applied at all, and a padded size then met the cap, so 1920 x 1090 became 1902 x 1080: narrower as well as wrong.

**`newFormatSize` was not the answer.** The note in `KMP-25` suggested it. In the vlcj 4.12.1 bytecode (`CallbackVideoSurface$SetupCallback.format`)
it is called once, straight after `getBufferFormat`, with the size just returned twice over. Measured: `newFormatSize(1280,738,1280,738)`.

### Why there are two parts to the fix

The picture's size is in the track list (`media().info().videoTracks()`), when the track list has it yet. Blocking inside the
callback for 2 s, and reading the tracks from another thread meanwhile:

| Container | Tracks when the callback starts | During a 2 s block | After the callback returns |
|---|---|---|---|
| MP4 | 1280x720, about 75 ms earlier | | |
| WebM | Size arrives about 15 ms into the callback; SAR 0/0 | Size known, SAR still 0/0 | SAR within about 20 ms |
| MPEG-TS | `0x0` | **Still `0x0`** | 1280x720, within about 35 ms |

So for MPEG-TS, waiting cannot work: the track is filled in only after the callback has returned. Hence:

- **The buffer** is the visible size when a track has it, under the cap. Exact frames, no resampling.
- **A new message, `MESSAGE_PICTURE`,** says what shape to draw the frame in, sent when the tracks first say so. `FitAs` draws to it and
  cues are placed on it. Non-square pixels only ever change this shape, never the buffer. For MPEG-TS the frame stays the padded size, so it is
  still resampled once by libVLC and then drawn in the right shape: geometry correct, slightly softer (bars of 4 rows become 5 in the frame).

`MediaHost` still does not call libVLC from its callback: its own thread reads the tracks, and the callback waits for that for at most 200 ms.
That thread looks again every 100 ms, which is what catches MPEG-TS and a later change of size. Between a format and its shape, a frame draws in its
own shape, so a TS stream's first 100 ms or so are as before. Track list entries are matched against the report: on each axis a track must be no larger
than the buffer and no more than 66 smaller, so the report from before a size change is not believed. The app holds
`MESSAGE_PICTURE` to the same bounds as a frame, and allocates nothing for it. `MAX_FRAME_WIDTH/HEIGHT` still bind both sizes (`FrameGeometryTest`, on hostile values).

### Measurements

Real sandboxed host, libVLC 3.0.23, `--check-subtitle-render` in a 1920 x 1200 scene with temporary logging and a dump of the raw
frame, neither committed. Cards are ffmpeg x264/x265/VP9 with 15-frame keyframe intervals. "Frame" is what reached the app. "On screen" is the
picture and the circle in the HUD-free PNG.

| Video | Frame, before | Frame, after | Picture on screen, before → after | Circle on screen, before → after |
|---|---|---|---|---|
| 1280x720 H.264, MP4 | 1280x738 | 1280x720 | 1920x1107 → 1920x1080 | 540x553 → 540x540 |
| 1920x1080 H.264 | 1902x1080 | 1920x1080 | 1920x1090 → 1920x1080 | 540x544 → 540x540 |
| 854x480 H.264 | 864x482 | 854x480 | 1920x1071 → 1920x1079 | 540x535 → 540x539 |
| 640x360 H.264 | 640x386 | 640x360 | 1920x1158 → 1920x1080 | 540x579 → 540x540 |
| 1920x800 H.264 | 1920x802 | 1920x800 | 1920x802 → 1920x800 | 400x402 → 400x400 |
| 720x1280 H.264, portrait | 606x1080 | 608x1080 | 673x1200 → 676x1200 | 337x338 → 338x338 |
| 3840x2160 H.264, over the cap | 1904x1080 | 1920x1080 | 1920x1089 → 1920x1080 | 540x545 → 540x540 |
| 720x576, SAR 16:15 | 720x578 | 720x576, shape 768x576 | 1495x1200 → 1600x1200 | 561x600 → 600x600 |
| 1440x1080, SAR 4:3 | 1426x1080 | 1440x1080, shape 1920x1080 | 1584x1200 → 1920x1080 | 446x600 → 542x540 |
| 854x480, SAR 32:27 | 864x482 | 854x480, shape 1012x480 | 1920x1071 → 1920x911 | 458x535 → 458x455 |
| 720x480, SAR 8:9 | 720x482 | 720x480, shape 720x540 | 1793x1200 → 1600x1200 | 673x600 → 600x600 |
| 1280x720 HEVC, MP4 | 1280x736 | 1280x720 | 1920x1104 → 1920x1080 | 540x552 → 540x540 |
| 1280x720 VP9, WebM | 1280x736 | 1280x720 | 1920x1104 → 1920x1080 | 540x552 → 540x540 |
| 1280x720, rotated 90° in the MP4 | 622x1080 | 608x1080 | 691x1200 → 676x1200 | 345x338 → 338x338 |
| **1280x720 H.264, MPEG-TS** | 1280x738 | **1280x738**, shape 1280x720 | 1920x1107 → 1920x1080 | 540x553 → 540x540 |
| Local HLS, TS segments | 1280x738 | **1280x738**, shape 1280x720 | 1920x1107 → 1920x1080 | 540x553 → 540x540 |
| Local HLS, fMP4 segments | 1280x738 | 1280x720 | 1920x1107 → 1920x1080 | 540x553 → 540x540 |
| Local HLS, two variants, after its switch to 720p | 1280x738 | 1280x720 | 1920x1107 → 1920x1080 | 540x553 → 540x540 |
| One TS, 1280x720 then 854x480, after the change | 864x482 | 864x482, shape 854x480 | 1920x1071 → 1920x1079 | 540x535 → 540x539 |

The exact shape of 854x480 at this width is 1920 x 1079.1, and of portrait is 675 x 1200: the cap rounds 607.5 to 608, so portrait is 0.1% wide. In 1440x1080 the card's
ellipse is 406 columns in the frame where 405 is exact, which at 4/3 gives the 542. The two-variant HLS started on its 854x480 variant in both runs, as 864x482 before and 854x480 after, then switched.

Marks in the frame, against the source rows and columns:

| Video | Marks at | Before | After |
|---|---|---|---|
| 1280x720 H.264 | rows 179.5, 539.5, 717.5 | 184.0, 553.0, 735.5 | 179.5, 539.5, 717.5 |
| 1920x1080 H.264 | columns 479.5, 1439.5, 1917.5 | 475.0, 1426.0, 1899.5 | 479.5, 1439.5, 1917.5 |
| 854x480 H.264 | rows 119.5, 359.5; columns 212.5, 639.5 | 120.0, 361.0; 215.0, 647.0 | 119.5, 359.5; 212.5, 639.5 |
| 640x360 H.264 | rows 89.5, 269.5 | 96.0, 289.0 | 89.5, 269.5 |
| 1280x720 MPEG-TS | rows 179.5, 539.5 | 184.0, 553.0 | 184.0, 553.0 in the frame; on screen 329.5, 869.5, where 60 + 1.5 x source is 329.25, 869.25 |

**One real stream:** YouTube through the official plugin, `nasa`, 2160p, isolated `user.home`, official plugins only. Reported 3840 x 2176. The frame went from 1905 x 1080 to 1920 x 1080, with a 16:9 picture letterboxed in the PNG, which was looked at.
Dailymotion and Twitch gave no link on the queries tried (`nasa`, `news`; `music`, `a`). `--check-playback` was skipped: it plays sound and was not needed.

### Tests

`FrameGeometryTest`, on the reported sizes measured above, `MediaStreamReaderTest` (3 new) and `PlayerScreenTest` (4 new, reading rendered pixels). `:desktopApp:jvmTest`: 157 tests, 0 failures, Temurin 17, `TZ=UTC`, local. Full gates not run from this branch.

Each of these alone goes red:

| Mutation | Failed by |
|---|---|
| The buffer is the reported size, which is the original bug | 5 in `FrameGeometryTest` |
| SAR ignored | 4 |
| Narrow pixels shrink the width instead of growing the height | `narrow pixels…`, `sideways…` |
| No lower or no upper padding bound, on either axis (4 mutations) | `a track is believed only within the padding…`. **The first two versions of these survived**, because one axis was covering for the other, so that test was written for them |
| Sideways ignored, or the pixels not turned | `a track shown sideways…` |
| Absurd SAR accepted | `an absurd or missing sample aspect ratio…` |
| Cap removed | 4 |
| Frame drawn with plain `Fit` | `a frame stretched…`, `non-square pixels are drawn wide` |
| Cues placed on the buffer | `a cue sits on the picture's shape…` |
| Reader takes any picture size, or one before a format | The two `MediaStreamReaderTest` cases |

**`FrameShapes` in `MediaHost` has no unit test,** because it needs libVLC. What proves it is the table above. With the host not sending the picture size, the same live TS run went back to 1920 x 1107 and 540 x 553, with `pictureSize=null`.

The committed code, with no logging, ran the whole `--check-subtitle-render` over TS, SAR 16:15 and MP4 at 1280 x 800: exit 0 on each, a cue on screen, pause and seek still working. Its sync figures mean nothing on these still cards, which carry no frame number.

### Not verified

- **A live window, and sound.** All of this is offscreen; `--check-playback` was not run.
- **Hardware decoding.** Every format report here is libavcodec's sizes. Whether the host can use a hardware decoder under its sandbox was not looked at.
- **A real stream whose segments are MPEG-TS.** Local HLS through libVLC's adaptive module stands in for it. No plugin reached here gave one.
- **Interlaced video, 10-bit, AV1, and the other four rotations** were not measured. A track that fits no rule falls back on the old behaviour.
- **The 66.** The largest padding measured is 26. 66 comes from reading libavcodec's alignment from memory, not from a file that needed it.

### Found and left

```
Found: --check-subtitle-render crashes on a frame narrower than 640: decode() reads CODE_BITS * CODE_BLOCK pixels of row CODE_ROW, in SubtitleRenderCheck.kt:246. A portrait video does it
Suggest: return early from sample() when the frame is narrower than the strip
Why: a check tool only, not the player. Worked around in the temporary measurement build, not changed here
```

```
Found: a still-image H.264 encode with an 8.3 s keyframe interval (x264 -tune stillimage) showed flat grey (luma 130) until its second keyframe. The same interval on moving testsrc2 did not, at 0.5 s or 3 s
Suggest: leave it unless a real file shows it
Why: seen on one synthetic file, with the cause not found. It may be the encode
```

```
Found, by reading: VlcPlayer.createOrNull deletes everything under ~/.cloudstream4/.media-host, so a check run with the normal home directory would remove the working directory of an app that is open
Suggest: one directory per process, or say so in KMP-25's instructions
Why: older than this. It is why these runs all used a fresh user.home
```

## BUG-16 — a host ended for a reason could tell its waiting call the wrong one · **low**

**Status:** done · #103 · found 2026-11-03 when a gate on `KMP-24` went red · `PluginHostClient.kt`

`abort` killed the process, then failed the waiting calls with its reason. The kill ends the reader, whose own `abort` says
"the plugin host exited", and whichever got there first won. The call failed either way; only the message was wrong. It also made
`PluginHostClientTest` fail at random: 2 of 12 runs of the test class on master.

Measured with temporary edits: a 50 ms pause after the kill failed 3 of 3 runs; waiting calls failed first, with the same pause
before the kill, failed 0 of 3.

The fix fails the waiting calls first and kills last. The test's fake process now pauses in its first `destroyForcibly`, so the reader always
gets there first: the old order fails 6 of 6 runs, this one 0 of 10.

---

## BUG-17 — opening the player could cost another running copy its stream, while that stream was starting · **low**

**Status:** in pr · found 2026-11-03 by reading, then run · `VlcPlayer.kt`

`createOrNull` deleted everything in `~/.cloudstream4/.media-host`, on the assumption of one stream at a time. Copies of the app
sharing a home break that assumption: a second window from `open -n`, another build, or a `--check-*` run.

**The suspected failure, a playing stream dying when its directory goes, does not happen.** The host keeps everything it needs open
and unlinked files stay readable. What fails is a host still starting.

Measured on the packaged app with a fresh `user.home`, two runs sharing it, `--check-subtitle-render`, before the fix:

| When the directory goes | What the first run saw |
|---|---|
| 10 s in, playing a local file | Nothing. Exit 0, `failed=false`, through pause, resume and seek |
| After the first frame, playing YouTube over https | Nothing. Exit 0 through pause, resume and a +10 s seek, which reconnects. Same as the control |
| 0, 0.1, 0.2 s after the directory appears, YouTube, deleted with `rm` | `no frame arrived`, exit 4 |
| 0.4 to 2.5 s after it appears, YouTube, deleted with `rm` | First frame as normal |
| Second real run started 0.2 to 0.9 s after the first, local file | `no frame arrived`, exit 4, in 6 of 6. At 0.1 s, and in 6 of 6 starting together, exit 0. The check's file was copied in and not yet opened, a wider window than a stream has, and only a check has it |
| The instant the directory appears, real player on a public https stream | `createOrNull` returned null, so `ProcessBuilder` could not start in it. In a window that is `PlayerUnavailable` |

A JVM started in a deleted directory stops at once: `Could not determine current working directory`, exit 1. So the person sees a failed
stream, or the screen for VLC being missing, only if the second copy opens a player within a few tenths of a second of the first. After
the host is up, no file appeared in its directory during a public HLS video with a pause and a seek, polled once a second.

**The fix.** A directory is now named `host-<pid>-<start millis>-<random>`, for the app that made it, and only one whose app has gone is cleared.
The name is the only state, so there is no lock and no window between creating a directory and marking it. A pid in use by a process with
another start time does not count, which covers reuse. If the start time cannot be read the directory stays: a leftover costs little. A host that
fails to start now removes its own directory, because the next sweep in the same app no longer would. Directories are still never reused, so a
host that outlives its app has no rights over a later one.

After the fix, same app, same home:

| Run | Result |
|---|---|
| Second run started 0.1 to 0.9 s after the first, 7 pairs | 14 of 14 reached frames with `failed=false` |
| App killed with `-9` | Its host exited within 2 s, its directory stayed, and the next run cleared it |
| Planted: a dead pid, pid 1 with a wrong start, an old-style name, a live process with its true start | The first three cleared, the last kept |

**Exit code is not the measure in the paired runs.** 4 of those 14 exited 1, with `failed=false` and a cue on screen, because `resumed`, `play` or `after-seek` passed the check's 100 ms sync bound. Machine load average was about 35 from other work, and two offscreen renders were competing. Before the fix the first run of a pair exited 4; none does now.

**The sandbox is unchanged.** `SeatbeltProfile` has no diff. Its real rendering for `host-100-…-111`, under `sandbox-exec`: read and write inside that directory; `Operation not permitted` to read `host-200-…`, to read `host-100-…-1112` (a name extending this one), to read a file in the parent, and to write in the parent.

`HostDirectoryTest`, with real processes, including another JVM making its directory through the same call and a `destroyForcibly` of it. Each mutation alone, 7 tests:

| Mutation | Failed |
|---|---|
| The old code: delete everything, name `host` | 4 |
| Delete everything, new names | 3 |
| Ignore the start time | 1 (`a pid now used by a process that started at another time…`) |
| Keep what cannot be shown to be live | 2 |
| Keep a dead app's directory | 1 |
| Keep old-style names | 1 |
| Do not record the start time | 3 |

**Not fixed, and cannot be from here: a build without this fix still deletes everything,** so side by side with one it still breaks a starting host (measured: unfixed second run, fixed first, exit 4). **And a fixed build treats an old-style name as a leftover,** so it can still do the same to an unfixed build (measured, exit 4). Keeping those names would leak them for good. Both end once every running build has the fix.

**Not verified.** A live window, which a locked session cannot drive. The removal of a directory from a failed start has no test: nothing makes `startMediaHost` fail without a seam. The live case: Twitch's search failed, and two public live HLS test streams gave no frame in the control run, directory intact, not looked into. So pausing a live stream, which might make libVLC write a temporary file, was not measured against a deleted directory. With the fix that directory is no longer deleted. A home shared over a network, where another machine's pid reads as dead.

---

## BUG-19 — a slow source lost every link, including the ones that had already been found · **high**

**Status:** done · #111 · found 2026-11-03 running CineStream 487, owner approved the fix the same day · `PluginHost.kt`, `PluginHostClient.kt`, `DetailViewModel.kt`, `DetailScreenDesktop.kt`

The host collected a plugin's links from its callbacks and replied only when `loadLinks` returned, and the app waited 60 s. CineStream asks dozens of sources at once and takes over a minute, so all five films failed with `links FAILED: CineStream did not answer within 60000ms`, holding links it had found in the first half second.

Now each link and subtitle goes to the app as the plugin offers it, and the source panel grows. Nothing here changed `:app`, `:library` or `classes.jar`.

### Measured: the five films

CineStream 487, sha256 `30dc6436…`, reviewed and sandboxed, a fresh home per film, packaged app, `--check-extensions <film>`, all five at once. `ProviderSimkl` and `ProviderCineStream` are off through the plugin's own keys, because CineStream does not load on master without that (another agent's defect). No URLs were recorded.

| Film | Before, 458d01b4 | After: first link | Links, of which the player may open | Subtitles | How the call ended |
|---|---|---:|---:|---:|---|
| Inception | failed at 60 s, 0 links | 261 ms | 91, 73 | 175 | finished |
| The Dark Knight | failed at 60 s, 0 links | 1359 ms | 51, 43 | 200 | finished |
| Interstellar | failed at 60 s, 0 links | 301 ms | 92, 74 | 142 | **at the ceiling**, 90 s, success with what it had |
| Oppenheimer | failed at 60 s, 0 links | 1289 ms | 90, 79 | 54 | finished |
| The Godfather | failed at 60 s, 0 links | 1215 ms | 56, 51 | 200 | finished |

Two earlier full timelines (`--verbose`), the first with a temporary 250 s ceiling to see the natural end, which is what the ceiling was chosen from. Counts move between runs because the sources do.

| Film | First link | Links at 30 s | At 60 s | Call ended, with |
|---|---:|---:|---:|---:|
| Inception | 484, 465 ms | 93, 104 | 93, 104 | 65.1 s 93, 67.2 s 104 |
| The Dark Knight | 1666, 3199 ms | 49, 55 | 49, 55 | 80.5 s 49, 83.6 s 55 |
| Interstellar | 453, 451 ms | 90, 96 | 90, 96 | 83.8 s 96, 87.5 s 102 |
| Oppenheimer | 1162, 1595 ms | 94, 91 | 94, 91 | 71.5 s 94, 75.9 s 91 |
| The Godfather | 1348, 1553 ms | 51, 68 | 51, 68 | 68.9 s 51, 77.0 s 68 |

**A link from a partial reply played, silently,** through `--check-subtitle-render … --first-link`, which takes the first value the screen would get, then stops the call, as a click would. Real frames through the sandboxed libVLC, looked at: Inception 640x290, position 114 s, 0 subtitle tracks (it was picked before any arrived, so that run exits 1, on the subtitle part of the check only); The Godfather 640x386, 200 tracks, a cue on screen, exit 0.

**Stopping worked on the real plugin.** In those two runs, sampling the serve host every 2 s: CPU 67% and 76%, then 59% and 51% with 14 connections, then 0.0% from the first sample after the call was stopped, for the remaining 116 s and 32 s, with 5 and 6 connections left open and not changing. `%cpu` from `ps`, connections from `lsof`. CineStream 487 is the only plugin measured for this.

**Official plugins, `nasa`, before and after, in fresh homes at the same moment:** all 5 load with their hashes verified, 4 of 5 return results in both. Dailymotion and Internet Archive 0 links, Invidious 0 results and Twitch's `open FAILED: RuntimeException` are the same in both. YouTube's first search hit differed between the two runs (`NASA Goddard`, `NASA`), so its 12 links and 2 subtitles against 14 and 21 are not a like-for-like comparison; both opened, gave links and loaded an English track.

### The design

**Wire.** After its id a reply now has a kind: failed 0, done 1, partial 2, which are the bytes the two existing kinds already were. Anything else ends the host, where any non-zero byte used to read as success. A partial carries links and subtitles in the existing `writeLinks` form. The host sends one per callback. The final reply on a links call carries nothing more.

**What holds against a hostile host:**

| Limit | Value | Why that value | Past it |
|---|---|---|---|
| Frame size | 8 MB, unchanged | | the host ends |
| Links per call, across every frame | 500 | the most the app ever kept | the host ends |
| Subtitles per call | 200 | the same | the host ends |
| Bytes per call, across every frame | 8 MB | all one call could ever send when its answer was one frame, so sending in parts costs nothing new | the host ends |
| Partial frames per call | no count of its own: at most 700 | an empty partial ends the host, so every frame costs at least one of the 700 entries | the host ends |
| Rate | not a number | snapshots are conflated at every step, so the reader sets the pace and a fast host only ever replaces the latest value. The read loop does no blocking work | |

Host-side, the same three bounds cut an honest plugin instead: past them it drops, so an honest 1000-link plugin keeps its host. A client that ends a host passing a bound is sound only because of that.

**Every existing check still applies.** A partial goes through `readFrame`, one id per frame, and the same parser. A partial to an id never issued, a partial after that id's final reply, a partial to a call that may not have one (search, load, home, the ready id) and an empty partial all end the host. Each partial goes through the same `cleaned()`, and each link through the same `isPublicHttpUrl`/`isPublicAudioUrl` before the screen, once, when it first appears.

**`BUG-14` is not loosened.** A partial to a timed-out id is parsed and counted exactly as a live one, and goes nowhere. It stays bound by the same limits, so a call past its ceiling is no cheaper a way to flood. One final reply per id, and the id then ends the host, as before. Links calls count toward `MAX_TIMED_OUT_CALLS`.

**The ceiling is 90 s, counted from the start, and no partial changes it.** All five films ended at 65 to 87 s in the runs above, so 60 would have called every one of them cut short, and would have cut 6 links from Interstellar. It is a close fit: Interstellar passed 90 s in the last run, and that run is the success-at-the-ceiling case in the table. Nothing holds a person up for it, since they can pick a link as soon as one exists. **The cost:** a source that never offers anything now takes 90 s to fail, not 60. Other calls keep their 60 s.

**What ends the wait:**

| Ending | With links | With none |
|---|---|---|
| The plugin finishes | success | `Empty`, *No Links Found* |
| The plugin throws | success with what it has | the plugin's message, as before |
| The ceiling | success with what it has; the check says `stopped early` | the existing failure, `did not answer within 90000ms` |
| The person leaves | the call stops | the call stops |
| The host ends | the call fails, and any links already on screen stay: they passed the same checks as all others | the failure |

**What happens to the plugin's work.** At the ceiling, when the panel is dismissed, on Back, and when a link is picked, the app sends `OP_CANCEL` with that call's id, in no more than one frame per call, and keeps the id in `timedOut`. The host answers at once, with that call's failed reply, and drops anything the plugin offers later; the answer does not wait for the plugin, so the id's place comes back. It then cancels the coroutine. **A plugin that cooperates stops** (measured above). **One blocking a thread or running its own scope keeps going until it finishes, because nothing can stop it short of ending the host,** and that would cost every other call on it. It holds one thread of the host's `Dispatchers.IO` meanwhile. Closing the window ends the host, as it always did.

**The app.** `DetailSource.links` is a `Flow` of the list so far, and only ever adds to the end. `DetailState.linksArriving` says more may come. The panel's header shows a count and the existing `Loading…` string, in space that does not change the height of anything, so **no new string, no baseline move**. `DesktopRowsSkeleton` replaces a rail-shaped skeleton in the panel and sits where the rows will. Rows keep their key, nothing asks for focus. `I18N-08`: no words in the state.

Also: an unreadable final reply now fails its call at once. It was already claimed, so `abort` could not reach it, and it waited for its timeout. And the check prints how many links the player may open, because its count was always before the filter, and for Inception that is 91 against 73.

### Tests, each checked by breaking the guard, alone

33 mutations, one at a time, each with its target tests rerun. All 33 were caught. Class counts after the change, all passing: `:feature:detail` 16, `:feature:extensions:sandbox` 151, `:desktopApp` 233.

| Removed | Failing tests |
|---|---:|
| Client: link bound / subtitle bound / byte budget / refusing an empty partial | 3 / 1 / 1 / 1 |
| Client: refusing an unknown reply kind / partial to an unknown id / partial to a call that may not have one | 1 / 2 / 2 |
| Client: recognising a timed-out id / counting after the call gave up | 4 / 1 |
| Client: a hard ceiling (a partial re-arms it) | 1 |
| Client: telling the host / retiring the id on leaving / the timed-out bound | 3 / 4 / 3 |
| Client: failing a call on an unreadable final / a host ending fails the call / a plugin failing keeps its links | 1 / 3 / 1 |
| Protocol: frame limit | 2 |
| Real child: link cap / subtitle cap / byte cap / dropping after the last reply / cancel stops the plugin / cancel answers at once | 1 / 1 / 1 / 3 / 1 / 1 |
| Source: URL filter / audio URL filter / checking a link once | 3 / 1 / 3 |
| ViewModel: picking stops / dismissing stops / a failure keeps links / the loading flag ending | 1 / 2 / 1 / 4 |
| Panel: appending at the end / the words taking no height / the words being there | 3 / 1 / 1 |

The hostile-host cases asked for are in `PluginHostClientTest`, with a fake that sends exact bytes: flood (links, subtitles, bytes), oversized frame, partial after final, partial for a timed-out id. Partial with a bad URL is `ArrivingLinksTest`, through the real filter, on IP literals so nothing resolves. The real child under real Seatbelt is in `SandboxEnforcementTest`, including one test reading raw frames, since the client could not tell a host that stopped sending from one that did not.

Renders looked at, 1280x800: waiting, first link, growing, overflowing, finished, nothing found, failed, and Filipino, Albanian and Arabic for the longest words. Pointer and keyboard through a real scene: pixels of the rows are identical while the list grows (hovered and scrolled) and when it finishes, and focus stays on its row.

### Not verified

- **A live window.** A locked session cannot drive one. The panel was run in an offscreen scene, the real path with `--first-link` and `--check-extensions`. A person clicking a row while links arrive has not happened.
- **Whether 90 s is right for anything but these five films,** on this machine and network, with five running at once. Interstellar crossed it in one run of three.
- **What other plugins do when told to stop.** Only CineStream 487 and test fixtures were measured.
- **Series and anime with this plugin.** Only the five films.
- **The gates:** module tests and `:desktopApp:createDistributable` only. `:app:checkExtensionApi` was not run here. No file under `app/` or `library/` changed.

### For the owner

**Picking a link early can lose its subtitles.** A link carries the subtitles offered so far, and picking stops the call. The first subtitle arrived 1 to 3 s in, most by 5 s, and the first link often before that: Inception's first link had 0 tracks. Later rows are fine, and Play again from the page asks afresh. Letting subtitles join a player already running, or not stopping on a pick, are both possible and neither is done. Coming back from the player shows the list as it was when the link was picked, and does not resume.

### Found and left

- **Links are in arrival order, not by quality:** the first row Inception offers can be 266p. Sorting would move rows, so it needs a decision, such as keeping arrival order until the search ends.
- **A row with a subtitle is 49 tall in the panel,** off the 4 dp step (`DesktopRow`, measured from the renders, 81 to 130).
- **`DetailScreenTv` shows no loading words.** Its list would grow, but no host wires it up yet.
- **`--check-playback`** still waits for the whole answer. It was not run, because it plays aloud.
- **Search, load and home:** a caller cancelled while waiting still stays in `pending` with no bound (`BUG-14`). Links calls no longer do.
- **Plugin `search` still gets 60 s,** so one slow search is still lost whole. The same idea does not apply as directly, because a search has one answer.

## BUG-18 — CineStream did not load, and neither did anything else whose stack maps named `java/util/Object` · **high**

**Status:** in pr · found 2026-11-03 by running the owner's plugin · `DexConverter.kt`, `StackMapFrames.kt`, shims in `syncproviders/`

CineStream 487 (`sha256 30dc6436…`), Allowed in a fresh home and nothing else seeded, printed `not loaded: NoClassDefFoundError: java/util/Object`, `0 of 1 running, 0 providers`. The owner mostly uses its CineTmdb source. There were two causes, and the second was behind the first.

**1. dex2jar writes a class that does not exist into stack maps.** Read from the 2.4.38 bytecode (`Dex2jar$1$1.getCommonSuperClass`): it walks both types up through the dex's own classes, then hands whatever it reached to ASM, which uses `Class.forName`. It catches any `Throwable` from that and returns the literal `java/util/Object`. So the literal appears when a chain ends at a class in neither the dex nor this classpath, such as `:app`'s `PluginData`. The comment in `DexConverter` saying no class is ever loaded was wrong: host classes were being loaded, uninitialised.

It is the type of a slot nobody reads any more, which is why it looks harmless and is not. dex2jar casts a value still in use to the type it is used as, so a join of two types only survives in a dead slot. The verifier still has to check the frame, loads `java/util/Object`, and fails the class.

The same walk never compares the second type itself. `ConversionFixtureChild` joined with its parent came out `java/lang/Object` in one order and the parent in the other, measured on the old converter's output.

**The fix.** dex2jar no longer computes frames (`computeFrames(false)`). `StackMapFrames` rewrites the jar afterwards with ASM's `COMPUTE_FRAMES` and a `getCommonSuperClass` of our own: the plugin's classes from the jar, everything else from `.class` files read as data, host first because a host loads parent-first. A class it cannot find ends its chain, and the answer falls back to `java/lang/Object`. Nothing is loaded, so the comment is now true. No verification was turned off, no flag was changed, and `SeatbeltProfile` has no diff. ASM was already on this classpath through `dex-translator`'s API, so there is no dependency change.

**2. The plugin's own `load()` needed classes only `:app` has.** With the frames fixed, the failure moved to `NoClassDefFoundError: com/lagradost/cloudstream3/syncproviders/SyncRepo`. `CineStream.load` does `registerMainAPI(CineStreamProvider())`, then `registerMainAPI(CineSimklProvider())`, then CineTmdb and the extractors, and `CineSimklProvider` has a field `SyncRepo(AccountManager.simklApi)`. It throws in the plugin's own constructor call, before `registerMainAPI`, so isolating `registerMainAPI` could not have saved CineTmdb. `JvmExtensionRuntime` treating a failed load as total is unchanged.

The shims are `AccountManager`, `SyncAPI` and its two nested classes, `SyncRepo`, `AuthUser`, `SimklApi` and `UiText`. Nobody is signed in: `authUser()` is null and `library()` is an empty success. They hold no token, reach no network or file, and are in the `SHIMS` list that test checks. On that answer CineSimkl's "Personal" rows say `Login required for personal content.`, from the plugin's code as read in its decompiled source, and no such row was exercised here.

**Stale conversions.** A converted jar is named `<sha>-<revision>.jar`, so `CONVERTER_REVISION` went from `-c2` to `-c3`. A home holding the bad `-c2` jar was run on the fixed app: it converted again, wrote `-c3`, and loaded 3 providers. The decision is bound to the `.cs3`'s hash, so the owner's Allow carries over.

### Measured, packaged app, fresh home, sandboxed, no settings seeded

`--check-extensions` on CineStream, `3 of 3 providers returned results`, exit 0, for both queries. Before the fix: 0 providers.

| Query | Provider | Search | Opened | Links | Subtitles | Home |
|---|---|---|---|---|---|---|
| `nasa` | CineTmdb | 12 | "Our Little Clinic" (series, 112 episodes) | 1 | 22 | 1 row, 20 titles |
| `nasa` | CineStream | 41 | "Incendies" (movie) | 27 | 88 | 1 row, 50 titles |
| `nasa` | CineSimkl | 21 | "NASA & SpaceX: Journey to the Future" (movie) | 14 | 2 | 1 row, 200 titles |
| `breaking bad` | CineTmdb | 15 | "Breaking Bad" (series, 62 episodes) | 47 | 94 | 1 row, 20 titles |
| `breaking bad` | CineStream | 16 | "El Camino" (movie) | 22 | 31 | 1 row, 50 titles |
| `breaking bad` | CineSimkl | 14 | "Untold Raygun: Breaking Badly" (movie) | 9 | 35 | 1 row, 200 titles |

**The real verifier, not a report.** Every class in a jar was linked with `getDeclaredMethods`, which verifies and does not initialise, so no plugin code ran, under `sandbox-exec` with no network and no writes, and the result read from HotSpot's own `-Xlog:class+init` lines.

| | Classes | Passed | Failed verification | Superclass missing | Failures naming `java/util/Object` | `VerifyError` |
|---|---|---|---|---|---|---|
| CineStream, before | 877 | 872 | 3 | 2 | 2 | 0 |
| CineStream, after | 877 | 874 | 1 | 2 | 0 | 0 |
| The sample's 53 jars, before | 3347 | 3316 | 17 | 14 | 24 log lines | 0 |
| The sample's 53 jars, after | 3347 | 3339 | 1 | 7 | 0 | 0 |

CineStream's "before" row was taken before the shims too. What does not link in CineStream now is 8 classes, all under `com.megix.settings`, for missing `android` classes such as `ClipboardManager`, `Bitmap`, and `View$OnApplyWindowInsetsListener`. Desktop never opens that screen. They were not shimmed, because loading does not need them.

**The 9 sample jars holding the string,** before and after:

| Plugin | Before | After |
|---|---|---|
| NextGenTV | `java/util/Object` | **Runs**, 1 provider, home 42 rows; 0 results for `nasa` |
| MegaProvider | `java/util/Object` | **Loads, registers 0 providers**, no error |
| ViStream | `java/util/Object` | Still not loaded: `androidx/preference/PreferenceManager` |
| BingeCloud | `java/util/Object` | Still not loaded: `NoSuchFieldError: MANUFACTURER` |
| Otakutsu | Runs, 1 provider | Same. The bad class is one it never reached |
| Eclipsia, EclipsiaAnime, Aniyomi, Fshare | Blocked by the scan | Same, as they should be |

So 4 of 9 failed on this defect and none does now; 2 of those 4 load, and **ViStream does not**: it reaches its next missing class on the first line of its `load`. Running or loaded went from 1 of 9 to 3 of 9.

**Nothing else moved.** The other 45 `.cs3` files of the `KMP-18` reviewed set (54 files, of which one never converts, so 53 jars) and the official 5, each as `.jar` and as `.cs3`, were each run in their own home on both builds. Same running count, providers, outcome and results or links for every file: 43 of 45 running, 50 providers, and all 10 official runs with the same counts (8 with results, 6 with links). Those counts are on a network, once each, so they say nothing moved and not that each plugin works.

### Tests

`DexConversionTest` runs a real dex through the real converter and sandbox into a real host. The fixture's `ConversionFixtureMissing` joins a class on no classpath with a host class. On the old converter it fails in a real host with `NoClassDefFoundError: java/util/Object`, the same words as the plugin. `ClassHierarchyTest` holds the cases no fixture reaches. The first attempt at this fixture passed on the old code, because a live join is cast away and the literal needs a class on no classpath: running it reverted is what found that.

Each mutation alone, 348 tests across `:feature:extensions:sandbox` and `:desktopApp`:

| Mutation | Failed |
|---|---|
| The old converter: dex2jar's frames, no rewrite | 3 |
| No rewrite, and no frames at all | 6 |
| The host's classes not consulted | 1 |
| The second type not compared, as dex2jar's walk | 3 |
| Fallback named `java/util/Object` | 4 |
| Plugin classes asked before the host's | 1 |
| Always `java/lang/Object` | 7 |
| Sync shims removed | 2 |
| Revision left at `-c2` | 1 (`a jar from the last converter is not used…`) |

The cycle guard in `ClassHierarchy.chain` was not mutated, because without it the test hangs rather than fails.

### Not verified

- **That any link plays.** Links were counted, not played: `--check-playback` was not run. A live window was not driven, so the Allow screen and the 37 warnings are as reported by the scan, not seen.
- **That exact superclasses are needed.** Always answering `java/lang/Object` also verified the same on CineStream, 874 of 877 with the same 8 not linking. The lookup is the correct answer in general and no real jar here was measured to depend on it.
- **CineSimkl's signed-in rows.** There is no account in a host, so they cannot appear. What happens beyond the login message was read, not run.
- **Windows and Linux.** Nothing loads there, with no sandbox.

### Found and left

- ViStream needs `androidx/preference/PreferenceManager` and BingeCloud `android.os.Build.MANUFACTURER`. Each is its own shim decision.
- MegaProvider loads and registers nothing. Why was not looked at.
- `AnimeONProvider` fails conversion with `OutOfMemoryError: Java heap space` (the process has `-Xmx512m`) and `DieGonRepo_Huhu` fails to load with a `ClassCastException`, the same on both builds.
- A superseded `<sha>-c2.jar` stays in `.store`: nothing deletes a jar from an older revision, here 1.7 MB.
- `JvmExtensionRuntime` still treats a throw from a plugin's `load()` as total, so the next plugin with one missing class in one provider's constructor loses all its providers, as CineStream did.

## BUG-20 — the iOS test gate cannot compile `:feature:home`'s tests · **measured 2026-11-03**

**Status:** done · #108 · found while adding `:feature:library`'s own iOS tests (`KMP-26`) · `:feature:home`

> **Filed as `BUG-17` and renumbered.** #106 took that id for a different defect minutes earlier, in a
> session running in parallel, and #108 reused it. `docs/tasks/README.md` says ids are stable and reusing
> one *"silently rewrites history that is already published"* — so this entry moved and #106's stayed, it
> being the one that landed first. `BUG-18` and `BUG-19` are spoken for by work in flight
> (`claude/bug-cinestream-load` and `claude/bug-links-as-they-arrive`), which is why this is 20 and not 18.
> **The lesson is not "pick a higher number": it is that `git fetch` immediately before choosing an id is
> not enough when another session is filing at the same time.** Check the file again at the moment of
> writing the heading.

`iosSimulatorTest` — the fifth, opt-in gate that `INF-04` added so a `commonTest` assertion is checked on
Kotlin/Native and not only on the JVM — **has been red since `KMP-20` merged (#92).** It does not fail an
assertion; it fails to compile:

    ./gradlew :feature:home:compileTestKotlinIosSimulatorArm64 -Pcloudstream.iosTest=true   # exit 1

    e: .../feature/home/src/commonTest/.../HomePagingTest.kt:15:9 Name contains illegal characters: ",".
    (and :35, :41, :58, :64 — five in all)

**Kotlin/Native refuses a comma inside a backticked name.** The JVM does not, so all five tests run and
pass under `jvmTest`, and the four gates this project actually runs never look at them. Measured on
`6456d54f`, which is master plus a docs-only change, so it is not caused by anything in flight.

**The fix is renaming five test functions** — the same rename `KMP-26` made to its own nine before they
would compile. Nothing else in the repository trips it: `:feature:library` is clean, and every other
`commonTest` either has no comma or does not reach Kotlin/Native.

**Why it matters more than five names:** the gate exists because a `Char` category, a `toString()` of a
`Double`, a date formatter or an atomic's memory model can differ between the JVM and Kotlin/Native, and
nothing else in this project would catch it. While it could not compile, **none of the 219 `commonTest`
assertions in the canary's modules were being checked there.**

### Fixed, and what the gate now reports

The five names lost their commas. Nothing else changed, and no assertion moved.

    ./gradlew iosSimulatorTest -Pcloudstream.iosTest=true     # exit 0, 1 m 35 s

**219 tests on an iOS simulator, 0 failures** — `:core:designsystem` 1, `:core:storage` 48,
`:feature:extensions` 4, `:feature:home` 10, `:feature:library` 14, `:feature:search` 3,
`:media:subtitle` 139. That is the first time every one of them has run there.

**What stops it happening again:** running the fifth gate. It is opt-in and not one of the four, so a
`commonTest` added without it stays unchecked until someone looks — which is exactly how this one lasted
from #92 to #108. Worth remembering when the next vertical adds a `commonTest`: the trap is silent on the
JVM, and the error (`Name contains illegal characters: ","`) names neither Kotlin/Native nor the reason.

## BUG-21 — a title that failed to open said "Error Loading Links" and printed an exception class · **low**

**Status:** done · #112 · found 2026-11-03 by the owner, opening a title with Invidious (its server refuses the request, so `load()` returns null) · `DetailCopy.kt`, `DesktopDetailSource.kt`

The page showed `Source error` / `Error Loading Links` / `IllegalStateException: provider returned nothing` / `Try again`. Two faults.

**1. Wrong sentence.** `toPageUiState` drew both `DetailLoad.Empty` and `DetailLoad.Failed` with `source_error` and `error_loading_links_toast`, the words for the links list. The title page fails before any link is asked for.

**2. A class name to a person.** The plugin host sends a failure as `"<simple class name>: <message>"` (`PluginHost.Writer.fail`), the client sanitises it into an `IOException` (`PluginHostClient`), `DetailViewModel.attempt` keeps its message as `DetailLoad.Failed.detail`, and `UiStateHost` draws `UiState.Error.detail` in the mono ramp. So anything a plugin threw was shown: `NoClassDefFoundError: …` the same way. Null from `load()` is `checkNotNull(...) { "provider returned nothing" }` at `PluginHost.kt` (`OP_LOAD`).

**The change.** The title page says `Couldn't open this title` / `Its source may be unavailable.` / `Try again`, for `Empty` and `Failed` alike, and draws no detail line. The links list keeps `Error Loading Links` and its own detail (`toLinksUiState` is untouched, because `BUG-19` rewrites it). Two new strings, `detail_load_failed_headline` and `detail_load_failed_body`, authored in all 62 directories; no existing string says this (`plugin_load_fail` needs a name the page does not have, and `source_error` is the wrong claim). The baseline moved as its sixth, additive move (`oracle-baseline/README.md`); `app/api/classes-jar.api` gains exactly 2 `R$string` fields, and `:app:dumpExtensionApi` writes the same bytes as the hand edit that preceded it.

**The diagnostic is dropped from the screen and logged.** A person cannot act on `IllegalStateException`, and the text is a plugin's, which the screen has been drawing in a font that cannot pass for the app's own voice. It is useful to someone debugging a plugin, so `DesktopDetailSource.details` writes `detail: <provider> could not open a title: <message>` to stderr, where the host already logs (`plugin host: …`, `android.util.Log`), with every `scheme://…` replaced by `<url>`: a plugin's message can repeat the request it made, and a request can carry a token. Failures that are not an `IOException` from the host are not logged there, and nothing is logged on the TV path, which has no desktop source.

**Null is not made a distinct state.** `Empty` and `Failed` now read identically, so nobody sees the difference. Making the host answer null as "nothing" would change the wire protocol, which `BUG-19` is rewriting, so it was left; the state holder still sees a failure.

### Tests

`desktopApp/.../detail/DetailPageFailureTest`, 7 tests, each mutation run alone against the committed fix:

| Mutation | Result |
|---|---|
| `DetailCopy.kt` reverted to master | 3 fail: wording, the other-language words, class name off the page |
| `UiState.Error(titlePrompt(retry), detail)` | 2 fail: wording, class name off the page |
| `withoutUrls` returns its input | 1 fails: URL not logged |

`:core:strings:check -Pcloudstream.strings.guards=fail`, `:feature:detail:jvmTest`, `:desktopApp:jvmTest` and `:desktopApp:createDistributable` pass locally. The full gates were not run here.

Rendered at 1280x800 through `DetailScreenDesktop` (`-Pcloudstream.home.renders=<dir>`): English and Arabic, both looked at.

### Not verified

- **The reported case end to end.** Invidious in a fresh home, packaged app, `--check-extensions nasa`: search returned 0 results, so there was no title to open and `load()` was never called with a null answer. The screen is rendered from a `DetailLoad.Failed` state, not reached through the plugin. The log line was tested only as a pure function (`withoutUrls`), not seen being written.
- **The 60 non-English translations.** All machine, unreviewed. Least sure: `so` (Somali) and `ti` (Tigrinya), also `my`, `ckb`, `or`, `as`. `apc` and `ars` are colloquial Arabic. `qt` is the Minion file, invented in its style. `pt` and `pt+BR` are identical.
- **The TV detail screen.** It uses the same `toPageUiState`; only the desktop one was rendered.

### Found and left

- **Other screens print the same raw text.** `HomeCopy` (`HomeStatus.Failed` -> `UiState.Error(…, detail)`), `SearchCopy` (`SearchStatus.Failed`) and `LibraryCopy` pass a failure's message to the same mono line, and the home and search view models build the detail from the failure (`HomeViewModel` via `failure.reason()`, search via `SearchOutcome.Failed`; neither read for what it puts in the string). Whether a search or home failure shows a class name was not run. This is the same state in kind but not the page that was reported.
- **`search_state_retry` ("Try again") is authored only in the base file.** The Arabic render shows `Try again` in English under an Arabic headline. It is every locale, and every screen that uses it.
- **The Arabic render is not mirrored**: the back button stays top left. `ImageComposeScene` took the locale for the strings and not the layout direction here; whether the window does was not checked.
- **A plugin that answers null from `load()` is still logged by the host as a failure** (`IllegalStateException`), not as a null.

## BUG-22 — a link picked early never got its subtitles · **medium**

**Status:** in pr · found 2026-11-03 while building `BUG-19` · `DetailViewModel.kt`, `SubtitleSession.kt`, `SubtitlePreference.kt`, `PlayerScreen.kt`, `DesktopDetailViewModels.kt`

`BUG-19` made a link pickable as soon as it arrives, and picking one stopped the search. Subtitles arrive later than links, so the earlier the pick, the fewer the tracks. Measured with CineStream on "inception", through `--check-extensions --verbose`: the first link at 485 ms with **0** subtitles, the first subtitle at 2,693 ms, and 175 by 64 s. Someone clicking the first row got a player with an empty list.

**The owner's call: match the existing app.** So this does what `GeneratorPlayer` and `PlayerGeneratorViewModel` do:

| | Android | Desktop now |
|---|---|---|
| Playing a link before the search ends (`overlayLoadingSkipButton`) | the job goes on | `LinkActivated` no longer stops it |
| A subtitle found after that | joins the player's list | `SubtitleSession.offer` |
| The preferred language | `SUBTITLE_AUTO_SELECT_KEY`, else `en` | `~/.cloudstream4/subtitle-language`, else `en` |
| When it turns on | when a matching track arrives, the first by name | the same, at most once per stream |
| A later arrival | leaves what is on alone | the same |
| Choosing a track | saves its language | the same; a track with no language saves nothing |
| Choosing Off | saves "none", so nothing turns on again | the same |

This changes what `KMP-25` settled, *"Starts off"*: a fresh install now opens with English on, as Android does.

**Where it differs, on purpose.** Android's search ends when the player does, because its source list is inside the player. Desktop's list is the page under the player, so backing out lands on a list that is still growing, and the search stops when that list is dismissed or the page is left. `BUG-19`'s 90 s ceiling still bounds it. The list also holds its rows while it is open and refills next time: it grows upward from its foot, so a track arriving would slide the row under the pointer as it is clicked. An empty list has nothing to slide, so the first tracks do appear in it.

**Measured.** `--check-subtitle-render <out> 1280x720 plugin:CineTmdb:<query> --first-link --keep-searching`, silent, in a fresh home: nothing chosen by hand, so only an arriving track can put a cue up.

| Query | Tracks at the pick | Tracks at the end of the run | Turned on | |
|---|---|---|---|---|
| inception | 0 | 174 | English | real frames, cue drawn (PNG read) |
| the godfather | 0 | 200 | English | real frames, cue drawn (PNG read) |

The first attempt at this check said `cue-on-screen=true` over a grey frame with **no cue in the PNG**: it was asking the timeline, and shooting before the screen's own tick had drawn anything. It now waits, and the PNGs were read rather than the flag. Reverting `listed`, the `settled` guard and the `stopLinks` removal together fails the new tests (see the PR).

**Not done.**

- **A language setting.** Android has one in its subtitle settings. Here the language changes only by choosing a track.
- **The next track when the preferred one fails.** Android does not try one either: this shows the failure and leaves the list to the person.
- **Whether the track fits the stream.** The first English track by name is not necessarily cut for this link's release, and the Godfather run's first cue is that file's own credit line. Android chooses the same way. Whether it stays in time over a whole film was not measured.
- **The window itself, and sound.** All of this ran offscreen and silent, so no one has clicked it live.

## BUG-23 — search stuck on its loading cards for good · **high**

**Status:** in pr · found 2026-11-03 by the owner, whose screenshot shows `other mo` over loading cards with `Sources 5 / 5` · `Viewmodel.kt` (`DebounceQuery`)

`onQueryChanged` sets `SearchStatus.Loading` and hands the text to `DebounceQuery`, which ended in `distinctUntilChanged`. A settled query equal to the previous one was dropped, so no search ran and nothing ever replaced `Loading`. Return was the only way out.

Measured on the real source and view model, with the owner's five plugins in a fresh home:

| What was typed | Before | After |
|---|---|---|
| `other mo` | results in 2.7 s | results in 0.9 s |
| one more letter, deleted inside 200 ms | **still loading at 15 s** | results in 1.1 s |
| cleared, then `other mo` again | **still loading at 15 s** | results in 1.0 s |
| a different query | results in 1.1 s | results in 1.1 s |

The plugins were not at fault: each answered `other mo` in under 900 ms. `SearchViewModel` is the only user of `DebounceQuery`, so the filter is gone rather than worked around. The cost is one repeat search when the same words settle twice. Putting the filter back fails both new tests.

**Found alongside it, not changed.** Clearing the field within 200 ms of typing still lets that query run, so results appear under an empty field (`query=''`, `status=Showing`, run once in a test).
---

## BUG-24 — three more defects in the shared search state machine, split out of #91 · **medium**

**Status:** in pr · found 2026-11-02 while switching Android TV to `SearchScreenTv` (`KMP-12` step 4) ·
`SearchViewModel.kt`, `SearchHistory.kt`, `SearchMvi.kt`, `Main.kt`

`SearchViewModel` is shared: the desktop search screen runs it now, and `KMP-12` points Android TV at
it next. These were found by building the Android host, and **every one of them is in the desktop app
as shipped** — none was ever Android-only. They sat unfixed in #91 behind a change that cannot be
verified without a television, which is why they are their own entry.

**Overlap with `BUG-23`, which landed first (#114).** Four defects were found here. The fourth — the
same query retyped after a clear stalling on the skeleton — `BUG-23` fixed independently and better,
by deleting `distinctUntilChanged` from `DebounceQuery` in `:core:ui` rather than steering around it
in the ViewModel. That also closed the `dune` → `dunes` → `dune` round trip this entry had listed as
not fixed. Three defects are left here, and the test written for the fourth was dropped rather than
shipped beside `BUG-23`'s, which asserts the same thing.

### What was wrong

| | What happened | Why |
|---|---|---|
| 1 | Typing one letter where no source can quick-search put the screen into a state it could not leave by typing | `onQueryChanged` always raised the skeleton and emitted to the debounce, whatever the sources could answer |
| 2 | A debounce still pending when Enter arrives replaced the full search's results with the quick subset | an IME can commit text and press its action in one gesture; `SingleActiveQuery` then cancels the full search for the later quick one |
| 3 | Text cleared inside the 200 ms window was searched anyway, so results appeared under an empty field | `Cleared` stopped the in-flight request but not the queued debounce. `BUG-23` found this too and left it |

A fourth thing was not a defect in the ViewModel but a limit of it: `source.sources()` was read once
in `init`, so the `n / m` count in the chrome went stale when extensions were allowed or stopped after
the screen existed. `Main.kt` worked around it by **throwing the whole ViewModel away**
(`DesktopSearchViewModels.live = null`), which also discarded the results and the typed query.

### Measured

Against `origin/master` at `9786c98e4` — before `BUG-23` landed — with the new contracts in place and
only `SearchViewModel.kt` reverted, `:feature:search:jvmTest` reported **17 tests completed, 4
failed**, one per defect found. The baseline is 17 rather than 18 because `SourcesChanged` does not
exist on master, so that one case cannot be compiled against the old code at all.

### Fixed

- `SearchSources.quickSearchable` (defaults `true`, so no caller changes meaning). Typing with no
  quick-searchable source echoes the field and runs nothing; submit still runs a full search.
- A `submitted` query, cleared by the next keystroke, which a pending quick search is checked against.
- `showIdle` emits a blank through the debounce, which drops the query queued behind a clear.
- The debounce consumer re-checks that the settled query is still what the field holds.
- `SearchAction.SourcesChanged` re-reads the count and runs no search. `SearchHost` dispatches it when
  an approval changes what is installed, so **the user keeps their results** instead of losing them.

### Not fixed

- `:app`'s own `ApiRepositorySearchSource` is where `quickSearchable` is produced as `false`, and that
  file stays in #91 with the TV screen it serves. On desktop the value is `true`, which is what
  `BUG-12` made correct there.
- The live desktop window. Every one of these is a state-machine test.
