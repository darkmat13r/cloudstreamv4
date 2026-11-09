package com.lagradost.cloudstream4.account

import androidx.compose.runtime.mutableIntStateOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class StoredSignIns(val profiles: Map<String, Map<String, StoredCredential>> = emptyMap())

@Serializable
private data class StoredCredential(
    val token: String,
    val email: String? = null,
    val password: String? = null,
    val refreshToken: String? = null,
)

/**
 * A signed build's sign-ins (`KMP-95f`): every profile's, as one Keychain item, `sign-ins`, so they outlive a quit. The
 * OpenSubtitles password is kept there beside its token (the owner's ruling, 2026-11-09), as `KMP-95d` held it in memory.
 *
 * - **It fails closed** (the PM, 2026-11-09). A write the Keychain refuses changes nothing and returns false: that
 *   sign-in is not signed in, and nothing is kept in memory, a file or anywhere else in its place.
 * - **"Couldn't read" is never "none"**, as with the PINs. Until the item can be read, no sign-in shows and every change
 *   is refused, so a change never writes back a Keychain item holding only one profile's sign-ins, wiping the others'.
 *   Each change tries the read again.
 * - What is read back is checked as a token from a service is (`HttpSignInClient.isToken`); one that is not is dropped.
 * - Nothing is logged; [toString] names nothing.
 *
 * Not thread-safe: the window calls it from its one UI thread, as [InMemorySignInStore].
 */
internal class KeychainSignInStore(
    private val vault: SecretVault,
    private val onSaveFailed: (Throwable) -> Unit = {},
) : SignInStore {
    /** Null while the item could not be read. */
    private var held: Map<Int, Map<DesktopService, Credential>>? = load()

    /** Bumped on every change, so a screen reading [get] recomposes. */
    private val changes = mutableIntStateOf(0)

    override val lastsAfterQuit: Boolean get() = true

    override fun get(profile: Int, service: DesktopService): Credential? {
        changes.intValue
        return held?.get(profile)?.get(service)
    }

    override fun put(profile: Int, service: DesktopService, credential: Credential): Boolean {
        if (!isKept(credential)) return false
        val now = current() ?: return false
        val mine = now[profile].orEmpty() + (service to credential)
        return save(now + (profile to mine))
    }

    override fun remove(profile: Int, service: DesktopService): Boolean {
        val now = current() ?: return false
        val mine = now[profile] ?: return true
        if (service !in mine) return true
        val left = mine - service
        return save(if (left.isEmpty()) now - profile else now + (profile to left))
    }

    override fun forget(profile: Int): Boolean {
        val now = current() ?: return false
        if (profile !in now) return true
        return save(now - profile)
    }

    override fun retainOnly(profiles: Set<Int>): Boolean {
        val now = current() ?: return false
        if (profiles.containsAll(now.keys)) return true
        return save(now.filterKeys { it in profiles })
    }

    override fun toString(): String = "KeychainSignInStore(<hidden>)"

    /** What is held, reading the item again if it could not be read before. */
    private fun current(): Map<Int, Map<DesktopService, Credential>>? {
        if (held == null) {
            held = load()
            if (held != null) changes.intValue++
        }
        return held
    }

    private fun save(next: Map<Int, Map<DesktopService, Credential>>): Boolean {
        val stored = StoredSignIns(
            next.filterValues { it.isNotEmpty() }.map { (profile, services) ->
                profile.toString() to services.map { (service, c) ->
                    service.name to StoredCredential(c.token, c.email, c.password, c.refreshToken)
                }.toMap()
            }.toMap(),
        )
        val bytes = json.encodeToString(StoredSignIns.serializer(), stored).toByteArray()
        val kept = try {
            vault.write(ITEM, bytes)
        } finally {
            bytes.fill(0)
        }
        if (!kept) {
            onSaveFailed(KeychainWriteFailed())
            return false
        }
        held = next.filterValues { it.isNotEmpty() }
        changes.intValue++
        return true
    }

    private fun load(): Map<Int, Map<DesktopService, Credential>>? = when (val item = vault.read(ITEM)) {
        VaultRead.Absent -> emptyMap()
        VaultRead.Failed -> null
        is VaultRead.Found -> try {
            if (item.bytes.size > MacKeychain.MAX_BYTES) null else parse(item.bytes.decodeToString())
        } finally {
            item.bytes.fill(0)
        }
    }

    private fun parse(text: String): Map<Int, Map<DesktopService, Credential>>? {
        val stored = runCatching { json.decodeFromString(StoredSignIns.serializer(), text) }.getOrNull() ?: return null
        return stored.profiles.mapNotNull { (key, services) ->
            val profile = key.toIntOrNull()?.takeIf { it in 0 until DesktopProfileFile.MAX_ID } ?: return@mapNotNull null
            val kept = services.mapNotNull { (name, c) ->
                val service = DesktopService.entries.firstOrNull { it.name == name } ?: return@mapNotNull null
                val credential = Credential(c.token, c.email, c.password, c.refreshToken)
                if (isKept(credential)) service to credential else null
            }.toMap()
            if (kept.isEmpty()) null else profile to kept
        }.toMap()
    }

    private fun isKept(credential: Credential): Boolean =
        HttpSignInClient.isToken(credential.token) && credential.refreshToken?.let(HttpSignInClient::isToken) != false &&
            listOfNotNull(credential.email, credential.password).all { it.length <= MAX_FIELD_CHARS }

    internal companion object {
        /** The Keychain item that holds every profile's sign-ins. */
        const val ITEM = "sign-ins"
        private const val MAX_FIELD_CHARS = 1_024
        private val json = Json { ignoreUnknownKeys = true }
    }
}
