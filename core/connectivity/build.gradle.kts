/**
 * `:core:connectivity` — whether this device can reach a network, as something a screen observes.
 *
 * First-party (plan I2): package `com.lagradost.cloudstream4.core.connectivity`, no
 * `com/lagradost/cloudstream3` directory, so it is never merged into `:app:makeJar`'s `classes.jar`.
 * A module of its own rather than a corner of `:core:http`, because every screen's offline state
 * observes it and none of them should inherit Ktor for that. `:core:common`, the other obvious home,
 * is frozen API.
 *
 * - `commonMain` — [ConnectivityFlow], `UnknownConnectivityFlow` (the desktop and iOS answer), and the
 *   pure reducer `:androidMain` feeds. No platform type.
 * - `androidMain` — `AndroidConnectivityFlow` over `ConnectivityManager`.
 * - `commonTest` — the reducer's rules. `iosSimulatorTest` (`INF-04`) can run them too.
 * - `androidDeviceTest` — a real network toggled on a real emulator: nothing in `commonTest` can show
 *   that the callbacks arrive, so that is what this is for.
 */
plugins {
    id("cloudstream.kmp.library")
    id("cloudstream.kmp.device-test")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // `StateFlow` is in `ConnectivityFlow`'s signature.
            api(libs.kotlinx.coroutines.core)
        }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
