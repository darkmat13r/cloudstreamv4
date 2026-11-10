import java.util.Properties
import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    // KMP-52: the downloads file is kotlinx.serialization JSON.
    alias(libs.plugins.kotlin.serialization)
}

// KMP-95d, 95e: the services' client ids, as `:app` reads them: an env var, else a key in the untracked local.properties.
// Never in the repo and never printed; a build without one shows that service as "Not available here". Only an id of
// plain letters and digits is written into the generated source, so nothing can break out of the literal.
val localProperties: Provider<Properties> =
    providers.fileContents(rootProject.layout.projectDirectory.file("local.properties")).asText
        .map { text -> Properties().apply { load(text.reader()) } }
fun clientId(env: String, key: String): Provider<String> = providers.environmentVariable(env)
    .orElse(localProperties.map { it.getProperty(key).orEmpty() })
    .orElse("")
    .map { it.trim().takeIf { value -> value.matches(Regex("[A-Za-z0-9]{1,128}")) }.orEmpty() }
val clientIds: Map<String, Provider<String>> = mapOf(
    "simklClientId" to clientId("SIMKL_CLIENT_ID", "simkl.id"),
    "malClientId" to clientId("MAL_KEY", "mal.key"),
    "aniListClientId" to clientId("ANILIST_KEY", "anilist.key"),
)

// KMP-95f: signing, from untracked values only: an env var, else `~/.gradle/gradle.properties`, else local.properties.
// The Team ID is not secret, but this repo is public and it ties to the owner's account, so it is never committed,
// printed, or written into a source: only into the build's entitlements.
//
// A build is signed only when it is asked to be, with `-Pcloudstream.sign=true` on a packaging run, and both values are
// there (the PM, 2026-11-09). So `run` and every test stay unsigned on a Mac that has the values: a signed-mode app in an
// unsigned process would only fail closed. The flag is refused on anything but packaging, and is read here, at build
// time, into a constant: nothing at runtime can change which stores a build has.
fun signingValue(key: String): Provider<String> = providers.environmentVariable(key)
    .orElse(providers.gradleProperty(key))
    .orElse(localProperties.map { it.getProperty(key).orEmpty() })
    .orElse("")
    .map { it.trim() }
