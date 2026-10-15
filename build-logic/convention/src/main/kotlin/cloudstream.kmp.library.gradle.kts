import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
import org.jetbrains.kotlin.gradle.tasks.KotlinNativeLink

/**
 * Base convention for every Kotlin Multiplatform library module in CloudStream:
 * [cloudstream.kmp.base] (SDK levels, `jvmTarget`, opt-ins) plus the `jvm()` canary and the
 * opt-in iOS canary.
 *
 * ## The `jvm()` target is a purity canary, not a shipping target
 *
 * Nothing ships from the `jvm` target of a `:core:*` module today. It is declared so that the
 * *build fails* the moment an `android.*` import reaches `commonMain`: `commonMain` must compile
 * against the intersection of `android` and `jvm`, and the JVM target has no `android.*` on its
 * classpath. Removing `jvm()` silently turns every `commonMain` source set back into an
 * Android-only source set and the migration becomes cosmetic. Do not remove it.
 *
 * A module whose code is irreducibly Android uses `cloudstream.kmp.android-only` instead, which
 * says so in its build file rather than hiding it behind a canary that never compiles anything.
 *
 * ## The iOS canary: `-Pcloudstream.iosCanary=true`
 *
 * `jvm()` only proves `commonMain` is Android-free. Android and JVM are both JVMs, so the shared
 * `commonMain` compilation still sees the JDK: `java.*` imports, `synchronized`, `String.format`,
 * OkHttp and Jackson all compile in `commonMain` today. The canary adds `iosArm64()` +
 * `iosSimulatorArm64()` so `commonMain` must also compile for Kotlin/Native, where none of that
 * exists.
 *
 * ```
 * ./gradlew iosCanary -Pcloudstream.iosCanary=true
 * ```
 *
 * - **Off by default.** With the property absent or `false` this plugin declares exactly the
 *   targets and tasks it always did: no iOS target, no `iosCanary` task, no Kotlin/Native
 *   download into `~/.konan`, and Android artifacts and classes.jar are unchanged.
 * - **Klibs only.** `iosCanary` depends on the two main `compileKotlinIos*` klib compilations.
 *   Every `KotlinNativeLink` and `KotlinNativeSimulatorTest` task is disabled, so nothing links,
 *   nothing runs and nothing ships — the test gate below is the one exception, and only for the
 *   simulator test binary. There is no `iosX64`: Compose Multiplatform 1.12 publishes none.
 * - **`jvmCommonMain` is invisible to it.** Code that `cloudstream.kmp.extension-api` puts in
 *   `jvmCommonMain` is never compiled for iOS; the canary guards `commonMain` only. A module with
 *   an empty `commonMain` (`:core:network`) passes trivially.
 * - **Linux runners.** Kotlin/Native cross-compiles Apple klibs on Linux only while no dependency
 *   uses cinterop or CocoaPods. Compose UI's iOS klibs are cinterop-backed, so any module that
 *   resolves Compose UI, directly or transitively (`cloudstream.kmp.compose`, Compose resources),
 *   needs a macOS host; modules without it may run on Linux.
 * - **Exemptions.** A module whose `commonMain` depends on `:library` cannot pass until Track L
 *   makes `:library` iOS-compilable (migration plan §4.1 rule 4). It goes in
 *   [IOS_CANARY_EXEMPT], which gets no iOS targets and no `iosCanary` task. That list may only
 *   shrink. A module depending on an exempt module is necessarily exempt too.
 *
 * ## The iOS test gate: `-Pcloudstream.iosTest=true` (`INF-04`)
 *
 * The canary compiles and stops. It links nothing and runs nothing, so a `commonTest` assertion
 * that holds on the JVM and breaks on Kotlin/Native — a `Char` category, a `toString()` of a
 * `Double`, a date formatter, an atomic's memory model — is invisible to it. This gate links the
 * `iosSimulatorArm64` test binary and runs it on a simulator, which is the only way an iOS
 * *behaviour* difference fails a build.
 *
 * ```
 * ./gradlew iosSimulatorTest -Pcloudstream.iosTest=true
 * ```
 *
 * - **Off by default, and independent of the canary.** Absent or `false`, this plugin behaves
 *   exactly as it did: the property is read once per module and nothing else changes. The two
 *   gates compose — pass both properties and both tasks exist — but neither implies the other.
 * - **`iosSimulatorArm64` only, and this gate does not pay for `iosArm64`.** `iosArm64` is a
 *   device target: there is no `KotlinNativeSimulatorTest` for it and its test binary cannot be
 *   executed on a host. So the test gate alone declares one target, not two; the canary still
 *   declares both because compiling for both is its whole point.
 * - **It runs nothing today, and says so per module.** Measured 2026-10-15: **no module on this
 *   plugin has a `commonTest` directory.** All 18 `.kt` test files across `:core:*`,
 *   `:media:model` and `:feature:*` are in `src/jvmTest` (plus 9 more in `:core:strings`'s
 *   `jvmTestUiString`, an extra srcDir on that same JVM source set), and `jvmTest` is a JVM
 *   source set that Kotlin/Native never sees. The only `commonTest` in the repository belongs to
 *   `:library`, which does not apply this plugin and is not iOS-compilable (Track L). Moving a
 *   suite from `jvmTest` to `commonTest` is a per-module change with a real cost and is not
 *   something this plugin does; until one happens, `iosSimulatorTest` prints which module it
 *   skipped and why, rather than passing quietly.
 * - **The trap this shape avoids: an empty test task is a *failure*, not a "0 tests".** Gradle
 *   9.4.1's `AbstractTestTask` conventions `failOnNoDiscoveredTests` to `true` (verified in
 *   `gradle-testing-base-9.4.1.jar`) and throws "the test task did not discover any tests to
 *   execute" when an unfiltered run discovers none. Wiring `iosSimulatorTest` straight to the KGP
 *   task would therefore turn the gate red in all 14 canary modules the day it was added — red
 *   for "there is nothing here", which teaches nobody anything and invites the
 *   `failOnNoDiscoveredTests = false` that this repository forbids. Hence [IOS_REACHABLE_TEST_SOURCE_SETS]: the KGP test task is wired in, and
 *   its link task enabled, only for a module that actually has a `.kt` file Kotlin/Native can see.
 * - **A module opting in declares its own test dependencies**, in `commonTest`, the way it already
 *   declares them in `jvmTest`:
 *   ```
 *   commonTest.dependencies { implementation(libs.kotlin.test) }
 *   ```
 *   The plugin does not add them for every module: `commonTest` is a parent of `jvmTest` and of
 *   `:core:strings`'s `androidDeviceTest`, so a dependency added here lands on working test
 *   classpaths that did not ask for it.
 * - **No simulator is named, on purpose.** KGP's `XcodeDefaultTestDevicesValueSource` picks the
 *   device itself from `xcrun simctl list devices available` and sets it as the task's convention,
 *   so there is no device id to pin here and nothing to re-pin when Xcode's simulator list
 *   changes. `--device <udid>` on the task overrides it for a one-off run.
 * - **`check` picks it up once a module has sources.** KGP registers `iosSimulatorArm64Test` in
 *   `allTests`, which `check` depends on, so with the property set gate 2 runs the iOS suite too.
 *   Without the property the task does not exist, and with it but no sources it stays disabled, so
 *   `check` is unaffected in both of today's cases.
 * - **macOS only, which the gate does not have to state.** KGP disables the simulator test task
 *   unless the host is a Mac of the target's architecture; this plugin only ever *disables* tasks
 *   and never force-enables one, so that stays true.
 * - **The exemption list is shared.** [IOS_CANARY_EXEMPT] governs both gates — a module that
 *   cannot compile for iOS cannot run a test there either — and is not widened by this gate.
 */

