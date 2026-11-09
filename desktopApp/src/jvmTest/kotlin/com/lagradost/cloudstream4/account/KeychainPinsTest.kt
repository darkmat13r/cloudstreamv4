package com.lagradost.cloudstream4.account

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `KMP-95f`: a signed build's PINs, in the Keychain ([FakeVault]), and the one move of D1's file there. Only a read-back
 * that matches replaces the file, and it is replaced with the marker, so a missing item is never "no PINs" (the PM and
 * the reviewer of `#308`, 2026-11-09).
 */
class KeychainPinsTest {
    private val directory: File = Files.createTempDirectory("keychain-pins").toFile()
    private val vault = FakeVault()
    private val pinsFile = File(directory, DesktopPins.PINS_FILE)
    private var now = 5_000_000L
    private var failures = 0

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun filePins() = DesktopPins(directory, clock = { now }, onSaveFailed = { throw it })
    private fun keychainPins() = DesktopPins(directory, clock = { now }, onSaveFailed = { failures++ }, vault = vault)
    private fun marked() = pinsFile.isFile && pinsFile.readText() == DesktopPins.MARKER

    @Test
    fun `a PIN set on a signed build is in the Keychain with only the marker on disk, and outlives a restart`() {
        val pins = keychainPins()
        assertTrue(pins.set(1, "4821"))
        assertTrue(marked(), "the marker, and no PIN, on disk")
        assertTrue(vault.text(DesktopPins.PINS_ITEM).orEmpty().contains("4821"))
        val next = keychainPins()
        assertTrue(next.hasPin(1))
        assertEquals(PinCheck.Right, next.check(1, "4821"))
        assertTrue(next.remove(1))
        assertFalse(pinsFile.exists(), "the last PIN takes the marker with it")
        assertFalse(keychainPins().hasPin(1))
    }

    @Test
    fun `D1's file moves to the Keychain, and becomes the marker only once the read-back matches`() {
        filePins().apply {
            set(1, "4821")
            set(2, "1357")
        }
        val pins = keychainPins()
        assertTrue(marked(), "moved, so the file is the marker")
        assertTrue(pins.hasPin(1) && pins.hasPin(2))
        assertEquals(PinCheck.Right, pins.check(2, "1357"))
        assertEquals(PinCheck.Right, keychainPins().check(1, "4821"), "from the Keychain now")
    }

    @Test
    fun `after the move an unsigned build is locked, not open`() {
        filePins().set(1, "4821")
        keychainPins()
        assertTrue(marked())
        val unsigned = filePins()
        assertTrue(unsigned.hasPin(1))
        assertTrue(unsigned.hasPin(5), "which have a PIN is not known to it, so every profile but the first is locked")
        assertFalse(unsigned.hasPin(StoredProfiles.FIRST))
        assertEquals(PinCheck.Unreadable, unsigned.check(1, "0000"))
        assertFalse(unsigned.set(2, "1111"), "and it writes nothing over the marker")
        assertTrue(marked())
    }

    @Test
    fun `a signed build whose item has gone, with the marker there, is locked, not open`() {
        keychainPins().set(1, "4821")
        vault.items.remove(DesktopPins.PINS_ITEM) // a re-signed app, or an item deleted in Keychain Access
        val pins = keychainPins()
        assertTrue(pins.hasPin(1))
        assertTrue(pins.hasPin(5))
        assertEquals(PinCheck.Unreadable, pins.check(1, "4821"))
        assertFalse(pins.set(2, "1111"))
        assertTrue(marked())
    }

    @Test
    fun `a move the Keychain refuses keeps the file, every profile stays locked, and a change is refused`() {
        filePins().set(1, "4821")
        val before = pinsFile.readText()
        vault.refuseWrites = true
        val pins = keychainPins()
        assertEquals(before, pinsFile.readText(), "a failed write keeps the file")
        assertTrue(pins.hasPin(1), "still locked, read from the file")
        assertEquals(PinCheck.Wrong(0L), pins.check(1, "0000"))
        assertEquals(PinCheck.Right, pins.check(1, "4821"))
        assertFalse(pins.set(2, "1111"), "a change before the move has finished could lose the file: refused")
        assertFalse(pins.remove(1))
        assertEquals(before, pinsFile.readText(), "the file is never written or lost on a signed build")
        assertTrue(failures > 0, "the refusal was reported")

        vault.refuseWrites = false
        val later = keychainPins()
        assertTrue(marked(), "moved on the next try")
        assertEquals(PinCheck.Right, later.check(1, "4821"))
    }

