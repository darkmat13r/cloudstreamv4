# `oracle-baseline` — pre-flip ground truth. **Never regenerated.**

These six files are the byte-for-byte output of `:core:strings:generateComposeStrings` as it stood
**before** the strings flip, when `app/src/main/res` was the source of truth and `:core:strings`
was the generated side. They were captured from a `--rerun-tasks` execution against
`app/src/main/res` at commit `624e80ce`, with `aapt2 9.1.1-14792394 compile --legacy`.

The flip reverses the pipeline: the Compose tree becomes authored, Android `res` becomes generated.
Every later step of that work is measured against **this** directory. If a step changes a value, a
locale fallback, a reference resolution or a request outcome, it shows up here as a diff and a
human decides whether it was intended.

## Why these are not regenerated

Regenerating them would erase the only record of what the pipeline produced before the change, and
every subsequent comparison would be a comparison against itself. `OracleBaselineTest` exists to
make that impossible by accident: it fails the build the moment the live generator stops
reproducing these bytes.

When a diff appears, the answer is never `cp build/stringsOracle/* oracle-baseline/`. Either the
change to the generator or to the resources is wrong, or the owner reviews the diff value by value
and decides — in a commit of its own that says so — to move the baseline.

## The files

| File | Rows | What it holds |
|---|---:|---|
| `decoded.tsv` | 36,171 | aapt2's decoded table, one row per item: source file, type, name, plural category, kind (`STR`/`STYLED`/`REF`/`ATTR`/`UNSUPPORTED`), text, spans. The raw oracle, before resolution. |
| `emitted.tsv` | 38,195 | What the generator wrote: one row per emitted value, keyed by output directory, type, name, plural category. Includes resolved references, locale-fallback copies and the `values-b+he` / `values-b+id` copies. |
| `lookup.tsv` | 73,558 | For each of the 87 parity requests, the value Android resolves for every key — the per-locale lookup table the parity test compares Compose against. |
| `requests.tsv` | 87 | The parsed `parity-requests.txt`: request id, Android tag, Compose language/script/region, expectation, note. |
| `findings.tsv` | 0 | Every finding the pipeline raised. **Empty since the flip** — it was 31 `STRAY_TEXT_REMOVED` and 34 `STYLED_AS_PLAIN_TEXT`, and a generated res has neither stray text nor `<b>`. |
| `report.txt` | 12 | The human-readable run summary. |


## What the flip moved, and what it did not

`app/src/main/res` is generated from the authored Compose tree as of `I18N-07` step 4.2, so these oracles were
recaptured. The two that decide whether anyone notices did not move at all:

| file | before | after |
|---|---|---|
| **`lookup.tsv`** | 66,315 | **0 lines changed** — Android resolves every one of the 66,315 cells the same way |
| **`emitted.tsv`** | 34,561 | **byte-identical to the PR 0 freeze** — the Compose tree is exactly what it was |
| `requests.tsv` | 87 | unchanged |
| `decoded.tsv` | 32,649 | same count, 114 lines differ — the 22 `donottranslate` names changed file |
| `findings.tsv` | 65 | 0 |

`emitted.tsv` returning to its PR 0 bytes is the strongest statement available here: the tree Compose packages after
regenerating the res from it is the same tree that was frozen before any of this started.

**PR 5 then re-keyed `lookup.tsv` without changing it.** Deleting the empty `values-arz` moved `arz` from a
directory-derived request to a pinned one in `parity-requests.txt`, so 762 rows changed id from `values-arz` to
`arz`. 762 lost, 762 invented, **0 with a different value** — and the sorted value column of the whole file has the
same md5 before and after (`3a1c1253…`). The row keys moved; nothing a user reads did.

What does change is `decoded.tsv`'s `location` column for 22 keys, because they moved from
`values/donottranslate-strings.xml` into `values/strings.xml`, where every other user-visible string already lived.

Row counts exclude the `#` header line that the four TSVs carry; `findings.tsv` and `report.txt`
have no header.

Headline numbers from `report.txt`, which are the ones later steps quote:

```
decoded: 63 directories, 32538 entries
visible keys: 758 (4 plurals)
emitted values by reason: {OWN=32428, REFERENCE_IN_LOCALE=500, ANDROID_FALLBACK=172}
locale copy values-b+he <- values-b+iw: 554 values
locale copy values-b+id <- values-b+in: 743 values
emitted directories: 64, values: 34397
```

