/**
 * `:core:storage` — the persistence ports (migration plan §4.2, §4.3, Phase 4).
 *
 * ## Not extension API
 *
 * Package `com.lagradost.cloudstream4.core.storage`, **no `com/lagradost/cloudstream3` directory in
 * any source set**: `cloudstream.extension-api-jar` merges a candidate module into
 * `app/build/classes.jar` only when it declares that package, so this module stays out of the jar
 * and its API can change freely. Adding a `com/lagradost/cloudstream3` directory here would make it
 * published extension API the next time `:app` depends on it — don't.
 *
 * `:app`'s `DataStore` stays exactly where it is. Its `Context.getKey` / `Context.setKey` are
 * `inline reified`, so their bodies are copied into every extension built against them; `javap` on
 * `classes.jar` shows those bodies calling `invokeinterface android/content/SharedPreferences.getString`
 * and `.putString` after `invokevirtual DataStore.getSharedPrefs`. They bind to the
 * `SharedPreferences` **interface** and to the JSON-string encoding, never to the XML file, which is
 * why another implementation behind that interface is ABI-safe.
 *
 * ## Layering
 *
 * Layer 1 (plan §4.1): it may depend on layer 0 only. In particular **not** on `:core:data`, which
 * is layer 2 and owns `PREFERENCES_NAME`; [StorageFiles] mirrors that constant instead and names it
 * in its KDoc. Not on `:library` either, so plan §4.1 rule 4 does not apply and this module is not
 * iOS-canary exempt.
 *
 * ## Source sets and dependencies
 *
 * Set up by `cloudstream.kmp.library`: `android` + the `jvm()` purity canary, plus `iosArm64` /
 * `iosSimulatorArm64` under `-Pcloudstream.iosCanary=true`.
 *
 * - `commonMain` — the three ports, their in-memory implementations and the key algebra. Pure
 *   Kotlin plus `kotlinx-coroutines-core`, which `PreferenceData.changes()` / `stateIn()` need in
 *   their signatures (`Flow`, `StateFlow`, `CoroutineScope`) to keep the shape `:shared`'s
 *   `PreferenceStore` already has. No `java.*`, no `android.*`, no kotlinx-serialization: values
 *   cross [KeyValueJsonStore] as JSON **strings**, so the encoding stays `:app`'s own and this
 *   module never needs a JSON codec.
 * - `androidMain` — `SharedPreferences`-backed implementations over today's two files. Nothing
 *   beyond the Android SDK: the settings store takes a `SharedPreferences` rather than reaching for
 *   `androidx.preference.PreferenceManager`, so `:app` keeps deciding what "default preferences"
 *   means and this module adds no dependency.
 * - `jvmTest` — the behavioural contract, run against the in-memory implementations.
 *   **Robolectric is not available and must not be added**, so the `SharedPreferences`
 *   implementations are not covered here; see the KDoc of `AndroidStorage` for what that leaves
 *   unverified.
 */
plugins {
    id("cloudstream.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            // `PreferenceData.changes()` is a Flow, so its contract needs `runTest` to assert.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
