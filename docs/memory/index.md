# Session memory, exported

These are the working notes this project's Claude Code sessions have accumulated, copied out of the
local agent memory directory on 2026-10-07 so they survive a move to a cloud session. They are a
snapshot, not a live store: the originals live outside the repo and anything written after this
date is not here.

They are **notes**, not specification. `docs/plans/kmp-migration.md` and `docs/tasks/` are
authoritative; where these disagree with those, those win. Several entries predate the strings flip
(`I18N-07`) and the module work, so check a claim before acting on it.

- [CloudStream 4 redesign](cloudstream4-redesign.md) — TV-first cross-platform redesign, scope and the Claude Design project link.
- [No indigo for CloudStream](cloudstream4-no-indigo.md) — user rejects the existing #3D50FA accent; wants researched palettes and distinct directions.
- [CloudStream feature inventory](cloudstream4-inventory.md) — 174 surfaces mapped from the app code; where the raw data lives and what it concluded.
- [CloudStream 4 decisions](cloudstream4-decisions.md) — settled product calls + the focus-ring geometry contract and the banned 320×180 rung.
- [CloudStream 4 design state](cloudstream4-design-state.md) — project link, which file is authoritative, and the rules every new screen must pass.
- [CloudStream 4 pending corrections](cloudstream4-pending.md) — what the drawn files still owe, and the rest of the P0 draw queue
- [CloudStream 4 cluster specs](cloudstream4-cluster-specs.md) — where the four workflows' source evidence and verified geometry live, and the headline code findings.
- [CloudStream 4 KMP modules](cloudstream4-kmp-modules.md) — how the design system maps onto :core:theme / :core:designsystem, the px-to-dp rule, and the constraints on any module work.
- [CloudStream 4 type metrics](cloudstream4-type-metrics.md) — the measured Manrope/Mono line boxes (1.375x, not 1.3) and the centring / bound-value traps behind most off-grid geometry.
- [CloudStream 4 ABI audit](cloudstream4-abi-audit.md) — what can leave :app without breaking extensions; the $stable trap; open ABI decisions.
- [CloudStream 4 migration progress](cloudstream4-migration-progress.md) — merged PRs, in-flight work, the agreed queue, worktrees, and what waits on the owner. Update after each PR.
