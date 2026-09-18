package com.lagradost.cloudstream4.core.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.nio.file.Path

/**
 * [PreferenceStore] in a file, for the JVM (Compose for Desktop).
 *
 * The counterpart of `AndroidPreferenceStore`. `SharedPreferences` stores a typed value per key;
 * this stores the same value as a **type-tagged string** in a [TextRecordFile], so a `Long` comes
 * back a `Long` and a value written as a `String` does not read as an `Int`.
 *
 * ## The encoding
 *
 * One character of type, a `:`, then the value. [TextRecordFile] does the escaping, so the payload
 * below is the plain text of the value and any character may appear in it:
 *
 * | tag | type | payload |
 * |---|---|---|
 * | `S` | `String` | the string itself |
 * | `L` | `Long` | `toString` |
 * | `I` | `Int` | `toString` |
 * | `F` | `Float` | `toString` |
 * | `B` | `Boolean` | `true` / `false` |
 * | `X` | `Set<String>` | each member escaped and followed by `,` — so the empty set is empty and a set holding one empty string is `,` |
 *
 * This is **not** the JSON-record encoding: settings are primitives on both platforms, exactly as
 * `SharedPreferences` keeps them, and this module still owns no JSON codec.
 *
 * ## Parity with Android
 *
 * Same key strings (including the `__PRIVATE_` and `__APP_STATE_` prefixes, which are part of the
 * key and not of this layer), same defaults for a missing key, same "a value of the wrong type
 * reads as the default", and the same [PreferenceData.changes] behaviour: a collector sees the
 * current value immediately and then every later write to that key.
 *
 * Two differences, both consequences of the file and both visible only to a second writer:
 *
 * - Android's `Entry.get()` **deletes** an entry whose stored type is wrong, because
 *   `SharedPreferences` throws and `:shared` self-heals that way. Here a wrong tag simply reads as
 *   the default and the value stays; a reader does not mutate the store. Nothing writes a wrong tag
 *   in the first place — only hand-editing the file can produce one.
 * - `changes()` observes **this instance's** writes. `SharedPreferences` notifies every listener in
 *   the process because the framework hands out one instance per file; here that guarantee comes
 *   from [JvmStorage] handing out one instance per file. Another process writing the file is not
 *   observed at all — see [TextRecordFile]'s concurrency note.
 *
 * @param file the store's own file. [JvmStorage.settings] resolves the usual one.
 * @param onCorruption told when the file could not be read; see [StorageCorruptionListener].
 */
