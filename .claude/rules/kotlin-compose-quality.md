---
paths:
  - "**/*.kt"
  - "**/*.kts"
---
# Kotlin & Compose Quality Rules

Applies to all Kotlin and Compose Multiplatform code. Complements `code-style.md`
(naming/formatting) and `solid-principles.md`. This file focuses on clean code,
readability, no hardcoded values, and security. When existing code violates these
rules, follow the suggest-confirm-change flow in `no-unsolicited-changes.md` — do
not silently refactor.

## File & Function Size Limits

Keep units small and single-purpose. Exceeding a limit is a signal to extract, not
to compress.

| Unit | Max | Action when exceeded |
|------|-----|----------------------|
| Kotlin file (`.kt`) | 300 lines | Extract classes/extensions to new files |
| Composable screen | 250 lines | Extract sub-composables to separate files |
| ViewModel | 200 lines | Move logic into use cases |
| Function / Composable | 40 lines | Extract private helpers |
| Function parameters | 5 | Wrap related params in a data class |
| Nesting depth | 3 levels | Use early returns, guard clauses, `when` |
| Cyclomatic branches | ~10 | Split into smaller functions |

- Never split a file or function without user approval — suggest it first.
- Test files may exceed these limits.

## Clean Code & Readability

- **One responsibility per unit** — a function does one thing; a class has one reason to change.
- **Name for intent, not implementation** — `remainingSessionSeconds`, not `secs` or `temp`.
- **No abbreviations** except well-known ones (`id`, `url`, `db`, `vm`).
- **Booleans read as questions** — `isLoading`, `hasActiveSubscription`, `canStartSession`.
- **Prefer early returns / guard clauses** over nested `if`/`else`.
- **Avoid comments that restate code.** Comment *why*, never *what*. Delete commented-out code.
- **No dead code** — unused params, functions, imports, or `TODO`s without context.
- **Express intent with the type system** — sealed classes for states, enums for fixed sets, value classes for typed IDs (`@JvmInline value class SessionId(val value: String)`).
- **Prefer `val`** — immutability by default; only use `var` when mutation is required.
- **Avoid `!!`** — use `?.`, `?:`, `requireNotNull()`, or restructure.
- **Pure functions where possible** — same input → same output, no hidden side effects.
- **Fail loudly at boundaries** — `require()` / `check()` for preconditions; `runCatching` only at I/O/external boundaries, never to swallow logic errors.
- **DRY, within reason** — extract a helper after the second duplication; check the codebase first (see `code-reuse.md`).

## No Static / Magic Values

Hardcoded literals scattered through code are forbidden. Every meaningful value has a name.

- **No magic numbers** — `if (retries > 3)` → `if (retries > MAX_RETRIES)`.
- **No magic strings** — keys, tags, routes, prefs names, MIME types must be named constants.
- **Constants live in `companion object` or top-level `private const val`**, named `UPPER_SNAKE_CASE`.
- **User-facing text** → always `stringResource(Res.string.xxx)`, translated for all 9 languages. Never hardcode display strings.
- **Dimensions / spacing / shapes / colors** → design system tokens only:
  - Spacing & sizes: `MaterialTheme` / design-system dimens — never raw `16.dp` repeated inline.
  - Shapes: `CloudStreamTheme.shapes` or `CloudStreamTheme.shapes` — NEVER `RoundedCornerShape(...)`.
  - Colors: `CloudStreamTheme.colors` / design tokens — NEVER `Color(0xFF...)` in UI.
  - Components: `PosterCard`, `PrimaryTextButton`, `ListRow`, `StateView` — not raw Material.
  - Icons: from the pack, via the design system — never hand-drawn, never Material Icons
    directly. No pack is chosen yet; see `icons.md`.
- **Config / endpoints / limits** → centralized config or build config, not inline literals.
- The only acceptable inline literals are trivially-obvious ones: `0`, `1`, `-1`, `""`, and indices in local algorithms.

## Compose Best Practices

- **State hoisting** — composables receive state via params, emit via callbacks. Keep them stateless where possible.
- **`Modifier` is the first optional param**, defaulting to `Modifier`; pass it through to the root child.
- **No business logic in composables** — derive state in the ViewModel; the UI only renders.
- **Read state via** `viewModel.uiState.collectAsState()` (property is `uiState`).
- **No allocations in the composition body** — no `listOf()`, `mapOf()`, lambdas-in-loops, or object creation per recomposition; hoist with `remember`.
- **`remember`** for expensive in-composition values; **`derivedStateOf`** when a value derives from other state; **key** `remember` correctly so it recomputes when inputs change.
- **Side effects only in effect handlers** — `LaunchedEffect` (keyed correctly), `DisposableEffect` (cleanup), `rememberCoroutineScope` for event-driven launches. Never launch coroutines directly in the body.
- **Stability** — annotate custom state with `@Immutable` / `@Stable`; prefer immutable types and `ImmutableList` to enable recomposition skipping.
- **No `Unit`-returning side effects passed as state.** Avoid passing whole ViewModels into composables — pass state + callbacks.
- **Keep one responsibility per composable**; extract repeated UI into reusable components.
- **Previews** — add `@Preview` for key composables and component states.
- **Lists** — provide stable `key`s in `LazyColumn`/`LazyRow` items.

## Concurrency & Lifecycle

- Use structured concurrency: `viewModelScope` / `lifecycleScope`. **Never `GlobalScope`.**
- `withContext(Dispatchers.IO)` for blocking work; keep the main thread free.
- `StateFlow` for state, `SharedFlow`/`Channel` for one-shot events.
- Inject dispatchers (don't hardcode `Dispatchers.IO`) so code stays testable.
- Cancel and clean up resources in the matching scope/effect.

## Security

See `dependency-security.md` for dependency vetting. For code:

- **Never log or expose secrets** — no tokens, API keys, credentials, or PII in logs, crash reports, or analytics. Strip them from error messages.
- **No secrets in source or VCS** — load from secure config / platform keystore; never commit `.env`, keys, or service-account JSON.
- **Validate all external input** — API responses, deep-link/intent params, URLs, user text. Treat everything outside the app as untrusted.
- **Parameterized queries only** — SQLDelight bind parameters; never string-concatenate SQL.
- **HTTPS for every network request**; validate redirect/deep-link targets before navigating.
- **No dynamic code execution** from untrusted sources; no untrusted deserialization.
- **Least privilege** — request the minimum permissions; scope file/storage access narrowly.
- **Wrap platform/I/O boundaries in `runCatching`** and handle failures explicitly — never let untrusted input crash or leak via an unhandled exception.
- **Don't trust client-side entitlement checks alone** — gate Pro features through the subscription/entitlement layer, not ad-hoc flags.