plugins {
    id("cloudstream.kmp.base")
    id("org.jetbrains.kotlin.multiplatform")
}

/**
 * Modules the iOS canary skips because their `commonMain` depends on `:library` (plan §4.1
 * rule 4). This list may only shrink.
 *
 * It governs the `-Pcloudstream.iosTest=true` gate too: a module with no iOS target has no iOS
 * test target either.
 */
val IOS_CANARY_EXEMPT: Set<String> = setOf(
    ":core:model", // commonMain api(project(":library")); lifts when Track L makes :library iOS-compilable
    // commonMain implementation(project(":library")): AppSettings's preference defaults and value
    // types are TvType / DubStatus / SearchQuality / AllLanguagesName. Not a new dependency —
    // `:shared` declared the same edge (KMP-06d moved the code here) and had no iOS target at all,
    // so iOS readiness is unchanged. Lifts with :core:model's, on the same Track L work.
    ":core:settings",
)

/**
 * The source set directories under `src/` whose `.kt` files reach the `iosSimulatorArm64` **test**
 * compilation, in the order the default hierarchy template nests them.
 *
 * `jvmTest` is deliberately absent and is where every test in these modules lives today — see the
 * test-gate section of the KDoc above. The list is the conventional directory names only: a module
 * that adds test sources through `kotlin.srcDir(...)` (as `:core:strings` does for
 * `jvmTestUiString`) is not detected, and would wire its iOS test task by moving the files instead.
 */
val IOS_REACHABLE_TEST_SOURCE_SETS: List<String> =
    listOf("commonTest", "nativeTest", "appleTest", "iosTest", "iosSimulatorArm64Test")

/** The one simulator target either gate declares, and the only Apple target that can run a test. */
val IOS_SIMULATOR_TARGET = "iosSimulatorArm64"

val iosExempt = project.path in IOS_CANARY_EXEMPT
val iosCanaryEnabled = providers.gradleProperty("cloudstream.iosCanary").orNull?.toBoolean() == true &&
    !iosExempt
