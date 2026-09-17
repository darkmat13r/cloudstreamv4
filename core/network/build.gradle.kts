/**
 * `:core:network` — OkHttp plumbing: today the DNS-over-HTTPS providers.
 *
 * ## FQN freeze: these classes are published extension API
 *
 * Every class here keeps the `com.lagradost.cloudstream3.*` fully-qualified name it had in
 * `:app`, and every file keeps its name: `DohProviders.kt` compiles to the facade `DohProvidersKt`,
 * which third-party extensions call the `addXxxDns` functions on. `:app:makeJar` merges this
 * module's `jvm` jar into `app/build/classes.jar`, so a rename breaks every published extension.
 * Move files; never rename them or their package. When you move an extension-facing class here,
 * add a sentinel to `cloudstream.extension-api-jar` in the same change.
 *
 * ## Source sets and dependencies
 *
 * Set up by `cloudstream.kmp.extension-api` — see that plugin's KDoc.
 *
 * - `jvmCommonMain` — OkHttp is JVM-only, so everything here is JVM-bound but Android-free.
 * - `api(libs.nicehttp)` — `okhttp3.OkHttpClient$Builder` is in `DohProvidersKt`'s public
 *   signatures, so callers need OkHttp on their compile classpath. NiceHttp exposes `okhttp` and
 *   `okhttp-dnsoverhttps` through its own `api`.
 */
plugins {
    id("cloudstream.kmp.extension-api")
}

kotlin {
    sourceSets {
        getByName("jvmCommonMain").dependencies {
            api(libs.nicehttp)
        }
    }
}
