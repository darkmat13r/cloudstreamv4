/**
 * `:core:strings` — the authored Compose Multiplatform string resources, and the generator that keeps
 * `app/src/main/res` in step with them.
 *
 * **The direction is the reverse of plan S1.** That decision said Android res and Weblate stay the source; this
 * repo is not connected to that Weblate project, and the owner's instruction is that all strings are Compose
 * resources. `I18N-07` carries the six steps and the evidence. `:app` and its `R` ids are unchanged either way —
 * `R.string` field names are published extension ABI and `:app:checkExtensionApi` is the gate.
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
    android {
        // `I18N-06`: the android actual of `localeNumberSymbols()` reads ICU-backed locale data, where the jvm
        // actual reads the JDK's CLDR. Nothing compared the two, so the android actual was argued, not measured.
        // A device test is the only way to read Android's own tables; `jvmTest` cannot reach them.
        withDeviceTestBuilder { sourceSetTreeName = "test" }
            .configure { instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner" }
    }

    sourceSets {
        jvmTest {
            // `src/jvmTest/kotlin` is compiled by `:core:strings:fixtures` too, over its own adversarial resources
            // and its own `Res`; it has no `UiString`. The S-b suite is this module's alone.
            kotlin.srcDir("src/jvmTestUiString/kotlin")
        }

        getByName("androidDeviceTest").dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.ext.junit)
            // Present for `androidx.test:runner`, which the instrumentation runner above loads and which
            // `gradle/libs.versions.toml` has no alias of its own for; `espresso-core` depends on it at compile
            // scope. An `androidx-test-runner` alias would say this more honestly — that is a catalog change, so
            // it is the owner's call (`I18N-06`).
            implementation(libs.espresso.core)
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
    // `BUG-84`: `StringResourceArgumentsGuardTest` reads every module's main sources for Compose's argument overloads.
    // The same tree it walks is declared here, so editing any of those files re-runs it. Dot-directories are left out
    // (`BUG-85`): a worktree under `.claude/` is another checkout, and its edits should not re-run this one's guard.
    jvmArgumentProviders.add(
        objects.newInstance<strings.StringsCallSitesArgument>().apply {
            root.set(layout.settingsDirectory)
            sources.from(
                fileTree(layout.settingsDirectory) {
                    include("**/src/*Main/**/*.kt")
                    exclude("app/**", "build-logic/**", "**/build/**", ".*/**", "**/.*/**")
                },
            )
        },
    )
}

// The strings flip is loss-free only if the authored Compose tree survives a full round trip through Android res
// and back. `verifyStringsRoundTrip` asserts that; see `strings.VerifyStringsRoundTripTask`. It belongs to this
// module only, for the same reason as the baseline: fixtures has no authored tree and no frozen lookup.
val generateComposeStrings = tasks.named<strings.GenerateComposeStringsTask>("generateComposeStrings")

// Compose packages `src/commonMain/composeResources` — the authored tree — rather than the generator's output.
// In this change the two hold the same values: the authored tree is a verbatim copy of what `generateComposeStrings`
// emits, and `GeneratedResourcesParityTest` compares every packaged value with that generator's aapt2-decoded
// oracle, through the real Compose runtime. So both trees are live and each is checked against the other.
// `materialiseComposeStrings` adds `values-b+he` and `values-b+id`, which are byte copies of `values-b+iw` and
// `values-b+in` and so must not be checked in twice (`strings.LocaleCopies`).
tasks.named<strings.MaterialiseComposeStringsTask>("materialiseComposeStrings") {
    sourceDirectory.set(layout.projectDirectory.dir("src/commonMain/composeResources"))
    localeCopies.set(generateComposeStrings.flatMap { it.localeCopies })
    guardMode.set(generateComposeStrings.flatMap { it.guardMode })
    aliases.set(layout.projectDirectory.file("authored-aliases.tsv"))
}

// `app/src/main/res` is generated from the same lean authored tree (I18N-07 step 4.2), one directory to one
// directory with nothing added. Committed rather than generated at build time because `R.string` field names are
// published extension ABI, and a res materialising into a build directory would take every one of them out of
// review. Every values directory of the res is generated: `values-arz` held no values at all and was deleted in
// PR 5, with `arz` pinned in parity-requests.txt so the locale is still checked.
val androidStringsCommon: strings.AndroidStringsTask.() -> Unit = {
    authoredDirectory.set(layout.projectDirectory.dir("src/commonMain/composeResources"))
    resDirectory.set(layout.settingsDirectory.dir("app/src/main/res"))
    localeCopyTargets.set(generateComposeStrings.map { it.localeCopies.get().keys })
    // Empty on purpose: every values directory of the res is generated from the authored tree. A name here would
    // be a directory nothing checks, so adding one needs a reason written beside it.
    unmanagedDirectories.set(emptySet<String>())
    aliases.set(layout.projectDirectory.file("authored-aliases.tsv"))
}

tasks.register<strings.AndroidStringsTask>("writeAndroidStrings") {
    group = "build"
    description = "Rewrites app/src/main/res/values*/strings.xml from the authored Compose tree. Review the diff."
    androidStringsCommon()
    check.set(false)
    reportFile.set(layout.buildDirectory.file("androidStrings/write.txt"))
    outputs.upToDateWhen { false }
}

val checkAndroidStrings = tasks.register<strings.AndroidStringsTask>("checkAndroidStrings") {
    group = "verification"
    description = "Fails if app/src/main/res/values*/strings.xml is not what the authored Compose tree produces."
    androidStringsCommon()
    check.set(true)
    reportFile.set(layout.buildDirectory.file("androidStrings/check.txt"))
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
    baselineLookup.set(layout.projectDirectory.file("oracle-baseline/lookup.tsv"))
    // Every value emitted outside the two localeCopies directories; each must come from the directory that
    // declares it. A REFERENCE_IN_LOCALE or ANDROID_FALLBACK in the second pass means a value is missing.
    expectedOwn.set(56096)
    outputDir.set(layout.buildDirectory.dir("stringsRoundTrip"))
}

tasks.named("check") {
    dependsOn(verifyStringsRoundTrip, checkAndroidStrings)
}
