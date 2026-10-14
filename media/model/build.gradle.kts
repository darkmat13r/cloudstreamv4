/**
 * `:media:model` — the data-only media contracts (`KMP-07`, migration plan §4.3).
 *
 * Layer 2. **New code, not a move:** nothing here comes from `:app` or `:library`, so no class in
 * `app/api/classes-jar.api` is affected and the `$stable` trap (`KMP-09`) does not apply.
 *
 * ## Why this module has no internal dependencies at all
 *
 * It is **iOS-canary on, unconditionally** — it is not in `IOS_CANARY_EXEMPT` and must never need
 * to be. Plan §4.1 rule 4 says a commonMain `:library` dependency forces an exemption, so this
 * module does not take one: every shape an extension supplies (`ExtractorLink`'s url, headers,
 * referer, quality) is restated here as plain data, and `:app` adapts between the two. That
 * restatement is the point of the module, not an accident of it — the contracts have to outlive
 * `:library`'s Android-bound declarations for an iOS player sibling to exist at all (§4.3).
 *
 * Plan §4.1 rule 5 also applies: `compose-runtime` at most, never Compose UI, never
 * `:core:strings`. Today it needs neither, so it declares neither.
 *
 * ## What may go in here
 *
 * Data and nothing else: no I/O, no coroutines in a signature, no platform type. `commonMain` must
 * compile for Kotlin/Native, so `java.*`, `android.*`, Jackson, OkHttp, `String.format` and
 * `synchronized` are all unavailable — and unlike `:core:model`, that is enforced by a build rather
 * than by reading, because this module really does have iOS targets under
 * `-Pcloudstream.iosCanary=true`.
 *
 *     ./gradlew :media:model:iosCanary -Pcloudstream.iosCanary=true
 */
plugins {
    id("cloudstream.kmp.library")
}

kotlin {
    sourceSets {
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
