package com.lagradost.cloudstream4.player

import android.text.format.DateFormat
import android.view.KeyEvent
import android.view.ViewGroup
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.player.HudKey
import com.lagradost.cloudstream4.feature.player.PlayerHudAction
import com.lagradost.cloudstream4.feature.player.PlayerHudController
import com.lagradost.cloudstream4.feature.player.PlayerHudState
import com.lagradost.cloudstream4.feature.player.tv.PlayerHudTv
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Date

/**
 * **The switch.** `false` puts the old controller back on Android TV: `FullScreenPlayer` never builds a [TvPlayerHud]
 * and every line that mentions one is a no-op. On by default, as the other TV switches are: it is UI only and touches
 * none of the user's files. A `var` so a test can prove the rollback.
 */
object TvPlayerHudSwitch {
    @JvmField
    var useComposeHud: Boolean = true
}

/**
 * Artboard 7d over the existing player (`KMP-64`): a `ComposeView` on top of the video, the
 * [PlayerHudController]'s rules, and the facts read from the player four times a second.
 *
 * The old Media3 controller is turned off while this is up (`FullScreenPlayer` sets `useController = false`), so the
 * subtitles, the pre-roll overlay and the video itself are the old player's, unchanged. What the buttons do is the
 * player's own code, through [onAction]: the pickers it opens are the dialogs it always opened (7e is `Q25`).
 *
 * **Keys.** The activity hands every key to the player's listener before any view sees it, so [onKey] is called there
 * first. Hidden, the controller uses the key; shown, it re-arms the timer and lets the focused control have it.
 *
 * @param facts what the player knows now. Called on the main thread.
 */
internal class TvPlayerHud(
    private val container: ViewGroup,
    owner: LifecycleOwner,
    hiddenSeekMs: Long,
    private val shownSeekMs: Long,
    private val facts: () -> TvHudFacts?,
    onAction: (PlayerHudAction) -> Unit,
) {
    private val state = MutableStateFlow(PlayerHudState())
    private val clock = MutableStateFlow("")
    private val controller = PlayerHudController(owner.lifecycleScope, hiddenSeekMs, onAction)

    /** Key codes whose down was used here, so their up is used here too and never reaches a view on its own. */
    private val consumedDowns = HashSet<Int>()

    private val view = ComposeView(container.context).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val now by state.collectAsState()
            val shown by controller.shown.collectAsState()
            val readout by controller.readout.collectAsState()
            val time by clock.collectAsState()
            CloudStreamTheme {
                PlayerHudTv(
                    state = now,
                    shown = shown,
                    readout = readout,
                    clock = time,
                    shownSeekMs = shownSeekMs,
                    onAction = onAction,
                )
            }
        }
    }

    private val ticker: Job

    init {
        container.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        ticker = owner.lifecycleScope.launch {
            while (isActive) {
                refresh()
                delay(TICK_MS)
            }
        }
        // The old HUD showed itself when playback started; 7d's entry is the same, focus on play/pause.
        controller.reveal()
    }

    fun refresh() {
        val next = facts()?.toHudState() ?: return
        state.value = next
        controller.state = next
        clock.value = DateFormat.getTimeFormat(container.context).format(Date())
    }

    fun reveal() = controller.reveal()

    /** One key from the activity. `true` when it was used here. */
    fun onKey(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (event.action == KeyEvent.ACTION_UP) return consumedDowns.remove(code)
        if (event.action != KeyEvent.ACTION_DOWN) return false
        val used = controller.onKey(hudKey(code))
        if (used) consumedDowns += code
        return used
    }

    fun release() {
        ticker.cancel()
        container.removeView(view)
    }

    private fun hudKey(code: Int): HudKey = when (code) {
        KeyEvent.KEYCODE_DPAD_LEFT -> HudKey.Left
        KeyEvent.KEYCODE_DPAD_RIGHT -> HudKey.Right
        KeyEvent.KEYCODE_DPAD_UP -> HudKey.Up
        KeyEvent.KEYCODE_DPAD_DOWN -> HudKey.Down
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> HudKey.Center
        // The old handler's toggles, the same keys.
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_NUMPAD_ENTER -> HudKey.PlayPause
        else -> HudKey.Other
    }

    /**
     * Whether a key the HUD did not use is still the HUD's: the D-pad and OK move focus among its own controls, and the
     * old handler must not act on them too. Any other key (MENU, a keyboard's shortcuts) goes on to the old handler.
     */
    fun ownsNavigation(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_UP_LEFT, KeyEvent.KEYCODE_DPAD_UP_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN_LEFT,
        KeyEvent.KEYCODE_DPAD_DOWN_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> true
        else -> false
    }

    private companion object {
        /** Four times a second: the elapsed time moves once a second, and a quarter keeps the knob from stepping. */
        const val TICK_MS = 250L
    }
}
