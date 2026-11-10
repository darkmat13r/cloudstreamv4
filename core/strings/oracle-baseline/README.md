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

## Thirty-fourth move, 2026-11-10 — `KMP-110c2`, 40 new English keys for the Mac's Extensions pane

New keys are approved in general (`I18N-09`). Additive: 38 strings and 2 plurals, authored in the English directory only
(the PM: the boards' German is a stand-in for lengths); the other 61 directories fall back to English until `I18N-26`
translates them. Regenerated from the live generator on master's thirty-second move (`38488365`, Train 18). Numbered
thirty-fourth because `#312`, the thirty-third, goes ahead of it in Train 19: if it lands first, this is regenerated on
top of it before this lands.

| file | before | after | added | changed | lost |
|---|---|---|---|---|---|
| `decoded.tsv` | 57,696 | 57,738 | 42 | 0 | 0 |
| `emitted.tsv` | 60,399 | 60,441 | 42 | 0 | 0 |
| `lookup.tsv` | 104,100 | 107,754 | 3,654 | 0 | 0 |
| `requests.tsv` | 87 | 87 | 0 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 | 0 |

Measured twice, by whole-line set difference with `sort | comm` and with a Python multiset that also checked order: no
line lost, every earlier line still there in the same order, and every added line names one of the new keys.
`decoded`'s 42 are the 38 strings and the 2 plurals' two categories each. `OWN` 55,622 -> 55,662, `REFERENCE_IN_LOCALE`
500 and `ANDROID_FALLBACK` 173 unchanged, and `expectedOwn` to match. `app/src/main/res`: 40 lines added, 0 removed;
`app/api/classes-jar.api` gains the 40 `R.string` fields and nothing else.

## Thirty-second move, 2026-11-10 — `I18N-23`, 1 new key for the subtitle picker (`BUG-77`)

New keys are approved in general (`I18N-09`). Additive: `subtitles_font_app` ("App font"), no plural, authored in all 62
directories (`OWN` grows by 62). The Android res came out with 62 lines added and 0 removed.

| file | before | after | added | changed | lost |
|---|---|---|---|---|---|
| `decoded.tsv` | 57,634 | 57,696 | 62 | 0 | 0 |
| `emitted.tsv` | 60,335 | 60,399 | 64 | 0 | 0 |
| `lookup.tsv` | 104,013 | 104,100 | 87 | 0 | 0 |
| `requests.tsv` | 87 | 87 | 0 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 | 0 |

Measured against master's baseline by whole-line set difference twice over, a multiset in Python and `sort | comm`: 0 lines
lost in any file, every line that was in a file before is in it after in the same order, and every added line names
`subtitles_font_app`. `report.txt` moved only in its counts: `OWN` 55,560 -> 55,622, visible keys 1,160 -> 1,161, plurals
unchanged at 30, and 1 more value in each of the two locale copies. **`REFERENCE_IN_LOCALE` stayed at 500 and
`ANDROID_FALLBACK` at 173.** `expectedOwn` 55,560 -> 55,622. Measured over the thirty-first move (`I18N-22`).

## Thirty-first move, 2026-11-09 — `I18N-22`, 1 new plural for `KMP-101`'s S3 banner

New keys are approved in general (`I18N-09`). Additive: one plural, `search_state_retry_sources`, authored in all 62
directories with each locale's own categories, 150 in all (`OWN` counts one value per key and directory, so it grows by 62).
First recorded as an unnumbered move on master's twenty-sixth; regenerated from the live generator on master's thirtieth
(`KMP-95d`) when Train 14 landed, and numbered then.

| file | before | after | added | changed | lost |
|---|---|---|---|---|---|
| `decoded.tsv` | 57,484 | 57,634 | 150 | 0 | 0 |
| `emitted.tsv` | 60,181 | 60,335 | 154 | 0 | 0 |
| `lookup.tsv` | 103,817 | 104,013 | 196 | 0 | 0 |
| `requests.tsv` | 87 | 87 | 0 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 | 0 |

I measured this against master's thirtieth move twice: by whole-line set difference with `sort | comm`, and with a
Python multiset that also checked order.
- No line was lost in any file, and every line that was there before is still there, in the same order.
- Every added line names the new key.
- `decoded`'s 150 are the key's categories summed over the 62 directories.
- `emitted`'s 154 are those 150, plus 3 for the `values-b+he` copy of `b+iw` (one, two, other) and 1 for the
  `values-b+id` copy of `b+in` (other).

`report.txt` moved only in its counts:
- `OWN` 55,498 -> 55,560;
- visible keys 1,159 -> 1,160, plurals 29 -> 30;
- one more value in each of the two locale copies.

**`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 173.** `expectedOwn` 55,498 -> 55,560, and
`OracleBaselineTest`'s row counts to the three after-counts above. The Android
res came out with 62 lines added and 0 removed, and `app/api/classes-jar.api` gains only the one `R` field.

The review's fix changed one value in place: iw's `one` form, "לנסות שוב מקור %d" -> "לנסות שוב %d מקור", so it no longer
reads as "source no. 1". One line changed in `decoded`, two in `emitted` (`b+iw` and its `values-b+he` copy) and four in
`lookup`, all at the same rows. No row was added or lost, and `report.txt` is unchanged.

## Thirtieth move, 2026-11-09 — `KMP-95d`, 1 new key: sign-ins last until you quit

New keys are approved in general (`I18N-09`), and the PM asked for this line (2026-11-09): until the Keychain (`KMP-95f`)
the Mac keeps sign-ins in memory only, and the screen says so. Additive: `account_signins_until_quit`, no plural, authored
in all 62 directories (`OWN` grows by 62). The Android res came out with 62 lines added and 0 removed. `report.txt` is the
generator's. Measured over the twenty-ninth move (`KMP-95c`), which this PR is stacked on.

| file | before | after | added | changed | lost |
|---|---|---|---|---|---|
| `decoded.tsv` | 57,422 | 57,484 | 62 | 0 | 0 |
| `emitted.tsv` | 60,117 | 60,181 | 64 | 0 | 0 |
| `lookup.tsv` | 103,730 | 103,817 | 87 | 0 | 0 |
| `requests.tsv` | 87 | 87 | 0 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 | 0 |

Measured twice over, a multiset in Python and `sort | comm`: 0 lines lost in any file, every line that was in a file
before is in it after in the same order, and every added line names the one key: 62 lines in `decoded`, 64 in `emitted`
(the `values-b+he` and `values-b+id` copies), 87 in `lookup`. `report.txt` moved only in its counts: `OWN` 55,436 ->
55,498, visible keys 1,158 -> 1,159, plurals unchanged at 29, and one more value in each of the two locale copies.
**`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 173.** `expectedOwn` 55,436 -> 55,498.

## Twenty-ninth move, 2026-11-09 — `KMP-95c`, 1 new key: a profile not added while the PINs could not be read

New keys are approved in general (`I18N-09`). The reviewer of `#286` asked that Add adds nothing when a PIN left under
the new profile's id cannot be forgotten, and says so. Additive: `account_profile_not_added`, no plural, authored in
all 62 directories (`OWN` grows by 62). The Android res came out with 62 lines added and 0 removed. `report.txt` is the
generator's.

| file | before | after | added | changed | lost |
|---|---|---|---|---|---|
| `decoded.tsv` | 57,360 | 57,422 | 62 | 0 | 0 |
| `emitted.tsv` | 60,053 | 60,117 | 64 | 0 | 0 |
| `lookup.tsv` | 103,643 | 103,730 | 87 | 0 | 0 |
| `requests.tsv` | 87 | 87 | 0 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 | 0 |

Measured against the twenty-eighth move's baseline twice over, a multiset in Python and `sort | comm`: 0 lines lost in
any file, and every added line names the one key: 62 lines in `decoded`, 64 in `emitted` (the `values-b+he` and
`values-b+id` copies), 87 in `lookup`. `report.txt` moved only in its counts: `OWN` 55,374 -> 55,436, visible keys
1,157 -> 1,158, plurals unchanged at 29, and one more value in each of the two locale copies. **`REFERENCE_IN_LOCALE`
stayed at 500 and `ANDROID_FALLBACK` at 173.** `expectedOwn` 55,374 -> 55,436.

## Twenty-eighth move, 2026-11-09 — `KMP-95c`, 1 new key: PINs that could not be read

New keys are approved in general (`I18N-09`). The reviewer of `#286` found that a `pins` file that could not be read
mid-session read as no file, which opened a locked profile; now the prompt refuses and says why. Additive:
`account_pin_unreadable`, no plural, authored in all 62 directories (`OWN` grows by 62). The Android res came out with
62 lines added and 0 removed. `report.txt` is the generator's.

| file | before | after | added | changed | lost |
|---|---|---|---|---|---|
| `decoded.tsv` | 57,298 | 57,360 | 62 | 0 | 0 |
| `emitted.tsv` | 59,989 | 60,053 | 64 | 0 | 0 |
| `lookup.tsv` | 103,556 | 103,643 | 87 | 0 | 0 |
| `requests.tsv` | 87 | 87 | 0 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 | 0 |

Measured against the twenty-seventh move's baseline twice over, a multiset in Python and `sort | comm`: 0 lines lost in
any file, and every added line names the one key: 62 lines in `decoded`, 64 in `emitted` (the `values-b+he` and
`values-b+id` copies), 87 in `lookup`. `report.txt` moved only in its counts: `OWN` 55,312 -> 55,374, visible keys
1,156 -> 1,157, plurals unchanged at 29, and one more value in each of the two locale copies. **`REFERENCE_IN_LOCALE`
stayed at 500 and `ANDROID_FALLBACK` at 173.** `expectedOwn` 55,312 -> 55,374.

## Twenty-seventh move, 2026-11-09 — `KMP-95c`, 1 new key: a PIN change that could not be saved

New keys are approved in general (`I18N-09`). The reviewer of `#286` asked that a PIN change that cannot be written
says so and changes nothing. Additive: `account_pin_not_saved`, no plural, authored in all 62 directories (`OWN` grows
by 62). The Android res came out with 62 lines added and 0 removed. `report.txt` is the generator's.

| file | before | after | added | changed | lost |
|---|---|---|---|---|---|
| `decoded.tsv` | 57,236 | 57,298 | 62 | 0 | 0 |
| `emitted.tsv` | 59,925 | 59,989 | 64 | 0 | 0 |
| `lookup.tsv` | 103,469 | 103,556 | 87 | 0 | 0 |
| `requests.tsv` | 87 | 87 | 0 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 | 0 |

Measured against master's baseline twice over, a multiset in Python and `sort | comm`: 0 lines lost in any file, every
line that was in a file before is in it after in the same order, and every added line names the one key: 62 lines in
`decoded`, 64 in `emitted` (the `values-b+he` and `values-b+id` copies), 87 in `lookup`. `report.txt` moved only in its
counts: `OWN` 55,250 -> 55,312, visible keys 1,155 -> 1,156, plurals unchanged at 29, and one more value in each of the
two locale copies. **`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 173.** `expectedOwn` 55,250 -> 55,312.

## Twenty-sixth move, 2026-11-08 — Train 9 (`#205`, `#228`, `#235`, `#224`, `#227`, `#230`, `#239`), one move: 12 new keys

One combined baseline move for the train that merges `#205` (`KMP-66`), `#228` (`I18N-17`), `#235` (`I18N-18`), `#224`
(`BUG-52`), `#227` (`BUG-54`), `#230` (`BUG-55`) and `#239` (`KMP-80`) together. Three of them carry keys, and each had
recorded its own move from its own branch point: `#205` as a "twenty-third" (after master's twenty-second), `#228` as an
unnumbered "Move for `I18N-17`" (after master's twenty-third) and `#230` as a "twenty-fourth" (branched before master's
twenty-first). Those headings collided with each other and with master's twenty-third, so none of them is kept: the train
regenerated once from the live generator on master's baseline, and all three are recorded here as this one move. It was
first recorded as the twenty-fourth on master's twenty-third; trains 11 and 12 landed the twenty-fourth and twenty-fifth
first, so the train merged master and regenerated once more on master's baseline with the twenty-fifth in it. `#235`, `#224`, `#227` and `#239` add none.

New keys are approved in general (`I18N-09`, above). Additive, every key authored in all 62 directories, none a plural
(`OWN` grows by 12 x 62 = 744):

| PR | keys | `decoded` | `emitted` | `lookup` |
|---|---:|---:|---:|---:|
| `#205` `KMP-66`, the TV playback panels: `player_panel_failed`, `player_panel_keeps`, `player_panel_numbered`, `player_panel_on`, `player_panel_open`, `player_panel_quality_profile`, `player_panel_quality_unknown`, `player_panel_retry_all`, `player_panel_style`, `player_panel_use` | 10 | 620 | 640 | 870 |
| `#228` `I18N-17`, the local library's name: `library_source_local` | 1 | 62 | 64 | 87 |
| `#230` `BUG-55`, the wrong-PIN wait: `pin_error_wait` (the time is a `0:30`-style countdown) | 1 | 62 | 64 | 87 |
| **total** | **12** | **744** | **768** | **1,044** |

| file | before | after | added | changed | lost |
|---|---|---|---|---|---|
| `decoded.tsv` | 56,492 | 57,236 | 744 | 0 | 0 |
| `emitted.tsv` | 59,157 | 59,925 | 768 | 0 | 0 |
| `lookup.tsv` | 102,425 | 103,469 | 1,044 | 0 | 0 |
| `requests.tsv` | 87 | 87 | 0 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 | 0 |

Measured against master's baseline by whole-line set difference twice over, a multiset in Python and `sort | comm`: 0 lines
lost in any file, every line that was in a file before is in it after in the same order, and every added line names exactly
one of the twelve keys, all twelve present: 12 x 62, 12 x 64 and 12 x 87. Split by the PR that owns each key, the added lines
equal, file by file, what each PR measured on its own branch (the per-PR columns above). `report.txt` moved only in its
counts: `OWN` 54,506 -> 55,250 (12 x 62), visible keys 1,143 -> 1,155, plurals unchanged at 29, and 12 more values in each of the
two locale copies. **`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 173.** `expectedOwn` 54,506 -> 55,250.

## Twenty-fifth move, 2026-11-08 — `I18N-21`, 41 new keys for the account boards (`DS-25`)

New keys are approved in general (`I18N-09`), and the owner approved the boards' wording. Additive: 41 `account_*`
strings, no plurals, each authored in all 62 directories (`OWN` grows by 41 x 62 = 2,542). The Android res came out
with 2,542 lines added and 0 removed. `report.txt` is the generator's.

| file | before | after | added | changed | lost |
|---|---|---|---|---|---|
| `decoded.tsv` | 53,950 | 56,492 | 2,542 | 0 | 0 |
| `emitted.tsv` | 56,533 | 59,157 | 2,624 | 0 | 0 |
| `lookup.tsv` | 98,858 | 102,425 | 3,567 | 0 | 0 |
| `requests.tsv` | 87 | 87 | 0 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 | 0 |

Measured against master's baseline by whole-line set difference twice over, a multiset in Python and `sort | comm`: 0 lines
lost in any file, every line that was in a file before is in it after in the same order, and every added line names exactly
one of the 41 keys: 62 lines each in `decoded`, 64 in `emitted` (the `values-b+he` and `values-b+id` copies), 87 in
`lookup`. `report.txt` moved only in its counts: `OWN` 51,964 -> 54,506, visible keys 1,102 -> 1,143, plurals unchanged at
29, and 41 more values in each of the two locale copies. **`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at
173.** `expectedOwn` 51,964 -> 54,506.

The review's fixes changed two of the 41 values in place: nn `account_pin_set`, "Set PIN" -> "Vel PIN", and nn
`account_pin_set_title`, "Set ein PIN" -> "Vel ein PIN". Two lines changed in each of `decoded`, `emitted` and `lookup`,
at the same rows; no row was added or lost, and `report.txt` is unchanged.

## Twenty-fourth move, 2026-11-08 — Train 11 (`#249`, `#256`), one move: 138 new keys for the player and Settings

One combined baseline move for Train 11, which merges `#249` (`I18N-19`) and `#256` (`I18N-20`) with nine PRs that add no
strings. Each of the two had recorded its own move from master's twenty-third (Train 7): `#249` as a "twenty-fourth", `#256`
as a "move for `I18N-20`" to be renumbered. Neither heading is kept. The train regenerated once from the live generator: the
Android res came out with 0 of 62 directories changed, and the live `decoded`, `emitted`, `lookup`, `requests` and `findings`
are byte-identical to the merged files here. `report.txt` is the generator's.

New keys are approved in general (`I18N-09`). Additive, every key authored in all 62 directories (`OWN` grows by
138 x 62 = 8,556). No key is in both PRs.

| PR | keys | of which plurals | `decoded` | `emitted` | `lookup` |
|---|---:|---:|---:|---:|---:|
| `#249` `I18N-19`, the player (`DS-23`) | 11 | 3 | 946 | 974 | 1,284 |
| `#256` `I18N-20`, Settings (`DS-24`) | 127 | 4 | 8,226 | 8,488 | 11,485 |
| **total** | **138** | **7** | **9,172** | **9,462** | **12,769** |

`#249`'s plurals are `player_source_count`, `player_seek_seconds` and `player_up_next_in`; `#256`'s are
`settings_repo_summary`, `settings_extension_scan_warnings`, `settings_extensions_all` and `settings_reset_item_profiles`.
`#256`'s review fixes changed three of its own new keys in place before it was queued; against master they are additions.

| file | before | after | added | changed | lost |
|---|---|---|---|---|---|
| `decoded.tsv` | 44,778 | 53,950 | 9,172 | 0 | 0 |
| `emitted.tsv` | 47,071 | 56,533 | 9,462 | 0 | 0 |
| `lookup.tsv` | 86,089 | 98,858 | 12,769 | 0 | 0 |
| `requests.tsv` | 87 | 87 | 0 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 | 0 |

Measured against master's baseline by whole-line set difference twice over, a multiset in Python and `sort | comm`: 0 lines
lost in any file, every line that was in a file before is in it after in the same order, and every added line names exactly
one of the 138 keys. The per-PR split is the additions each PR measured on its own branch. `report.txt` moved only in its
counts: `OWN` 43,408 -> 51,964, visible keys 964 -> 1,102, plurals 22 -> 29, and 138 more values in each of the two locale
copies. **`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 173.** `expectedOwn` 43,408 -> 51,964.

## Twenty-third move, 2026-11-08 — Train 7 (`#213`, `#216`, `#219`, `#217`), one move: `I18N-16`'s 18 new keys for TV account select (board 8d)

One combined baseline move for the train that merges `#213` (`BUG-49`), `#216` (`BUG-41`), `#219` (`BUG-45`) and `#217` (`I18N-16`)
together. Only `#217` adds strings; the other three add none. `#217` branched from master's twenty-second move (Train 5), which is
still master's latest, and the train's single regeneration from the live generator reproduced its files byte for byte.
New keys are approved in general (`I18N-09`). Additive, all eighteen authored in all 62 directories, none a plural:
`account_select_title`, `account_select_note`, `account_manage_profiles`, `account_add_profile`, `account_profile_current`,
`account_profile_locked`, `account_profile_no_sync`, `account_sync_title`, `account_sync_note`, `account_sync_pair`,
`account_sync_needs_browser`, `account_sync_unavailable`, `account_sync_connected`, `account_sync_remove`,
`account_pair_title`, `account_pair_instructions`, `account_pair_code`, `account_pair_new_code`.

| file | before | after | added | changed | lost |
|---|---|---|---|---|---|
| `decoded.tsv` | 43,662 | 44,778 | 1,116 | 0 | 0 |
| `emitted.tsv` | 45,919 | 47,071 | 1,152 | 0 | 0 |
| `lookup.tsv` | 84,523 | 86,089 | 1,566 | 0 | 0 |
| `requests.tsv` | 87 | 87 | 0 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 | 0 |

Measured against master's baseline by whole-line set difference twice over, a multiset in Python and `sort | comm`: 0 lines
lost in any file, every line that was in a file before is in it after in the same order, and every added line names exactly
one of the eighteen keys, all eighteen present: 18 x 62, 18 x 64 and 18 x 87. `report.txt` moved only in its counts: `OWN`
42,292 -> 43,408 (18 x 62), visible keys 946 -> 964, plurals unchanged at 22, and 18 more values in each of the two locale
copies. **`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 173.** `expectedOwn` 42,292 -> 43,408.

## Twenty-second move, 2026-11-07 — Train 5 (`#189`, `#190`, `#188`, `#198`, `#202`), one move: `KMP-64`'s 8 new keys for the TV player HUD

One combined baseline move for the train that merges `#189` (`KMP-64`), `#190` (`KMP-62`), `#188` (`BUG-43`), `#198` (`BUG-44`) and
`#202` (`BUG-47`) together. Only `#189` adds strings; the other four add none, and the regenerated oracle confirms that. `#189`
recorded its keys as its own "eighteenth" move, branched from master's seventeenth; master's eighteenth to twenty-first landed
first, so that heading is not kept. The train regenerated once from the live generator on master's baseline with the twenty-first
in it.

New keys are approved in general (`I18N-09`, above). Additive, all eight authored in all 62 directories, none a plural
(`player_hud_buffering`, `player_hud_failed_detail`, `player_hud_offline_copy`, `player_hud_previous_episode`, `player_hud_seek_back`,
`player_hud_seek_forward`, `player_hud_speed`, `player_hud_up_next`).

| file | before | after | added | changed | lost |
|---|---|---|---|---|---|
| `decoded.tsv` | 43,166 | 43,662 | 496 | 0 | 0 |
| `emitted.tsv` | 45,407 | 45,919 | 512 | 0 | 0 |
| `lookup.tsv` | 83,827 | 84,523 | 696 | 0 | 0 |
| `requests.tsv` | 87 | 87 | 0 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 | 0 |

Measured against master's baseline by whole-line set difference twice over, a multiset in Python and `sort | comm`: 0 lines lost
in any file, every line that was in a file before is in it after in the same order, and every added line names exactly one of the
eight keys, all eight present. These are the additions `#189` measured on its own branch (496, 512, 696): 8 x 62, 8 x 64 and
8 x 87. `report.txt` moved only in its counts: `OWN` 41,796 -> 42,292 (8 x 62), visible keys 938 -> 946, plurals unchanged at 22,
and 8 more values in each of the two locale copies. **`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 173.**
`expectedOwn` 41,796 -> 42,292.

## Twenty-first move, 2026-11-07 — Train 4 (`#183`, `#199`, `#197`, `#196`, `#195`, `#201`, `#194`), one move: 15 new keys

One combined baseline move for the train that merges `#183` (`BUG-41`), `#199` (`BUG-45`), `#197` (`KMP-68`), `#196`
(`I18N-15`), `#195` (`KMP-50`), `#201` (`KMP-51`) and `#194` (`I18N-12`) together. Three of them carry keys of their own, and
each had recorded its own move from its own branch point: `#196` as the "next move after the nineteenth", `#195` and `#194`
each as a "nineteenth", and `#201` as a move "on a stacked branch" that carries `#195`'s key over `#196`. Those headings collided
with each other and with master's nineteenth and twentieth, so none of them is kept: the train regenerated once from the live
generator on master's baseline with the twentieth in it, and all of them are recorded here as this one move. `#201` adds no
key of its own (its one key is `#195`'s); `#183`, `#199` and `#197` add none.

New keys are approved in general (`I18N-09`, above). Additive, every key authored in all 62 directories (`OWN` grows by
15 x 62 = 930):

| PR | keys | of which plurals | `decoded` | `emitted` | `lookup` |
|---|---:|---:|---:|---:|---:|
| `#196` `I18N-15`, Search and Library (`DS-22`) | 12 | 1 | 832 | 858 | 1,153 |
| `#195` `KMP-50`, the TV library's sort | 1 | 0 | 62 | 64 | 87 |
| `#194` `I18N-12`, board 7i's season chips | 2 | 0 | 124 | 128 | 174 |
| **total** | **15** | **1** | **1,018** | **1,050** | **1,414** |

| file | before | after | added | changed | lost |
|---|---|---|---|---|---|
| `decoded.tsv` | 42,148 | 43,166 | 1,018 | 0 | 0 |
| `emitted.tsv` | 44,357 | 45,407 | 1,050 | 0 | 0 |
| `lookup.tsv` | 82,413 | 83,827 | 1,414 | 0 | 0 |
| `requests.tsv` | 87 | 87 | 0 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 | 0 |

Measured against master's baseline by whole-line set difference twice over, a multiset in Python and `sort | comm`:
0 lines lost in any file, and every added line names one of the 15 keys, all 15 present. Split by the PR that owns each key,
the added lines equal, file by file, what each PR measured on its own branch (the per-PR columns above). `report.txt` moved
only in its counts: `OWN` 40,866 -> 41,796, visible keys 923 -> 938, plurals 21 -> 22 (`library_title_count`), and 15 more
values in each of the two locale copies. **`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 173.** `expectedOwn`
40,866 -> 41,796.

## Twentieth move, 2026-11-07 — Train 3 (`#176`, `#181`, `#178`, `#179`, `#185`, `#186`, `#191`), one move: 33 new keys

One combined baseline move for the train that merges `#176` (`KMP-48`), `#181` (`KMP-38` PR 2a), `#178` (`I18N-11`), `#179`
(`KMP-54`), `#185` (`I18N-13`), `#186` (`KMP-53` step 2) and `#191` (`I18N-14`) together. Four of them carry keys, and each had
recorded its own move, numbered from its own branch point: `#176` as an "eighteenth", `#178` as a "seventeenth", `#185` as a
"nineteenth" and `#191` as the "next move after the eighteenth". Those headings collided with each other and with master's
seventeenth to nineteenth, so none of them is kept: the train regenerated once from the live generator on master's baseline
with the nineteenth in it, and all four are recorded here as this one move. `#179` contains `#178` and `#186` contains `#185`,
and neither adds a key of its own; `#181` adds none.

New keys are approved in general (`I18N-09`, above). Additive, every key authored in all 62 directories (`OWN` grows by
33 x 62 = 2,046):

| PR | keys | of which plurals | `decoded` | `emitted` | `lookup` |
|---|---:|---:|---:|---:|---:|
| `#176` `KMP-48`, TV Quick Search | 7 | 0 | 434 | 448 | 609 |
| `#178` `I18N-11`, the desktop downloads pages | 14 | 5 | 1,308 | 1,346 | 1,763 |
| `#185` `I18N-13`, the Mac menu bar's Go menu | 1 | 0 | 62 | 64 | 87 |
| `#191` `I18N-14`, Home and a title's page (`DS-21`) | 11 | 1 | 770 | 794 | 1,066 |
| **total** | **33** | **6** | **2,574** | **2,652** | **3,525** |

| file | before | after | added | changed | lost |
|---|---|---|---|---|---|
| `decoded.tsv` | 39,574 | 42,148 | 2,574 | 0 | 0 |
| `emitted.tsv` | 41,705 | 44,357 | 2,652 | 0 | 0 |
| `lookup.tsv` | 78,888 | 82,413 | 3,525 | 0 | 0 |
| `requests.tsv` | 87 | 87 | 0 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 | 0 |

Measured against master's baseline by whole-line set difference twice over, a multiset in Python and `sort | comm`:
0 lines lost in any file, and every added line names one of the 33 keys, all 33 present. Split by the PR that owns each key,
the added lines equal, file by file, what each PR measured on its own branch (the per-PR columns above). `report.txt` moved
only in its counts: `OWN` 38,820 -> 40,866, visible keys 890 -> 923, plurals 15 -> 21, and 33 more values in each of the two
locale copies. **`REFERENCE_IN_LOCALE` stayed at 500 and `ANDROID_FALLBACK` at 173.** `expectedOwn` 38,820 -> 40,866.

## Nineteenth move, 2026-11-07 — Train 2 (`#173`, `#182`, `#180`), one move: `KMP-47`'s 8 new keys for TV search's suggestions

One combined baseline move for the train that merges `#173` (`KMP-47`), `#182` (`BUG-42`) and `#180` (`KMP-53`) together.
Only `#173` adds strings; `#182` and `#180` add none, and the regenerated oracle confirms that. `#173` recorded its keys as
its own sixteenth move and its review's reword of `search_state_no_suggestions_detail` (now `0 SUGGESTIONS · 0 RECENT
MATCHES` in all 62 directories) as its seventeenth, both branched from master's fifteenth. Master's sixteenth to eighteenth
landed first, so the train regenerated once from the live generator on master's baseline with the eighteenth in it, and the
whole of `#173` is recorded here as one move.

New keys are approved in general (`I18N-09`, above). Additive, all eight authored in all 62 directories; three are plurals
(`search_state_no_suggestions_action`, `search_state_no_suggestions_body`, `search_tv_suggestion_sources`), whose categories
follow each locale's own other plurals. The other five are `search_state_no_suggestions_detail`,
`search_state_no_suggestions_headline`, `search_tv_suggestion_recent`, `search_tv_suggestion_recent_suggested` and
`search_tv_suggestion_suggested`.

| file | before | after | added | changed | lost |
|---|---|---|---|---|---|
| `decoded.tsv` | 38,814 | 39,574 | 760 | 0 | 0 |
| `emitted.tsv` | 40,923 | 41,705 | 782 | 0 | 0 |
| `lookup.tsv` | 77,865 | 78,888 | 1,023 | 0 | 0 |
| `requests.tsv` | 87 | 87 | 0 | 0 | 0 |
| `findings.tsv` | 0 | 0 | 0 | 0 | 0 |

Measured against master's baseline by whole-line set difference twice over, a multiset in Python and `sort | comm`:
0 lines lost in any file, and every added line names one of the eight keys, all eight present. These are the additions `#173`
measured on its own branch (760, 782, 1,023). `report.txt` moved only in its counts: `OWN` 38,324 -> 38,820 (8 x 62), visible
keys 882 -> 890, plurals 12 -> 15, and 8 more values in each of the two locale copies. **`REFERENCE_IN_LOCALE` stayed at 500
and `ANDROID_FALLBACK` at 173.** `expectedOwn` 38,324 -> 38,820.

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