val signRequested: Boolean = providers.gradleProperty("cloudstream.sign").orNull == "true"
val teamId: String = if (signRequested) signingValue("CLOUDSTREAM_TEAM_ID").get() else ""
val signIdentity: String = if (signRequested) signingValue("CLOUDSTREAM_SIGN_IDENTITY").get() else ""
// The restricted entitlements (the application identifier, the team, the access group) are authorised by a provisioning
// profile (TN3137): an untracked path, embedded only when signing. Never committed, and its path is never printed.
val provisioningProfilePath: String = if (signRequested) signingValue("CLOUDSTREAM_PROVISIONING_PROFILE").get() else ""
val signedBuild: Boolean = signRequested
// Compose's `macOS.signing` takes a Developer ID or Mac App Store identity only: any other name gets "Developer ID
// Application: " put before it (`ValidatedMacOSSigningSettings.fullDeveloperID`, Compose 1.12), and then no certificate
// matches. An Apple Development identity is the right one for the owner's own Mac (the PM, 2026-11-10), so that build is
// signed by `signMacApp` below instead.
val developmentIdentity: Boolean = signedBuild && signIdentity.startsWith("Apple Development:")
if (signRequested) {
    val missing = listOf(
        "CLOUDSTREAM_TEAM_ID" to teamId,
        "CLOUDSTREAM_SIGN_IDENTITY" to signIdentity,
        "CLOUDSTREAM_PROVISIONING_PROFILE" to provisioningProfilePath,
    ).filter { it.second.isEmpty() }
    if (missing.isNotEmpty()) {
        throw GradleException("-Pcloudstream.sign=true needs CLOUDSTREAM_TEAM_ID, CLOUDSTREAM_SIGN_IDENTITY and CLOUDSTREAM_PROVISIONING_PROFILE; missing: ${missing.joinToString { it.first }}.")
    }
    if (!teamId.matches(Regex("[A-Z0-9]{10}"))) {
        throw GradleException("CLOUDSTREAM_TEAM_ID is not a Team ID: ten capital letters and digits.")
    }
    if (!File(provisioningProfilePath).isFile) {
        throw GradleException("CLOUDSTREAM_PROVISIONING_PROFILE does not name a file.")
    }
    // A development profile lists the devices it runs on (their UDIDs), so it may sign a local build only: a release
    // or notarised build refuses it (the PM, 2026-11-09). The profile's plist is plain text inside its CMS envelope.
    val listsDevices = File(provisioningProfilePath).readBytes().decodeToString().contains("<key>ProvisionedDevices</key>")
    gradle.taskGraph.whenReady {
        val release = allTasks.map { it.path }.filter { it == ":desktopApp:createReleaseDistributable" || it.startsWith(":desktopApp:notarize") }
        if (listsDevices && release.isNotEmpty()) {
            throw GradleException(
                "A release or notarised build cannot use a development provisioning profile: it lists devices. " +
                    "Use a Developer ID profile, or package for a local run only.",
            )
        }
    }
    // A development identity signs a local `createDistributable` only (the PM, 2026-11-10): `signMacApp` signs that one
    // image after it is made, so a dmg or a pkg packaged from it would be packaged before it is signed.
    if (developmentIdentity) {
        val notLocal = gradle.startParameter.taskNames.filterNot { it.startsWith("-") }
            .filterNot { it == "createDistributable" || it == ":desktopApp:createDistributable" }
        if (notLocal.isNotEmpty()) {
            throw GradleException("An Apple Development identity signs createDistributable only; not: ${notLocal.joinToString()}.")
        }
    }
    // Only this module's packaging may ask for a signed build: a signed `run` or test is the trap this flag prevents.
    val packaging = Regex("(:desktopApp:)?(package|createDistributable|createReleaseDistributable|notarize|generateMacEntitlements|generateDesktopSecrets|bundleJnaDispatch|signMacApp)\\w*")
    val others = gradle.startParameter.taskNames.filterNot { it.startsWith("-") || packaging.matches(it) }
    if (others.isNotEmpty()) {
        throw GradleException("-Pcloudstream.sign=true is only for packaging and notarising; not for: ${others.joinToString()}.")
    }
}

val generateDesktopSecrets = tasks.register("generateDesktopSecrets") {
    val out = layout.buildDirectory.dir("generated/desktopSecrets/kotlin")
    // A local copy: the configuration cache cannot hold a reference to the script itself.
    val ids: Map<String, Provider<String>> = clientIds.toMap()
    ids.forEach { (name, id) ->
        inputs.property("${name}Present", id.map { it.isNotEmpty() })
        inputs.property("${name}Hash", id.map { it.hashCode() })
    }
    // KMP-95f: whether this build is signed, as a constant: nothing at runtime can make a signed build unsigned.
    val signed = signedBuild
    inputs.property("signedBuild", signed)
    outputs.dir(out)
    doLast {
        val file = out.get().file("com/lagradost/cloudstream4/account/DesktopSecrets.kt").asFile
        file.parentFile.mkdirs()
        val fields = ids.entries.joinToString("") { (name, id) ->
            val value = id.get()
            "    /** A client id, or null when the build had none. */\n" +
                "    val $name: String? = ${if (value.isEmpty()) "null" else "\"$value\""}\n"
        }
        val signedField = "    /** Whether this build is signed, so its PINs and sign-ins are in the Keychain (`KMP-95f`). */\n" +
            "    const val signedBuild: Boolean = $signed\n"
        file.writeText(
            "package com.lagradost.cloudstream4.account\n\n" +
                "/** Generated by `generateDesktopSecrets` from the build's environment, never from the repo (`KMP-95d`). */\n" +
                "internal object DesktopSecrets {\n" + fields + signedField + "}\n"
        )
    }
}

