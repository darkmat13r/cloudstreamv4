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
 * Every episode in the order a viewer goes through them (`KMP-94`'s Up next): the seasons in [seasonGroups]' order,
 * numbered ones ascending, then Specials, then Other, and each season by its episode number, an unnumbered one last,
 * as [firstEpisodeToPlay] reads them. Ties keep the provider's order, so two episodes with one number stay as listed.
 */
fun List<DetailEpisode>.playOrder(): List<DetailEpisode> =
    seasonGroups().flatMap { group -> group.episodes.sortedWith(compareBy(nullsLast()) { it.episode }) }

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

/**
 * The index of the chip a page shows.
 *
 * - The one the viewer [chosen], when they have chosen one.
 * - Otherwise the group holding the episode the primary action plays, [primaryData], so the page opens where Resume
 *   or Play points: Resume at S3E5 opens Season 3, and that row is the one marked.
 * - Otherwise, when the primary plays nothing in the list, the first group.
 *
 * Always clamped to the groups there are, because a re-read after `Reappeared` can return fewer than the chip last
 * chosen. With no groups at all — a film, or a series whose provider returned no episodes — it is
 * [FIRST_SEASON_GROUP] without clamping, because `coerceIn(0, -1)` throws on the empty range, which once crashed
 * every film's page.
 */
internal fun List<DetailSeasonGroup>.shownSeasonIndex(chosen: Int?, primaryData: String?): Int {
    if (isEmpty()) return FIRST_SEASON_GROUP
    val opening = primaryData
        ?.let { data -> indexOfFirst { group -> group.episodes.any { it.data == data } } }
        ?.takeIf { it >= FIRST_SEASON_GROUP }
    return (chosen ?: opening ?: FIRST_SEASON_GROUP).coerceIn(FIRST_SEASON_GROUP, lastIndex)
}

/**
 * Where an unstarted show starts: the first episode of the lowest numbered season above [SPECIALS_SEASON], so S1E1 and
 * not a special a provider happened to list first (the owner, 2026-11-08, `KMP-92`). "First" is the lowest episode
 * number in that season, with unnumbered ones after, and the list's own order between equals.
 *
 * With no numbered season at all, it is the provider's first episode that is not a special, so an anime with only
 * unnumbered episodes and a special listed first still starts on its first episode. With only specials, it is the
 * first of them. Null for an empty list.
 */
internal fun List<DetailEpisode>.firstEpisodeToPlay(): DetailEpisode? {
    val season = mapNotNull { it.season }.filter { it > SPECIALS_SEASON }.minOrNull()
        ?: return firstOrNull { it.season != SPECIALS_SEASON } ?: firstOrNull()
    return filter { it.season == season }.sortedWith(compareBy(nullsLast()) { it.episode }).first()
}
