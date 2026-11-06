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
| `decoded.tsv` | 37,084 | aapt2's decoded table, one row per item: source file, type, name, plural category, kind (`STR`/`STYLED`/`REF`/`ATTR`/`UNSUPPORTED`), text, spans. The raw oracle, before resolution. |
| `emitted.tsv` | 39,139 | What the generator wrote: one row per emitted value, keyed by output directory, type, name, plural category. Includes resolved references, locale-fallback copies and the `values-b+he` / `values-b+id` copies. |
| `lookup.tsv` | 75,124 | For each of the 87 parity requests, the value Android resolves for every key — the per-locale lookup table the parity test compares Compose against. |
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

## Nineteenth move, 2026-11-07 — `I18N-12`, 2 new keys for board 7i's season chips

New keys are approved in general (`I18N-09`). Additive, both authored in all 62 directories, neither a plural:
`season_specials` for season 0 and `season_other` for episodes with no season.

| file | before | after | added | changed | lost |
|---|---|---|---|---|---|
| `decoded.tsv` | 38,814 | 38,938 | 124 | 0 | 0 |
| `emitted.tsv` | 40,923 | 41,051 | 128 | 0 | 0 |
| `lookup.tsv` | 77,865 | 78,039 | 174 | 0 | 0 |
| `requests.tsv` | 87 | 87 | 0 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 | 0 |

Measured by whole-line set difference twice over, a multiset in Python and `sort | comm`: 0 lines lost in any
file, and every added line names one of the two keys. Each delta is exactly what it should be — 124 is 2 x 62
directories, 128 is that plus the two locale copies, 174 is 2 x the 87 parity requests. `report.txt` moved only
in its counts: `OWN` 38,324 -> 38,448 (2 x 62), visible keys 882 -> 884, and 2 more values in each locale copy.
**`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 173.** `expectedOwn` 38,324 -> 38,448.

**Redone from the live generator on train 1's baseline**, as the seventeenth move was. This was first measured
against the pre-train-1 baseline, where it was the eighteenth move with different before-counts; train 1 landed
`KMP-49`'s 6 keys first and took that number. On rebase the three `.tsv` files **auto-merged without a conflict**,
interleaving two independent sets of additions into a file no generator ever emitted — plausible, and quite
possibly green, since `OracleBaselineTest` compares bytes it would then be comparing against itself. They were
discarded and regenerated instead. A baseline that was merged rather than produced is not a baseline.

## Eighteenth move, 2026-11-07 — Train 1 (`#170`, `#174`, `#175`), one move: `KMP-49`'s 6 new keys for the TV library's sources

One combined baseline move for the train that merges `#170` (`KMP-38`), `#174` (`KMP-59`) and `#175` (`KMP-49`) together.
Only `#175` adds strings; `#170` and `#174` add none, and the regenerated oracle confirms that. `#175` recorded this move as
its own "seventeenth", branched from master's sixteenth; master's seventeenth (`KMP-44`) landed first, so the train
regenerated it once from the live generator on master's baseline with the seventeenth in it, and it is recorded here as
the eighteenth.

New keys are approved in general (`I18N-09`, above). Additive. 5 are authored in all 62 directories, none a plural
(`library_not_connected`, `library_on_device`, `library_source`, `library_source_note`, `library_synced_format`); 1 is a
format with no words (`library_episodes_progress`), in the default directory only.

| file | before | after | added | changed | lost |
|---|---|---|---|---|---|
| `decoded.tsv` | 38,503 | 38,814 | 311 | 0 | 0 |
| `emitted.tsv` | 40,602 | 40,923 | 321 | 0 | 0 |
| `lookup.tsv` | 77,343 | 77,865 | 522 | 0 | 0 |
| `requests.tsv` | 87 | 87 | 0 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 | 0 |

