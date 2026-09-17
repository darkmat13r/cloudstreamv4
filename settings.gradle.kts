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
include(":app", ":shared", ":library", ":docs", ":desktopApp")
include(":core:theme", ":core:designsystem", ":core:common", ":core:network", ":core:data", ":core:http", ":core:screenshot")