## The one line that is not compared

`report.txt` line 1 is `aapt2 <version> compile --legacy; guards=<mode>`. Both halves are
environment, not output: the aapt2 version moves with AGP, and the guard mode moves with
`-Pcloudstream.strings.guards`, which pull requests pass as `fail` and local builds default to
`report`. `OracleBaselineTest` compares `report.txt` from line 2 on and asserts only the *shape* of
line 1. The other five files were verified byte-identical across both guard modes and across a
forced rerun; they are compared whole.

## Moved on purpose: `I18N-05`, broken translations

## Fourth move, 2026-11-03 — `KMP-25`, 3 new keys for a subtitle track that fails

Approved by the owner for new keys ("you can add any new strings (should be translated)", `I18N-09`). Additive,
and every key is authored in all 62 directories, like the third move.

| file | before | after | changed | lost |
|---|---|---|---|---|
| `lookup.tsv` | 69,295 | 69,556 | **0** | **0** |
| `decoded.tsv` | 33,133 | 33,319 | **0** | **0** |
| `emitted.tsv` | 35,059 | 35,251 | **0** | **0** |
| `requests.tsv` | 87 | 87 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 |

Measured by set difference over whole lines, not by reading the diff: every line that was in a file before is
in it after, in the same order, and every new line names one of the 3 keys. Each key has 62 lines in `decoded.tsv`,
64 in `emitted.tsv` and 87 in `lookup.tsv`. `report.txt` moved only in its counts: `OWN` 32,819 -> 33,005 (3 x 62),
visible keys 786 -> 789, and 3 more values in each of the two locale copies. **`REFERENCE_IN_LOCALE` stayed at 500
and `ANDROID_FALLBACK` at 172.** `expectedOwn` 32,819 -> 33,005.

## Fifth move, 2026-11-03 — `KMP-24`, 27 new keys for the extension review list

New keys are approved in general (`I18N-09`, above), and the brief for `KMP-24` asks for this. Additive, every key
authored in all 62 directories, none of them a plural.

| file | before | after | changed | lost |
|---|---|---|---|---|
| `lookup.tsv` | 69,556 | 71,905 | **0** | **0** |
| `decoded.tsv` | 33,319 | 34,993 | **0** | **0** |
| `emitted.tsv` | 35,251 | 36,979 | **0** | **0** |
| `requests.tsv` | 87 | 87 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 |

Measured by whole-line set difference twice over, a multiset in Python and `sort | comm -23`: every line that was in
a file before is in it after, in the same order, and every new line names one of the 27 keys. In `lookup.tsv` each key
appears for all 87 requests, 2,349 cells. `report.txt` moved only in its counts: `OWN` 33,005 -> 34,679 (27 x 62), visible
keys 789 -> 816, and 27 more values in each of the two locale copies. **`REFERENCE_IN_LOCALE` stayed at 500 and
`ANDROID_FALLBACK` at 172.** `expectedOwn` 33,005 -> 34,679.

## Twelfth move, 2026-11-05 — `KMP-42`, 4 new keys for TV search's source panel

New keys are approved in general (`I18N-09`, above). Additive, all four authored in all 62 directories, none a plural. Measured by whole-line set
difference twice over, a multiset in Python and `sort | comm`: 0 lines lost or changed in any file, and every new line names one of the 4 keys: 248 in
`decoded.tsv`, 256 in `emitted.tsv`, 348 in `lookup.tsv`. **`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 172.**
`expectedOwn` 35,609 -> 35,857.

## Eleventh move, 2026-11-04 — `BUG-28`, 1 new key for a plugin's new version

New keys are approved in general (`I18N-09`, above). Additive, authored in all 62 directories, not a plural. Measured by whole-line set
difference twice over, a multiset in Python and `sort | comm`: 0 lines lost or changed in any file, and every new line names the key: 62 in
`decoded.tsv`, 64 in `emitted.tsv`, 87 in `lookup.tsv`. **`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 172.**
`expectedOwn` 35,547 -> 35,609.

## Tenth move, 2026-11-04 — `KMP-29`, 4 new keys for choosing another source in the player

