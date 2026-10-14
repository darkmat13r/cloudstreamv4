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

**Status** is one of:

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

**Update 2026-10-07: CI now runs** (`DEC-01`, `INF-01`); the first full green run is on #42. The
paragraph below describes every PR before that. **Before 2026-10-07, no CI had ever run on this
repository's pull requests.** GitHub Actions fails account-wide in
roughly two seconds with a billing error. Every check quoted in every PR body in this series was
run locally, on one machine, with one JDK, against one or two attached devices. Treat every green
tick accordingly, and see `INF-01`.
