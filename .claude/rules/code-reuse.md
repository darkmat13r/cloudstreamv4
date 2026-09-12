---
paths:
  - "**/*.kt"
---
# Code Reuse & Existing Code First

**CRITICAL: Always search the codebase for existing solutions before writing new code.**

## Before Writing ANY New Code

1. **Search for existing implementations** — grep/glob for similar functions, utilities, constants, and patterns already in the codebase
2. **Reuse what exists** — if a utility, helper, constant, extension function, or component already exists, use it
3. **Extend, don't duplicate** — if existing code is close but not quite right, extend it rather than creating a parallel implementation
4. **Check for existing constants** — never introduce a magic number or hardcoded string if a constant already exists in the codebase

## Magic Values & Static Constants

- If you find a hardcoded value (number, string, color, dimension), search the codebase for an existing constant
- If a constant exists, replace the hardcoded value with the existing constant
- If no constant exists but the value is used in 2+ places, **ask the user** before creating a new constant:
  - State: what value you found, where it's used, and where other constants live
  - Suggest: the constant name and location
  - Wait for confirmation before creating it
- Kotlin: constants go in companion objects or dedicated `Constants.kt` files
- Compose: dimensions, colours, shapes and type come from `CloudStreamDimens` and
  `CloudStreamTheme` — never a literal at a call site

## When You Notice Style/Quality Issues

**Do NOT silently fix style violations in existing code.** Instead:

1. **Report the issue** — describe what you found and where (file:line)
2. **Suggest the change** — show what the code is now vs. what it should be
3. **Explain why** — which guideline or best practice it violates
4. **Wait for approval** — only make the change after the user confirms

Example format:
```
Found: `padding = 16.dp` in LibraryScreen.kt:42
Suggest: `padding = CloudStreamDimens.SidePanelContentInset`
Why: Hardcoded dp values should use design system tokens (dimension-scale.md)
```

## Performance Issues

When you encounter performance issues while working on a task:

1. **Flag the issue** — describe the problem and its potential impact
2. **Suggest the fix** — show the current code and the improved version
3. **Explain the impact** — why this matters (memory, CPU, recomposition, re-render, etc.)
4. **Wait for confirmation** — do not apply performance fixes without user approval

Common things to flag:
- Kotlin/Compose: unnecessary recompositions, missing `remember`/`derivedStateOf`, allocations in composables, blocking main thread
- General: N+1 queries, unbounded lists, missing pagination, redundant API calls