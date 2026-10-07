/**
 * First-party Compose layer: `com.lagradost.cloudstream4.compose` (screens, dialogs, modifiers, the MVI
 * primitives in `Viewmodel.kt`) and `com.lagradost.cloudstream4.theme`, carved out of `:shared` (KMP-06c).
 *
 * Two names are inherited from `:shared` and must not change, because published extensions link
 * against them through `:app:makeJar` (see `app/api/classes-jar.api`):
 *
 *  - the Android namespace `com.lagradost.cloudstream4`, whose `R` class is in the jar;
 *  - the public `Res` class at `com.lagradost.cloudstream4.generated.resources`. Exactly one module
 *    may generate it — two `Res` classes at one FQN collide on the classpath — so `:shared` no longer
 *    generates one.
 *
 * `androidResources` is enabled because without it no `composeResources` reach an APK (CMP-9547).
 */
plugins {
    id("cloudstream.kmp.compose")
}

kotlin {
    android {
        // Overrides the path-derived namespace from cloudstream.kmp.base. See the KDoc above.
        namespace = "com.lagradost.cloudstream4"

        androidResources {
            enable = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.preference.ktx)
        }
    }
}

compose.resources {
    // Overrides cloudstream.kmp.compose, which gives each module a private, path-derived package.
    publicResClass = true
    packageOfResClass = "com.lagradost.cloudstream4.generated.resources"
    // Not `auto`: it compares an exact components-resources coordinate and silently stops generating
    // on a catalog change (same reasoning as cloudstream.kmp.strings).
    generateResClass = always
}
