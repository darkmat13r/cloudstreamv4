package com.lagradost.cloudstream4.core.storage

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * One typed entry of a [PreferenceStore]: a live handle on a key, not a snapshot of its value.
 *
 * Deliberately the same shape as `com.mihon.common.preference.PreferenceData` in `:shared`, which
 * the 7 Compose settings screens are written against. See [PreferenceStore] for why there are two
 * of these today and what Phase 5 does about it.
 */
interface PreferenceData<T> {

    fun key(): String

    /** The stored value, or [defaultValue] when nothing is stored (or what is stored is unreadable). */
    fun get(): T

    fun set(value: T)

    /** Whether the backing store holds this key at all — `false` does not mean "equal to the default". */
    fun isSet(): Boolean

    fun delete()

    fun defaultValue(): T

    /** Emits the current value, then again on every change to this key. */
    fun changes(): Flow<T>

    fun stateIn(scope: CoroutineScope): StateFlow<T>

    companion object {
        /**
         * A preference that should not be exposed in places like backups without user consent.
         *
         * This is a naming convention over the settings file, **not** a protection mechanism: the
         * value is stored in plain text like any other. Secrets belong in [SecretStore], which is
         * equally unencrypted today but says so in one place.
         */
        fun isPrivate(key: String): Boolean = key.startsWith(PRIVATE_PREFIX)

        fun privateKey(key: String): String = "$PRIVATE_PREFIX$key"

        /**
         * A preference used for internal app state that isn't really a user preference and
         * therefore should not be in places like backups.
         */
        fun isAppState(key: String): Boolean = key.startsWith(APP_STATE_PREFIX)

        fun appStateKey(key: String): String = "$APP_STATE_PREFIX$key"

        /**
         * Byte-compatible with `com.mihon.common.preference.PreferenceData` in `:shared`: the
         * prefixes are part of the key strings already written to the settings file, so they are
         * restated here verbatim rather than renamed.
         */
        private const val APP_STATE_PREFIX = "__APP_STATE_"
        private const val PRIVATE_PREFIX = "__PRIVATE_"
    }
}

inline fun <reified T, R : T> PreferenceData<T>.getAndSet(crossinline block: (T) -> R) = set(block(get()))

operator fun <T> PreferenceData<Set<T>>.plusAssign(item: T) {
    set(get() + item)
}

operator fun <T> PreferenceData<Set<T>>.plusAssign(items: Iterable<T>) {
    set(get() + items)
}

operator fun <T> PreferenceData<Set<T>>.minusAssign(item: T) {
    set(get() - item)
}

fun PreferenceData<Boolean>.toggle(): Boolean {
    set(!get())
    return get()
}
