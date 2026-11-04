// https://developer.android.com/build#settings-file
pluginManagement {
    // Convention plugins (cloudstream.kmp.library / cloudstream.kmp.compose).
    // Must be the first statement in this block.
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        mavenLocal()
        maven("https://jitpack.io")
    }
}

rootProject.name = "CloudStream"
include(":app", ":library", ":docs", ":desktopApp")
include(":media:model", ":media:subtitle")
include(":feature:extensions", ":feature:extensions:sandbox", ":feature:search", ":feature:detail", ":feature:home", ":feature:library")
include(":core:theme", ":core:designsystem", ":core:ui", ":core:mihon", ":core:common", ":core:network", ":core:data", ":core:devicetest", ":core:http", ":core:model", ":core:screenshot", ":core:settings", ":core:storage", ":core:strings", ":core:strings:fixtures")