    @Test
    fun `a read-back that does not match keeps the file and removes the item`() {
        filePins().set(1, "4821")
        vault.readBack = """{"pins":{"1":"0000"}}""".toByteArray()
        keychainPins()
        assertFalse(marked(), "not what was written, so the file stays")
        assertFalse(DesktopPins.PINS_ITEM in vault.items, "and the item that came back different is removed")
        vault.readBack = null
        assertEquals(PinCheck.Right, keychainPins().check(1, "4821"), "the file's PIN, moved on the next launch")
        assertTrue(marked())
    }

    @Test
    fun `a mismatched item whose delete was refused is locked, and the move is tried again once it can be removed`() {
        filePins().set(1, "4821")
        val d1 = pinsFile.readText()
        vault.readBack = """{"pins":{"1":"0000"}}""".toByteArray()
        vault.refuseDeletes = true
        keychainPins()
        assertEquals(d1, pinsFile.readText(), "the file stays")
        vault.readBack = null
        vault.items[DesktopPins.PINS_ITEM] = """{"pins":{"1":"0000"}}""".toByteArray() // what the Keychain held
        val stuck = keychainPins()
        assertEquals(PinCheck.Unreadable, stuck.check(1, "0000"), "an item that differs from D1's file is not trusted")
        assertEquals(PinCheck.Unreadable, stuck.check(1, "4821"))
        assertTrue(stuck.hasPin(5))
        assertEquals(d1, pinsFile.readText(), "and the file is not replaced")

        vault.refuseDeletes = false
        keychainPins() // removes the item that differs
        val moved = keychainPins()
        assertEquals(PinCheck.Right, moved.check(1, "4821"), "D1's PIN, moved again")
        assertTrue(marked())
    }

    @Test
    fun `an item beside a D1 file holding the same PINs replaces the file with the marker`() {
        filePins().set(1, "4821")
        vault.items[DesktopPins.PINS_ITEM] = pinsFile.readBytes() // a move whose marker could not be written
        val pins = keychainPins()
        assertTrue(marked())
        assertEquals(PinCheck.Right, pins.check(1, "4821"))
    }

    @Test
    fun `a first PIN the Keychain refuses takes its marker back, so it locks nothing`() {
        val pins = keychainPins()
        vault.refuseWrites = true
        assertFalse(pins.set(1, "4821"))
        assertFalse(pinsFile.exists())
        vault.refuseWrites = false
        val next = keychainPins()
        assertFalse(next.hasPin(1))
        assertFalse(next.hasPin(5), "nothing is locked by a PIN that was never set")
    }

    @Test
    fun `a Keychain that cannot be read locks every profile but the first, and is never the file instead`() {
        filePins().set(1, "4821")
        vault.refuseReads = true
        val pins = keychainPins()
        assertTrue(pins.hasPin(1))
        assertTrue(pins.hasPin(7), "which have a PIN is not known, so every profile but the first is locked")
        assertFalse(pins.hasPin(StoredProfiles.FIRST))
        assertEquals(PinCheck.Unreadable, pins.check(1, "4821"), "not compared, even with the file there")
        assertFalse(pins.set(2, "1111"))
        assertTrue(pinsFile.exists(), "the file is not touched")
    }

    @Test
    fun `an item that is not ours is unreadable, not no PINs`() {
        vault.items[DesktopPins.PINS_ITEM] = "not json".toByteArray()
        val pins = keychainPins()
        assertTrue(pins.hasPin(1))
        assertEquals(PinCheck.Unreadable, pins.check(1, "0000"))
        assertFalse(pins.set(1, "1111"))
        assertEquals("not json", vault.text(DesktopPins.PINS_ITEM))
    }

    @Test
    fun `with neither the Keychain item nor the file, no profile has a PIN`() {
        val pins = keychainPins()
        assertFalse(pins.hasPin(1))
        assertEquals(PinCheck.Right, pins.check(1, "0000"))
    }
}
