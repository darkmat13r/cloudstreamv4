/**
 * `:feature:extensions:sandbox` — runs third-party extensions outside the app's process.
 *
 * JVM only, and deliberately not on a `cloudstream.kmp.*` convention plugin: those add an Android
 * target and the iOS canary, and neither a child process nor macOS Seatbelt exists on either.
 * Package `com.lagradost.cloudstream4…`, so `:app:makeJar` never merges it. It is not on `:app`'s
 * classpath at all, which is what makes the `android.*` and `plugins.Plugin` shims here safe: they
 * can only ever be seen by a plugin host.
 */
plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm()
    sourceSets {
        jvmMain.dependencies {
            implementation(project(":feature:extensions"))
            implementation(project(":library"))
            implementation(libs.kotlinx.coroutines.core)
            // Already on this classpath through `:library`, as `implementation`. YoutubeProvider
            // fails without `NewPipe.init`, and only a host can call it.
            implementation(libs.newpipeextractor)
            // Needed at runtime only inside the conversion process. It parses hostile input, so
            // nothing outside DexConverter may call it.
            implementation(libs.dex2jar.translator)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
