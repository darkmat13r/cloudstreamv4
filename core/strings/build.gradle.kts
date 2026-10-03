/**
 * `:core:strings` — Compose Multiplatform string resources generated from `app/src/main/res` (plan S1, §7 Phase 3
 * S-a). Android res and Weblate stay the source; `:app`, its `R` ids and `.github/locales.py` do not change.
 *
 * ## Not extension API
 *
 * Package `com.lagradost.cloudstream4.core.strings`, no `com/lagradost/cloudstream3` directory: never merged into
 * `:app:makeJar`'s `classes.jar` (I2). Nothing depends on this module yet (S-b adds the text type and consumers).
 *
 * ## Where the values live
 *
 * `src/commonMain/composeResources/values…/strings.xml` is the authored tree and is what Compose packages, by way
 * of `materialiseComposeStrings`. `generateComposeStrings` still runs over `app/src/main/res` on every build, but
 * now as the **oracle** the authored tree is checked against rather than as the thing that feeds Compose. The two
 * trees are redundant by construction and every value of both is compared; the flip that drops one of them is a
 * later change.
 *
 * ## What is generated
 *
 * Set up by `cloudstream.kmp.strings` — see that plugin and `strings.GenerateComposeStringsTask`.
 *
 * - every `<string>` and `<plurals>` of `values…/strings.xml`, plus the names in `visible-donottranslate.txt`
 *   (the rest of `donottranslate-strings.xml` are preference keys and view tags);
 * - `@string/` references resolved in each requesting locale, and values copied into locale directories wherever
 *   Compose's locale fallback would otherwise return something other than Android's (API 24+ `ResTable_config`);
 * - `values-b+he` / `values-b+id` as exact copies of `values-b+iw` / `values-b+in`: Android maps `he`/`id` to
 *   `iw`/`in`, Compose asks for `he`/`id` on API 35+, JVM and iOS (`localeCopies`, `strings.LocaleCopies`). Adding a
 *   real `values-b+he` or `values-b+id` to `app/src/main/res` fails the build until its copy entry is removed;
 * - styled text (`<b>`) as plain text; no arrays; no build-type or flavor overlays.
 *
 * ## Checks
 *
 * - `generateComposeStrings` reports content guards by default and fails on them with
 *   `-Pcloudstream.strings.guards=fail`, which pull requests pass; it always fails on aapt2 failures. A translation
 *   pushed to master therefore never breaks a local, IDE or release build.
 * - Oracles and how to regenerate them after an AGP bump: `tools/README.md`.
 * - `jvmTest` reads the generated resources back through the real Compose runtime and compares every value and
 *   every request in `parity-requests.txt` with Android's, from the generator's aapt2-decoded oracles.
 * - `:core:strings:fixtures` runs the same pipeline and tests over adversarial inputs and a hand-checked golden decode.
 * - iOS: no CI job runs the canary yet (plan §6.3 G4); check by hand on macOS with
 *   `./gradlew :core:strings:iosCanary -Pcloudstream.iosCanary=true`.
 */
plugins {
    id("cloudstream.kmp.strings")
}

kotlin {
    sourceSets {
        jvmTest {
            // `src/jvmTest/kotlin` is compiled by `:core:strings:fixtures` too, over its own adversarial resources
            // and its own `Res`; it has no `UiString`. The S-b suite is this module's alone.
            kotlin.srcDir("src/jvmTestUiString/kotlin")
        }
    }
}

// `oracle-baseline/` is the frozen pre-flip output of `generateComposeStrings`, compared by `OracleBaselineTest`.
// It belongs to this module only: fixtures generates from its own `res` and has nothing to compare.
tasks.named<Test>("jvmTest") {
    jvmArgumentProviders.add(
        objects.newInstance<strings.StringsBaselineArgument>().apply {
            baselineDir.set(layout.projectDirectory.dir("oracle-baseline"))
        },
    )
}

// The strings flip is loss-free only if the authored Compose tree survives a full round trip through Android res
// and back. `verifyStringsRoundTrip` asserts that; see `strings.VerifyStringsRoundTripTask`. It belongs to this
// module only, for the same reason as the baseline: fixtures has no authored tree and no frozen lookup.
// The five values of `authored-additions.tsv` are part of the authored tree, so the generator that produces that
// tree emits them too. They reach the emitted map only - never `lookup`, which is Android's own answer and is what
// `verifyStringsRoundTrip` holds to a zero-line diff.
val generateComposeStrings = tasks.named<strings.GenerateComposeStringsTask>("generateComposeStrings") {
    additions.set(layout.projectDirectory.file("authored-additions.tsv"))
}

// Compose packages `src/commonMain/composeResources` — the authored tree — rather than the generator's output.
// In this change the two hold the same values: the authored tree is a verbatim copy of what `generateComposeStrings`
// emits, and `GeneratedResourcesParityTest` compares every packaged value with that generator's aapt2-decoded
// oracle, through the real Compose runtime. So both trees are live and each is checked against the other.
// `materialiseComposeStrings` adds `values-b+he` and `values-b+id`, which are byte copies of `values-b+iw` and
// `values-b+in` and so must not be checked in twice (`strings.LocaleCopies`).
tasks.named<strings.MaterialiseComposeStringsTask>("materialiseComposeStrings") {
    sourceDirectory.set(layout.projectDirectory.dir("src/commonMain/composeResources"))
    localeCopies.set(generateComposeStrings.flatMap { it.localeCopies })
}

val verifyStringsRoundTrip = tasks.register<strings.VerifyStringsRoundTripTask>("verifyStringsRoundTrip") {
    group = "verification"
    description = "Re-encodes the authored Compose values as Android res and asserts the generator's fixed point."
    aapt2.set(generateComposeStrings.flatMap { it.aapt2 })
    resDirectory.set(generateComposeStrings.flatMap { it.resDirectory })
    visibleDoNotTranslate.set(generateComposeStrings.flatMap { it.visibleDoNotTranslate })
    parityRequests.set(generateComposeStrings.flatMap { it.parityRequests })
    localeCopies.set(generateComposeStrings.flatMap { it.localeCopies })
    guardMode.set(generateComposeStrings.flatMap { it.guardMode })
    additions.set(layout.projectDirectory.file("authored-additions.tsv"))
    baselineLookup.set(layout.projectDirectory.file("oracle-baseline/lookup.tsv"))
    // 33100 values the pre-flip generator emitted outside the two localeCopies directories, plus the five of
    // `authored-additions.tsv`. Every one of them must come from the directory that declares it: OWN and nothing else.
    expectedOwn.set(33105)
    outputDir.set(layout.buildDirectory.dir("stringsRoundTrip"))
}

tasks.named("check") {
    dependsOn(verifyStringsRoundTrip)
}
