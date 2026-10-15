/**
 * `:feature:search` — the search vertical's state machine and contracts (plan row 18, vertical 1).
 *
 * ## No screens yet, and nothing in `:app` is wired to it
 *
 * Phase 7's recipe is five steps: state machine, screens, glue Fragment, repoint one nav node, then
 * delete the old fragment a release later. This module is **step 1 only**. `:app`'s
 * `SearchFragment` and `SearchViewModel` are untouched and still the live path.
 *
 * ## Not extension API
 *
 * Package `com.lagradost.cloudstream4.feature.search`, no `com/lagradost/cloudstream3` directory, so
 * `:app:makeJar` never merges it (plan §4.2: `:feature:*` is not merged, old classes stay in `:app`).
 * No sentinel, and `app/api/classes-jar.api` does not move. `:library`'s `SearchResponse` is
 * published and stays exactly where it is.
 *
 * ## Why this does **not** need Track L
 *
 * Plan row 18 carries the note *"ViewModel needs Track L for iOS (rule 4)"*, because the old
 * `SearchViewModel` consumes `SearchResponse` from `:library`. A `commonMain` edge to `:library`
 * would force this module into `IOS_CANARY_EXEMPT`, and that list "may only shrink"
 * (`cloudstream.kmp.library`'s KDoc).
 *
 * `SearchResultItem` restates the fields the UI reads, and `SearchSource` is the seam `:app`
 * implements over `APIRepository` — so **there is no `:library` edge, no exemption, and no Track L
 * dependency**. Mapping `SearchResponse` happens in `:app`, beside the types it belongs to. This is
 * the third time the same move has paid: `KMP-05` chose a source set for it, `KMP-11` chose a field
 * type, and here it removes a documented blocker outright.
 *
 * ## Dependencies, all of them already in the canary
 *
 * `:core:ui` for the MVI containers and the debounce / single-active-query helpers, and
 * `:core:designsystem` for `UiState`. Both are Compose modules, hence `cloudstream.kmp.compose`, and
 * both take part in the iOS canary — so this module joins it too rather than being exempted from it.
 */
plugins {
    id("cloudstream.kmp.compose")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:ui"))
            implementation(project(":core:designsystem"))
        }

        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
