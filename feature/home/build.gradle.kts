/**
 * `:feature:home` — a source's main page: rows of titles, a source picker, and paging by row.
 *
 * Plain Kotlin and Compose in commonMain: no `:library`, no image library, so it stays in the iOS canary.
 * Hosts implement [com.lagradost.cloudstream4.feature.home.HomeSource] and draw the artwork. Not merged
 * into `classes.jar`: its package is `cloudstream4`, like every `:feature:*` module.
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
            // Every word on these screens comes from here (I18N-08), resolved where it is drawn.
            implementation(project(":core:strings"))
        }
        // The fold is pure Kotlin, so its tests are the ones that can reach iOS.
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