class FilePreferenceStore internal constructor(
    private val records: TextRecordFile,
) : PreferenceStore {

    constructor(
        file: Path,
        onCorruption: StorageCorruptionListener = StorageCorruptionListener.STDERR,
    ) : this(TextRecordFile(file, onCorruption))

    /** Where the settings are, for diagnostics and for tests. */
    val file: Path get() = records.path

    /** Emits the key that changed. `extraBufferCapacity` so a `set` never suspends. */
    private val keyChanges = MutableSharedFlow<String>(extraBufferCapacity = 64)

    override fun getString(key: String, defaultValue: String): PreferenceData<String> =
        Entry(key, defaultValue, { it.payload(STRING) }, { tagged(STRING, it) })

    override fun getLong(key: String, defaultValue: Long): PreferenceData<Long> =
        Entry(key, defaultValue, { it.payload(LONG)?.toLongOrNull() }, { tagged(LONG, it.toString()) })

    override fun getInt(key: String, defaultValue: Int): PreferenceData<Int> =
        Entry(key, defaultValue, { it.payload(INT)?.toIntOrNull() }, { tagged(INT, it.toString()) })

    override fun getFloat(key: String, defaultValue: Float): PreferenceData<Float> =
        Entry(key, defaultValue, { it.payload(FLOAT)?.toFloatOrNull() }, { tagged(FLOAT, it.toString()) })

    override fun getBoolean(key: String, defaultValue: Boolean): PreferenceData<Boolean> =
        Entry(
            key = key,
            defaultValue = defaultValue,
            read = { stored ->
                when (stored.payload(BOOLEAN)) {
                    "true" -> true
                    "false" -> false
                    else -> null
                }
            },
            write = { tagged(BOOLEAN, it.toString()) },
        )

    override fun getStringSet(key: String, defaultValue: Set<String>): PreferenceData<Set<String>> =
        Entry(key, defaultValue, { it.readStringSet() }, { tagged(STRING_SET, encodeSet(it)) })

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): PreferenceData<T> = Entry(
        key = key,
        defaultValue = defaultValue,
        read = { stored -> stored.payload(STRING)?.let { runCatching { deserializer(it) }.getOrNull() } },
        write = { tagged(STRING, serializer(it)) },
    )

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): PreferenceData<T> = Entry(
        key = key,
        defaultValue = defaultValue,
        read = { stored ->
            stored.payload(INT)?.toIntOrNull()?.let { runCatching { deserializer(it) }.getOrNull() }
        },
        write = { tagged(INT, serializer(it).toString()) },
    )

    override fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ): PreferenceData<Set<T>> = Entry(
        key = key,
        defaultValue = defaultValue,
        read = { stored ->
            stored.readStringSet()?.mapNotNull { runCatching { deserializer(it) }.getOrNull() }?.toSet()
        },
        write = { tagged(STRING_SET, encodeSet(it.map(serializer))) },
    )

    /**
     * Every stored key with its value decoded back to the type it was written as — `String`, `Long`,
     * `Int`, `Float`, `Boolean` or `Set<String>`, the same types `SharedPreferences.getAll` yields.
     * A record whose tag is unreadable is left out rather than reported as a raw string.
     */
    override fun getAll(): Map<String, *> = records.snapshot().mapNotNull { (key, stored) ->
        decode(stored)?.let { key to it }
    }.toMap()

    private fun decode(stored: String): Any? {
        val payload = stored.payloadOrNull() ?: return null
        return when (stored.first()) {
            STRING -> payload
            LONG -> payload.toLongOrNull()
            INT -> payload.toIntOrNull()
            FLOAT -> payload.toFloatOrNull()
            BOOLEAN -> payload.toBooleanStrictOrNull()
            STRING_SET -> decodeSet(payload)
            else -> null
        }
    }

    private inner class Entry<T>(
        private val key: String,
        private val defaultValue: T,
        private val read: (String) -> T?,
        private val write: (T) -> String,
    ) : PreferenceData<T> {

        override fun key(): String = key

        override fun get(): T = records.get(key)?.let(read) ?: defaultValue

        override fun set(value: T) {
            notify(records.edit { it[key] = write(value) })
        }

        override fun isSet(): Boolean = records.contains(key)

        override fun delete() {
            notify(records.edit { it.remove(key) })
        }

        override fun defaultValue(): T = defaultValue

        override fun changes(): Flow<T> = keyChanges
            .filter { it == key }
            .onStart { emit(key) }
            .map { get() }
            .conflate()

        override fun stateIn(scope: CoroutineScope): StateFlow<T> =
            changes().stateIn(scope, SharingStarted.Eagerly, get())

        /**
         * Emitted after the write has landed on disk, so a collector woken by it reads a value that
         * has already been persisted. Writing the same value again changes nothing and notifies
         * nobody, which is what a `SharedPreferences` listener does too.
         */
        private fun notify(changed: Set<String>) {
            for (changedKey in changed) keyChanges.tryEmit(changedKey)
        }
    }

    private companion object {
        const val STRING = 'S'
        const val LONG = 'L'
        const val INT = 'I'
        const val FLOAT = 'F'
        const val BOOLEAN = 'B'
        const val STRING_SET = 'X'

        fun tagged(tag: Char, payload: String): String = "$tag:$payload"

        /** The payload when the stored value carries [tag], `null` for any other tag or shape. */
        fun String.payload(tag: Char): String? =
            if (length >= 2 && this[0] == tag && this[1] == ':') substring(2) else null

        /** The payload whatever the tag, for [getAll]. */
        fun String.payloadOrNull(): String? = if (length >= 2 && this[1] == ':') substring(2) else null

        fun String.readStringSet(): Set<String>? = payload(STRING_SET)?.let(::decodeSet)

        /**
         * Each member escaped and terminated by `,`, so the empty set and the set of one empty
         * string are not the same text. [TextRecordFile] escapes `,` itself, which is why a member
         * containing a comma survives.
         */
        fun encodeSet(members: Iterable<String>): String =
            members.joinToString(separator = "") { escape(it) + "," }

        fun decodeSet(payload: String): Set<String>? = runCatching {
            payload.splitUnescaped(',')
                // The encoding terminates every member, so the text after the last `,` is empty.
                .dropLast(1)
                .map(::unescape)
                .toSet()
        }.getOrNull()
    }
}
