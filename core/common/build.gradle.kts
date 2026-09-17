/**
 * `:core:common` — small utilities shared by every layer.
 *
 * ## FQN freeze: these classes are published extension API
 *
 * Every class here keeps the `com.lagradost.cloudstream3.*` fully-qualified name it had in
 * `:app`. Third-party extensions compile against `:app:makeJar` (`app/build/classes.jar`), which
 * merges this module's `jvm` jar, so a package rename or a class that drops out of that jar breaks
 * every published extension at load time. Move files; never rename their package.
 *
 * `makeJar` checks for sentinel classes and fails the build if one is missing: when you move an
 * extension-facing class here, add its `.class` entry to `sentinels` in the build-logic plugin
 * `cloudstream.extension-api-jar`, in the same change. Only this module's `jvm` jar is published,
 * so nothing extension-facing may live in `androidMain` alone.
 *
 * ## Source sets and build conventions
 *
 * Set up by `cloudstream.kmp.extension-api`, which also applies the Compose compiler so moved
 * classes keep the `$stable` field `:app` emitted — see that plugin's KDoc.
 *
 * - `commonMain` — pure Kotlin (`IDisposable`, `Vector2`).
 * - `jvmCommonMain` — JVM-bound but Android-free. `Event.kt` lives here: it uses `synchronized()`
 *   and exposes `java.lang.Runnable` in its public API. Porting it to `commonMain` (expect/actual,
 *   atomicfu) would change the bytecode signatures extensions link against.
 */
plugins {
    id("cloudstream.kmp.extension-api")
}
