/**
 * The Compose settings framework vendored from Mihon (`com.mihon.*`), Apache-2.0 — see `NOTICE` and
 * `src/commonMain/kotlin/com/mihon/readme.md`. Kept in its own module so the licence boundary is a
 * module boundary (KMP-06b): no first-party code belongs here.
 *
 * `api` on `:core:ui`: `SearchableSettings` extends its `Screen`, and the widgets use its dialogs,
 * modifiers, preview theme and `Res`.
 */
plugins {
    id("cloudstream.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:ui"))
            implementation(libs.kotlinx.collections.immutable)
        }
        androidMain.dependencies {
            implementation(libs.preference.ktx)
        }
    }
}
