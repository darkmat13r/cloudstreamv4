# Screenshot Tests — every feature, every state that matters

**Every feature ships with screenshot tests for its load-bearing states.** A feature is not done
when it renders once on your machine; it is done when a change that alters its rendering fails
the build.

Suite lives in `:core:screenshot`, `src/screenshotTest/kotlin`.

```
./gradlew :core:screenshot:updateDebugScreenshotTest     # record / re-record
./gradlew :core:screenshot:validateDebugScreenshotTest   # verify
```

Reference images are committed. A diff is a review artefact: someone looks at it and either
approves the change or fixes the regression. Re-recording to make red go away is the one thing
this rule exists to prevent.

## Current status — read before you promise this in a PR

`com.android.compose.screenshot:0.0.1-alpha16` **does not render on this toolchain** (AGP 9.1.1,
Gradle 9.4.1). The wiring is correct and complete — tasks register, previews compile, `@Preview`
survives into the bytecode, the engine resolves and reports "1 test, 0 failures" — and it writes
no images. A preview containing only `BasicText("hello")` renders nothing either, so it is the
plugin, not the components.

So: **write the cases now, expect the task to be red, do not mask it.** The header of
`core/screenshot/build.gradle.kts` has the full evidence and the two traps
(`failOnNoDiscoveredTests` and the KMP incompatibility). When a working plugin version lands the
suite starts guarding immediately with no further setup.

If a feature genuinely needs working screenshot coverage before then, say so — switching to
Roborazzi is a real option and a real dependency decision, not something to slip into a feature PR.

## What earns a case

Add one when a state is **load-bearing**:

- a failure that must stay legible — the whole point of the redesign's state work
- a disabled control that must not read as enabled at three metres
- a state whose geometry is easy to break silently: a text stack whose height must stay a
  multiple of 16dp to centre on the step, a chip whose count line box must divide cleanly
- a token whose contrast matters
- the empty and offline variants of any surface that has them

Do **not** add a case for every permutation. A suite nobody reads the diffs of is worse than no
suite — it trains people to re-record without looking.

## Writing a case

Previews live in `:core:screenshot`, not beside the component, because the plugin cannot see KMP
modules. That is a constraint, but treat it as the feature it turned out to be: this file is the
curated set, and a `@Preview` written for development convenience in `:core:designsystem` can be
changed freely without failing anyone's build.

Three things the plugin requires, each of which fails **silently**:

| Requirement | What happens otherwise |
|---|---|
| The function is **`public`** | `internal` is name-mangled by Kotlin; the scanner never matches it |
| It takes **no parameters** | Not discovered |
| It is **top-level or in an object**, annotated `@Preview` + `@Composable` | Not discovered |

Name the case for the state, not the component: `ListRows`, not `ListRowTest`. Give every case a
`@Preview(name = ...)` — the name is what appears in the report and in the image filename, and it
is what someone reads when the diff turns up in review.

Pin `widthDp`/`heightDp`. An unpinned preview resizes when its content changes, so the diff shows
a size change rather than the thing that actually moved.

## Review checklist

- Does every new feature have at least its failure and empty states covered?
- Is each case a state someone would want to be told about, or is it permutation filler?
- Are the previews `public`, parameterless, and pinned to a size?
- Did the diff get looked at, or was it re-recorded?
