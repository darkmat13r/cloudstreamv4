package com.lagradost.cloudstream4.feature.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
) : MutablePlaybackSettings, DownloadSettings {
    private val state = MutableStateFlow(SettingsValues.fromStored(storage.load()))
    private val autoplay = MutableStateFlow(state.value.autoplayNext)
    private val atOnce = MutableStateFlow(state.value.downloadsAtOnce)

    val values: StateFlow<SettingsValues> = state.asStateFlow()
    override val autoplayNext: StateFlow<Boolean> = autoplay.asStateFlow()
    override val downloadsAtOnce: StateFlow<Int> = atOnce.asStateFlow()

    override fun setAutoplayNext(on: Boolean) = update { it.copy(autoplayNext = on) }

    fun setDownloadsAtOnce(count: Int) {
        require(count in SettingsValues.DOWNLOADS_AT_ONCE) { "downloadsAtOnce out of range" }
        update { it.copy(downloadsAtOnce = count) }
    }

    private fun update(change: (SettingsValues) -> SettingsValues) {
        val next = change(state.value)
        if (next == state.value) return
        state.value = next
        autoplay.value = next.autoplayNext
        atOnce.value = next.downloadsAtOnce
        try {
            storage.save(next.toStored())
        } catch (e: Exception) {
            onSaveFailed(e::class.simpleName ?: "Exception")
        }
    }
}
