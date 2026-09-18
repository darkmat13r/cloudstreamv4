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
| `decoded.tsv` | 32,649 | aapt2's decoded table, one row per item: source file, type, name, plural category, kind (`STR`/`STYLED`/`REF`/`ATTR`/`UNSUPPORTED`), text, spans. The raw oracle, before resolution. |
| `emitted.tsv` | 34,561 | What the generator wrote: one row per emitted value, keyed by output directory, type, name, plural category. Includes resolved references, locale-fallback copies and the `values-b+he` / `values-b+id` copies. |
| `lookup.tsv` | 66,315 | For each of the 87 parity requests, the value Android resolves for every key — the per-locale lookup table the parity test compares Compose against. |
| `requests.tsv` | 87 | The parsed `parity-requests.txt`: request id, Android tag, Compose language/script/region, expectation, note. |
| `findings.tsv` | 65 | Every finding the pipeline raised, sorted by severity then kind then location. All `INFO` here: 31 `STRAY_TEXT_REMOVED`, 34 `STYLED_AS_PLAIN_TEXT`. |
| `report.txt` | 13 | The human-readable run summary. |

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
