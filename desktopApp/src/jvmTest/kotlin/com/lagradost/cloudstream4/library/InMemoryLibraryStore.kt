package com.lagradost.cloudstream4.library

import com.lagradost.cloudstream4.feature.library.LibraryBucket
import com.lagradost.cloudstream4.feature.library.LibraryStore
import com.lagradost.cloudstream4.feature.library.LibraryTitle
import com.lagradost.cloudstream4.feature.library.LibraryTitleId
import com.lagradost.cloudstream4.feature.library.WatchPosition

/**
 * A library store in memory, for tests that drive the window.
 *
 * It exists so that **no test reads or writes `~/.cloudstream4`**. `DesktopApp`'s default store is the
 * real one, and a test that let it be constructed would read the person running the test's own library
 * — which passes on one machine and not another, and which is somebody's data.
 */
internal open class InMemoryLibraryStore(
    titles: List<LibraryTitle> = emptyList(),
    positions: List<WatchPosition> = emptyList(),
) : LibraryStore {
    val titles = titles.toMutableList()
    val positions = positions.toMutableList()

    open override suspend fun titles(): List<LibraryTitle> = titles.toList()

    open override suspend fun remember(title: LibraryTitle) {
        val existing = titles.firstOrNull { it.id == title.id }
        titles.removeAll { it.id == title.id }
        titles += title.copy(bucket = existing?.bucket ?: title.bucket, keptAt = existing?.keptAt ?: title.keptAt)
    }

    open override suspend fun setBucket(id: LibraryTitleId, bucket: LibraryBucket?) {
        titles.replaceAll { if (it.id == id) it.copy(bucket = bucket) else it }
    }

    open override suspend fun positions(): List<WatchPosition> = positions.toList()

    open override suspend fun record(position: WatchPosition) {
        positions.removeAll { it.id == position.id && it.episodeData == position.episodeData }
        positions += position
    }

    open override suspend fun clearPositions(id: LibraryTitleId) {
        positions.removeAll { it.id == id }
    }
}
