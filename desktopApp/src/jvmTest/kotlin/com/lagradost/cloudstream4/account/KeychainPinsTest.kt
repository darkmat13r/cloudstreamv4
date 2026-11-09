package com.lagradost.cloudstream4.account

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `KMP-95f`: a signed build's PINs, in the Keychain ([FakeVault]), and the one move of D1's file there. The file goes
 * only once what the Keychain gives back is what was written (the PM, 2026-11-09).
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

    @Test
    fun `a PIN set on a signed build is in the Keychain, never a file, and outlives a restart`() {
        val pins = keychainPins()
        assertTrue(pins.set(1, "4821"))
        assertFalse(pinsFile.exists(), "no PINs file")
        assertTrue(vault.text(DesktopPins.PINS_ITEM).orEmpty().contains("4821"))
        val next = keychainPins()
        assertTrue(next.hasPin(1))
        assertEquals(PinCheck.Right, next.check(1, "4821"))
        assertTrue(next.remove(1))
        assertFalse(keychainPins().hasPin(1))
    }

    @Test
    fun `D1's file moves to the Keychain, and is deleted only once the read-back matches`() {
        filePins().apply {
            set(1, "4821")
            set(2, "1357")
        }
        assertTrue(pinsFile.exists())
        val pins = keychainPins()
        assertFalse(pinsFile.exists(), "moved, so the file is gone")
        assertTrue(pins.hasPin(1) && pins.hasPin(2))
        assertEquals(PinCheck.Right, pins.check(2, "1357"))
        assertEquals(PinCheck.Right, keychainPins().check(1, "4821"), "from the Keychain now")
    }

    @Test
    fun `a move the Keychain refuses keeps the file, every profile stays locked, and a change is refused`() {
        filePins().set(1, "4821")
        val before = pinsFile.readText()
        vault.refuseWrites = true
        val pins = keychainPins()
        assertTrue(pinsFile.exists(), "a failed write keeps the file")
        assertEquals(before, pinsFile.readText())
        assertTrue(pins.hasPin(1), "still locked, read from the file")
        assertEquals(PinCheck.Wrong(0L), pins.check(1, "0000"))
        assertEquals(PinCheck.Right, pins.check(1, "4821"))
        assertFalse(pins.set(2, "1111"), "nowhere to write: refused, not put in the file")
        assertFalse(pins.remove(1))
        assertEquals(before, pinsFile.readText(), "the file is never written on a signed build")
        assertTrue(failures > 0, "the refusal was reported")

        vault.refuseWrites = false
        val later = keychainPins()
        assertFalse(pinsFile.exists(), "moved on the next try")
        assertEquals(PinCheck.Right, later.check(1, "4821"))
    }

    @Test
    fun `a read-back that does not match keeps the file`() {
        filePins().set(1, "4821")
        vault.readBack = """{"pins":{"1":"0000"}}""".toByteArray()
        keychainPins()
        assertTrue(pinsFile.exists(), "not what was written, so the file stays")
        assertFalse(DesktopPins.PINS_ITEM in vault.items, "and the item that came back different is removed")
        vault.readBack = null
        assertEquals(PinCheck.Right, keychainPins().check(1, "4821"), "the file's PIN, moved on the next launch")
        assertFalse(pinsFile.exists())
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
    fun `once the Keychain holds the PINs, a file left by a failed delete goes unread`() {
        keychainPins().set(1, "4821")
        pinsFile.writeText("""{"pins":{"1":"0000","3":"9999"}}""")
        val pins = keychainPins()
        assertFalse(pinsFile.exists())
        assertFalse(pins.hasPin(3), "its PINs are not read")
        assertEquals(PinCheck.Right, pins.check(1, "4821"))
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
