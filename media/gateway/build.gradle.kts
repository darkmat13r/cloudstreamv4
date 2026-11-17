/**
 * `:media:gateway` — the media gateway's url scheme and HLS rewriter (`BUG-114`), shared by the Mac and the iPad.
 *
 * `KMP-113` built them for the Mac, in `:desktopApp`, on `java.net.URI`. The iPad needs the same rules: one audited copy
 * of what decides a url's role, what a playlist may name and what is refused. So the policy is in `commonMain`, and only
 * the url mechanics are per platform (`UriOps`):
 * - **JVM and Android** (`jvmCommonMain`): `java.net.URI` and `java.net.IDN`, exactly as the Mac called them. Its tests
 *   moved here unchanged, and `MacIdenticalParityTest` holds the moved policy, with `strict` off, to a verbatim copy of
 *   the original over generated input: that is what shows the Mac's behaviour is unchanged.
 * - **iOS** (`iosMain`): [PortableUri], a port of the `java.net.URI` parser and resolver for what the gateway uses, in
 *   `commonMain` so the JVM can test it against the original. A host that is not ASCII is refused, not punycoded.
 *
 * `strict` (the iPad's) refuses a whole playlist when anything in it could name a url and was not rewritten. The Mac runs
 * with it off: its media host's sandbox refuses anything the gateway does not route, which the iPad has no equivalent of.
 *
 * Not extension API: package `com.lagradost.cloudstream4.player.gateway`, never in `classes.jar`.
 */
plugins {
    id("cloudstream.kmp.library")
}

kotlin {
    applyDefaultHierarchyTemplate()

    @OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)
    abiValidation {}

    sourceSets {
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
        }
        androidMain { dependsOn(jvmCommonMain) }
        jvmMain { dependsOn(jvmCommonMain) }

        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        // NsUrlDifferentialTest compares the gateway's hosts with Ktor's `Url`, as the iPad's Ktor Darwin client reads them.
        matching { it.name == "iosTest" }.configureEach {
            dependencies { implementation(libs.ktor.http) }
        }
        jvmTest.dependencies {
            // GatewayUrlTest (moved from :desktopApp unchanged) reads SandboxPolicy.MAX_URL_CHARS, and
            // GatewayLimitsTest holds the copy here to it. Test-only.
            implementation(project(":feature:extensions:sandbox"))
        }
    }
}
