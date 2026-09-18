package com.lagradost.cloudstream4.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The behavioural contract of [SecretStore].
 *
 * [KeyValueJsonSecretStore] is `commonMain` code over a [KeyValueJsonStore], so this covers the
 * whole of the implementation `:app` will use on Android — everything except the
 * `SharedPreferences` layer underneath it. [InMemorySecretStore] is held to the same contract.
 */
class SecretStoreTest {

    private fun stores(): List<SecretStore> = listOf(
        InMemorySecretStore(),
        KeyValueJsonSecretStore(InMemoryKeyValueJsonStore(), prefix = "auth_tokens"),
    )

    @Test
    fun `an absent secret reads as null`() {
        stores().forEach { store ->
            assertNull(store.get("MAL/0"), "${store::class.simpleName}")
            assertFalse(store.contains("MAL/0"))
        }
    }

    @Test
    fun `set then get returns the secret verbatim`() {
        stores().forEach { store ->
            store.set("MAL/0", "[{\"accessToken\":\"abc\"}]")
            assertEquals("[{\"accessToken\":\"abc\"}]", store.get("MAL/0"))
            assertTrue(store.contains("MAL/0"))
        }
    }

    @Test
    fun `remove deletes one secret and leaves the others`() {
        stores().forEach { store ->
            store.set("MAL/0", "a")
            store.set("AniList/0", "b")
            store.remove("MAL/0")
            assertNull(store.get("MAL/0"))
            assertEquals("b", store.get("AniList/0"))
        }
    }

    @Test
    fun `keys round-trip through get`() {
        stores().forEach { store ->
            store.set("MAL/0", "a")
            store.set("AniList/1", "b")
            assertEquals(setOf("MAL/0", "AniList/1"), store.keys())
            store.keys().forEach { key -> assertTrue(store.get(key) != null, "lost $key") }
        }
    }

    @Test
    fun `clear removes everything`() {
        stores().forEach { store ->
            store.set("MAL/0", "a")
            store.set("AniList/1", "b")
            store.clear()
            assertEquals(emptySet<String>(), store.keys())
            assertNull(store.get("MAL/0"))
        }
    }

    @Test
    fun `every implementation states its protection, and today none encrypts`() {
        // The point of SecretProtection: a caller can ask, and the answer is not flattering.
        stores().forEach { store ->
            assertEquals(SecretProtection.PLAINTEXT, store.protection, "${store::class.simpleName}")
        }
    }

    // --- KeyValueJsonSecretStore: where the bytes actually land ---

    @Test
    fun `secrets land under the prefix, as flat keys app already writes`() {
        val records = InMemoryKeyValueJsonStore()
        val secrets = KeyValueJsonSecretStore(records, prefix = "auth_tokens")

        secrets.set("MAL/0", "[]")

        // AccountManager.updateAccounts writes setKey("auth_tokens", "MAL/0", array) → this key.
        assertEquals(setOf("auth_tokens/MAL/0"), records.keys())
    }

    @Test
    fun `it reads secrets an existing install already has`() {
        // No migration: the tokens are where :app left them.
        val records = InMemoryKeyValueJsonStore(
            mapOf(
                "auth_tokens/MAL/0" to "[{\"accessToken\":\"existing\"}]",
                "data_store_helper/account" to "[{\"keyIndex\":0}]",
            ),
        )
        val secrets = KeyValueJsonSecretStore(records, prefix = "auth_tokens")

        assertEquals("[{\"accessToken\":\"existing\"}]", secrets.get("MAL/0"))
        assertEquals(setOf("MAL/0"), secrets.keys())
    }

    @Test
    fun `it touches nothing outside its prefix`() {
        val records = InMemoryKeyValueJsonStore(
            mapOf("auth_tokens/MAL/0" to "a", "auth_ids/MAL/0" to "1", "unrelated" to "x"),
        )
        val secrets = KeyValueJsonSecretStore(records, prefix = "auth_tokens")

        secrets.clear()

        assertEquals(setOf("auth_ids/MAL/0", "unrelated"), records.keys())
    }

    @Test
    fun `a sibling prefix is not part of the folder`() {
        // "auth_tokens_backup" must not be swept up by a store scoped to "auth_tokens".
        val records = InMemoryKeyValueJsonStore(
            mapOf("auth_tokens/MAL/0" to "a", "auth_tokens_backup/MAL/0" to "b"),
        )
        val secrets = KeyValueJsonSecretStore(records, prefix = "auth_tokens")

        assertEquals(setOf("MAL/0"), secrets.keys())
        secrets.clear()
        assertEquals(setOf("auth_tokens_backup/MAL/0"), records.keys())
    }
}