val iosTestEnabled = providers.gradleProperty("cloudstream.iosTest").orNull?.toBoolean() == true &&
    !iosExempt

/**
 * The [IOS_REACHABLE_TEST_SOURCE_SETS] directories this module actually has Kotlin in.
 *
 * Resolved at configuration time and kept as plain strings, so the task actions below capture a
 * `List<String>` rather than the project — that part is the configuration-cache requirement and it
 * is satisfied by construction.
 *
 * **What is *not* established** is whether adding the first `commonTest` file invalidates a stored
 * configuration-cache entry or is missed by it — only that the entry is reused at all, which it is
 * (an identical repeat of `:feature:search:iosSimulatorTest -Pcloudstream.iosTest=true` reports
 * "Configuration cache entry reused"). The four-gate script never shows a reuse because its four
 * invocations carry different flags and therefore store four different entries; that is expected,
 * not a defect. If you add a `commonTest` source set and this gate does not see it, suspect this
 * first and re-run with `--no-configuration-cache` to confirm.
 */
val iosReachableTestSources: List<String> = when {
    !iosTestEnabled -> emptyList()
    else -> IOS_REACHABLE_TEST_SOURCE_SETS.filter { sourceSet ->
        layout.projectDirectory.dir("src/$sourceSet").asFile
            .walkTopDown()
            .any { it.isFile && it.extension == "kt" }
    }
}

kotlin {
    // The purity canary. See the KDoc above before touching this line.
    jvm()

    // Idempotent: KGP applies it implicitly when nothing calls dependsOn, and ignores a re-apply
    // (cloudstream.kmp.extension-api calls it too). Explicit so a module adding a manual dependsOn
    // keeps iosMain / appleMain / nativeMain.
    applyDefaultHierarchyTemplate()

    if (iosCanaryEnabled || iosTestEnabled) {
        // Declared by either gate; the canary's second platform is the test gate's cost to avoid.
        val simulator = iosSimulatorArm64()
        // [IOS_SIMULATOR_TARGET] is matched against task metadata below, where a stale string would
        // silently stop matching. Fail here, where the cause is one line away, instead.
        if (simulator.targetName != IOS_SIMULATOR_TARGET) {
            throw GradleException(
                "IOS_SIMULATOR_TARGET says $IOS_SIMULATOR_TARGET, KGP says ${simulator.targetName}"
            )
        }

        if (iosCanaryEnabled) {
            // Klibs only; see "The iOS canary" in the KDoc above.
            val iosCompilations = listOf(iosArm64(), simulator).map { target ->
                target.compilations.getByName("main").compileTaskProvider
            }
            tasks.register("iosCanary") {
                group = "verification"
                description = "Compiles commonMain as iOS klibs (iosArm64, iosSimulatorArm64). Links nothing."
                dependsOn(iosCompilations)
            }
        }
    }
}

if (iosTestEnabled) {
    // Hoisted out of the task action: a `doLast` lambda may capture only values the configuration
    // cache can serialise, which a Project or a Task is not.
    val skipMessage = "iosSimulatorTest: skipped ${project.path} — no test sources reach iOS, " +
        "nothing in ${IOS_REACHABLE_TEST_SOURCE_SETS.joinToString("/")}. See INF-04"
    val simulatorTestTasks = tasks.withType<KotlinNativeSimulatorTest>()

    tasks.register("iosSimulatorTest") {
        group = "verification"
        if (iosReachableTestSources.isEmpty()) {
            description = "Reports that this module has no test sources Kotlin/Native can see."
            // Loud rather than vacuously green: the gate's whole value today is naming the modules
            // whose suites stop at the JVM.
            // No explicit lambda parameter: `doLast` has both an Action and a Groovy Closure
            // overload, and naming the parameter makes Kotlin pick the Closure one and fail to
            // infer it. `logger` resolves against the Task receiver of `register`'s block.
            doLast { logger.lifecycle(skipMessage) }
        } else {
            description = "Runs the $IOS_SIMULATOR_TARGET test binary on a simulator " +
                "(sources: ${iosReachableTestSources.joinToString()})."
            dependsOn(simulatorTestTasks)
        }
    }
}

if (iosCanaryEnabled || iosTestEnabled) {
    // The canary never links or runs, even when `check`/`allTests` pull these in. The test gate
    // makes exactly one exception: the simulator test binary, in a module that has sources for it.
    val simulatorTestsRun = iosTestEnabled && iosReachableTestSources.isNotEmpty()

    tasks.withType<KotlinNativeLink>().configureEach {
        val isSimulatorTestLink = binary is TestExecutable &&
            binary.target.targetName == IOS_SIMULATOR_TARGET
        if (!simulatorTestsRun || !isSimulatorTestLink) {
            enabled = false
        }
    }

    if (!simulatorTestsRun) {
        tasks.withType<KotlinNativeSimulatorTest>().configureEach { enabled = false }
    }
}
