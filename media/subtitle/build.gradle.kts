/**
 * `:media:subtitle`, the commonMain half (migration plan §4.3, Phase 6).
 *
 * Layer 2. **New code, not a move:** `:app`'s `CustomSubripParser`, `CustomSubtitleDecoderFactory`
 * and `PlayerSubtitleHelper` are untouched and still the live path. Moving them touches
 * `classes.jar` and is a later step, so no class in `app/api/classes-jar.api` is affected and the
 * `$stable` trap (`KMP-09`) does not apply.
 *
 * ## Why it depends on `:media:model`
 *
 * `SubtitleCue` and `CaptionStyle` already live there (`KMP-07`), so this module takes them rather
 * than declaring a third cue type. `:media:model` depends on nothing and is canary-on, so this
 * module stays out of `IOS_CANARY_EXEMPT` too. Plan §4.1 rule 2 says "lower layers only" and draws
 * both modules at layer 2; see `docs/tasks/kmp.md` `KMP-21`.
 *
 * ## What may go in here
 *
 * Pure Kotlin, and everything a subtitle file reaches is untrusted: bounded by `SubtitleLimits`,
 * never throwing on input, no `Regex`. The platform half (Media3 decoder factory, spans,
 * `SubtitleView` styling) stays in `:app` for now.
 *
 *     ./gradlew :media:subtitle:iosCanary -Pcloudstream.iosCanary=true
 *     ./gradlew :media:subtitle:iosSimulatorTest -Pcloudstream.iosTest=true
 */
plugins {
    id("cloudstream.kmp.library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // `api`: SubtitleCue is in SubtitleParseResult's and SubtitleTimeline's public signatures.
            api(project(":media:model"))
        }

        // `commonTest`, so the iOS gate (INF-04) runs the adversarial suite on Kotlin/Native as well.
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
