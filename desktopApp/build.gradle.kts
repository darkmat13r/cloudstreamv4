import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    // KMP-52: the downloads file is kotlinx.serialization JSON.
    alias(libs.plugins.kotlin.serialization)
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
            implementation(project(":feature:detail"))
            implementation(project(":feature:home"))
            // KMP-26: bookmarks, watch progress and resume. The store is this module's, under ~/.cloudstream4.
            implementation(project(":feature:library"))
            // KMP-54: the Mac downloads page. Its source is KMP-52's, under com.lagradost.cloudstream4.downloads.
            implementation(project(":feature:downloads"))
            // KMP-13: `JvmExtensionRuntime` and `ExtensionHash`, from this module's `jvmMain`. It is
            // the only extension runtime that works off Android — a `.cs3` is dex-only, so the
            // cross-platform `.jar` is what desktop loads. No catalogue entry is added for it; the
            // runtime needs nothing but the JDK and `:library`, which is already below.
            implementation(project(":feature:extensions"))
            // Plugins run in a sandboxed child process, never in this JVM. See SandboxedExtensions.
            implementation(project(":feature:extensions:sandbox"))
            // coil-compose comes with the compose bundle; this adds its network fetcher, for posters.
            implementation(libs.bundles.coil)
            // Plays what a plugin resolves. Loads the user's installed libVLC; bundles no VLC.
            implementation(libs.vlcj)
            // KMP-25: subtitles. The parser and the types it returns; the fetch and the drawing are here.
            implementation(project(":media:subtitle"))
            implementation(project(":core:designsystem"))
            implementation(project(":core:strings"))
            implementation(project(":core:data"))
            implementation(project(":library"))
            // KMP-52: the Mac download service: the engine and its file. `:feature:downloads` is above (KMP-54).
            implementation(project(":media:download"))
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// Where HomeScreenTest, DownloadsScreenTest and DetailScreenTest write their PNGs, when asked with -Pcloudstream.<screen>.renders=<directory>.
tasks.withType<Test> {
    providers.gradleProperty("cloudstream.home.renders").orNull?.let { systemProperty("cloudstream.home.renders", it) }
    providers.gradleProperty("cloudstream.downloads.renders").orNull?.let { systemProperty("cloudstream.downloads.renders", it) }
    providers.gradleProperty("cloudstream.detail.renders").orNull?.let { systemProperty("cloudstream.detail.renders", it) }
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
            // jlink keeps only what is listed, and nothing fails until a user hits the gap at runtime.
            // jdk.zipfs: dex2jar writes its jar through it ("cant find zipfs support" without it).
            // jdk.crypto.ec: TLS to anything offering only EC ciphers. java.naming: JNDI DNS, which
            // the JDK's own resolver paths and okhttp's logging probe. jdk.unsupported: atomicfu.
            // jdk.charsets: the legacy encodings of subtitle files, windows-1256 and Shift_JIS among them.
            // java.base alone would silently read those as windows-1252 (`HttpSubtitleLoader`).
            modules("jdk.zipfs", "jdk.crypto.ec", "java.naming", "jdk.unsupported", "java.net.http", "java.sql", "jdk.charsets")
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