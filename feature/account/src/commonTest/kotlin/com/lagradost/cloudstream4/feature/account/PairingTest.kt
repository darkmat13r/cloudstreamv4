package com.lagradost.cloudstream4.feature.account

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A pairing code is a short-lived credential: no `toString` of the state may carry it. The code here is a fixture. */
class PairingTest {

    private val pairing = Pairing("Simkl", "simkl.com/pin", userCode = "FAKE7Q", secondsLeft = 60, qr = null)

    @Test
    fun theCodeIsNeverInToString() {
        assertFalse(pairing.toString().contains("FAKE7Q"))
        assertTrue(pairing.toString().contains("Simkl"))
    }

    @Test
    fun norInTheScreenStatesToString() {
        val state = AccountSelectState(profiles = emptyList(), services = emptyList(), pairing = pairing)
        assertFalse(state.toString().contains("FAKE7Q"))
    }
}
