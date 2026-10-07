---
paths:
  - "core/**/src/commonMain/kotlin/**/*.kt"
  - "feature/**/src/commonMain/kotlin/**/*.kt"
  - "shared/src/commonMain/kotlin/com/lagradost/cloudstream4/**/*.kt"
---
# MVI & ViewModel Rules

## The containers, not a base class

There is no `BaseViewModel` here and one should not be added. The primitives are three small
interfaces in `core/ui/src/commonMain/kotlin/com/lagradost/cloudstream4/compose/Viewmodel.kt`:

| | |
|---|---|
| `StateContainer<State>` | holds and exposes state — `DefaultStateContainer` is the impl |
| `EffectContainer<Effect>` | one-shot events — `DefaultEffectContainer` is the impl |
| `ActionHandler<Action>` | takes actions in |

A ViewModel **implements the ones it needs by delegation**, rather than inheriting a base:

```kotlin
class LibraryViewModel :
    ViewModel(),
    StateContainer<LibraryState> by DefaultStateContainer(LibraryState()),
    ActionHandler<LibraryAction> {

    override fun onAction(action: LibraryAction) { … }
}
```

That is the pattern the seven migrated Compose settings screens already use, and the one
`docs/plans/kmp-migration.md` carries forward — a ViewModel that delegates has nothing Android in
it and can live in `commonMain`. Inheriting a base class drags the base's dependencies with it,
which is exactly what the `jvm()` canary will reject.

## Model states as a type, never as boolean flags

Use `UiState<T>` from `:core:designsystem`:

```kotlin
// GOOD — the states are exhaustive, and `when` will tell you when you miss one
data class LibraryState(val page: UiState<List<LibraryItem>> = UiState.Loading)

// BAD — six booleans encode sixty-four states, of which about five are reachable
data class LibraryState(val isLoading: Boolean, val isEmpty: Boolean, val error: String?, …)
```

`UiState` has `Loading`, `Content`, `Empty`, `Error`, `Offline` and `Partial`, because those are
the six every surface in this app owes. `Offline` and `Partial` are separate from `Error` on
purpose: they are banners over content that is still usable, not replacements for it.

This is the rule that fixes the app's most common defect. Failures today are `Toast`s, and one
branch is literally `// No user indication it failed :( // TODO` — a state that has no type is a
state nobody renders.

## Where the UI reads it

- State: `viewModel.uiState.collectAsState()` — the property is `uiState`, never `state`
- Effects: `ObserveEffect(viewModel.effect) { … }` from `Viewmodel.kt`
- Actions in: `viewModel.onAction(Action.X)`

Composables take state and emit callbacks. No business logic in a composable, and no ViewModel
passed into one — pass the state and the lambdas.

## Navigation: the XML graph, not Navigation-Compose

CloudStream navigates through `app/src/main/res/navigation/mobile_navigation.xml`. There is no
Navigation-Compose and no `@Serializable` destination. A Compose screen reaches the graph through
the glue the Settings migration proved:

```kotlin
class LibraryFragment2 : BaseComposeFragment(), Screen by LibraryScreen
```

then repoint **one node** in the nav graph. Per the migration plan the old fragment stays until a
release later — that is the rollback lever, not dead code to tidy up.

## No DI framework

Dependencies are resolved through object singletons and `DataStoreHelper`; ViewModels come from
`by activityViewModels()` / `by viewModels()`. Do not introduce Koin or Hilt as part of a
feature — that is a dependency decision of its own, needing approval.

## Concurrency

- `viewModelScope` only. Never `GlobalScope`.
- Inject dispatchers rather than hardcoding `Dispatchers.IO`, so the ViewModel stays testable.
- `StateFlow` for state, `SharedFlow` / `Channel` for one-shot events.
