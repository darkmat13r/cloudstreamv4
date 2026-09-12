---
paths:
  - "shared/domain/**/*.kt"
  - "shared/data/**/*.kt"
  - "shared/database/**/*.kt"
---
# Clean Architecture Rules

## Dependency Flow (STRICT)

```
Presentation (shared:ui) -> Domain (shared:domain) -> Data (shared:data) -> Database (shared:database)
```

Inner layers NEVER depend on outer layers.

## Domain Layer (`shared:domain`)

**Use Cases** - ALL business logic via Use Cases:
- `UseCase` - Single operations, implement `block: suspend () -> Unit`
- `UseCaseFlow` - Observable operations, implement `build(): Flow<T>`
- Place in the owning module's `commonMain`, e.g. `feature/<name>/src/commonMain/kotlin/`
- Only depend on repository **interfaces**, never implementations

**Repository Interfaces** - Prefix with `I` (e.g., `IUserRepository`):
- `Flow<T>` for observable data
- `suspend fun` for one-time operations

## Data Layer (`shared:data`)

- Implement repository interfaces from domain
- Constructor injection for dependencies
- Data mappers: `Entity.toDomain()`, `Domain.toEntity()`
- Never expose database entities outside data layer

## Database Layer (`shared:database`)

- SQLDelight schema in `.sq` files
- DAO classes wrapping SQLDelight queries
- Flow for observations, suspend for mutations

## Dependency wiring

There is **no DI framework here**. Dependencies are resolved through object singletons and
constructor parameters. Do not add Koin or Hilt as part of a feature.

## Where this is true today, and where it is the target

`:app` is a single 67k-line module and does not have these layers yet. The module graph that does
is `docs/plans/kmp-migration.md` — `:core:model`, `:core:data`, `:core:network`, `:feature:*` —
and it is a plan, not the current state. When this rule and the tree disagree, the tree is what
you are working in; say so rather than inventing the missing layer for one feature.
- `DatabaseModule.kt` - SQLDelight database setup
