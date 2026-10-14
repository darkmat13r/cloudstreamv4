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

