package com.lagradost.cloudstream4.account

/**
 * `KMP-95f`: the Keychain for tests, in memory. It can be told to refuse reads, writes or deletes, or to give back other
 * bytes than were written, and it counts what it was asked. Nothing in a test reaches the real Keychain.
 */
internal class FakeVault : SecretVault {
    val items = HashMap<String, ByteArray>()
    var refuseReads = false
    var refuseWrites = false
    var refuseDeletes = false

    /** Once anything is written, gives back these bytes on a read instead, as a read-back that does not match. */
    var readBack: ByteArray? = null
    var reads = 0
    var writes = 0

    fun text(name: String): String? = items[name]?.decodeToString()

    override fun read(name: String): VaultRead {
        reads++
        if (refuseReads) return VaultRead.Failed
        readBack?.takeIf { writes > 0 }?.let { return VaultRead.Found(it.copyOf()) }
        return items[name]?.let { VaultRead.Found(it.copyOf()) } ?: VaultRead.Absent
    }

    override fun write(name: String, value: ByteArray): Boolean {
        writes++
        if (refuseWrites) return false
        items[name] = value.copyOf()
        return true
    }

    override fun delete(name: String): Boolean {
        if (refuseDeletes) return false
        items.remove(name)
        return true
    }
}
