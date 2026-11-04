package com.lagradost.cloudstream4.player

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DisplayWakeHoldTest {
    private val started = mutableListOf<Process>()

    private fun harmless(): Process =
        ProcessBuilder("/bin/sleep", "30").start().also(started::add)

    @AfterTest
    fun clean() {
        started.forEach { it.destroyForcibly() }
    }

    @Test
    fun `playing holds once, however many times it is told`() {
        val hold = DisplayWakeHold(::harmless)
        hold.set(true)
        hold.set(true)
        assertEquals(1, started.size)
        assertTrue(started.single().isAlive)
    }

    @Test
    fun `a pause lets go`() {
        val hold = DisplayWakeHold(::harmless)
        hold.set(true)
        hold.set(false)
        started.single().waitFor()
        assertFalse(started.single().isAlive)
    }

    @Test
    fun `playing again holds again`() {
        val hold = DisplayWakeHold(::harmless)
        hold.set(true)
        hold.set(false)
        hold.set(true)
        assertEquals(2, started.size)
        assertTrue(started.last().isAlive)
    }

    @Test
    fun `a hold that died is replaced`() {
        val hold = DisplayWakeHold(::harmless)
        hold.set(true)
        started.single().destroyForcibly().waitFor()
        hold.set(true)
        assertEquals(2, started.size)
    }

    @Test
    fun `nothing to start with is not a failure`() {
        val hold = DisplayWakeHold { null }
        hold.set(true)
        hold.set(false)
    }
}
