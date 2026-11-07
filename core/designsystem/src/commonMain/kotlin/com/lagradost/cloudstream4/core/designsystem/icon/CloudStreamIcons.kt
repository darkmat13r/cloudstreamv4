package com.lagradost.cloudstream4.core.designsystem.icon

import androidx.compose.ui.graphics.vector.ImageVector
import compose.icons.TablerIcons
import compose.icons.tablericons.AdjustmentsHorizontal
import compose.icons.tablericons.AlertTriangle
import compose.icons.tablericons.ArrowLeft
import compose.icons.tablericons.Book
import compose.icons.tablericons.Bookmark
import compose.icons.tablericons.Cast
import compose.icons.tablericons.Check
import compose.icons.tablericons.ChevronRight
import compose.icons.tablericons.CircleX
import compose.icons.tablericons.Clock
import compose.icons.tablericons.Database
import compose.icons.tablericons.Dots
import compose.icons.tablericons.Dice
import compose.icons.tablericons.Download
import compose.icons.tablericons.ExternalLink
import compose.icons.tablericons.Folder
import compose.icons.tablericons.Gauge
import compose.icons.tablericons.Headphones
import compose.icons.tablericons.Heart
import compose.icons.tablericons.Home
import compose.icons.tablericons.InfoCircle
import compose.icons.tablericons.Link
import compose.icons.tablericons.List
import compose.icons.tablericons.Lock
import compose.icons.tablericons.Maximize
import compose.icons.tablericons.MessageCircle
import compose.icons.tablericons.Minimize
import compose.icons.tablericons.Movie
import compose.icons.tablericons.PlayerPause
import compose.icons.tablericons.PlayerPlay
import compose.icons.tablericons.PlayerSkipBack
import compose.icons.tablericons.PlayerSkipForward
import compose.icons.tablericons.PlayerStop
import compose.icons.tablericons.PlayerTrackNext
import compose.icons.tablericons.PlayerTrackPrev
import compose.icons.tablericons.Plus
import compose.icons.tablericons.Refresh
import compose.icons.tablericons.Rotate
import compose.icons.tablericons.Search
import compose.icons.tablericons.Server
import compose.icons.tablericons.Settings
import compose.icons.tablericons.Share
import compose.icons.tablericons.Star
import compose.icons.tablericons.Trash
import compose.icons.tablericons.User
import compose.icons.tablericons.Volume
import compose.icons.tablericons.Volume3
import compose.icons.tablericons.WifiOff
import compose.icons.tablericons.X

/**
 * Every icon in the app, named for what it means (`.claude/rules/icons.md`, `DEC-11`).
 *
 * A screen says [Download], never the pack's own symbol, so changing pack is an edit to this file. The pack
 * is an `implementation` dependency of this module, which makes that a compile error and not a convention.
 * One glyph per meaning, and one meaning per glyph: `CloudStreamIconsTest` fails on a duplicate.
 *
 * Getters, because the pack builds each vector on first read and caches it. A `val` here would build all
 * of them the first time anything touches this object.
 *
 * ## Two meanings Tabler has no glyph for
 *
 * - [Subtitles] is a speech bubble, like Apple's captions glyph.
 * - [Quality] is a pair of sliders: Tabler has no HD mark, and what the control does is adjust.
 */
object CloudStreamIcons {
    // Transport
    val Play: ImageVector get() = TablerIcons.PlayerPlay
    val Pause: ImageVector get() = TablerIcons.PlayerPause
    val Stop: ImageVector get() = TablerIcons.PlayerStop
    val SeekBackward: ImageVector get() = TablerIcons.PlayerTrackPrev
    val SeekForward: ImageVector get() = TablerIcons.PlayerTrackNext
    val PreviousEpisode: ImageVector get() = TablerIcons.PlayerSkipBack
    val NextEpisode: ImageVector get() = TablerIcons.PlayerSkipForward
    val Subtitles: ImageVector get() = TablerIcons.MessageCircle
    val Quality: ImageVector get() = TablerIcons.AdjustmentsHorizontal
    val Audio: ImageVector get() = TablerIcons.Headphones
    val Speed: ImageVector get() = TablerIcons.Gauge
    val Cast: ImageVector get() = TablerIcons.Cast
    val Volume: ImageVector get() = TablerIcons.Volume
    val Muted: ImageVector get() = TablerIcons.Volume3
    val EnterFullscreen: ImageVector get() = TablerIcons.Maximize
    val ExitFullscreen: ImageVector get() = TablerIcons.Minimize
    /** Plays from the beginning, beside a Resume. Not [Retry]: nothing failed. */
    val StartOver: ImageVector get() = TablerIcons.Rotate
    val Trailer: ImageVector get() = TablerIcons.Movie

    // Navigation and chrome
    val Back: ImageVector get() = TablerIcons.ArrowLeft
    val Close: ImageVector get() = TablerIcons.X
    /** Something done before: a recent search. */
    val History: ImageVector get() = TablerIcons.Clock
    /** Empties a field. Not [Close]: nothing goes away, and Mac search fields draw it circled. */
    val Clear: ImageVector get() = TablerIcons.CircleX
    val Search: ImageVector get() = TablerIcons.Search
    val Home: ImageVector get() = TablerIcons.Home
    val Settings: ImageVector get() = TablerIcons.Settings
    val More: ImageVector get() = TablerIcons.Dots
    val Disclosure: ImageVector get() = TablerIcons.ChevronRight
    val Library: ImageVector get() = TablerIcons.Book
    val Profile: ImageVector get() = TablerIcons.User
    /** Opens what something is about, such as a title's page from the home's hero. Not [More]: that is an overflow. */
    val Info: ImageVector get() = TablerIcons.InfoCircle
    /** A title's episode list. */
    val Episodes: ImageVector get() = TablerIcons.List
    /** Leaves the app for a browser. */
    val OpenExternal: ImageVector get() = TablerIcons.ExternalLink
    /** Something reached by its URL, such as playing from a link. */
    val Link: ImageVector get() = TablerIcons.Link

    // Actions and state
    val Download: ImageVector get() = TablerIcons.Download
    val Add: ImageVector get() = TablerIcons.Plus
    val Delete: ImageVector get() = TablerIcons.Trash
    val Done: ImageVector get() = TablerIcons.Check
    val Retry: ImageVector get() = TablerIcons.Refresh
    val Warning: ImageVector get() = TablerIcons.AlertTriangle
    val Share: ImageVector get() = TablerIcons.Share
    /** Where a download is on the disk: the Mac's Show in Finder (`DEC-19`). */
    val Folder: ImageVector get() = TablerIcons.Folder
    val Favorite: ImageVector get() = TablerIcons.Heart
    val Bookmark: ImageVector get() = TablerIcons.Bookmark
    val Star: ImageVector get() = TablerIcons.Star
    val Lock: ImageVector get() = TablerIcons.Lock
    val Offline: ImageVector get() = TablerIcons.WifiOff
    /** Home's Random: one title chosen from what is loaded (`TV Home Extras` 3j). */
    val Random: ImageVector get() = TablerIcons.Dice

    // Sources
    val Source: ImageVector get() = TablerIcons.Server
    val Repository: ImageVector get() = TablerIcons.Database
}
