plugins {
    id("cloudstream.kmp.compose")
    // Real D-pad traversal on a device. `INF-10`; see `androidDeviceTest`.
    id("cloudstream.kmp.device-test")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":core:theme"))
            // Behind CloudStreamIcons; a screen names the meaning, never the pack (DEC-11).
            implementation(libs.tabler.icons)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            // Skia, to draw the icons offscreen.
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources {
    // The bundled fonts (KMP-97). Not `auto`: it compares an exact components-resources coordinate and silently
    // stops generating on a catalog change (same reasoning as :core:ui and cloudstream.kmp.strings). The package and
    // the private Res class are the convention's.
    generateResClass = always
}
