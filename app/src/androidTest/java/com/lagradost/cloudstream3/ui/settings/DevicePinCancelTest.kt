package com.lagradost.cloudstream3.ui.settings

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.syncproviders.AuthAPI
import com.lagradost.cloudstream3.syncproviders.AuthPinData
import com.lagradost.cloudstream3.syncproviders.AuthToken
import com.lagradost.cloudstream3.syncproviders.AuthUser
import com.lagradost.cloudstream3.syncproviders.PlainAuthRepo
import com.lagradost.cloudstream3.utils.DataStoreHelper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * `BUG-49` on the real dialog: open the device-code pairing, leave with BACK, then have the code entered on "the other
 * device". The TV must not be signed in.
 *
 * The provider is a fake in this test: its code is a fixture (`FAKE00`), its token a marker, and it reaches no network.
 * Nothing is printed; the assertion counts saved sign-ins.
 */
@RunWith(AndroidJUnit4::class)
class DevicePinCancelTest {

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val codeEntered = AtomicBoolean(false)
    private val polls = AtomicInteger()

    private val fake = object : AuthAPI() {
        override val name = "Pairing fixture"
        override val idPrefix = PREFIX
        override val hasPin = true
        override suspend fun pinRequest() =
            AuthPinData(deviceCode = "fixture-device", userCode = CODE, verificationUrl = "https://example.invalid/pin",
                expiresIn = 30, interval = 1)
        override suspend fun login(payload: AuthPinData): AuthToken? {
            polls.incrementAndGet()
            return if (codeEntered.get()) AuthToken(accessToken = "NOT-A-REAL-TOKEN") else null
        }
        override suspend fun user(token: AuthToken?) = AuthUser(name = "Fixture", id = 4949)
    }

    @After
    fun forgetTheFixture() {
        val index = DataStoreHelper.currentAccount
        removeKey("${AccountManager.ACCOUNT_TOKEN}/$PREFIX/$index")
        removeKey("${AccountManager.ACCOUNT_IDS}/$PREFIX/$index")
    }

    @Test
    fun aCodeEnteredAfterCancelDoesNotSignIn() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { SettingsAccount.showPin(it, PlainAuthRepo(fake)) }
            assertNotNull("The pairing dialog never showed", device.wait(Until.findObject(By.text(CODE)), 10_000))

            device.pressBack()
            device.wait(Until.gone(By.text(CODE)), 5_000)
            val pollsAtCancel = polls.get()
            codeEntered.set(true)
            Thread.sleep(4_000) // four poll intervals

            assertEquals("A cancelled pairing signed in", 0, AccountManager.accounts(PREFIX).size)
            assertEquals("The pairing kept polling after Cancel", pollsAtCancel, polls.get())
        }
    }

    private companion object {
        const val PREFIX = "bug49fixture"
        const val CODE = "FAKE00"
    }
}
