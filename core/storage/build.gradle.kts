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
 * - `jvmMain` — file-backed implementations of the same three ports, for Compose for Desktop.
 *   **The JDK and nothing else**: one text file per store, written atomically, under a per-OS
 *   application directory. `java.util.prefs.Preferences` is deliberately not used — its 8192-character
 *   value limit cannot hold a CloudStream JSON record; `TextRecordFile`'s KDoc has the details. This
 *   is the one source set where the `jvm()` target is more than the purity canary
 *   `cloudstream.kmp.library` declares it as: `:desktopApp` is a real consumer. The canary still
 *   works exactly as before — `commonMain` still has to compile against the intersection.
 * - `commonTest` — the behavioural contract of the three ports plus the JSON-record encoding, run
 *   against the in-memory implementations. It lives here rather than in `jvmTest` so the
 *   `-Pcloudstream.iosTest=true` gate (`INF-04`) executes it on an `iosSimulatorArm64` simulator
 *   too: the four suites need only `kotlin.test`, `runTest` and `commonMain`, so the contract is
 *   checked on every target the ports claim rather than on the JVM alone.
 * - `jvmTest` — the same contract **again, by subclassing those suites, against the file-backed
 *   implementations** (`commonTest` is a parent source set, so they are visible), plus what only a
 *   file has (persistence, atomicity, corruption recovery, permissions) and the per-OS directory
 *   algebra.
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

        // `commonTest`, not `jvmTest`, for the four port-contract suites: they use `kotlin.test`,
        // `runTest` and this module's own `commonMain` types, with no `java.*` anywhere, so
        // `iosSimulatorTest` (`INF-04`) runs them on an `iosSimulatorArm64` simulator as well as on
        // the JVM. `commonTest` is a parent source set of `jvmTest`, so the file-backed suites that
        // subclass them (`FileStoreContractTest`) still see both the suites and these dependencies,
        // so `jvmTest` needs no declaration of its own.
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            // `PreferenceData.changes()` is a Flow, so its contract needs `runTest` to assert.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
