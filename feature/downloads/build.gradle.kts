/**
 * `:feature:downloads` — what is on the disk and on its way there (`Q14`, boards 6c and 6e).
 *
 * Plain Kotlin and Compose in commonMain. `:app` implements [com.lagradost.cloudstream4.feature.downloads.DownloadsSource]
 * over its downloader and hosts the screen. Not merged into `classes.jar`: its package is `cloudstream4`,
 * like every `:feature:*` module.
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
            api(project(":core:connectivity"))
            api(project(":media:model"))
        }
        // The figures on a row are pure Kotlin, so these are the tests that reach iOS.
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
