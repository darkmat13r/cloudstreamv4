/**
 * `:feature:player` — the TV player's HUD, artboard 7d of `design/TV Detail Player.dc.html` (`KMP-64`, queue `Q24`).
 *
 * commonMain holds the HUD's state, its visibility and scrub rules, and the screen. `:app` hosts it over the existing
 * player (`FullScreenPlayer`), maps the player's facts into [com.lagradost.cloudstream4.feature.player.PlayerHudState]
 * and carries the actions back. Nothing here touches a stream: the state holds titles, source names, quality and track
 * labels, and never a URL, a header or a path.
 *
 * Not extension API: package `com.lagradost.cloudstream4.feature.player`, not merged into `classes.jar`.
 */
plugins {
    id("cloudstream.kmp.compose")
    id("cloudstream.kmp.device-test")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:ui"))
            implementation(project(":core:designsystem"))
            // Every word on the HUD comes from here (I18N-08), resolved where it is drawn.
            implementation(project(":core:strings"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
