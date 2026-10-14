/**
 * Device-backed app settings and the Android Compose host that applies them — what was left in
 * `:shared` after `:core:ui` (`KMP-06c`) and `:core:mihon` (`KMP-06b`) were carved out of it, moved
 * here verbatim so `:shared` can be deleted (`KMP-06d`).
 *
 * Five files, in two groups that share one reason to live together: they are the only first-party
 * code that sits **above** the vendored Mihon tree.
 *
 *  - `DevicePreferenceStore` ×3 — `AppSettings` and its 8 `*Preferences` classes, built on Mihon's
 *    `PreferenceStore` and exposing Mihon's `PreferenceData<T>`.
 *  - `compose/ComposeFragment.kt`, `compose/Layout.kt` — the `ComposeView` host for the migrated
 *    settings screens, and the `DeviceLayout` flags it provides.
 *
 * ## Why this is not `:core:ui`
 *
 * `:core:mihon` is `api(project(":core:ui"))`, so an import of `com.mihon` from `:core:ui` is a
 * module cycle, and `ComposeFragment.kt` has three such edges that cannot be cut there:
 *
 *  - `com.mihon.presentation.LocalBackPress` — `SearchableSettings` reads *that* exact
 *    `staticCompositionLocalOf`. A re-declaration in `:core:ui` would provide a different local and
 *    the settings back handler would silently stop working.
 *  - `com.mihon.presentation.settings.collectAsState` — an extension on Mihon's `PreferenceData`.
 *  - `rememberAppSettings()`, whose `AppSettings` takes Mihon's `PreferenceStore`.
 *
 * `Layout.kt` imports no `com.mihon` and could live in `:core:ui/androidMain` on its own, but
 * `ComposeFragment` is the only caller of its `internal` `DeviceLayout.Companion.layoutToFlag`.
 * Splitting the pair would mean widening that to public for no gain, so they stay together.
 *
 * ## Why not `:core:storage`, which `docs/plans/kmp-migration.md` §7 Phase 5 names
 *
 * That line predates `:core:mihon`. `:core:storage` declares its own, deliberately
 * identical-shaped `PreferenceStore`/`PreferenceData`, and porting onto them retypes 62
 * `preference = settings.…` arguments across 6 `:app` settings screens, because the receiving
 * fields are declared `PreferenceData<…>` in vendored Mihon code. It would also give
 * `:core:storage` a `:library` edge, contradicting the layer-0 purity its own KDoc states, and the
 * Compose compiler, which `cloudstream.kmp.library` does not carry. `docs/tasks/kmp.md` `KMP-06d`
 * records all of it; consolidating the two `PreferenceStore`s is still open.
 *
 * ## Dependencies
 *
 * `api` on both `:core:ui` and `:core:mihon`, exactly as `:shared` had them: `Screen` is in
 * `createComposeView`'s signature and `PreferenceData` is in `AppSettings`'s, and `:app` reaches
 * both layers through this one edge, so it needs no build-file change beyond the module rename.
 *
 * Two of `:shared`'s dependencies are **not** carried over, because no file here uses them:
 * `kotlinx-collections-immutable` (the Mihon tree's, now `:core:mihon`'s) and `preference-ktx`
 * (`DevicePreferenceStore.android.kt` reaches `SharedPreferences` through Mihon's
 * `AndroidPreferenceStore`, which declares it itself). `libs.bundles.compose` is not declared
 * either: `cloudstream.kmp.compose` supplies it as `api`.
 */
plugins {
    id("cloudstream.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api: `Screen` is the receiver of createComposeView, and the theme is applied here.
            api(project(":core:ui"))
            // api: `AppSettings` exposes com.mihon.common.preference.PreferenceData in its public
            // surface, and :app passes those values straight into Mihon's settings widgets.
            api(project(":core:mihon"))
            // AllLanguagesName, DubStatus, SearchQuality, TvType are preference defaults and value
            // types. This commonMain edge is why the module is in IOS_CANARY_EXEMPT; see
            // cloudstream.kmp.library.
            implementation(project(":library"))
        }

        androidMain.dependencies {
            // LocalOnBackPressedDispatcherOwner, which ComposeFragment provides to LocalBackPress.
            implementation(libs.activity.compose)
        }
    }
}
