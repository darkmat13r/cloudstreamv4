package com.lagradost.cloudstream4.core.devicetest

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.ExternalResource

/**
 * Takes the device out of touch mode after each test (`BUG-59`). Every test that taps the screen uses it.
 *
 * A tap (`UiDevice.click`, `UiObject2.click`) puts the **whole device** in touch mode, not just the test's window.
 * It stays on across tests, test classes and test APKs until a navigation key ends it. Back does not end it, so
 * closing the keyboard with Back leaves it on (measured: `dumpsys window` reads `mInTouchMode=true` after
 * `SearchScreenTvDeviceTest.aTapOpensTheKeyboard`, in another module's run). A test that reads the first focus before
 * it presses a key then fails for a reason that is not its own: `TvDetailNavigationTest` found `Go Back` focused
 * instead of `Try again`.
 *
 * ```
 * @get:Rule
 * val touchMode = TouchModeReset()
 * ```
 */
class TouchModeReset : ExternalResource() {
    override fun after() {
        InstrumentationRegistry.getInstrumentation().setInTouchMode(false)
    }
}
