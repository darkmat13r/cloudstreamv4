/**
 * `:core:strings` — Compose Multiplatform string resources generated from `app/src/main/res` (plan S1, §7 Phase 3
 * S-a). Android res and Weblate stay the source; `:app`, its `R` ids and `.github/locales.py` do not change.
 *
 * ## Not extension API
 *
 * Package `com.lagradost.cloudstream4.core.strings`, no `com/lagradost/cloudstream3` directory: never merged into
 * `:app:makeJar`'s `classes.jar` (I2). Nothing depends on this module yet (S-b adds the text type and consumers).
 *
 * ## What is generated
 *
 * Set up by `cloudstream.kmp.strings` — see that plugin and `strings.GenerateComposeStringsTask`.
 *
 * - every `<string>` and `<plurals>` of `values…/strings.xml`, plus the names in `visible-donottranslate.txt`
 *   (the rest of `donottranslate-strings.xml` are preference keys and view tags);
 * - `@string/` references resolved in each requesting locale, and values copied into locale directories wherever
 *   Compose's locale fallback would otherwise return something other than Android's (API 24+ `ResTable_config`);
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