Measured against master's baseline by whole-line set difference twice over, a multiset in Python and `sort | comm`:
0 lines lost in any file, and every added line names one of the six keys, all six present. `decoded` +311 = 5 x 62 + 1;
`emitted` +321 = 5 x 64 + 1 (the two locale copies); `lookup` +522 = 6 x 87 requests. These are the same additions `#175`
measured on its own branch. `report.txt` moved only in its counts: `OWN` 38,013 -> 38,324, visible keys 876 -> 882, and
5 more values in each of the two locale copies. **`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 173.**
`expectedOwn` 38,013 -> 38,324.

## Seventeenth move, 2026-11-06 — `KMP-44`, 3 new keys for `None` as Home's source

New keys are approved in general (`I18N-09`). Additive, all three authored in all 62 directories, none a plural. Redone from the
live generator on master's baseline with the sixteenth move (`KMP-45`) in it.

| file | before | after | added | changed | lost |
|---|---|---|---|---|---|
| `decoded.tsv` | 38,317 | 38,503 | 186 | 0 | 0 |
| `emitted.tsv` | 40,410 | 40,602 | 192 | 0 | 0 |
| `lookup.tsv` | 77,082 | 77,343 | 261 | 0 | 0 |
| `requests.tsv` | 87 | 87 | 0 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 | 0 |

Measured by whole-line set difference twice over, a multiset in Python and `sort | comm`: 0 lines lost in any file, and every
added line names one of the three keys. `report.txt` moved only in its counts: `OWN` 37,827 -> 38,013 (3 x 62), visible keys
873 -> 876, and 3 more values in each of the two locale copies. **`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at
173.** `expectedOwn` 37,827 -> 38,013.

## Sixteenth move, 2026-11-06 — `KMP-45`, 5 new keys for Home's bookmarks rail

New keys are approved in general (`I18N-09`). Additive, authored in all 62 directories: four strings and one plural,
`home_bookmarks_count`, with each directory's own plural categories (the set `home_sections_failed` declares there). This move
branched from 35,609 and was redone, from the live generator, on master's baseline with the twelfth to fifteenth moves in it.

| file | before | after | added | changed | lost |
|---|---|---|---|---|---|
| `decoded.tsv` | 37,919 | 38,317 | 398 | 0 | 0 |
| `emitted.tsv` | 40,000 | 40,410 | 410 | 0 | 0 |
| `lookup.tsv` | 76,538 | 77,082 | 544 | 0 | 0 |
| `requests.tsv` | 87 | 87 | 0 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 | 0 |

Measured by whole-line set difference twice over, a multiset in Python and `sort | comm`: 0 lines lost in any file, and every
added line names one of the five keys. `report.txt` moved only in its counts: `OWN` 37,517 -> 37,827 (5 x 62), visible keys
868 -> 873, plurals 11 -> 12, and 5 more values in each of the two locale copies. **`REFERENCE_IN_LOCALE` stayed at 500 and
`ANDROID_FALLBACK` at 173.** `expectedOwn` 37,517 -> 37,827.

## Fifteenth move, 2026-11-06 — `Q15`, 15 new keys for one series' downloads on TV

