package com.lagradost.cloudstream4.download

import com.lagradost.cloudstream4.media.download.DownloadStorage
import java.io.File

/**
 * **The switch** for the segmented `:media:download` engine. `false`, the default, is the rollback:
 * `VideoDownloadManager.downloadSingleEpisode` then calls `downloadThing` exactly as it did before
 * this object existed, and nothing in [EngineVideoDownload] runs.
 *
 * It covers the `ExtractorLinkType.VIDEO` arm only. HLS, subtitles, torrents, magnets and DASH never
 * reach the engine whatever this says.
 *
 * `true` changes user-visible behaviour: TLS is verified, private and loopback addresses are refused,
 * the file only appears when it is complete, and more. The owner reviews that list (in the PR that
 * added this object) before it is turned on; until then only tests set it. It is a `var` so a test
 * can prove both paths, as `TvRailSwitch` does.
 */
internal object EngineDownloadSwitch {
    @JvmField
    var useSegmentedEngine: Boolean = false

    /**
     * Test-only: lets the engine reach a server on 127.0.0.1. Production never sets it, so the engine's
     * refusal of loopback, private and link-local addresses stands.
     */
    @JvmField
    var allowPrivateAddressesForTests: Boolean = false

    /** Test-only: the engine's file operations, for a quota that fills up. `null` is the real disk. */
    @JvmField
    var storageForTests: DownloadStorage? = null

    /** Test-only: where downloads are staged. `null` is `noBackupFilesDir/downloads`. */
    @JvmField
    var stagingRootForTests: File? = null
}
