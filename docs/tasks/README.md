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

**Status** is one of (and a `done` entry should name its PR number, not say "in pr" — four entries
had gone stale that way by 2026-10-15):

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

**Update 2026-10-15: CI is down again, on billing** (`DEC-01`). Runs end in ~2 seconds with zero
steps and a *"recent account payments have failed or your spending limit needs to be increased"*
annotation. **A red tick currently means no check ran** — it is not a result, and re-running does not
help. Only the owner can clear it.

CI did run from **2026-10-07** to then (`INF-01`; first full green run on #42), and before 2026-10-07
it had never run on this repository's pull requests at all. So for everything merged outside that
window — which is most of this series, including #64–#68 — **every check quoted in a PR body is a
local run**, on one machine, with one JDK, against one or two attached devices. Treat the ticks
accordingly, and see `INF-01` for reliability and `INF-08` for the step the runner kills even when
billing is healthy.
