import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvm {}
    sourceSets {
        jvmMain.dependencies {
            implementation(libs.bundles.compose)
            implementation(compose.desktop.currentOs) {
                // compose.desktop.currentOs imports the wrong material 2, so we exclude it
                exclude(group = "org.jetbrains.compose.material", module = "material")
            }
            // :core:mihon, not :shared (KMP-06d): Main.kt uses SwitchPreferenceWidget from the
            // vendored Mihon tree and reaches :core:ui's components, theme and `Res` through its
            // `api` edge. It uses nothing :shared declares, so this is the whole of :shared's
            // desktop surface.
            implementation(project(":core:mihon"))

            // Desktop first (owner, 2026-10-15): the window hosts the search vertical rather than
            // a Hello-World placeholder. :library and :core:data are what APIRepository needs;
            // :core:strings is where the copy comes from, resolved with Compose resources rather
            // than a Context — see DesktopSearchCopy's KDoc for why that distinction matters.
            implementation(project(":feature:search"))
            implementation(project(":core:designsystem"))
            implementation(project(":core:strings"))
            implementation(project(":core:data"))
            implementation(project(":library"))
        }
    }
}

// java.lang.System::load has been called by org.jetbrains.skiko.LibraryLoader in an unnamed module
tasks.withType<JavaExec> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

compose.desktop {
    application {
        mainClass = "com.lagradost.cloudstream4.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "CloudStream"
            packageVersion = "1.0.0"

            val iconsRoot = project.file("desktop-icons")
            macOS {
                // iconFile.set(iconsRoot.resolve("icon-mac.icns"))
            }
            windows {
                iconFile.set(iconsRoot.resolve("icon-windows.ico"))
                // menuGroup = "Compose Examples"
                // see https://wixtoolset.org/documentation/manual/v3/howtos/general/generate_guids.html
                // upgradeUuid = ""
            }
            linux {
                iconFile.set(iconsRoot.resolve("icon-linux.png"))
            }
        }

        //buildTypes.release.proguard {
        //    configurationFiles.from(project.file("rules.pro"))
        //}
    }
}