New keys are approved in general (`I18N-09`, above). Additive. 12 are authored in all 62 directories, one of them a plural (`downloads_failed_count`); 3 are formats with no words (`downloads_joined`, `downloads_rate`, `downloads_episode_code`) in the default directory only. Measured by whole-line multiset difference against master after the fourteenth: 0 lines lost or changed in any file, and every new line names one of the keys: 835 in `decoded.tsv`, 861 in `emitted.tsv`, 1,414 in `lookup.tsv`; `requests.tsv` and `findings.tsv` unchanged. **`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 173.** The first `%%` in the corpus is this move's `downloads_watched`, which is why `FormatterParityTest` now expects `%`.
`expectedOwn` 36,770 -> 37,517, on top of the fourteenth.

## Fourteenth move, 2026-11-06 — `KMP-38`, 3 new keys for the TV detail page

New keys are approved in general (`I18N-09`, above). Additive, none a plural. Measured by whole-line set difference twice over,
a multiset in Python and `sort | comm`: **0 lines lost or changed in any file.** `expectedOwn` 36,604 -> 36,770.

This move branched from 35,609, the same point as the twelfth and thirteenth below, and the counts here are stated
**against master with both of those already in it**. Merged as a whole-line multiset union and checked both ways: 0
lines lost from either side, every line this branch contributed still present, and every line the merge added one of
this move's own three keys.

| file | before | after | added | changed | lost |
|---|---|---|---|---|---|
| `decoded.tsv` | 36,918 | 37,084 | 166 | 0 | 0 |
| `emitted.tsv` | 38,966 | 39,139 | 173 | 0 | 0 |
| `lookup.tsv` | 74,863 | 75,124 | 261 | 0 | 0 |
| `requests.tsv` | 87 | 87 | 0 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 | 0 |

Every added line names one of the three keys, and all three appear, checked by set difference rather than by eye. The counts
are exact and each is accounted for:

- **`decoded.tsv` +166** = `cast` 62 + `more_like_this` 62 + `duration_hours_minutes_format` 42. The first two are authored in
  all 62 directories; the third is authored only where its source key is, which is the 42 directories that have
  `download_time_left_hour_min_sec_format`. The other 20 resolve to the `values` form, which is what Android does anyway and
  what leaves room for a real translation later.
- **`lookup.tsv` +261** = 87 parity requests x 3 keys. Every request resolves all three.
- **`emitted.tsv` +173** = 166 own + 6 locale copies (`values-b+he` <- `values-b+iw` and `values-b+id` <- `values-b+in`, three
  keys each) + **1 Android fallback**.

**`REFERENCE_IN_LOCALE` stayed at 500. `ANDROID_FALLBACK` moved 172 -> 173**, and that one row is the only count in this move
that is not a plain addition, so it is named: `values-b+tl` resolves `duration_hours_minutes_format` to `%1$do %2$dm` from
`values-b+fil`. `tl` is the legacy code for Filipino and Android resolves one from the other; `fil` declares the key because
its own `download_time_left_hour_min_sec_format` does, and `tl` does not. No existing key's locale fallback changed.

**On the values themselves.** `cast` was not translated: it is each directory's own `cast_format` with the format argument and
its separator removed, so all 62 come from this project's existing human translations of the same word in the same sense. Three
were corrected rather than copied — `values-b+lt` ("Skleisti" is the verb, to spread), `values-b+ars` ("بث" is broadcast) and
`values-b+ti` (its separator is the Ethiopic word mark U+1361, which the strip missed). The first two are **pre-existing wrong
senses in `cast_format` itself**, reported rather than changed here. `duration_hours_minutes_format` is likewise derived, from
each directory's `download_time_left_hour_min_sec_format` with the seconds component removed, so it keeps each locale's own unit
letters — Filipino's `%1$do` for *oras*, German's `%1$dstd`, Japanese's `%1$d時間 %2$d分`. Only `more_like_this` is new
translation, and like `I18N-09`'s it has not been reviewed by a native speaker; every entry was checked to be in the same script
as that directory's existing strings, which caught one error.

## Thirteenth move, 2026-11-05 — `KMP-42`, 4 new keys for TV search's source panel

New keys are approved in general (`I18N-09`, above). Additive, all four authored in all 62 directories, none a plural. Measured by whole-line set
difference twice over, a multiset in Python and `sort | comm`: 0 lines lost or changed in any file, and every new line names one of the 4 keys: 248 in
`decoded.tsv`, 256 in `emitted.tsv`, 348 in `lookup.tsv`. **`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 172.**
`expectedOwn` 36,356 -> 36,604, on top of the twelfth (both moves branched from 35,609; merged as a whole-line multiset union, 0 lines lost from either side).

## Twelfth move, 2026-11-05 — `Q14`, 15 new keys for the TV downloads screen

New keys are approved in general (`I18N-09`, above). Additive. 12 are authored in all 62 directories, none a plural. 3 are formats with no words, `downloads_delete_chosen`, `downloads_film_subtitle` and `downloads_series_subtitle`, in the default directory only, as `extra_info_format` and `storage_size_format` are. Measured by whole-line set difference against master: 0 lines lost or changed in any file, and every new line names one of the keys: 747 in `decoded.tsv`, 771 in `emitted.tsv`, 1,305 in `lookup.tsv`. **`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 172.**
`expectedOwn` 35,609 -> 36,356.

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
