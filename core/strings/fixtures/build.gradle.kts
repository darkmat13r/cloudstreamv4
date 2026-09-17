import strings.VerifyStringsGuardsTask

/**
 * `:core:strings:fixtures` — the `:core:strings` pipeline and parity tests over adversarial inputs instead of
 * `app/src/main/res`. Test-only: nothing depends on it and it ships nowhere.
 *
 * - `res/`: escaping and whitespace edge cases (backslash runs, CR/C0 escapes, quotes, CDATA, entities, spans,
 *   xliff, astral characters, BOM, a stray top-level `"`), per-locale references and every locale fallback the
 *   model distinguishes (pt -> pt-BR, fil <-> tl, zh-TW not zh, sr-Latn scripts, legacy `values-fr`, the
 *   `values-b+he` copy of `values-b+iw`). The shared `jvmTest` sources of `:core:strings` run over it with this
 *   module's own `Res`.
 * - `FixtureLocaleCopyTest`: the packaged `values-b+he` is exactly the emitted `values-b+iw`.
 * - `FixtureGoldenDecodeTest`: the generator's decoded table equals `golden-decoded.tsv`, which was produced with
 *   `protobuf-java` + `aapt2-proto` (not the generator's wire reader) and reviewed by hand.
 * - `guard-cases/`: inputs that must fail. `verifyStringsGuards` runs each through the real pipeline and aapt2 in
 *   `fail` and `report` mode (see `strings.VerifyStringsGuardsTask`); it is part of `check`.
 */
plugins {
    id("cloudstream.kmp.strings")
}

val generateComposeStrings = tasks.named<strings.GenerateComposeStringsTask>("generateComposeStrings") {
    resDirectory.set(layout.projectDirectory.dir("res"))
}

kotlin {
    sourceSets {
        jvmTest {
            kotlin.srcDir("../src/jvmTest/kotlin")
        }
    }
}

val verifyStringsGuards = tasks.register<VerifyStringsGuardsTask>("verifyStringsGuards") {
    group = "verification"
    description = "Runs every guard case through the strings pipeline in fail and report mode."
    aapt2.set(generateComposeStrings.flatMap { it.aapt2 })
    localeCopies.set(generateComposeStrings.flatMap { it.localeCopies })
    cases.set(layout.projectDirectory.dir("guard-cases"))
    resultFile.set(layout.buildDirectory.file("verifyStringsGuards/result.txt"))
}

tasks.named("check") {
    dependsOn(verifyStringsGuards)
}
