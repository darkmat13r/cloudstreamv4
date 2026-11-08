/**
 * `:feature:account` — who is watching, and which sync services that profile uses (`Q16`, TV artboard 8d).
 *
 * Plain Kotlin and Compose in commonMain. `:app` implements [com.lagradost.cloudstream4.feature.account.AccountSource]
 * over its profiles and sync providers, renders the pairing QR code, and hosts the screen. Not merged into
 * `classes.jar`: its package is `cloudstream4`, like every `:feature:*` module.
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
            implementation(project(":core:strings"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
