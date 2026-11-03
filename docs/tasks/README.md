# Task lists

What is actually outstanding, with the evidence behind each item. One file per track.

| File | Track | What lives there |
|---|---|---|
| [decisions.md](decisions.md) | `DEC` | Questions only the owner can answer. Several of these block other tracks. |
| [kmp.md](kmp.md) | `KMP` | Carving `:app` into modules; the `:shared` restructure; iOS readiness. |
| [design-system.md](design-system.md) | `DS` | `:core:theme` / `:core:designsystem` adoption, the light scheme, screenshots, icons. |
| [i18n.md](i18n.md) | `I18N` | Strings pipeline, `UiString` adoption, hardcoded English, translation defects. |
| [defects.md](defects.md) | `BUG` | Bugs found and not yet fixed, each with the measurement that found it. |
| [infrastructure.md](infrastructure.md) | `INF` | CI, ABI guards, build logic, test infrastructure. |

## Conventions

**IDs are stable.** `KMP-04` keeps its number when it is done; it is marked done, not deleted and
not renumbered. PR descriptions and commit messages reference these IDs, so reusing a number
silently rewrites history that is already published.

**Status** is one of (and a `done` entry should name its PR number, not say "in pr"):

> **This drifts every time, and it is structural rather than careless.** Four entries had gone stale
> this way by 2026-10-15, fifteen by 2026-11-03 (#105), and five more within hours of that (#116:
> `BUG-17`, `BUG-18`, `BUG-22`, `BUG-23`, `BUG-24`). The cause is that the PR implementing a task
> writes `in pr` **in that same PR**, and merging it cannot change its own text — so every entry is
> born stale and only a later PR can correct it. Either write `done · #N` once the PR number exists
> and before merging, or accept that someone has to sweep. Nothing mechanical checks it.


| Status | Meaning |
|---|---|
| `open` | Nobody is on it. Ready to start unless a `blocked by` says otherwise. |
| `in pr #N` | Implemented and awaiting review. |
| `blocked` | Waiting on something named in the entry — usually a `DEC` item. |
| `done` | Merged. Keeps its row; the PR number is the record. |

**Every claim carries its evidence.** A task that says "X is broken" names the file and line, or
the command whose output showed it. A task whose evidence is an argument rather than a measurement
says so in as many words. This matters because several entries below exist *because* a plausible
argument turned out to be wrong when someone ran it.

**Size.** If a task cannot be one revertible PR, it has subtasks that can be.

## Maintaining this

Update the affected file in the same PR as the work, not afterwards. A task list that is refreshed
in a separate pass is a task list that is wrong between passes — which is how
`docs/plans/kmp-migration.md` came to list thirteen merged PRs as open.

When a `DEC` is answered, record the answer **and the reasoning** in that entry, then unblock what
it was blocking in the same change. The answer is worth more than the decision: six months later
nobody remembers why, and the entry is the only place that knows.

## Standing constraint on everything here

**CI does not run, by decision.** `DEC-01` was answered on 2026-10-15: the GitHub Actions billing
failure will not be fixed and all five workflows stay `disabled_manually`. **Local four-gate runs are
the agreed verification** — not a stopgap — so every PR must name its own `abi` / `check` / `extapi` /
`canary` results, and a missing tick is expected rather than a problem to chase.

CI did run from **2026-10-07 to 2026-10-15** (`INF-01`; first full green run on #42), and before
2026-10-07 it had never run on this repository's pull requests at all. Outside that window — which is
most of this series — **every check quoted in a PR body is a local run**, on one machine, with one
JDK, against one or two attached devices. `INF-08` records the one step the runner kept killing while
CI was alive; it is parked, not solved.
