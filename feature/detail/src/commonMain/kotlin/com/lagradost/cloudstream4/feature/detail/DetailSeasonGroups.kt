package com.lagradost.cloudstream4.feature.detail

import androidx.compose.runtime.Immutable

/** The provider's season integer for specials, by TMDB's convention and every provider that follows it. */
internal const val SPECIALS_SEASON = 0

/**
 * One chip in board 7i's season row, and the episodes that chip shows.
 *
 * [season] is the provider's own integer, [SPECIALS_SEASON] for specials, and **null** for episodes the
 * provider gave no season at all. The label is not here: this module holds no words (`I18N-08`), so the
 * screen turns a season into `Season 3`, `Specials` or `Other`.
 */
@Immutable
internal data class DetailSeasonGroup(
    val season: Int?,
    val episodes: List<DetailEpisode>,
)

/**
 * The episode list split into board 7i's chips, in the order the chips are drawn.
 *
 * Numbered seasons ascending, then **Specials**, then **Other** — the owner's ruling (2026-11-07), whose
 * point is that *no episode is hidden*. Specials is last among the numbers even though `0` sorts first,
 * because a specials season is not season zero of the story.
 *
 * ## Why this reads [DetailEpisode.season] and not [TitleDetail.seasons]
 *
 * `seasons` is **derived from this same list** by `toSeasons()`, which is
 * `mapNotNull { it.season }.groupingBy { it }.eachCount()` — so it **drops every episode whose season is
 * null**. A chip row built from `seasons` would have no chip those episodes belong to and they would
 * vanish from the page, which is the one outcome the Other chip exists to prevent. The counts can also
 * disagree: `seasons` sums to less than `episodes.size` exactly when a provider numbered nothing.
 *
 * Returns one group for a title with one season, and none at all for a title with no episodes.
 * [chipsVisible] is what turns "one group" into "draw no chips".
 */
internal fun List<DetailEpisode>.seasonGroups(): List<DetailSeasonGroup> {
    if (isEmpty()) return emptyList()
    val bySeason = groupBy { it.season }
    val order = buildList<Int?> {
        addAll(bySeason.keys.filterNotNull().filter { it != SPECIALS_SEASON }.sorted())
        if (bySeason.containsKey(SPECIALS_SEASON)) add(SPECIALS_SEASON)
        if (bySeason.containsKey(null)) add(null)
    }
    return order.map { season -> DetailSeasonGroup(season, bySeason.getValue(season)) }
}

/**
 * Whether board 7i's chip row is drawn at all.
 *
 * One group is not a selector, it is a label: a lone chip filters nothing and would add a focus stop that
 * does nothing, which 7i's own focus note makes safe to omit — it sends UP from the chips to the action
 * row, and with no chip row UP from the first episode lands on exactly that. The page stays legible and
 * the focus graph keeps its shape.
 */
internal fun List<DetailSeasonGroup>.chipsVisible(): Boolean = size >= MIN_GROUPS_FOR_CHIPS

private const val MIN_GROUPS_FOR_CHIPS = 2
