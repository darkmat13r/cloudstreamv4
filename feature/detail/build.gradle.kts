/**
 * `:feature:detail` — a title's page, its episodes, and choosing a stream (`KMP-15`).
 *
 * Plain Kotlin and Compose in commonMain: no `:library`, no player, no image library. Hosts implement
 * [com.lagradost.cloudstream4.feature.detail.DetailSource] and draw the artwork. Not merged into
 * `classes.jar`: its package is `cloudstream4`, like every `:feature:*` module.
 */
plugins {
    id("cloudstream.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:ui"))
            implementation(project(":core:designsystem"))
            // Every word on these screens comes from here (I18N-08), resolved where it is drawn.
            implementation(project(":core:strings"))
            // `api`: SubtitleTrack is on StreamLink. Plain data with no dependencies of its own.
            api(project(":media:model"))
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
