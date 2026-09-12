---
paths:
  - "**/*.kt"
---
# SOLID Principles (ALL Code)

These apply to all Kotlin in this repo — `:app`, `:library`, `:shared` and every `:core` /
`:feature` module.

## Single Responsibility Principle
- One class/component = one reason to change
- Separate data fetching from presentation
- Separate business logic from UI rendering
- If you use "and" to describe what something does, split it

## Open-Closed Principle
- Design for extension without modification
- Use interfaces/abstractions for pluggable behavior
- Kotlin: sealed classes, strategy pattern, extension functions

## Liskov Substitution Principle
- Subtypes must honor base type contracts
- Don't override methods to throw "not supported"
- Prefer composition over inheritance when behavior diverges

## Interface Segregation Principle
- Small, focused interfaces over large ones
- Clients should not depend on methods they don't use
- Kotlin: split `IRepository` into read/write interfaces if callers only need one

## Dependency Inversion Principle
- Depend on abstractions, not concretions
- Kotlin: repository interfaces in domain, implementations in data
- Constructor/prop injection for all dependencies

## Constants & Configuration
- NO magic numbers or hardcoded strings in logic
- Extract to named constants, enums, or config files
- Kotlin: companion object constants, enum classes
