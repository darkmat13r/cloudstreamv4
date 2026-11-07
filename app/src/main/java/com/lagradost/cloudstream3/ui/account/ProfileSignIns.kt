package com.lagradost.cloudstream3.ui.account

import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKeys
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.syncproviders.AccountManager
import com.lagradost.cloudstream3.utils.DataStoreHelper

/**
 * `BUG-48`: a profile's sign-ins are not under its own `<keyIndex>/` folder but under
 * `auth_tokens/<provider>/<keyIndex>` and `auth_ids/<provider>/<keyIndex>`, so deleting a profile left them behind, and
 * the next profile given that index was signed in as the deleted one.
 *
 * Three defences, each enough alone for its own case:
 * - [removeFor] when a profile is deleted;
 * - [nextKeyIndex] never hands out an index that still has a sign-in;
 * - [removeOrphans] at startup clears sign-ins that no profile can reach: those left by deletes before this fix, or
 *   detached by a backup restore (a backup carries the profile list but not the sign-ins).
 *
 * Indices match on the key's exact last segment, so profile 14242 keeps its sign-ins when 4242 goes. Nothing here
 * reads or logs a value; only keys are touched.
 */
internal object ProfileSignIns {
    private val folders = listOf(AccountManager.ACCOUNT_TOKEN, AccountManager.ACCOUNT_IDS)

    /** The default profile: always index 0, synthesized when absent from the stored list (a fresh install). */
    private const val DEFAULT_INDEX = 0

    private fun signInKeys(): List<String> = folders.flatMap { getKeys(it).orEmpty() }

    private fun indexOf(key: String): Int? = key.substringAfterLast('/').toIntOrNull()

    /** Removes every sign-in of the profile at [keyIndex]. */
    fun removeFor(keyIndex: Int) {
        signInKeys().filter { indexOf(it) == keyIndex }.forEach { removeKey(it) }
    }

    /** One past the highest index any profile or any sign-in uses, so a new profile never inherits a sign-in. */
    fun nextKeyIndex(accounts: List<DataStoreHelper.Account>): Int {
        val highestProfile = accounts.maxOfOrNull { it.keyIndex } ?: DEFAULT_INDEX
        val highestSignIn = signInKeys().mapNotNull(::indexOf).maxOrNull() ?: DEFAULT_INDEX
        return maxOf(highestProfile, highestSignIn) + 1
    }

    /**
     * Removes sign-ins whose index belongs to no profile: not the default (0), not in the stored list, and not the
     * selected one. Returns how many keys went.
     */
    fun removeOrphans(): Int {
        val reachable = DataStoreHelper.accounts.map { it.keyIndex }.toSet() +
            DEFAULT_INDEX + DataStoreHelper.selectedKeyIndex
        val orphans = signInKeys().filter { key -> indexOf(key)?.let { it !in reachable } ?: false }
        orphans.forEach { removeKey(it) }
        return orphans.size
    }
}
