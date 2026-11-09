package com.lagradost.cloudstream4.account

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `KMP-95f`: which stores a build gets, decided when it was built (the PM, 2026-11-09). Both kinds, over [FakeVault]:
 * nothing here reaches the real Keychain.
 */
class AccountStorageTest {
    private val directory: File = Files.createTempDirectory("account-storage").toFile()

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    @Test
    fun `a signed build keeps PINs and sign-ins in the Keychain only, with no way to the in-memory store or the file`() {
        val vault = FakeVault()
        val storage = AccountStorage.forBuild(signed = true, directory = directory, vault = { vault })
        assertIs<KeychainSignInStore>(storage.signIns)
        assertTrue(storage.signIns.lastsAfterQuit)
        assertTrue(storage.signIns.put(0, DesktopService.Simkl, Credential("token-fixture")))
        assertTrue(storage.pins.set(1, "4821"))
        assertEquals(setOf(KeychainSignInStore.ITEM, DesktopPins.PINS_ITEM), vault.items.keys)
        assertFalse(File(directory, DesktopPins.PINS_FILE).exists())

        // The Keychain refuses everything: nothing signs in, nothing is locked open, and nothing falls back.
        val refusing = FakeVault().apply {
            refuseReads = true
            refuseWrites = true
        }
        val failing = AccountStorage.forBuild(signed = true, directory = directory, vault = { refusing })
        assertFalse(failing.signIns.put(0, DesktopService.Simkl, Credential("token-fixture")))
        assertEquals(null, failing.signIns.get(0, DesktopService.Simkl))
        assertTrue(failing.pins.hasPin(1))
        assertEquals(PinCheck.Unreadable, failing.pins.check(1, "4821"))
        assertFalse(File(directory, DesktopPins.PINS_FILE).exists(), "no file instead")
    }

    @Test
    fun `an unsigned build keeps 95d's in-memory sign-ins and D1's file, and never makes a Keychain`() {
        val storage = AccountStorage.forBuild(
            signed = false,
            directory = directory,
            vault = { error("an unsigned build must not reach the Keychain") },
        )
        assertIs<InMemorySignInStore>(storage.signIns)
        assertFalse(storage.signIns.lastsAfterQuit, "so the window says sign-ins last until quit")
        assertTrue(storage.pins.set(1, "4821"))
        assertTrue(File(directory, DesktopPins.PINS_FILE).exists())
    }

    @Test
    fun `a test build is unsigned, even on a Mac with the signing values, so nothing reaches the Keychain through the app`() {
        // Only `-Pcloudstream.sign=true` on a packaging run makes a signed build, and the build refuses it for a test.
        assertFalse(DesktopSecrets.signedBuild)
    }

    @Test
    fun `a deleted profile's Keychain sign-ins go, a reused id starts clean, and the launch sweep drops the rest`() {
        val vault = FakeVault()
        val file = DesktopProfileFile(File(directory, DesktopProfileFile.FILE_NAME))
        file.save(StoredProfiles(profiles = listOf(StoredProfile(0, "Alex"), StoredProfile(1, "Sam"))))
        fun profiles(): DesktopProfiles {
            val storage = AccountStorage.forBuild(signed = true, directory = directory, vault = { vault })
            return DesktopProfiles(file, libraryDirectory = directory, pins = storage.pins, signIns = storage.signIns)
        }
        // A profile 2 that is no longer in the file, as a delete whose forget the Keychain refused leaves it.
        KeychainSignInStore(vault).put(2, DesktopService.Simkl, Credential("token-fixture"))
        val first = profiles()
        assertEquals(null, first.signIns.get(2, DesktopService.Simkl), "the launch sweep dropped it")

        first.signIns.put(1, DesktopService.Simkl, Credential("token-fixture"))
        assertTrue(first.delete(1))
        assertEquals(null, profiles().signIns.get(1, DesktopService.Simkl), "gone after a restart too")

        // A delete whose forget the Keychain refuses leaves the sign-in under id 1, the id the next profile takes:
        // while it cannot be forgotten, nothing is added, so a new profile never inherits it.
        val next = profiles()
        next.add("Sam", "Profile")
        next.signIns.put(1, DesktopService.SubDl, Credential("token-fixture", "someone@example.invalid"))
        vault.refuseWrites = true
        assertTrue(next.delete(1))
        assertEquals(null, next.add("Robin", "Profile"))
        assertEquals(1, next.stored.profiles.size)
        vault.refuseWrites = false
        val id = checkNotNull(next.add("Robin", "Profile"))
        assertEquals(1, id)
        assertEquals(null, next.signIns.get(id, DesktopService.SubDl))
        assertEquals(null, profiles().signIns.get(id, DesktopService.SubDl))
    }
}
