---
name: cloudstream4-cluster-specs
description: Where the four CloudStream 4 cluster workflows put their source evidence and verified screen geometry
metadata:
  type: reference
---

Four workflows (2026-09-12) each read the real Kotlin/XML for one cluster, solved exact TV geometry
per screen, then adversarially verified it. Outputs are large — read them rather than re-deriving.

| Cluster | Task output | Run id | Screens | Violations found |
|---|---|---|---|---|
| Search suggestions | `tasks/w1qls5rx7.output` | `wf_c173366f-63c` | 3 | 28 |
| Episodes + downloads | `tasks/wb2edh483.output` | `wf_32a8f324-a48` | 5 | 56 |
| Detail + player | `tasks/wwczt5blu.output` | `wf_df3c795b-c23` | 5 | — |
| Onboarding + extensions | `tasks/wsy5r3sp1.output` | `wf_f89adafe-bd5` | 4 | — |

Each JSON has `.result.evidence[]` (per-area source findings with file:line) and `.result.screens[]`
(`.spec` = proposed geometry, `.verdict` = violations + corrected numbers). Resume any of them with
`Workflow({scriptPath: '…/workflows/scripts/<name>-<runid>.js', resumeFromRunId: '<runid>'})`.

**The specs were written against the pre-correction contract** (40px lift clearance, 24px glyphs,
40-tall chips). Their *content* decisions are sound; convert their geometry to the corrected chassis
in [[cloudstream4-design-state]] at assembly time — lift 8, clearance 32, chip 48, glyph 32.

Highest-value source findings, all verified with file:line in the outputs:
- **Search fans out only on submit**; the per-keystroke provider call is commented out at
  `SearchFragment.kt:442`. Suggestions come from TMDB autocomplete (`SearchSuggestionApi.kt`), are
  in-memory only, and never merge with stored history. History stores `searchedAt` but its `type`
  field is always written empty (`SearchViewModel.kt:219`, `// TODO implement tv type`).
- **`getWatchProgress()` divides by zero** when duration is 0, returning NaN (`ResultFragment.kt:132`).
- **`setViewPos` silently drops anything under 30s** (`DataStoreHelper.kt:700`).
- **TV cannot show download state at all**: `hasDownloadSupport = false` is hard-coded at
  `ResultFragmentTv.kt:433`.
- **`bytesPerSecond` is computed and thrown away** — it reaches only the notification
  (`DownloadManager.kt:689`); `getEstimatedTimeLeft` is `internal` and returns a formatted String.
- **No pre-flight free-space check exists anywhere** in `utils/downloader/`; the only `StatFs` call
  is display-only. A download that dies past 50 MB is reported as *partial success*
  (`DownloadManager.kt:558`).
- **`Score` has a private constructor** and is an opaque normalized int; the scale is chosen at read
  time via `toInt(maxScore)`. A 0 score renders as nothing (`ResultViewModel2.kt:332`).
- **Poster and backdrop fall back into each other** (`ResultViewModel2.kt:290`), so a portrait poster
  gets stretched into the backdrop slot with no "missing backdrop" state.
- **Cast is all-or-nothing on images**, decided by whether the *first* actor has one
  (`ResultViewModel2.kt:235`).
- **First run on TV**: Language → Extensions → Media → Layout → Home. Provider-language is dead code
  on a fresh install. Default focus on the first screen is **Skip**, not Next
  (`fragment_setup_language.xml:61`). The add-repository button is commented out in three layouts;
  it opened `PUBLIC_REPOSITORIES_LIST` in a webview and was removed by commit `a565319e`.
- **There is no TV variant of any setup layout.**

Part of [[cloudstream4-redesign]]. See [[cloudstream4-pending]] for what is still undrawn.
