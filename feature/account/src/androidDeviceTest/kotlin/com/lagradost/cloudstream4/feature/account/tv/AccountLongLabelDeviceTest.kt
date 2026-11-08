package com.lagradost.cloudstream4.feature.account.tv

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lagradost.cloudstream4.core.designsystem.target.TextButtonDefaults
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.strings.generated.resources.Res
import com.lagradost.cloudstream4.core.strings.generated.resources.account_manage_profiles
import com.lagradost.cloudstream4.core.strings.generated.resources.account_sync_remove
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.account.AccountSelectState
import com.lagradost.cloudstream4.feature.account.ProfileCard
import com.lagradost.cloudstream4.feature.account.SignIn
import com.lagradost.cloudstream4.feature.account.SyncService
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale
import kotlin.math.max

/**
 * `BUG-62` (b), on artboard 8d's own screen: the two fixed-width slots grow to their label instead of cutting it.
 *
 * - **Manage profiles** had 144dp of label in a 176dp button. `account_manage_profiles` measures 173.5dp in
 *   Malayalam, 147.2dp in Tamil and 142.0dp in Filipino against 80.4dp in English.
 * - **A service row's verb** had 200dp. `account_sync_remove` measures 229.1dp in Malayalam against
 *   124.7dp in English — the word that says what pressing the row does was the one being cut.
 *
 * (`BUG-62`, measured on `Television_720p` in all 61 locales.)
 *
 * **The screen is drawn in Malayalam**, not handed a long string: both labels come from `:core:strings` inside the
 * composable, so the only way to put a real translation on screen is to run the screen in that language.
 * compose-resources resolves through `Locale.current`, which on Android is the default locale, so this case sets it and
 * puts it back. [theScreenIsReallyInMalayalam] guards the rest: if the locale ever stops reaching the resources, the
 * labels fall back to English, they fit, and these cases would pass saying nothing.
 *
 * What is asserted is the rule rather than any one translation's width: **no label is cut, and a slot is its label
 * plus its padding or the board's width, whichever is more.** A translator who shortens a string does not fail this.
 */
@RunWith(AndroidJUnit4::class)
class AccountLongLabelDeviceTest {

    @get:Rule
    val rule = createComposeRule()

    private val before: Locale = Locale.getDefault()

    /** What the screen resolved, read from the composition so the case never second-guesses the resources. */
    private class Labels(val manage: String, val remove: String)

    private var labels: Labels? = null

    @Before
    fun drawInMalayalam() = Locale.setDefault(Locale.forLanguageTag(MALAYALAM))

    @After
    fun putTheLocaleBack() = Locale.setDefault(before)

    private fun show() {
        rule.setContent {
            CloudStreamTheme {
                ReadLabels()
                AccountSelectScreenTv(state = BOARD, onAction = {})
            }
        }
        rule.waitForIdle()
    }

    /**
     * Reads the two labels the screen will draw.
     *
     * It writes to a plain field rather than to state, which is safe exactly because nothing observes it: no
     * recomposition can be triggered by it, and the test reads it after the composition has settled.
     */
    @Composable
    private fun ReadLabels() {
        labels = Labels(
            manage = stringResource(Res.string.account_manage_profiles),
            remove = stringResource(Res.string.account_sync_remove),
        )
    }

    private fun labels(): Labels = checkNotNull(labels) { "the screen has not been composed" }

    private fun bounds(text: String, unmerged: Boolean = false): Rect =
        rule.onNodeWithText(text, useUnmergedTree = unmerged).fetchSemanticsNode().boundsInRoot

    /** `BasicText` and `Text` both publish a layout, which is the only honest way to ask whether a label was cut. */
    private fun SemanticsNodeInteraction.layout(): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        val action = checkNotNull(fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action) {
            "the label does not publish a layout"
        }
        action(results)
        return results.first()
    }

    private fun Dp.px(): Float = with(rule.density) { toPx() }

    /** The guard: without it the two cases below could pass on English labels that were never too long. */
    @Test
    fun theScreenIsReallyInMalayalam() {
        show()
        assertNotEquals("the forced locale did not reach the resources", MANAGE_EN, labels().manage)
        assertNotEquals("the forced locale did not reach the resources", REMOVE_EN, labels().remove)
    }

    @Test
    fun theManageButtonGrowsToItsLabel() {
        show()
        rule.assertScreenshot("account-long-labels")

        val manage = labels().manage
        val text = rule.onNodeWithText(manage, useUnmergedTree = true).layout()
        assertFalse("the label is not cut: $manage", text.isLineEllipsized(FIRST_LINE))
        assertEquals(
            "the button is its label plus padding, floored at the board's width",
            max(
                AccountSelectDefaults.ManageWidth.px(),
                text.size.width + TextButtonDefaults.HorizontalPadding.px() * SIDES,
            ),
            bounds(manage).width,
            TOLERANCE,
        )
    }

    @Test
    fun aServiceRowsVerbIsNotCut() {
        show()
        val remove = labels().remove
        val text = rule.onNodeWithText(remove, useUnmergedTree = true)
        assertFalse("the verb is not cut: $remove", text.layout().isLineEllipsized(FIRST_LINE))

        val verb = bounds(remove, unmerged = true)
        val row = bounds(remove)
        assertTrue("the verb stays inside its row: $verb in $row", verb.right <= row.right && verb.left >= row.left)
        assertTrue(
            "the service's name is still on the row beside it",
            bounds(CONNECTED_SERVICE, unmerged = true).right <= verb.left,
        )
    }

    private companion object {
        const val MALAYALAM = "ml"
        const val FIRST_LINE = 0
        const val SIDES = 2
        const val CONNECTED_SERVICE = "Kitsu"

        /** A pixel of rounding: a dp is not a whole number of pixels on every density. */
        const val TOLERANCE = 1f

        /** The English the forced locale must not be showing. */
        const val MANAGE_EN = "Manage profiles"
        const val REMOVE_EN = "Remove from this device"

        /** 8d's state, reduced to what these two labels need: a profile, and a service that can be removed. */
        val BOARD = AccountSelectState(
            profiles = listOf(ProfileCard(0, "Main", current = true, locked = false, syncedWith = listOf("Kitsu"))),
            services = listOf(
                SyncService("kitsu", CONNECTED_SERVICE, connected = true, signIn = SignIn.InApp),
            ),
        )
    }
}
