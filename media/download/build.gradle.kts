/**
 * `:media:download`: the segmented byte engine (plan §4.3). New code, not a move, so no class in
 * `app/api/classes-jar.api` is affected and the `$stable` trap (`KMP-09`) does not apply.
 *
 * FileKit 0.17.0 means a consumer inherits stdlib 2.4.21, serialization 1.12.0 and a minSdk of 24.
 * `:app` moved to all three in `DEC-18`; `:library` deliberately did not.
 *
 * `jvmCommonMain` is declared here as `:core:http` does it.
 *
 * `androidDeviceTest` runs the file operations on a real API 24/25 device, where `java.nio.file` does
 * not exist and kotlinx-io's atomic move throws: see `FileKitStorage.atomicMove`.
 */
plugins {
    id("cloudstream.kmp.library")
    id("cloudstream.kmp.device-test")
}

kotlin {
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            // `api`: DownloadFailure, HttpClient, PlatformFile and RawSink are in public signatures.
            api(project(":media:model"))
            api(project(":core:http"))
            api(libs.filekit.core)
            api(libs.kotlinx.io.core)
            api(libs.kotlinx.coroutines.core)
        }

        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain { dependsOn(jvmCommonMain) }
        jvmMain { dependsOn(jvmCommonMain) }

        // `commonTest`, so the iOS gate (INF-04) runs the pure rules on Kotlin/Native as well.
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmTest.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
    }
}
