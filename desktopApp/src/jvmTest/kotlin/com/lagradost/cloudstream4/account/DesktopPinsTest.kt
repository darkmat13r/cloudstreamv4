package com.lagradost.cloudstream4.account

import com.lagradost.cloudstream4.feature.account.PinLimit
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `KMP-95c`: the Mac's PINs and their limit, in a temporary directory on a fake boot clock. */
class DesktopPinsTest {
    private val directory: File = Files.createTempDirectory("pins-test").toFile()
    private var now = 5_000_000L

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun pins() = DesktopPins(directory, clock = { now }, onSaveFailed = { throw it })

    @Test
    fun `a PIN is set, a right one opens and clears the count, and a wrong one counts`() {
        val pins = pins()
        assertTrue(pins.set(1, "4821"))
        assertTrue(pins.hasPin(1))
        assertEquals(PinCheck.Wrong(0L), pins.check(1, "0000"))
        assertEquals(PinCheck.Right, pins.check(1, "4821"))
        repeat(4) { assertEquals(PinCheck.Wrong(0L), pins.check(1, "0000")) }
        assertEquals(PinCheck.Wrong(30_000L), pins.check(1, "0000"), "the count started again after the right one")
    }

    @Test
    fun `during a wait nothing is compared, the right PIN included`() {
        val pins = pins()
        pins.set(1, "4821")
        repeat(5) { pins.check(1, "0000") }
        assertEquals(PinCheck.Waiting(30_000L), pins.check(1, "4821"))
        now += 30_000L
        assertEquals(PinCheck.Right, pins.check(1, "4821"))
    }

    @Test
    fun `the first profile is never locked, and only four digits are a PIN`() {
        val pins = pins()
        assertFalse(pins.set(StoredProfiles.FIRST, "1234"))
        listOf("123", "12345", "12a4", "١٢٣٤", " 1234").forEach { assertFalse(pins.set(2, it), "not a PIN: '$it'") }
        assertFalse(pins.hasPin(2))
    }

