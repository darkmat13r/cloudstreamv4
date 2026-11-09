package com.lagradost.cloudstream4.account

/** What reading one secret came to. "Couldn't read" is never "not there", as with the PINs file (the reviewer of `#286`). */
internal sealed interface VaultRead {
    /** Nothing is kept under that name. */
    data object Absent : VaultRead

    /** [bytes] is the secret. The caller clears it once read. */
    class Found(val bytes: ByteArray) : VaultRead {
        override fun toString(): String = "Found(<hidden>)"
    }

    /** It could not be read: locked, refused, missing an entitlement, not this Mac. Nothing may be decided from it. */
    data object Failed : VaultRead
}

/**
 * Where a signed build keeps its secrets (`KMP-95f`): the Mac's Keychain, through [MacKeychain]. Each secret is one item
 * under a fixed [name]; a test passes a fake.
 *
 * Nothing here logs, prints or throws a name or a value: a failure is only false or [VaultRead.Failed].
 */
internal interface SecretVault {
    fun read(name: String): VaultRead

    /** Keeps [value] under [name], replacing what was there. False when it could not: nothing is kept anywhere else. */
    fun write(name: String, value: ByteArray): Boolean

    /** Removes [name]. True when it is gone, including when it was never there. */
    fun delete(name: String): Boolean
}

/** Told to `onSaveFailed` when the Keychain refused a write. It carries no message: nothing about the item is said. */
internal class KeychainWriteFailed : Exception()