New keys are approved in general (`I18N-09`, above). Additive, all four authored in all 62 directories, none a plural.

| file | before | after | changed | lost |
|---|---|---|---|---|
| `lookup.tsv` | 72,775 | 73,123 | **0** | **0** |
| `decoded.tsv` | 35,613 | 35,861 | **0** | **0** |
| `emitted.tsv` | 37,619 | 37,875 | **0** | **0** |
| `requests.tsv` | 87 | 87 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 |

Measured by whole-line set difference twice over, a multiset in Python and `sort | comm`: every line that was in a
file before is in it after, and every new line names one of the 4 keys. `report.txt` moved only in its counts: `OWN`
35,299 -> 35,547 (4 x 62), visible keys 826 -> 830, and 4 more values in each of the two locale copies.
**`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 172.** `expectedOwn` 35,299 -> 35,547.

## Ninth move, 2026-11-04 — `KMP-31`, 4 new keys for picture size in the player

New keys are approved in general (`I18N-09`, above) and the owner asked for this feature. Additive, all 4 keys authored
in all 62 directories, none a plural.

| file | before | after | changed | lost |
|---|---|---|---|---|
| `lookup.tsv` | 72,427 | 72,775 | **0** | **0** |
| `decoded.tsv` | 35,365 | 35,613 | **0** | **0** |
| `emitted.tsv` | 37,363 | 37,619 | **0** | **0** |
| `requests.tsv` | 87 | 87 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 |

Measured by whole-line set difference twice over, a multiset in Python and `sort | comm`: every line that was in a
file before is in it after, and every new line names one of the 4 keys. Each key has 62 lines in `decoded.tsv`, 64 in
`emitted.tsv` and 87 in `lookup.tsv` (348 cells). `OWN` 35,051 -> 35,299 (4 x 62), visible keys 822 -> 826.
**`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 172.** `expectedOwn` 35,051 -> 35,299.

## Eighth move, 2026-11-03 — `KMP-28`, 2 new keys for mute in the player

New keys are approved in general (`I18N-09`, above). Additive, both keys authored in all 62 directories, neither a plural.

| file | before | after | changed | lost |
|---|---|---|---|---|
| `lookup.tsv` | 72,253 | 72,427 | **0** | **0** |
| `decoded.tsv` | 35,241 | 35,365 | **0** | **0** |
| `emitted.tsv` | 37,235 | 37,363 | **0** | **0** |
| `requests.tsv` | 87 | 87 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 |

Measured by whole-line set difference twice over, a multiset in Python and `sort | comm`: every line that was in a
file before is in it after, and every new line names one of the 2 keys. Each key has 62 lines in `decoded.tsv`, 64 in
`emitted.tsv` and 87 in `lookup.tsv` (174 cells). `report.txt` moved only in its counts: `OWN` 34,927 -> 35,051
(2 x 62), visible keys 820 -> 822, and 2 more values in each of the two locale copies.
**`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 172.** `expectedOwn` 34,927 -> 35,051.

## Seventh move, 2026-11-03 — `KMP-27`, 2 new keys for full screen in the player

New keys are approved in general (`I18N-09`, above) and the owner asked for this feature. Additive, both keys authored
in all 62 directories, neither a plural.

| file | before | after | changed | lost |
|---|---|---|---|---|
| `lookup.tsv` | 72,079 | 72,253 | **0** | **0** |
| `decoded.tsv` | 35,117 | 35,241 | **0** | **0** |
| `emitted.tsv` | 37,107 | 37,235 | **0** | **0** |
| `requests.tsv` | 87 | 87 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 |

Measured by whole-line set difference twice over, a multiset in Python and `sort | comm`: every line that was in a
file before is in it after, and every new line names one of the 2 keys. Each key has 62 lines in `decoded.tsv`, 64 in
`emitted.tsv` and 87 in `lookup.tsv` (174 cells). `report.txt` moved only in its counts: `OWN` 34,803 -> 34,927
(2 x 62), visible keys 818 -> 820, and 2 more values in each of the two locale copies.
**`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 172.** `expectedOwn` 34,803 -> 34,927.

## Sixth move, 2026-11-03 — `BUG-21`, 2 new keys for a title that could not be opened

