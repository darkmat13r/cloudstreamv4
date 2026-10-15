package com.lagradost.cloudstream4.core.storage

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The encoding probe: what a [KeyValueJsonStore] must do to a value written by
 * `Context.setKey` in `:app`, which is **nothing**.
 *
 * ## What `:app` writes
 *
 * `DataStore.kt:163` — `Context.setKey(path, value)` is
 * `getSharedPrefs().edit { putString(path, value?.toJsonLiteral()) }`, and `Context.getKey(path)`
 * is `getSharedPrefs().getString(path, null)` followed by `parseJson`. So a record is a `String`
 * holding a JSON **literal**, produced by `AppUtils.toJsonLiteral` in `:library`: kotlinx
 * `json.encodeToString` when the class has a serializer, Jackson `mapper.writeValueAsString`
 * otherwise.
 *
 * The one thing that catches people: `setKey` uses `toJsonLiteral`, not `toJson`. `toJson` returns
 * a `String` value unchanged; `toJsonLiteral` always encodes, so a `String` value is stored
 * **quoted** — `"hello"`, seven characters — because `getKey` will `parseJson` it back. A writer
 * that stores a bare `hello` writes a record `:app` cannot read.
 *
 * ## What this test proves, and what it does not
 *
 * [FIXTURES] are JSON records in the shapes `:app` stores, reproduced here as literals. They are
 * *representative*, not re-derived from `:library`: two of them deliberately differ from what
 * `toJsonLiteral` emits today (kotlinx runs with `explicitNulls = false`, so a `@Serializable`
 * class omits null fields, and non-ASCII is emitted literally). That changes nothing here: the
 * store is a verbatim carrier and these assertions only pin round-tripping.
 * The test asserts the store returns each one **character for character**, which is the
 * whole of this module's obligation: the encoding belongs to `:app` and this port is a transparent
 * carrier for it. The fixtures are deliberately not produced by calling into `:library` — adding
 * `:library` to any configuration of this module makes `cloudstream.kmp.base` apply its opt-in
 * markers to every compilation, including `commonMain` and the iOS canary where `:library` is not
 * on the classpath, which emits an unresolved-marker warning on every build.
 *
 * It does **not** prove that `AppUtils.toJsonLiteral` still produces these strings; that is
 * `:library`'s own contract and its own tests. And it does not exercise `SharedPreferences` — see
 * `AndroidStorage`'s KDoc.
 *
 * Every case goes through [newStore], so `FileJsonRecordEncodingTest` re-runs the whole fixture set
 * against the file-backed store — where "verbatim" has teeth, because the value is escaped on the
 * way to disk and unescaped on the way back.
 *
 * ## Why this suite is in `commonTest`
 *
 * It needs `kotlin.test` and this module's own `commonMain` types and nothing else, so
 * `iosSimulatorTest` (`INF-04`) runs it on a simulator as well as on the JVM. `commonTest` is a
 * parent source set of `jvmTest`, so the file-backed subclasses in `FileStoreContractTest` still
 * inherit every case. Backtick names here may not contain a comma — Kotlin/Native rejects it; see
 * `KeyValueJsonStoreTest` for the detail.
 */
open class JsonRecordEncodingTest {

    /** The store under test. A subclass overrides this and inherits every case below. */
    protected open fun newStore(): KeyValueJsonStore = InMemoryKeyValueJsonStore()

    private companion object {
        /**
         * Representative records as `Context.setKey` stores them; see the class KDoc on what is and
         * is not re-derived from `toJsonLiteral`.
         *
         * | value | how `toJsonLiteral` reaches it |
         * |---|---|
         * | `"hello"` | `String.serializer()` — quoted, *not* the bare string |
         * | `42` | `Int.serializer()` |
         * | `true` | `Boolean.serializer()` |
         * | a `@Serializable` data class | generated serializer, declaration order;
         *   `explicitNulls = false` omits null fields |
         * | an array of them | a `List` has no serializer of its own, so Jackson handles it,
         *   and Jackson *keeps* nulls |
         * | an escaped string | JSON escaping: a backslash, a newline and a `\u` escape must all
         *   survive the store untouched |
         */
        val FIXTURES: Map<String, String> = mapOf(
            "string" to "\"hello\"",
            "empty string" to "\"\"",
            "int" to "42",
            "negative int" to "-1",
            "long" to "9007199254740993",
            "double" to "1.5",
            "boolean" to "true",
            "json null" to "null",
            "object" to "{\"accessToken\":\"abc\",\"refreshToken\":null,\"expiresIn\":3600}",
            "array of objects" to "[{\"keyIndex\":0,\"name\":\"Default\",\"lockPin\":null}]",
            "empty array" to "[]",
            "escaped string" to "\"a\\\"b\\nc\\\\d\\u00e9\"",
            "unicode" to "\"日本語\"",
        )
    }

    @Test
    fun `every record round-trips character for character`() {
        val store = newStore()
        FIXTURES.forEach { (name, json) -> store.set(name, json) }
        FIXTURES.forEach { (name, json) ->
            assertEquals(json, store.get(name), "record '$name' was not stored verbatim")
        }
    }

    @Test
    fun `a batched write stores records verbatim too`() {
        val store = newStore()
        store.edit { FIXTURES.forEach { (name, json) -> set(name, json) } }
        FIXTURES.forEach { (name, json) -> assertEquals(json, store.get(name)) }
    }

    @Test
    fun `a string record is the quoted form that distinguishes toJsonLiteral from toJson`() {
        // Guards the fixture itself: if someone "tidies" this to "hello", the encoding note above
        // has been lost and a writer built on this store would produce unreadable records.
        assertEquals(7, FIXTURES.getValue("string").length)
        assertEquals('"', FIXTURES.getValue("string").first())
    }

    @Test
    fun `the json record file is the one app already uses`() {
        // Mirrors com.lagradost.cloudstream3.utils.PREFERENCES_NAME in :core:data, which this
        // module may not depend on (layer 2 from layer 1). Restated so a drift is at least visible
        // in a diff of this line.
        assertEquals("rebuild_preference", StorageFiles.JSON_RECORDS)
    }

    @Test
    fun `secret keys are the ones app writes today`() {
        // AccountManager.updateAccounts writes setKey(ACCOUNT_TOKEN, "$prefix/$currentAccount", …),
        // i.e. the flat key "auth_tokens/MAL/0". A SecretStore over the prefix must address it.
        val records = newStore()
        val secrets = KeyValueJsonSecretStore(records, prefix = "auth_tokens")

        secrets.set("MAL/0", FIXTURES.getValue("array of objects"))

        assertEquals(setOf("auth_tokens/MAL/0"), records.keys())
        assertEquals(FIXTURES.getValue("array of objects"), records.get("auth_tokens/MAL/0"))
    }
}
