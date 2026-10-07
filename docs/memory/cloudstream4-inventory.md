---
name: cloudstream4-inventory
description: Where the CloudStream feature/screen inventory lives and what it concluded
metadata:
  type: reference
---

A 13-agent workflow read the whole CloudStream app and inventoried every UI surface
(2026-09-12). Results:

- Raw per-area returns: `subagents/workflows/wf_8e504539-c30/journal.jsonl` in this project's
  Claude Code session dir (one JSON line per agent, 12 areas).
- Consolidated list: the task output file `tasks/wfyjcif6x.output` (`.result.consolidated`).

Headline numbers: **174 surfaces found, deduped to 67 worth designing — 18 P0 / 30 P1 / 19 P2**,
and a list of **45 components** a design system must define to cover them.

The four conclusions worth remembering:
1. Four primitives (poster card, episode card, content rail, selection sheet) are reimplemented
   about five times each in divergent XML — unifying them deletes most of the duplicate layouts.
2. Almost no surface implements loading / empty / error / offline / partial consistently; failures
   are Toasts, and Library's failure branch is literally `// No user indication it failed :( // TODO`.
3. TV is not a design — it is the phone layout plus hidden views, with a hand-rolled focus engine in
   `CommonActivity.dispatchKeyEvent` that exists to work around those invisible views, and
   `@dimen/navbar_width` = 0dp so every TV nav-rail inset is a no-op.
4. First run is P0, not long tail: the app ships with zero sources and the onboarding
   add-repository button is commented out in both layouts.

To regenerate or extend, re-run the saved script with `resumeFromRunId: 'wf_8e504539-c30'` —
unchanged agents replay from cache. Part of [[cloudstream4-redesign]].
