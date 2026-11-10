/**
 * `:core:http` — the first-party Ktor client factory (plan I2/I3, Phase 4 H-b).
 *
 * ## Not extension API
 *
 * Package `com.lagradost.cloudstream4.core.http`, no `com/lagradost/cloudstream3` directory: this
 * module is never merged into `:app:makeJar`'s `classes.jar`, so its API can change freely. It must
 * never depend on `:library`.
 *
 * ## Source sets and dependencies
 *
 * `cloudstream.kmp.library` has no `jvmCommonMain`, so it is declared here the way
 * `cloudstream.kmp.extension-api` does it. Not that plugin: it marks frozen API and adds the Compose
 * compiler.
 *
 * - `commonMain` — `api(ktor-client-core)`: `HttpClient` is in `HttpClientFactory`'s signatures.
 * - `jvmCommonMain` — `implementation(ktor-client-okhttp)`, deliberately not `api`: callers must not
 *   reach the `OkHttp` engine factory, whose engine a closed `HttpClient` shuts down (see
 *   `OkHttpClientFactory.kt`). `okhttp3.OkHttpClient` is in the public signature there; any caller
 *   already has OkHttp on its compile classpath, since it has to pass a client in.
 * - `iosMain` — `implementation(ktor-client-darwin)` (`KMP-112`), for the same reason. It exists only
 *   when an iOS gate declares iOS targets (`-Pcloudstream.iosCanary=true` or `iosTest=true`), so it
 *   is wired with `matching`, which does nothing when the source set is absent.
 */
plugins {
    id("cloudstream.kmp.library")
}

kotlin {
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            api(libs.ktor.client.core)
        }

        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(libs.ktor.client.okhttp)
            }
        }

        androidMain { dependsOn(jvmCommonMain) }
        jvmMain { dependsOn(jvmCommonMain) }

        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }

        matching { it.name == "iosMain" }.configureEach {
            dependencies { implementation(libs.ktor.client.darwin) }
        }
        matching { it.name == "iosTest" }.configureEach {
            dependencies { implementation(libs.kotlin.test) }
        }
    }
}