New keys are approved in general (`I18N-09`, above) and the owner approved this fix. Additive, both keys authored in
all 62 directories, neither a plural.

| file | before | after | changed | lost |
|---|---|---|---|---|
| `lookup.tsv` | 71,905 | 72,079 | **0** | **0** |
| `decoded.tsv` | 34,993 | 35,117 | **0** | **0** |
| `emitted.tsv` | 36,979 | 37,107 | **0** | **0** |
| `requests.tsv` | 87 | 87 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 |

Measured by whole-line set difference twice over, a multiset in Python and `sort | comm`: every line that was in a
file before is in it after, in the same order, and every new line names one of the 2 keys. Each key has 62 lines in
`decoded.tsv`, 64 in `emitted.tsv` and 87 in `lookup.tsv` (174 cells). `report.txt` moved only in its counts: `OWN`
34,679 -> 34,803 (2 x 62), visible keys 816 -> 818, and 2 more values in each of the two locale copies.
**`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 172.** `expectedOwn` 34,679 -> 34,803.

## Third move, 2026-11-03 — `I18N-09`, 6 new keys for the desktop screens

Approved by the owner for new keys: "you can add any new strings (should be translated)". Additive
again, and this time every key is authored in all 62 directories, so `REFERENCE_IN_LOCALE` and
`ANDROID_FALLBACK` have nothing new to carry.

| file | before | after | changed | lost |
|---|---|---|---|---|
| `lookup.tsv` | 68,664 | 69,295 | **0** | **0** |
| `decoded.tsv` | 32,673 | 33,133 | **0** | **0** |
| `emitted.tsv` | 34,585 | 35,059 | **0** | **0** |
| `requests.tsv` | 87 | 87 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 |

Measured by set difference over whole lines, not by reading the diff: every line that was in a file
before is in it after, in the same order, and every new line names one of the 6 keys. In
`lookup.tsv` each of the 6 appears for all 87 requests: 87 cells for each `<string>`, 196 for
`home_sections_failed`, whose categories differ per locale. `report.txt` moved only in its counts: `OWN`
32,447 -> 32,819 (6 x 62), visible keys 780 -> 786, plurals 9 -> 10, and 6 more values in each of
the two locale copies. **`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 172.**
`expectedOwn` 32,447 -> 32,819.

## Second move, 2026-10-15 — 22 new search keys

The second move is the first that was purely **additive**: `KMP-12`
step 3 added 22 keys to `values/strings.xml` for the new search screen, and a frozen pre-flip
baseline cannot contain a key that did not exist before the flip. Every new string will hit this,
so the measurement that justified the move is worth stating in full:

| file | before | after | changed | lost |
|---|---|---|---|---|
| `lookup.tsv` | 66,315 | 68,664 | **0** | **0** |
| `decoded.tsv` | 32,646 | 32,673 | **0** | **0** |
| `emitted.tsv` | 34,558 | 34,585 | **0** | **0** |
| `requests.tsv` | 87 | 87 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 |

Every one of the 2,349 new `lookup.tsv` cells names one of the 22 new keys, and all 22 appear —
checked by set difference, not by eye. `report.txt` moved only in its counts: `OWN` 32,425 ->
32,447, visible keys 758 -> 780, plurals 4 -> 9. **`REFERENCE_IN_LOCALE` stayed at 500 and
`ANDROID_FALLBACK` at 172**, which is the number that matters: no existing key's locale fallback
changed. `expectedOwn` 32,425 -> 32,447.

The first move was different in kind and is kept below for contrast — it changed cells rather than
adding them.

The baseline was moved once after the flip, by hand, in a commit of its own. `lookup.tsv` changed in **9 cells**
(66,315 rows before and after; 0 lost, 0 invented): `iw`, `he`, `he-IL` and `values-b+iw` for
`next_episode_time_hour_format`; `values-b+lv`, `values-b+qt` and `values-b+so` for `duration_format`;
`values-b+my` and `values-b+so` for `next_episode_time_hour_format`. Three keys were deleted from the authored tree
(`so` x2, `qt` x1), so `decoded.tsv` 32,649 -> 32,646, `emitted.tsv` 34,561 -> 34,558 and `OWN` 32,428 -> 32,425.
Details and reasoning in `docs/tasks/i18n.md` under `I18N-05`.
