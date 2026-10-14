package com.lagradost.cloudstream4.media.model

/**
 * Who asked for something to happen.
 *
 * Restates `:app`'s `PlayerEventSource`. It exists so a player can tell a command it originated
 * from a user gesture apart from one it raised itself, and from one a sync peer sent — without it,
 * echoing a remote seek back to the peer is indistinguishable from a local one.
 */
enum class CommandSource {
    /** A user gesture. */
    USER,

    /** The engine's own doing — an automatic next episode, a buffering stall. */
    ENGINE,

    /** A peer in a shared session. */
    SYNC,
}

/**
 * Something the UI asks the playback engine to do.
 *
 * Today this is `:app`'s `CSPlayerEvent`, an eleven-case enum, **plus** about twenty methods on
 * `IPlayer` that are commands in every sense but are not in it — `seekTo`, `setPlaybackSpeed`,
 * `setSubtitleOffset`, `setPreferredSubtitles`, `setMaxVideoSize`. The split is not principled: it
 * is that `CSPlayerEvent` is the subset that needs to survive a trip through an Android `Intent`,
 * because the picture-in-picture buttons are `RemoteAction`s and carry an int.
 *
 * A sealed hierarchy instead of an enum, because half of these carry a value. An enum forces the
 * argument into a side channel, which is exactly how `IPlayer` ended up with two command surfaces.
 *
 * ## This does not replace `CSPlayerEvent`, and must not try to
 *
 * `CSPlayerEvent` is in `app/api/classes-jar.api`, so its cases and their order are published ABI.
 * It also has a live wire format: `PlayerPipHelper` writes `event.value` into an `Intent` extra and
 * `PlayerView` reads it back. As of `BUG-09` the reader looks the case up **by `value`**; it used
 * to index `CSPlayerEvent.entries[int]` by ordinal, which agreed only because every case happens
 * to have `value == ordinal`. So `value` is the wire format and must stay stable.
 * `:app` keeps the enum and maps; nothing here renumbers anything.
 */
sealed interface PlayerCommand {

    val source: CommandSource

    // Transport.

    data class Play(override val source: CommandSource = CommandSource.USER) : PlayerCommand
    data class Pause(override val source: CommandSource = CommandSource.USER) : PlayerCommand
    data class TogglePlayPause(override val source: CommandSource = CommandSource.USER) : PlayerCommand

    /** Start the current stream again from the beginning. */
    data class Restart(override val source: CommandSource = CommandSource.USER) : PlayerCommand

    // Position.

    /** Move to an absolute position. */
    data class SeekTo(
        val positionMs: Long,
        override val source: CommandSource = CommandSource.USER,
    ) : PlayerCommand

    /**
     * Move by [offsetMs], forwards when positive.
     *
     * Kept separate from [SeekTo] rather than resolved by the caller: the engine knows the current
     * position and the duration, so only it can clamp without a race.
     */
    data class SeekBy(
        val offsetMs: Long,
        override val source: CommandSource = CommandSource.USER,
    ) : PlayerCommand

    /** Skip past the chapter covering the current position, if there is one. */
    data class SkipChapter(override val source: CommandSource = CommandSource.USER) : PlayerCommand

    // Playlist.

    /**
     * Move [offset] items through the playlist, forwards when positive.
     *
     * `:app`'s `EpisodeSeekEvent` asserts a non-zero offset at runtime; here it is a precondition,
     * because a zero-offset episode seek has no meaning and silently doing nothing hides a caller
     * bug.
     */
    data class SeekEpisode(
        val offset: Int,
        override val source: CommandSource = CommandSource.USER,
    ) : PlayerCommand {
        init {
            require(offset != 0) { "SeekEpisode offset must not be zero" }
        }
    }

    // Rendering and tracks.

    data class SetPlaybackSpeed(
        val speed: Float,
        override val source: CommandSource = CommandSource.USER,
    ) : PlayerCommand {
        init {
            require(speed > 0f) { "speed must be positive, was $speed" }
        }
    }

    data class SetMuted(
        val muted: Boolean,
        override val source: CommandSource = CommandSource.USER,
    ) : PlayerCommand

    /** Select a subtitle track, or `null` to show none. */
    data class SelectSubtitle(
        val trackId: String?,
        override val source: CommandSource = CommandSource.USER,
    ) : PlayerCommand

    /** Shift every subtitle by [offsetMs], positive meaning later. */
    data class SetSubtitleOffset(
        val offsetMs: Long,
        override val source: CommandSource = CommandSource.USER,
    ) : PlayerCommand

    data class SelectAudioTrack(
        val trackId: String?,
        override val source: CommandSource = CommandSource.USER,
    ) : PlayerCommand

    /**
     * Continue playing with no video surface.
     *
     * Not a track selection: on Android it also means taking audio focus and running under a
     * foreground service, which is why `:media:audio` exists as a seam at all (plan §4.5).
     */
    data class PlayAsAudio(override val source: CommandSource = CommandSource.USER) : PlayerCommand

    // Lifecycle.

    data class Stop(override val source: CommandSource = CommandSource.USER) : PlayerCommand
}
