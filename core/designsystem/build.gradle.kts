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
