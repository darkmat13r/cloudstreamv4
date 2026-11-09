package com.lagradost.cloudstream4.player

/**
 * One player screen giving way to the next episode's (`KMP-94`) without leaving full screen.
 *
 * A screen leaves full screen when it closes. The next episode's screen takes the old one's place, so the old one closes
 * then too; [begin] says that this close is a handover, and the new screen takes over the same [PlayerFullscreen], which
 * remembers how the window was before any of them. [settled] once the old screen is gone.
 */
internal class PlayerHandover {
    private var handing = false
    private var current: PlayerFullscreen? = null

    fun begin() {
        handing = true
    }

    /** Asked by a closing screen: true when it is closing for the next episode. */
    fun keepsFullscreen(): Boolean = handing

    /** The screen's full screen: the one already in use during a handover, else a new one from [make]. */
    fun fullscreen(make: () -> PlayerFullscreen?): PlayerFullscreen? {
        if (!handing || current == null) current = make()
        return current
    }

    fun settled() {
        handing = false
    }
}
