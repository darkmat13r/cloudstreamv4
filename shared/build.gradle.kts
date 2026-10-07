plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.android.multiplatform.library)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    android {
        // Must be unique. `com.lagradost.cloudstream4` (whose `R` is in classes.jar) moved to :core:ui
        // with the resources, so this module declares none and generates no `R`.
        namespace = "com.lagradost.cloudstream4.shared"
        compileSdk = libs.versions.compileSdk.get().toInt()
        minSdk = libs.versions.minSdk.get().toInt()
    }

    jvm()

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        all {
            languageSettings {
                optIn("com.lagradost.cloudstream3.InternalAPI")
                optIn("com.lagradost.cloudstream3.Prerelease")
            }
        }

        commonMain.dependencies {
            // api: SearchableSettings extends :core:ui's Screen, and :app / :desktopApp reach :core:ui
            // through this edge.
            api(project(":core:ui"))
            implementation(libs.bundles.compose)
            implementation(libs.kotlinx.collections.immutable)
            implementation(project(":library"))
        }

        androidMain.dependencies {
            implementation(libs.activity.compose)
            implementation(libs.preference.ktx)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}

compose.resources {
    // :core:ui owns the public `Res` at com.lagradost.cloudstream4.generated.resources, which the
    // Mihon tree here imports. A second `Res` would collide on the classpath.
    generateResClass = never
}