    @Test
    fun `a PIN and its wait are kept across a restart, in owner-only files apart from the profiles`() {
        val pins = pins()
        pins.set(1, "4821")
        repeat(5) { pins.check(1, "0000") }
        now += 10_000L
        val next = pins()
        assertTrue(next.hasPin(1))
        assertEquals(20_000L, next.waitMs(1))
        listOf(DesktopPins.PINS_FILE, DesktopPins.ATTEMPTS_FILE).forEach { name ->
            val mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(File(directory, name).toPath()))
            assertEquals("rw-------", mode, name)
        }
        assertFalse(File(directory, DesktopPins.ATTEMPTS_FILE).readText().contains("4821"), "the attempts file holds no PIN")
        assertFalse(directory.listFiles().orEmpty().any { it.name.endsWith(".tmp") })
    }

    @Test
    fun `the PIN is never in profiles json`() {
        val file = DesktopProfileFile(File(directory, DesktopProfileFile.FILE_NAME))
        file.save(StoredProfiles(profiles = listOf(StoredProfile(0), StoredProfile(1, "Sam"))))
        val profiles = DesktopProfiles(file, libraryDirectory = directory, pins = pins())
        profiles.setPin(1, "4821")
        profiles.rename(1, "Samira")
        assertFalse(File(directory, DesktopProfileFile.FILE_NAME).readText().contains("4821"))
        assertTrue(profiles.cards("Default")[1].locked)
    }

    @Test
    fun `a pins file that cannot be read reads as no PINs, as a deleted one would`() {
        File(directory, DesktopPins.PINS_FILE).writeText("not json")
        assertFalse(pins().hasPin(1))
        File(directory, DesktopPins.PINS_FILE).writeText("{\"pins\":{\"1\":\"12\",\"0\":\"1234\",\"x\":\"1234\",\"2\":\"5678\"}}")
        val pins = pins()
        assertFalse(pins.hasPin(1), "not four digits")
        assertFalse(pins.hasPin(0), "the first profile is never locked")
        assertTrue(pins.hasPin(2))
    }

    @Test
    fun `an attempts record out of range reads as the strictest wait, never a free one`() {
        File(directory, DesktopPins.ATTEMPTS_FILE).writeText("{\"attempts\":{\"1\":{\"wrong\":-3,\"startedAt\":${now},\"waitMs\":-1}}}")
        assertEquals(PinLimit.MAX_WAIT_MS, pins().waitMs(1))
    }

    @Test
    fun `deleting a profile takes its PIN and its count, and the launch sweep those of profiles no longer listed`() {
        val file = DesktopProfileFile(File(directory, DesktopProfileFile.FILE_NAME))
        file.save(StoredProfiles(profiles = listOf(StoredProfile(0), StoredProfile(1, "Sam"), StoredProfile(2, "Robin"))))
        val pins = pins()
        val profiles = DesktopProfiles(file, libraryDirectory = directory, pins = pins)
        profiles.setPin(1, "4821")
        profiles.setPin(2, "1357")
        repeat(5) { pins.check(2, "0000") }
        assertTrue(profiles.delete(2))
        assertFalse(pins.hasPin(2))
        assertEquals(0L, pins.waitMs(2))

        // As if Robin's delete had been written but its PIN had not gone: the next launch's sweep takes it.
        pins.set(2, "1357")
        val next = DesktopProfiles(file, libraryDirectory = directory, pins = pins())
        assertFalse(next.pins.hasPin(2))
        assertTrue(next.pins.hasPin(1))
    }

    @Test
    fun `a profiles file that is not exactly this version's list sweeps no PIN`() {
        File(directory, DesktopProfileFile.FILE_NAME).writeText("{}")
        pins().set(3, "2468")
        val profiles = DesktopProfiles(DesktopProfileFile(File(directory, DesktopProfileFile.FILE_NAME)), libraryDirectory = directory, pins = pins())
        assertTrue(profiles.pins.hasPin(3))
    }

    @Test
    fun `a set or remove that cannot be written changes nothing, in memory or on disk`() {
        val failures = mutableListOf<Throwable>()
        val pins = DesktopPins(directory, clock = { now }, onSaveFailed = { failures += it })
        assertTrue(pins.set(1, "4821"))
        val before = File(directory, DesktopPins.PINS_FILE).readText()
        directory.setWritable(false)
        try {
            assertFalse(pins.set(2, "1357"))
            assertFalse(pins.hasPin(2), "not locked when it would open after a restart")
            assertFalse(pins.remove(1))
            assertTrue(pins.hasPin(1), "still locked, as it will be after a restart")
            assertEquals(PinCheck.Wrong(0L), pins.check(1, "0000"))
        } finally {
            directory.setWritable(true)
        }
        assertEquals(before, File(directory, DesktopPins.PINS_FILE).readText())
        assertEquals(3, failures.size, "the set, the remove, and the wrong PIN's count, each reported")
    }

    @Test
    fun `a check compares with the file, not a copy held since launch`() {
        val pins = pins()
        pins.set(1, "4821")
        pins().set(1, "2468") // as another window of the app would
        assertEquals(PinCheck.Right, pins.check(1, "2468"))
        File(directory, DesktopPins.PINS_FILE).delete()
        assertEquals(PinCheck.Right, pins.check(1, "0000"), "a deleted file is no PIN")
        assertFalse(pins.hasPin(1))
    }

    @Test
    fun `a new profile on a reused id never inherits a deleted profile's PIN or count`() {
        val file = DesktopProfileFile(File(directory, DesktopProfileFile.FILE_NAME))
        // A key this version does not know: the file loads, but the launch sweep removes nothing over it (#281).
        File(directory, DesktopProfileFile.FILE_NAME).writeText(
            """{"version":1,"current":0,"profiles":[{"id":0},{"id":1,"name":"Sam"}],"from":"a later version"}""",
        )
        // Left by a profile 2 whose delete was written but whose PIN was not removed.
        pins().set(2, "1357")
        repeat(5) { pins().check(2, "0000") }
        val profiles = DesktopProfiles(file, libraryDirectory = directory, pins = pins())
        assertEquals(2, profiles.add("Robin", fallback = "Profile 3"))
        assertFalse(profiles.pins.hasPin(2), "Robin is not locked by the PIN of the profile before")
        assertEquals(0L, profiles.pins.waitMs(2))
        assertFalse(profiles.cards("Default").first { it.id == 2 }.locked)
    }

    @Test
    fun `a wait reads as A4 shows it, rounded up to the second`() {
        assertEquals("00:30", waitClock(30_000L))
        assertEquals("00:30", waitClock(29_001L))
        assertEquals("00:01", waitClock(1L))
        assertEquals("59:12", waitClock(3_552_000L))
        assertEquals("60:00", waitClock(PinLimit.MAX_WAIT_MS))
    }
}
