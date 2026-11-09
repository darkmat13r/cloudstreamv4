package com.lagradost.cloudstream4.account

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `KMP-95f`: a signed build's sign-ins, over [FakeVault]. They outlive a restart, and a Keychain that refuses fails
 * closed. Every email, password and token here is made up.
 */
class KeychainSignInStoreTest {
    private val vault = FakeVault()

    @Test
    fun `a sign-in outlives a restart, the OpenSubtitles password with it, and nothing says it ends at quit`() {
        val store = KeychainSignInStore(vault)
        assertTrue(store.lastsAfterQuit)
        assertTrue(store.put(0, DesktopService.OpenSubtitles, Credential(TOKEN, EMAIL, PASSWORD)))
        assertTrue(store.put(1, DesktopService.MyAnimeList, Credential(TOKEN, refreshToken = REFRESH)))

        val restarted = KeychainSignInStore(vault)
        val subtitles = checkNotNull(restarted.get(0, DesktopService.OpenSubtitles))
        assertEquals(TOKEN, subtitles.token)
        assertEquals(EMAIL, subtitles.email)
        assertEquals(PASSWORD, subtitles.password, "kept to renew its token (the owner's ruling)")
        assertEquals(REFRESH, checkNotNull(restarted.get(1, DesktopService.MyAnimeList)).refreshToken)
        assertNull(restarted.get(1, DesktopService.OpenSubtitles), "one profile's sign-in is not another's")
        assertEquals(setOf(KeychainSignInStore.ITEM), vault.items.keys, "one item, every profile's")
    }

    @Test
    fun `a write the Keychain refuses keeps nothing, anywhere, and says so`() {
        val store = KeychainSignInStore(vault, onSaveFailed = { failed++ })
        vault.refuseWrites = true
        assertFalse(store.put(0, DesktopService.Simkl, Credential(TOKEN)))
        assertNull(store.get(0, DesktopService.Simkl), "not signed in: not even until quit")
        assertEquals(1, failed)
        vault.refuseWrites = false
        assertNull(KeychainSignInStore(vault).get(0, DesktopService.Simkl))
    }

    private var failed = 0

    @Test
    fun `a Keychain that cannot be read shows no sign-in and changes nothing, so it never wipes the others`() {
        KeychainSignInStore(vault).apply {
            put(0, DesktopService.Simkl, Credential(TOKEN))
            put(1, DesktopService.SubDl, Credential(TOKEN, EMAIL))
        }
        val kept = vault.text(KeychainSignInStore.ITEM)
        vault.refuseReads = true
        val store = KeychainSignInStore(vault)
        assertNull(store.get(0, DesktopService.Simkl))
        val writes = vault.writes
        assertFalse(store.put(1, DesktopService.Simkl, Credential(TOKEN)))
        assertFalse(store.remove(0, DesktopService.Simkl))
        assertFalse(store.forget(1))
        assertFalse(store.retainOnly(setOf(0)))
        assertEquals(writes, vault.writes, "nothing was written while the item could not be read")
        assertEquals(kept, vault.text(KeychainSignInStore.ITEM))

        vault.refuseReads = false
        assertTrue(store.put(1, DesktopService.Simkl, Credential(TOKEN)), "each change reads again")
        assertNotNull(store.get(0, DesktopService.Simkl), "the others came back with it")
        assertNotNull(store.get(1, DesktopService.SubDl))
    }

    @Test
    fun `an item that is not ours reads as unreadable, never as none`() {
        vault.items[KeychainSignInStore.ITEM] = "not json".toByteArray()
        val store = KeychainSignInStore(vault)
        assertFalse(store.put(0, DesktopService.Simkl, Credential(TOKEN)))
        assertEquals("not json", vault.text(KeychainSignInStore.ITEM), "not overwritten")
    }

    @Test
    fun `what is read back is checked as a token is, and one that is not is dropped`() {
        vault.items[KeychainSignInStore.ITEM] = """{"profiles":{"0":{"Simkl":{"token":"bad\r\ntoken"},"SubDl":{"token":"$TOKEN"}},"x":{}}}""".toByteArray()
        val store = KeychainSignInStore(vault)
        assertNull(store.get(0, DesktopService.Simkl))
        assertNotNull(store.get(0, DesktopService.SubDl))
        assertFalse(store.put(0, DesktopService.Simkl, Credential("bad\ntoken")), "nor is one kept")
    }

    @Test
    fun `remove, forget and retainOnly write what is left, and a refused one leaves it as it was`() {
        val store = KeychainSignInStore(vault)
        store.put(0, DesktopService.Simkl, Credential(TOKEN))
        store.put(1, DesktopService.Simkl, Credential(TOKEN))
        store.put(2, DesktopService.SubDl, Credential(TOKEN, EMAIL))
        vault.refuseWrites = true
        assertFalse(store.remove(0, DesktopService.Simkl))
        assertNotNull(store.get(0, DesktopService.Simkl), "still signed in, as the screen then says")
        assertFalse(store.forget(1))
        assertNotNull(store.get(1, DesktopService.Simkl))
        vault.refuseWrites = false
        assertTrue(store.remove(0, DesktopService.Simkl))
        assertTrue(store.forget(1))
        assertTrue(store.retainOnly(setOf(0, 1)))
        val restarted = KeychainSignInStore(vault)
        assertNull(restarted.get(0, DesktopService.Simkl))
        assertNull(restarted.get(1, DesktopService.Simkl))
        assertNull(restarted.get(2, DesktopService.SubDl), "retainOnly dropped a profile that is gone")
        assertTrue(restarted.forget(5), "nothing to forget is not a failure")
    }

    @Test
    fun `the store names nothing it holds`() {
        val store = KeychainSignInStore(vault)
        store.put(0, DesktopService.OpenSubtitles, Credential(TOKEN, EMAIL, PASSWORD))
        listOf(TOKEN, EMAIL, PASSWORD).forEach { assertFalse(it in store.toString()) }
        assertFalse(TOKEN in (vault.read(KeychainSignInStore.ITEM)).toString())
    }

    private companion object {
        const val TOKEN = "token-fixture-0123456789"
        const val REFRESH = "refresh-fixture-0123456789"
        const val EMAIL = "someone@example.invalid"
        const val PASSWORD = "password-fixture"
    }
}
