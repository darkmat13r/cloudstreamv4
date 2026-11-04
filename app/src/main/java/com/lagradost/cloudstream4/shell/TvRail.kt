package com.lagradost.cloudstream4.shell

import android.graphics.Rect
import android.view.FocusFinder
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Density
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.databinding.ActivityMainBinding
import com.lagradost.cloudstream4.core.designsystem.icon.CloudStreamIcons
import com.lagradost.cloudstream4.core.designsystem.rail.NavRailHost
import com.lagradost.cloudstream4.core.designsystem.rail.NavRailItem
import com.lagradost.cloudstream4.core.theme.CloudStreamDimens
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme

/**
 * **The switch.** `false` puts the XML rail back on Android TV, as it was before this class: `MainActivity` never
 * builds a [TvRail] and every line that mentions one is then a no-op. It is a `var` so a test can prove that. Phone
 * and the emulator layout never get a [TvRail] whatever this says.
 */
object TvRailSwitch {
    @JvmField
    var useComposeRail: Boolean = true
}

/** Which rail item marks [destinationId]: a page below a top-level page marks that page, as the XML rail did. */
internal fun railItemOf(destinationId: Int): Int = when (destinationId) {
    in DOWNLOADS_PAGES -> R.id.navigation_downloads
    in SETTINGS_PAGES -> R.id.navigation_settings
    else -> destinationId
}

/**
 * The Compose rail (`DEC-17`, `TV Home` 3a and 3b) on the TV layout.
 *
 * **Why a view added here and not an edit to `activity_main_tv.xml`.** That layout is shared with the emulator layout,
 * and has to keep a `NavigationRailView` with this id for `ActivityMainBinding.bind`, so leaving it alone is what
 * keeps the emulator layout byte for byte what it was. The Compose rail is a `ComposeView` added to `homeRoot`.
 *
 * **The XML rail stays, hidden, as the navigation seam.** `setupWithNavController`, its debounce, the
 * `cloudstreamsearch://` handler and `SearchTvFragment.BrowseHome` all go through `navRailView.selectedItemId`, and a
 * `GONE` rail still answers that. So committing an item here is the same call those make.
 *
 * **Why the view changes size.** A view cannot paint outside itself, and `FocusFinder` measures a view by its bounds.
 * Collapsed, it is the rail's own 76dp, so LEFT from the page finds it where the rail is. Expanded, it is the whole
 * screen, so the rail can paint to 188dp over the page with the dim behind it. The page's margin never changes, so
 * the page does not reflow.
 */
