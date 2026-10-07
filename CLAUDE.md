# CLAUDE.md

CloudStream: an Android media app, partway through a migration to Kotlin Multiplatform + Compose.
This file is the entry point; it points at the documents that hold the detail rather than
restating them. Where they disagree, they win, and this file is the one to fix.

## Read first, in order

1. `docs/HANDOVER.md` — current state, open PRs, environment traps, what waits on the owner.
2. `docs/tasks/README.md` — the live task list, one file per track (`DEC`, `KMP`, `DS`, `I18N`, `BUG`, `INF`).
3. `docs/plans/kmp-migration.md` — settled decisions, the target module graph, phases.
4. `.claude/rules/*.md` — authoritative project rules. They override defaults.

## Shape of the build

- `:app` — `com.android.application`; cannot become KMP (AGP 9 has no multiplatform application
  plugin). Shrinks to a shell as code is carved out.
- `:library` — the published extension API. Depends on nothing internal.
- `:core:*` — KMP modules (android + a `jvm()` canary), built on the convention plugins in
  `build-logic/convention`: `cloudstream.kmp.library`, `.compose`, `.extension-api`, `.strings`,
  `.android-only`.
- `:shared` — being retired (`KMP-06`). `:core:ui` holds the first-party Compose layer; the vendored
  Mihon tree (`com.mihon.*`, Apache-2.0, see `NOTICE`) is still in `:shared` until `KMP-06b`.
- `:desktopApp` — a placeholder JVM app.

## The two constraints on every module move

- **`classes.jar` is a published binary contract.** Third-party extensions compile against
  `app/build/classes.jar`; `app/api/classes-jar.api` is its dump. `:app:checkExtensionApi` is the gate
  and is **not** part of `check` — run it separately. FQNs, file facades, nested-class names, `R`
  field names and `$stable` fields are all part of the contract (plan §3.2).
- **The `$stable` trap** (`KMP-09`). Moving a Compose-compiled class into a module without the
  Compose compiler silently drops `public static final int $stable`. A module that receives classes
  from `:app` applies `cloudstream.kmp.extension-api`. Scan with `javap` before claiming a move is
  signature-neutral; an ABI dump alone misses the cross-module case.

## Strings

`app/src/main/res/values*/strings.xml` is **generated** — never hand-edit it. The source is
`core/strings/src/commonMain/composeResources/`. Regenerate with
`./gradlew :core:strings:writeAndroidStrings`. See `docs/HANDOVER.md` before touching a string.

## Running checks locally

CI has never run on this repository (`DEC-01`). Every check is a local run, so run all three of the
commands in `docs/HANDOVER.md` and say in the PR that they were local. The traps:

- `TZ=UTC` — `BUG-08`, `EpisodeDateTest` fails off-UTC.
- `JAVA_HOME=<Temurin 17>` — `INF-06`, `:core:*` tests run on the daemon JVM, and on 21+
  `checkKotlinAbi` fails against the 17-written baselines. Never run `updateKotlinAbi` on 21+.
- `-Dorg.gradle.jvmargs="-Xmx14G -Dfile.encoding=UTF-8"` — the 4G default OOMs D8 in a full run.
- `-x :app:generateGitHash` — only in a git worktree.
- **A piped Gradle run reports the pipe's exit code.** `./gradlew … | tail` exits 0 on a failed
  build. Redirect to a file and capture `$?`.

## How work is done here

- One small task per PR, referenced by its task ID. Update the affected `docs/tasks/` file in the
  same PR.
- **Measure, don't argue.** Nearly every wrong claim in this project's history was supported by a
  plausible argument nobody executed. Re-run a measurement a second way before writing the number
  down, and re-verify anything a subagent reports.
- Never mask a failing check, and never re-record a baseline to make it pass.
- Questions listed as waiting on the owner in `docs/HANDOVER.md` are not yours to settle.
- No unsolicited changes to existing code (`.claude/rules/no-unsolicited-changes.md`); no new
  dependency without asking (`dependency-security.md`); no hand-drawn icons (`icons.md`); every `dp`
  on the 4 dp step (`dimension-scale.md`).
