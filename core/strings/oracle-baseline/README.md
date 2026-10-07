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
| `decoded.tsv` | 32,646 | aapt2's decoded table, one row per item: source file, type, name, plural category, kind (`STR`/`STYLED`/`REF`/`ATTR`/`UNSUPPORTED`), text, spans. The raw oracle, before resolution. |
| `emitted.tsv` | 34,558 | What the generator wrote: one row per emitted value, keyed by output directory, type, name, plural category. Includes resolved references, locale-fallback copies and the `values-b+he` / `values-b+id` copies. |
| `lookup.tsv` | 66,315 | For each of the 87 parity requests, the value Android resolves for every key — the per-locale lookup table the parity test compares Compose against. |
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

The baseline was moved once after the flip, by hand, in a commit of its own. `lookup.tsv` changed in **9 cells**
(66,315 rows before and after; 0 lost, 0 invented): `iw`, `he`, `he-IL` and `values-b+iw` for
`next_episode_time_hour_format`; `values-b+lv`, `values-b+qt` and `values-b+so` for `duration_format`;
`values-b+my` and `values-b+so` for `next_episode_time_hour_format`. Three keys were deleted from the authored tree
(`so` x2, `qt` x1), so `decoded.tsv` 32,649 -> 32,646, `emitted.tsv` 34,561 -> 34,558 and `OWN` 32,428 -> 32,425.
Details and reasoning in `docs/tasks/i18n.md` under `I18N-05`.