class TvRail(
    private val activity: MainActivity,
    private val binding: ActivityMainBinding,
) {
    private val railWidth = with(Density(activity)) { CloudStreamDimens.NavRailWidth.roundToPx() }

    /** From the menu both XML rails use, so its order and its translated titles have one source. */
    private val menu = binding.navRailView.menu
    private val destinations = List(menu.size()) { menu.getItem(it).itemId }
    private val items = List(menu.size()) { index ->
        NavRailItem(menu.getItem(index).title.toString(), iconOf(destinations[index]))
    }

    private var selectedIndex by mutableIntStateOf(0)
    private var shownDestination: Int? = null

    /** What held focus on the page when the last D-pad key went down, and where in it. */
    private var pageFocus: View? = null
    private val pageFocusRect = Rect()

    private val view = ComposeView(activity).apply {
        isVisible = false
        layoutParams = FrameLayout.LayoutParams(railWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.START)
        setContent {
            CloudStreamTheme {
                val ink = CloudStreamTheme.colors.ink
                NavRailHost(
                    items = items,
                    selectedIndex = selectedIndex,
                    onSelect = { binding.navRailView.selectedItemId = destinations[it] },
                    onLeave = ::returnToPage,
                    onExpandedChange = { expanded ->
                        updateLayoutParams {
                            width = if (expanded) ViewGroup.LayoutParams.MATCH_PARENT else railWidth
                        }
                    },
                    logo = {
                        Image(
                            painter = painterResource(R.drawable.ic_cloudstream_monochrome_big),
                            contentDescription = null,
                            colorFilter = ColorFilter.tint(ink),
                        )
                    },
                )
            }
        }
    }

    init {
        // Above the page and below the unused focus outline, inside `homeRoot` so it takes the overscan padding.
        binding.homeRoot.addView(view, 1)
        binding.navRailView.neverTakeFocus()
    }

    /** Called from `updateNavBar` after it has set up the XML rail, so this has the last word on the shell. */
    fun onNavBarUpdated(destinationId: Int, visible: Boolean) {
        if (destinationId != shownDestination) {
            shownDestination = destinationId
            pageFocus = null
        }
        binding.navRailView.isVisible = false
        view.isVisible = visible
        destinations.indexOf(railItemOf(destinationId)).takeIf { it >= 0 }?.let { selectedIndex = it }
        binding.navHostFragment.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            marginStart = if (visible) railWidth else 0
        }
    }

    /** Called before the key is dispatched, because by the time focus has moved Compose has forgotten where it was. */
    fun beforeKey(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN || event.keyCode !in DPAD_KEYS || view.hasFocus()) return
        val focused = activity.currentFocus ?: return
        focused.getFocusedRect(pageFocusRect)
        pageFocus = focused
    }

    /** Moves focus into the rail, which lands on the current page. */
    fun requestFocus(): Boolean = view.isVisible && view.requestFocus()

    /**
     * Back to the exact stop that yielded focus (3b). If that stop has gone, because the page changed or because it was
     * never on screen, to the nearest stop towards the page, and failing that to the page's first.
     *
     * A View takes focus by itself. A Compose page has released its focus by now, so it searches for the nearest
     * stop towards the direction given, from the rect given. A rect one pixel before the stop's own leading edge
     * finds that stop and no other.
     */
    private fun returnToPage() {
        val direction = if (view.layoutDirection == View.LAYOUT_DIRECTION_RTL) View.FOCUS_LEFT else View.FOCUS_RIGHT
        val page = pageFocus?.takeIf { it.isAttachedToWindow && it.isShown }
        pageFocus = null
        if (page?.requestFocus(direction, leadingEdge(direction)) == true) return
        val nearest = FocusFinder.getInstance().findNextFocus(binding.homeRoot, activity.currentFocus, direction)
        if (nearest?.requestFocus(direction) != true) binding.navHostFragment.requestFocus(direction)
    }

    private fun leadingEdge(direction: Int): Rect? {
        val stop = pageFocusRect
        // Compose reports this when nothing on it can hold focus.
        if (stop.left == Int.MIN_VALUE) return null
        return if (direction == View.FOCUS_RIGHT) {
            Rect(stop.left - 1, stop.top, stop.left, stop.bottom)
        } else {
            Rect(stop.right, stop.top, stop.right + 1, stop.bottom)
        }
    }
}

/**
 * XML pages name the rail's items in `nextFocusLeft`. `FocusFinder` tests only such a view's own flags, so an item
 * of a `GONE` rail still counts, and it can take focus until the rail has first been laid out, which it never is. A
 * remote's LEFT then parks focus somewhere invisible (measured: `TvRailNavigationTest`, first run). Unfocusable, the
 * item is passed over and the search falls through to geometry, which finds the Compose rail.
 */
private fun View.neverTakeFocus() {
    isFocusable = false
    (this as? ViewGroup)?.children?.forEach { it.neverTakeFocus() }
}

private fun iconOf(destinationId: Int): ImageVector = when (destinationId) {
    R.id.navigation_home -> CloudStreamIcons.Home
    R.id.navigation_search -> CloudStreamIcons.Search
    R.id.navigation_library -> CloudStreamIcons.Library
    R.id.navigation_downloads -> CloudStreamIcons.Download
    else -> CloudStreamIcons.Settings
}

private val DOWNLOADS_PAGES = setOf(
    R.id.navigation_download_child,
    R.id.navigation_download_queue,
)

private val SETTINGS_PAGES = setOf(
    R.id.navigation_subtitles,
    R.id.navigation_chrome_subtitles,
    R.id.navigation_settings_player,
    R.id.navigation_settings_updates,
    R.id.navigation_settings_ui,
    R.id.navigation_settings_account,
    R.id.navigation_settings_providers,
    R.id.navigation_settings_general,
    R.id.navigation_settings_extensions,
    R.id.navigation_settings_plugins,
    R.id.navigation_test_providers,
)

private val DPAD_KEYS = setOf(
    KeyEvent.KEYCODE_DPAD_LEFT,
    KeyEvent.KEYCODE_DPAD_RIGHT,
    KeyEvent.KEYCODE_DPAD_UP,
    KeyEvent.KEYCODE_DPAD_DOWN,
)
