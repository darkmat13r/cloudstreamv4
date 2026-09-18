package com.lagradost.cloudstream4.core.storage

/**
 * A [SecretStore] in a map, for tests and previews.
 *
 * [protection] is [SecretProtection.PLAINTEXT] and that is not a placeholder: the values are in a
 * `MutableMap` in this process's heap, readable by anything in the process and visible in a heap
 * dump. It reports what it does, like every other implementation.
 */
class InMemorySecretStore(
    initialSecrets: Map<String, String> = emptyMap(),
) : SecretStore {

    private val secrets: MutableMap<String, String> = initialSecrets.toMutableMap()

    override val protection: SecretProtection = SecretProtection.PLAINTEXT

    override fun get(key: String): String? = secrets[key]

    override fun set(key: String, secret: String) {
        secrets[key] = secret
    }

    override fun remove(key: String) {
        secrets.remove(key)
    }

    override fun contains(key: String): Boolean = secrets.containsKey(key)

    override fun keys(): Set<String> = secrets.keys.toSet()

    override fun clear() {
        secrets.clear()
    }
}