// KMP-95f: the entitlements with the Team ID in, under build/ only. Only a signed build makes or uses them.
val generateMacEntitlements = tasks.register("generateMacEntitlements") {
    val template = layout.projectDirectory.file("macos/entitlements.plist")
    val out = layout.buildDirectory.file("generated/macos/entitlements.plist")
    val id = teamId
    inputs.file(template)
    inputs.property("teamIdHash", id.hashCode())
    outputs.file(out)
    doLast {
        if (!id.matches(Regex("[A-Z0-9]{10}"))) throw GradleException("CLOUDSTREAM_TEAM_ID is missing: a signed build needs it.")
        val text = template.asFile.readText()
        // The application identifier, the team, and the access group.
        check(Regex("__TEAM_ID__").findAll(text).count() == 3) { "macos/entitlements.plist has lost a placeholder." }
        out.get().asFile.apply { parentFile.mkdirs() }.writeText(text.replace("__TEAM_ID__", id))
    }
}

// KMP-95f, the reviewer of #308: a signed app loads JNA's native part from inside its own signed bundle, never from
// a cache a user can write to. JNA's `libjnidispatch` for each Mac arch is copied from the one JNA jar into the app's
// resources (Compose puts `macos-arm64` or `macos-x64` in `Contents/app/resources`), and the app runs with
// `jna.nounpack=true` and `jna.boot.library.path` there. Registered only for a signed build.
val bundleJnaDispatch = if (!signedBuild) null else tasks.register<Sync>("bundleJnaDispatch") {
    val jar = configurations.named("jvmRuntimeClasspath").map { classpath ->
        classpath.filter { it.name == "jna-${libs.versions.jna.get()}.jar" }.singleFile
    }
    from(jar.map { zipTree(it) }) {
        include("com/sun/jna/darwin-aarch64/libjnidispatch.jnilib", "com/sun/jna/darwin-x86-64/libjnidispatch.jnilib")
        eachFile {
            val arch = if (relativePath.segments.contains("darwin-aarch64")) "macos-arm64" else "macos-x64"
            relativePath = RelativePath(true, arch, "libjnidispatch.dylib")
        }
        includeEmptyDirs = false
    }
    into(layout.buildDirectory.dir("generated/jnaDispatch"))
}

