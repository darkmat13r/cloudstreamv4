package com.lagradost.cloudstream4.feature.settings

import com.lagradost.cloudstream4.core.designsystem.desktop.PosterSize
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Where a host keeps the stored map. The Mac keeps it in one owner-only file; a test keeps it in memory.
 *
 * [load] never throws: a host that cannot read returns an empty map, which reads as every default.
 * [save] may throw; the repository keeps the new value in memory either way, so the window still shows it.
 */
interface SettingsStorage {
    fun load(): Map<String, String>

    fun save(values: Map<String, String>)

    /** Keeps nothing. Every value starts at its default. */
    class InMemory(initial: Map<String, String> = emptyMap()) : SettingsStorage {
        var stored: Map<String, String> = initial
            private set

        override fun load(): Map<String, String> = stored

        override fun save(values: Map<String, String>) {
            stored = values
        }
    }
}

/**
 * The one owner of the settings: reads them once, hands out read-only views, and writes every change through.
 *
 * @param onSaveFailed told when [SettingsStorage.save] throws, with the exception's class only. The value is kept in
 *   memory, so the change holds until the app quits.
 */
class SettingsRepository(
    private val storage: SettingsStorage,
    private val onSaveFailed: (String) -> Unit = {},
) : MutablePlaybackSettings, DownloadSettings, AppearanceSettings {
    private val state = MutableStateFlow(SettingsValues.fromStored(storage.load()))
    private val autoplay = MutableStateFlow(state.value.autoplayNext)
    private val atOnce = MutableStateFlow(state.value.downloadsAtOnce)
    private val random = MutableStateFlow(state.value.showRandom)
    private val posters = MutableStateFlow(state.value.posterSize)

    val values: StateFlow<SettingsValues> = state.asStateFlow()
    override val autoplayNext: StateFlow<Boolean> = autoplay.asStateFlow()
    override val downloadsAtOnce: StateFlow<Int> = atOnce.asStateFlow()
    override val showRandom: StateFlow<Boolean> = random.asStateFlow()
    override val posterSize: StateFlow<PosterSize> = posters.asStateFlow()

    override fun setAutoplayNext(on: Boolean) = update { it.copy(autoplayNext = on) }

    fun setDownloadsAtOnce(count: Int) {
        require(count in SettingsValues.DOWNLOADS_AT_ONCE) { "downloadsAtOnce out of range" }
        update { it.copy(downloadsAtOnce = count) }
    }

    fun setShowRandom(on: Boolean) = update { it.copy(showRandom = on) }

    fun setPosterSize(size: PosterSize) = update { it.copy(posterSize = size) }

    /**
     * Applies [change] atomically, so two changes at once never lose one. The views and the file then take whatever is
     * current, and again if it moved meanwhile: whichever change lands last, the views and the file end on it.
     */
    private fun update(change: (SettingsValues) -> SettingsValues) {
        var changed = false
        state.update { current -> change(current).also { changed = it != current } }
        if (!changed) return
        do {
            val now = state.value
            autoplay.value = now.autoplayNext
            atOnce.value = now.downloadsAtOnce
            random.value = now.showRandom
            posters.value = now.posterSize
            try {
                storage.save(now.toStored())
            } catch (e: Exception) {
                // No return: a failed save still re-checks, so the views never stay on a value that moved meanwhile.
                onSaveFailed(e::class.simpleName ?: "Exception")
            }
        } while (state.value != now)
    }
}
