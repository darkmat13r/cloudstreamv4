/**
 * `:feature:library` — what the user has kept: bookmarked titles, watch progress, and where to resume.
 *
 * Plain Kotlin and Compose in commonMain: no `:library`, no image library, so it stays in the iOS canary.
 * Hosts implement [com.lagradost.cloudstream4.feature.library.LibraryStore] and draw the artwork. Not
 * merged into `classes.jar`: its package is `cloudstream4`, like every `:feature:*` module.
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
            // The ViewModel takes a `ConnectivityFlow` (`KMP-51`), so a host hands its own in.
            api(project(":core:connectivity"))
        }
        // Sorting, bucketing and the resume rule are pure Kotlin, so these are the tests that reach iOS.
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