// KMP-95f: a build signed with an Apple Development identity, signed here rather than by Compose (see
// `developmentIdentity`). It runs after `createDistributable`, on the image it made:
// - the profile goes in as `Contents/embedded.provisionprofile`, which authorises the restricted entitlements;
// - every Mach-O file is signed, the deepest first: the runtime's dylibs and launchers, JNA's `libjnidispatch`;
// - then the runtime's bundle, then the app with the entitlements.
// With the hardened runtime, which the entitlements' JIT, executable-memory and library-validation keys are for. The
// Keychain round trip was measured both ways on a signed build (2026-11-10), and passed both ways, so the stricter one
// stays. The identity, the Team ID and the profile's path are passed to codesign only, never printed: a failure names
// the file's place in the app and nothing else.
val signMacApp = if (!developmentIdentity) null else tasks.register("signMacApp") {
    val app = layout.buildDirectory.file("compose/binaries/main/app/CloudStream.app")
    val entitlements = generateMacEntitlements.map { it.outputs.files.singleFile }
    val profile = File(provisioningProfilePath)
    val identity = signIdentity
    dependsOn(generateMacEntitlements)
    doLast {
        val root = app.get().asFile
        check(root.isDirectory) { "signMacApp: createDistributable made no app." }
        profile.copyTo(root.resolve("Contents/embedded.provisionprofile"), overwrite = true)
        fun codesign(target: File, vararg extra: String) {
            val process = ProcessBuilder(listOf("codesign", "--force", "--timestamp=none", "--options", "runtime", "--sign", identity) + extra + target.path)
                .redirectErrorStream(true)
                .start()
            process.inputStream.readBytes()
            if (process.waitFor() != 0) throw GradleException("codesign refused ${target.relativeTo(root)}.")
        }
        fun isMachO(file: File): Boolean {
            if (!file.isFile || file.length() < 8) return false
            val head = file.inputStream().use { it.readNBytes(8) }
            val magic = head.take(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
            val second = head.drop(4).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
            // 64-bit and 32-bit, either byte order; a universal binary's `cafebabe` has a small arch count after it,
            // where a class file has its version.
            return magic == 0xFEEDFACFL || magic == 0xCFFAEDFEL || magic == 0xFEEDFACEL || magic == 0xCEFAEDFEL ||
                (magic == 0xCAFEBABEL && second in 1L..20L)
        }
        val contents = root.resolve("Contents")
        val main = contents.resolve("MacOS").listFiles().orEmpty().toSet()
        contents.walkBottomUp()
            .filter { it !in main && isMachO(it) }
            .sortedByDescending { it.path.count { c -> c == File.separatorChar } }
            .forEach { codesign(it) }
        contents.resolve("runtime").takeIf { it.resolve("Contents/Info.plist").isFile }?.let { codesign(it) }
        main.filter(::isMachO).forEach { codesign(it) }
        codesign(root, "--entitlements", entitlements.get().path)
        val verify = ProcessBuilder("codesign", "--verify", "--strict", root.path).redirectErrorStream(true).start()
        verify.inputStream.readBytes()
        if (verify.waitFor() != 0) throw GradleException("signMacApp: the signed app does not verify.")
    }
}
signMacApp?.let { sign -> tasks.matching { it.name == "createDistributable" }.configureEach { finalizedBy(sign) } }

kotlin {
    jvm {}
    sourceSets {
        jvmMain {
            kotlin.srcDir(generateDesktopSecrets)
        }
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
            // The shared HUD model (`HudSkip`): the Mac draws Skip from the same type TV does (`KMP-87`).
            implementation(project(":feature:player"))
            implementation(project(":feature:home"))
            // KMP-26: bookmarks, watch progress and resume. The store is this module's, under ~/.cloudstream4.
            implementation(project(":feature:library"))
            // KMP-54: the Mac downloads page. Its source is KMP-52's, under com.lagradost.cloudstream4.downloads.
            implementation(project(":feature:downloads"))
            // KMP-90: the Settings window, and the preferences the player and the downloads engine read.
            implementation(project(":feature:settings"))
            // KMP-95: who is watching (DS-25). The picker and the sidebar's profile button are this module's.
            implementation(project(":feature:account"))
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
            // The string form, as `compose.desktop.currentOs` above: here an exclude needs it. The version is still the catalog's.
            implementation(libs.vlcj.get().let { "${it.module}:${it.versionConstraint.requiredVersion}" }) {
                // BUG-73: vlcj-natives brings JNA 5.16.0 as `jna-jpms` and `jna-platform-jpms`, the same `com.sun.jna`
                // classes as the `jna` and `jna-platform` 5.19.1 that FileKit brings. Gradle sees two modules, so both
                // shipped, and which one loaded was the classpath's order: 5.16.0 under `run`, 5.19.1 in the packaged app.
                // Excluded, there is one JNA, 5.19.1, the version the owner approved for the Keychain (KMP-95f).
                exclude(group = "net.java.dev.jna", module = "jna-jpms")
                exclude(group = "net.java.dev.jna", module = "jna-platform-jpms")
            }
            // KMP-25: subtitles. The parser and the types it returns; the fetch and the drawing are here.
            implementation(project(":media:subtitle"))
            implementation(project(":core:designsystem"))
            implementation(project(":core:strings"))
            implementation(project(":core:data"))
            implementation(project(":library"))
            // KMP-52: the Mac download service: the engine and its file. `:feature:downloads` is above (KMP-54).
            implementation(project(":media:download"))
            implementation(libs.kotlinx.serialization.json)
            // KMP-95d: A11's QR of Simkl's page, as `:app` draws it for TV. The catalog's version, approved for the Mac by
            // the owner (2026-11-09).
            implementation(libs.qrcode.kotlin)
            // KMP-74: socketpair, sendmsg and recvmsg, so a downloaded file reaches the sandboxed media host as one open,
            // read-only descriptor and never as a path. Declared, not taken from vlcj or FileKit, and pinned.
            // KMP-95f: the Keychain too, through Security.framework (`MacKeychain`), approved by the owner for this
            // (2026-11-08) at the same 5.19.1.
            implementation(libs.jna)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

// Where HomeScreenTest, DownloadsScreenTest, SearchScreenTest, LibraryScreenTest, DetailScreenTest, SettingsWindowTest, DesktopShellTest and ExtensionsPaneRenders write their PNGs, when asked with -Pcloudstream.<screen>.renders=<directory>.
tasks.withType<Test> {
    providers.gradleProperty("cloudstream.home.renders").orNull?.let { systemProperty("cloudstream.home.renders", it) }
    providers.gradleProperty("cloudstream.downloads.renders").orNull?.let { systemProperty("cloudstream.downloads.renders", it) }
    providers.gradleProperty("cloudstream.search.renders").orNull?.let { systemProperty("cloudstream.search.renders", it) }
    providers.gradleProperty("cloudstream.library.renders").orNull?.let { systemProperty("cloudstream.library.renders", it) }
    providers.gradleProperty("cloudstream.detail.renders").orNull?.let { systemProperty("cloudstream.detail.renders", it) }
    providers.gradleProperty("cloudstream.settings.renders").orNull?.let { systemProperty("cloudstream.settings.renders", it) }
    providers.gradleProperty("cloudstream.account.renders").orNull?.let { systemProperty("cloudstream.account.renders", it) }
    providers.gradleProperty("cloudstream.extensions.renders").orNull?.let { systemProperty("cloudstream.extensions.renders", it) }
    providers.gradleProperty("cloudstream.shell.renders").orNull?.let { systemProperty("cloudstream.shell.renders", it) }
}

// java.lang.System::load has been called by org.jetbrains.skiko.LibraryLoader in an unnamed module
tasks.withType<JavaExec> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

compose.desktop {
    application {
        mainClass = "com.lagradost.cloudstream4.MainKt"
        if (bundleJnaDispatch != null) {
            // `$APPDIR` is jpackage's `Contents/app`; the bundled `libjnidispatch` is in its `resources`.
            jvmArgs("-Djna.nounpack=true", "-Djna.boot.library.path=\$APPDIR/resources")
        }
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            bundleJnaDispatch?.let { task -> appResourcesRootDir.fileProvider(task.map { it.destinationDir }) }
            // jlink keeps only what is listed, and nothing fails until a user hits the gap at runtime.
            // jdk.zipfs: dex2jar writes its jar through it ("cant find zipfs support" without it).
            // jdk.crypto.ec: TLS to anything offering only EC ciphers. java.naming: JNDI DNS, which
            // the JDK's own resolver paths and okhttp's logging probe. jdk.unsupported: atomicfu.
            // jdk.charsets: the legacy encodings of subtitle files, windows-1256 and Shift_JIS among them.
            // java.base alone would silently read those as windows-1252 (`HttpSubtitleLoader`).
            modules("jdk.zipfs", "jdk.crypto.ec", "java.naming", "jdk.unsupported", "java.net.http", "java.sql", "jdk.charsets")
            packageName = "CloudStream"
            packageVersion = "1.0.0"

            val iconsRoot = project.file("src/desktop-icons")
            macOS {
                // DS-28: the app icon, Apple's grid (an 824 tile on 1024).
                iconFile.set(iconsRoot.resolve("icon-macos.icns"))
                // KMP-95f: the Keychain's items belong to this id's access group, and the items' service is the same id.
                bundleID = "com.lagradost.cloudstream4"
                // Signed only for `-Pcloudstream.sign=true` on a packaging run, with both untracked values; codesign is then
                // given the identity, and the entitlements with the Team ID in. Otherwise nothing here runs and the app keeps
                // 95c's and 95d's stores.
                // An Apple Development identity is signed by `signMacApp` instead: Compose cannot name one.
                if (signedBuild && !developmentIdentity) {
                    signing {
                        sign.set(true)
                        identity.set(signIdentity)
                    }
                    entitlementsFile.fileProvider(generateMacEntitlements.map { it.outputs.files.singleFile })
                    provisioningProfile.set(File(provisioningProfilePath))
                }
                // KMP-95e: the browser's sign-ins come back as cloudstreamapp://mallogin and anilistlogin (`AccountManager.APP_STRING`).
                // Only a packaged app has this; `BrowserRedirects` accepts only what a waiting sign-in asked for.
                infoPlist {
                    extraKeysRawXml = """
                        <key>CFBundleURLTypes</key>
                        <array>
                            <dict>
                                <key>CFBundleURLName</key>
                                <string>com.lagradost.cloudstream4.signin</string>
                                <key>CFBundleURLSchemes</key>
                                <array>
                                    <string>cloudstreamapp</string>
                                </array>
                            </dict>
                        </array>
                    """.trimIndent()
                }
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