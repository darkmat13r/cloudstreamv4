package com.lagradost.cloudstream4.core.designsystem.desktop

/**
 * How big the Mac draws posters: Settings' Poster size (`DS-24` ST11, `KMP-90e`). Each screen turns it into its own
 * smallest card width, so a bigger size means fewer, wider columns. [Medium] is every screen's width before the setting.
 *
 * Here, not in `:feature:settings`, because Home, Search and Library read it and none of them depends on Settings.
 */
enum class PosterSize {
    Small,
    Medium,
    Large,
}
