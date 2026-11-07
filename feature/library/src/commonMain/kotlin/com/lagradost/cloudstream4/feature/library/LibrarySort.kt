package com.lagradost.cloudstream4.feature.library

import androidx.compose.runtime.Immutable

/**
 * What a list can be sorted by: `TV Library` 5d's four rows. The old screen's `ListSorting` is these four, each in two
 * directions, and its `Query`, which is no sort at all.
 */
enum class LibrarySortCriterion {
    /** When the title was last changed in the list: [LibraryTitle.keptAt]. */
    Updated,

    /** Its name, ignoring case. */
    Title,

    /** When it came out: [LibraryTitle.releaseYear]. */
    Released,

    /** The user's own rating: [LibraryTitle.ratingValue]. */
    Rating,
}

/**
 * One sort: a criterion and its direction. One choice for every source (`DEC-20`), kept by the host.
 *
 * @property descending newest, highest or Z first. A title is A to Z when this is false.
 */
@Immutable
data class LibrarySort(val criterion: LibrarySortCriterion, val descending: Boolean) {

    /** The same criterion the other way round: pressing the chosen row again (5d). */
    fun flipped(): LibrarySort = copy(descending = !descending)

    companion object {
        /**
         * Last updated, newest first: the order a list has without a choice, and the fallback for a source that cannot
         * sort by the one chosen. Every source can sort by it.
         */
        val Default = LibrarySort(LibrarySortCriterion.Updated, descending = true)

        /** [criterion] the way it is first offered: A to Z for a name, newest or highest first for the rest. */
        fun of(criterion: LibrarySortCriterion) =
            LibrarySort(criterion, descending = criterion != LibrarySortCriterion.Title)
    }
}

/**
 * What [sort] applies to [source]: the choice where the source can sort by it, else [LibrarySort.Default]. The choice
 * itself is kept, so a source that can sort by it shows it again (`DEC-20`: one choice for all, with a fallback).
 */
fun LibrarySort.appliedTo(source: LibrarySource?): LibrarySort =
    if (source == null || criterion in source.sorts) this else LibrarySort.Default
