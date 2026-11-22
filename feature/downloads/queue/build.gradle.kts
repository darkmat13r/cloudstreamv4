/**
 * `:feature:downloads:queue` — the downloads queue the Mac and the iPad share (`KMP-123`).
 *
 * Moved from `:desktopApp` (`KMP-52`), where it was JVM only: a FIFO over `:media:download`'s engine, the
 * downloads file that survives a restart, and the data `:feature:downloads`' screens read. No UI.
 *
 * ## Source sets
 *
 * - `commonMain`: the queue, the downloads file's format, file and folder names. Nothing from `java.*`.
 * - `jvmCommonMain`: NFKC (`java.text.Normalizer`), URL parsing (`java.net.URI`) and an interruptible resolve:
 *   exactly what the Mac ran before the move, so its behaviour does not change.
 * - `jvmMain`: [JvmQueueDisk], the Mac's file operations, moved as they were (`java.nio.file`).
 * - `iosMain`: the same three over Foundation, and `IosQueueDisk` over POSIX.
 *
 * Android compiles this module (the convention plugin gives every module an Android target) and has no
 * `QueueDisk`: Android's downloads are `:app`'s and do not use it.
 *
 * ## Not extension API
 *
 * Package `com.lagradost.cloudstream4…`, so `:app:makeJar` never merges it, and nothing here reaches `:library`:
 * a link's type is this module's own [com.lagradost.cloudstream4.downloads.DesktopDownloadLink.directFile], which
 * the Mac maps from `ExtractorLinkType`. That is what keeps it in the iOS canary.
 */
plugins {
    id("cloudstream.kmp.library")
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // `api`: DownloadsContents, DownloadTitleKind and the series types are in this module's public signatures.
            api(project(":feature:downloads"))
            // `api`: DownloadOptions and HttpClient are in the queue's constructor.
            api(project(":media:download"))
            implementation(libs.kotlinx.serialization.json)
        }

        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain { dependsOn(jvmCommonMain) }
        jvmMain { dependsOn(jvmCommonMain) }

        // The pure rules and the iOS disk, so the iOS test gate runs them on Kotlin/Native.
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
