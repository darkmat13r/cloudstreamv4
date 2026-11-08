package com.lagradost.cloudstream4.feature.account.tv

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import android.view.KeyEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lagradost.cloudstream4.core.devicetest.Dpad
import com.lagradost.cloudstream4.core.devicetest.DpadDriver
import com.lagradost.cloudstream4.core.devicetest.assertScreenshot
import com.lagradost.cloudstream4.core.devicetest.focusedLabel
import com.lagradost.cloudstream4.core.theme.CloudStreamTheme
import com.lagradost.cloudstream4.feature.account.AccountAction
import com.lagradost.cloudstream4.feature.account.AccountSelectState
import com.lagradost.cloudstream4.feature.account.Pairing
import com.lagradost.cloudstream4.feature.account.ProfileCard
import com.lagradost.cloudstream4.feature.account.SignIn
import com.lagradost.cloudstream4.feature.account.SyncService
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Artboard 8d on a device with fixture state: no `:app`, no provider, no network.
 *
 * The pairing code is a fixture (`FAKE00`) and the QR a checkerboard, so no screenshot holds a real credential.
 */
@RunWith(AndroidJUnit4::class)
class AccountSelectScreenTvDeviceTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val dpad = DpadDriver(readFocus = { rule.focusedLabel() })
    private val actions = CopyOnWriteArrayList<AccountAction>()
    private var state by mutableStateOf(BOARD)

    private fun show() {
        // As the :app host does (SidePanelHost's contract): API 36 sends BACK to the back dispatcher, not the panel.
        rule.runOnUiThread {
            rule.activity.onBackPressedDispatcher.addCallback(object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (state.pairing != null) {
                        actions += AccountAction.CancelPairing
                        state = state.copy(pairing = null)
                    }
                }
            })
        }
        rule.setContent {
            CloudStreamTheme {
                AccountSelectScreenTv(state, onAction = { action ->
                    actions += action
                    when (action) {
                        is AccountAction.ServicePressed -> if (action.id == SIMKL) state = state.copy(pairing = pairing())
                        AccountAction.CancelPairing -> state = state.copy(pairing = null)
                        else -> Unit
                    }
                })
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun focusStartsOnTheCurrentProfile() {
        show()
        assertEquals(MAIN, rule.focusedLabel())
    }

    /** 8d's note: Manage profiles, then the cards (LEFT/RIGHT), then the service rows (DOWN). */
    @Test
    fun theOrderIsManageThenCardsThenRows() {
        show()
        assertEquals(SARA, dpad.press(Dpad.Right))
        assertEquals(GUEST, dpad.press(Dpad.Right))
        assertEquals(ADD, dpad.press(Dpad.Right))
        assertEquals(MANAGE, dpad.press(Dpad.Up))
        assertTrue(dpad.press(Dpad.Down) in setOf(MAIN, SARA, GUEST, ADD))
        assertEquals(SIMKL_NAME, dpad.press(Dpad.Down))
        assertEquals(MAL_NAME, dpad.press(Dpad.Down))
    }

    @Test
    fun aCardPressSelectsThatProfile() {
        show()
        dpad.press(Dpad.Right)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitForIdle()
        assertEquals(AccountAction.ProfilePressed(1), actions.last())
    }

    /** Pairing opens with focus on Get a new code; BACK cancels it and focus returns to the row that opened it. */
    @Test
    fun pairingTakesFocusAndBackReturnsItToTheRow() {
        show()
        assertEquals(SIMKL_NAME, generateSequence { dpad.press(Dpad.Down) }.take(4).first { it == SIMKL_NAME })
        key(KeyEvent.KEYCODE_DPAD_CENTER)
        rule.waitForIdle()
        rule.onNodeWithText(FIXTURE_CODE).assertExists()
        awaitFocus(NEW_CODE)
        key(KeyEvent.KEYCODE_BACK)
        rule.waitUntil(5_000) { state.pairing == null }
        assertEquals(AccountAction.CancelPairing, actions.last())
        awaitFocus(SIMKL_NAME)
    }

    /** Every resting state on one screen: current, locked, no sync, pair, browser-only, connected. */
    @Test
    fun screenshotTheScreen() {
        show()
        rule.assertScreenshot("account-select")
    }

    @Test
    fun screenshotThePairingPanel() {
        state = BOARD.copy(pairing = pairing())
        show()
        rule.waitUntil(5_000) { rule.focusedLabel() == NEW_CODE }
        rule.assertScreenshot("account-select-pairing")
    }

    /** Waits for focus to land on [label], and says where it is if it does not. */
    private fun awaitFocus(label: String) {
        val until = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < until) {
            rule.waitForIdle()
            if (rule.focusedLabel() == label) return
            Thread.sleep(50)
        }
        throw AssertionError("Focus is on ${rule.focusedLabel()}, not $label")
    }

    /** OK and BACK through the input dispatcher, as a remote sends them. */
    private fun key(code: Int) {
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(code)
        rule.waitForIdle()
    }

    private fun pairing() = Pairing(SIMKL_NAME, "example.invalid/pin", FIXTURE_CODE, secondsLeft = 582, qr = checkerboard())

    /** A stand-in for the QR code: a pattern, not a code, so nothing in the shot can be scanned. */
    private fun checkerboard(): ImageBitmap {
        val bitmap = ImageBitmap(CELLS * CELL, CELLS * CELL)
        val canvas = Canvas(bitmap)
        val white = Paint().apply { color = Color.White }
        val black = Paint().apply { color = Color.Black }
        for (x in 0 until CELLS) for (y in 0 until CELLS) {
            canvas.drawRect(Rect((x * CELL).toFloat(), (y * CELL).toFloat(), ((x + 1) * CELL).toFloat(), ((y + 1) * CELL).toFloat()),
                if ((x + y) % 2 == 0) black else white)
        }
        return bitmap
    }

    private companion object {
        const val MAIN = "Main"
        const val SARA = "Sara"
        const val GUEST = "Guest"
        const val ADD = "Add profile"
        const val MANAGE = "Manage profiles"
        const val NEW_CODE = "Get a new code"
        const val SIMKL = "simkl"
        const val SIMKL_NAME = "Simkl"
        const val MAL_NAME = "MyAnimeList"
        const val FIXTURE_CODE = "FAKE00"
        const val CELLS = 8
        const val CELL = 8

        val BOARD = AccountSelectState(
            profiles = listOf(
                ProfileCard(0, MAIN, current = true, locked = false, syncedWith = listOf("Kitsu")),
                ProfileCard(1, SARA, current = false, locked = true, syncedWith = listOf("Simkl")),
                ProfileCard(2, GUEST, current = false, locked = false),
            ),
            services = listOf(
                SyncService(SIMKL, SIMKL_NAME, connected = false, signIn = SignIn.PairWithCode),
                SyncService("mal", MAL_NAME, connected = false, signIn = SignIn.Unavailable),
                SyncService("anilist", "AniList", connected = false, signIn = SignIn.Unavailable),
                SyncService("kitsu", "Kitsu", connected = true, signIn = SignIn.InApp),
            ),
        )
    }
}
