# BUG — found and not yet fixed

Each entry carries the measurement that found it. Several were found by an adversarial verification
pass **after** a plausible argument said the opposite, which is why the evidence column matters more
than the description.

---

## BUG-01 — `dur * 60` overflows on untrusted input · **medium**

**Status:** open · `ResultViewModel2.kt:345`, `EpisodeAdapter.kt:329`

```kotlin
durationText = if (dur == null || dur <= 0) null else txt(secondsToReadable(dur * 60, "0 mins"))
```

`duration` is `Int?` **minutes**, so `dur * 60` is Int arithmetic and wraps. Measured:
`dur = 35_791_395` → `-2_147_483_596` → renders `0 mins`; `dur = 40_000_000` → `-1_894_967_296` →
`0 mins`. `dur > 0` does not prevent the product going negative.

`EpisodeAdapter.kt:329` has the same shape via `runTime?.times(60L)?.toInt()`, which truncates a
`Long`.

**Why it matters beyond cosmetics:** `duration` comes from an extension-supplied `LoadResponse`.
`.claude/rules/dependency-security.md` classes extension output as **untrusted input that must be
validated**. This is that rule's case, not a hypothetical.

**Note:** this was first reported as *"unreachable dead English"* on the strength of reading the
`dur <= 0` guard. That was wrong and the overflow was found by running it. A fix that works in
minutes directly sidesteps the whole thing.

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
