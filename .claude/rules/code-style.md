---
paths:
  - "**/*.kt"
  - "**/*.cpp"
  - "**/*.h"
---
# Code Style Guidelines

Kotlin and Compose. This project has no web frontend and no native code, so the React and C++
sections that came with this ruleset have been removed rather than left to rot.

## File Length Limits

Keep files focused and under these limits. If a file exceeds the limit, suggest splitting it — do not split without user approval.

| Language | Max Lines | Action |
|----------|-----------|--------|
| Kotlin (`.kt`) | 300 | Suggest extracting classes/functions to separate files |
| Composable screens | 250 | Suggest extracting sub-composables into separate files |
| ViewModels | 200 | Suggest extracting logic into use cases |
| Test files | 500 | Higher limit is acceptable for test files |

## Kotlin Code Style

### Naming
- Classes/Interfaces: `PascalCase` — `SessionManager`, `ILibraryRepository`
- Functions: `camelCase` — `getActiveSessions()`, `onTimerExpired()`
- Constants: `UPPER_SNAKE_CASE` in companion objects — `MAX_RETRY_COUNT`
- Properties: `camelCase` — `isLoading`, `currentSession`
- Packages: `lowercase` dot-separated — `com.lagradost.cloudstream4.core.designsystem`
- Type parameters: single uppercase letter or descriptive `PascalCase` — `T`, `Event`, `State`

### Formatting
- 4-space indentation (no tabs)
- Max line length: 120 characters
- Trailing commas in multi-line parameter lists and collections
- Blank line between functions, between class members of different kinds
- No blank line after opening brace or before closing brace
- Use expression body (`=`) for single-expression functions
- Use `when` over `if-else` chains with 3+ branches

### Best Practices
- Prefer `val` over `var` — immutability by default
- Prefer data classes for DTOs and state objects
- Use sealed classes/interfaces for restricted hierarchies
- Use extension functions for utility operations on existing types
- Prefer `?.let {}` / `?.run {}` over null checks with temporary variables
- Use `require()` / `check()` for preconditions, not `if + throw`
- Avoid `!!` — use `?.`, `?:`, or `requireNotNull()` instead
- Scope functions: `let` for null checks, `apply` for builder-style, `also` for side effects, `run` for transformations, `with` for multiple calls on same object

### Coroutines
- Never use `GlobalScope` — use structured concurrency (`viewModelScope`, `lifecycleScope`)
- Use `withContext(Dispatchers.IO)` for blocking operations
- Prefer `Flow` over `LiveData` for reactive streams
- Use `StateFlow` for state, `SharedFlow` for events

## Jetpack Compose Code Style

### Naming
- Composable functions: `PascalCase` — `SessionScreen()`, `TimerCard()`
- Non-composable functions: `camelCase`
- State holders: suffix with `State` — `SessionScreenState`
- Modifiers: always first optional parameter, default `Modifier`

### Formatting
- Composable parameters: `Modifier` first (after required params), callbacks last
- One parameter per line when 3+ parameters
- Lambda arguments outside parentheses
- Chain modifiers with line breaks for readability

### Best Practices
- Use `remember` for expensive computations in composition
- Use `derivedStateOf` when state derives from other state
- Use `LaunchedEffect` for side effects, `DisposableEffect` for cleanup
- Avoid allocations inside composable functions (no `listOf()`, `mapOf()`, object creation in body)
- Extract reusable composables for any repeated UI pattern
- Keep composables small — one responsibility per composable
- Use `@Stable` / `@Immutable` annotations for custom state classes to help skip recomposition
- State hoisting: composables receive state via params, emit events via callbacks
- Preview: add `@Preview` for key composables
